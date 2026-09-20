package dev.agenticscheduler.server.sync

import dev.agenticscheduler.sync.DeviceId
import dev.agenticscheduler.sync.EncryptedEnvelopeV1
import dev.agenticscheduler.sync.SyncSpaceId
import java.security.MessageDigest
import java.sql.Connection
import java.sql.ResultSet
import java.sql.Timestamp
import javax.sql.DataSource

class JdbcOpaqueSyncRepository(private val dataSource: DataSource) : OpaqueSyncRepository {
    override fun authenticate(credential: String): AuthenticatedDevice? {
        val hash = sha256(credential)
        return dataSource.connection.use { connection ->
            connection.prepareStatement(
                "SELECT account_id, device_id FROM device WHERE credential_hash = ? AND revoked_at IS NULL",
            ).use { statement ->
                statement.setBytes(1, hash)
                statement.executeQuery().use { rs ->
                    if (!rs.next()) null else AuthenticatedDevice(rs.getString("account_id"), rs.getString("device_id"))
                }
            }
        }
    }

    override fun upload(
        actor: AuthenticatedDevice,
        spaceId: String,
        envelope: EncryptedEnvelopeV1,
        ciphertext: ByteArray,
    ): UploadOutcome = dataSource.connection.use connection@{ connection ->
        connection.autoCommit = false
        try {
            if (!isMember(connection, actor, spaceId)) return@connection UploadOutcome.NotFound
            val existing = findEnvelope(connection, spaceId, envelope.mutationId)
            if (existing != null) {
                connection.commit()
                return@connection if (sameEnvelope(existing, envelope, ciphertext)) {
                    UploadOutcome.Idempotent(existing.serverCursor)
                } else {
                    UploadOutcome.IntegrityConflict
                }
            }
            val nextCursor = connection.prepareStatement(
                "SELECT next_cursor FROM sync_space WHERE sync_space_id = ? FOR UPDATE",
            ).use { statement ->
                statement.setString(1, spaceId)
                statement.executeQuery().use { rs ->
                    if (!rs.next()) null else rs.getLong(1) + 1
                }
            } ?: return@connection UploadOutcome.NotFound
            connection.prepareStatement("UPDATE sync_space SET next_cursor = ? WHERE sync_space_id = ?").use { statement ->
                statement.setLong(1, nextCursor)
                statement.setString(2, spaceId)
                statement.executeUpdate()
            }
            connection.prepareStatement(
                "INSERT INTO encrypted_operation_envelope " +
                    "(sync_space_id, server_cursor, mutation_id, sender_device_id, key_epoch, ciphertext) VALUES (?, ?, ?, ?, ?, ?)",
            ).use { statement ->
                statement.setString(1, spaceId)
                statement.setLong(2, nextCursor)
                statement.setString(3, envelope.mutationId)
                statement.setString(4, actor.deviceId)
                statement.setLong(5, envelope.keyEpoch)
                statement.setBytes(6, ciphertext)
                statement.executeUpdate()
            }
            connection.commit()
            UploadOutcome.Stored(nextCursor)
        } catch (failure: Throwable) {
            connection.rollback()
            throw failure
        } finally {
            connection.autoCommit = true
        }
    }

    override fun fetch(
        actor: AuthenticatedDevice,
        spaceId: String,
        afterCursor: Long,
        limit: Int,
    ): List<StoredEnvelope>? = dataSource.connection.use { connection ->
        if (!isMember(connection, actor, spaceId)) return@use null
        connection.prepareStatement(
            "SELECT server_cursor, mutation_id, sender_device_id, key_epoch, ciphertext " +
                "FROM encrypted_operation_envelope WHERE sync_space_id = ? AND server_cursor > ? " +
                "ORDER BY server_cursor ASC LIMIT ?",
        ).use { statement ->
            statement.setString(1, spaceId)
            statement.setLong(2, afterCursor)
            statement.setInt(3, limit)
            statement.executeQuery().use { rs ->
                buildList {
                    while (rs.next()) {
                        val bytes = rs.getBytes("ciphertext")
                        add(
                            StoredEnvelope(
                                serverCursor = rs.getLong("server_cursor"),
                                envelope = EncryptedEnvelopeV1(
                                    syncSpaceId = SyncSpaceId(spaceId),
                                    mutationId = rs.getString("mutation_id"),
                                    senderDeviceId = DeviceId(rs.getString("sender_device_id")),
                                    keyEpoch = rs.getLong("key_epoch"),
                                    ciphertextBase64Url = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(bytes),
                                ),
                            ),
                        )
                    }
                }
            }
        }
    }

    private fun isMember(connection: Connection, actor: AuthenticatedDevice, spaceId: String): Boolean =
        connection.prepareStatement(
            "SELECT 1 FROM sync_space_membership m " +
                "JOIN sync_space s ON s.sync_space_id = m.sync_space_id " +
                "JOIN device d ON d.device_id = m.device_id " +
                "WHERE m.sync_space_id = ? AND m.device_id = ? AND d.account_id = ? " +
                "AND s.account_id = ? AND d.revoked_at IS NULL",
        ).use { statement ->
            statement.setString(1, spaceId)
            statement.setString(2, actor.deviceId)
            statement.setString(3, actor.accountId)
            statement.setString(4, actor.accountId)
            statement.executeQuery().use(ResultSet::next)
        }

    private fun findEnvelope(connection: Connection, spaceId: String, mutationId: String): ExistingEnvelope? =
        connection.prepareStatement(
            "SELECT server_cursor, sender_device_id, key_epoch, ciphertext " +
                "FROM encrypted_operation_envelope WHERE sync_space_id = ? AND mutation_id = ? FOR UPDATE",
        ).use { statement ->
            statement.setString(1, spaceId)
            statement.setString(2, mutationId)
            statement.executeQuery().use { rs ->
                if (!rs.next()) null else ExistingEnvelope(
                    serverCursor = rs.getLong("server_cursor"),
                    senderDeviceId = rs.getString("sender_device_id"),
                    keyEpoch = rs.getLong("key_epoch"),
                    ciphertext = rs.getBytes("ciphertext"),
                )
            }
        }

    private fun sameEnvelope(existing: ExistingEnvelope, incoming: EncryptedEnvelopeV1, ciphertext: ByteArray): Boolean =
        existing.senderDeviceId == incoming.senderDeviceId.value &&
            existing.keyEpoch == incoming.keyEpoch &&
            MessageDigest.isEqual(existing.ciphertext, ciphertext)

    private data class ExistingEnvelope(
        val serverCursor: Long,
        val senderDeviceId: String,
        val keyEpoch: Long,
        val ciphertext: ByteArray,
    )

    private fun sha256(value: String): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
}
