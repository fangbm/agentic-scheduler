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
data class SyncSpaceKeyEpochMetadata(
    val syncSpaceId: SyncSpaceId,
    val acceptedKeyEpoch: Long,
    val contentKeyReference: SecretReference,
) {
    init { require(acceptedKeyEpoch >= 0) { "Key epoch must not be negative." } }
}

interface SyncKeyMetadataRepository {
    suspend fun keyEpoch(syncSpaceId: SyncSpaceId): SyncSpaceKeyEpochMetadata?
    suspend fun saveKeyEpoch(value: SyncSpaceKeyEpochMetadata)
}

/**
 * The only content-key lookup used by normal D8 envelope traffic. It reads
 * non-secret durable metadata, rejects epoch rollback and resolves the AEAD
 * only through the platform secure-key boundary.
 */
class SecureSyncPayloadKeyProvider(
    private val metadata: SyncKeyMetadataRepository,
    private val keyMaterial: PlatformKeyMaterialStore,
) : SyncPayloadKeyProvider {
    override suspend fun keyFor(syncSpaceId: SyncSpaceId, keyEpoch: Long): SyncPayloadKeyLookup {
        val current = metadata.keyEpoch(syncSpaceId) ?: return SyncPayloadKeyLookup.Missing
        return when {
            keyEpoch < current.acceptedKeyEpoch -> SyncPayloadKeyLookup.RejectedRollback(current.acceptedKeyEpoch)
            keyEpoch > current.acceptedKeyEpoch -> SyncPayloadKeyLookup.Missing
            else -> keyMaterial.contentAead(current.contentKeyReference)
                ?.let(SyncPayloadKeyLookup::Available)
                ?: SyncPayloadKeyLookup.Missing
        }
    }
}
