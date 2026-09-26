package dev.agenticscheduler.server.sync

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import java.security.MessageDigest
import java.util.Base64
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class JdbcRecoveryEnrollmentIdempotencyTest {
    @Test
    fun `lost acknowledgment retry is proof gated and immutable identity is enforced`() {
        withDatabase { dataSource, repository ->
            val fixture = fixture(dataSource, "lost-ack")
            val first = enrollment(fixture, proof(41), counter = 7, nextProof = proof(42))
            assertEquals(RecoveryEnrollmentResult.Created(fixture.accountId, fixture.deviceId), repository.enrollWithRecovery(first))

            val retry = enrollment(fixture, proof(42), counter = 8, nextProof = proof(43))
            assertEquals(RecoveryEnrollmentResult.Idempotent(fixture.accountId, fixture.deviceId), repository.enrollWithRecovery(retry))
            assertProof(dataSource, fixture.accountId, proof(42), 8)
            assertEquals(1, count(dataSource, "SELECT COUNT(*) FROM recovery_enrollment_request WHERE request_id = ?", fixture.requestId))
            assertEquals(1, count(dataSource, "SELECT COUNT(*) FROM device WHERE device_id = ?", fixture.deviceId))
            assertEquals(1, count(dataSource, "SELECT COUNT(*) FROM sync_space_membership WHERE sync_space_id = ?", fixture.spaceId))
            assertEquals(32, bytes(dataSource, "SELECT request_fingerprint FROM recovery_enrollment_request WHERE request_id = ?", fixture.requestId)!!.size)

            val changedKey = retry.copy(hpkePublicKeyBase64Url = encode(bytesOf(31)))
            assertEquals(RecoveryEnrollmentResult.RequestIdentityConflict, repository.enrollWithRecovery(changedKey))
            val changedCredential = retry.copy(credentialHashBase64Url = encode(bytesOf(32)))
            assertEquals(RecoveryEnrollmentResult.RequestIdentityConflict, repository.enrollWithRecovery(changedCredential))
            val changedTarget = retry.copy(targetDeviceId = "recovery-target-changed-${fixture.suffix}")
            assertEquals(RecoveryEnrollmentResult.RequestIdentityConflict, repository.enrollWithRecovery(changedTarget))
            assertEquals(
                RecoveryEnrollmentResult.InvalidProof,
                repository.enrollWithRecovery(retry.copy(proofBase64Url = encode(proof(99)))),
            )
            assertProof(dataSource, fixture.accountId, proof(42), 8)
            assertEquals(1, count(dataSource, "SELECT COUNT(*) FROM device WHERE account_id = ?", fixture.accountId))
        }
    }

    @Test
    fun `legacy completion without fingerprint fails closed after current proof validation`() {
        withDatabase { dataSource, repository ->
            val fixture = fixture(dataSource, "legacy", withDevice = true)
            dataSource.connection.use { connection ->
                connection.prepareStatement(
                    "INSERT INTO recovery_enrollment_request(request_id, account_id, target_device_id) VALUES (?, ?, ?)",
                ).use { statement ->
                    statement.setString(1, fixture.requestId)
                    statement.setString(2, fixture.accountId)
                    statement.setString(3, fixture.deviceId)
                    statement.executeUpdate()
                }
            }
            val currentRequest = enrollment(fixture, proof(1), counter = 0, nextProof = proof(2))
            assertEquals(RecoveryEnrollmentResult.RequestIdentityConflict, repository.enrollWithRecovery(currentRequest))
            assertEquals(RecoveryEnrollmentResult.InvalidProof, repository.enrollWithRecovery(currentRequest.copy(proofBase64Url = encode(proof(9)))))
            assertProof(dataSource, fixture.accountId, proof(1), 0)
            assertNull(bytes(dataSource, "SELECT request_fingerprint FROM recovery_enrollment_request WHERE request_id = ?", fixture.requestId))
        }
    }

    @Test
    fun `concurrent same request attempts serialize on account proof`() {
        val jdbcUrl = System.getenv("SYNC_TEST_DATABASE_URL") ?: return
        val suffix = UUID.randomUUID().toString().replace("-", "")
        val fixture = fixtureIds(suffix, "race")
        val trigger = "d8_recovery_pause_$suffix"
        val function = "d8_recovery_pause_fn_$suffix"
        val source = dataSource(jdbcUrl)
        val repository = JdbcOpaqueSyncRepository(source)
        val executor = Executors.newFixedThreadPool(2)
        try {
            ServerSchemaMigrator(source).migrate()
            seed(source, fixture)
            installInsertPause(source, fixture.accountId, trigger, function)

            val request = enrollment(fixture, proof(41), counter = 7, nextProof = proof(42))
            val first = executor.submit<RecoveryEnrollmentResult> { repository.enrollWithRecovery(request) }
            awaitActivity(source, "wait_event = 'PgSleep' AND query ILIKE '%INSERT INTO recovery_enrollment_request%'")
            val second = executor.submit<RecoveryEnrollmentResult> { repository.enrollWithRecovery(request) }
            awaitActivity(source, "wait_event_type = 'Lock' AND query ILIKE '%SELECT account_id FROM account%'")

            assertEquals(RecoveryEnrollmentResult.Created(fixture.accountId, fixture.deviceId), first.get(12, TimeUnit.SECONDS))
            assertEquals(RecoveryEnrollmentResult.InvalidProof, second.get(12, TimeUnit.SECONDS))
            assertProof(source, fixture.accountId, proof(42), 8)
            assertEquals(1, count(source, "SELECT COUNT(*) FROM recovery_enrollment_request WHERE request_id = ?", fixture.requestId))
            assertEquals(1, count(source, "SELECT COUNT(*) FROM device WHERE device_id = ?", fixture.deviceId))
        } finally {
            executor.shutdownNow()
            cleanup(source, fixture, trigger, function)
            source.close()
        }
    }

    private fun withDatabase(block: (HikariDataSource, JdbcOpaqueSyncRepository) -> Unit) {
        val jdbcUrl = System.getenv("SYNC_TEST_DATABASE_URL") ?: return
        val suffix = UUID.randomUUID().toString().replace("-", "")
        val fixture = fixtureIds(suffix, "retry")
        val source = dataSource(jdbcUrl)
        try {
            ServerSchemaMigrator(source).migrate()
            seed(source, fixture)
            block(source, JdbcOpaqueSyncRepository(source))
        } finally {
            cleanup(source, fixture)
            source.close()
        }
    }

    private fun dataSource(url: String) = HikariDataSource(HikariConfig().apply {
        jdbcUrl = url
        username = System.getenv("SYNC_TEST_DATABASE_USER") ?: "agentic"
        password = System.getenv("SYNC_TEST_DATABASE_PASSWORD") ?: "agentic-test"
        maximumPoolSize = 4
        connectionInitSql = "SET application_name = 'd8-recovery-idempotency-test'"
    })

    private data class Fixture(
        val suffix: String,
        val accountId: String,
        val spaceId: String,
        val deviceId: String,
        val requestId: String,
    )

    private fun fixture(dataSource: HikariDataSource, label: String, withDevice: Boolean = false): Fixture {
        val suffix = UUID.randomUUID().toString().replace("-", "")
        val value = fixtureIds(suffix, label)
        seed(dataSource, value, withDevice)
        return value
    }

    private fun fixtureIds(suffix: String, label: String) = Fixture(
        suffix = suffix,
        accountId = "d8-recovery-$label-account-$suffix",
        spaceId = "d8-recovery-$label-space-$suffix",
        deviceId = "d8-recovery-$label-target-$suffix",
        requestId = "d8-recovery-$label-request-$suffix",
    )

    private fun seed(dataSource: HikariDataSource, fixture: Fixture, withDevice: Boolean = false) {
        dataSource.connection.use { connection ->
            connection.autoCommit = false
            try {
                connection.prepareStatement("INSERT INTO account(account_id) VALUES (?)").use { statement ->
                    statement.setString(1, fixture.accountId)
                    statement.executeUpdate()
                }
                connection.prepareStatement("INSERT INTO sync_space(sync_space_id, account_id) VALUES (?, ?)").use { statement ->
                    statement.setString(1, fixture.spaceId)
                    statement.setString(2, fixture.accountId)
                    statement.executeUpdate()
                }
                if (withDevice) {
                    connection.prepareStatement(
                        "INSERT INTO device(device_id, account_id, credential_hash, hpke_public_key) VALUES (?, ?, ?, ?)",
                    ).use { statement ->
                        statement.setString(1, fixture.deviceId)
                        statement.setString(2, fixture.accountId)
                        statement.setBytes(3, bytesOf(70))
                        statement.setBytes(4, bytesOf(71))
                        statement.executeUpdate()
                    }
                }
                connection.prepareStatement("INSERT INTO recovery_proof(account_id, proof_hash, counter) VALUES (?, ?, ?)").use { statement ->
                    statement.setString(1, fixture.accountId)
                    statement.setBytes(2, sha256(proof(if (withDevice) 1 else 41)))
                    statement.setLong(3, if (withDevice) 0 else 7)
                    statement.executeUpdate()
                }
                connection.commit()
            } catch (failure: Throwable) {
                connection.rollback()
                throw failure
            }
        }
    }

    private fun enrollment(fixture: Fixture, currentProof: ByteArray, counter: Long, nextProof: ByteArray) = RecoveryEnrollmentRequestWire(
        accountId = fixture.accountId,
        requestId = fixture.requestId,
        targetDeviceId = fixture.deviceId,
        hpkePublicKeyBase64Url = encode(bytesOf(11)),
        credentialHashBase64Url = encode(bytesOf(12)),
        proofBase64Url = encode(currentProof),
        counter = counter,
        nextProofHashBase64Url = encode(sha256(nextProof)),
    )

    private fun assertProof(dataSource: HikariDataSource, accountId: String, currentProof: ByteArray, counter: Long) {
        dataSource.connection.use { connection ->
            connection.prepareStatement("SELECT proof_hash, counter FROM recovery_proof WHERE account_id = ?").use { statement ->
                statement.setString(1, accountId)
                statement.executeQuery().use { result ->
                    assertTrue(result.next())
                    assertEquals(counter, result.getLong("counter"))
                    assertContentEquals(sha256(currentProof), result.getBytes("proof_hash"))
                }
            }
        }
    }

    private fun count(dataSource: HikariDataSource, sql: String, parameter: String): Int =
        dataSource.connection.use { connection ->
            connection.prepareStatement(sql).use { statement ->
                statement.setString(1, parameter)
                statement.executeQuery().use { result -> result.next(); result.getInt(1) }
            }
        }

    private fun bytes(dataSource: HikariDataSource, sql: String, parameter: String): ByteArray? =
        dataSource.connection.use { connection ->
            connection.prepareStatement(sql).use { statement ->
                statement.setString(1, parameter)
                statement.executeQuery().use { result -> if (result.next()) result.getBytes(1) else null }
            }
        }

    private fun installInsertPause(dataSource: HikariDataSource, accountId: String, trigger: String, function: String) {
        dataSource.connection.use { connection ->
            connection.createStatement().use { statement ->
                statement.execute(
                    "CREATE FUNCTION $function() RETURNS trigger LANGUAGE plpgsql AS " +
                        "\$\$ BEGIN IF NEW.account_id = '$accountId' THEN PERFORM pg_sleep(3); END IF; RETURN NEW; END \$\$",
                )
                statement.execute(
                    "CREATE TRIGGER $trigger BEFORE INSERT ON recovery_enrollment_request " +
                        "FOR EACH ROW EXECUTE FUNCTION $function()",
                )
            }
        }
    }

    private fun awaitActivity(dataSource: HikariDataSource, condition: String) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (System.nanoTime() < deadline) {
            val found = dataSource.connection.use { connection ->
                connection.createStatement().use { statement ->
                    statement.executeQuery(
                        "SELECT EXISTS (SELECT 1 FROM pg_stat_activity " +
                            "WHERE application_name = 'd8-recovery-idempotency-test' AND $condition)",
                    ).use { result -> result.next() && result.getBoolean(1) }
                }
            }
            if (found) return
            Thread.sleep(20)
        }
        error("Timed out waiting for PostgreSQL activity: $condition")
    }

    private fun cleanup(dataSource: HikariDataSource, fixture: Fixture, trigger: String? = null, function: String? = null) {
        runCatching {
            dataSource.connection.use { connection ->
                if (trigger != null) connection.createStatement().use { it.execute("DROP TRIGGER IF EXISTS $trigger ON recovery_enrollment_request") }
                if (function != null) connection.createStatement().use { it.execute("DROP FUNCTION IF EXISTS $function()") }
                connection.prepareStatement("DELETE FROM recovery_enrollment_request WHERE account_id = ?").use { it.setString(1, fixture.accountId); it.executeUpdate() }
                connection.prepareStatement("DELETE FROM sync_space_membership WHERE sync_space_id = ?").use { it.setString(1, fixture.spaceId); it.executeUpdate() }
                connection.prepareStatement("DELETE FROM recovery_proof WHERE account_id = ?").use { it.setString(1, fixture.accountId); it.executeUpdate() }
                connection.prepareStatement("DELETE FROM sync_space WHERE sync_space_id = ?").use { it.setString(1, fixture.spaceId); it.executeUpdate() }
                connection.prepareStatement("DELETE FROM device WHERE account_id = ?").use { it.setString(1, fixture.accountId); it.executeUpdate() }
                connection.prepareStatement("DELETE FROM account WHERE account_id = ?").use { it.setString(1, fixture.accountId); it.executeUpdate() }
            }
        }
    }

    private fun proof(value: Int) = ByteArray(32) { value.toByte() }
    private fun bytesOf(value: Int) = ByteArray(32) { (value + it).toByte() }
    private fun sha256(value: ByteArray) = MessageDigest.getInstance("SHA-256").digest(value)
    private fun encode(value: ByteArray) = Base64.getUrlEncoder().withoutPadding().encodeToString(value)
}
