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
        val sql = javaClass.classLoader.getResourceAsStream(migrations.single().resource)!!.bufferedReader().use { it.readText() }
        assertTrue(sql.contains("encrypted_operation_envelope"))
        assertTrue(sql.contains("ciphertext BYTEA"))
        assertTrue(sql.contains("server_cursor"))
        assertFalse(sql.contains("title", ignoreCase = true))
        assertFalse(sql.contains("dvv", ignoreCase = true))
        assertFalse(sql.contains("hlc", ignoreCase = true))
        assertFalse(sql.contains("DROP TABLE", ignoreCase = true))
    }
}
