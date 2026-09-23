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
import java.nio.ByteBuffer
import javax.sql.DataSource

class JdbcOpaqueSyncRepository(private val dataSource: DataSource) : OpaqueSyncRepository, ServerBootstrapRepository, ServerEnrollmentRepository, ServerSecurityLifecycleRepository {
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
                lockAccount(connection, invitation.first)
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

    override fun registerEnrollment(request: EnrollmentRequestWire, ttlSeconds: Long): EnrollmentRegistrationResult {
        requireValidId(request.accountId, "accountId")
        requireValidId(request.requestId, "requestId")
        requireValidId(request.targetDeviceId, "targetDeviceId")
        require(ttlSeconds in 60..86_400)
        val publicKey = decodeCanonical(request.hpkePublicKeyBase64Url, 32)
        val credentialHash = decodeCanonical(request.credentialHashBase64Url, 32)
        val expiresAt = Instant.now().plusSeconds(ttlSeconds)
        return dataSource.connection.use connection@{ connection ->
            connection.autoCommit = false
            try {
                val accountExists = connection.prepareStatement("SELECT 1 FROM account WHERE account_id = ?").use { statement ->
                    statement.setString(1, request.accountId)
                    statement.executeQuery().use(ResultSet::next)
                }
                if (!accountExists) {
                    connection.rollback()
                    return@connection EnrollmentRegistrationResult.UnknownAccount
                }
                val duplicate = connection.prepareStatement("SELECT 1 FROM device_enrollment_request WHERE request_id = ?").use { statement ->
                    statement.setString(1, request.requestId)
                    statement.executeQuery().use(ResultSet::next)
                }
                if (duplicate) {
                    connection.rollback()
                    return@connection EnrollmentRegistrationResult.DuplicateRequest
                }
                connection.prepareStatement(
                    "INSERT INTO device_enrollment_request(request_id, account_id, target_device_id, hpke_public_key, credential_hash, expires_at) VALUES (?, ?, ?, ?, ?, ?)",
                ).use { statement ->
                    statement.setString(1, request.requestId)
                    statement.setString(2, request.accountId)
                    statement.setString(3, request.targetDeviceId)
                    statement.setBytes(4, publicKey)
                    statement.setBytes(5, credentialHash)
                    statement.setTimestamp(6, java.sql.Timestamp.from(expiresAt))
                    statement.executeUpdate()
                }
                connection.commit()
                EnrollmentRegistrationResult.Created(expiresAt.epochSecond)
            } catch (failure: Throwable) {
                connection.rollback()
                throw failure
            } finally {
                connection.autoCommit = true
            }
        }
    }

    override fun pendingEnrollments(actor: AuthenticatedDevice): List<PendingEnrollmentResponse>? = dataSource.connection.use { connection ->
        if (!activeAccountDevice(connection, actor)) return@use null
        connection.prepareStatement(
            "SELECT account_id, request_id, target_device_id, hpke_public_key FROM device_enrollment_request " +
                "WHERE account_id = ? AND approved_at IS NULL AND expires_at > CURRENT_TIMESTAMP ORDER BY request_id ASC",
        ).use { statement ->
            statement.setString(1, actor.accountId)
            statement.executeQuery().use { rs ->
                buildList {
                    while (rs.next()) add(
                        PendingEnrollmentResponse(
                            accountId = rs.getString("account_id"),
                            requestId = rs.getString("request_id"),
                            targetDeviceId = rs.getString("target_device_id"),
                            hpkePublicKeyBase64Url = Base64.getUrlEncoder().withoutPadding().encodeToString(rs.getBytes("hpke_public_key")),
                        ),
                    )
                }
            }
        }
    }

    override fun approveEnrollment(actor: AuthenticatedDevice, requestId: String, packageBytes: ByteArray): EnrollmentApprovalResult =
        dataSource.connection.use connection@{ connection ->
            connection.autoCommit = false
            try {
                if (!activeAccountDevice(connection, actor)) {
                    connection.rollback()
                    return@connection EnrollmentApprovalResult.NotFound
                }
                val request = connection.prepareStatement(
                    "SELECT account_id, target_device_id, credential_hash, approved_at FROM device_enrollment_request " +
                        "WHERE request_id = ? AND expires_at > CURRENT_TIMESTAMP FOR UPDATE",
                ).use { statement ->
                    statement.setString(1, requestId)
                    statement.executeQuery().use { rs ->
                        if (!rs.next()) null else EnrollmentApprovalRow(
                            accountId = rs.getString("account_id"),
                            targetDeviceId = rs.getString("target_device_id"),
                            credentialHash = rs.getBytes("credential_hash"),
                            approved = rs.getTimestamp("approved_at") != null,
                        )
                    }
                } ?: run {
                    connection.rollback()
                    return@connection EnrollmentApprovalResult.NotFound
                }
                if (request.accountId != actor.accountId) {
                    connection.rollback()
                    return@connection EnrollmentApprovalResult.NotFound
                }
                if (request.approved) {
                    connection.rollback()
                    return@connection EnrollmentApprovalResult.AlreadyApproved
                }
                val credentialHash = request.credentialHash ?: run {
                    connection.rollback()
                    return@connection EnrollmentApprovalResult.MissingCredentialHash
                }
                lockAccount(connection, request.accountId)
                val targetExists = connection.prepareStatement("SELECT 1 FROM device WHERE device_id = ?").use { statement ->
                    statement.setString(1, request.targetDeviceId)
                    statement.executeQuery().use(ResultSet::next)
                }
                if (targetExists) {
                    connection.rollback()
                    return@connection EnrollmentApprovalResult.TargetDeviceAlreadyExists
                }
                val credentialExists = connection.prepareStatement("SELECT 1 FROM device WHERE credential_hash = ?").use { statement ->
                    statement.setBytes(1, credentialHash)
                    statement.executeQuery().use(ResultSet::next)
                }
                if (credentialExists) {
                    connection.rollback()
                    return@connection EnrollmentApprovalResult.TargetDeviceAlreadyExists
                }
                connection.prepareStatement("INSERT INTO device_key_package(request_id, package_bytes) VALUES (?, ?)").use { statement ->
                    statement.setString(1, requestId)
                    statement.setBytes(2, packageBytes)
                    statement.executeUpdate()
                }
                connection.prepareStatement(
                    "INSERT INTO device(device_id, account_id, credential_hash) VALUES (?, ?, ?)",
                ).use { statement ->
                    statement.setString(1, request.targetDeviceId)
                    statement.setString(2, request.accountId)
                    statement.setBytes(3, credentialHash)
                    statement.executeUpdate()
                }
                connection.prepareStatement(
                    "INSERT INTO sync_space_membership(sync_space_id, device_id) " +
                        "SELECT sync_space_id, ? FROM sync_space WHERE account_id = ?",
                ).use { statement ->
                    statement.setString(1, request.targetDeviceId)
                    statement.setString(2, request.accountId)
                    statement.executeUpdate()
                }
                connection.prepareStatement("UPDATE device_enrollment_request SET approved_at = CURRENT_TIMESTAMP WHERE request_id = ?").use { statement ->
                    statement.setString(1, requestId)
                    statement.executeUpdate()
                }
                connection.commit()
                EnrollmentApprovalResult.Approved
            } catch (failure: Throwable) {
                connection.rollback()
                throw failure
            } finally {
                connection.autoCommit = true
            }
        }

    override fun fetchKeyPackage(requestId: String, targetDeviceId: String): ByteArray? = dataSource.connection.use { connection ->
        connection.prepareStatement(
            "SELECT p.package_bytes FROM device_key_package p " +
                "JOIN device_enrollment_request r ON r.request_id = p.request_id " +
                "WHERE p.request_id = ? AND r.target_device_id = ? AND r.approved_at IS NOT NULL " +
                "AND r.expires_at > CURRENT_TIMESTAMP",
        ).use { statement ->
            statement.setString(1, requestId)
            statement.setString(2, targetDeviceId)
            statement.executeQuery().use { rs -> if (rs.next()) rs.getBytes(1) else null }
        }
    }

    override fun registerRecoveryProof(actor: AuthenticatedDevice, request: RecoveryProofRegistrationRequest): RecoveryProofRegistrationResult =
        dataSource.connection.use connection@{ connection ->
            connection.autoCommit = false
            try {
                if (!activeAccountDevice(connection, actor)) {
                    connection.rollback()
                    return@connection RecoveryProofRegistrationResult.NotFound
                }
                require(request.counter >= 0)
                val proofHash = decodeCanonical(request.proofHashBase64Url, 32)
                val existing = connection.prepareStatement(
                    "SELECT proof_hash, counter FROM recovery_proof WHERE account_id = ? FOR UPDATE",
                ).use { statement ->
                    statement.setString(1, actor.accountId)
                    statement.executeQuery().use { rs ->
                        if (!rs.next()) null else rs.getBytes("proof_hash") to rs.getLong("counter")
                    }
                }
                when {
                    existing == null -> {
                        connection.prepareStatement("INSERT INTO recovery_proof(account_id, proof_hash, counter) VALUES (?, ?, ?)").use { statement ->
                            statement.setString(1, actor.accountId)
                            statement.setBytes(2, proofHash)
                            statement.setLong(3, request.counter)
                            statement.executeUpdate()
                        }
                    }
                    request.counter < existing.second -> {
                        connection.rollback()
                        return@connection RecoveryProofRegistrationResult.RejectedRollback
                    }
                    request.counter == existing.second && !MessageDigest.isEqual(proofHash, existing.first) -> {
                        connection.rollback()
                        return@connection RecoveryProofRegistrationResult.IntegrityConflict
                    }
                    request.counter == existing.second -> {
                        connection.rollback()
                        return@connection RecoveryProofRegistrationResult.Stored
                    }
                    else -> connection.prepareStatement(
                        "UPDATE recovery_proof SET proof_hash = ?, counter = ?, updated_at = CURRENT_TIMESTAMP WHERE account_id = ?",
                    ).use { statement ->
                        statement.setBytes(1, proofHash)
                        statement.setLong(2, request.counter)
                        statement.setString(3, actor.accountId)
                        statement.executeUpdate()
                    }
                }
                connection.commit()
                RecoveryProofRegistrationResult.Stored
            } catch (failure: Throwable) {
                connection.rollback()
                throw failure
            } finally {
                connection.autoCommit = true
            }
        }

    override fun recoveryBootstrap(accountId: String): RecoveryBootstrapDescriptor? = dataSource.connection.use { connection ->
        requireValidId(accountId, "accountId")
        connection.prepareStatement(
            "SELECT p.counter, e.envelope_bytes " +
                "FROM recovery_proof p JOIN recovery_envelope e ON e.account_id = p.account_id " +
                "WHERE p.account_id = ?",
        ).use { statement ->
            statement.setString(1, accountId)
            statement.executeQuery().use { rs ->
                if (!rs.next()) null
                else RecoveryBootstrapDescriptor(
                    counter = rs.getLong("counter"),
                    recoveryEnvelope = rs.getBytes("envelope_bytes"),
                )
            }
        }
    }

    override fun enrollWithRecovery(request: RecoveryEnrollmentRequestWire): RecoveryEnrollmentResult =
        dataSource.connection.use connection@{ connection ->
            connection.autoCommit = false
            try {
                requireValidId(request.accountId, "accountId")
                requireValidId(request.requestId, "requestId")
                requireValidId(request.targetDeviceId, "targetDeviceId")
                require(request.counter >= 0)
                val credentialHash = decodeCanonical(request.credentialHashBase64Url, 32)
                val proof = decodeCanonical(request.proofBase64Url, 32)
                val nextProofHash = decodeCanonical(request.nextProofHashBase64Url, 32)
                val completed = connection.prepareStatement(
                    "SELECT account_id, target_device_id FROM recovery_enrollment_request WHERE request_id = ?",
                ).use { statement ->
                    statement.setString(1, request.requestId)
                    statement.executeQuery().use { rs ->
                        if (!rs.next()) null else rs.getString("account_id") to rs.getString("target_device_id")
                    }
                }
                if (completed != null) {
                    connection.rollback()
                    return@connection if (completed.first == request.accountId && completed.second == request.targetDeviceId) {
                        RecoveryEnrollmentResult.Created(request.accountId, request.targetDeviceId)
                    } else {
                        RecoveryEnrollmentResult.InvalidProof
                    }
                }
                lockAccount(connection, request.accountId)
                val verifier = connection.prepareStatement(
                    "SELECT proof_hash, counter FROM recovery_proof WHERE account_id = ? FOR UPDATE",
                ).use { statement ->
                    statement.setString(1, request.accountId)
                    statement.executeQuery().use { rs ->
                        if (!rs.next()) null else rs.getBytes("proof_hash") to rs.getLong("counter")
                    }
                } ?: run {
                    connection.rollback()
                    return@connection RecoveryEnrollmentResult.UnknownAccount
                }
                if (request.counter != verifier.second || !MessageDigest.isEqual(verifier.first, sha256Bytes(proof)) || MessageDigest.isEqual(verifier.first, nextProofHash)) {
                    connection.rollback()
                    return@connection RecoveryEnrollmentResult.InvalidProof
                }
                val targetExists = connection.prepareStatement("SELECT 1 FROM device WHERE device_id = ?").use { statement ->
                    statement.setString(1, request.targetDeviceId)
                    statement.executeQuery().use(ResultSet::next)
                }
                if (targetExists) {
                    connection.rollback()
                    return@connection RecoveryEnrollmentResult.TargetDeviceAlreadyExists
                }
                val credentialExists = connection.prepareStatement("SELECT 1 FROM device WHERE credential_hash = ?").use { statement ->
                    statement.setBytes(1, credentialHash)
                    statement.executeQuery().use(ResultSet::next)
                }
                if (credentialExists) {
                    connection.rollback()
                    return@connection RecoveryEnrollmentResult.TargetDeviceAlreadyExists
                }
                connection.prepareStatement("INSERT INTO device(device_id, account_id, credential_hash) VALUES (?, ?, ?)").use { statement ->
                    statement.setString(1, request.targetDeviceId)
                    statement.setString(2, request.accountId)
                    statement.setBytes(3, credentialHash)
                    statement.executeUpdate()
                }
                connection.prepareStatement(
                    "INSERT INTO sync_space_membership(sync_space_id, device_id) SELECT sync_space_id, ? FROM sync_space WHERE account_id = ?",
                ).use { statement ->
                    statement.setString(1, request.targetDeviceId)
                    statement.setString(2, request.accountId)
                    statement.executeUpdate()
                }
                connection.prepareStatement(
                    "UPDATE recovery_proof SET proof_hash = ?, counter = ?, updated_at = CURRENT_TIMESTAMP WHERE account_id = ?",
                ).use { statement ->
                    statement.setBytes(1, nextProofHash)
                    statement.setLong(2, request.counter + 1)
                    statement.setString(3, request.accountId)
                    statement.executeUpdate()
                }
                connection.prepareStatement("INSERT INTO recovery_enrollment_request(request_id, account_id, target_device_id) VALUES (?, ?, ?)").use { statement ->
                    statement.setString(1, request.requestId)
                    statement.setString(2, request.accountId)
                    statement.setString(3, request.targetDeviceId)
                    statement.executeUpdate()
                }
                connection.commit()
                RecoveryEnrollmentResult.Created(request.accountId, request.targetDeviceId)
            } catch (failure: Throwable) {
                connection.rollback()
                throw failure
            } finally {
                connection.autoCommit = true
            }
        }

    override fun revokeAndRotate(actor: AuthenticatedDevice, targetDeviceId: String, request: AtomicRevocationRequest): AtomicRevocationResult =
        dataSource.connection.use connection@{ connection ->
            connection.autoCommit = false
            try {
                requireValidId(targetDeviceId, "targetDeviceId")
                requireValidId(request.rotationId, "rotationId")
                if (targetDeviceId == actor.deviceId) {
                    connection.rollback()
                    return@connection AtomicRevocationResult.SelfRevocationDenied
                }
                if (!activeAccountDevice(connection, actor)) {
                    connection.rollback()
                    return@connection AtomicRevocationResult.NotFound
                }
                lockAccount(connection, actor.accountId)
                val envelope = decodeCanonical(request.recoveryEnvelopeBase64Url, null)
                if (envelope.isEmpty() || request.packages.isEmpty()) {
                    connection.rollback()
                    return@connection AtomicRevocationResult.InvalidPackageSet
                }
                val packages = request.packages.map { packageUpload ->
                    requireValidId(packageUpload.deviceId, "packageDeviceId")
                    packageUpload.deviceId to decodeCanonical(packageUpload.packageBase64Url, null)
                }.sortedBy { it.first }
                if (packages.map { it.first }.toSet().size != packages.size) {
                    connection.rollback()
                    return@connection AtomicRevocationResult.InvalidPackageSet
                }
                val requestHash = rotationRequestHash(request.rotationId, targetDeviceId, envelope, packages)
                val existing = connection.prepareStatement(
                    "SELECT account_id, request_hash FROM sync_key_rotation WHERE rotation_id = ? FOR UPDATE",
                ).use { statement ->
                    statement.setString(1, request.rotationId)
                    statement.executeQuery().use { rs ->
                        if (!rs.next()) null else rs.getString("account_id") to rs.getBytes("request_hash")
                    }
                }
                if (existing != null) {
                    connection.rollback()
                    return@connection if (existing.first == actor.accountId && MessageDigest.isEqual(existing.second, requestHash)) {
                        AtomicRevocationResult.AlreadyApplied
                    } else {
                        AtomicRevocationResult.IntegrityConflict
                    }
                }
                val targetState = connection.prepareStatement(
                    "SELECT revoked_at FROM device WHERE device_id = ? AND account_id = ? FOR UPDATE",
                ).use { statement ->
                    statement.setString(1, targetDeviceId)
                    statement.setString(2, actor.accountId)
                    statement.executeQuery().use { rs -> if (!rs.next()) null else rs.getTimestamp("revoked_at") != null }
                } ?: run {
                    connection.rollback()
                    return@connection AtomicRevocationResult.NotFound
                }
                if (targetState) {
                    connection.rollback()
                    return@connection AtomicRevocationResult.AlreadyRevoked
                }
                val expectedDevices = connection.prepareStatement(
                    "SELECT device_id FROM device WHERE account_id = ? AND revoked_at IS NULL AND device_id <> ? ORDER BY device_id",
                ).use { statement ->
                    statement.setString(1, actor.accountId)
                    statement.setString(2, targetDeviceId)
                    statement.executeQuery().use { rs ->
                        buildList { while (rs.next()) add(rs.getString("device_id")) }
                    }
                }
                if (expectedDevices != packages.map { it.first }) {
                    connection.rollback()
                    return@connection AtomicRevocationResult.InvalidPackageSet
                }
                connection.prepareStatement(
                    "INSERT INTO sync_key_rotation(rotation_id, account_id, revoked_device_id, request_hash, recovery_envelope) VALUES (?, ?, ?, ?, ?)",
                ).use { statement ->
                    statement.setString(1, request.rotationId)
                    statement.setString(2, actor.accountId)
                    statement.setString(3, targetDeviceId)
                    statement.setBytes(4, requestHash)
                    statement.setBytes(5, envelope)
                    statement.executeUpdate()
                }
                connection.prepareStatement(
                    "INSERT INTO sync_key_rotation_package(rotation_id, device_id, package_bytes) VALUES (?, ?, ?)",
                ).use { statement ->
                    packages.forEach { (deviceId, bytes) ->
                        statement.setString(1, request.rotationId)
                        statement.setString(2, deviceId)
                        statement.setBytes(3, bytes)
                        statement.addBatch()
                    }
                    statement.executeBatch()
                }
                connection.prepareStatement(
                    "INSERT INTO recovery_envelope(account_id, envelope_bytes) VALUES (?, ?) " +
                        "ON CONFLICT (account_id) DO UPDATE SET envelope_bytes = EXCLUDED.envelope_bytes, updated_at = CURRENT_TIMESTAMP",
                ).use { statement ->
                    statement.setString(1, actor.accountId)
                    statement.setBytes(2, envelope)
                    statement.executeUpdate()
                }
                connection.prepareStatement("UPDATE device SET revoked_at = CURRENT_TIMESTAMP WHERE device_id = ? AND account_id = ?").use { statement ->
                    statement.setString(1, targetDeviceId)
                    statement.setString(2, actor.accountId)
                    statement.executeUpdate()
                }
                connection.commit()
                AtomicRevocationResult.Applied
            } catch (failure: Throwable) {
                connection.rollback()
                throw failure
            } finally {
                connection.autoCommit = true
            }
        }

    override fun saveRecoveryEnvelope(actor: AuthenticatedDevice, envelopeBytes: ByteArray): Boolean =
        dataSource.connection.use connection@{ connection ->
            if (!activeAccountDevice(connection, actor)) return@connection false
            connection.prepareStatement(
                "INSERT INTO recovery_envelope(account_id, envelope_bytes) VALUES (?, ?) " +
                    "ON CONFLICT (account_id) DO UPDATE SET envelope_bytes = EXCLUDED.envelope_bytes, updated_at = CURRENT_TIMESTAMP",
            ).use { statement ->
                statement.setString(1, actor.accountId)
                statement.setBytes(2, envelopeBytes)
                statement.executeUpdate()
            }
            true
        }

    override fun fetchRecoveryEnvelope(actor: AuthenticatedDevice): ByteArray? = dataSource.connection.use { connection ->
        if (!activeAccountDevice(connection, actor)) return@use null
        connection.prepareStatement("SELECT envelope_bytes FROM recovery_envelope WHERE account_id = ?").use { statement ->
            statement.setString(1, actor.accountId)
            statement.executeQuery().use { rs -> if (rs.next()) rs.getBytes(1) else null }
        }
    }

    override fun revokeDevice(actor: AuthenticatedDevice, targetDeviceId: String): DeviceRevocationResult =
        dataSource.connection.use connection@{ connection ->
            connection.autoCommit = false
            try {
                val result = when {
                    targetDeviceId == actor.deviceId -> DeviceRevocationResult.SelfRevocationDenied
                    !activeAccountDevice(connection, actor) -> DeviceRevocationResult.NotFound
                    else -> {
                        val state = connection.prepareStatement("SELECT revoked_at FROM device WHERE device_id = ? AND account_id = ? FOR UPDATE").use { statement ->
                            statement.setString(1, targetDeviceId)
                            statement.setString(2, actor.accountId)
                            statement.executeQuery().use { rs -> if (!rs.next()) null else rs.getTimestamp("revoked_at") != null }
                        }
                        when {
                            state == null -> DeviceRevocationResult.NotFound
                            state -> DeviceRevocationResult.AlreadyRevoked
                            else -> {
                                connection.prepareStatement("UPDATE device SET revoked_at = CURRENT_TIMESTAMP WHERE device_id = ? AND account_id = ?").use { statement ->
                                    statement.setString(1, targetDeviceId)
                                    statement.setString(2, actor.accountId)
                                    statement.executeUpdate()
                                }
                                DeviceRevocationResult.Revoked
                            }
                        }
                    }
                }
                connection.commit()
                result
            } catch (failure: Throwable) {
                connection.rollback()
                throw failure
            } finally {
                connection.autoCommit = true
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

    private fun activeAccountDevice(connection: Connection, actor: AuthenticatedDevice): Boolean =
        connection.prepareStatement("SELECT 1 FROM device WHERE device_id = ? AND account_id = ? AND revoked_at IS NULL").use { statement ->
            statement.setString(1, actor.deviceId)
            statement.setString(2, actor.accountId)
            statement.executeQuery().use(ResultSet::next)
        }

    private fun lockAccount(connection: Connection, accountId: String) {
        connection.prepareStatement("SELECT account_id FROM account WHERE account_id = ? FOR UPDATE").use { statement ->
            statement.setString(1, accountId)
            check(statement.executeQuery().use(ResultSet::next)) { "Account does not exist." }
        }
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

    private data class EnrollmentApprovalRow(
        val accountId: String,
        val targetDeviceId: String,
        val credentialHash: ByteArray?,
        val approved: Boolean,
    )

    private fun sha256(value: String): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))

    private fun sha256Bytes(value: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(value)

    private fun rotationRequestHash(
        rotationId: String,
        targetDeviceId: String,
        envelope: ByteArray,
        packages: List<Pair<String, ByteArray>>,
    ): ByteArray {
        val digest = MessageDigest.getInstance("SHA-256")
        fun updateBytes(value: ByteArray) {
            digest.update(ByteBuffer.allocate(4).putInt(value.size).array())
            digest.update(value)
        }
        fun updateString(value: String) = updateBytes(value.toByteArray(Charsets.UTF_8))
        updateString(rotationId)
        updateString(targetDeviceId)
        updateBytes(envelope)
        packages.forEach { (deviceId, bytes) -> updateString(deviceId); updateBytes(bytes) }
        return digest.digest()
    }

    private fun decodeCanonical(value: String, expectedBytes: Int?): ByteArray {
        require(value.isNotEmpty() && '=' !in value)
        val decoded = Base64.getUrlDecoder().decode(value)
        if (expectedBytes != null) require(decoded.size == expectedBytes)
        require(Base64.getUrlEncoder().withoutPadding().encodeToString(decoded) == value)
        return decoded
    }

    private fun requireValidId(value: String, name: String) {
        require(value.isNotBlank() && value.length <= 128) { "$name is invalid." }
    }
}
