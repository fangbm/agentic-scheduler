package dev.agenticscheduler.application.sync

import dev.agenticscheduler.sync.DeviceId
import dev.agenticscheduler.sync.DotSnapshot
import dev.agenticscheduler.sync.DvvSnapshot
import dev.agenticscheduler.sync.EncryptedEnvelopeV1
import dev.agenticscheduler.sync.HlcSnapshot
import dev.agenticscheduler.sync.MutationOrigin
import dev.agenticscheduler.sync.SyncOperation
import dev.agenticscheduler.sync.SyncPayloadV1
import dev.agenticscheduler.sync.SyncSpaceId
import dev.agenticscheduler.sync.SyncWireCodec
import dev.agenticscheduler.sync.VersionComponent
import dev.agenticscheduler.sync.WorkLogAppend
import dev.agenticscheduler.sync.WorkLogImage
import dev.agenticscheduler.sync.ZonedTimeRangeImage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlinx.coroutines.runBlocking

class AuthenticatedSyncEnvelopeCodecTest {
    private val binding = SyncEnvelopeBinding(
        syncSpaceId = SyncSpaceId("personal-space"),
        mutationId = "00000000-0000-7000-8000-000000000001",
        senderDeviceId = DeviceId("desktop-device"),
        keyEpoch = 7,
    )
    private val aead = TinkSyncPayloadAead.generate()
    private val codec = AuthenticatedSyncEnvelopeCodec(
        StaticKeys(binding.syncSpaceId, binding.keyEpoch, aead),
        StaticCurrentKey(binding.syncSpaceId, binding.keyEpoch, aead),
    )

    @Test
    fun `encrypts typed payload with frozen aad and decrypts it`() = runBlocking {
        assertEquals(
            "agentic-scheduler-sync|v1|personal-space|00000000-0000-7000-8000-000000000001|desktop-device|7",
            binding.authenticatedAssociatedData(),
        )

        val envelope = assertIs<EncryptSyncPayloadResult.Encrypted>(codec.encrypt(binding, payload())).envelope
        assertNotEquals("cGF5bG9hZA", envelope.ciphertextBase64Url)

        val decrypted = assertIs<DecryptSyncEnvelopeResult.AuthenticatedPlaintext>(codec.decrypt(envelope))
        assertEquals(SyncWireCodec.encodePayload(payload()), decrypted.payloadJson)
    }

    @Test
    fun `rejects tampered ciphertext and every aad identity component`() = runBlocking {
        val envelope = assertIs<EncryptSyncPayloadResult.Encrypted>(codec.encrypt(binding, payload())).envelope
        val alteredCiphertext = envelope.copy(ciphertextBase64Url = envelope.ciphertextBase64Url.dropLast(1) + "A")
        assertEquals(DecryptSyncEnvelopeResult.AuthenticationFailed, codec.decrypt(alteredCiphertext))

        val aadCodec = AuthenticatedSyncEnvelopeCodec(PermissiveKeys(aead), StaticCurrentKey(binding.syncSpaceId, binding.keyEpoch, aead))
        assertEquals(DecryptSyncEnvelopeResult.AuthenticationFailed, aadCodec.decrypt(envelope.copy(syncSpaceId = SyncSpaceId("other-space"))))
        assertEquals(DecryptSyncEnvelopeResult.AuthenticationFailed, aadCodec.decrypt(envelope.copy(mutationId = "00000000-0000-7000-8000-000000000002")))
        assertEquals(DecryptSyncEnvelopeResult.AuthenticationFailed, aadCodec.decrypt(envelope.copy(senderDeviceId = DeviceId("other-device"))))
        assertEquals(DecryptSyncEnvelopeResult.AuthenticationFailed, aadCodec.decrypt(envelope.copy(keyEpoch = 8)))
    }

    @Test
    fun `rejects wrong key and mismatched inner mutation id`() = runBlocking {
        val envelope = assertIs<EncryptSyncPayloadResult.Encrypted>(codec.encrypt(binding, payload())).envelope
        val wrongKeyCodec = AuthenticatedSyncEnvelopeCodec(
            StaticKeys(binding.syncSpaceId, binding.keyEpoch, TinkSyncPayloadAead.generate()),
            StaticCurrentKey(binding.syncSpaceId, binding.keyEpoch, aead),
        )
        assertEquals(DecryptSyncEnvelopeResult.AuthenticationFailed, wrongKeyCodec.decrypt(envelope))

        val mismatchedBinding = binding.copy(mutationId = "00000000-0000-7000-8000-000000000002")
        assertIs<EncryptSyncPayloadResult.InvalidPayload>(codec.encrypt(mismatchedBinding, payload()))
    }

    @Test
    fun `leaves authenticated unknown inner protocol for SyncEngine durable quarantine`() = runBlocking {
        val unknownPayload = """{"payloadVersion":2,"operation":{"ignored":true}}"""
        val plaintextAead = EchoAead(unknownPayload)
        val plaintextCodec = AuthenticatedSyncEnvelopeCodec(
            PermissiveKeys(plaintextAead),
            StaticCurrentKey(binding.syncSpaceId, binding.keyEpoch, plaintextAead),
        )
        val envelope = EncryptedEnvelopeV1(
            syncSpaceId = binding.syncSpaceId,
            mutationId = binding.mutationId,
            senderDeviceId = binding.senderDeviceId,
            keyEpoch = binding.keyEpoch,
            ciphertextBase64Url = "opaque",
        )

        val decrypted = assertIs<DecryptSyncEnvelopeResult.AuthenticatedPlaintext>(plaintextCodec.decrypt(envelope))
        assertEquals(unknownPayload, decrypted.payloadJson)
    }

    @Test
    fun `rotation retains old ciphertext for decrypt but forbids old epoch outbound encryption`() = runBlocking {
        val epoch7 = TinkSyncPayloadAead.generate()
        val epoch8 = TinkSyncPayloadAead.generate()
        val oldBinding = binding.copy(keyEpoch = 7)
        val oldCodec = AuthenticatedSyncEnvelopeCodec(
            StaticKeys(binding.syncSpaceId, 7, epoch7),
            StaticCurrentKey(binding.syncSpaceId, 7, epoch7),
        )
        val oldEnvelope = assertIs<EncryptSyncPayloadResult.Encrypted>(oldCodec.encrypt(oldBinding, payload())).envelope
        val rotated = RotatedKeys(binding.syncSpaceId, epoch7, epoch8)
        val rotatedCodec = AuthenticatedSyncEnvelopeCodec(rotated, rotated)

        assertIs<DecryptSyncEnvelopeResult.AuthenticatedPlaintext>(rotatedCodec.decrypt(oldEnvelope))
        assertEquals(EncryptSyncPayloadResult.NonActiveKeyEpoch(8), rotatedCodec.encrypt(oldBinding, payload()))
        assertEquals(DecryptSyncEnvelopeResult.MissingContentKey, rotatedCodec.decrypt(oldEnvelope.copy(keyEpoch = 9)))
    }

    private fun payload(): SyncPayloadV1 = SyncPayloadV1(
        operation = SyncOperation(
            mutationId = binding.mutationId,
            dvv = DvvSnapshot(listOf(VersionComponent("desktop-replica", 0)), DotSnapshot("desktop-replica", 1)),
            hlc = HlcSnapshot(1_700_000_000_000, 0, "desktop-replica"),
            origin = MutationOrigin.User,
            orderedMutations = listOf(
                WorkLogAppend(
                    WorkLogImage(
                        id = "work-log-1",
                        taskId = "task-1",
                        time = ZonedTimeRangeImage("2026-09-16T09:00:00", "2026-09-16T09:30:00", "Asia/Shanghai"),
                    ),
                ),
            ),
        ),
    )

    private class StaticKeys(
        private val syncSpaceId: SyncSpaceId,
        private val keyEpoch: Long,
        private val key: SyncPayloadAead,
    ) : SyncPayloadKeyProvider {
        override suspend fun keyFor(syncSpaceId: SyncSpaceId, keyEpoch: Long): SyncPayloadKeyLookup =
            key.takeIf { this.syncSpaceId == syncSpaceId && this.keyEpoch == keyEpoch }
                ?.let(SyncPayloadKeyLookup::Available)
                ?: SyncPayloadKeyLookup.Missing
    }

    private class PermissiveKeys(
        private val key: SyncPayloadAead,
    ) : SyncPayloadKeyProvider {
        override suspend fun keyFor(syncSpaceId: SyncSpaceId, keyEpoch: Long): SyncPayloadKeyLookup = SyncPayloadKeyLookup.Available(key)
    }

    private class StaticCurrentKey(
        private val syncSpaceId: SyncSpaceId,
        private val keyEpoch: Long,
        private val key: SyncPayloadAead,
    ) : CurrentEncryptionKeyProvider {
        override suspend fun currentEncryptionKey(syncSpaceId: SyncSpaceId): CurrentEncryptionKeyLookup =
            if (syncSpaceId == this.syncSpaceId) CurrentEncryptionKeyLookup.Available(keyEpoch, key)
            else CurrentEncryptionKeyLookup.Missing
    }

    private class RotatedKeys(
        private val syncSpaceId: SyncSpaceId,
        private val epoch7: SyncPayloadAead,
        private val epoch8: SyncPayloadAead,
    ) : SyncPayloadKeyProvider, CurrentEncryptionKeyProvider {
        override suspend fun keyFor(syncSpaceId: SyncSpaceId, keyEpoch: Long): SyncPayloadKeyLookup = when {
            syncSpaceId != this.syncSpaceId -> SyncPayloadKeyLookup.Missing
            keyEpoch == 7L -> SyncPayloadKeyLookup.Available(epoch7)
            keyEpoch == 8L -> SyncPayloadKeyLookup.Available(epoch8)
            else -> SyncPayloadKeyLookup.Missing
        }

        override suspend fun currentEncryptionKey(syncSpaceId: SyncSpaceId): CurrentEncryptionKeyLookup =
            if (syncSpaceId == this.syncSpaceId) CurrentEncryptionKeyLookup.Available(8, epoch8)
            else CurrentEncryptionKeyLookup.Missing
    }

    private class EchoAead(
        private val plaintext: String,
    ) : SyncPayloadAead {
        override fun encryptToBase64Url(plaintextUtf8: String, associatedDataUtf8: String): String = "unused"
        override fun decryptFromBase64Url(ciphertextBase64Url: String, associatedDataUtf8: String): String = plaintext
    }
}
