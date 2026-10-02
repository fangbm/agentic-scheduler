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
        assertFailsWith<IllegalArgumentException> {
            AgentDvvSnapshot(listOf(AgentVersionComponent(agentReplica, 2)), AgentDot(agentReplica, 2))
        }
        assertFailsWith<IllegalArgumentException> {
            AgentSyncOperation(
                operationId,
                AgentDvvSnapshot(emptyList(), AgentDot(agentReplica, 1)),
                AgentHlcSnapshot(100, 0, AgentReplicaId(id(4))),
                ThreadCreated(AgentThreadSyncId(id(3)), "Planning", 90),
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

    @Test fun `unknown turn member discriminator is rejected as invalid payload`() {
        val fixture = """{"payloadVersion":3,"operation":{"operationId":"${id(11)}","agentDvv":{"context":[],"dot":{"replicaId":"${id(2)}","counter":1}},"hlc":{"physicalMillis":100,"logical":0,"replicaId":"${id(2)}"},"agentEvent":{"type":"TurnFinalized","turnId":"${id(13)}","threadId":"${id(3)}","parentTurnIds":[],"orderedMembers":[{"type":"FUTURE_MEMBER","id":"${id(14)}"}],"outcome":"SUCCEEDED"}}}"""
        assertIs<AgentPayloadDecodeResult.Invalid>(AgentSyncWireCodec.decodePayload(fixture, MutationId(id(11))))
    }

    @Test fun `prohibited provider metadata is rejected before projection`() {
        val fixture = """{"payloadVersion":3,"operation":{"operationId":"${id(1)}","agentDvv":{"context":[],"dot":{"replicaId":"${id(2)}","counter":1}},"hlc":{"physicalMillis":100,"logical":0,"replicaId":"${id(2)}"},"agentEvent":{"type":"ThreadCreated","threadId":"${id(3)}","title":"Planning","createdAtEpochMillis":90,"providerSessionId":"private"}}}"""
        assertIs<AgentPayloadDecodeResult.Invalid>(AgentSyncWireCodec.decodePayload(fixture, operationId))
    }

    @Test fun `tool input and result JSON are opaque and round trip even with apiKey fields`() {
        val inputs = kotlinx.serialization.json.buildJsonObject {
            put("apiKey", kotlinx.serialization.json.JsonPrimitive("user-defined-input"))
        }
        val toolCallPayload = SyncPayloadV3(operation = AgentSyncOperation(
            operationId,
            AgentDvvSnapshot(emptyList(), AgentDot(agentReplica, 1)),
            AgentHlcSnapshot(100, 0, agentReplica),
            ToolCallFinalized(
                AgentToolCallSyncId(id(40)), AgentThreadSyncId(id(3)), AgentTurnSyncId(id(41)),
                AgentMessageSyncId(id(42)), "custom.submit", inputs, FinalAgentToolCallStatus.COMPLETED,
            ),
        ))
        val encodedToolCall = AgentSyncWireCodec.encodePayload(toolCallPayload)
        assertEquals(toolCallPayload, assertIs<AgentPayloadDecodeResult.Supported>(AgentSyncWireCodec.decodePayload(encodedToolCall, operationId)).payload)

        val resultPayload = toolCallPayload.copy(operation = toolCallPayload.operation.copy(
            agentEvent = ToolResultAppended(
                AgentToolResultSyncId(id(43)), AgentToolCallSyncId(id(40)), AgentThreadSyncId(id(3)),
                AgentTurnSyncId(id(41)), AgentToolResultStatusV3.SUCCESS, inputs,
                emptyList(),
            ),
        ))
        val encodedResult = AgentSyncWireCodec.encodePayload(resultPayload)
        assertEquals(resultPayload, assertIs<AgentPayloadDecodeResult.Supported>(AgentSyncWireCodec.decodePayload(encodedResult, operationId)).payload)
    }

    @Test fun `turn manifest rejects duplicate parents self-parenting and duplicate members at construction`() {
        val turnId = AgentTurnSyncId(id(13))
        val parentId = AgentTurnSyncId(id(10))
        val message = MessageMember(AgentMessageSyncId(id(14)))
        val duplicateParent = assertFailsWith<IllegalArgumentException> {
            TurnFinalized(turnId, AgentThreadSyncId(id(3)), listOf(parentId, parentId), listOf(message), AgentTurnOutcome.SUCCEEDED)
        }
        assertEquals("Turn parent IDs must be unique.", duplicateParent.message)
        assertFailsWith<IllegalArgumentException> {
            TurnFinalized(turnId, AgentThreadSyncId(id(3)), listOf(turnId), listOf(message), AgentTurnOutcome.SUCCEEDED)
        }
        assertFailsWith<IllegalArgumentException> {
            TurnFinalized(turnId, AgentThreadSyncId(id(3)), emptyList(), listOf(message, message), AgentTurnOutcome.SUCCEEDED)
        }
    }

    @Test fun `delete resolution DTO requires sorted unique participants and exact replacement semantics`() {
        val thread = AgentThreadSyncId(id(50))
        val sorted = listOf(MutationId(id(51)), MutationId(id(52)))
        assertFailsWith<IllegalArgumentException> {
            ThreadDeleteConflictResolved(thread, sorted.reversed(), AgentThreadDeleteResolution.KEEP_DELETION, null)
        }
        assertFailsWith<IllegalArgumentException> {
            ThreadDeleteConflictResolved(thread, listOf(sorted.first(), sorted.first()), AgentThreadDeleteResolution.KEEP_DELETION, null)
        }
        assertFailsWith<IllegalArgumentException> {
            ThreadDeleteConflictResolved(thread, sorted, AgentThreadDeleteResolution.KEEP_DELETION, AgentThreadSyncId(id(53)))
        }
        assertFailsWith<IllegalArgumentException> {
            ThreadDeleteConflictResolved(thread, sorted, AgentThreadDeleteResolution.COPY_CONTENT_TO_NEW_THREAD, null)
        }
        assertFailsWith<IllegalArgumentException> {
            ThreadDeleteConflictResolved(thread, sorted, AgentThreadDeleteResolution.COPY_CONTENT_TO_NEW_THREAD, thread)
        }
    }

    @Test fun `delete resolution has canonical KEEP and COPY JSON fixtures and decoder rejects malformed components`() {
        val keep = SyncPayloadV3(operation = AgentSyncOperation(
            MutationId(id(90)), AgentDvvSnapshot(emptyList(), AgentDot(agentReplica, 90)), AgentHlcSnapshot(90, 0, agentReplica),
            ThreadDeleteConflictResolved(AgentThreadSyncId(id(3)), listOf(MutationId(id(10)), MutationId(id(11))), AgentThreadDeleteResolution.KEEP_DELETION, null),
        ))
        val copy = keep.copy(operation = keep.operation.copy(
            operationId = MutationId(id(91)),
            agentDvv = AgentDvvSnapshot(emptyList(), AgentDot(agentReplica, 91)),
            hlc = AgentHlcSnapshot(91, 0, agentReplica),
            agentEvent = ThreadDeleteConflictResolved(AgentThreadSyncId(id(3)), listOf(MutationId(id(10)), MutationId(id(11))), AgentThreadDeleteResolution.COPY_CONTENT_TO_NEW_THREAD, AgentThreadSyncId(id(92))),
        ))
        assertEquals(fixture("thread-delete-conflict-resolved-keep.json"), AgentSyncWireCodec.encodePayload(keep))
        assertEquals(fixture("thread-delete-conflict-resolved-copy.json"), AgentSyncWireCodec.encodePayload(copy))
        assertEquals(keep, assertIs<AgentPayloadDecodeResult.Supported>(AgentSyncWireCodec.decodePayload(AgentSyncWireCodec.encodePayload(keep), keep.operation.operationId)).payload)
        val duplicateParticipants = AgentSyncWireCodec.encodePayload(keep).replace(
            "\"participantOperationIds\":[\"${id(10)}\",\"${id(11)}\"]",
            "\"participantOperationIds\":[\"${id(10)}\",\"${id(10)}\"]",
        )
        val unsortedParticipants = AgentSyncWireCodec.encodePayload(keep).replace(
            "\"participantOperationIds\":[\"${id(10)}\",\"${id(11)}\"]",
            "\"participantOperationIds\":[\"${id(11)}\",\"${id(10)}\"]",
        )
        assertIs<AgentPayloadDecodeResult.Invalid>(AgentSyncWireCodec.decodePayload(duplicateParticipants, keep.operation.operationId))
        assertIs<AgentPayloadDecodeResult.Invalid>(AgentSyncWireCodec.decodePayload(unsortedParticipants, keep.operation.operationId))
    }

    @Test fun `turn manifest decoder rejects duplicate parents self-parenting and duplicate members`() {
        val valid = validTurnPayload()
        val parent = "\"parentTurnIds\":[\"${id(10)}\"]"
        val duplicateParents = valid.replace(parent, "\"parentTurnIds\":[\"${id(10)}\",\"${id(10)}\"]")
        val selfParent = valid.replace(parent, "\"parentTurnIds\":[\"${id(13)}\"]")
        val member = "\"orderedMembers\":[{\"type\":\"MESSAGE\",\"id\":\"${id(14)}\"}]"
        val duplicateMembers = valid.replace(member, "\"orderedMembers\":[{\"type\":\"MESSAGE\",\"id\":\"${id(14)}\"},{\"type\":\"MESSAGE\",\"id\":\"${id(14)}\"}]")

        assertIs<AgentPayloadDecodeResult.Invalid>(AgentSyncWireCodec.decodePayload(duplicateParents, MutationId(id(11))))
        assertIs<AgentPayloadDecodeResult.Invalid>(AgentSyncWireCodec.decodePayload(selfParent, MutationId(id(11))))
        assertIs<AgentPayloadDecodeResult.Invalid>(AgentSyncWireCodec.decodePayload(duplicateMembers, MutationId(id(11))))
    }

    @Test fun `turn Action references must match its turn thread and source message`() {
        val threadId = AgentThreadSyncId(id(3))
        val turnId = AgentTurnSyncId(id(40))
        val sourceId = AgentMessageSyncId(id(41))
        val actionId = AgentActionSyncId(id(42))
        val callId = AgentToolCallSyncId(id(43))
        val resultId = AgentToolResultSyncId(id(44))
        val manifest = TurnFinalized(turnId, threadId, emptyList(), listOf(MessageMember(sourceId), ToolCallMember(callId), ToolResultMember(resultId), ActionMember(actionId)), AgentTurnOutcome.SUCCEEDED)
        val source = MessageAppended(sourceId, threadId, turnId, AgentMessageRoleV3.ASSISTANT, "Done", 99)
        val call = ToolCallFinalized(callId, threadId, turnId, sourceId, "task.read", kotlinx.serialization.json.buildJsonObject {}, FinalAgentToolCallStatus.COMPLETED)
        val result = ToolResultAppended(resultId, callId, threadId, turnId, AgentToolResultStatusV3.SUCCESS, kotlinx.serialization.json.JsonPrimitive("ok"), emptyList())
        val valid = ActionFinalized(actionId, turnId, threadId, sourceId, listOf(callId), listOf(resultId), emptyList(), FinalAgentActionStatus.SUCCEEDED)
        assertEquals(AgentTurnLinkValidation.Valid, AgentTurnLinkValidator.validate(manifest, mapOf(actionId to valid), mapOf(sourceId to source), mapOf(callId to call), mapOf(resultId to result)))

        val otherTurn = valid.copy(turnId = AgentTurnSyncId(id(43)))
        assertIs<AgentTurnLinkValidation.InvalidAssociation>(AgentTurnLinkValidator.validate(manifest, mapOf(actionId to otherTurn), mapOf(sourceId to source), mapOf(callId to call), mapOf(resultId to result)))
        val otherThread = valid.copy(threadId = AgentThreadSyncId(id(44)))
        assertIs<AgentTurnLinkValidation.InvalidAssociation>(AgentTurnLinkValidator.validate(manifest, mapOf(actionId to otherThread), mapOf(sourceId to source), mapOf(callId to call), mapOf(resultId to result)))
        val otherSourceTurn = source.copy(turnId = AgentTurnSyncId(id(43)))
        assertIs<AgentTurnLinkValidation.InvalidAssociation>(AgentTurnLinkValidator.validate(manifest, mapOf(actionId to valid), mapOf(sourceId to otherSourceTurn), mapOf(callId to call), mapOf(resultId to result)))
        val otherCallTurn = call.copy(turnId = AgentTurnSyncId(id(45)))
        assertIs<AgentTurnLinkValidation.InvalidAssociation>(AgentTurnLinkValidator.validate(manifest, mapOf(actionId to valid), mapOf(sourceId to source), mapOf(callId to otherCallTurn), mapOf(resultId to result)))
    }

    @Test fun `standalone audit Action cannot be included as a turn member`() {
        val action = ActionFinalized(AgentActionSyncId(id(42)), null, null, null, emptyList(), emptyList(), emptyList(), FinalAgentActionStatus.SUCCEEDED)
        val manifest = TurnFinalized(AgentTurnSyncId(id(40)), AgentThreadSyncId(id(3)), emptyList(), listOf(ActionMember(action.actionId)), AgentTurnOutcome.SUCCEEDED)
        assertIs<AgentTurnLinkValidation.InvalidAssociation>(AgentTurnLinkValidator.validate(manifest, mapOf(action.actionId to action), emptyMap()))
    }

    @Test fun `legacy D8 payload decoder marks unknown V3 while accepting independent V1 business operation`() {
        val v3 = AgentSyncWireCodec.encodePayload(SyncPayloadV3(operation = AgentSyncOperation(
            operationId, AgentDvvSnapshot(emptyList(), AgentDot(agentReplica, 1)), AgentHlcSnapshot(100, 0, agentReplica),
            ThreadCreated(AgentThreadSyncId(id(3)), "Planning", 90),
        )))
        val legacy = assertIs<PayloadDecodeResult.UnsupportedVersion>(SyncWireCodec.decodePayload(v3))
        assertEquals(3, legacy.actual)

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

    private fun validTurnPayload(): String = AgentSyncWireCodec.encodePayload(SyncPayloadV3(operation = AgentSyncOperation(
        MutationId(id(11)),
        AgentDvvSnapshot(emptyList(), AgentDot(agentReplica, 1)),
        AgentHlcSnapshot(100, 0, agentReplica),
        TurnFinalized(
            AgentTurnSyncId(id(13)), AgentThreadSyncId(id(3)), listOf(AgentTurnSyncId(id(10))),
            listOf(MessageMember(AgentMessageSyncId(id(14)))), AgentTurnOutcome.SUCCEEDED,
        ),
    )))

    private fun id(value: Int) = "00000000-0000-7000-8000-${value.toString().padStart(12, '0')}"
}
