package dev.agenticscheduler.database

import androidx.room3.testing.MigrationTestHelper
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import dev.agenticscheduler.agent.history.*
import dev.agenticscheduler.agent.permission.AgentPermissionPolicy
import dev.agenticscheduler.agent.permission.AgentSyncWriteGate
import dev.agenticscheduler.application.sync.LocalEnrollmentRepository
import dev.agenticscheduler.application.sync.LocalEnrollmentState
import dev.agenticscheduler.application.editing.CreateTaskInput
import dev.agenticscheduler.application.editing.EditingResult
import dev.agenticscheduler.application.editing.TaskEditingService
import dev.agenticscheduler.application.history.AgentOriginWriteGate
import dev.agenticscheduler.application.history.MutationCoordinator
import dev.agenticscheduler.application.history.MutationWallClock
import dev.agenticscheduler.application.history.NoActiveSyncSpaceWritePolicy
import dev.agenticscheduler.application.id.EpochMillisecondsClock
import dev.agenticscheduler.application.id.RandomBytes
import dev.agenticscheduler.application.id.RfcUuidV7Generator
import dev.agenticscheduler.application.sync.SecretReference
import dev.agenticscheduler.database.repository.RoomApplicationTransactionRunner
import dev.agenticscheduler.database.repository.RoomAgentStateRepository
import dev.agenticscheduler.database.repository.RoomMutationJournalRepository
import dev.agenticscheduler.database.repository.RoomTaskRepository
import dev.agenticscheduler.domain.task.Task
import dev.agenticscheduler.domain.task.TaskPriority
import dev.agenticscheduler.sync.MutationId
import dev.agenticscheduler.sync.AccountId
import dev.agenticscheduler.sync.DeviceId
import dev.agenticscheduler.sync.EnrollmentRequestId
import dev.agenticscheduler.sync.HpkePublicKeyBase64Url
import dev.agenticscheduler.sync.SyncSpaceId
import dev.agenticscheduler.sync.MutationOrigin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import org.junit.Rule
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.assertIs
import kotlin.test.assertFailsWith

class AgentPersistenceTest {
    @get:Rule val migrations = MigrationTestHelper(
        Path.of("schemas"), Files.createTempFile("agent-v12-migration-", ".db"),
        BundledSQLiteDriver(), AgenticSchedulerDatabase::class,
        { AgenticSchedulerDatabaseConstructor.initialize() },
    )

    @Test fun `fresh v12 keeps audit when a thread is deleted`() = runBlocking {
        val database = openInMemoryDesktopDatabase()
        try {
            val repository = RoomAgentStateRepository(database)
            val thread = AgentThread(AgentThreadId(id(1)), "Schedule", 100)
            val message = AgentMessage(AgentMessageId(id(2)), thread.id, 0, AgentMessageRole.USER, "Move my task", 101)
            val call = AgentToolCall(AgentToolCallId(id(3)), thread.id, message.id, 0, "task.update", "{}", AgentToolCallState.COMPLETED)
            val result = AgentToolResult(AgentToolResultId(id(4)), thread.id, call.id, 0, AgentToolResultStatus.SUCCESS, "{}", listOf(MutationId(id(7))))
            val summary = ContextSummary(ContextSummaryId(id(5)), thread.id, message.id, message.id, 1, "Earlier request", 102, null, null)
            val action = AgentAction(AgentActionId(id(6)), thread.id, message.id, null, null, listOf(call.id), listOf(result.id), null, null, null, result.mutationIds, AgentActionStatus.SUCCEEDED)
            repository.saveThread(thread)
            repository.appendMessage(message)
            repository.saveToolCall(call)
            repository.appendToolResult(result)
            repository.appendSummary(summary)
            repository.saveAction(action)
            repository.savePermissionPolicy(AgentPermissionPolicy.default())
            repository.setSyncAgentOriginEnabled(SyncSpaceId("personal"), true)
            val provider = ProviderConfig(ProviderConfigId(id(8)), "https://model.example/v1", "chosen-model", 8192, 2048, true, true, SecretReference("secure://provider/1"))
            repository.saveProviderConfig(provider)
            repository.selectProviderConfig(provider.id)

            assertEquals(provider, repository.providerConfig(provider.id))
            assertEquals(provider.id, repository.selectedProviderConfigId())
            assertTrue(repository.syncAgentOriginEnabled(SyncSpaceId("personal")))
            assertFalse(repository.syncAgentOriginEnabled(SyncSpaceId("other")))
            assertEquals(message, repository.messages(thread.id).single())
            assertEquals(result, repository.toolResults(thread.id).single())
            assertEquals(summary, repository.summaries(thread.id).single())

            repository.deleteThread(thread.id)
            assertNull(repository.thread(thread.id))
            assertTrue(repository.messages(thread.id).isEmpty())
            assertTrue(repository.toolCalls(thread.id).isEmpty())
            assertTrue(repository.toolResults(thread.id).isEmpty())
            assertTrue(repository.summaries(thread.id).isEmpty())
            assertEquals(action, repository.action(action.id))
        } finally {
            database.close()
        }
    }

    @Test fun `v11 to v12 migration preserves history and creates agent tables`() = runBlocking {
        val old = migrations.createDatabase(11)
        old.prepare(
            "INSERT INTO mutation_record(mutation_id, origin, dvv_json, hlc_physical_millis, hlc_logical, hlc_replica_id, committed_at_epoch_millis, outbound_eligible) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
        ).use { statement ->
            statement.bindText(1, id(9)); statement.bindText(2, "USER"); statement.bindText(3, "{}")
            statement.bindLong(4, 1); statement.bindLong(5, 0); statement.bindText(6, id(10))
            statement.bindLong(7, 1); statement.bindLong(8, 1); statement.step()
        }
        old.close()
        val upgraded = migrations.runMigrationsAndValidate(12, listOf(AgentMigration11To12))
        try {
            upgraded.prepare("SELECT mutation_id FROM mutation_record").use { statement ->
                assertTrue(statement.step()); assertEquals(id(9), statement.getText(0))
            }
            upgraded.prepare("SELECT thread_id FROM agent_thread").use { statement -> assertFalse(statement.step()) }
        } finally {
            upgraded.close()
        }
    }

    @Test fun `Agent sync write gate defaults closed for active space and follows local opt in`() = runBlocking {
        val database = openInMemoryDesktopDatabase()
        try {
            val agentState = RoomAgentStateRepository(database)
            val accountId = AccountId("personal-account")
            val space = SyncSpaceId("personal-space")
            val enrollment = object : LocalEnrollmentRepository {
                var current: LocalEnrollmentState? = null
                override suspend fun state(accountId: AccountId) = current
                override suspend fun savePending(value: LocalEnrollmentState.Pending) { current = value }
                override suspend fun saveActive(value: LocalEnrollmentState.Active) { current = value }
            }
            val gate = AgentSyncWriteGate(accountId, enrollment, agentState)
            assertTrue(gate.mayCommit()) // No enrolled SyncSpace: local-only write.
            assertFalse(gate.enabled(space))

            enrollment.saveActive(LocalEnrollmentState.Active(
                accountId, DeviceId("desktop"), EnrollmentRequestId("request"),
                HpkePublicKeyBase64Url("AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"),
                SecretReference("secure://hpke"), space,
                SecretReference("secure://amk"), SecretReference("secure://credential"),
            ))
            assertFalse(gate.mayCommit())
            assertFalse(gate.enabled(space))
            agentState.setSyncAgentOriginEnabled(space, true)
            assertTrue(gate.mayCommit())
            assertTrue(gate.enabled(space))
            assertFalse(gate.enabled(SyncSpaceId("other-space")))
            agentState.setSyncAgentOriginEnabled(space, false)
            assertFalse(gate.mayCommit())
            assertFalse(gate.enabled(space))
        } finally {
            database.close()
        }
    }

    @Test fun `Agent Task ToolResult audit and ChangeLog share committed MutationId`() = runBlocking {
        val database = openInMemoryDesktopDatabase()
        try {
            val agent = RoomAgentStateRepository(database)
            val thread = AgentThread(AgentThreadId(id(20)), "Agent task", 1)
            val message = AgentMessage(AgentMessageId(id(21)), thread.id, 0, AgentMessageRole.USER, "Create a task", 2)
            val call = AgentToolCall(AgentToolCallId(id(22)), thread.id, message.id, 0, "task.create", "{}", AgentToolCallState.RUNNING)
            val action = AgentAction(AgentActionId(id(23)), thread.id, message.id, null, null, listOf(call.id), emptyList(), null, null, null, emptyList(), AgentActionStatus.PROPOSED)
            val resultId = AgentToolResultId(id(24))
            agent.saveThread(thread)
            agent.appendMessage(message)
            agent.saveToolCall(call)
            agent.saveAction(action)

            var seed = 0
            val ids = RfcUuidV7Generator(EpochMillisecondsClock { 1_700_000_000_000 }, RandomBytes { size ->
                ByteArray(size) { (seed++).toByte() }
            })
            val history = RoomMutationJournalRepository(database)
            val tasks = RoomTaskRepository(database)
            val coordinator = MutationCoordinator(
                RoomApplicationTransactionRunner(database), history, ids, MutationWallClock { 3 },
                AgentOriginWriteGate { true },
            )
            val editing = TaskEditingService(tasks, ids, coordinator, NoActiveSyncSpaceWritePolicy)

            val created = assertIs<EditingResult.Success<Task>>(editing.create(
                CreateTaskInput("Read paper", TaskPriority.HIGH, null, null, null),
                MutationOrigin.Agent(action.id.value),
                onCommitted = { committed ->
                    agent.appendToolResult(AgentToolResult(resultId, thread.id, call.id, 0, AgentToolResultStatus.SUCCESS, "{}", listOf(committed.mutationId)))
                    agent.saveAction(action.copy(toolResultIds = listOf(resultId), mutationIds = listOf(committed.mutationId), status = AgentActionStatus.SUCCEEDED))
                    agent.saveToolCall(call.copy(state = AgentToolCallState.COMPLETED))
                },
            ))
            val mutationId = requireNotNull(created.mutationId)
            assertEquals(created.value, tasks.getTask(created.value.id))
            assertEquals(mutationId.value, history.timeline().single().operation.mutationId)
            assertEquals(MutationOrigin.Agent(action.id.value), history.timeline().single().operation.origin)
            assertEquals(mutationId.value, history.diff(mutationId.value).single().mutationId)
            assertEquals(listOf(mutationId), agent.toolResults(thread.id).single().mutationIds)
            assertEquals(listOf(mutationId), agent.action(action.id)?.mutationIds)

            val failedCall = call.copy(id = AgentToolCallId(id(25)), ordinal = 1)
            agent.saveToolCall(failedCall)
            assertFailsWith<IllegalStateException> {
                editing.create(
                    CreateTaskInput("Must roll back", TaskPriority.NORMAL, null, null, null),
                    MutationOrigin.Agent(id(26)),
                    onCommitted = { committed ->
                        agent.appendToolResult(AgentToolResult(AgentToolResultId(id(27)), thread.id, failedCall.id, 1, AgentToolResultStatus.SUCCESS, "{}", listOf(committed.mutationId)))
                        error("Simulated audit failure")
                    },
                )
            }
            assertEquals(listOf(created.value), tasks.observeTasks().first())
            assertEquals(1, history.timeline().size)
            assertEquals(1, agent.toolResults(thread.id).size)
        } finally {
            database.close()
        }
    }

    private fun id(number: Int) = "00000000-0000-7000-8000-${number.toString().padStart(12, '0')}"
}
