package dev.agenticscheduler.sync

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

class AgentSyncGoldenFixtureTest {
    @Test fun `checked in JSON fixtures are exact codec output`() {
        assertEquals(fixture("thread-created.json"), AgentSyncWireCodec.encodePayload(threadCreated()))
        assertEquals(fixture("turn-finalized.json"), AgentSyncWireCodec.encodePayload(turnFinalized()))
        assertEquals(fixture("thread-title-set.json"), AgentSyncWireCodec.encodePayload(payload(21, 2, 110, 1, ThreadTitleSet(AgentThreadSyncId(id(3)), "Renamed"))))
        assertEquals(fixture("message-appended.json"), AgentSyncWireCodec.encodePayload(payload(22, 3, 120, 2, MessageAppended(AgentMessageSyncId(id(23)), AgentThreadSyncId(id(3)), AgentTurnSyncId(id(24)), AgentMessageRoleV3.USER, "你好", 119))))
        assertEquals(fixture("tool-call-finalized.json"), AgentSyncWireCodec.encodePayload(payload(25, 4, 130, 3, ToolCallFinalized(AgentToolCallSyncId(id(26)), AgentThreadSyncId(id(3)), AgentTurnSyncId(id(24)), AgentMessageSyncId(id(23)), "task.read", jsonObject("{\"taskId\":\"${id(31)}\"}"), FinalAgentToolCallStatus.COMPLETED))))
        assertEquals(fixture("tool-result-appended.json"), AgentSyncWireCodec.encodePayload(payload(27, 5, 140, 4, ToolResultAppended(AgentToolResultSyncId(id(28)), AgentToolCallSyncId(id(26)), AgentThreadSyncId(id(3)), AgentTurnSyncId(id(24)), AgentToolResultStatusV3.SUCCESS, Json.parseToJsonElement("{\"ok\":true}"), listOf(MutationId(id(30)))))))
        assertEquals(fixture("action-finalized-in-turn.json"), AgentSyncWireCodec.encodePayload(payload(35, 6, 150, 5, actionInTurn())))
        assertEquals(fixture("action-finalized-standalone.json"), AgentSyncWireCodec.encodePayload(payload(36, 7, 160, 6, ActionFinalized(AgentActionSyncId(id(37)), null, null, null, emptyList(), emptyList(), listOf(MutationId(id(30))), FinalAgentActionStatus.SUCCEEDED))))
        assertEquals(fixture("thread-deleted.json"), AgentSyncWireCodec.encodePayload(payload(38, 8, 170, 7, ThreadDeleted(AgentThreadSyncId(id(3))))))
        assertEquals(fixture("thread-delete-conflict-resolved-keep.json"), AgentSyncWireCodec.encodePayload(deleteResolutionPayload(90, AgentThreadDeleteResolution.KEEP_DELETION, null)))
        assertEquals(fixture("thread-delete-conflict-resolved-copy.json"), AgentSyncWireCodec.encodePayload(deleteResolutionPayload(91, AgentThreadDeleteResolution.COPY_CONTENT_TO_NEW_THREAD, AgentThreadSyncId(id(92)))))
    }

    @Test fun `checked in exceptional fixtures fail closed`() {
        assertEquals(AgentPayloadDecodeResult.UnsupportedEvent("FutureEvent"), AgentSyncWireCodec.decodePayload(fixture("unknown-event.json"), MutationId(id(1))))
        assertIs<AgentPayloadDecodeResult.Invalid>(AgentSyncWireCodec.decodePayload(fixture("unknown-manifest-member.json"), MutationId(id(11))))
        assertIs<AgentPayloadDecodeResult.Invalid>(AgentSyncWireCodec.decodePayload(fixture("prohibited-provider-metadata.json"), MutationId(id(1))))
    }

    private fun fixture(name: String): String =
        requireNotNull(javaClass.getResource("/agent-sync-v3/$name")) { "Missing V3 golden fixture $name." }
            .readText()
            .trimEnd('\r', '\n')

    private fun threadCreated() = SyncPayloadV3(operation = AgentSyncOperation(
        MutationId(id(1)),
        AgentDvvSnapshot(emptyList(), AgentDot(AgentReplicaId(id(2)), 1)),
        AgentHlcSnapshot(100, 0, AgentReplicaId(id(2))),
        ThreadCreated(AgentThreadSyncId(id(3)), "Planning", 90),
    ))

    private fun turnFinalized() = SyncPayloadV3(operation = AgentSyncOperation(
        MutationId(id(11)),
        AgentDvvSnapshot(listOf(AgentVersionComponent(AgentReplicaId(id(2)), 1)), AgentDot(AgentReplicaId(id(12)), 2)),
        AgentHlcSnapshot(250, 1, AgentReplicaId(id(12))),
        TurnFinalized(
            AgentTurnSyncId(id(13)), AgentThreadSyncId(id(3)), listOf(AgentTurnSyncId(id(10))),
            listOf(MessageMember(AgentMessageSyncId(id(14))), ToolCallMember(AgentToolCallSyncId(id(15))),
                ToolResultMember(AgentToolResultSyncId(id(16))), ActionMember(AgentActionSyncId(id(17)))),
            AgentTurnOutcome.FAILED,
        ),
    ))

    private fun payload(operation: Int, counter: Long, physical: Long, logical: Long, event: AgentSyncEvent) = SyncPayloadV3(operation = AgentSyncOperation(
        MutationId(id(operation)), AgentDvvSnapshot(emptyList(), AgentDot(AgentReplicaId(id(2)), counter)),
        AgentHlcSnapshot(physical, logical, AgentReplicaId(id(2))), event,
    ))

    private fun actionInTurn() = ActionFinalized(
        AgentActionSyncId(id(29)), AgentTurnSyncId(id(24)), AgentThreadSyncId(id(3)), AgentMessageSyncId(id(23)),
        listOf(AgentToolCallSyncId(id(26))), listOf(AgentToolResultSyncId(id(28))), listOf(MutationId(id(30))),
        FinalAgentActionStatus.SUCCEEDED,
    )

    private fun jsonObject(encoded: String) = Json.parseToJsonElement(encoded) as JsonObject

    private fun deleteResolutionPayload(operation: Int, resolution: AgentThreadDeleteResolution, replacement: AgentThreadSyncId?) = SyncPayloadV3(operation = AgentSyncOperation(
        MutationId(id(operation)), AgentDvvSnapshot(emptyList(), AgentDot(AgentReplicaId(id(2)), operation.toLong())),
        AgentHlcSnapshot(operation.toLong(), 0, AgentReplicaId(id(2))),
        ThreadDeleteConflictResolved(
            AgentThreadSyncId(id(3)), listOf(MutationId(id(10)), MutationId(id(11))), resolution, replacement,
        ),
    ))

    private fun id(value: Int) = "00000000-0000-7000-8000-${value.toString().padStart(12, '0')}"
}
