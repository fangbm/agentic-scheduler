package dev.agenticscheduler.server.sync

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ServerSchemaTest {
    @Test
    fun `migration catalog is ordered and contains only opaque transport columns`() {
        val migrations = ServerMigrationCatalog.migrations
        assertTrue(migrations.isNotEmpty())
        assertTrue(migrations.map(ServerMigration::version) == migrations.map(ServerMigration::version).sorted())
        migrations.forEach { migration ->
            val sql = javaClass.classLoader.getResourceAsStream(migration.resource)!!.bufferedReader().use { it.readText() }
            assertFalse(sql.contains("title", ignoreCase = true))
            assertFalse(sql.contains("dvv", ignoreCase = true))
            assertFalse(sql.contains("hlc", ignoreCase = true))
            assertFalse(sql.contains("DROP TABLE", ignoreCase = true))
        }
        val relaySql = javaClass.classLoader.getResourceAsStream(migrations.first().resource)!!.bufferedReader().use { it.readText() }
        assertTrue(relaySql.contains("encrypted_operation_envelope"))
        assertTrue(relaySql.contains("ciphertext BYTEA"))
        assertTrue(relaySql.contains("server_cursor"))
        val invitationSql = javaClass.classLoader.getResourceAsStream(migrations[1].resource)!!.bufferedReader().use { it.readText() }
        assertTrue(invitationSql.contains("account_invitation"))
        val enrollmentSql = javaClass.classLoader.getResourceAsStream(migrations[2].resource)!!.bufferedReader().use { it.readText() }
        assertTrue(enrollmentSql.contains("device_enrollment_request"))
        assertTrue(enrollmentSql.contains("device_key_package"))
    }
}
