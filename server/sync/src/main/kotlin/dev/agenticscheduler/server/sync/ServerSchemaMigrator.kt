package dev.agenticscheduler.server.sync

import java.sql.Connection
import javax.sql.DataSource

data class ServerMigration(val version: Int, val resource: String)

object ServerMigrationCatalog {
    val migrations = listOf(
        ServerMigration(1, "db/migration/V1__opaque_sync.sql"),
        ServerMigration(2, "db/migration/V2__account_invitations.sql"),
    )
}

class ServerSchemaMigrator(private val dataSource: DataSource) {
    fun migrate() {
        dataSource.connection.use { connection ->
            connection.autoCommit = false
            try {
                connection.createStatement().use {
                    it.execute(
                        "CREATE TABLE IF NOT EXISTS agentic_server_schema_version " +
                            "(version INTEGER PRIMARY KEY, applied_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP)",
                    )
                }
                val current = connection.createStatement().use { statement ->
                    statement.executeQuery("SELECT COALESCE(MAX(version), 0) FROM agentic_server_schema_version").use { rs ->
                        rs.next()
                        rs.getInt(1)
                    }
                }
                val ordered = ServerMigrationCatalog.migrations.sortedBy(ServerMigration::version)
                require(ordered.map(ServerMigration::version).distinct().size == ordered.size) { "Duplicate server migration version." }
                val latest = ordered.maxOfOrNull(ServerMigration::version) ?: 0
                check(current <= latest) { "Database schema $current is newer than binary schema $latest." }
                ordered.filter { it.version > current }.forEach { migration ->
                    val sql = javaClass.classLoader.getResourceAsStream(migration.resource)
                        ?.bufferedReader()
                        ?.use { it.readText() }
                        ?: error("Missing server migration resource ${migration.resource}.")
                    connection.createStatement().use { it.execute(sql) }
                    connection.prepareStatement(
                        "INSERT INTO agentic_server_schema_version(version) VALUES (?)",
                    ).use { statement ->
                        statement.setInt(1, migration.version)
                        statement.executeUpdate()
                    }
                }
                connection.commit()
            } catch (failure: Throwable) {
                connection.rollback()
                throw failure
            } finally {
                connection.autoCommit = true
            }
        }
    }
}
