package dev.agenticscheduler.application.sync

import dev.agenticscheduler.sync.SyncSpaceId

/** Opaque platform-secure-store handle. It is metadata safe to persist in Room, never key material. */
@JvmInline
value class SecretReference(val value: String) {
    init { require(value.isNotBlank()) { "Secret reference must not be blank." } }
}

/**
 * Non-secret, cross-platform stable fingerprint supplied by the platform crypto
 * implementation. Its canonical form is base64url-without-padding of
 * SHA-256(raw AES-256 content-key bytes). It is never a secure-store reference
 * and must not be derived from one.
 */
@JvmInline
value class ContentKeyIdentity(val value: String) {
    init { require(value.isNotBlank()) { "Content key identity must not be blank." } }
}

/**
 * No caller receives a SyncSpace key's raw bytes. Platform implementations own
 * AES key generation/import, platform-backed protection and deletion.
 */
interface PlatformKeyMaterialStore {
    /** Imports transient key-package material directly into platform protection and returns only an opaque reference. */
    suspend fun importContentKey(material: ImportedContentKeyMaterial): ImportedContentKey
    suspend fun contentAead(reference: SecretReference): SyncPayloadAead?
    suspend fun delete(reference: SecretReference)
}

data class ImportedContentKey(
    val reference: SecretReference,
    val identity: ContentKeyIdentity,
)

/** Opaque transient result of a platform HPKE/recovery decrypt; it must never be persisted in Room. */
interface ImportedContentKeyMaterial

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
    val contentKeyIdentity: ContentKeyIdentity,
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

/**
 * The Room transaction's authoritative decision about imported secure-store
 * objects. The installer retains only references for [adoptedEpochs] and
 * deletes freshly imported references for [reusedEpochs].
 */
data class SyncKeyPackageAdoption(
    val adoptedEpochs: Set<Long>,
    val reusedEpochs: Set<Long>,
) {
    init {
        require(adoptedEpochs.intersect(reusedEpochs).isEmpty())
    }
}

sealed interface InstallSyncKeyPackageResult {
    val adoption: SyncKeyPackageAdoption

    data class Installed(override val adoption: SyncKeyPackageAdoption) : InstallSyncKeyPackageResult
    data class Advanced(override val adoption: SyncKeyPackageAdoption) : InstallSyncKeyPackageResult
    data class Idempotent(override val adoption: SyncKeyPackageAdoption) : InstallSyncKeyPackageResult
    data class Repaired(override val adoption: SyncKeyPackageAdoption) : InstallSyncKeyPackageResult
    data object IntegrityError : InstallSyncKeyPackageResult {
        override val adoption = SyncKeyPackageAdoption(emptySet(), emptySet())
    }
    data class RejectedRollback(val activeEncryptionEpoch: Long) : InstallSyncKeyPackageResult {
        override val adoption = SyncKeyPackageAdoption(emptySet(), emptySet())
    }
}

interface SyncKeyRingRepository {
    suspend fun state(syncSpaceId: SyncSpaceId): SyncSpaceKeyState?
    suspend fun currentEncryptionKey(syncSpaceId: SyncSpaceId): SyncSpaceContentKeyMetadata?
    suspend fun decryptionKey(syncSpaceId: SyncSpaceId, keyEpoch: Long): SyncSpaceContentKeyMetadata?
    suspend fun historicalDecryptKeys(syncSpaceId: SyncSpaceId): List<SyncSpaceContentKeyMetadata>
    suspend fun installNewEpoch(
        syncSpaceId: SyncSpaceId,
        keyEpoch: Long,
        contentKeyReference: SecretReference,
        contentKeyIdentity: ContentKeyIdentity,
    ): InstallSyncSpaceKeyEpochResult

    suspend fun installKeyPackage(
        syncSpaceId: SyncSpaceId,
        activeKey: SyncKeyPackageKeyReference,
        historicalReferences: List<SyncKeyPackageKeyReference>,
    ): InstallSyncKeyPackageResult
}

data class SyncKeyPackageKeyReference(val keyEpoch: Long, val reference: SecretReference, val identity: ContentKeyIdentity) {
    init { require(keyEpoch >= 0) }
}

data class DecryptedSyncKeyPackage(
    val syncSpaceId: SyncSpaceId,
    val activeEpoch: Long,
    val activeKey: ImportedContentKeyMaterial,
    val historicalKeys: List<SyncKeyPackageHistoricalKey>,
) {
    init {
        require(activeEpoch >= 0)
        require(historicalKeys.map(SyncKeyPackageHistoricalKey::keyEpoch).distinct().size == historicalKeys.size)
        require(historicalKeys.all { it.keyEpoch < activeEpoch })
    }
}

data class SyncKeyPackageHistoricalKey(val keyEpoch: Long, val material: ImportedContentKeyMaterial)

/**
 * Cross-store installation order is intentional: import all material first,
 * then publish the complete ring in one Room transaction. A crash can orphan
 * a secure-store object but can never publish a dangling Room reference.
 */
class SyncKeyPackageInstaller(
    private val keyMaterial: PlatformKeyMaterialStore,
    private val keyRing: SyncKeyRingRepository,
) {
    suspend fun install(value: DecryptedSyncKeyPackage): InstallSyncKeyPackageResult {
        val imported = mutableMapOf<Long, SecretReference>()
        try {
            val active = keyMaterial.importContentKey(value.activeKey).also { imported[value.activeEpoch] = it.reference }
            val historical = value.historicalKeys.map { key ->
                keyMaterial.importContentKey(key.material).let { importedKey ->
                    imported[key.keyEpoch] = importedKey.reference
                    SyncKeyPackageKeyReference(key.keyEpoch, importedKey.reference, importedKey.identity)
                }
            }
            val result = keyRing.installKeyPackage(value.syncSpaceId, SyncKeyPackageKeyReference(value.activeEpoch, active.reference, active.identity), historical)
            for ((epoch, reference) in imported) {
                if (epoch !in result.adoption.adoptedEpochs) {
                    keyMaterial.delete(reference)
                }
            }
            return result
        } catch (failure: Throwable) {
            for (reference in imported.values) {
                try {
                    keyMaterial.delete(reference)
                } catch (_: Throwable) {
                    // Preserve the original import/install failure. Orphaned
                    // secure-store objects are safe; dangling Room references are not.
                }
            }
            throw failure
        }
    }
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
