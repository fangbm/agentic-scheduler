package dev.agenticscheduler.application.sync

import dev.agenticscheduler.application.persistence.ApplicationTransactionRunner
import dev.agenticscheduler.sync.AccountId
import dev.agenticscheduler.sync.DeviceId
import dev.agenticscheduler.sync.HistoricalSyncSpaceKeyV1
import dev.agenticscheduler.sync.HpkePublicKeyBase64Url
import dev.agenticscheduler.sync.RotationKeyPackageEnvelopeV1
import dev.agenticscheduler.sync.RotationKeyPackagePlaintextV1
import dev.agenticscheduler.sync.RotationWireCodec
import dev.agenticscheduler.sync.RotationWireDecodeResult
import dev.agenticscheduler.sync.SyncSpaceId
import dev.agenticscheduler.sync.SyncSpaceKeyPackageV1
import dev.agenticscheduler.sync.decodeCanonicalBase64Url
import dev.agenticscheduler.sync.encodeCanonicalBase64Url

data class RotationKeyPackageBuildRequest(
    val accountId: AccountId,
    val rotationId: String,
    val accountMasterKeyReference: SecretReference,
    val syncSpaceId: SyncSpaceId,
    val targetDeviceId: DeviceId,
    val targetHpkePublicKey: HpkePublicKeyBase64Url,
) {
    init { require(rotationId.isNotBlank() && rotationId.length <= 128) }
}

sealed interface RotationKeyPackageBuildResult {
    data class Built(val envelope: RotationKeyPackageEnvelopeV1) : RotationKeyPackageBuildResult
    data object MissingAccountMasterKey : RotationKeyPackageBuildResult
    data object MissingActiveContentKey : RotationKeyPackageBuildResult
    data class MissingHistoricalContentKey(val keyEpoch: Long) : RotationKeyPackageBuildResult
    data class ContentKeyIdentityMismatch(val keyEpoch: Long) : RotationKeyPackageBuildResult
    data object InvalidKeyRing : RotationKeyPackageBuildResult
    data object InvalidExportedKeyMaterial : RotationKeyPackageBuildResult
}

/** Builds one SYN-007B package for an already-ACTIVE recipient. */
class RotationKeyPackageBuilder(
    private val keyRing: SyncKeyRingRepository,
    private val keyMaterial: PlatformPairingKeyMaterialExporter,
    private val hpke: PairingHpke,
) {
    suspend fun build(request: RotationKeyPackageBuildRequest): RotationKeyPackageBuildResult {
        val active = keyRing.currentEncryptionKey(request.syncSpaceId)
            ?: return RotationKeyPackageBuildResult.MissingActiveContentKey
        if (active.syncSpaceId != request.syncSpaceId || active.usage != SyncSpaceContentKeyUsage.ACTIVE) {
            return RotationKeyPackageBuildResult.InvalidKeyRing
        }
        val historical = keyRing.historicalDecryptKeys(request.syncSpaceId)
            .sortedBy(SyncSpaceContentKeyMetadata::keyEpoch)
        if (
            historical.map(SyncSpaceContentKeyMetadata::keyEpoch).distinct().size != historical.size ||
            historical.any {
                it.syncSpaceId != request.syncSpaceId ||
                    it.usage != SyncSpaceContentKeyUsage.DECRYPT_ONLY ||
                    it.keyEpoch >= active.keyEpoch
            }
        ) return RotationKeyPackageBuildResult.InvalidKeyRing

        val accountMasterKey = keyMaterial.exportAccountMasterKeyForPairing(request.accountMasterKeyReference)
            ?: return RotationKeyPackageBuildResult.MissingAccountMasterKey
        val activeKey = keyMaterial.exportContentKeyForPairing(active.contentKeyReference)
            ?: return RotationKeyPackageBuildResult.MissingActiveContentKey
        if (activeKey.identity != active.contentKeyIdentity) {
            return RotationKeyPackageBuildResult.ContentKeyIdentityMismatch(active.keyEpoch)
        }

        val historicalKeys = ArrayList<HistoricalSyncSpaceKeyV1>(historical.size)
        for (key in historical) {
            val exported = keyMaterial.exportContentKeyForPairing(key.contentKeyReference)
                ?: return RotationKeyPackageBuildResult.MissingHistoricalContentKey(key.keyEpoch)
            if (exported.identity != key.contentKeyIdentity) {
                return RotationKeyPackageBuildResult.ContentKeyIdentityMismatch(key.keyEpoch)
            }
            val raw = exported.material.copyRawKeyBytesForPairing()
            if (raw.size != KEY_BYTES) return RotationKeyPackageBuildResult.InvalidExportedKeyMaterial
            historicalKeys += HistoricalSyncSpaceKeyV1(key.keyEpoch, encodeCanonicalBase64Url(raw))
        }

        val rawAccountMasterKey = accountMasterKey.copyRawKeyBytesForPairing()
        val rawActiveKey = activeKey.material.copyRawKeyBytesForPairing()
        if (rawAccountMasterKey.size != KEY_BYTES || rawActiveKey.size != KEY_BYTES) {
            return RotationKeyPackageBuildResult.InvalidExportedKeyMaterial
        }

        val plaintext = RotationKeyPackagePlaintextV1(
            accountId = request.accountId,
            rotationId = request.rotationId,
            targetDeviceId = request.targetDeviceId,
            keyEpoch = active.keyEpoch,
            accountMasterKeyBase64Url = encodeCanonicalBase64Url(rawAccountMasterKey),
            syncSpace = SyncSpaceKeyPackageV1(
                syncSpaceId = request.syncSpaceId,
                activeEpoch = active.keyEpoch,
                activeKeyBase64Url = encodeCanonicalBase64Url(rawActiveKey),
                historicalKeys = historicalKeys,
            ),
        )
        return RotationKeyPackageBuildResult.Built(
            hpke.encryptRotationKeyPackage(request.targetHpkePublicKey, plaintext),
        )
    }

    private companion object { const val KEY_BYTES = 32 }
}

/** Exact SYN-007B length-prefixed HPKE contextInfo bytes. */
object RotationKeyPackageContextV1 {
    private val label = "agentic-scheduler-rotation-key-package".encodeToByteArray()

    fun bytes(
        accountId: AccountId,
        rotationId: String,
        targetDeviceId: DeviceId,
        rotationPackageVersion: Int,
        keyEpoch: Long,
    ): ByteArray {
        require(rotationId.isNotBlank() && rotationId.length <= 128)
        require(rotationPackageVersion == RotationWireCodec.ROTATION_PACKAGE_VERSION)
        require(keyEpoch >= 0)
        return RotationByteBuilder().apply {
            write(label)
            writeByte(0)
            writeLengthPrefixed(accountId.value)
            writeLengthPrefixed(rotationId)
            writeLengthPrefixed(targetDeviceId.value)
            writeU32(rotationPackageVersion.toUInt())
            writeU64(keyEpoch.toULong())
        }.toByteArray()
    }
}

fun PairingHpke.encryptRotationKeyPackage(
    recipient: HpkePublicKeyBase64Url,
    plaintext: RotationKeyPackagePlaintextV1,
): RotationKeyPackageEnvelopeV1 {
    val context = RotationKeyPackageContextV1.bytes(
        plaintext.accountId,
        plaintext.rotationId,
        plaintext.targetDeviceId,
        plaintext.rotationPackageVersion,
        plaintext.keyEpoch,
    )
    val encrypted = encrypt(
        recipient,
        RotationWireCodec.encodePlaintext(plaintext).encodeToByteArray(),
        context,
    )
    return RotationKeyPackageEnvelopeV1(
        accountId = plaintext.accountId,
        rotationId = plaintext.rotationId,
        targetDeviceId = plaintext.targetDeviceId,
        keyEpoch = plaintext.keyEpoch,
        encapsulatedKeyBase64Url = encrypted.encapsulatedKeyBase64Url,
        ciphertextBase64Url = encrypted.ciphertextBase64Url,
    )
}

sealed interface DecryptRotationKeyPackageResult {
    data class Admitted(val plaintext: RotationKeyPackagePlaintextV1) : DecryptRotationKeyPackageResult
    data object AuthenticationFailed : DecryptRotationKeyPackageResult
    data object InvalidPackage : DecryptRotationKeyPackageResult
    data object IdentityMismatch : DecryptRotationKeyPackageResult
}

fun PairingHpke.decryptRotationKeyPackage(
    active: LocalEnrollmentState.Active,
    privateKey: PairingPrivateKeyMaterial,
    envelope: RotationKeyPackageEnvelopeV1,
): DecryptRotationKeyPackageResult {
    if (active.accountId != envelope.accountId || active.deviceId != envelope.targetDeviceId) {
        return DecryptRotationKeyPackageResult.IdentityMismatch
    }
    val context = RotationKeyPackageContextV1.bytes(
        envelope.accountId,
        envelope.rotationId,
        envelope.targetDeviceId,
        envelope.rotationPackageVersion,
        envelope.keyEpoch,
    )
    val plaintextJson = try {
        decrypt(
            privateKey,
            envelope.encapsulatedKeyBase64Url,
            envelope.ciphertextBase64Url,
            context,
        ).decodeToString()
    } catch (_: Exception) {
        return DecryptRotationKeyPackageResult.AuthenticationFailed
    }
    val plaintext = when (val decoded = RotationWireCodec.decodePlaintext(plaintextJson)) {
        is RotationWireDecodeResult.Supported -> decoded.value
        else -> return DecryptRotationKeyPackageResult.InvalidPackage
    }
    if (
        plaintext.accountId != envelope.accountId ||
        plaintext.rotationId != envelope.rotationId ||
        plaintext.targetDeviceId != envelope.targetDeviceId ||
        plaintext.keyEpoch != envelope.keyEpoch ||
        plaintext.syncSpace.syncSpaceId != active.syncSpaceId
    ) return DecryptRotationKeyPackageResult.IdentityMismatch
    return DecryptRotationKeyPackageResult.Admitted(plaintext)
}

sealed interface RotationKeyPackageApplyResult {
    data class Applied(
        val state: LocalEnrollmentState.Active,
        val installation: InstallSyncKeyPackageResult,
    ) : RotationKeyPackageApplyResult
    data class AlreadyApplied(val installation: InstallSyncKeyPackageResult) : RotationKeyPackageApplyResult
    data class Superseded(val activeKeyEpoch: Long) : RotationKeyPackageApplyResult
    data object NotActive : RotationKeyPackageApplyResult
    data object MissingPrivateKey : RotationKeyPackageApplyResult
    data object AuthenticationFailed : RotationKeyPackageApplyResult
    data object InvalidPackage : RotationKeyPackageApplyResult
    data object IdentityMismatch : RotationKeyPackageApplyResult
    data object AccountMasterKeyImportFailed : RotationKeyPackageApplyResult
    data class KeyPackageRejected(val result: InstallSyncKeyPackageResult) : RotationKeyPackageApplyResult
    data object CommitFailed : RotationKeyPackageApplyResult
}

/**
 * Applies a rotation package to an existing ACTIVE device.
 *
 * Enrollment identity, HPKE identity and DeviceCredential are immutable here.
 * Only the AMK reference and complete SyncSpace key ring may advance.
 */
class RotationKeyPackageRecipientService(
    private val enrollments: LocalEnrollmentRepository,
    private val privateKeys: PlatformPairingPrivateKeyStore,
    private val hpke: PairingHpke,
    private val accountMasterKeys: PlatformAccountMasterKeyStore,
    private val keyRing: SyncKeyRingRepository,
    private val keyPackageInstaller: SyncKeyPackageInstaller,
    private val transactions: ApplicationTransactionRunner,
) {
    suspend fun apply(envelope: RotationKeyPackageEnvelopeV1): RotationKeyPackageApplyResult {
        val active = enrollments.state(envelope.accountId) as? LocalEnrollmentState.Active
            ?: return RotationKeyPackageApplyResult.NotActive
        if (active.deviceId != envelope.targetDeviceId) return RotationKeyPackageApplyResult.IdentityMismatch

        val currentEpoch = keyRing.state(active.syncSpaceId)?.activeEncryptionEpoch
        if (currentEpoch != null && envelope.keyEpoch < currentEpoch) {
            return RotationKeyPackageApplyResult.Superseded(currentEpoch)
        }

        val privateKey = privateKeys.privateKey(active.hpkePrivateKeyReference)
            ?: return RotationKeyPackageApplyResult.MissingPrivateKey
        val plaintext = when (val decrypted = hpke.decryptRotationKeyPackage(active, privateKey, envelope)) {
            is DecryptRotationKeyPackageResult.Admitted -> decrypted.plaintext
            DecryptRotationKeyPackageResult.AuthenticationFailed -> return RotationKeyPackageApplyResult.AuthenticationFailed
            DecryptRotationKeyPackageResult.InvalidPackage -> return RotationKeyPackageApplyResult.InvalidPackage
            DecryptRotationKeyPackageResult.IdentityMismatch -> return RotationKeyPackageApplyResult.IdentityMismatch
        }

        val importedAmk = try {
            accountMasterKeys.importAccountMasterKey(
                RawEphemeralKeyMaterial.fromBase64Url(plaintext.accountMasterKeyBase64Url),
            )
        } catch (_: Throwable) {
            null
        } ?: return RotationKeyPackageApplyResult.AccountMasterKeyImportFailed

        val packageForInstall = DecryptedSyncKeyPackage(
            syncSpaceId = plaintext.syncSpace.syncSpaceId,
            activeEpoch = plaintext.syncSpace.activeEpoch,
            activeKey = RawEphemeralKeyMaterial.fromBase64Url(plaintext.syncSpace.activeKeyBase64Url),
            historicalKeys = plaintext.syncSpace.historicalKeys.map { key ->
                SyncKeyPackageHistoricalKey(
                    key.keyEpoch,
                    RawEphemeralKeyMaterial.fromBase64Url(key.keyBase64Url),
                )
            },
        )

        var installation: InstallSyncKeyPackageResult? = null
        var updatedState: LocalEnrollmentState.Active? = null
        try {
            transactions.inWriteTransaction {
                installation = keyPackageInstaller.install(packageForInstall)
                when (installation) {
                    is InstallSyncKeyPackageResult.Installed,
                    is InstallSyncKeyPackageResult.Advanced,
                    -> {
                        updatedState = active.copy(accountMasterKeyReference = importedAmk)
                        enrollments.saveActive(requireNotNull(updatedState))
                    }
                    is InstallSyncKeyPackageResult.Idempotent,
                    is InstallSyncKeyPackageResult.Repaired,
                    is InstallSyncKeyPackageResult.IntegrityError,
                    is InstallSyncKeyPackageResult.RejectedRollback,
                    null,
                    -> Unit
                }
            }
        } catch (_: Throwable) {
            safeDeleteAmk(importedAmk)
            return RotationKeyPackageApplyResult.CommitFailed
        }

        val result = requireNotNull(installation)
        return when (result) {
            is InstallSyncKeyPackageResult.Installed,
            is InstallSyncKeyPackageResult.Advanced,
            -> {
                if (active.accountMasterKeyReference != importedAmk) {
                    safeDeleteAmk(active.accountMasterKeyReference)
                }
                RotationKeyPackageApplyResult.Applied(requireNotNull(updatedState), result)
            }
            is InstallSyncKeyPackageResult.Idempotent,
            is InstallSyncKeyPackageResult.Repaired,
            -> {
                safeDeleteAmk(importedAmk)
                RotationKeyPackageApplyResult.AlreadyApplied(result)
            }
            is InstallSyncKeyPackageResult.IntegrityError,
            is InstallSyncKeyPackageResult.RejectedRollback,
            -> {
                safeDeleteAmk(importedAmk)
                RotationKeyPackageApplyResult.KeyPackageRejected(result)
            }
        }
    }

    private suspend fun safeDeleteAmk(reference: SecretReference) {
        try {
            accountMasterKeys.delete(reference)
        } catch (_: Throwable) {
            // Secure-store orphan cleanup is best effort; durable metadata remains authoritative.
        }
    }
}

private class RotationByteBuilder {
    private val values = mutableListOf<Byte>()
    fun write(value: ByteArray) { values += value.toList() }
    fun writeByte(value: Int) { values += value.toByte() }
    fun writeLengthPrefixed(value: String) {
        val encoded = value.encodeToByteArray()
        writeU32(encoded.size.toUInt())
        write(encoded)
    }
    fun writeU32(value: UInt) {
        write(byteArrayOf((value shr 24).toByte(), (value shr 16).toByte(), (value shr 8).toByte(), value.toByte()))
    }
    fun writeU64(value: ULong) {
        write(
            byteArrayOf(
                (value shr 56).toByte(), (value shr 48).toByte(), (value shr 40).toByte(), (value shr 32).toByte(),
                (value shr 24).toByte(), (value shr 16).toByte(), (value shr 8).toByte(), value.toByte(),
            ),
        )
    }
    fun toByteArray(): ByteArray = values.toByteArray()
}


sealed interface RotationPackageCatchUpResult {
    data class Completed(val outcomes: List<RotationPackageOutcome>) : RotationPackageCatchUpResult
    data object FetchFailed : RotationPackageCatchUpResult
    data class InvalidRelayPackage(val rotationId: String) : RotationPackageCatchUpResult
    data class ApplyFailed(val rotationId: String, val result: RotationKeyPackageApplyResult) : RotationPackageCatchUpResult
}

data class RotationPackageOutcome(
    val rotationId: String,
    val result: RotationKeyPackageApplyResult,
)

/**
 * Pulls all opaque rotation packages for the current authenticated ACTIVE device and applies them
 * in server order. V1 deliberately has no package-ack table; repeated fetches are expected and
 * converge through Superseded / AlreadyApplied outcomes.
 */
class RotationPackageCatchUpService(
    private val transport: RotationPackageTransport,
    private val recipient: RotationKeyPackageRecipientService,
) {
    suspend fun catchUp(): RotationPackageCatchUpResult {
        val remote = try {
            transport.rotationPackages()
        } catch (_: Throwable) {
            return RotationPackageCatchUpResult.FetchFailed
        }

        val outcomes = ArrayList<RotationPackageOutcome>(remote.size)
        for (item in remote) {
            val encoded = try {
                decodeCanonicalBase64Url(item.packageBase64Url, null, "Rotation package relay bytes")
                    .decodeToString(throwOnInvalidSequence = true)
            } catch (_: Throwable) {
                return RotationPackageCatchUpResult.InvalidRelayPackage(item.rotationId)
            }
            val envelope = when (val decoded = RotationWireCodec.decodeEnvelope(encoded)) {
                is RotationWireDecodeResult.Supported -> decoded.value
                else -> return RotationPackageCatchUpResult.InvalidRelayPackage(item.rotationId)
            }
            if (
                envelope.rotationId != item.rotationId ||
                envelope.targetDeviceId.value != item.targetDeviceId
            ) {
                return RotationPackageCatchUpResult.InvalidRelayPackage(item.rotationId)
            }

            val applied = recipient.apply(envelope)
            when (applied) {
                is RotationKeyPackageApplyResult.Applied,
                is RotationKeyPackageApplyResult.AlreadyApplied,
                is RotationKeyPackageApplyResult.Superseded,
                -> outcomes += RotationPackageOutcome(item.rotationId, applied)
                else -> return RotationPackageCatchUpResult.ApplyFailed(item.rotationId, applied)
            }
        }
        return RotationPackageCatchUpResult.Completed(outcomes)
    }
}
