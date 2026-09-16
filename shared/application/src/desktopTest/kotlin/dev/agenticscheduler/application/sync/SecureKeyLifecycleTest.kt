package dev.agenticscheduler.application.sync

import dev.agenticscheduler.sync.SyncSpaceId
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals

class SecureKeyLifecycleTest {
    @Test
    fun `content key lookup uses durable accepted epoch and rejects rollback`() = runBlocking {
        val space = SyncSpaceId("personal-space")
        val key = EchoAead
        val provider = SecureSyncPayloadKeyProvider(
            metadata = MemoryMetadata(SyncSpaceKeyEpochMetadata(space, 7, SecretReference("secure://content-key/7"))),
            keyMaterial = MemoryKeyMaterial(SecretReference("secure://content-key/7"), key),
        )

        assertEquals(SyncPayloadKeyLookup.Available(key), provider.keyFor(space, 7))
        assertEquals(SyncPayloadKeyLookup.RejectedRollback(7), provider.keyFor(space, 6))
        assertEquals(SyncPayloadKeyLookup.Missing, provider.keyFor(space, 8))
        assertEquals(SyncPayloadKeyLookup.Missing, provider.keyFor(SyncSpaceId("unknown-space"), 7))
    }

    private class MemoryMetadata(
        private val value: SyncSpaceKeyEpochMetadata,
    ) : SyncKeyMetadataRepository {
        override suspend fun keyEpoch(syncSpaceId: SyncSpaceId): SyncSpaceKeyEpochMetadata? = value.takeIf { it.syncSpaceId == syncSpaceId }
        override suspend fun saveKeyEpoch(value: SyncSpaceKeyEpochMetadata) = Unit
    }

    private class MemoryKeyMaterial(
        private val expectedReference: SecretReference,
        private val key: SyncPayloadAead,
    ) : PlatformKeyMaterialStore {
        override suspend fun contentAead(reference: SecretReference): SyncPayloadAead? = key.takeIf { reference == expectedReference }
        override suspend fun delete(reference: SecretReference) = Unit
    }

    private data object EchoAead : SyncPayloadAead {
        override fun encryptToBase64Url(plaintextUtf8: String, associatedDataUtf8: String): String = plaintextUtf8
        override fun decryptFromBase64Url(ciphertextBase64Url: String, associatedDataUtf8: String): String = ciphertextBase64Url
    }
}
