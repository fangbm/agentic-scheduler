package dev.agenticscheduler.server.sync

import dev.agenticscheduler.sync.DeviceId
import dev.agenticscheduler.sync.EncryptedEnvelopeV1
import dev.agenticscheduler.sync.SyncSpaceId
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Instant
import java.util.Base64
import java.sql.Connection
import java.sql.ResultSet
import java.sql.Timestamp
import javax.sql.DataSource

class JdbcOpaqueSyncRepository(private val dataSource: DataSource) : OpaqueSyncRepository, ServerBootstrapRepository {
    private val random = SecureRandom()

    override fun createInvitation(accountId: String, syncSpaceId: String, ttlSeconds: Long): InvitationCreateResponse {
        requireValidId(accountId, "accountId")
        requireValidId(syncSpaceId, "syncSpaceId")
        require(ttlSeconds in 60..86_400)
        val tokenBytes = ByteArray(32).also(random::nextBytes)
        val token = Base64.getUrlEncoder().withoutPadding().encodeToString(tokenBytes)
        val expiresAt = Instant.now().plusSeconds(ttlSeconds)
        dataSource.connection.use { connection ->
            connection.autoCommit = false
            try {
                connection.prepareStatement("INSERT INTO account(account_id) VALUES (?) ON CONFLICT (account_id) DO NOTHING").use { statement ->
                    statement.setString(1, accountId)
                    statement.executeUpdate()
                }
                connection.prepareStatement(
                    "INSERT INTO sync_space(sync_space_id, account_id) VALUES (?, ?) " +
                        "ON CONFLICT (sync_space_id) DO NOTHING",
                ).use { statement ->
                    statement.setString(1, syncSpaceId)
                    statement.setString(2, accountId)
                    statement.executeUpdate()
                }
                connection.prepareStatement("SELECT account_id FROM sync_space WHERE sync_space_id = ?").use { statement ->
                    statement.setString(1, syncSpaceId)
                    statement.executeQuery().use { rs ->
                        check(rs.next() && rs.getString(1) == accountId) { "Sync space belongs to another account." }
                    }
                }
                connection.prepareStatement(
                    "INSERT INTO account_invitation(invitation_hash, account_id, sync_space_id, expires_at) VALUES (?, ?, ?, ?)",
                ).use { statement ->
                    statement.setBytes(1, sha256(token))
                    statement.setString(2, accountId)
                    statement.setString(3, syncSpaceId)
                    statement.setTimestamp(4, java.sql.Timestamp.from(expiresAt))
                    statement.executeUpdate()
                }
                connection.commit()
            } catch (failure: Throwable) {
                connection.rollback()
                throw failure
            } finally {
                connection.autoCommit = true
            }
        }
        return InvitationCreateResponse(token, expiresAt.epochSecond)
    }

    override fun bootstrap(request: BootstrapRequest): BootstrapResult {
        require(request.invitationToken.isNotBlank())
        requireValidId(request.deviceId, "deviceId")
        val credentialBytes = ByteArray(32).also(random::nextBytes)
        val credential = Base64.getUrlEncoder().withoutPadding().encodeToString(credentialBytes)
        return dataSource.connection.use connection@{ connection ->
            connection.autoCommit = false
            try {
                val invitation = connection.prepareStatement(
                    "SELECT account_id, sync_space_id FROM account_invitation " +
                        "WHERE invitation_hash = ? AND consumed_at IS NULL AND expires_at > CURRENT_TIMESTAMP FOR UPDATE",
                ).use { statement ->
                    statement.setBytes(1, sha256(request.invitationToken))
                    statement.executeQuery().use { rs ->
                        if (!rs.next()) null else rs.getString("account_id") to rs.getString("sync_space_id")
                    }
                } ?: run {
                    connection.rollback()
                    return@connection BootstrapResult.InvalidInvitation
                }
                val deviceExists = connection.prepareStatement("SELECT 1 FROM device WHERE device_id = ?").use { statement ->
                    statement.setString(1, request.deviceId)
                    statement.executeQuery().use(ResultSet::next)
                }
                if (deviceExists) {
                    connection.rollback()
                    return@connection BootstrapResult.DeviceAlreadyExists
                }
                connection.prepareStatement(
                    "INSERT INTO device(device_id, account_id, credential_hash) VALUES (?, ?, ?)",
                ).use { statement ->
                    statement.setString(1, request.deviceId)
                    statement.setString(2, invitation.first)
                    statement.setBytes(3, sha256(credential))
                    statement.executeUpdate()
                }
                connection.prepareStatement(
                    "INSERT INTO sync_space_membership(sync_space_id, device_id) VALUES (?, ?)",
                ).use { statement ->
                    statement.setString(1, invitation.second)
                    statement.setString(2, request.deviceId)
                    statement.executeUpdate()
                }
                connection.prepareStatement(
                    "UPDATE account_invitation SET consumed_at = CURRENT_TIMESTAMP WHERE invitation_hash = ?",
                ).use { statement ->
                    statement.setBytes(1, sha256(request.invitationToken))
                    statement.executeUpdate()
                }
                connection.commit()
                BootstrapResult.Created(BootstrapResponse(invitation.first, invitation.second, request.deviceId, credential))
            } catch (failure: Throwable) {
                connection.rollback()
                throw failure
            } finally {
                connection.autoCommit = true
            }
        }
    }

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

    private fun requireValidId(value: String, name: String) {
        require(value.isNotBlank() && value.length <= 128) { "$name is invalid." }
    }
}
