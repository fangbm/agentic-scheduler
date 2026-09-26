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
        val recoverySql = javaClass.classLoader.getResourceAsStream(migrations[3].resource)!!.bufferedReader().use { it.readText() }
        assertTrue(recoverySql.contains("recovery_envelope"))
        val enrollmentCredentialSql = javaClass.classLoader.getResourceAsStream(migrations[4].resource)!!.bufferedReader().use { it.readText() }
        assertTrue(enrollmentCredentialSql.contains("credential_hash"))
        val recoveryProofSql = javaClass.classLoader.getResourceAsStream(migrations[5].resource)!!.bufferedReader().use { it.readText() }
        assertTrue(recoveryProofSql.contains("recovery_proof"))
        val rotationSql = javaClass.classLoader.getResourceAsStream(migrations[6].resource)!!.bufferedReader().use { it.readText() }
        assertTrue(rotationSql.contains("sync_key_rotation"))
        val deviceHpkeSql = javaClass.classLoader.getResourceAsStream(migrations[7].resource)!!.bufferedReader().use { it.readText() }
        assertTrue(deviceHpkeSql.contains("hpke_public_key"))
        assertTrue(deviceHpkeSql.contains("octet_length"))
        val recoveryIdentitySql = javaClass.classLoader.getResourceAsStream(migrations[8].resource)!!.bufferedReader().use { it.readText() }
        assertTrue(recoveryIdentitySql.contains("request_fingerprint"))
        assertTrue(recoveryIdentitySql.contains("octet_length"))
    }
}
