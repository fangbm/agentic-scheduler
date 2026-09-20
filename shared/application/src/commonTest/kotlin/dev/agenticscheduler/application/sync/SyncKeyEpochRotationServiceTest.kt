package dev.agenticscheduler.application.sync

import dev.agenticscheduler.sync.SyncSpaceId
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class SyncKeyEpochRotationServiceTest {
    @Test
    fun `rotation advances the monotonic epoch without exposing key bytes`() = runBlocking {
        val ring = MemoryRing(SyncSpaceKeyState(SyncSpaceId("space"), 4), InstallSyncSpaceKeyEpochResult.Advanced)
        val material = MemoryMaterial()
        val service = SyncKeyEpochRotationService(
            ring,
            PlatformContentKeyGenerator { ImportedContentKey(SecretReference("secure://key/5"), ContentKeyIdentity("identity-5")) },
            material,
        )
        val result = assertIs<SyncKeyEpochRotationResult.Installed>(service.rotate(SyncSpaceId("space")))
        assertEquals(5L, result.keyEpoch)
        assertEquals(5L, ring.installedEpoch)
        assertEquals(emptyList(), material.deleted)
    }

    @Test
    fun `rejected install deletes the newly generated secure reference`() = runBlocking {
        val ring = MemoryRing(SyncSpaceKeyState(SyncSpaceId("space"), 4), InstallSyncSpaceKeyEpochResult.IntegrityError)
        val material = MemoryMaterial()
        val service = SyncKeyEpochRotationService(
            ring,
            PlatformContentKeyGenerator { ImportedContentKey(SecretReference("secure://key/rejected"), ContentKeyIdentity("identity")) },
            material,
        )
        assertIs<SyncKeyEpochRotationResult.Rejected>(service.rotate(SyncSpaceId("space")))
        assertEquals(listOf(SecretReference("secure://key/rejected")), material.deleted)
    }

    private class MemoryRing(
        private val current: SyncSpaceKeyState?,
        private val installResult: InstallSyncSpaceKeyEpochResult,
    ) : SyncKeyRingRepository {
        var installedEpoch: Long? = null
        override suspend fun state(syncSpaceId: SyncSpaceId) = current
        override suspend fun currentEncryptionKey(syncSpaceId: SyncSpaceId): SyncSpaceContentKeyMetadata? = null
        override suspend fun decryptionKey(syncSpaceId: SyncSpaceId, keyEpoch: Long): SyncSpaceContentKeyMetadata? = null
        override suspend fun historicalDecryptKeys(syncSpaceId: SyncSpaceId) = emptyList<SyncSpaceContentKeyMetadata>()
        override suspend fun installNewEpoch(syncSpaceId: SyncSpaceId, keyEpoch: Long, contentKeyReference: SecretReference, contentKeyIdentity: ContentKeyIdentity): InstallSyncSpaceKeyEpochResult {
            installedEpoch = keyEpoch
            return installResult
        }
        override suspend fun installKeyPackage(syncSpaceId: SyncSpaceId, activeKey: SyncKeyPackageKeyReference, historicalReferences: List<SyncKeyPackageKeyReference>): InstallSyncKeyPackageResult = error("unused")
    }

    private class MemoryMaterial : PlatformKeyMaterialStore {
        val deleted = mutableListOf<SecretReference>()
        override suspend fun importContentKey(material: ImportedContentKeyMaterial) = error("unused")
        override suspend fun contentAead(reference: SecretReference): SyncPayloadAead? = null
        override suspend fun delete(reference: SecretReference) { deleted += reference }
    }
}
