package dev.agenticscheduler.sync

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

class AgentSyncWireProtocolTest {
    private val operationId = MutationId(id(1))
    private val agentReplica = AgentReplicaId(id(2))

    @Test fun `V3 thread creation matches exact canonical JSON golden fixture`() {
        val payload = SyncPayloadV3(operation = AgentSyncOperation(
            operationId = operationId,
            agentDvv = AgentDvvSnapshot(emptyList(), AgentDot(agentReplica, 1)),
            hlc = AgentHlcSnapshot(100, 0, agentReplica),
            agentEvent = ThreadCreated(AgentThreadSyncId(id(3)), "Planning", 90),
        ))
        val fixture = """{"payloadVersion":3,"operation":{"operationId":"${id(1)}","agentDvv":{"context":[],"dot":{"replicaId":"${id(2)}","counter":1}},"hlc":{"physicalMillis":100,"logical":0,"replicaId":"${id(2)}"},"agentEvent":{"type":"ThreadCreated","threadId":"${id(3)}","title":"Planning","createdAtEpochMillis":90}}}"""

        assertEquals(fixture, AgentSyncWireCodec.encodePayload(payload))
        assertEquals(payload, assertIs<AgentPayloadDecodeResult.Supported>(AgentSyncWireCodec.decodePayload(fixture, operationId)).payload)
    }

    @Test fun `V3 turn manifest preserves declared parentage and member ordering`() {
        val payload = SyncPayloadV3(operation = AgentSyncOperation(
            operationId = MutationId(id(11)),
            agentDvv = AgentDvvSnapshot(listOf(AgentVersionComponent(agentReplica, 1)), AgentDot(AgentReplicaId(id(12)), 2)),
            hlc = AgentHlcSnapshot(250, 1, AgentReplicaId(id(12))),
            agentEvent = TurnFinalized(
                AgentTurnSyncId(id(13)), AgentThreadSyncId(id(3)), listOf(AgentTurnSyncId(id(10))),
                listOf(MessageMember(AgentMessageSyncId(id(14))), ToolCallMember(AgentToolCallSyncId(id(15))),
                    ToolResultMember(AgentToolResultSyncId(id(16))), ActionMember(AgentActionSyncId(id(17)))),
                AgentTurnOutcome.FAILED,
            ),
        ))
        val fixture = """{"payloadVersion":3,"operation":{"operationId":"${id(11)}","agentDvv":{"context":[{"replicaId":"${id(2)}","counter":1}],"dot":{"replicaId":"${id(12)}","counter":2}},"hlc":{"physicalMillis":250,"logical":1,"replicaId":"${id(12)}"},"agentEvent":{"type":"TurnFinalized","turnId":"${id(13)}","threadId":"${id(3)}","parentTurnIds":["${id(10)}"],"orderedMembers":[{"type":"MESSAGE","id":"${id(14)}"},{"type":"TOOL_CALL","id":"${id(15)}"},{"type":"TOOL_RESULT","id":"${id(16)}"},{"type":"ACTION","id":"${id(17)}"}],"outcome":"FAILED"}}}"""

        assertEquals(fixture, AgentSyncWireCodec.encodePayload(payload))
        assertEquals(payload, assertIs<AgentPayloadDecodeResult.Supported>(AgentSyncWireCodec.decodePayload(fixture, MutationId(id(11)))).payload)
    }

    @Test fun `Agent causal DTO identities and context are isolated and canonical`() {
        assertFailsWith<IllegalArgumentException> { AgentReplicaId("business-replica") }
        assertFailsWith<IllegalArgumentException> {
            AgentDvvSnapshot(
                listOf(AgentVersionComponent(AgentReplicaId(id(4)), 1), AgentVersionComponent(agentReplica, 1)),
                AgentDot(agentReplica, 2),
            )
        }
    }

    @Test fun `outer authenticated routing ID must equal the V3 operation ID`() {
        val fixture = """{"payloadVersion":3,"operation":{"operationId":"${id(1)}","agentDvv":{"context":[],"dot":{"replicaId":"${id(2)}","counter":1}},"hlc":{"physicalMillis":100,"logical":0,"replicaId":"${id(2)}"},"agentEvent":{"type":"ThreadCreated","threadId":"${id(3)}","title":"Planning","createdAtEpochMillis":90}}}"""
        val mismatch = assertIs<AgentPayloadDecodeResult.OuterIdMismatch>(AgentSyncWireCodec.decodePayload(fixture, MutationId(id(9))))
        assertEquals(MutationId(id(9)), mismatch.expected)
        assertEquals(operationId, mismatch.actual)
    }

    @Test fun `unknown V3 event is rejected without partial decode`() {
        val fixture = """{"payloadVersion":3,"operation":{"operationId":"${id(1)}","agentDvv":{"context":[],"dot":{"replicaId":"${id(2)}","counter":1}},"hlc":{"physicalMillis":100,"logical":0,"replicaId":"${id(2)}"},"agentEvent":{"type":"FutureEvent"}}}"""
        assertEquals("FutureEvent", assertIs<AgentPayloadDecodeResult.UnsupportedEvent>(AgentSyncWireCodec.decodePayload(fixture, operationId)).discriminator)
    }

    @Test fun `legacy D8 quarantines entire unknown V3 envelope and still decodes independent V1 business operation`() {
        val v3 = AgentSyncWireCodec.encodePayload(SyncPayloadV3(operation = AgentSyncOperation(
            operationId, AgentDvvSnapshot(emptyList(), AgentDot(agentReplica, 1)), AgentHlcSnapshot(100, 0, agentReplica),
            ThreadCreated(AgentThreadSyncId(id(3)), "Planning", 90),
        )))
        val legacy = assertIs<PayloadDecodeResult.UnsupportedVersion>(SyncWireCodec.decodePayload(v3))
        assertEquals(3, legacy.actual)
        val quarantine = ProtocolQuarantine(SyncSpaceId("space"), operationId.value, 41, ProtocolQuarantineReason.UNSUPPORTED_PAYLOAD_VERSION, "3")
        assertEquals(operationId.value, quarantine.mutationId)

        val business = businessPayload()
        assertEquals(1, assertIs<PayloadDecodeResult.Supported>(SyncWireCodec.decodePayload(business)).payload.payloadVersion)
    }

    @Test fun `encoded V3 plaintext cap is measured in UTF8 bytes`() {
        val base = SyncPayloadV3(operation = AgentSyncOperation(
            operationId, AgentDvvSnapshot(emptyList(), AgentDot(agentReplica, 1)), AgentHlcSnapshot(100, 0, agentReplica),
            ThreadCreated(AgentThreadSyncId(id(3)), "", 90),
        ))
        val baseSize = AgentSyncWireCodec.encodePayload(base).encodeToByteArray().size
        val exactPadding = "x".repeat(AgentSyncWireCodec.MAX_PLAINTEXT_UTF8_BYTES - baseSize)
        val exactLimit = base.copy(operation = base.operation.copy(agentEvent = ThreadCreated(AgentThreadSyncId(id(3)), exactPadding, 90)))
        assertEquals(AgentSyncWireCodec.MAX_PLAINTEXT_UTF8_BYTES, AgentSyncWireCodec.encodePayload(exactLimit).encodeToByteArray().size)
        val multibyteOverLimit = exactLimit.copy(operation = exactLimit.operation.copy(agentEvent = ThreadCreated(AgentThreadSyncId(id(3)), exactPadding + "界", 90)))
        assertFailsWith<AgentSyncPayloadTooLargeException> { AgentSyncWireCodec.encodePayload(multibyteOverLimit) }
    }

    private fun businessPayload() = SyncWireCodec.encodePayload(SyncPayloadV1(
        operation = SyncOperation(
            mutationId = id(30),
            dvv = DvvSnapshot(emptyList(), DotSnapshot(id(31), 1)),
            hlc = HlcSnapshot(200, 0, id(31)),
            origin = MutationOrigin.User,
            orderedMutations = listOf(EventPut(null, EventImage(id(32), "Read", EventTimeImage.AllDay(AllDayRangeImage("2026-01-01", "2026-01-02")), FlexibilityImage.HARD, PinStateImage.UNPINNED))),
        ),
    ))

    private fun id(value: Int) = "00000000-0000-7000-8000-${value.toString().padStart(12, '0')}"
}
