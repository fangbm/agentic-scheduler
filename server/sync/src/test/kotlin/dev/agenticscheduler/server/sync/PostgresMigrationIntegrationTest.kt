package dev.agenticscheduler.server.sync

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import kotlin.test.Test
import kotlin.test.assertEquals

class PostgresMigrationIntegrationTest {
    @Test
    fun `numbered server migrations apply and replay`() {
        val url = System.getenv("SYNC_TEST_DATABASE_URL") ?: return
        val dataSource = HikariDataSource(
            HikariConfig().apply {
                jdbcUrl = url
                username = System.getenv("SYNC_TEST_DATABASE_USER") ?: "agentic"
                password = System.getenv("SYNC_TEST_DATABASE_PASSWORD") ?: "agentic-test"
                maximumPoolSize = 2
            },
        )
        try {
            ServerSchemaMigrator(dataSource).migrate()
            ServerSchemaMigrator(dataSource).migrate()
            dataSource.connection.use { connection ->
                connection.createStatement().use { statement ->
                    statement.executeQuery("SELECT MAX(version) FROM agentic_server_schema_version").use { result ->
                        result.next()
                        assertEquals(ServerMigrationCatalog.migrations.maxOf { it.version }, result.getInt(1))
                    }
                }
                connection.createStatement().use { statement ->
                    statement.executeQuery(
                        "SELECT COUNT(*) FROM information_schema.tables WHERE table_name IN " +
                            "('account', 'encrypted_operation_envelope', 'device_enrollment_request', 'recovery_envelope')",
                    ).use { result ->
                        result.next()
                        assertEquals(4, result.getInt(1))
                    }
                }
            }
        } finally {
            dataSource.close()
        }
    }
}
