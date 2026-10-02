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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
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
            val localReplica = replica(2)
            persistence.provisionLocalReplica(space, localReplica)
            val original = payload(10, ThreadCreated(AgentThreadSyncId(id(20)), "Title", 1), replica(1))
            assertEquals(AgentSyncPersistResult.Inserted, persistence.acceptInbound(space, original))
            assertEquals(AgentSyncPersistResult.Duplicate, persistence.acceptInbound(space, original))
            assertEquals(setOf(AgentSyncDirection.INBOUND), persistence.direction(space, id(10)))
            val dependsOnBusiness = persistence.enqueueOutbound(
                space,
                MutationId(id(12)),
                AgentHlcSnapshot(12, 0, localReplica),
                ToolResultAppended(AgentToolResultSyncId(id(13)), AgentToolCallSyncId(id(14)), AgentThreadSyncId(id(15)), AgentTurnSyncId(id(16)), AgentToolResultStatusV3.SUCCESS, Json.parseToJsonElement("{\"ok\":true}"), listOf(MutationId(id(17)))),
            )
            assertEquals("HELD", db.useReaderConnection { it.scalarText("SELECT state FROM agent_sync_outbox WHERE sync_space_id = ? AND operation_id = ?", space.value, id(12)) })
            persistence.resolvePendingDependency(space, AgentSyncPendingDependency(id(12), AgentSyncDependencyKind.BUSINESS_MUTATION, id(17)))
            assertEquals("READY", db.useReaderConnection { it.scalarText("SELECT state FROM agent_sync_outbox WHERE sync_space_id = ? AND operation_id = ?", space.value, id(12)) })
            assertFailsWith<AgentSyncIntegrityConflictException> {
                persistence.acceptInbound(space, original.copy(operation = original.operation.copy(agentEvent = ThreadCreated(AgentThreadSyncId(id(20)), "Different", 1))))
            }
            assertFailsWith<AgentSyncIntegrityConflictException> {
                persistence.acceptInbound(space, payload(11, ThreadTitleSet(AgentThreadSyncId(id(21)), "Rename"), replica(1)))
            }
            assertEquals(0L, dependsOnBusiness.agentDvv.dot.counter)
            assertEquals(2L, db.useReaderConnection { it.scalarLong("SELECT count(*) FROM agent_sync_conflict WHERE sync_space_id = ?", space.value) })
            Unit
        } finally { db.close() }
    }

    @Test fun `pending dependencies and quarantine restart survive reopening and backfill is separate from D8 cursor`() = runBlocking {
        val path = Files.createTempFile("agent-sync-state-", ".db")
        try {
            val db = openDesktopDatabase(path.toAbsolutePath().toString())
            val persistence = RoomAgentSyncPersistence(db)
            val space = SyncSpaceId("personal")
            persistence.provisionLocalReplica(space, replica(1))
            db.useWriterConnection { it.exec("INSERT INTO sync_space_cursor(sync_space_id, server_cursor) VALUES (?, ?)", space.value, 44L) }
            val value = payload(30, ThreadTitleSet(AgentThreadSyncId(id(31)), "Waiting"), replica(2), 1, listOf(AgentVersionComponent(replica(2), 0)))
            persistence.acceptInbound(space, value)
            persistence.addPendingDependency(space, AgentSyncPendingDependency(id(30), AgentSyncDependencyKind.PARENT_RECORD, id(32)))
            persistence.advanceBackfill(space, AgentSyncBackfillState(17, AgentSyncBackfillRecoveryState.RUNNING, 12, 100))
            assertEquals(AgentSyncBackfillState(11, AgentSyncBackfillRecoveryState.RUNNING, 12, 100), persistence.backfillState(space))
            persistence.advanceBackfill(space, AgentSyncBackfillState(17, AgentSyncBackfillRecoveryState.RUNNING, 12, 101))
            persistence.advanceBackfill(space, AgentSyncBackfillState(30, AgentSyncBackfillRecoveryState.RUNNING, 7, 102))
            assertEquals(AgentSyncBackfillState(6, AgentSyncBackfillRecoveryState.RUNNING, 7, 102), persistence.backfillState(space))
            db.close()

            val reopened = openDesktopDatabase(path.toAbsolutePath().toString())
            try {
                val recovered = RoomAgentSyncPersistence(reopened)
                assertEquals(2, recovered.pendingDependencies(space, id(30)).size)
                assertFailsWith<IllegalStateException> { recovered.markHandled(space, id(30)) }
                assertEquals(AgentSyncBackfillState(6, AgentSyncBackfillRecoveryState.RUNNING, 7, 102), recovered.backfillState(space))
                recovered.advanceBackfill(space, AgentSyncBackfillState(30, AgentSyncBackfillRecoveryState.RUNNING, null, 103))
                assertEquals(AgentSyncBackfillState(30, AgentSyncBackfillRecoveryState.RUNNING, 7, 103), recovered.backfillState(space))
                recovered.advanceBackfill(space, AgentSyncBackfillState(31, AgentSyncBackfillRecoveryState.RUNNING, 20, 104))
                assertEquals(AgentSyncBackfillState(31, AgentSyncBackfillRecoveryState.RUNNING, 7, 104), recovered.backfillState(space))
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
            persistence.provisionLocalReplica(space, replica(1))
            val manifest = payload(43, TurnFinalized(turn, thread, emptyList(), listOf(MessageMember(message)), AgentTurnOutcome.SUCCEEDED))
            persistence.acceptInbound(space, manifest)
            assertEquals(AgentSyncTurnState.INCOMPLETE, persistence.turnState(space, turn.value))
            persistence.markHandled(space, id(43))
            assertEquals(AgentSyncTurnState.INCOMPLETE, persistence.turnState(space, turn.value))
            assertFailsWith<IllegalArgumentException> { persistence.markTurnActive(space, turn.value) }
            persistence.acceptInbound(space, payload(44, MessageAppended(message, thread, turn, AgentMessageRoleV3.USER, "hi", 2)))
            persistence.markHandled(space, id(44))
            assertEquals(AgentSyncTurnState.COMPLETE_VERIFIED, persistence.turnState(space, turn.value))
            persistence.markTurnActive(space, turn.value)
            assertTrue(persistence.isTurnActive(space, turn.value))
            persistence.acceptInbound(space, payload(45, ThreadDeleted(thread)))
            assertFalse(persistence.isThreadTombstoned(space, thread.value))
            assertTrue(persistence.isTurnActive(space, turn.value))
            persistence.markHandled(space, id(45))
            assertTrue(persistence.isThreadTombstoned(space, thread.value))
            assertFalse(persistence.isTurnActive(space, turn.value))
            assertFailsWith<IllegalStateException> { persistence.markTurnActive(space, turn.value) }
            persistence.acceptInbound(space, payload(49, MessageAppended(AgentMessageSyncId(id(50)), thread, turn, AgentMessageRoleV3.USER, "concurrent append", 4)))
            assertEquals(1L, db.useReaderConnection { it.scalarLong("SELECT count(*) FROM agent_sync_conflict WHERE conflict_kind = 'THREAD_DELETE_APPEND_CONFLICT' AND entity_id = ?", thread.value) })
            persistence.acceptInbound(space, payload(47, ActionFinalized(AgentActionSyncId(id(48)), null, thread, message, emptyList(), emptyList(), emptyList(), FinalAgentActionStatus.SUCCEEDED)))
            assertEquals(AgentSyncAuditParentState.PARENT_REMOVED_BY_TOMBSTONE, persistence.auditParentState(space, id(48), "MESSAGE", message.value))
        } finally { db.close() }
    }

    @Test fun `causally pending thread delete leaves active projection until handled`() = runBlocking {
        val db = openInMemoryDesktopDatabase()
        try {
            val persistence = RoomAgentSyncPersistence(db)
            val space = SyncSpaceId("personal")
            val thread = AgentThreadSyncId(id(310))
            val turn = AgentTurnSyncId(id(311))
            val owner = replica(2)
            val deletingReplica = replica(3)
            persistence.provisionLocalReplica(space, replica(1))

            persistence.acceptInbound(space, payload(312, TurnFinalized(turn, thread, emptyList(), emptyList(), AgentTurnOutcome.SUCCEEDED), owner))
            assertEquals(AgentSyncTurnState.INCOMPLETE, persistence.turnState(space, turn.value))
            persistence.markHandled(space, id(312))
            assertEquals(AgentSyncTurnState.COMPLETE_VERIFIED, persistence.turnState(space, turn.value))
            persistence.markTurnActive(space, turn.value)
            assertTrue(persistence.isTurnActive(space, turn.value))

            val delete = payload(313, ThreadDeleted(thread), deletingReplica, context = listOf(AgentVersionComponent(owner, 1)))
            persistence.acceptInbound(space, delete)
            assertFalse(persistence.isThreadTombstoned(space, thread.value))
            assertTrue(persistence.isTurnActive(space, turn.value))
            assertTrue(persistence.eligibleInboundOperations(space).isEmpty())
            assertFailsWith<IllegalStateException> { persistence.markHandled(space, id(313)) }

            persistence.acceptInbound(space, payload(314, ThreadTitleSet(thread, "causal prerequisite"), owner, 1, listOf(AgentVersionComponent(owner, 0))))
            persistence.markHandled(space, id(314))
            assertEquals(listOf(id(313)), persistence.eligibleInboundOperations(space).map { it.operationId.value })
            persistence.markHandled(space, id(313))
            assertTrue(persistence.isThreadTombstoned(space, thread.value))
            assertFalse(persistence.isTurnActive(space, turn.value))
            assertEquals(AgentSyncTurnState.TOMBSTONED, persistence.turnState(space, turn.value))
        } finally { db.close() }
    }

    @Test fun `handled manifest is required and tombstoned turns stay sticky after late members`() = runBlocking {
        val db = openInMemoryDesktopDatabase()
        try {
            val persistence = RoomAgentSyncPersistence(db)
            val space = SyncSpaceId("personal")
            val thread = AgentThreadSyncId(id(320))
            val completeTurn = AgentTurnSyncId(id(321))
            val stagedTurn = AgentTurnSyncId(id(322))
            val lateMessage = AgentMessageSyncId(id(323))
            persistence.provisionLocalReplica(space, replica(1))

            persistence.acceptInbound(space, payload(324, TurnFinalized(completeTurn, thread, emptyList(), emptyList(), AgentTurnOutcome.SUCCEEDED)))
            assertEquals(AgentSyncTurnState.INCOMPLETE, persistence.turnState(space, completeTurn.value))
            persistence.markHandled(space, id(324))
            assertEquals(AgentSyncTurnState.COMPLETE_VERIFIED, persistence.turnState(space, completeTurn.value))

            persistence.acceptInbound(space, payload(325, TurnFinalized(stagedTurn, thread, emptyList(), listOf(MessageMember(lateMessage)), AgentTurnOutcome.SUCCEEDED)))
            persistence.markHandled(space, id(325))
            assertEquals(AgentSyncTurnState.INCOMPLETE, persistence.turnState(space, stagedTurn.value))
            persistence.acceptInbound(space, payload(326, ThreadDeleted(thread)))
            persistence.markHandled(space, id(326))
            assertEquals(AgentSyncTurnState.TOMBSTONED, persistence.turnState(space, completeTurn.value))
            assertEquals(AgentSyncTurnState.TOMBSTONED, persistence.turnState(space, stagedTurn.value))

            persistence.acceptInbound(space, payload(327, MessageAppended(lateMessage, thread, stagedTurn, AgentMessageRoleV3.USER, "late", 1)))
            persistence.markHandled(space, id(327))
            assertEquals(AgentSyncTurnState.TOMBSTONED, persistence.turnState(space, stagedTurn.value))
            assertFalse(persistence.isTurnActive(space, stagedTurn.value))
        } finally { db.close() }
    }

    @Test fun `delete resolution conflict and tombstone recover after restart without LWW`() = runBlocking {
        val path = Files.createTempFile("agent-delete-resolution-", ".db")
        val space = SyncSpaceId("personal")
        val thread = AgentThreadSyncId(id(610))
        val deletionId = MutationId(id(611))
        val appendId = MutationId(id(612))
        val localReplica = replica(1)
        try {
            val db = openDesktopDatabase(path.toAbsolutePath().toString())
            val persistence = RoomAgentSyncPersistence(db)
            persistence.provisionLocalReplica(space, localReplica)

            val deletion = payload(611, ThreadDeleted(thread), replica(2))
            persistence.acceptInbound(space, deletion)
            persistence.markHandled(space, deletionId.value)
            val append = payload(612, MessageAppended(
                AgentMessageSyncId(id(613)), thread, AgentTurnSyncId(id(614)), AgentMessageRoleV3.USER, "concurrent text", 1,
            ), replica(3))
            persistence.acceptInbound(space, append)
            persistence.markHandled(space, appendId.value)

            val participants = listOf(deletionId, appendId).sortedBy { it.value }
            val explicit = persistence as AgentSyncExplicitUserResolutionPersistence
            val keep = explicit.commitExplicitUserDeleteResolution(space, AgentSyncExplicitDeleteResolution(
                MutationId(id(615)), AgentHlcSnapshot(615, 0, localReplica),
                ThreadDeleteConflictResolved(thread, participants, AgentThreadDeleteResolution.KEEP_DELETION, null),
            )).single()
            assertTrue(persistence.isThreadTombstoned(space, thread.value))
            assertTrue(persistence.threadHistoryProjection(space, thread).turns.isEmpty())

            // A second device resolves the same component differently without observing the first resolution.
            val remoteCopy = payload(616, ThreadDeleteConflictResolved(
                thread, participants, AgentThreadDeleteResolution.COPY_CONTENT_TO_NEW_THREAD, AgentThreadSyncId(id(617)),
            ), replica(4), context = listOf(AgentVersionComponent(replica(2), 0), AgentVersionComponent(replica(3), 0)))
            persistence.acceptInbound(space, remoteCopy)
            persistence.markHandled(space, id(616))
            val projection = persistence.threadHistoryProjection(space, thread)
            val conflict = projection.conflicts.single { it.kind == AgentSemanticConflictKind.DELETE_RESOLUTION }
            assertEquals(AgentSemanticConflictState.OPEN, conflict.state)
            assertEquals(listOf(keep.operationId, remoteCopy.operation.operationId).sortedBy { it.value }, conflict.resolutionOperationIds)
            assertTrue(projection.tombstoned)
            db.close()

            val reopened = openDesktopDatabase(path.toAbsolutePath().toString())
            try {
                val recovered = RoomAgentSyncPersistence(reopened)
                val afterRestart = recovered.threadHistoryProjection(space, thread)
                assertTrue(afterRestart.tombstoned)
                assertTrue(afterRestart.turns.isEmpty())
                assertEquals(conflict, afterRestart.conflicts.single { it.kind == AgentSemanticConflictKind.DELETE_RESOLUTION })
                assertTrue(recovered.isThreadTombstoned(space, thread.value))
            } finally { reopened.close() }
        } finally { Files.deleteIfExists(path) }
    }

    @Test fun `explicit COPY resolution persists fresh text-only replacement batch and keeps source tombstoned`() = runBlocking {
        val db = openInMemoryDesktopDatabase()
        try {
            val persistence = RoomAgentSyncPersistence(db)
            val explicit = persistence as AgentSyncExplicitUserResolutionPersistence
            val space = SyncSpaceId("personal")
            val thread = AgentThreadSyncId(id(630))
            val localReplica = replica(1)
            persistence.provisionLocalReplica(space, localReplica)
            val deleteId = MutationId(id(631))
            val messageOperationId = MutationId(id(632))
            persistence.acceptInbound(space, payload(631, ThreadDeleted(thread), replica(2)))
            persistence.markHandled(space, deleteId.value)
            persistence.acceptInbound(space, payload(632, MessageAppended(
                AgentMessageSyncId(id(633)), thread, AgentTurnSyncId(id(634)), AgentMessageRoleV3.USER, "source text", 2,
            ), replica(3)))
            persistence.markHandled(space, messageOperationId.value)

            val replacement = AgentThreadSyncId(id(640))
            val newTurn = AgentTurnSyncId(id(641))
            val newMessage = AgentMessageSyncId(id(642))
            val participants = listOf(deleteId, messageOperationId).sortedBy { it.value }
            val resolution = ThreadDeleteConflictResolved(thread, participants, AgentThreadDeleteResolution.COPY_CONTENT_TO_NEW_THREAD, replacement)
            assertFailsWith<IllegalArgumentException> {
                persistence.enqueueOutbound(space, MutationId(id(635)), AgentHlcSnapshot(635, 0, localReplica), resolution)
            }
            val createdEvent = ThreadCreated(replacement, "user-selected copy", 10)
            val messageEvent = MessageAppended(newMessage, replacement, newTurn, AgentMessageRoleV3.USER, "selected text", 11)
            val turnEvent = TurnFinalized(newTurn, replacement, emptyList(), listOf(MessageMember(newMessage)), AgentTurnOutcome.SUCCEEDED)
            val result = explicit.commitExplicitUserDeleteResolution(space, AgentSyncExplicitDeleteResolution(
                MutationId(id(636)), AgentHlcSnapshot(636, 0, localReplica), resolution,
                listOf(
                    AgentSyncOutboundEventDraft(MutationId(id(637)), AgentHlcSnapshot(637, 0, localReplica), createdEvent),
                    AgentSyncOutboundEventDraft(MutationId(id(638)), AgentHlcSnapshot(638, 0, localReplica), messageEvent),
                    AgentSyncOutboundEventDraft(MutationId(id(639)), AgentHlcSnapshot(639, 0, localReplica), turnEvent),
                ),
            ))

            assertEquals(4, result.size)
            assertEquals(listOf(resolution, createdEvent, messageEvent, turnEvent), result.map { it.agentEvent })
            assertTrue(persistence.isThreadTombstoned(space, thread.value))
            assertTrue(persistence.threadHistoryProjection(space, thread).turns.isEmpty())
            assertEquals(1L, db.useReaderConnection { it.scalarLong("SELECT count(*) FROM agent_sync_thread_tombstone WHERE sync_space_id = ? AND thread_id = ?", space.value, thread.value) })

            val matchingRemoteResolution = payload(650, resolution, replica(4), context = listOf(
                AgentVersionComponent(replica(2), 0), AgentVersionComponent(replica(3), 0),
            ))
            persistence.acceptInbound(space, matchingRemoteResolution)
            persistence.markHandled(space, id(650))
            val converged = persistence.threadHistoryProjection(space, thread)
            assertEquals(AgentSemanticConflictState.RESOLVED, converged.conflicts.single { it.kind == AgentSemanticConflictKind.THREAD_DELETE_APPEND }.state)
            assertFalse(converged.conflicts.any { it.kind == AgentSemanticConflictKind.DELETE_RESOLUTION })
        } finally { db.close() }
    }

    @Test fun `Agent sync structural validation rejects a dropped unique index`() = runBlocking {
        val db = openInMemoryDesktopDatabase()
        try {
            val before = db.useReaderConnection { it.agentSyncCatalogSql() }
            AgentSyncSchema.validateCatalog(before)
            db.useWriterConnection { it.exec("DROP INDEX agent_sync_record_identity_idx") }
            val corrupted = db.useReaderConnection { it.agentSyncCatalogSql() }
            assertFailsWith<IllegalStateException> { AgentSyncSchema.validateCatalog(corrupted) }
            Unit
        } finally { db.close() }
    }

    @Test fun `agent replica identities and handled dots stay per SyncSpace`() = runBlocking {
        val db = openInMemoryDesktopDatabase()
        try {
            val persistence = RoomAgentSyncPersistence(db)
            val a = SyncSpaceId("a")
            val b = SyncSpaceId("b")
            persistence.provisionLocalReplica(a, replica(1))
            persistence.provisionLocalReplica(b, replica(1))
            val inboundA = payload(60, ThreadTitleSet(AgentThreadSyncId(id(61)), "A"), replica(3))
            val inboundB = payload(62, ThreadTitleSet(AgentThreadSyncId(id(63)), "B"), replica(4))
            persistence.acceptInbound(a, inboundA)
            persistence.markHandled(a, id(60))
            persistence.acceptInbound(b, inboundB)
            persistence.markHandled(b, id(62))
            assertEquals(AgentSyncFrontierState(mapOf(replica(3) to 0L)), persistence.dvvFrontier(a))
            assertEquals(AgentSyncFrontierState(mapOf(replica(4) to 0L)), persistence.dvvFrontier(b))
            assertEquals(1L, db.useReaderConnection { it.scalarLong("SELECT count(*) FROM agent_sync_handled_dot WHERE sync_space_id = ?", a.value) })
            assertEquals(1L, db.useReaderConnection { it.scalarLong("SELECT count(*) FROM agent_sync_handled_dot WHERE sync_space_id = ?", b.value) })
            assertEquals(AgentSyncLocalClock(replica(1), 0), persistence.localReplica(a))
            assertEquals(AgentSyncLocalClock(replica(1), 0), persistence.localReplica(b))
            assertFailsWith<IllegalArgumentException> { persistence.provisionLocalReplica(a, replica(3)) }
            Unit
        } finally { db.close() }
    }

    @Test fun `C1 starts at zero and concurrent local allocations are atomic across restart`() = runBlocking {
        val path = Files.createTempFile("agent-local-clock-", ".db")
        val space = SyncSpaceId("personal")
        val local = replica(1)
        val events = (0 until 24).map { index ->
            MutationId(id(100 + index)) to ThreadCreated(AgentThreadSyncId(id(1000 + index)), "thread-$index", index.toLong())
        }
        try {
            val db = openDesktopDatabase(path.toAbsolutePath().toString())
            val persistence = RoomAgentSyncPersistence(db)
            assertEquals(AgentSyncLocalClock(local, 0), persistence.provisionLocalReplica(space, local))
            assertEquals(AgentSyncFrontierState(emptyMap()), persistence.dvvFrontier(space))
            val created = coroutineScope {
                events.mapIndexed { index, (operationId, event) ->
                    async(Dispatchers.Default) {
                        persistence.enqueueOutbound(space, operationId, AgentHlcSnapshot(index.toLong(), 0, local), event)
                    }
                }.awaitAll()
            }
            assertEquals((0L until events.size.toLong()).toList(), created.map { it.agentDvv.dot.counter }.sorted())
            created.forEach { operation ->
                val counter = operation.agentDvv.dot.counter
                val authorContext = operation.agentDvv.context.singleOrNull { it.replicaId == local }?.counter
                assertEquals(if (counter == 0L) null else counter - 1L, authorContext)
            }
            assertEquals(AgentSyncLocalClock(local, events.size.toLong()), persistence.localReplica(space))
            assertEquals(AgentSyncFrontierState(mapOf(local to events.size.toLong() - 1L)), persistence.dvvFrontier(space))
            val retry = created.first()
            assertEquals(retry, persistence.enqueueOutbound(space, retry.operationId, retry.hlc, retry.agentEvent))
            assertEquals(AgentSyncLocalClock(local, events.size.toLong()), persistence.localReplica(space))
            db.close()

            val reopened = openDesktopDatabase(path.toAbsolutePath().toString())
            try {
                val recovered = RoomAgentSyncPersistence(reopened)
                assertEquals(AgentSyncLocalClock(local, events.size.toLong()), recovered.localReplica(space))
                assertEquals(AgentSyncFrontierState(mapOf(local to events.size.toLong() - 1L)), recovered.dvvFrontier(space))
            } finally { reopened.close() }
        } finally { Files.deleteIfExists(path) }
    }

    @Test fun `C1 remote gaps remain pending until contiguous dots catch up`() = runBlocking {
        val db = openInMemoryDesktopDatabase()
        try {
            val persistence = RoomAgentSyncPersistence(db)
            val space = SyncSpaceId("personal")
            val remote = replica(2)
            persistence.provisionLocalReplica(space, replica(1))
            val turn = AgentTurnSyncId(id(173))
            val dot2 = payload(72, TurnFinalized(turn, AgentThreadSyncId(id(172)), emptyList(), emptyList(), AgentTurnOutcome.SUCCEEDED), remote, 2, listOf(AgentVersionComponent(remote, 1)))
            persistence.acceptInbound(space, dot2)
            assertEquals(listOf(id(72)), persistence.pendingDependencies(space, id(72)).map { it.operationId })
            assertEquals(emptyList(), persistence.eligibleInboundOperations(space).map { it.operationId.value })
            assertFailsWith<IllegalStateException> { persistence.markHandled(space, id(72)) }

            persistence.acceptInbound(space, payload(70, ThreadTitleSet(AgentThreadSyncId(id(170)), "zero"), remote))
            assertEquals(listOf(id(70)), persistence.eligibleInboundOperations(space).map { it.operationId.value })
            persistence.markHandled(space, id(70))
            assertEquals(AgentSyncFrontierState(mapOf(remote to 0L)), persistence.dvvFrontier(space))
            assertEquals(1, persistence.pendingDependencies(space, id(72)).size)

            persistence.acceptInbound(space, payload(71, ThreadTitleSet(AgentThreadSyncId(id(171)), "one"), remote, 1, listOf(AgentVersionComponent(remote, 0))))
            assertEquals(listOf(id(71)), persistence.eligibleInboundOperations(space).map { it.operationId.value })
            persistence.markHandled(space, id(71))
            assertEquals(AgentSyncFrontierState(mapOf(remote to 1L)), persistence.dvvFrontier(space))
            assertTrue(persistence.pendingDependencies(space, id(72)).isEmpty())
            assertEquals(listOf(id(72)), persistence.eligibleInboundOperations(space).map { it.operationId.value })
            persistence.markHandled(space, id(72))
            assertEquals(AgentSyncFrontierState(mapOf(remote to 2L)), persistence.dvvFrontier(space))
            assertEquals(AgentSyncTurnState.COMPLETE_VERIFIED, persistence.turnState(space, turn.value))
        } finally { db.close() }
    }

    @Test fun `C1 rejects bad author predecessor and counter overflow without reuse`() = runBlocking {
        val db = openInMemoryDesktopDatabase()
        try {
            val persistence = RoomAgentSyncPersistence(db)
            val space = SyncSpaceId("personal")
            val local = replica(1)
            persistence.provisionLocalReplica(space, local)
            val malformed = payload(80, ThreadTitleSet(AgentThreadSyncId(id(180)), "bad"), replica(2), 2, listOf(AgentVersionComponent(replica(2), 0)))
            assertFailsWith<IllegalArgumentException> { persistence.acceptInbound(space, malformed) }

            db.useWriterConnection {
                it.exec("UPDATE agent_sync_space_state SET local_counter = ? WHERE sync_space_id = ?", Long.MAX_VALUE, space.value)
                it.exec("INSERT INTO agent_sync_dvv_frontier(sync_space_id, agent_replica_id, counter) VALUES (?, ?, ?)", space.value, local.value, Long.MAX_VALUE - 1)
            }
            assertFailsWith<IllegalStateException> {
                persistence.enqueueOutbound(space, MutationId(id(81)), AgentHlcSnapshot(81, 0, local), ThreadCreated(AgentThreadSyncId(id(181)), "overflow", 81))
            }
            assertEquals(0L, db.useReaderConnection { it.scalarLong("SELECT count(*) FROM agent_sync_outbox WHERE sync_space_id = ?", space.value) })
        } finally { db.close() }
    }

    private fun payload(
        operation: Int,
        event: AgentSyncEvent,
        dotReplica: AgentReplicaId = replica(100 + operation),
        counter: Long = 0,
        context: List<AgentVersionComponent> = emptyList(),
    ) = SyncPayloadV3(operation = AgentSyncOperation(
        MutationId(id(operation)), AgentDvvSnapshot(context, AgentDot(dotReplica, counter)), AgentHlcSnapshot(counter, 0, dotReplica), event,
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

private suspend fun PooledConnection.agentSyncCatalogSql(): Map<String, String> = usePrepared(
    "SELECT name, sql FROM sqlite_master WHERE type IN ('table','index') AND name LIKE 'agent_sync_%' AND sql IS NOT NULL ORDER BY name",
) { statement ->
    buildMap { while (statement.step()) put(statement.getText(0), statement.getText(1)) }
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
