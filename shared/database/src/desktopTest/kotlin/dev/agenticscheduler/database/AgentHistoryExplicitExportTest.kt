package dev.agenticscheduler.database

import androidx.room3.useReaderConnection
import androidx.room3.useWriterConnection
import dev.agenticscheduler.agent.history.*
import dev.agenticscheduler.agent.permission.AgentPermissionMode
import dev.agenticscheduler.application.id.EpochMillisecondsClock
import dev.agenticscheduler.application.id.UuidV7Generator
import dev.agenticscheduler.application.sync.*
import dev.agenticscheduler.database.repository.RoomAgentHistoryExportSource
import dev.agenticscheduler.database.repository.RoomAgentStateRepository
import dev.agenticscheduler.database.repository.RoomAgentSyncPersistence
import dev.agenticscheduler.database.repository.RoomAgentSyncTransportPersistence
import dev.agenticscheduler.sync.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Test
import java.nio.file.Files
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AgentHistoryExplicitExportTest {
    @Test fun `only explicit verified export queues sanitized immutable facts and survives retry after restart`() = runBlocking {
        val path = Files.createTempFile("agent-explicit-history-", ".db")
        val space = SyncSpaceId("explicit-export")
        val replica = AgentReplicaId(id(70))
        val thread = AgentThread(AgentThreadId(id(1)), "tracked", 10)
        val user = AgentMessage(AgentMessageId(id(2)), thread.id, 0, AgentMessageRole.USER, "Create a task", 11)
        val assistant = AgentMessage(AgentMessageId(id(3)), thread.id, 1, AgentMessageRole.ASSISTANT, "Creating it", 12)
        val call = AgentToolCall(AgentToolCallId(id(4)), thread.id, assistant.id, 0, "task.create", "{\"title\":\"work\"}",
            AgentToolCallState.COMPLETED, previewJson = "PRIVATE_PREVIEW", providerCallId = "PRIVATE_PROVIDER_CALL_ID")
        val result = AgentToolResult(AgentToolResultId(id(5)), thread.id, call.id, 0, AgentToolResultStatus.SUCCESS,
            "{\"ok\":true}", listOf(MutationId(id(6))))
        val action = AgentAction(AgentActionId(id(7)), thread.id, assistant.id, ProviderConfigId(id(8)), "PRIVATE_MODEL",
            listOf(call.id), listOf(result.id), AgentPermissionMode.ALLOW_DIRECT, "PRIVATE_CONFIRMATION", "PRIVATE_BRANCH",
            listOf(MutationId(id(6))), AgentActionStatus.SUCCEEDED)
        val enrollment = TestEnrollment(space)
        val ids = object : UuidV7Generator { var nextId = 100; override fun next() = id(nextId++) }
        var now = 1000L
        try {
            var db = openDesktopDatabase(path.toAbsolutePath().toString())
            var state = RoomAgentStateRepository(db)
            var sync = RoomAgentSyncPersistence(db)
            var transport = RoomAgentSyncTransportPersistence(db)
            sync.provisionLocalReplica(space, replica)
            state.saveThread(thread)
            state.beginLocalHistoryTurn(thread.id, id(9))
            state.appendMessage(user)
            state.appendMessage(assistant)
            state.saveToolCall(call)
            state.appendToolResult(result)
            state.saveAction(action)
            assertTrue(state.finalizeLocalHistoryTurn(thread.id, id(9), AgentLocalTurnOutcome.SUCCEEDED))
            transport.setConversationConsent(space, true, true)
            val exporter = exporter(db, enrollment, transport, sync, state, ids) { now }
            assertEquals(1, exporter.availability(space).eligibleTurns)
            assertEquals(0L, db.useReaderConnection { it.usePrepared("SELECT count(*) FROM agent_sync_outbox") { statement -> statement.step(); statement.getLong(0) } },
                "Consent alone must not queue any history.")

            val first = exporter.exportFromUser(space)
            assertEquals(7, first.newlyQueuedFacts)
            assertEquals(0, first.previouslyQueuedFacts)
            val outboundBeforeRestart = sync.historicalExportMappings(space)
            assertEquals(7, outboundBeforeRestart.size)
            val serialized = outboundBeforeRestart.joinToString("\n") { Json.encodeToString(AgentSyncEvent.serializer(), it.event) }
            listOf("PRIVATE_PREVIEW", "PRIVATE_PROVIDER_CALL_ID", "PRIVATE_MODEL", "PRIVATE_CONFIRMATION", "PRIVATE_BRANCH").forEach {
                assertFalse(it in serialized, "Private local metadata leaked into V3 event DTO: $it")
            }
            assertTrue("PRIVATE_MODEL" in Json.encodeToString(AgentAction.serializer(), action), "Test must contain excluded local provider metadata.")
            val firstOperationIds = outboundBeforeRestart.map { sync.operation(space, it.operationId.value)!!.operationId.value }
            db.close()

            now = 2000L
            db = openDesktopDatabase(path.toAbsolutePath().toString())
            state = RoomAgentStateRepository(db)
            sync = RoomAgentSyncPersistence(db)
            transport = RoomAgentSyncTransportPersistence(db)
            val retry = exporter(db, enrollment, transport, sync, state, ids) { now }.exportFromUser(space)
            assertEquals(0, retry.newlyQueuedFacts)
            assertEquals(7, retry.previouslyQueuedFacts)
            val mappingsAfterRestart = sync.historicalExportMappings(space)
            assertEquals(outboundBeforeRestart, mappingsAfterRestart)
            assertEquals(firstOperationIds, mappingsAfterRestart.map { it.operationId.value })
            assertEquals(7L, db.useReaderConnection { it.usePrepared("SELECT count(*) FROM agent_sync_outbox") { statement -> statement.step(); statement.getLong(0) } })
            db.close()
        } finally {
            Files.deleteIfExists(path)
        }
    }

    @Test fun `legacy and crash-gap provenance remain unavailable to historical export`() = runBlocking {
        val db = openInMemoryDesktopDatabase()
        try {
            val state = RoomAgentStateRepository(db)
            val legacy = AgentThread(AgentThreadId(id(20)), "legacy", 1)
            state.saveThread(legacy)
            db.useWriterConnection { connection ->
                connection.usePrepared("UPDATE agent_local_thread_provenance SET state = 'LEGACY_UNVERIFIED', creation_title = NULL, creation_at_epoch_millis = NULL WHERE thread_id = ?") { statement ->
                    statement.bindText(1, legacy.id.value); statement.step()
                }
            }
            val tracked = AgentThread(AgentThreadId(id(21)), "new", 2)
            state.saveThread(tracked)
            assertEquals("TRACKED", db.useReaderConnection { it.usePrepared("SELECT state FROM agent_local_thread_provenance WHERE thread_id = ?") { statement -> statement.bindText(1, tracked.id.value); statement.step(); statement.getText(0) } })
            state.beginLocalHistoryTurn(tracked.id, id(22))
            assertEquals(1, state.localHistoryTurns(tracked.id).size)
            state.appendMessage(AgentMessage(AgentMessageId(id(23)), tracked.id, 0, AgentMessageRole.USER, "crash gap", 3))
            state.beginLocalHistoryTurn(tracked.id, id(24))
            state.appendMessage(AgentMessage(AgentMessageId(id(25)), tracked.id, 1, AgentMessageRole.USER, "local use continues", 4))
            assertTrue(state.finalizeLocalHistoryTurn(tracked.id, id(24), AgentLocalTurnOutcome.SUCCEEDED))
            val snapshot = RoomAgentHistoryExportSource(db, state).snapshot()
            assertEquals(1, snapshot.legacyUnverifiedThreadCount)
            assertFalse(snapshot.threads.any { it.threadId.value == legacy.id.value }, "Legacy thread must not become an export candidate.")
            val trackedSnapshot = snapshot.threads.firstOrNull { it.threadId.value == tracked.id.value }
                ?: error("Tracked provenance row was omitted from export source: ${snapshot.threads.map { it.threadId.value }}")
            assertEquals(2, trackedSnapshot.turns.size)
            assertFalse(trackedSnapshot.turns.first().finalized)
            assertTrue(trackedSnapshot.turns.last().finalized)
            assertFalse(trackedSnapshot.turns.last().ancestryVerified, "Ancestry gaps taint export only; this later local turn still completed.")
        } finally { db.close() }
    }

    private fun exporter(database: AgenticSchedulerDatabase, enrollment: LocalEnrollmentRepository, transport: RoomAgentSyncTransportPersistence,
        sync: RoomAgentSyncPersistence, state: AgentStateRepository, ids: UuidV7Generator, clock: () -> Long) =
        AgentHistoryExplicitExport(enrollment, transport, sync, sync, RoomAgentHistoryExportSource(database, state), ids,
            EpochMillisecondsClock(clock))

    private class TestEnrollment(private val space: SyncSpaceId) : LocalEnrollmentRepository {
        private val active = LocalEnrollmentState.Active(AccountId("personal"), DeviceId("desktop"), EnrollmentRequestId("request"),
            HpkePublicKeyBase64Url("AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"), SecretReference("secure://hpke"), space,
            SecretReference("secure://master"), SecretReference("secure://credential"))
        override suspend fun state(accountId: AccountId) = active.takeIf { it.accountId == accountId }
        override suspend fun states(): List<LocalEnrollmentState> = listOf(active)
        override suspend fun savePending(value: LocalEnrollmentState.Pending) = Unit
        override suspend fun saveActive(value: LocalEnrollmentState.Active) = Unit
    }

    private fun id(value: Int) = "00000000-0000-7000-8000-${value.toString().padStart(12, '0')}"
}
