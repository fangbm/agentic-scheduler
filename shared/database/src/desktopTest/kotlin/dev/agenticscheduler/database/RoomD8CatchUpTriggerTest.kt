package dev.agenticscheduler.database

import androidx.room3.withWriteTransaction
import dev.agenticscheduler.application.editing.CreateTaskInput
import dev.agenticscheduler.application.editing.EditingResult
import dev.agenticscheduler.application.editing.TaskEditingService
import dev.agenticscheduler.application.history.MutationCoordinator
import dev.agenticscheduler.application.history.MutationWallClock
import dev.agenticscheduler.application.history.NoActiveSyncSpaceWritePolicy
import dev.agenticscheduler.application.id.productionUuidV7Generator
import dev.agenticscheduler.application.sync.ActiveSyncCatchUpTrigger
import dev.agenticscheduler.application.sync.ActiveSyncRuntimeCatchUpResult
import dev.agenticscheduler.application.sync.SyncTransportRunResult
import dev.agenticscheduler.application.sync.SyncUploadResult
import dev.agenticscheduler.database.record.MutationRecord
import dev.agenticscheduler.database.repository.RoomApplicationTransactionRunner
import dev.agenticscheduler.database.repository.RoomMutationJournalRepository
import dev.agenticscheduler.database.repository.RoomTaskRepository
import dev.agenticscheduler.domain.task.TaskPriority
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.yield
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class RoomD8CatchUpTriggerTest {
    @Test
    fun `initial revision emission catches up mutations committed before first collection`(): Unit {
        kotlinx.coroutines.runBlocking {
        val databaseFile = File.createTempFile("agentic-d8-trigger-initial-", ".db")
        val database = openDesktopDatabase(databaseFile.absolutePath)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val calls = Channel<Unit>(Channel.UNLIMITED)
        val ids = productionUuidV7Generator()
        val coordinator = MutationCoordinator(
            RoomApplicationTransactionRunner(database),
            RoomMutationJournalRepository(database),
            ids,
            MutationWallClock { 100L },
        )
        val taskEditor = TaskEditingService(
            RoomTaskRepository(database),
            ids,
            coordinator,
            NoActiveSyncSpaceWritePolicy,
        )
        val trigger = ActiveSyncCatchUpTrigger(
            scope = scope,
            committedOutboundMutationRevision = database.mutationJournalDao().observeOutboundMutationRevision(),
            catchUp = {
                calls.send(Unit)
                ActiveSyncRuntimeCatchUpResult.Completed(emptyList(), SyncTransportRunResult(0, 0, 0))
            },
        )

        try {
            assertIs<EditingResult.Success<*>>(
                taskEditor.create(CreateTaskInput("Committed before subscription", TaskPriority.NORMAL, null, null, null)),
            )

            trigger.start()

            // The initial value of the single revision subscription is the
            // startup signal; it must include rows committed before collection.
            withTimeout(5_000) { calls.receive() }
            assertEquals(null, withTimeoutOrNull(300) { calls.receive() })
        } finally {
            trigger.close()
            scope.cancel()
            database.close()
            databaseFile.delete()
        }
        }
    }

    @Test
    fun `committed local mutation schedules serialized catch-up and explicit retry is callable`(): Unit {
        kotlinx.coroutines.runBlocking {
        val databaseFile = File.createTempFile("agentic-d8-trigger-", ".db")
        val database = openDesktopDatabase(databaseFile.absolutePath)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val calls = Channel<Int>(Channel.UNLIMITED)
        val entered = CompletableDeferred<Unit>()
        val releaseFirst = CompletableDeferred<Unit>()
        val activeRuns = AtomicInteger(0)
        val maximumConcurrentRuns = AtomicInteger(0)
        val invocation = AtomicInteger(0)

        val trigger = ActiveSyncCatchUpTrigger(
            scope = scope,
            committedOutboundMutationRevision = database.mutationJournalDao().observeOutboundMutationRevision(),
            catchUp = {
                val concurrent = activeRuns.incrementAndGet()
                maximumConcurrentRuns.updateAndGet { previous -> maxOf(previous, concurrent) }
                try {
                    val current = invocation.incrementAndGet()
                    calls.send(current)
                    if (current == 1) {
                        entered.complete(Unit)
                        releaseFirst.await()
                    }
                    ActiveSyncRuntimeCatchUpResult.Completed(
                        emptyList(),
                        SyncTransportRunResult(
                            0,
                            0,
                            0,
                            stoppedOnFailure = if (current == 1) SyncUploadResult.RetryableFailure("offline") else null,
                        ),
                    )
                } finally {
                    activeRuns.decrementAndGet()
                }
            },
        )

        try {
            trigger.start()
            withTimeout(5_000) { entered.await() }
            assertEquals(1, withTimeout(5_000) { calls.receive() })

            val ids = productionUuidV7Generator()
            val coordinator = MutationCoordinator(
                RoomApplicationTransactionRunner(database),
                RoomMutationJournalRepository(database),
                ids,
                MutationWallClock { 100L },
            )
            val taskEditor = TaskEditingService(
                RoomTaskRepository(database),
                ids,
                coordinator,
                NoActiveSyncSpaceWritePolicy,
            )

            assertIs<EditingResult.Success<*>>(
                taskEditor.create(CreateTaskInput("Committed locally", TaskPriority.NORMAL, null, null, null)),
            )

            // A committed mutation arriving during catch-up is queued, not run
            // concurrently with the network/database work already in progress.
            yield()
            assertEquals(1, invocation.get())
            releaseFirst.complete(Unit)
            assertEquals(2, withTimeout(5_000) { calls.receive() })

            trigger.requestCatchUp()
            assertEquals(3, withTimeout(5_000) { calls.receive() })
            assertEquals(1, maximumConcurrentRuns.get())
        } finally {
            trigger.close()
            scope.cancel()
            database.close()
            databaseFile.delete()
        }
        }
    }

    @Test
    fun `receive-only journal rows do not trigger outbound catch-up`(): Unit {
        kotlinx.coroutines.runBlocking {
        val databaseFile = File.createTempFile("agentic-d8-trigger-inbound-", ".db")
        val database = openDesktopDatabase(databaseFile.absolutePath)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val calls = Channel<Unit>(Channel.UNLIMITED)
        val trigger = ActiveSyncCatchUpTrigger(
            scope,
            database.mutationJournalDao().observeOutboundMutationRevision(),
            {
                calls.send(Unit)
                ActiveSyncRuntimeCatchUpResult.Completed(emptyList(), SyncTransportRunResult(0, 0, 0))
            },
        )
        try {
            trigger.start()
            withTimeout(5_000) { calls.receive() }
            database.withWriteTransaction {
                database.mutationJournalDao().insertMutationRecord(
                    MutationRecord(
                        mutationId = "remote-only",
                        origin = "USER",
                        dvvJson = "{}",
                        hlcPhysicalMillis = 1,
                        hlcLogical = 0,
                        hlcReplicaId = "remote",
                        committedAtEpochMillis = 1,
                        outboundEligible = false,
                    ),
                )
            }
            assertEquals(null, withTimeoutOrNull(300) { calls.receive() })
        } finally {
            trigger.close()
            scope.cancel()
            database.close()
            databaseFile.delete()
        }
        }
    }
}
