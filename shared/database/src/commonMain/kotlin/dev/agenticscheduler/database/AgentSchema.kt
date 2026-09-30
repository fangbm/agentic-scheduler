package dev.agenticscheduler.database

import androidx.room3.RoomDatabase
import androidx.room3.migration.Migration
import androidx.sqlite.SQLiteConnection

/**
 * D9's eight local tables share the existing Room connection and transaction.
 * Room 3's generated single onValidateSchema method exceeds the JVM bytecode
 * limit when these tables are added as @Entity to the already-large D8 database.
 * This explicit catalog is the v12 schema contract and is checked on every open.
 */
internal object AgentSchema {
    private val createStatements = listOf(
        "CREATE TABLE IF NOT EXISTS agent_thread (thread_id TEXT NOT NULL PRIMARY KEY, created_at_epoch_millis INTEGER NOT NULL, payload_json TEXT NOT NULL)",
        "CREATE TABLE IF NOT EXISTS agent_message (message_id TEXT NOT NULL PRIMARY KEY, thread_id TEXT NOT NULL, ordinal INTEGER NOT NULL, payload_json TEXT NOT NULL, UNIQUE(thread_id, ordinal))",
        "CREATE INDEX IF NOT EXISTS agent_message_thread_idx ON agent_message(thread_id, ordinal)",
        "CREATE TABLE IF NOT EXISTS agent_tool_call (call_id TEXT NOT NULL PRIMARY KEY, thread_id TEXT NOT NULL, ordinal INTEGER NOT NULL, payload_json TEXT NOT NULL)",
        "CREATE INDEX IF NOT EXISTS agent_tool_call_thread_idx ON agent_tool_call(thread_id, ordinal)",
        "CREATE TABLE IF NOT EXISTS agent_tool_result (result_id TEXT NOT NULL PRIMARY KEY, thread_id TEXT NOT NULL, call_id TEXT NOT NULL UNIQUE, ordinal INTEGER NOT NULL, payload_json TEXT NOT NULL)",
        "CREATE INDEX IF NOT EXISTS agent_tool_result_thread_idx ON agent_tool_result(thread_id, ordinal)",
        "CREATE TABLE IF NOT EXISTS context_summary (summary_id TEXT NOT NULL PRIMARY KEY, thread_id TEXT NOT NULL, created_at_epoch_millis INTEGER NOT NULL, payload_json TEXT NOT NULL)",
        "CREATE INDEX IF NOT EXISTS context_summary_thread_idx ON context_summary(thread_id, created_at_epoch_millis)",
        "CREATE TABLE IF NOT EXISTS agent_action (action_id TEXT NOT NULL PRIMARY KEY, thread_id TEXT, payload_json TEXT NOT NULL)",
        "CREATE INDEX IF NOT EXISTS agent_action_thread_idx ON agent_action(thread_id, action_id)",
        "CREATE TABLE IF NOT EXISTS agent_permission_policy (capability TEXT NOT NULL PRIMARY KEY, mode TEXT NOT NULL)",
        "CREATE TABLE IF NOT EXISTS provider_config (config_id TEXT NOT NULL PRIMARY KEY, selected INTEGER NOT NULL DEFAULT 0 CHECK(selected IN (0,1)), credential_secret_ref TEXT, payload_json TEXT NOT NULL)",
    )

    private val validationQueries = listOf(
        "SELECT thread_id, created_at_epoch_millis, payload_json FROM agent_thread LIMIT 0",
        "SELECT message_id, thread_id, ordinal, payload_json FROM agent_message LIMIT 0",
        "SELECT call_id, thread_id, ordinal, payload_json FROM agent_tool_call LIMIT 0",
        "SELECT result_id, thread_id, call_id, ordinal, payload_json FROM agent_tool_result LIMIT 0",
        "SELECT summary_id, thread_id, created_at_epoch_millis, payload_json FROM context_summary LIMIT 0",
        "SELECT action_id, thread_id, payload_json FROM agent_action LIMIT 0",
        "SELECT capability, mode FROM agent_permission_policy LIMIT 0",
        "SELECT config_id, selected, credential_secret_ref, payload_json FROM provider_config LIMIT 0",
    )

    fun create(connection: SQLiteConnection) {
        createStatements.forEach { sql -> connection.prepare(sql).use { it.step() } }
    }

    fun validate(connection: SQLiteConnection) {
        validationQueries.forEach { sql -> connection.prepare(sql).use { it.step() } }
    }
}

/** D9-02 Agent sync catalog. These tables intentionally stay outside Room @Entity validation. */
internal object AgentSyncSchema {
    private val createStatements = listOf(
        "CREATE TABLE IF NOT EXISTS agent_sync_operation_identity (sync_space_id TEXT NOT NULL, operation_id TEXT NOT NULL, agent_replica_id TEXT NOT NULL, agent_counter INTEGER NOT NULL CHECK(agent_counter >= 0), event_type TEXT NOT NULL, immutable_record_kind TEXT, immutable_record_id TEXT, payload_json TEXT NOT NULL, PRIMARY KEY(sync_space_id, operation_id), UNIQUE(sync_space_id, agent_replica_id, agent_counter))",
        "CREATE UNIQUE INDEX IF NOT EXISTS agent_sync_record_identity_idx ON agent_sync_operation_identity(sync_space_id, immutable_record_kind, immutable_record_id) WHERE immutable_record_kind IS NOT NULL",
        "CREATE TABLE IF NOT EXISTS agent_sync_inbox (sync_space_id TEXT NOT NULL, operation_id TEXT NOT NULL, state TEXT NOT NULL CHECK(state IN ('PENDING','HANDLED')), PRIMARY KEY(sync_space_id, operation_id), FOREIGN KEY(sync_space_id, operation_id) REFERENCES agent_sync_operation_identity(sync_space_id, operation_id) ON DELETE RESTRICT)",
        "CREATE INDEX IF NOT EXISTS agent_sync_inbox_state_idx ON agent_sync_inbox(sync_space_id, state, operation_id)",
        "CREATE TABLE IF NOT EXISTS agent_sync_outbox (sync_space_id TEXT NOT NULL, operation_id TEXT NOT NULL, state TEXT NOT NULL CHECK(state IN ('READY','HELD','UPLOADED')), PRIMARY KEY(sync_space_id, operation_id), FOREIGN KEY(sync_space_id, operation_id) REFERENCES agent_sync_operation_identity(sync_space_id, operation_id) ON DELETE RESTRICT)",
        "CREATE INDEX IF NOT EXISTS agent_sync_outbox_state_idx ON agent_sync_outbox(sync_space_id, state, operation_id)",
        "CREATE TABLE IF NOT EXISTS agent_sync_space_state (sync_space_id TEXT NOT NULL PRIMARY KEY, local_replica_id TEXT NOT NULL, local_counter INTEGER NOT NULL CHECK(local_counter >= 0), dvv_frontier_version INTEGER NOT NULL DEFAULT 1)",
        "CREATE TABLE IF NOT EXISTS agent_sync_dvv_frontier (sync_space_id TEXT NOT NULL, agent_replica_id TEXT NOT NULL, counter INTEGER NOT NULL CHECK(counter >= 0), PRIMARY KEY(sync_space_id, agent_replica_id), FOREIGN KEY(sync_space_id) REFERENCES agent_sync_space_state(sync_space_id) ON DELETE RESTRICT)",
        "CREATE TABLE IF NOT EXISTS agent_sync_handled_dot (sync_space_id TEXT NOT NULL, agent_replica_id TEXT NOT NULL, counter INTEGER NOT NULL CHECK(counter >= 0), operation_id TEXT NOT NULL, PRIMARY KEY(sync_space_id, agent_replica_id, counter), UNIQUE(sync_space_id, operation_id), FOREIGN KEY(sync_space_id, operation_id) REFERENCES agent_sync_operation_identity(sync_space_id, operation_id) ON DELETE RESTRICT)",
        "CREATE TABLE IF NOT EXISTS agent_sync_pending_dependency (sync_space_id TEXT NOT NULL, operation_id TEXT NOT NULL, dependency_kind TEXT NOT NULL CHECK(dependency_kind IN ('AGENT_DOT','PARENT_RECORD','BUSINESS_MUTATION','TURN_MEMBER','PARENT_TURN')), dependency_key TEXT NOT NULL, PRIMARY KEY(sync_space_id, operation_id, dependency_kind, dependency_key), FOREIGN KEY(sync_space_id, operation_id) REFERENCES agent_sync_operation_identity(sync_space_id, operation_id) ON DELETE RESTRICT)",
        "CREATE INDEX IF NOT EXISTS agent_sync_pending_dependency_lookup_idx ON agent_sync_pending_dependency(sync_space_id, dependency_kind, dependency_key)",
        "CREATE TABLE IF NOT EXISTS agent_sync_turn_stage (sync_space_id TEXT NOT NULL, turn_id TEXT NOT NULL, thread_id TEXT NOT NULL, manifest_operation_id TEXT NOT NULL, outcome TEXT NOT NULL, state TEXT NOT NULL CHECK(state IN ('INCOMPLETE','COMPLETE_VERIFIED','TOMBSTONED')), PRIMARY KEY(sync_space_id, turn_id), FOREIGN KEY(sync_space_id, manifest_operation_id) REFERENCES agent_sync_operation_identity(sync_space_id, operation_id) ON DELETE RESTRICT)",
        "CREATE INDEX IF NOT EXISTS agent_sync_turn_thread_state_idx ON agent_sync_turn_stage(sync_space_id, thread_id, state, turn_id)",
        "CREATE TABLE IF NOT EXISTS agent_sync_turn_member (sync_space_id TEXT NOT NULL, turn_id TEXT NOT NULL, member_ordinal INTEGER NOT NULL CHECK(member_ordinal >= 0), member_kind TEXT NOT NULL, member_id TEXT NOT NULL, present INTEGER NOT NULL DEFAULT 0 CHECK(present IN (0,1)), PRIMARY KEY(sync_space_id, turn_id, member_ordinal), UNIQUE(sync_space_id, turn_id, member_kind, member_id), FOREIGN KEY(sync_space_id, turn_id) REFERENCES agent_sync_turn_stage(sync_space_id, turn_id) ON DELETE RESTRICT)",
        "CREATE TABLE IF NOT EXISTS agent_sync_active_turn_projection (sync_space_id TEXT NOT NULL, turn_id TEXT NOT NULL, thread_id TEXT NOT NULL, PRIMARY KEY(sync_space_id, turn_id), FOREIGN KEY(sync_space_id, turn_id) REFERENCES agent_sync_turn_stage(sync_space_id, turn_id) ON DELETE RESTRICT)",
        "CREATE INDEX IF NOT EXISTS agent_sync_active_projection_thread_idx ON agent_sync_active_turn_projection(sync_space_id, thread_id, turn_id)",
        "CREATE TABLE IF NOT EXISTS agent_sync_thread_tombstone (sync_space_id TEXT NOT NULL, thread_id TEXT NOT NULL, operation_id TEXT NOT NULL, agent_replica_id TEXT NOT NULL, counter INTEGER NOT NULL CHECK(counter >= 0), dvv_json TEXT NOT NULL, PRIMARY KEY(sync_space_id, operation_id), FOREIGN KEY(sync_space_id, operation_id) REFERENCES agent_sync_operation_identity(sync_space_id, operation_id) ON DELETE RESTRICT)",
        "CREATE INDEX IF NOT EXISTS agent_sync_tombstone_thread_idx ON agent_sync_thread_tombstone(sync_space_id, thread_id, counter, agent_replica_id)",
        "CREATE TABLE IF NOT EXISTS agent_sync_conflict (conflict_id TEXT NOT NULL PRIMARY KEY, sync_space_id TEXT NOT NULL, entity_kind TEXT NOT NULL CHECK(entity_kind IN ('THREAD','TURN','MESSAGE','TOOL_CALL','TOOL_RESULT','ACTION','TOMBSTONE')), entity_id TEXT NOT NULL, conflict_kind TEXT NOT NULL, local_operation_id TEXT, remote_operation_id TEXT, candidate_payload_json TEXT NOT NULL, metadata_json TEXT NOT NULL)",
        "CREATE INDEX IF NOT EXISTS agent_sync_conflict_entity_idx ON agent_sync_conflict(sync_space_id, entity_kind, entity_id, conflict_id)",
        "CREATE TABLE IF NOT EXISTS agent_sync_audit_parent_link (sync_space_id TEXT NOT NULL, action_id TEXT NOT NULL, thread_id TEXT, parent_kind TEXT NOT NULL CHECK(parent_kind IN ('MESSAGE','TOOL_CALL','TOOL_RESULT','TURN')), parent_id TEXT NOT NULL, state TEXT NOT NULL CHECK(state IN ('PARENT_PENDING','PARENT_VERIFIED','PARENT_REMOVED_BY_TOMBSTONE')), PRIMARY KEY(sync_space_id, action_id, parent_kind, parent_id))",
        "CREATE INDEX IF NOT EXISTS agent_sync_audit_parent_state_idx ON agent_sync_audit_parent_link(sync_space_id, parent_kind, parent_id, state)",
        "CREATE TABLE IF NOT EXISTS agent_sync_backfill_state (sync_space_id TEXT NOT NULL PRIMARY KEY, cursor INTEGER NOT NULL CHECK(cursor >= 0), recovery_state TEXT NOT NULL CHECK(recovery_state IN ('IDLE','REQUIRED','RUNNING','INCOMPLETE','COMPLETE')), earliest_quarantined_cursor INTEGER, updated_at_epoch_millis INTEGER NOT NULL)",
    )

    private val validationQueries = listOf(
        "SELECT sync_space_id, operation_id, agent_replica_id, agent_counter, event_type, immutable_record_kind, immutable_record_id, payload_json FROM agent_sync_operation_identity LIMIT 0",
        "SELECT sync_space_id, operation_id, state FROM agent_sync_inbox LIMIT 0",
        "SELECT sync_space_id, operation_id, state FROM agent_sync_outbox LIMIT 0",
        "SELECT sync_space_id, local_replica_id, local_counter, dvv_frontier_version FROM agent_sync_space_state LIMIT 0",
        "SELECT sync_space_id, agent_replica_id, counter FROM agent_sync_dvv_frontier LIMIT 0",
        "SELECT sync_space_id, agent_replica_id, counter, operation_id FROM agent_sync_handled_dot LIMIT 0",
        "SELECT sync_space_id, operation_id, dependency_kind, dependency_key FROM agent_sync_pending_dependency LIMIT 0",
        "SELECT sync_space_id, turn_id, thread_id, manifest_operation_id, outcome, state FROM agent_sync_turn_stage LIMIT 0",
        "SELECT sync_space_id, turn_id, member_ordinal, member_kind, member_id, present FROM agent_sync_turn_member LIMIT 0",
        "SELECT sync_space_id, turn_id, thread_id FROM agent_sync_active_turn_projection LIMIT 0",
        "SELECT sync_space_id, thread_id, operation_id, agent_replica_id, counter, dvv_json FROM agent_sync_thread_tombstone LIMIT 0",
        "SELECT conflict_id, sync_space_id, entity_kind, entity_id, conflict_kind, local_operation_id, remote_operation_id, candidate_payload_json, metadata_json FROM agent_sync_conflict LIMIT 0",
        "SELECT sync_space_id, action_id, thread_id, parent_kind, parent_id, state FROM agent_sync_audit_parent_link LIMIT 0",
        "SELECT sync_space_id, cursor, recovery_state, earliest_quarantined_cursor, updated_at_epoch_millis FROM agent_sync_backfill_state LIMIT 0",
    )

    fun create(connection: SQLiteConnection) { createStatements.forEach { connection.prepare(it).use { statement -> statement.step() } } }
    fun validate(connection: SQLiteConnection) { validationQueries.forEach { connection.prepare(it).use { statement -> statement.step() } } }
}

/** Explicit v11→v12 migration; prior Room tables and data are untouched. */
internal object AgentMigration11To12 : Migration(11, 12) {
    override suspend fun migrate(connection: SQLiteConnection) = AgentSchema.create(connection)
}

/** v12→v13 adds only isolated Agent sync tables; all v12 records remain untouched. */
internal object AgentSyncMigration12To13 : Migration(12, 13) {
    override suspend fun migrate(connection: SQLiteConnection) = AgentSyncSchema.create(connection)
}

/** Fresh v12 installs need the same tables as upgraded v11 installs. */
internal object AgentSchemaCallback : RoomDatabase.Callback() {
    override suspend fun onCreate(connection: SQLiteConnection) {
        AgentSchema.create(connection)
        AgentSyncSchema.create(connection)
    }
    override suspend fun onOpen(connection: SQLiteConnection) {
        AgentSchema.validate(connection)
        AgentSyncSchema.validate(connection)
    }
}
