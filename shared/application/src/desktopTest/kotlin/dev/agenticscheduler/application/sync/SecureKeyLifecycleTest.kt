package dev.agenticscheduler.application.sync

import dev.agenticscheduler.sync.SyncSpaceId
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals

class SecureKeyLifecycleTest {
    @Test
    fun `content key lookup retains historical decrypt keys across rotation`() = runBlocking {
        val space = SyncSpaceId("personal-space")
        val key = EchoAead
        val provider = SecureSyncPayloadKeyProvider(
            metadata = MemoryKeyRing(space),
            keyMaterial = MemoryKeyMaterial(setOf(SecretReference("secure://content-key/7"), SecretReference("secure://content-key/8")), key),
        )
        val outbound = SecureCurrentEncryptionKeyProvider(
            metadata = MemoryKeyRing(space),
            keyMaterial = MemoryKeyMaterial(setOf(SecretReference("secure://content-key/7"), SecretReference("secure://content-key/8")), key),
        )

        assertEquals(SyncPayloadKeyLookup.Available(key), provider.keyFor(space, 7))
        assertEquals(SyncPayloadKeyLookup.Available(key), provider.keyFor(space, 8))
        assertEquals(SyncPayloadKeyLookup.Missing, provider.keyFor(space, 6))
        assertEquals(SyncPayloadKeyLookup.Missing, provider.keyFor(SyncSpaceId("unknown-space"), 7))
        assertEquals(CurrentEncryptionKeyLookup.Available(8, key), outbound.currentEncryptionKey(space))
    }

    @Test
    fun `installer deletes freshly imported references that Room reuses`() = runBlocking {
        val space = SyncSpaceId("personal-space")
        val material = RecordingKeyMaterial()
        val ring = PackageResultRing(
            InstallSyncKeyPackageResult.Advanced(
                SyncKeyPackageAdoption(adoptedEpochs = setOf(8), reusedEpochs = setOf(7)),
            ),
        )
        val installer = SyncKeyPackageInstaller(material, ring)

        val result = installer.install(
            DecryptedSyncKeyPackage(
                syncSpaceId = space,
                activeEpoch = 8,
                activeKey = TestKeyMaterial("active", "identity-8"),
                historicalKeys = listOf(SyncKeyPackageHistoricalKey(7, TestKeyMaterial("historical", "identity-7"))),
            ),
        )

        assertEquals(ring.result, result)
        assertEquals(listOf(SecretReference("secure://active"), SecretReference("secure://historical")), material.imported)
        assertEquals(listOf(SecretReference("secure://historical")), material.deleted)
    }

    @Test
    fun `post commit reused key cleanup failure never deletes an adopted key`() = runBlocking {
        val space = SyncSpaceId("personal-space")
        val historical = SecretReference("secure://historical")
        val material = RecordingKeyMaterial(failingDeletes = setOf(historical))
        val ring = PackageResultRing(
            InstallSyncKeyPackageResult.Advanced(
                SyncKeyPackageAdoption(adoptedEpochs = setOf(8), reusedEpochs = setOf(7)),
            ),
        )

        val result = SyncKeyPackageInstaller(material, ring).install(
            DecryptedSyncKeyPackage(
                syncSpaceId = space,
                activeEpoch = 8,
                activeKey = TestKeyMaterial("active", "identity-8"),
                historicalKeys = listOf(SyncKeyPackageHistoricalKey(7, TestKeyMaterial("historical", "identity-7"))),
            ),
        )

        assertEquals(ring.result, result)
        assertEquals(listOf(historical), material.deleted)
    }

    private class MemoryKeyRing(
        private val space: SyncSpaceId,
    ) : SyncKeyRingRepository {
        private val active = SyncSpaceContentKeyMetadata(space, 8, SecretReference("secure://content-key/8"), ContentKeyIdentity("identity-8"), SyncSpaceContentKeyUsage.ACTIVE)
        private val historical = SyncSpaceContentKeyMetadata(space, 7, SecretReference("secure://content-key/7"), ContentKeyIdentity("identity-7"), SyncSpaceContentKeyUsage.DECRYPT_ONLY)
        override suspend fun state(syncSpaceId: SyncSpaceId): SyncSpaceKeyState? = SyncSpaceKeyState(space, 8).takeIf { syncSpaceId == space }
        override suspend fun currentEncryptionKey(syncSpaceId: SyncSpaceId): SyncSpaceContentKeyMetadata? = active.takeIf { syncSpaceId == space }
        override suspend fun decryptionKey(syncSpaceId: SyncSpaceId, keyEpoch: Long): SyncSpaceContentKeyMetadata? =
            listOf(active, historical).firstOrNull { it.syncSpaceId == syncSpaceId && it.keyEpoch == keyEpoch }
        override suspend fun historicalDecryptKeys(syncSpaceId: SyncSpaceId): List<SyncSpaceContentKeyMetadata> = listOf(historical).takeIf { syncSpaceId == space }.orEmpty()
        override suspend fun installNewEpoch(
            syncSpaceId: SyncSpaceId,
            keyEpoch: Long,
            contentKeyReference: SecretReference,
            contentKeyIdentity: ContentKeyIdentity,
        ): InstallSyncSpaceKeyEpochResult = error("Not used")

        override suspend fun installKeyPackage(
            syncSpaceId: SyncSpaceId,
            activeKey: SyncKeyPackageKeyReference,
            historicalReferences: List<SyncKeyPackageKeyReference>,
        ): InstallSyncKeyPackageResult = error("Not used")
    }

    private class MemoryKeyMaterial(
        private val expectedReferences: Set<SecretReference>,
        private val key: SyncPayloadAead,
    ) : PlatformKeyMaterialStore {
        override suspend fun importContentKey(material: ImportedContentKeyMaterial): ImportedContentKey = error("Not used")
        override suspend fun contentAead(reference: SecretReference): SyncPayloadAead? = key.takeIf { reference in expectedReferences }
        override suspend fun delete(reference: SecretReference) = Unit
    }

    private class PackageResultRing(
        val result: InstallSyncKeyPackageResult,
    ) : SyncKeyRingRepository {
        override suspend fun state(syncSpaceId: SyncSpaceId): SyncSpaceKeyState? = error("Not used")
        override suspend fun currentEncryptionKey(syncSpaceId: SyncSpaceId): SyncSpaceContentKeyMetadata? = error("Not used")
        override suspend fun decryptionKey(syncSpaceId: SyncSpaceId, keyEpoch: Long): SyncSpaceContentKeyMetadata? = error("Not used")
        override suspend fun historicalDecryptKeys(syncSpaceId: SyncSpaceId): List<SyncSpaceContentKeyMetadata> = error("Not used")
        override suspend fun installNewEpoch(
            syncSpaceId: SyncSpaceId,
            keyEpoch: Long,
            contentKeyReference: SecretReference,
            contentKeyIdentity: ContentKeyIdentity,
        ): InstallSyncSpaceKeyEpochResult = error("Not used")

        override suspend fun installKeyPackage(
            syncSpaceId: SyncSpaceId,
            activeKey: SyncKeyPackageKeyReference,
            historicalReferences: List<SyncKeyPackageKeyReference>,
        ): InstallSyncKeyPackageResult = result
    }

    private data class TestKeyMaterial(
        val name: String,
        val identity: String,
    ) : ImportedContentKeyMaterial

    private class RecordingKeyMaterial(
        private val failingDeletes: Set<SecretReference> = emptySet(),
    ) : PlatformKeyMaterialStore {
        val imported = mutableListOf<SecretReference>()
        val deleted = mutableListOf<SecretReference>()

        override suspend fun importContentKey(material: ImportedContentKeyMaterial): ImportedContentKey {
            val source = material as TestKeyMaterial
            return ImportedContentKey(
                reference = SecretReference("secure://${source.name}"),
                identity = ContentKeyIdentity(source.identity),
            ).also { imported += it.reference }
        }

        override suspend fun contentAead(reference: SecretReference): SyncPayloadAead? = null
        override suspend fun delete(reference: SecretReference) {
            deleted += reference
            if (reference in failingDeletes) error("secure-store cleanup unavailable")
        }
    }

    private data object EchoAead : SyncPayloadAead {
        override fun encryptToBase64Url(plaintextUtf8: String, associatedDataUtf8: String): String = plaintextUtf8
        override fun decryptFromBase64Url(ciphertextBase64Url: String, associatedDataUtf8: String): String = ciphertextBase64Url
    }
}
