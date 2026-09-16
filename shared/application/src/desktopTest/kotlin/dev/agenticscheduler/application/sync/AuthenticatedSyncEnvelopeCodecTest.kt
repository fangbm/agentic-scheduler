package dev.agenticscheduler.application.sync

import dev.agenticscheduler.sync.DeviceId
import dev.agenticscheduler.sync.DotSnapshot
import dev.agenticscheduler.sync.DvvSnapshot
import dev.agenticscheduler.sync.HlcSnapshot
import dev.agenticscheduler.sync.MutationOrigin
import dev.agenticscheduler.sync.SyncOperation
import dev.agenticscheduler.sync.SyncPayloadV1
import dev.agenticscheduler.sync.SyncSpaceId
import dev.agenticscheduler.sync.VersionComponent
import dev.agenticscheduler.sync.WorkLogAppend
import dev.agenticscheduler.sync.WorkLogImage
import dev.agenticscheduler.sync.ZonedTimeRangeImage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals

class AuthenticatedSyncEnvelopeCodecTest {
    private val binding = SyncEnvelopeBinding(
        syncSpaceId = SyncSpaceId("personal-space"),
        mutationId = "00000000-0000-7000-8000-000000000001",
        senderDeviceId = DeviceId("desktop-device"),
        keyEpoch = 7,
    )
    private val aead = TinkSyncPayloadAead.generate()
    private val codec = AuthenticatedSyncEnvelopeCodec(StaticKeys(binding.syncSpaceId, binding.keyEpoch, aead))

    @Test
    fun `encrypts typed payload with frozen aad and decrypts it`() {
        assertEquals(
            "agentic-scheduler-sync|v1|personal-space|00000000-0000-7000-8000-000000000001|desktop-device|7",
            binding.authenticatedAssociatedData(),
        )

        val envelope = assertIs<EncryptSyncPayloadResult.Encrypted>(codec.encrypt(binding, payload())).envelope
        assertNotEquals("cGF5bG9hZA", envelope.ciphertextBase64Url)

        val decrypted = assertIs<DecryptSyncEnvelopeResult.Decrypted>(codec.decrypt(envelope))
        assertEquals(payload(), decrypted.payload)
    }

    @Test
    fun `rejects tampered ciphertext and every aad identity component`() {
        val envelope = assertIs<EncryptSyncPayloadResult.Encrypted>(codec.encrypt(binding, payload())).envelope
        val alteredCiphertext = envelope.copy(ciphertextBase64Url = envelope.ciphertextBase64Url.dropLast(1) + "A")
        assertEquals(DecryptSyncEnvelopeResult.AuthenticationFailed, codec.decrypt(alteredCiphertext))

        val aadCodec = AuthenticatedSyncEnvelopeCodec(PermissiveKeys(aead))
        assertEquals(DecryptSyncEnvelopeResult.AuthenticationFailed, aadCodec.decrypt(envelope.copy(syncSpaceId = SyncSpaceId("other-space"))))
        assertEquals(DecryptSyncEnvelopeResult.AuthenticationFailed, aadCodec.decrypt(envelope.copy(mutationId = "00000000-0000-7000-8000-000000000002")))
        assertEquals(DecryptSyncEnvelopeResult.AuthenticationFailed, aadCodec.decrypt(envelope.copy(senderDeviceId = DeviceId("other-device"))))
        assertEquals(DecryptSyncEnvelopeResult.AuthenticationFailed, aadCodec.decrypt(envelope.copy(keyEpoch = 8)))
    }

    @Test
    fun `rejects wrong key and mismatched inner mutation id`() {
        val envelope = assertIs<EncryptSyncPayloadResult.Encrypted>(codec.encrypt(binding, payload())).envelope
        val wrongKeyCodec = AuthenticatedSyncEnvelopeCodec(StaticKeys(binding.syncSpaceId, binding.keyEpoch, TinkSyncPayloadAead.generate()))
        assertEquals(DecryptSyncEnvelopeResult.AuthenticationFailed, wrongKeyCodec.decrypt(envelope))

        val mismatchedBinding = binding.copy(mutationId = "00000000-0000-7000-8000-000000000002")
        assertIs<EncryptSyncPayloadResult.InvalidPayload>(codec.encrypt(mismatchedBinding, payload()))
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
        override fun keyFor(syncSpaceId: SyncSpaceId, keyEpoch: Long): SyncPayloadAead? =
            key.takeIf { this.syncSpaceId == syncSpaceId && this.keyEpoch == keyEpoch }
    }

    private class PermissiveKeys(
        private val key: SyncPayloadAead,
    ) : SyncPayloadKeyProvider {
        override fun keyFor(syncSpaceId: SyncSpaceId, keyEpoch: Long): SyncPayloadAead = key
    }
}
