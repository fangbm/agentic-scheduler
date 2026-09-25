package dev.agenticscheduler.database

import androidx.room3.testing.MigrationTestHelper
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import dev.agenticscheduler.agent.history.*
import dev.agenticscheduler.agent.permission.AgentPermissionPolicy
import dev.agenticscheduler.application.sync.SecretReference
import dev.agenticscheduler.database.repository.RoomAgentStateRepository
import dev.agenticscheduler.sync.MutationId
import dev.agenticscheduler.sync.SyncSpaceId
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

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

    private fun id(number: Int) = "00000000-0000-7000-8000-${number.toString().padStart(12, '0')}"
}
