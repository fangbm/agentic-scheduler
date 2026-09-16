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
        override suspend fun installNewEpoch(syncSpaceId: SyncSpaceId, keyEpoch: Long, contentKeyReference: SecretReference): InstallSyncSpaceKeyEpochResult = error("Not used")
        override suspend fun installKeyPackage(syncSpaceId: SyncSpaceId, activeKey: SyncKeyPackageKeyReference, historicalReferences: List<SyncKeyPackageKeyReference>): InstallSyncSpaceKeyEpochResult = error("Not used")
    }

    private class MemoryKeyMaterial(
        private val expectedReferences: Set<SecretReference>,
        private val key: SyncPayloadAead,
    ) : PlatformKeyMaterialStore {
        override suspend fun importContentKey(material: ImportedContentKeyMaterial): ImportedContentKey = error("Not used")
        override suspend fun contentAead(reference: SecretReference): SyncPayloadAead? = key.takeIf { reference in expectedReferences }
        override suspend fun delete(reference: SecretReference) = Unit
    }

    private data object EchoAead : SyncPayloadAead {
        override fun encryptToBase64Url(plaintextUtf8: String, associatedDataUtf8: String): String = plaintextUtf8
        override fun decryptFromBase64Url(ciphertextBase64Url: String, associatedDataUtf8: String): String = ciphertextBase64Url
    }
}
