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

/** Explicit v11→v12 migration; prior Room tables and data are untouched. */
internal object AgentMigration11To12 : Migration(11, 12) {
    override suspend fun migrate(connection: SQLiteConnection) = AgentSchema.create(connection)
}

/** Fresh v12 installs need the same tables as upgraded v11 installs. */
internal object AgentSchemaCallback : RoomDatabase.Callback() {
    override suspend fun onCreate(connection: SQLiteConnection) = AgentSchema.create(connection)
    override suspend fun onOpen(connection: SQLiteConnection) = AgentSchema.validate(connection)
}
