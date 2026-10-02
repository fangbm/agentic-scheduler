package dev.agenticscheduler.sync

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class AgentSyncMergeProjectionTest {
    private val thread = AgentThreadSyncId(id(1))

    @Test fun `concurrent delete and append form a conflict without timestamp winner`() {
        val created = operation(10, 1, 0, emptyList(), ThreadCreated(thread, "base", 1))
        val deleted = operation(11, 2, 0, listOf(1 to 0L), ThreadDeleted(thread), physical = 900)
        val appended = operation(12, 3, 0, listOf(1 to 0L), MessageAppended(
            AgentMessageSyncId(id(13)), thread, AgentTurnSyncId(id(14)), AgentMessageRoleV3.USER, "kept candidate", 2,
        ), physical = 1)

        val projection = AgentSyncMergeProjection.project(thread, listOf(created, deleted, appended), emptySet())

        assertTrue(projection.tombstoned)
        assertTrue(projection.turns.isEmpty())
        assertFalse(projection.providerContinuationAllowed)
        val conflict = projection.conflicts.single { it.kind == AgentSemanticConflictKind.THREAD_DELETE_APPEND }
        assertEquals(listOf(deleted.operationId, appended.operationId).sortedBy { it.value }, conflict.participantOperationIds)
        assertEquals(AgentSemanticConflictState.OPEN, conflict.state)
    }

    @Test fun `concurrent delete and finalized turn are included in the conflict component`() {
        val deleted = operation(40, 2, 0, emptyList(), ThreadDeleted(thread), physical = 100)
        val turn = operation(41, 3, 0, emptyList(), TurnFinalized(
            AgentTurnSyncId(id(42)), thread, emptyList(), emptyList(), AgentTurnOutcome.SUCCEEDED,
        ), physical = 1)

        val projection = AgentSyncMergeProjection.project(thread, listOf(deleted, turn), emptySet())

        assertEquals(listOf(deleted.operationId, turn.operationId).sortedBy { it.value },
            projection.conflicts.single { it.kind == AgentSemanticConflictKind.THREAD_DELETE_APPEND }.participantOperationIds)
    }

    @Test fun `resolution must name and causally observe the complete component`() {
        val deleted = operation(11, 2, 0, emptyList(), ThreadDeleted(thread))
        val appended = operation(12, 3, 0, emptyList(), MessageAppended(
            AgentMessageSyncId(id(13)), thread, AgentTurnSyncId(id(14)), AgentMessageRoleV3.USER, "candidate", 2,
        ))
        val participants = listOf(deleted.operationId, appended.operationId).sortedBy { it.value }
        val valid = operation(15, 4, 0, listOf(2 to 0L, 3 to 0L), ThreadDeleteConflictResolved(
            thread, participants, AgentThreadDeleteResolution.KEEP_DELETION, null,
        ))
        val omitted = valid.copy(agentEvent = (valid.agentEvent as ThreadDeleteConflictResolved).copy(participantOperationIds = listOf(deleted.operationId)))
        val unobserved = operation(16, 5, 0, listOf(2 to 0L), (valid.agentEvent as ThreadDeleteConflictResolved).copy(
            participantOperationIds = participants,
        ))

        assertEquals(AgentDeleteResolutionValidation.Valid, AgentSyncMergeProjection.validateDeleteResolution(valid, listOf(deleted, appended)))
        assertIs<AgentDeleteResolutionValidation.Invalid>(AgentSyncMergeProjection.validateDeleteResolution(omitted, listOf(deleted, appended)))
        assertIs<AgentDeleteResolutionValidation.Invalid>(AgentSyncMergeProjection.validateDeleteResolution(unobserved, listOf(deleted, appended)))
    }

    @Test fun `concurrent different resolutions remain an explicit semantic conflict`() {
        val deleted = operation(11, 2, 0, emptyList(), ThreadDeleted(thread))
        val appended = operation(12, 3, 0, emptyList(), MessageAppended(
            AgentMessageSyncId(id(13)), thread, AgentTurnSyncId(id(14)), AgentMessageRoleV3.USER, "candidate", 2,
        ))
        val participants = listOf(deleted.operationId, appended.operationId).sortedBy { it.value }
        val keep = operation(15, 4, 0, listOf(2 to 0L, 3 to 0L), ThreadDeleteConflictResolved(
            thread, participants, AgentThreadDeleteResolution.KEEP_DELETION, null,
        ))
        val copy = operation(16, 5, 0, listOf(2 to 0L, 3 to 0L), ThreadDeleteConflictResolved(
            thread, participants, AgentThreadDeleteResolution.COPY_CONTENT_TO_NEW_THREAD, AgentThreadSyncId(id(17)),
        ), physical = 99)

        val projection = AgentSyncMergeProjection.project(thread, listOf(deleted, appended, keep, copy), emptySet())

        assertTrue(projection.tombstoned)
        assertTrue(projection.turns.isEmpty())
        assertEquals(AgentSemanticConflictState.OPEN, projection.conflicts.single { it.kind == AgentSemanticConflictKind.DELETE_RESOLUTION }.state)
    }

    @Test fun `same concurrent resolution decision converges without timestamp selection`() {
        val deleted = operation(11, 2, 0, emptyList(), ThreadDeleted(thread))
        val appended = operation(12, 3, 0, emptyList(), MessageAppended(
            AgentMessageSyncId(id(13)), thread, AgentTurnSyncId(id(14)), AgentMessageRoleV3.USER, "candidate", 2,
        ))
        val participants = listOf(deleted.operationId, appended.operationId).sortedBy { it.value }
        val replacement = AgentThreadSyncId(id(17))
        val first = operation(18, 4, 0, listOf(2 to 0L, 3 to 0L), ThreadDeleteConflictResolved(
            thread, participants, AgentThreadDeleteResolution.COPY_CONTENT_TO_NEW_THREAD, replacement,
        ), physical = 100)
        val second = operation(19, 5, 0, listOf(2 to 0L, 3 to 0L), ThreadDeleteConflictResolved(
            thread, participants, AgentThreadDeleteResolution.COPY_CONTENT_TO_NEW_THREAD, replacement,
        ), physical = 1)

        val projection = AgentSyncMergeProjection.project(thread, listOf(deleted, appended, first, second), emptySet())

        assertTrue(projection.tombstoned)
        assertEquals(AgentSemanticConflictState.RESOLVED, projection.conflicts.single { it.kind == AgentSemanticConflictKind.THREAD_DELETE_APPEND }.state)
        assertFalse(projection.conflicts.any { it.kind == AgentSemanticConflictKind.DELETE_RESOLUTION })
    }

    @Test fun `title concurrency is exposed without LWW`() {
        val left = operation(20, 2, 0, emptyList(), ThreadTitleSet(thread, "left"), physical = 100)
        val right = operation(21, 3, 0, emptyList(), ThreadTitleSet(thread, "right"), physical = 1)

        val projection = AgentSyncMergeProjection.project(thread, listOf(left, right), emptySet())

        assertIs<AgentThreadTitleProjection.Conflict>(projection.title)
        assertTrue(projection.conflicts.any { it.kind == AgentSemanticConflictKind.THREAD_TITLE && it.state == AgentSemanticConflictState.OPEN })
        assertFalse(projection.providerContinuationAllowed)
    }

    @Test fun `complete sibling turns project as an explicit fork and block Provider continuation`() {
        val parent = AgentTurnSyncId(id(30))
        val leftTurn = AgentTurnSyncId(id(31))
        val rightTurn = AgentTurnSyncId(id(32))
        val parentOperation = operation(33, 2, 0, emptyList(), TurnFinalized(parent, thread, emptyList(), emptyList(), AgentTurnOutcome.SUCCEEDED))
        val left = operation(34, 2, 1, listOf(2 to 0L), TurnFinalized(leftTurn, thread, listOf(parent), emptyList(), AgentTurnOutcome.SUCCEEDED))
        val right = operation(35, 3, 0, listOf(2 to 0L), TurnFinalized(rightTurn, thread, listOf(parent), emptyList(), AgentTurnOutcome.SUCCEEDED))

        val projection = AgentSyncMergeProjection.project(thread, listOf(parentOperation, left, right), setOf(parent, leftTurn, rightTurn))

        assertEquals(listOf(parent, leftTurn, rightTurn), projection.turns.map { it.manifest.turnId })
        assertEquals(AgentSemanticConflictState.OPEN, projection.conflicts.single { it.kind == AgentSemanticConflictKind.CONCURRENT_TURN_FORK }.state)
        assertFalse(projection.providerContinuationAllowed)
    }

    private fun operation(
        op: Int,
        replica: Int,
        counter: Long,
        context: List<Pair<Int, Long>>,
        event: AgentSyncEvent,
        physical: Long = op.toLong(),
    ): AgentSyncOperation {
        val author = replica(replica)
        val versions = context.map { (replicaNumber, seen) -> AgentVersionComponent(replica(replicaNumber), seen) }
            .sortedBy { it.replicaId.value }
        return AgentSyncOperation(
            MutationId(id(op)), AgentDvvSnapshot(versions, AgentDot(author, counter)),
            AgentHlcSnapshot(physical, 0, author), event,
        )
    }

    private fun id(number: Int) = "00000000-0000-7000-8000-${number.toString().padStart(12, '0')}"
    private fun replica(number: Int) = AgentReplicaId(id(100 + number))
}
