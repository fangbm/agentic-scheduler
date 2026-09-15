package dev.agenticscheduler.sync

import kotlin.test.Test
import kotlin.test.assertEquals

class D8SyncStateTest {
    @Test fun `common causal context is the canonical component wise participant minimum`() {
        val first = participant(1, DvvSnapshot(listOf(VersionComponent(id(2), 4), VersionComponent(id(3), 7)), DotSnapshot(id(1), 9)))
        val second = participant(4, DvvSnapshot(listOf(VersionComponent(id(2), 6), VersionComponent(id(3), 5)), DotSnapshot(id(1), 10)))
        assertEquals(
            CausalContextSnapshot(listOf(VersionComponent(id(1), 9), VersionComponent(id(2), 4), VersionComponent(id(3), 5))),
            commonCausalContextOf(listOf(first, second)),
        )
    }

    @Test fun `conflict requires the canonical provisional participant and common context`() {
        val first = participant(10, DvvSnapshot(emptyList(), DotSnapshot(id(11), 1)))
        val second = participant(12, DvvSnapshot(emptyList(), DotSnapshot(id(13), 1)))
        val participants = listOf(first, second)
        val conflict = SyncConflict(
            conflictId = id(14), syncSpaceId = SyncSpaceId("personal"),
            entityRefs = listOf(SyncConflictEntityRef(EntityKind.WORK_LOG, id(15), listOf("append"))),
            participants = participants, provisionalMutationId = first.mutationId,
            kind = SyncConflictKind.INTEGRITY, commonCausalContext = commonCausalContextOf(participants), status = SyncConflictStatus.OPEN,
        )
        assertEquals(SyncConflictKind.INTEGRITY, conflict.kind)
        assertEquals(CausalContextSnapshot(emptyList()), conflict.commonCausalContext)
    }

    private fun participant(number: Int, dvv: DvvSnapshot) = SyncConflictParticipant(MutationId(id(number)), dvv, "{}")
    private fun id(number: Int) = "00000000-0000-7000-8000-${number.toString().padStart(12, '0')}"
}
