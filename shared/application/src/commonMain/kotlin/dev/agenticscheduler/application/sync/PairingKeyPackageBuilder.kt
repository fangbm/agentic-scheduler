package dev.agenticscheduler.application.sync

import dev.agenticscheduler.sync.AccountId
import dev.agenticscheduler.sync.KeyPackageEnvelopeV1
import dev.agenticscheduler.sync.KeyPackagePlaintextV1
import dev.agenticscheduler.sync.PendingEnrollmentRequestV1
import dev.agenticscheduler.sync.SyncSpaceId
import dev.agenticscheduler.sync.SyncSpaceKeyPackageV1
import dev.agenticscheduler.sync.HistoricalSyncSpaceKeyV1
import dev.agenticscheduler.sync.encodeCanonicalBase64Url

/**
 * Narrow platform boundary for the explicit, user-approved pairing flow.
 * Exported bytes are transient: [PairingKeyPackageBuilder] immediately seals
 * them with HPKE and never persists or returns them to its caller.
 */
interface PlatformPairingKeyMaterialExporter {
    suspend fun exportAccountMasterKeyForPairing(reference: SecretReference): PairingEphemeralKeyMaterial?
    suspend fun exportContentKeyForPairing(reference: SecretReference): ExportedPairingContentKey?
}

/** A transient 256-bit secret supplied only by the platform pairing export boundary. */
interface PairingEphemeralKeyMaterial : PlatformSecretMaterial {
    fun copyRawKeyBytesForPairing(): ByteArray

    override fun copyRawSecretBytesForSecureStore(): ByteArray = copyRawKeyBytesForPairing()
}

data class ExportedPairingContentKey(
    val material: PairingEphemeralKeyMaterial,
    val identity: ContentKeyIdentity,
)

data class PairingKeyPackageBuildRequest(
    val accountId: AccountId,
    val accountMasterKeyReference: SecretReference,
    val syncSpaceId: SyncSpaceId,
    val enrollmentRequest: PendingEnrollmentRequestV1,
) {
    init {
        require(accountId == enrollmentRequest.accountId) {
            "A pairing key package must use its enrollment request's account."
        }
    }
}

sealed interface PairingKeyPackageBuildResult {
    data class Built(val envelope: KeyPackageEnvelopeV1) : PairingKeyPackageBuildResult
    data object MissingAccountMasterKey : PairingKeyPackageBuildResult
    data object MissingActiveContentKey : PairingKeyPackageBuildResult
    data class MissingHistoricalContentKey(val keyEpoch: Long) : PairingKeyPackageBuildResult
    data class ContentKeyIdentityMismatch(val keyEpoch: Long) : PairingKeyPackageBuildResult
    data object InvalidKeyRing : PairingKeyPackageBuildResult
    data object InvalidExportedKeyMaterial : PairingKeyPackageBuildResult
}

/**
 * Builds a SYN-006A package from the sender's active key and complete retained
 * decrypt-only ring. The return value is already HPKE encrypted; plaintext key
 * bytes never escape this application service or enter Room.
 */
class PairingKeyPackageBuilder(
    private val keyRing: SyncKeyRingRepository,
    private val keyMaterial: PlatformPairingKeyMaterialExporter,
    private val hpke: PairingHpke,
) {
    suspend fun build(request: PairingKeyPackageBuildRequest): PairingKeyPackageBuildResult {
        val active = keyRing.currentEncryptionKey(request.syncSpaceId)
            ?: return PairingKeyPackageBuildResult.MissingActiveContentKey
        if (
            active.syncSpaceId != request.syncSpaceId ||
            active.usage != SyncSpaceContentKeyUsage.ACTIVE
        ) return PairingKeyPackageBuildResult.InvalidKeyRing

        val historical = keyRing.historicalDecryptKeys(request.syncSpaceId)
            .sortedBy(SyncSpaceContentKeyMetadata::keyEpoch)
        if (
            historical.map(SyncSpaceContentKeyMetadata::keyEpoch).distinct().size != historical.size ||
            historical.any {
                it.syncSpaceId != request.syncSpaceId ||
                    it.usage != SyncSpaceContentKeyUsage.DECRYPT_ONLY ||
                    it.keyEpoch >= active.keyEpoch
            }
        ) return PairingKeyPackageBuildResult.InvalidKeyRing

        val accountMasterKey = keyMaterial.exportAccountMasterKeyForPairing(request.accountMasterKeyReference)
            ?: return PairingKeyPackageBuildResult.MissingAccountMasterKey
        val activeKey = keyMaterial.exportContentKeyForPairing(active.contentKeyReference)
            ?: return PairingKeyPackageBuildResult.MissingActiveContentKey
        if (activeKey.identity != active.contentKeyIdentity) {
            return PairingKeyPackageBuildResult.ContentKeyIdentityMismatch(active.keyEpoch)
        }

        val historicalKeys = ArrayList<HistoricalSyncSpaceKeyV1>(historical.size)
        for (key in historical) {
            val exported = keyMaterial.exportContentKeyForPairing(key.contentKeyReference)
                ?: return PairingKeyPackageBuildResult.MissingHistoricalContentKey(key.keyEpoch)
            if (exported.identity != key.contentKeyIdentity) {
                return PairingKeyPackageBuildResult.ContentKeyIdentityMismatch(key.keyEpoch)
            }
            val raw = exported.material.copyRawKeyBytesForPairing()
            if (raw.size != KEY_BYTES) return PairingKeyPackageBuildResult.InvalidExportedKeyMaterial
            historicalKeys += HistoricalSyncSpaceKeyV1(key.keyEpoch, encodeCanonicalBase64Url(raw))
        }

        val rawAccountMasterKey = accountMasterKey.copyRawKeyBytesForPairing()
        val rawActiveKey = activeKey.material.copyRawKeyBytesForPairing()
        if (rawAccountMasterKey.size != KEY_BYTES || rawActiveKey.size != KEY_BYTES) {
            return PairingKeyPackageBuildResult.InvalidExportedKeyMaterial
        }

        val plaintext = KeyPackagePlaintextV1(
            accountId = request.accountId,
            requestId = request.enrollmentRequest.requestId,
            targetDeviceId = request.enrollmentRequest.targetDeviceId,
            keyEpoch = active.keyEpoch,
            accountMasterKeyBase64Url = encodeCanonicalBase64Url(rawAccountMasterKey),
            syncSpace = SyncSpaceKeyPackageV1(
                syncSpaceId = request.syncSpaceId,
                activeEpoch = active.keyEpoch,
                activeKeyBase64Url = encodeCanonicalBase64Url(rawActiveKey),
                historicalKeys = historicalKeys,
            ),
        )
        return PairingKeyPackageBuildResult.Built(
            hpke.encryptKeyPackage(request.enrollmentRequest.hpkePublicKeyBase64Url, plaintext),
        )
    }

    private companion object {
        const val KEY_BYTES = 32
    }
}
