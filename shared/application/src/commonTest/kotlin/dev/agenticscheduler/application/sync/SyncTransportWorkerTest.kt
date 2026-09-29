package dev.agenticscheduler.application.sync

import dev.agenticscheduler.application.persistence.CommittedMutation
import dev.agenticscheduler.application.persistence.FocusBlockTombstone
import dev.agenticscheduler.application.persistence.HandledReceiveDot
import dev.agenticscheduler.application.persistence.HistoryChange
import dev.agenticscheduler.application.persistence.HistoryRepository
import dev.agenticscheduler.application.persistence.PendingSyncReceive
import dev.agenticscheduler.application.persistence.StoredOutboundEnvelope
import dev.agenticscheduler.application.persistence.SyncOutboundEnvelopeRepository
import dev.agenticscheduler.application.persistence.SyncReceiveRepository
import dev.agenticscheduler.sync.DeviceId
import dev.agenticscheduler.sync.DvvSnapshot
import dev.agenticscheduler.sync.DotSnapshot
import dev.agenticscheduler.sync.EncryptedEnvelopeV1
import dev.agenticscheduler.sync.EventImage
import dev.agenticscheduler.sync.EventPut
import dev.agenticscheduler.sync.EventTimeImage
import dev.agenticscheduler.sync.FlexibilityImage
import dev.agenticscheduler.sync.HlcSnapshot
import dev.agenticscheduler.sync.MutationOrigin
import dev.agenticscheduler.sync.PinStateImage
import dev.agenticscheduler.sync.ProtocolQuarantine
import dev.agenticscheduler.sync.SyncOperation
import dev.agenticscheduler.sync.SyncSpaceId
import dev.agenticscheduler.sync.AllDayRangeImage
import dev.agenticscheduler.sync.SyncConflict
import dev.agenticscheduler.sync.MutationId
import dev.agenticscheduler.sync.SyncWireCodec
import dev.agenticscheduler.application.history.SyncReceiveResult
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SyncTransportWorkerTest {
    @Test
    fun `failed upload retains exact ciphertext for idempotent retry`() = runBlocking {
        val outbound = MemoryOutbound()
        val transport = MemoryTransport()
        val keys = CountingKeys { transport.encryptions++ }
        val codec = AuthenticatedSyncEnvelopeCodec(keys, keys)
        val worker = SyncTransportWorker(
            history = MemoryHistory(operation()),
            outbound = outbound,
            receive = MemoryReceive(),
            codec = codec,
            encryptionKeys = keys,
            deviceId = DeviceId("device"),
            transport = transport,
            receiveGateway = SyncEnvelopeReceiver { _, _ -> error("fetch is empty") },
        )

        val first = worker.run(SyncSpaceId("space"))
        val retained = assertNotNull(outbound.value)
        assertEquals(0, first.uploaded)
        assertNotNull(first.stoppedOnFailure)
        assertFalse(retained.uploaded)
        assertEquals(1, transport.encryptions)
        val firstEnvelope = retained.envelope

        transport.shouldFail = false
        val second = worker.run(SyncSpaceId("space"))
        assertEquals(1, second.uploaded)
        assertTrue(assertNotNull(outbound.value).uploaded)
        assertEquals(2, transport.uploaded.size)
        assertEquals(firstEnvelope, transport.uploaded.last())
        assertEquals(1, transport.encryptions)
    }

    @Test
    fun `direct and relay routes produce the same receive outcome`() = runBlocking {
        val remote = RemoteSyncEnvelope(
            3,
            EncryptedEnvelopeV1(
                syncSpaceId = SyncSpaceId("space"),
                mutationId = "00000000-0000-7000-8000-000000000099",
                senderDeviceId = DeviceId("remote"),
                keyEpoch = 0,
                ciphertextBase64Url = "AQI",
            ),
        )
        val directSeen = mutableListOf<String>()
        val relaySeen = mutableListOf<String>()
        val direct = workerFor(ReplayTransport(remote), directSeen)
        val relay = workerFor(ReplayTransport(remote), relaySeen)
        assertEquals(direct.run(SyncSpaceId("space")), relay.run(SyncSpaceId("space")))
        assertEquals(directSeen, relaySeen)
        assertEquals(listOf(SyncWireCodec.encodeEnvelope(remote.envelope)), directSeen)
    }

    @Test
    fun `received history is never re-encrypted for upload`() = runBlocking {
        val local = operation()
        val received = local.copy(mutationId = "00000000-0000-7000-8000-000000000098")
        val transport = MemoryTransport().apply { shouldFail = false }
        val keys = CountingKeys { transport.encryptions++ }
        val worker = SyncTransportWorker(
            history = MemoryHistory(listOf(CommittedMutation(local, 0, true), CommittedMutation(received, 0, false))),
            outbound = MemoryOutbound(),
            receive = MemoryReceive(),
            codec = AuthenticatedSyncEnvelopeCodec(keys, keys),
            encryptionKeys = keys,
            deviceId = DeviceId("device"),
            transport = transport,
            receiveGateway = SyncEnvelopeReceiver { _, _ -> error("fetch is empty") },
        )
        worker.run(SyncSpaceId("space"))
        assertEquals(1, transport.uploaded.size)
        assertEquals(local.mutationId, transport.uploaded.single().mutationId)
    }

    @Test
    fun `Agent origin cannot upload until device local compatibility gate is enabled`() = runBlocking {
        val agent = operation().copy(origin = MutationOrigin.Agent("00000000-0000-7000-8000-000000000092"))
        val transport = MemoryTransport().apply { shouldFail = false }
        val outbound = MemoryOutbound()
        val keys = CountingKeys { transport.encryptions++ }
        fun worker(gate: AgentOutboundCompatibilityGate = AgentOutboundCompatibilityGate { false }) = SyncTransportWorker(
            history = MemoryHistory(agent), outbound = outbound, receive = MemoryReceive(),
            codec = AuthenticatedSyncEnvelopeCodec(keys, keys), encryptionKeys = keys,
            deviceId = DeviceId("device"), transport = transport,
            receiveGateway = SyncEnvelopeReceiver { _, _ -> error("fetch is empty") },
            agentOutboundGate = gate,
        )

        val denied = worker().run(SyncSpaceId("space"))
        assertNotNull(denied.stoppedOnFailure)
        assertEquals(0, transport.encryptions)
        assertEquals(0, transport.uploaded.size)
        assertNull(outbound.value)

        val accepted = worker(AgentOutboundCompatibilityGate { it == SyncSpaceId("space") }).run(SyncSpaceId("space"))
        assertEquals(1, accepted.uploaded)
        assertEquals(1, transport.uploaded.size)
    }

    private fun workerFor(transport: SyncTransport, seen: MutableList<String>) = SyncTransportWorker(
        history = MemoryHistory(emptyList()),
        outbound = MemoryOutbound(),
        receive = MemoryReceive(),
        codec = AuthenticatedSyncEnvelopeCodec(CountingKeys {}, CountingKeys {}),
        encryptionKeys = CountingKeys {},
        deviceId = DeviceId("device"),
        transport = transport,
        receiveGateway = SyncEnvelopeReceiver { encoded, _ ->
            seen += encoded
            EncryptedSyncReceiveResult.Handled(SyncReceiveResult.Duplicate(MutationId("00000000-0000-7000-8000-000000000099")))
        },
    )

    private class CountingKeys(private val onEncrypt: () -> Unit) : SyncPayloadKeyProvider, CurrentEncryptionKeyProvider {
        private val aead = object : SyncPayloadAead {
            override fun encryptToBase64Url(plaintextUtf8: String, associatedDataUtf8: String): String {
                onEncrypt()
                return "AQI"
            }
            override fun decryptFromBase64Url(ciphertextBase64Url: String, associatedDataUtf8: String): String = error("unused")
        }
        override suspend fun keyFor(syncSpaceId: SyncSpaceId, keyEpoch: Long) = SyncPayloadKeyLookup.Available(aead)
        override suspend fun currentEncryptionKey(syncSpaceId: SyncSpaceId) = CurrentEncryptionKeyLookup.Available(0, aead)
    }

    private fun operation() = SyncOperation(
        mutationId = "mutation-1",
        dvv = DvvSnapshot(emptyList(), DotSnapshot("replica", 1)),
        hlc = HlcSnapshot(1, 0, "replica"),
        origin = MutationOrigin.User,
        orderedMutations = listOf(
            EventPut(
                before = null,
                after = EventImage(
                    id = "event-1",
                    title = "Opaque",
                    time = EventTimeImage.AllDay(AllDayRangeImage("2026-01-01", "2026-01-02")),
                    flexibility = FlexibilityImage.HARD,
                    pinState = PinStateImage.UNPINNED,
                ),
            ),
        ),
    )

    private class MemoryOutbound : SyncOutboundEnvelopeRepository {
        var value: StoredOutboundEnvelope? = null
        override suspend fun envelope(syncSpaceId: SyncSpaceId, mutationId: String) = value?.takeIf { it.syncSpaceId == syncSpaceId && it.mutationId == mutationId }
        override suspend fun save(value: StoredOutboundEnvelope) { this.value = value }
        override suspend fun markUploaded(syncSpaceId: SyncSpaceId, mutationId: String) { value = requireNotNull(value).copy(uploaded = true) }
    }

    private class MemoryTransport : SyncTransport {
        var shouldFail = true
        var encryptions = 0
        val uploaded = mutableListOf<EncryptedEnvelopeV1>()
        override suspend fun upload(envelope: EncryptedEnvelopeV1): SyncUploadResult {
            uploaded += envelope
            return if (shouldFail) SyncUploadResult.RetryableFailure("offline") else SyncUploadResult.Stored(1)
        }
        override suspend fun fetch(syncSpaceId: SyncSpaceId, afterCursor: Long, limit: Int) = emptyList<RemoteSyncEnvelope>()
    }

    private class MemoryHistory(private val values: List<CommittedMutation>) : HistoryRepository {
        constructor(value: SyncOperation) : this(listOf(CommittedMutation(value, 0)))
        override suspend fun timeline() = values
        override suspend fun mutation(mutationId: String) = null
        override suspend fun entityChanges(entityKind: dev.agenticscheduler.sync.EntityKind, entityId: String) = emptyList<HistoryChange>()
        override suspend fun diff(mutationId: String) = emptyList<HistoryChange>()
        override suspend fun focusBlockTombstone(focusBlockId: String): FocusBlockTombstone? = null
    }

    private class ReplayTransport(private val remote: RemoteSyncEnvelope) : SyncTransport {
        private var delivered = false
        override suspend fun upload(envelope: EncryptedEnvelopeV1) = SyncUploadResult.Stored(1)
        override suspend fun fetch(syncSpaceId: SyncSpaceId, afterCursor: Long, limit: Int): List<RemoteSyncEnvelope> =
            if (delivered) emptyList() else listOf(remote).also { delivered = true }
    }

    private class MemoryReceive : SyncReceiveRepository {
        override suspend fun serverCursor(syncSpaceId: SyncSpaceId) = 0L
        override suspend fun saveServerCursor(syncSpaceId: SyncSpaceId, cursor: Long) = Unit
        override suspend fun pending(syncSpaceId: SyncSpaceId, mutationId: String): PendingSyncReceive? = null
        override suspend fun pending(syncSpaceId: SyncSpaceId): List<PendingSyncReceive> = emptyList()
        override suspend fun savePending(value: PendingSyncReceive) = Unit
        override suspend fun removePending(syncSpaceId: SyncSpaceId, mutationId: String) = Unit
        override suspend fun handledDot(syncSpaceId: SyncSpaceId, replicaId: dev.agenticscheduler.sync.ReplicaId, counter: Long): HandledReceiveDot? = null
        override suspend fun handledDots(syncSpaceId: SyncSpaceId): List<HandledReceiveDot> = emptyList()
        override suspend fun saveHandledDot(value: HandledReceiveDot) = Unit
        override suspend fun quarantine(value: ProtocolQuarantine) = Unit
        override suspend fun quarantine(syncSpaceId: SyncSpaceId, mutationId: String): ProtocolQuarantine? = null
        override suspend fun saveConflict(value: SyncConflict) = Unit
        override suspend fun conflict(conflictId: String): SyncConflict? = null
        override suspend fun conflicts(syncSpaceId: SyncSpaceId): List<SyncConflict> = emptyList()
    }
}
