package dev.agenticscheduler.application.sync

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

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
) {
    private val requests = Channel<Unit>(Channel.CONFLATED)
    private var observer: Job? = null
    private var runner: Job? = null
    private var started = false

    /** Starts one worker and observes revisions, including the initial revision. */
    fun start() {
        check(!started) { "Active sync catch-up trigger has already started." }
        started = true
        runner = scope.launch { runRequests() }
        observer = scope.launch {
            committedOutboundMutationRevision
                .distinctUntilChanged()
                .collect { requestCatchUp() }
        }
    }

    /** Safe to call on foreground entry or a platform network-available signal. */
    fun requestCatchUp() {
        if (started) requests.trySend(Unit)
    }

    /** Stops local observation and retry work; runtime deactivation is owned by the composition. */
    fun close() {
        observer?.cancel()
        runner?.cancel()
        requests.close()
    }

    private suspend fun runRequests() {
        for (ignored in requests) {
            var retryDelayMillis = INITIAL_RETRY_DELAY_MILLIS
            while (currentCoroutineContext().isActive) {
                val shouldRetry = try {
                    catchUp().shouldRetry()
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Throwable) {
                    onUnexpectedFailure()
                    true
                }
                if (!shouldRetry) break

                // A new foreground/network signal interrupts the backoff.
                withTimeoutOrNull(retryDelayMillis) { requests.receive() }
                retryDelayMillis = (retryDelayMillis * 2).coerceAtMost(MAX_RETRY_DELAY_MILLIS)
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

    private companion object {
        const val INITIAL_RETRY_DELAY_MILLIS = 1_000L
        const val MAX_RETRY_DELAY_MILLIS = 60_000L
    }
}
