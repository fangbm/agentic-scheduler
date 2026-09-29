package dev.agenticscheduler.sync

import kotlin.test.Test
import kotlin.test.assertEquals

class AgentSyncGoldenFixtureTest {
    @Test fun `checked in JSON fixtures are exact codec output`() {
        assertEquals(fixture("thread-created.json"), AgentSyncWireCodec.encodePayload(threadCreated()))
        assertEquals(fixture("turn-finalized.json"), AgentSyncWireCodec.encodePayload(turnFinalized()))
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

    private fun id(value: Int) = "00000000-0000-7000-8000-${value.toString().padStart(12, '0')}"
}
