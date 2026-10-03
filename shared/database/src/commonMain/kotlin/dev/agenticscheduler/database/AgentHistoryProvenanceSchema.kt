package dev.agenticscheduler.database

import androidx.room3.RoomDatabase
import androidx.room3.migration.Migration
import androidx.sqlite.SQLiteConnection

/** Local-only first-alpha provenance and explicit export retry identities. */
internal object AgentHistoryProvenanceSchema {
    private val statements = listOf(
        "CREATE TABLE IF NOT EXISTS agent_local_thread_provenance (thread_id TEXT NOT NULL PRIMARY KEY, state TEXT NOT NULL CHECK(state IN ('TRACKED','LEGACY_UNVERIFIED','DELETED')), creation_title TEXT, creation_at_epoch_millis INTEGER, CHECK((state = 'TRACKED' AND creation_at_epoch_millis IS NOT NULL) OR (state = 'LEGACY_UNVERIFIED' AND creation_title IS NULL AND creation_at_epoch_millis IS NULL) OR state = 'DELETED'))",
        "CREATE TABLE IF NOT EXISTS agent_local_turn_provenance (start_order INTEGER PRIMARY KEY AUTOINCREMENT, thread_id TEXT NOT NULL, turn_id TEXT NOT NULL, parent_turn_ids_json TEXT NOT NULL, ancestry_verified INTEGER NOT NULL CHECK(ancestry_verified IN (0,1)), lifecycle TEXT NOT NULL CHECK(lifecycle IN ('RUNNING','AWAITING_CONFIRMATION','FINALIZED','INCOMPLETE')), outcome TEXT CHECK(outcome IN ('SUCCEEDED','FAILED')), UNIQUE(thread_id, turn_id), CHECK((lifecycle = 'FINALIZED' AND outcome IS NOT NULL) OR (lifecycle != 'FINALIZED' AND outcome IS NULL)))",
        "CREATE INDEX IF NOT EXISTS agent_local_turn_thread_order_idx ON agent_local_turn_provenance(thread_id, start_order)",
        "CREATE TABLE IF NOT EXISTS agent_local_turn_member (thread_id TEXT NOT NULL, turn_id TEXT NOT NULL, member_order INTEGER NOT NULL CHECK(member_order >= 0), member_kind TEXT NOT NULL CHECK(member_kind IN ('MESSAGE','TOOL_CALL','TOOL_RESULT','ACTION')), member_id TEXT NOT NULL, finalized INTEGER NOT NULL CHECK(finalized IN (0,1)), snapshot_json TEXT, PRIMARY KEY(thread_id, turn_id, member_order), UNIQUE(thread_id, turn_id, member_kind, member_id), FOREIGN KEY(thread_id, turn_id) REFERENCES agent_local_turn_provenance(thread_id, turn_id) ON DELETE RESTRICT)",
        "CREATE TABLE IF NOT EXISTS agent_history_export_mapping (sync_space_id TEXT NOT NULL, source_kind TEXT NOT NULL, source_id TEXT NOT NULL, operation_id TEXT NOT NULL, hlc_json TEXT NOT NULL, event_json TEXT NOT NULL, state TEXT NOT NULL CHECK(state IN ('PREPARED','QUEUED')), PRIMARY KEY(sync_space_id, source_kind, source_id), UNIQUE(sync_space_id, operation_id))",
    )

    private val validation = listOf(
        "SELECT thread_id, state, creation_title, creation_at_epoch_millis FROM agent_local_thread_provenance LIMIT 0",
        "SELECT start_order, thread_id, turn_id, parent_turn_ids_json, ancestry_verified, lifecycle, outcome FROM agent_local_turn_provenance LIMIT 0",
        "SELECT thread_id, turn_id, member_order, member_kind, member_id, finalized, snapshot_json FROM agent_local_turn_member LIMIT 0",
        "SELECT sync_space_id, source_kind, source_id, operation_id, hlc_json, event_json, state FROM agent_history_export_mapping LIMIT 0",
    )

    fun create(connection: SQLiteConnection) = statements.forEach { connection.prepare(it).use { statement -> statement.step() } }

    fun validate(connection: SQLiteConnection) {
        validation.forEach { connection.prepare(it).use { it.step() } }
        fun name(sql: String) = sql.substringAfter("EXISTS ").substringBefore(" (").substringBefore(' ')
        val names = statements.map(::name).toSet()
        val actual = connection.prepare("SELECT name, sql FROM sqlite_master WHERE (type IN ('table','index') AND name LIKE 'agent_local_%') OR (type = 'table' AND name = 'agent_history_export_mapping')").use { statement ->
            buildMap { while (statement.step()) put(statement.getText(0), normalize(statement.getText(1))) }
        }
        val expected = statements.associate { sql -> name(sql) to normalize(sql) }
        check(actual.keys == names && actual == expected) {
            "Agent local provenance schema mismatch. Expected ${expected.keys.sorted()}, found ${actual.keys.sorted()}."
        }
    }

    private fun normalize(sql: String) = sql.replace(Regex("\\bIF\\s+NOT\\s+EXISTS\\b", RegexOption.IGNORE_CASE), "")
        .replace(Regex("\\s+"), " ").trim().lowercase()
}

/** Every pre-v15 local thread is explicitly unverified; migration makes no historical guesses. */
internal object AgentHistoryProvenanceMigration14To15 : Migration(14, 15) {
    override suspend fun migrate(connection: SQLiteConnection) {
        AgentHistoryProvenanceSchema.create(connection)
        connection.prepare(
            "INSERT INTO agent_local_thread_provenance(thread_id, state, creation_title, creation_at_epoch_millis) " +
                "SELECT thread_id, 'LEGACY_UNVERIFIED', NULL, NULL FROM agent_thread",
        ).use { it.step() }
    }
}

internal object AgentHistoryProvenanceSchemaCallback : RoomDatabase.Callback() {
    override suspend fun onCreate(connection: SQLiteConnection) = AgentHistoryProvenanceSchema.create(connection)
    override suspend fun onOpen(connection: SQLiteConnection) = AgentHistoryProvenanceSchema.validate(connection)
}
