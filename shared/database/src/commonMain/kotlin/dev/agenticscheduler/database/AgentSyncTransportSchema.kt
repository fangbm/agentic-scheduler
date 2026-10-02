package dev.agenticscheduler.database

import androidx.room3.migration.Migration
import androidx.sqlite.SQLiteConnection

/** v14 extension: no new Room entities or business causal metadata. */
internal object AgentSyncTransportSchema {
    val statements = listOf(
        "CREATE TABLE IF NOT EXISTS agent_sync_transport_consent (sync_space_id TEXT NOT NULL PRIMARY KEY, enabled INTEGER NOT NULL CHECK(enabled IN (0,1)), all_active_devices_v3_acknowledged INTEGER NOT NULL CHECK(all_active_devices_v3_acknowledged IN (0,1)), CHECK(enabled = 0 OR all_active_devices_v3_acknowledged = 1))",
        "CREATE TABLE IF NOT EXISTS agent_sync_outbound_envelope (sync_space_id TEXT NOT NULL, operation_id TEXT NOT NULL, envelope_json TEXT NOT NULL, PRIMARY KEY(sync_space_id, operation_id), FOREIGN KEY(sync_space_id, operation_id) REFERENCES agent_sync_outbox(sync_space_id, operation_id) ON DELETE RESTRICT)",
    )
    val tableNames = statements.map { it.substringAfter("EXISTS ").substringBefore(" (") }.toSet()
    fun create(connection: SQLiteConnection) = statements.forEach { connection.prepare(it).use { stmt -> stmt.step() } }
    fun validate(connection: SQLiteConnection) {
        statements.forEach { expected ->
            val name = expected.substringAfter("EXISTS ").substringBefore(" (")
            connection.prepare("SELECT sql FROM sqlite_master WHERE type = 'table' AND name = ?").use { stmt ->
                stmt.bindText(1, name)
                check(stmt.step() && normalize(stmt.getText(0)) == normalize(expected)) { "Agent transport schema mismatch: $name" }
            }
        }
    }
    private fun normalize(sql: String): String = sql.replace(Regex("\\bIF\\s+NOT\\s+EXISTS\\b", RegexOption.IGNORE_CASE), "")
        .replace(Regex("\\s+"), " ").trim().lowercase()
}

/** Existing v13 operations/outbox remain pending; absent consent means OFF. */
internal object AgentSyncTransportMigration13To14 : Migration(13, 14) {
    override suspend fun migrate(connection: SQLiteConnection) = AgentSyncTransportSchema.create(connection)
}
