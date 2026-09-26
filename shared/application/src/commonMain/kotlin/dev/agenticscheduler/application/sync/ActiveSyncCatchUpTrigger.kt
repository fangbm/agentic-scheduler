package dev.agenticscheduler.application.sync

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.random.Random

/**
 * Serializes production catch-up requests from app start, committed local
 * mutations, and explicit lifecycle/network retry signals. The initial value
 * from the same distinct revision subscription also requests startup catch-up,
 * so commits made before collection begins are included.
 *
 * The observed revision is backed by committed outbound-eligible journal rows,
 * so receive-only writes do not recursively schedule uploads. Network failures
 * retry with bounded exponential delay while the app scope remains active;
 * [requestCatchUp] interrupts that delay when connectivity returns.
 */
class ActiveSyncCatchUpTrigger(
    private val scope: CoroutineScope,
    private val committedOutboundMutationRevision: Flow<Long>,
    private val catchUp: suspend () -> ActiveSyncRuntimeCatchUpResult?,
    private val onUnexpectedFailure: () -> Unit = {},
    private val onNonRetryableFailure: (String) -> Unit = {},
    private val pollingIntervalMillis: Long? = null,
    private val jitterUnit: () -> Double = { Random.Default.nextDouble(-1.0, 1.0) },
    private val waitMillis: suspend (Long) -> Unit = { delay(it) },
    private val initialRetryDelayMillis: Long = INITIAL_RETRY_DELAY_MILLIS,
    private val maxRetryDelayMillis: Long = MAX_RETRY_DELAY_MILLIS,
) {
    private enum class RequestKind { AUTOMATIC, EXPLICIT_RETRY }

    private val requests = Channel<RequestKind>(Channel.CONFLATED)
    private val foreground = MutableStateFlow(false)
    private var observer: Job? = null
    private var runner: Job? = null
    private var periodic: Job? = null
    private var started = false
    private val stoppedOnNonRetryableFailure = MutableStateFlow(false)

    init {
        require(pollingIntervalMillis == null || pollingIntervalMillis > 0)
        require(initialRetryDelayMillis > 0)
        require(maxRetryDelayMillis >= initialRetryDelayMillis)
    }

    /** Starts one worker and observes revisions, including the initial revision. */
    fun start() {
        check(!started) { "Active sync catch-up trigger has already started." }
        started = true
        runner = scope.launch { runRequests() }
        observer = scope.launch {
            committedOutboundMutationRevision
                .distinctUntilChanged()
                .collect { requestAutomaticCatchUp() }
        }
        if (pollingIntervalMillis != null) {
            periodic = scope.launch {
                foreground.collectLatest { isForeground ->
                    if (isForeground) {
                        while (currentCoroutineContext().isActive) {
                            val jitter = jitterUnit().coerceIn(-1.0, 1.0)
                            val period = periodMillisWithJitter(pollingIntervalMillis, jitter)
                            waitMillis(period)
                            requestAutomaticCatchUp()
                        }
                    }
                }
            }
        }
    }

    /** Immediate signal for startup, local commit, foreground entry, or network recovery. */
    fun requestCatchUp() {
        requestAutomaticCatchUp()
    }

    /** Foreground entry requests immediate catch-up; leaving foreground pauses only periodic work. */
    fun setForeground(isForeground: Boolean) {
        if (!started || foreground.value == isForeground) return
        foreground.value = isForeground
        if (isForeground) requestAutomaticCatchUp()
    }

    /** An explicit user retry may restart a worker stopped by an auth/integrity failure. */
    fun retryNow() {
        if (started) {
            stoppedOnNonRetryableFailure.value = false
            requests.trySend(RequestKind.EXPLICIT_RETRY)
        }
    }

    /** Stops local observation and retry work; runtime deactivation is owned by the composition. */
    fun close() {
        observer?.cancel()
        runner?.cancel()
        periodic?.cancel()
        requests.close()
    }

    private fun requestAutomaticCatchUp() {
        if (started && !stoppedOnNonRetryableFailure.value) requests.trySend(RequestKind.AUTOMATIC)
    }

    private suspend fun runRequests() {
        for (request in requests) {
            if (request == RequestKind.EXPLICIT_RETRY) stoppedOnNonRetryableFailure.value = false
            if (stoppedOnNonRetryableFailure.value) continue
            var retryDelayMillis = initialRetryDelayMillis
            while (currentCoroutineContext().isActive) {
                var unexpectedFailure = false
                val result = try {
                    catchUp()
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Throwable) {
                    onUnexpectedFailure()
                    unexpectedFailure = true
                    null
                }
                val failure = result.nonRetryableFailure()
                if (failure != null) {
                    stoppedOnNonRetryableFailure.value = true
                    onNonRetryableFailure(failure)
                    break
                }
                if (!unexpectedFailure && !result.shouldRetry()) break

                // A new foreground/network signal interrupts the backoff.
                val incoming = withTimeoutOrNull(retryDelayMillis) { requests.receive() }
                if (incoming == RequestKind.EXPLICIT_RETRY) stoppedOnNonRetryableFailure.value = false
                if (stoppedOnNonRetryableFailure.value) break
                retryDelayMillis = (retryDelayMillis * 2).coerceAtMost(maxRetryDelayMillis)
            }
        }
    }

    private fun ActiveSyncRuntimeCatchUpResult?.shouldRetry(): Boolean = when (this) {
        is ActiveSyncRuntimeCatchUpResult.RotationFailed ->
            result == RotationPackageCatchUpResult.FetchFailed
        is ActiveSyncRuntimeCatchUpResult.Completed ->
            transport.stoppedOnFailure is SyncUploadResult.RetryableFailure
        null -> false
    }

    private fun ActiveSyncRuntimeCatchUpResult?.nonRetryableFailure(): String? = when (this) {
        is ActiveSyncRuntimeCatchUpResult.RotationFailed -> when (result) {
            RotationPackageCatchUpResult.FetchFailed -> null
            is RotationPackageCatchUpResult.FetchRejected -> result.reason
            is RotationPackageCatchUpResult.InvalidRelayPackage -> "ROTATION_PACKAGE_INTEGRITY_FAILURE"
            is RotationPackageCatchUpResult.ApplyFailed -> "ROTATION_PACKAGE_APPLY_FAILURE"
            is RotationPackageCatchUpResult.Completed -> null
        }
        is ActiveSyncRuntimeCatchUpResult.Completed -> when {
            transport.stoppedOnReceiveFailure != null -> "SYNC_RECEIVE_FAILURE"
            transport.stoppedOnFailure is SyncUploadResult.IntegrityConflict -> "SYNC_INTEGRITY_FAILURE"
            transport.stoppedOnFailure is SyncUploadResult.NonRetryableFailure ->
                (transport.stoppedOnFailure as SyncUploadResult.NonRetryableFailure).detail
            else -> null
        }
        null -> null
    }

    companion object {
        const val DESKTOP_ANDROID_POLL_INTERVAL_MILLIS = 60_000L
        const val WEAR_POLL_INTERVAL_MILLIS = 180_000L
        const val JITTER_FRACTION = 0.10
        const val INITIAL_RETRY_DELAY_MILLIS = 1_000L
        const val MAX_RETRY_DELAY_MILLIS = 60_000L

        fun periodMillisWithJitter(baseMillis: Long, jitterUnit: Double): Long {
            require(baseMillis > 0)
            require(jitterUnit in -1.0..1.0)
            return (baseMillis * (1.0 + JITTER_FRACTION * jitterUnit)).toLong().coerceAtLeast(1L)
        }
    }

}
