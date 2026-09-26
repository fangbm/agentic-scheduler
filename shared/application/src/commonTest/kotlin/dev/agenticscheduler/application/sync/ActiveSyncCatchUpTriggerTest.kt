package dev.agenticscheduler.application.sync

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ActiveSyncCatchUpTriggerTest {
    @Test
    fun `platform periods receive independent bounded jitter`() {
        assertEquals(54_000L, ActiveSyncCatchUpTrigger.periodMillisWithJitter(60_000L, -1.0))
        assertEquals(66_000L, ActiveSyncCatchUpTrigger.periodMillisWithJitter(60_000L, 1.0))
        assertEquals(162_000L, ActiveSyncCatchUpTrigger.periodMillisWithJitter(180_000L, -1.0))
        assertEquals(198_000L, ActiveSyncCatchUpTrigger.periodMillisWithJitter(180_000L, 1.0))
    }

    @Test
    fun `foreground idle timer catches remote-only changes and pauses in background`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val calls = Channel<Unit>(Channel.UNLIMITED)
        val periods = Channel<Long>(Channel.UNLIMITED)
        val allowTick = Channel<Unit>(Channel.CONFLATED)
        val trigger = ActiveSyncCatchUpTrigger(
            scope = scope,
            committedOutboundMutationRevision = emptyFlow(),
            catchUp = {
                calls.send(Unit)
                completedCatchUp()
            },
            pollingIntervalMillis = ActiveSyncCatchUpTrigger.DESKTOP_ANDROID_POLL_INTERVAL_MILLIS,
            jitterUnit = { 0.0 },
            waitMillis = { millis ->
                periods.send(millis)
                allowTick.receive()
            },
        )

        try {
            trigger.start()
            trigger.setForeground(true)
            // Foreground entry is immediate even before the first idle period.
            withTimeout(2_000) { calls.receive() }
            assertEquals(60_000L, withTimeout(2_000) { periods.receive() })

            // No local revision is emitted: the periodic request is what pulls
            // a remote-only change from the runtime.
            allowTick.send(Unit)
            withTimeout(2_000) { calls.receive() }
            assertEquals(60_000L, withTimeout(2_000) { periods.receive() })

            trigger.setForeground(false)
            assertNull(withTimeoutOrNull(100) { periods.receive() })
            assertNull(withTimeoutOrNull(100) { calls.receive() })
        } finally {
            trigger.close()
            scope.cancel()
        }
    }

    @Test
    fun `transient failure retries with bounded backoff`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val calls = Channel<Int>(Channel.UNLIMITED)
        var count = 0
        val trigger = ActiveSyncCatchUpTrigger(
            scope = scope,
            committedOutboundMutationRevision = flowOf(0L),
            catchUp = {
                count += 1
                calls.send(count)
                if (count == 1) {
                    ActiveSyncRuntimeCatchUpResult.Completed(
                        emptyList(),
                        SyncTransportRunResult(0, 0, 0, SyncUploadResult.RetryableFailure("offline")),
                    )
                } else completedCatchUp()
            },
            initialRetryDelayMillis = 10,
            maxRetryDelayMillis = 20,
        )

        try {
            trigger.start()
            assertEquals(1, withTimeout(2_000) { calls.receive() })
            assertEquals(2, withTimeout(2_000) { calls.receive() })
        } finally {
            trigger.close()
            scope.cancel()
        }
    }

    @Test
    fun `nonretryable failure stops periodic retries until explicit retry`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val calls = Channel<Int>(Channel.UNLIMITED)
        val reasons = Channel<String>(Channel.UNLIMITED)
        val periods = Channel<Long>(Channel.UNLIMITED)
        val allowTick = Channel<Unit>(Channel.CONFLATED)
        var count = 0
        val trigger = ActiveSyncCatchUpTrigger(
            scope = scope,
            // Model the startup subscription so the worker is active before
            // the terminal result and later foreground/poll signals arrive.
            committedOutboundMutationRevision = flowOf(0L),
            catchUp = {
                count += 1
                calls.send(count)
                if (count == 1) {
                    ActiveSyncRuntimeCatchUpResult.Completed(
                        emptyList(),
                        SyncTransportRunResult(0, 0, 0, SyncUploadResult.NonRetryableFailure("UNAUTHORIZED")),
                    )
                } else completedCatchUp()
            },
            onNonRetryableFailure = { reasons.trySend(it) },
            pollingIntervalMillis = 10,
            jitterUnit = { 0.0 },
            waitMillis = { millis ->
                periods.send(millis)
                allowTick.receive()
            },
        )

        try {
            trigger.start()
            assertEquals(1, withTimeout(2_000) { calls.receive() })
            assertEquals("UNAUTHORIZED", withTimeout(2_000) { reasons.receive() })
            trigger.setForeground(true)
            assertEquals(10L, withTimeout(2_000) { periods.receive() })
            allowTick.send(Unit)
            assertNull(withTimeoutOrNull(100) { calls.receive() })

            trigger.retryNow()
            assertEquals(2, withTimeout(2_000) { calls.receive() })
        } finally {
            trigger.close()
            scope.cancel()
        }
    }

    private fun completedCatchUp() = ActiveSyncRuntimeCatchUpResult.Completed(
        rotations = emptyList(),
        transport = SyncTransportRunResult(0, 0, 0),
    )
}
