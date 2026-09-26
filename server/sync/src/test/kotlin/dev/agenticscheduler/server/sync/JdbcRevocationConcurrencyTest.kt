package dev.agenticscheduler.server.sync

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class JdbcRevocationConcurrencyTest {
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
                    "DELETE FROM sync_key_rotation_package WHERE rotation_id IN (?, ?)",
                ).use { statement ->
                    statement.setString(1, "d8-revoke-race-rotation-a-${accountId.removePrefix("d8-revoke-race-")}")
                    statement.setString(2, "d8-revoke-race-rotation-b-${accountId.removePrefix("d8-revoke-race-")}")
                    statement.executeUpdate()
                }
                connection.prepareStatement("DELETE FROM sync_key_rotation WHERE account_id = ?").use { statement ->
                    statement.setString(1, accountId)
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
                connection.prepareStatement("DELETE FROM device WHERE device_id IN (?, ?, ?)").use { statement ->
                    statement.setString(1, deviceA)
                    statement.setString(2, deviceB)
                    statement.setString(3, deviceC)
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
