package dev.agenticscheduler.server.sync

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import dev.agenticscheduler.sync.DeviceId
import dev.agenticscheduler.sync.EncryptedEnvelopeV1
import dev.agenticscheduler.sync.SyncSpaceId
import java.util.Base64
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class JdbcRevocationConcurrencyTest {
    @Test
    fun `revoked device cannot approve enrollment after rotation obtains account lock`() {
        verifyRevokedWriter(
            prepare = { race ->
                val request = EnrollmentRequestWire(
                    race.accountId, "d8-enrollment-${race.suffix}", "d8-target-${race.suffix}",
                    encodedBytes(5), encodedBytes(6),
                )
                assertTrue(race.repository.registerEnrollment(request, 3600) is EnrollmentRegistrationResult.Created)
            },
            write = { race ->
                race.repository.approveEnrollment(
                    AuthenticatedDevice(race.accountId, race.deviceB),
                    "d8-enrollment-${race.suffix}", byteArrayOf(7),
                )
            },
            verify = { race, result ->
                assertEquals(EnrollmentApprovalResult.NotFound, result)
                assertEquals(0L, countRows(race.dataSource, "SELECT count(*) FROM device WHERE device_id = ?", "d8-target-${race.suffix}"))
                assertEquals(0L, countRows(race.dataSource, "SELECT count(*) FROM device_key_package WHERE request_id = ?", "d8-enrollment-${race.suffix}"))
            },
        )
    }

    @Test
    fun `revoked device cannot register recovery proof after rotation obtains account lock`() {
        verifyRevokedWriter(
            write = { race ->
                race.repository.registerRecoveryProof(
                    AuthenticatedDevice(race.accountId, race.deviceB),
                    RecoveryProofRegistrationRequest(encodedBytes(7), 0),
                )
            },
            verify = { race, result ->
                assertEquals(RecoveryProofRegistrationResult.NotFound, result)
                assertEquals(0L, countRows(race.dataSource, "SELECT count(*) FROM recovery_proof WHERE account_id = ?", race.accountId))
            },
        )
    }

    @Test
    fun `revoked device cannot upload after rotation obtains account lock`() {
        verifyRevokedWriter(
            write = { race ->
                race.repository.upload(
                    AuthenticatedDevice(race.accountId, race.deviceB), race.spaceId,
                    EncryptedEnvelopeV1(
                        syncSpaceId = SyncSpaceId(race.spaceId),
                        mutationId = "d8-upload-${race.suffix}",
                        senderDeviceId = DeviceId(race.deviceB),
                        keyEpoch = 0,
                        ciphertextBase64Url = "AQI",
                    ), byteArrayOf(1, 2),
                )
            },
            verify = { race, result ->
                assertEquals(UploadOutcome.NotFound, result)
                assertEquals(0L, countRows(race.dataSource, "SELECT count(*) FROM encrypted_operation_envelope WHERE sync_space_id = ?", race.spaceId))
            },
        )
    }

    @Test
    fun `revoked device cannot overwrite recovery envelope after rotation obtains account lock`() {
        val jdbcUrl = System.getenv("SYNC_TEST_DATABASE_URL") ?: return
        val suffix = UUID.randomUUID().toString().replace("-", "")
        val accountId = "d8-revoke-race-$suffix"
        val spaceId = "d8-revoke-race-space-$suffix"
        val deviceA = "d8-revoke-race-a-$suffix"
        val deviceB = "d8-revoke-race-b-$suffix"
        val deviceC = "d8-revoke-race-c-$suffix"
        val rotationId = "d8-revoke-race-rotation-a-$suffix"
        val triggerName = "d8_pause_rotation_$suffix"
        val functionName = "d8_pause_rotation_fn_$suffix"
        val dataSource = HikariDataSource(HikariConfig().apply {
            this.jdbcUrl = jdbcUrl
            username = System.getenv("SYNC_TEST_DATABASE_USER") ?: "agentic"
            password = System.getenv("SYNC_TEST_DATABASE_PASSWORD") ?: "agentic-test"
            maximumPoolSize = 4
            connectionInitSql = "SET application_name = 'd8-revoke-race-test'"
        })
        val executor = Executors.newFixedThreadPool(2)
        val repository = JdbcOpaqueSyncRepository(dataSource)
        try {
            ServerSchemaMigrator(dataSource).migrate()
            seedAccount(dataSource, accountId, spaceId, deviceA, deviceB, deviceC)
            installRotationPause(dataSource, accountId, triggerName, functionName)

            val rotation = executor.submit<AtomicRevocationResult> {
                repository.revokeAndRotate(
                    AuthenticatedDevice(accountId, deviceA), deviceB,
                    rotation(rotationId, deviceA, deviceC),
                )
            }
            awaitRotationInsertSleep(dataSource)

            val staleWriter = executor.submit<Boolean> {
                repository.saveRecoveryEnvelope(AuthenticatedDevice(accountId, deviceB), byteArrayOf(9, 9))
            }
            awaitAccountLockWait(dataSource)

            assertEquals(AtomicRevocationResult.Applied, rotation.get(10, TimeUnit.SECONDS))
            assertEquals(false, staleWriter.get(10, TimeUnit.SECONDS))
            assertContentEquals(byteArrayOf(1, 2), repository.fetchRecoveryEnvelope(AuthenticatedDevice(accountId, deviceA)))
        } finally {
            executor.shutdownNow()
            cleanup(dataSource, accountId, spaceId, deviceA, deviceB, deviceC, triggerName, functionName)
            dataSource.close()
        }
    }

    @Test
    fun `actor revoked while waiting for account lock cannot revoke another device`() {
        val jdbcUrl = System.getenv("SYNC_TEST_DATABASE_URL") ?: return
        val suffix = UUID.randomUUID().toString().replace("-", "")
        val accountId = "d8-revoke-race-$suffix"
        val spaceId = "d8-revoke-race-space-$suffix"
        val deviceA = "d8-revoke-race-a-$suffix"
        val deviceB = "d8-revoke-race-b-$suffix"
        val deviceC = "d8-revoke-race-c-$suffix"
        val rotationA = "d8-revoke-race-rotation-a-$suffix"
        val rotationB = "d8-revoke-race-rotation-b-$suffix"
        val triggerName = "d8_pause_rotation_$suffix"
        val functionName = "d8_pause_rotation_fn_$suffix"
        val dataSource = HikariDataSource(HikariConfig().apply {
            this.jdbcUrl = jdbcUrl
            username = System.getenv("SYNC_TEST_DATABASE_USER") ?: "agentic"
            password = System.getenv("SYNC_TEST_DATABASE_PASSWORD") ?: "agentic-test"
            maximumPoolSize = 4
            connectionInitSql = "SET application_name = 'd8-revoke-race-test'"
        })
        val executor = Executors.newFixedThreadPool(2)
        val repository = JdbcOpaqueSyncRepository(dataSource)
        try {
            ServerSchemaMigrator(dataSource).migrate()
            seedAccount(dataSource, accountId, spaceId, deviceA, deviceB, deviceC)
            installRotationPause(dataSource, accountId, triggerName, functionName)

            val first = executor.submit<AtomicRevocationResult> {
                repository.revokeAndRotate(
                    AuthenticatedDevice(accountId, deviceA),
                    deviceB,
                    rotation(rotationA, deviceA, deviceC),
                )
            }
            awaitRotationInsertSleep(dataSource)

            val second = executor.submit<AtomicRevocationResult> {
                repository.revokeAndRotate(
                    AuthenticatedDevice(accountId, deviceB),
                    deviceA,
                    rotation(rotationB, deviceB, deviceC),
                )
            }
            awaitAccountLockWait(dataSource)

            assertEquals(AtomicRevocationResult.Applied, first.get(10, TimeUnit.SECONDS))
            assertEquals(AtomicRevocationResult.NotFound, second.get(10, TimeUnit.SECONDS))
            dataSource.connection.use { connection ->
                connection.prepareStatement("SELECT revoked_at FROM device WHERE device_id = ?").use { statement ->
                    statement.setString(1, deviceA)
                    statement.executeQuery().use { result ->
                        assertTrue(result.next())
                        assertEquals(null, result.getTimestamp(1))
                    }
                }
                connection.prepareStatement("SELECT revoked_at FROM device WHERE device_id = ?").use { statement ->
                    statement.setString(1, deviceB)
                    statement.executeQuery().use { result ->
                        assertTrue(result.next())
                        assertTrue(result.getTimestamp(1) != null)
                    }
                }
            }
        } finally {
            executor.shutdownNow()
            cleanup(dataSource, accountId, spaceId, deviceA, deviceB, deviceC, triggerName, functionName)
            dataSource.close()
        }
    }

    private fun seedAccount(dataSource: HikariDataSource, accountId: String, spaceId: String, vararg devices: String) {
        dataSource.connection.use { connection ->
            connection.autoCommit = false
            try {
                connection.prepareStatement("INSERT INTO account(account_id) VALUES (?)").use { statement ->
                    statement.setString(1, accountId)
                    statement.executeUpdate()
                }
                connection.prepareStatement("INSERT INTO sync_space(sync_space_id, account_id) VALUES (?, ?)").use { statement ->
                    statement.setString(1, spaceId)
                    statement.setString(2, accountId)
                    statement.executeUpdate()
                }
                devices.forEachIndexed { index, deviceId ->
                    connection.prepareStatement(
                        "INSERT INTO device(device_id, account_id, credential_hash, hpke_public_key) VALUES (?, ?, ?, ?)",
                    ).use { statement ->
                        statement.setString(1, deviceId)
                        statement.setString(2, accountId)
                        statement.setBytes(3, ByteArray(32) { (index + 1).toByte() })
                        statement.setBytes(4, ByteArray(32) { (index + 4).toByte() })
                        statement.executeUpdate()
                    }
                    connection.prepareStatement("INSERT INTO sync_space_membership(sync_space_id, device_id) VALUES (?, ?)").use { statement ->
                        statement.setString(1, spaceId)
                        statement.setString(2, deviceId)
                        statement.executeUpdate()
                    }
                }
                connection.commit()
            } catch (failure: Throwable) {
                connection.rollback()
                throw failure
            }
        }
    }

    private data class RaceContext(
        val suffix: String,
        val accountId: String,
        val spaceId: String,
        val deviceA: String,
        val deviceB: String,
        val deviceC: String,
        val dataSource: HikariDataSource,
        val repository: JdbcOpaqueSyncRepository,
    )

    private fun <T> verifyRevokedWriter(
        prepare: (RaceContext) -> Unit = {},
        write: (RaceContext) -> T,
        verify: (RaceContext, T) -> Unit,
    ) {
        val jdbcUrl = System.getenv("SYNC_TEST_DATABASE_URL") ?: return
        val suffix = UUID.randomUUID().toString().replace("-", "")
        val accountId = "d8-revoke-race-$suffix"
        val spaceId = "d8-revoke-race-space-$suffix"
        val deviceA = "d8-revoke-race-a-$suffix"
        val deviceB = "d8-revoke-race-b-$suffix"
        val deviceC = "d8-revoke-race-c-$suffix"
        val triggerName = "d8_pause_rotation_$suffix"
        val functionName = "d8_pause_rotation_fn_$suffix"
        val dataSource = HikariDataSource(HikariConfig().apply {
            this.jdbcUrl = jdbcUrl
            username = System.getenv("SYNC_TEST_DATABASE_USER") ?: "agentic"
            password = System.getenv("SYNC_TEST_DATABASE_PASSWORD") ?: "agentic-test"
            maximumPoolSize = 4
            connectionInitSql = "SET application_name = 'd8-revoke-race-test'"
        })
        val executor = Executors.newFixedThreadPool(2)
        val race = RaceContext(suffix, accountId, spaceId, deviceA, deviceB, deviceC, dataSource, JdbcOpaqueSyncRepository(dataSource))
        try {
            ServerSchemaMigrator(dataSource).migrate()
            seedAccount(dataSource, accountId, spaceId, deviceA, deviceB, deviceC)
            prepare(race)
            installRotationPause(dataSource, accountId, triggerName, functionName)

            val rotation = executor.submit<AtomicRevocationResult> {
                race.repository.revokeAndRotate(
                    AuthenticatedDevice(accountId, deviceA), deviceB,
                    rotation("d8-revoke-race-rotation-a-$suffix", deviceA, deviceC),
                )
            }
            awaitRotationInsertSleep(dataSource)
            val staleWriter = executor.submit<T> { write(race) }
            awaitAccountLockWait(dataSource)

            assertEquals(AtomicRevocationResult.Applied, rotation.get(10, TimeUnit.SECONDS))
            verify(race, staleWriter.get(10, TimeUnit.SECONDS))
        } finally {
            executor.shutdownNow()
            cleanup(dataSource, accountId, spaceId, deviceA, deviceB, deviceC, triggerName, functionName)
            dataSource.close()
        }
    }

    private fun encodedBytes(value: Byte) = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32) { value })

    private fun countRows(dataSource: HikariDataSource, query: String, id: String): Long = dataSource.connection.use { connection ->
        connection.prepareStatement(query).use { statement ->
            statement.setString(1, id)
            statement.executeQuery().use { result ->
                assertTrue(result.next())
                result.getLong(1)
            }
        }
    }

    private fun installRotationPause(dataSource: HikariDataSource, accountId: String, triggerName: String, functionName: String) {
        dataSource.connection.use { connection ->
            connection.createStatement().use { statement ->
                statement.execute(
                    "CREATE FUNCTION $functionName() RETURNS trigger LANGUAGE plpgsql AS " +
                        "\$\$ BEGIN IF NEW.account_id = '$accountId' THEN PERFORM pg_sleep(4); END IF; " +
                        "RETURN NEW; END \$\$",
                )
                statement.execute(
                    "CREATE TRIGGER $triggerName BEFORE INSERT ON sync_key_rotation " +
                        "FOR EACH ROW EXECUTE FUNCTION $functionName()",
                )
            }
        }
    }

    private fun awaitRotationInsertSleep(dataSource: HikariDataSource) {
        awaitActivity(dataSource, "wait_event = 'PgSleep' AND query ILIKE '%INSERT INTO sync_key_rotation%'")
    }

    private fun awaitAccountLockWait(dataSource: HikariDataSource) {
        awaitActivity(dataSource, "wait_event_type = 'Lock' AND query ILIKE '%SELECT account_id FROM account%'")
    }

    private fun awaitActivity(dataSource: HikariDataSource, condition: String) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (System.nanoTime() < deadline) {
            val found = dataSource.connection.use { connection ->
                connection.createStatement().use { statement ->
                    statement.executeQuery(
                        "SELECT EXISTS (SELECT 1 FROM pg_stat_activity WHERE application_name = 'd8-revoke-race-test' AND $condition)",
                    ).use { result -> result.next() && result.getBoolean(1) }
                }
            }
            if (found) return
            Thread.sleep(20)
        }
        error("Timed out waiting for PostgreSQL activity: $condition")
    }

    private fun rotation(rotationId: String, vararg remainingDevices: String) = AtomicRevocationRequest(
        rotationId = rotationId,
        recoveryEnvelopeBase64Url = "AQI",
        packages = remainingDevices.map { RotationPackageUpload(it, "AwQ") },
    )

    private fun cleanup(
        dataSource: HikariDataSource,
        accountId: String,
        spaceId: String,
        deviceA: String,
        deviceB: String,
        deviceC: String,
        triggerName: String,
        functionName: String,
    ) {
        runCatching {
            dataSource.connection.use { connection ->
                connection.createStatement().use { statement ->
                    statement.execute("DROP TRIGGER IF EXISTS $triggerName ON sync_key_rotation")
                    statement.execute("DROP FUNCTION IF EXISTS $functionName()")
                }
                connection.prepareStatement(
                    "DELETE FROM sync_key_rotation_package WHERE rotation_id IN (SELECT rotation_id FROM sync_key_rotation WHERE account_id = ?)",
                ).use { statement ->
                    statement.setString(1, accountId)
                    statement.executeUpdate()
                }
                connection.prepareStatement("DELETE FROM sync_key_rotation WHERE account_id = ?").use { statement ->
                    statement.setString(1, accountId)
                    statement.executeUpdate()
                }
                connection.prepareStatement("DELETE FROM device_key_package WHERE request_id IN (SELECT request_id FROM device_enrollment_request WHERE account_id = ?)").use { statement ->
                    statement.setString(1, accountId)
                    statement.executeUpdate()
                }
                connection.prepareStatement("DELETE FROM device_enrollment_request WHERE account_id = ?").use { statement ->
                    statement.setString(1, accountId)
                    statement.executeUpdate()
                }
                connection.prepareStatement("DELETE FROM recovery_enrollment_request WHERE account_id = ?").use { statement ->
                    statement.setString(1, accountId)
                    statement.executeUpdate()
                }
                connection.prepareStatement("DELETE FROM recovery_proof WHERE account_id = ?").use { statement ->
                    statement.setString(1, accountId)
                    statement.executeUpdate()
                }
                connection.prepareStatement("DELETE FROM encrypted_operation_envelope WHERE sync_space_id = ?").use { statement ->
                    statement.setString(1, spaceId)
                    statement.executeUpdate()
                }
                connection.prepareStatement("DELETE FROM sync_space_membership WHERE sync_space_id = ?").use { statement ->
                    statement.setString(1, spaceId)
                    statement.executeUpdate()
                }
                connection.prepareStatement("DELETE FROM sync_space WHERE sync_space_id = ?").use { statement ->
                    statement.setString(1, spaceId)
                    statement.executeUpdate()
                }
                connection.prepareStatement("DELETE FROM recovery_envelope WHERE account_id = ?").use { statement ->
                    statement.setString(1, accountId)
                    statement.executeUpdate()
                }
                connection.prepareStatement("DELETE FROM device WHERE account_id = ?").use { statement ->
                    statement.setString(1, accountId)
                    statement.executeUpdate()
                }
                connection.prepareStatement("DELETE FROM account WHERE account_id = ?").use { statement ->
                    statement.setString(1, accountId)
                    statement.executeUpdate()
                }
            }
        }
    }
}
