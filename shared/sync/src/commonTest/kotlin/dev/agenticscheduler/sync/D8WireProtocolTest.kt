package dev.agenticscheduler.sync

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class D8WireProtocolTest {
    @Test fun `v1 envelope and payload use frozen fixtures`() {
        val operation = operation()
        val envelope = EncryptedEnvelopeV1(
            syncSpaceId = SyncSpaceId("personal-space"),
            mutationId = operation.mutationId,
            senderDeviceId = DeviceId("desktop-device"),
            keyEpoch = 7,
            ciphertextBase64Url = "cGF5bG9hZA",
        )
        val expectedEnvelope = """{"envelopeVersion":1,"syncSpaceId":"personal-space","mutationId":"00000000-0000-7000-8000-000000000001","senderDeviceId":"desktop-device","keyEpoch":7,"ciphertextBase64Url":"cGF5bG9hZA"}"""
        val expectedPayload = """{"payloadVersion":1,"operation":{"mutationId":"00000000-0000-7000-8000-000000000001","dvv":{"context":[],"dot":{"replicaId":"00000000-0000-7000-8000-000000000002","counter":1}},"hlc":{"physicalMillis":100,"logical":0,"replicaId":"00000000-0000-7000-8000-000000000002"},"origin":{"type":"USER"},"orderedMutations":[{"type":"EventPut","before":null,"after":{"id":"00000000-0000-7000-8000-000000000003","title":"Read","time":{"type":"ALL_DAY","dates":{"startDate":"2026-01-01","endDateExclusive":"2026-01-02"}},"flexibility":"HARD","pinState":"UNPINNED"},"entityKind":"EVENT"}]}}"""

        assertEquals(expectedEnvelope, SyncWireCodec.encodeEnvelope(envelope))
        assertEquals(envelope, assertIs<EnvelopeDecodeResult.Supported>(SyncWireCodec.decodeEnvelope(expectedEnvelope)).envelope)
        assertEquals(expectedPayload, SyncWireCodec.encodePayload(SyncPayloadV1(operation = operation)))
        assertEquals(operation, assertIs<PayloadDecodeResult.Supported>(SyncWireCodec.decodePayload(expectedPayload)).payload.operation)
    }

    @Test fun `outer ignores additive fields but unknown outer version is rejected before decrypt`() {
        val additive = """{"envelopeVersion":1,"syncSpaceId":"space","mutationId":"00000000-0000-7000-8000-000000000001","senderDeviceId":"device","keyEpoch":0,"ciphertextBase64Url":"AA","future":"ignored"}"""
        assertEquals("space", assertIs<EnvelopeDecodeResult.Supported>(SyncWireCodec.decodeEnvelope(additive)).envelope.syncSpaceId.value)
        assertEquals(2, assertIs<EnvelopeDecodeResult.UnsupportedVersion>(SyncWireCodec.decodeEnvelope(additive.replace("\"envelopeVersion\":1", "\"envelopeVersion\":2"))).actual)
    }

    @Test fun `unknown inner payload version or mutation rejects the whole operation`() {
        val payload = SyncWireCodec.encodePayload(SyncPayloadV1(operation = operation()))
        assertEquals(2, assertIs<PayloadDecodeResult.UnsupportedVersion>(SyncWireCodec.decodePayload(payload.replace("\"payloadVersion\":1", "\"payloadVersion\":2"))).actual)
        assertEquals("FutureMutation", assertIs<PayloadDecodeResult.UnsupportedMutation>(SyncWireCodec.decodePayload(payload.replace("\"type\":\"EventPut\"", "\"type\":\"FutureMutation\""))).discriminator)
    }

    @Test fun `malformed payload version and discriminator JSON types return invalid`() {
        val payload = SyncWireCodec.encodePayload(SyncPayloadV1(operation = operation()))

        assertIs<PayloadDecodeResult.Invalid>(
            SyncWireCodec.decodePayload(payload.replace("\"payloadVersion\":1", "\"payloadVersion\":{}")),
        )
        assertIs<PayloadDecodeResult.Invalid>(
            SyncWireCodec.decodePayload(payload.replace("\"payloadVersion\":1", "\"payloadVersion\":\"1\"")),
        )
        assertIs<PayloadDecodeResult.Invalid>(
            SyncWireCodec.decodePayload(payload.replace("\"type\":\"EventPut\"", "\"type\":{}")),
        )
        assertIs<PayloadDecodeResult.Invalid>(
            SyncWireCodec.decodePayload(payload.replace("\"type\":\"USER\"", "\"type\":[]")),
        )
    }

    private fun operation() = SyncOperation(
        mutationId = "00000000-0000-7000-8000-000000000001",
        dvv = DvvSnapshot(emptyList(), DotSnapshot("00000000-0000-7000-8000-000000000002", 1)),
        hlc = HlcSnapshot(100, 0, "00000000-0000-7000-8000-000000000002"),
        origin = MutationOrigin.User,
        orderedMutations = listOf(
            EventPut(
                before = null,
                after = EventImage(
                    id = "00000000-0000-7000-8000-000000000003",
                    title = "Read",
                    time = EventTimeImage.AllDay(AllDayRangeImage("2026-01-01", "2026-01-02")),
                    flexibility = FlexibilityImage.HARD,
                    pinState = PinStateImage.UNPINNED,
                ),
            ),
        ),
    )
    @Test
    fun `conflict resolution origin round trips and unknown origin fails closed`() {
        val payload = SyncPayloadV1(operation = operation())
        val resolution = payload.copy(operation = payload.operation.copy(
            origin = MutationOrigin.ConflictResolution("conflict-1"),
        ))
        val encoded = SyncWireCodec.encodePayload(resolution)
        assertEquals(
            """{"payloadVersion":1,"operation":{"mutationId":"00000000-0000-7000-8000-000000000001","dvv":{"context":[],"dot":{"replicaId":"00000000-0000-7000-8000-000000000002","counter":1}},"hlc":{"physicalMillis":100,"logical":0,"replicaId":"00000000-0000-7000-8000-000000000002"},"origin":{"type":"CONFLICT_RESOLUTION","conflictId":"conflict-1"},"orderedMutations":[{"type":"EventPut","before":null,"after":{"id":"00000000-0000-7000-8000-000000000003","title":"Read","time":{"type":"ALL_DAY","dates":{"startDate":"2026-01-01","endDateExclusive":"2026-01-02"}},"flexibility":"HARD","pinState":"UNPINNED"},"entityKind":"EVENT"}]}}""",
            encoded,
        )
        val decoded = assertIs<PayloadDecodeResult.Supported>(SyncWireCodec.decodePayload(encoded))
        assertEquals(MutationOrigin.ConflictResolution("conflict-1"), decoded.payload.operation.origin)

        val future = encoded.replace("\"CONFLICT_RESOLUTION\"", "\"FUTURE_RESOLUTION\"")
        val unsupported = assertIs<PayloadDecodeResult.UnsupportedMutation>(SyncWireCodec.decodePayload(future))
        assertEquals("origin:FUTURE_RESOLUTION", unsupported.discriminator)
    }
}
