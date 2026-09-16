package dev.agenticscheduler.application.sync

import dev.agenticscheduler.sync.SyncSpaceId

/** Opaque platform-secure-store handle. It is metadata safe to persist in Room, never key material. */
@JvmInline
value class SecretReference(val value: String) {
    init { require(value.isNotBlank()) { "Secret reference must not be blank." } }
}

/**
 * No caller receives a SyncSpace key's raw bytes. Platform implementations own
 * AES key generation/import, platform-backed protection and deletion.
 */
interface PlatformKeyMaterialStore {
    suspend fun contentAead(reference: SecretReference): SyncPayloadAead?
    suspend fun delete(reference: SecretReference)
}

/** Separate boundary for recovery secrets and device credentials. Room stores only SecretReference values. */
interface PlatformSecretStore {
    suspend fun delete(reference: SecretReference)
}

/** Durable non-secret key metadata. A key epoch is monotonic for one SyncSpace. */
enum class SyncSpaceContentKeyUsage { ACTIVE, DECRYPT_ONLY }

data class SyncSpaceContentKeyMetadata(
    val syncSpaceId: SyncSpaceId,
    val keyEpoch: Long,
    val contentKeyReference: SecretReference,
    val usage: SyncSpaceContentKeyUsage,
) {
    init { require(keyEpoch >= 0) { "Key epoch must not be negative." } }
}

data class SyncSpaceKeyState(
    val syncSpaceId: SyncSpaceId,
    val activeEncryptionEpoch: Long,
) { init { require(activeEncryptionEpoch >= 0) } }

sealed interface InstallSyncSpaceKeyEpochResult {
    data object Installed : InstallSyncSpaceKeyEpochResult
    data object Advanced : InstallSyncSpaceKeyEpochResult
    data object Idempotent : InstallSyncSpaceKeyEpochResult
    data object IntegrityError : InstallSyncSpaceKeyEpochResult
    data class RejectedRollback(val activeEncryptionEpoch: Long) : InstallSyncSpaceKeyEpochResult
}

interface SyncKeyRingRepository {
    suspend fun state(syncSpaceId: SyncSpaceId): SyncSpaceKeyState?
    suspend fun currentEncryptionKey(syncSpaceId: SyncSpaceId): SyncSpaceContentKeyMetadata?
    suspend fun decryptionKey(syncSpaceId: SyncSpaceId, keyEpoch: Long): SyncSpaceContentKeyMetadata?
    suspend fun historicalDecryptKeys(syncSpaceId: SyncSpaceId): List<SyncSpaceContentKeyMetadata>
    suspend fun installNewEpoch(syncSpaceId: SyncSpaceId, keyEpoch: Long, contentKeyReference: SecretReference): InstallSyncSpaceKeyEpochResult
}

/**
 * The only content-key lookup used by normal D8 envelope traffic. It reads
 * non-secret durable metadata, rejects epoch rollback and resolves the AEAD
 * only through the platform secure-key boundary.
 */
class SecureSyncPayloadKeyProvider(
    private val metadata: SyncKeyRingRepository,
    private val keyMaterial: PlatformKeyMaterialStore,
) : SyncPayloadKeyProvider {
    override suspend fun keyFor(syncSpaceId: SyncSpaceId, keyEpoch: Long): SyncPayloadKeyLookup {
        val key = metadata.decryptionKey(syncSpaceId, keyEpoch) ?: return SyncPayloadKeyLookup.Missing
        return keyMaterial.contentAead(key.contentKeyReference)
            ?.let(SyncPayloadKeyLookup::Available)
            ?: SyncPayloadKeyLookup.Missing
    }
}

class SecureCurrentEncryptionKeyProvider(
    private val metadata: SyncKeyRingRepository,
    private val keyMaterial: PlatformKeyMaterialStore,
) : CurrentEncryptionKeyProvider {
    override suspend fun currentEncryptionKey(syncSpaceId: SyncSpaceId): CurrentEncryptionKeyLookup {
        val key = metadata.currentEncryptionKey(syncSpaceId) ?: return CurrentEncryptionKeyLookup.Missing
        return keyMaterial.contentAead(key.contentKeyReference)
            ?.let { CurrentEncryptionKeyLookup.Available(key.keyEpoch, it) }
            ?: CurrentEncryptionKeyLookup.Missing
    }
}
