package dev.agenticscheduler.database

import androidx.room3.useReaderConnection
import androidx.room3.PooledConnection
import androidx.room3.useWriterConnection
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.SQLiteStatement
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.room3.testing.MigrationTestHelper
import dev.agenticscheduler.agent.history.*
import dev.agenticscheduler.agent.permission.AgentPermissionMode
import dev.agenticscheduler.agent.permission.AgentToolCapability
import dev.agenticscheduler.application.sync.*
import dev.agenticscheduler.database.repository.AgentSyncIntegrityConflictException
import dev.agenticscheduler.database.repository.RoomAgentSyncPersistence
import dev.agenticscheduler.sync.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Rule
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AgentSyncPersistenceTest {
    @get:Rule val migrations = MigrationTestHelper(
        Path.of("schemas"), Files.createTempFile("agent-v12-to-v13-", ".db"),
        BundledSQLiteDriver(), AgenticSchedulerDatabase::class,
        { AgenticSchedulerDatabaseConstructor.initialize() },
    )

    @Test fun `v12 to v13 preserves D9 local state and fresh schema parity`() = runBlocking {
        val v11 = migrations.createDatabase(11)
        v11.exec("INSERT INTO mutation_record(mutation_id, origin, dvv_json, hlc_physical_millis, hlc_logical, hlc_replica_id, committed_at_epoch_millis, outbound_eligible) VALUES (?, ?, ?, ?, ?, ?, ?, ?)", id(90), "USER", "{}", 1L, 0L, id(91), 2L, 1L)
        v11.close()
        val v12 = migrations.runMigrationsAndValidate(12, listOf(AgentMigration11To12))
        val thread = AgentThread(AgentThreadId(id(1)), "Persisted", 10)
        val message = AgentMessage(AgentMessageId(id(2)), thread.id, 0, AgentMessageRole.USER, "Pending approval", 11)
        val call = AgentToolCall(AgentToolCallId(id(3)), thread.id, message.id, 0, "task.create", "{}", AgentToolCallState.WAITING_CONFIRMATION, "{\"preview\":true}")
        val action = AgentAction(AgentActionId(id(4)), thread.id, message.id, ProviderConfigId(id(8)), "model-a", listOf(call.id), emptyList(), AgentPermissionMode.REQUIRE_CONFIRMATION, call.id.value, null, emptyList(), AgentActionStatus.WAITING_CONFIRMATION)
        val result = AgentToolResult(AgentToolResultId(id(5)), thread.id, call.id, 1, AgentToolResultStatus.SUCCESS, "{\"ok\":true}")
        val summary = ContextSummary(ContextSummaryId(id(6)), thread.id, message.id, message.id, 1, "local summary", 12, null, null)
        v12.exec("INSERT INTO agent_thread(thread_id, created_at_epoch_millis, payload_json) VALUES (?, ?, ?)", id(1), 10L, Json.encodeToString(AgentThread.serializer(), thread))
        v12.exec("INSERT INTO agent_message(message_id, thread_id, ordinal, payload_json) VALUES (?, ?, ?, ?)", id(2), id(1), 0L, Json.encodeToString(AgentMessage.serializer(), message))
        v12.exec("INSERT INTO agent_tool_call(call_id, thread_id, ordinal, payload_json) VALUES (?, ?, ?, ?)", id(3), id(1), 0L, Json.encodeToString(AgentToolCall.serializer(), call))
        v12.exec("INSERT INTO agent_tool_result(result_id, thread_id, call_id, ordinal, payload_json) VALUES (?, ?, ?, ?, ?)", id(5), id(1), id(3), 1L, Json.encodeToString(AgentToolResult.serializer(), result))
        v12.exec("INSERT INTO context_summary(summary_id, thread_id, created_at_epoch_millis, payload_json) VALUES (?, ?, ?, ?)", id(6), id(1), 12L, Json.encodeToString(ContextSummary.serializer(), summary))
        v12.exec("INSERT INTO agent_action(action_id, thread_id, payload_json) VALUES (?, ?, ?)", id(4), id(1), Json.encodeToString(AgentAction.serializer(), action))
        v12.exec("INSERT INTO agent_permission_policy(capability, mode) VALUES (?, ?)", AgentToolCapability.LOW_RISK_CREATE.name, AgentPermissionMode.REQUIRE_CONFIRMATION.name)
        v12.exec("INSERT INTO provider_config(config_id, selected, credential_secret_ref, payload_json) VALUES (?, ?, ?, ?)", id(8), 1L, "secure://provider/key", "{\"baseUrl\":\"https://example.invalid\",\"model\":\"model-a\",\"maxContextUnits\":8192,\"reservedOutputUnits\":1024,\"streamingSupported\":true,\"toolCallingSupported\":true}")
        v12.exec("INSERT INTO sync_space_cursor(sync_space_id, server_cursor) VALUES (?, ?)", "personal", 44L)
        v12.close()

        val upgraded = migrations.runMigrationsAndValidate(13, listOf(AgentSyncMigration12To13))
        try {
            AgentSchema.validate(upgraded)
            AgentSyncSchema.validate(upgraded)
            assertEquals(Json.encodeToString(AgentThread.serializer(), thread), upgraded.scalarText("SELECT payload_json FROM agent_thread WHERE thread_id = ?", id(1)))
            assertEquals(Json.encodeToString(AgentMessage.serializer(), message), upgraded.scalarText("SELECT payload_json FROM agent_message WHERE message_id = ?", id(2)))
            assertEquals(Json.encodeToString(AgentToolCall.serializer(), call), upgraded.scalarText("SELECT payload_json FROM agent_tool_call WHERE call_id = ?", id(3)))
            assertEquals(Json.encodeToString(AgentToolResult.serializer(), result), upgraded.scalarText("SELECT payload_json FROM agent_tool_result WHERE result_id = ?", id(5)))
            assertEquals(Json.encodeToString(ContextSummary.serializer(), summary), upgraded.scalarText("SELECT payload_json FROM context_summary WHERE summary_id = ?", id(6)))
            assertEquals(Json.encodeToString(AgentAction.serializer(), action), upgraded.scalarText("SELECT payload_json FROM agent_action WHERE action_id = ?", id(4)))
            assertEquals("REQUIRE_CONFIRMATION", upgraded.scalarText("SELECT mode FROM agent_permission_policy WHERE capability = ?", AgentToolCapability.LOW_RISK_CREATE.name))
            assertEquals("secure://provider/key", upgraded.scalarText("SELECT credential_secret_ref FROM provider_config WHERE config_id = ?", id(8)))
            assertEquals("44", upgraded.scalarText("SELECT server_cursor FROM sync_space_cursor WHERE sync_space_id = ?", "personal"))
            val agentTableNames = upgraded.agentSyncTableNames().filterNot { it.endsWith("_idx") }.toSet()
            assertEquals(setOf("agent_sync_operation_identity", "agent_sync_inbox", "agent_sync_outbox", "agent_sync_space_state", "agent_sync_dvv_frontier", "agent_sync_handled_dot", "agent_sync_pending_dependency", "agent_sync_turn_stage", "agent_sync_turn_member", "agent_sync_active_turn_projection", "agent_sync_thread_tombstone", "agent_sync_conflict", "agent_sync_audit_parent_link", "agent_sync_backfill_state"), agentTableNames)
            agentTableNames.forEach { table -> assertEquals(0L, upgraded.scalarLong("SELECT count(*) FROM $table"), "$table must be empty after migration") }

            val migratedSchema = upgraded.schemaSignatures()
            val fresh = openInMemoryDesktopDatabase()
            val freshSchema = try { fresh.useReaderConnection { it.schemaSignatures() } } finally { fresh.close() }
            assertEquals(freshSchema, migratedSchema)
        } finally {
            upgraded.close()
        }
    }

    @Test fun `immutable duplicate dedupes while identity and dot rebindings conflict`() = runBlocking {
        val db = openInMemoryDesktopDatabase()
        try {
            val persistence = RoomAgentSyncPersistence(db)
            val space = SyncSpaceId("personal")
            val original = payload(10, 1, ThreadCreated(AgentThreadSyncId(id(20)), "Title", 1))
            assertEquals(AgentSyncPersistResult.Inserted, persistence.enqueueOutbound(space, original))
            assertEquals(AgentSyncPersistResult.Duplicate, persistence.enqueueOutbound(space, original))
            assertEquals(setOf(AgentSyncDirection.OUTBOUND), persistence.direction(space, id(10)))
            val dependsOnBusiness = payload(12, 2, ToolResultAppended(AgentToolResultSyncId(id(13)), AgentToolCallSyncId(id(14)), AgentThreadSyncId(id(15)), AgentTurnSyncId(id(16)), AgentToolResultStatusV3.SUCCESS, Json.parseToJsonElement("{\"ok\":true}"), listOf(MutationId(id(17)))))
            persistence.enqueueOutbound(space, dependsOnBusiness)
            assertEquals("HELD", db.useReaderConnection { it.scalarText("SELECT state FROM agent_sync_outbox WHERE sync_space_id = ? AND operation_id = ?", space.value, id(12)) })
            persistence.resolvePendingDependency(space, AgentSyncPendingDependency(id(12), AgentSyncDependencyKind.BUSINESS_MUTATION, id(17)))
            assertEquals("READY", db.useReaderConnection { it.scalarText("SELECT state FROM agent_sync_outbox WHERE sync_space_id = ? AND operation_id = ?", space.value, id(12)) })
            assertFailsWith<AgentSyncIntegrityConflictException> {
                persistence.enqueueOutbound(space, original.copy(operation = original.operation.copy(agentEvent = ThreadCreated(AgentThreadSyncId(id(20)), "Different", 1))))
            }
            assertFailsWith<AgentSyncIntegrityConflictException> {
                persistence.enqueueOutbound(space, payload(11, 1, ThreadTitleSet(AgentThreadSyncId(id(20)), "Rename")))
            }
            assertEquals(2L, db.useReaderConnection { it.scalarLong("SELECT count(*) FROM agent_sync_conflict WHERE sync_space_id = ?", space.value) })
            Unit
        } finally { db.close() }
    }

    @Test fun `pending causal and parent dependencies survive reopening and backfill is separate from D8 cursor`() = runBlocking {
        val path = Files.createTempFile("agent-sync-state-", ".db")
        try {
            val db = openDesktopDatabase(path.toAbsolutePath().toString())
            val persistence = RoomAgentSyncPersistence(db)
            val space = SyncSpaceId("personal")
            db.useWriterConnection { it.exec("INSERT INTO sync_space_cursor(sync_space_id, server_cursor) VALUES (?, ?)", space.value, 44L) }
            val value = payload(30, 2, ThreadTitleSet(AgentThreadSyncId(id(31)), "Waiting"))
            persistence.acceptInbound(space, value)
            persistence.addPendingDependency(space, AgentSyncPendingDependency(id(30), AgentSyncDependencyKind.AGENT_DOT, "${replica(1).value}:1"))
            persistence.addPendingDependency(space, AgentSyncPendingDependency(id(30), AgentSyncDependencyKind.PARENT_RECORD, id(32)))
            persistence.advanceBackfill(space, AgentSyncBackfillState(17, AgentSyncBackfillRecoveryState.RUNNING, 12, 100))
            db.close()

            val reopened = openDesktopDatabase(path.toAbsolutePath().toString())
            try {
                val recovered = RoomAgentSyncPersistence(reopened)
                assertEquals(2, recovered.pendingDependencies(space, id(30)).size)
                assertFailsWith<IllegalStateException> { recovered.resolvePendingDependency(space, AgentSyncPendingDependency(id(30), AgentSyncDependencyKind.AGENT_DOT, "${replica(1).value}:1")) }
                assertEquals(AgentSyncBackfillState(17, AgentSyncBackfillRecoveryState.RUNNING, 12, 100), recovered.backfillState(space))
                assertEquals(44L, reopened.useReaderConnection { it.scalarLong("SELECT server_cursor FROM sync_space_cursor WHERE sync_space_id = ?", space.value) })
            } finally { reopened.close() }
        } finally { Files.deleteIfExists(path) }
    }

    @Test fun `incomplete staged turn cannot activate and tombstone blocks activation`() = runBlocking {
        val db = openInMemoryDesktopDatabase()
        try {
            val persistence = RoomAgentSyncPersistence(db)
            val space = SyncSpaceId("personal")
            val thread = AgentThreadSyncId(id(40))
            val turn = AgentTurnSyncId(id(41))
            val message = AgentMessageSyncId(id(42))
            persistence.setLocalReplica(space, replica(1), 0)
            val manifest = payload(43, 1, TurnFinalized(turn, thread, emptyList(), listOf(MessageMember(message)), AgentTurnOutcome.SUCCEEDED))
            persistence.acceptInbound(space, manifest)
            persistence.markHandled(space, id(43))
            assertEquals(AgentSyncTurnState.INCOMPLETE, persistence.turnState(space, turn.value))
            assertFailsWith<IllegalArgumentException> { persistence.markTurnActive(space, turn.value) }
            persistence.acceptInbound(space, payload(44, 2, MessageAppended(message, thread, turn, AgentMessageRoleV3.USER, "hi", 2)))
            persistence.markHandled(space, id(44))
            assertEquals(AgentSyncTurnState.COMPLETE_VERIFIED, persistence.turnState(space, turn.value))
            persistence.markTurnActive(space, turn.value)
            assertTrue(persistence.isTurnActive(space, turn.value))
            persistence.acceptInbound(space, payload(45, 3, ThreadDeleted(thread)))
            assertTrue(persistence.isThreadTombstoned(space, thread.value))
            assertFalse(persistence.isTurnActive(space, turn.value))
            assertFailsWith<IllegalStateException> { persistence.markTurnActive(space, turn.value) }
            persistence.acceptInbound(space, payload(49, 1, MessageAppended(AgentMessageSyncId(id(50)), thread, turn, AgentMessageRoleV3.USER, "concurrent append", 4), replica(3)))
            assertEquals(1L, db.useReaderConnection { it.scalarLong("SELECT count(*) FROM agent_sync_conflict WHERE conflict_kind = 'THREAD_DELETE_APPEND_CONFLICT' AND entity_id = ?", thread.value) })
            persistence.acceptInbound(space, payload(47, 4, ActionFinalized(AgentActionSyncId(id(48)), null, thread, message, emptyList(), emptyList(), emptyList(), FinalAgentActionStatus.SUCCEEDED)))
            assertEquals(AgentSyncAuditParentState.PARENT_REMOVED_BY_TOMBSTONE, persistence.auditParentState(space, id(48), "MESSAGE", message.value))
        } finally { db.close() }
    }

    @Test fun `agent replica identities and handled dots stay per SyncSpace while frontier remains decision blocked`() = runBlocking {
        val db = openInMemoryDesktopDatabase()
        try {
            val persistence = RoomAgentSyncPersistence(db)
            val a = SyncSpaceId("a")
            val b = SyncSpaceId("b")
            persistence.setLocalReplica(a, replica(1), 4)
            persistence.setLocalReplica(b, replica(1), 0)
            val inboundA = payload(60, 1, ThreadTitleSet(AgentThreadSyncId(id(61)), "A"), replica(3))
            val inboundB = payload(62, 1, ThreadTitleSet(AgentThreadSyncId(id(63)), "B"), replica(4))
            persistence.acceptInbound(a, inboundA)
            persistence.markHandled(a, id(60))
            persistence.acceptInbound(b, inboundB)
            persistence.markHandled(b, id(62))
            assertEquals(AgentSyncFrontierState.BlockedByDecision, persistence.dvvFrontier(a))
            assertEquals(AgentSyncFrontierState.BlockedByDecision, persistence.dvvFrontier(b))
            assertEquals(1L, db.useReaderConnection { it.scalarLong("SELECT count(*) FROM agent_sync_handled_dot WHERE sync_space_id = ?", a.value) })
            assertEquals(1L, db.useReaderConnection { it.scalarLong("SELECT count(*) FROM agent_sync_handled_dot WHERE sync_space_id = ?", b.value) })
            assertEquals(replica(1) to 4L, persistence.localReplica(a))
            assertEquals(replica(1) to 0L, persistence.localReplica(b))
            assertFailsWith<IllegalArgumentException> { persistence.setLocalReplica(a, replica(3), 5) }
            assertFailsWith<IllegalArgumentException> { persistence.setLocalReplica(a, replica(1), 3) }
            Unit
        } finally { db.close() }
    }

    private fun payload(operation: Int, counter: Long, event: AgentSyncEvent, dotReplica: AgentReplicaId = replica(1)) = SyncPayloadV3(operation = AgentSyncOperation(
        MutationId(id(operation)), AgentDvvSnapshot(emptyList(), AgentDot(dotReplica, counter)), AgentHlcSnapshot(counter, 0, dotReplica), event,
    ))

    private fun id(number: Int) = "00000000-0000-7000-8000-${number.toString().padStart(12, '0')}"
    private fun replica(number: Int) = AgentReplicaId(id(100 + number))
}

private fun SQLiteConnection.exec(sql: String, vararg args: Any?) = prepare(sql).use { statement ->
    statement.bindValues(args.toList())
    statement.step()
}

private fun SQLiteConnection.scalarText(sql: String, vararg args: Any?): String? = prepare(sql).use { statement ->
    statement.bindValues(args.toList())
    if (statement.step()) statement.getText(0) else null
}

private fun SQLiteConnection.scalarLong(sql: String, vararg args: Any?): Long = prepare(sql).use { statement ->
    statement.bindValues(args.toList())
    if (statement.step()) statement.getLong(0) else 0
}

private fun SQLiteConnection.agentSyncTableNames(): List<String> = prepare("SELECT name FROM sqlite_master WHERE name LIKE 'agent_sync_%' ORDER BY name").use { statement ->
    buildList { while (statement.step()) add(statement.getText(0)) }
}

private fun SQLiteConnection.schemaSignatures(): List<String> = prepare("SELECT type || ':' || name || ':' || sql FROM sqlite_master WHERE name NOT LIKE 'sqlite_%' ORDER BY type, name").use { statement ->
    buildList { while (statement.step()) add(statement.getText(0)) }
}

private suspend fun PooledConnection.schemaSignatures(): List<String> = usePrepared("SELECT type || ':' || name || ':' || sql FROM sqlite_master WHERE name NOT LIKE 'sqlite_%' ORDER BY type, name") { statement ->
    buildList { while (statement.step()) add(statement.getText(0)) }
}

private suspend fun PooledConnection.exec(sql: String, vararg args: Any?) = usePrepared(sql) { statement ->
    statement.bindValues(args.toList())
    statement.step()
}

private suspend fun PooledConnection.scalarLong(sql: String, vararg args: Any?): Long = usePrepared(sql) { statement ->
    statement.bindValues(args.toList())
    if (statement.step()) statement.getLong(0) else 0
}

private suspend fun PooledConnection.scalarText(sql: String, vararg args: Any?): String? = usePrepared(sql) { statement ->
    statement.bindValues(args.toList())
    if (statement.step()) statement.getText(0) else null
}

private fun SQLiteStatement.bindValues(args: List<Any?>) {
    args.forEachIndexed { index, value -> when (value) {
        null -> bindNull(index + 1)
        is String -> bindText(index + 1, value)
        is Long -> bindLong(index + 1, value)
        is Int -> bindLong(index + 1, value.toLong())
        else -> error("Unsupported test SQL value $value")
    } }
}
