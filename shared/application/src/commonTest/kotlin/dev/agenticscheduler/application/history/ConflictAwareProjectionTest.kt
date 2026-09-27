package dev.agenticscheduler.application.history

import dev.agenticscheduler.application.persistence.*
import dev.agenticscheduler.sync.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class ConflictAwareProjectionTest {
    @Test fun `provisional projection overlays only conflicted Event title without writes`() = kotlinx.coroutines.runBlocking {
        val localReplica = id(1); val remoteReplica = id(2); val eventId = id(3)
        val durable = EventImage(eventId, "Local", EventTimeImage.AllDay(AllDayRangeImage("2026-01-03", "2026-01-04")), FlexibilityImage.HARD, PinStateImage.UNPINNED)
        val provisional = durable.copy(title = "Remote", time = EventTimeImage.AllDay(AllDayRangeImage("2026-01-01", "2026-01-02")))
        val conflict = conflict(eventId, localReplica, remoteReplica, provisional)
        val receive = MemoryReceive(listOf(conflict))
        val result = assertIs<ConflictProjection.Projected>(ConflictAwareProjection(receive).project(
            SyncSpaceId("personal-space"), EntityKind.EVENT, eventId, EventPut(null, durable),
        ))
        val projected = assertIs<EventPut>(result.mutation)
        assertEquals("Remote", projected.after.title)
        assertEquals("2026-01-03", (projected.after.time as EventTimeImage.AllDay).dates.startDate)
        assertEquals(0, receive.writeCount)
    }

    @Test fun `write guard blocks only intersecting groups`() = kotlinx.coroutines.runBlocking {
        val localReplica = id(10); val remoteReplica = id(11); val eventId = id(12)
        val candidate = EventImage(eventId, "Remote", EventTimeImage.AllDay(AllDayRangeImage("2026-01-01", "2026-01-02")), FlexibilityImage.HARD, PinStateImage.UNPINNED)
        val guard = SyncConflictWriteGuard(MemoryReceive(listOf(conflict(eventId, localReplica, remoteReplica, candidate))), SyncSpaceId("personal-space"))
        val base = candidate.copy(title = "Local")
        assertEquals(listOf("title"), guard.blocks(listOf(EventPut(base, base.copy(title = "Edited")))).single().blockedGroups)
        assertEquals(emptyList(), guard.blocks(listOf(EventPut(base, base.copy(time = EventTimeImage.AllDay(AllDayRangeImage("2026-02-01", "2026-02-02")))))))
    }

    @Test fun `same conflict selects provisional value while retaining each replica's accepted groups`() = kotlinx.coroutines.runBlocking {
        val localReplica = id(30); val remoteReplica = id(31); val eventId = id(32)
        val provisional = EventImage(eventId, "Provisional", EventTimeImage.AllDay(AllDayRangeImage("2026-01-01", "2026-01-02")), FlexibilityImage.HARD, PinStateImage.UNPINNED)
        val projection = ConflictAwareProjection(MemoryReceive(listOf(conflict(eventId, localReplica, remoteReplica, provisional))))
        val first = EventImage(eventId, "First", EventTimeImage.AllDay(AllDayRangeImage("2026-02-01", "2026-02-02")), FlexibilityImage.HARD, PinStateImage.UNPINNED)
        val second = first.copy(time = EventTimeImage.AllDay(AllDayRangeImage("2026-03-01", "2026-03-02")))

        val firstResult = assertIs<EventPut>(assertIs<ConflictProjection.Projected>(projection.project(SyncSpaceId("personal-space"), EntityKind.EVENT, eventId, EventPut(null, first))).mutation)
        val secondResult = assertIs<EventPut>(assertIs<ConflictProjection.Projected>(projection.project(SyncSpaceId("personal-space"), EntityKind.EVENT, eventId, EventPut(null, second))).mutation)

        assertEquals("Provisional", firstResult.after.title)
        assertEquals("Provisional", secondResult.after.title)
        assertEquals(first.time, firstResult.after.time)
        assertEquals(second.time, secondResult.after.time)
    }

    @Test fun `missing durable entity is unprojectable rather than synthesized`() = kotlinx.coroutines.runBlocking {
        val localReplica = id(40); val remoteReplica = id(41); val eventId = id(42)
        val candidate = EventImage(eventId, "Remote", EventTimeImage.AllDay(AllDayRangeImage("2026-01-01", "2026-01-02")), FlexibilityImage.HARD, PinStateImage.UNPINNED)
        val result = ConflictAwareProjection(MemoryReceive(listOf(conflict(eventId, localReplica, remoteReplica, candidate)))).project(
            SyncSpaceId("personal-space"), EntityKind.EVENT, eventId, null,
        )
        assertIs<ConflictProjection.Unprojectable>(result)
        Unit
    }

    @Test fun `PlanningProfile mode conflict preserves accepted disjoint configuration groups`() = kotlinx.coroutines.runBlocking {
        val profileId = id(43)
        val candidateConfiguration = PlanningProfileConfigurationImage.Configured(
            "Asia/Shanghai", emptyList(), "PT30M", "PT1H", "PT2H", AllDayEventPolicyImage.NON_BLOCKING,
        )
        val durableConfiguration = candidateConfiguration.copy(
            timeZone = "UTC",
            weeklyAvailability = listOf(CanonicalAvailabilityWindowImage(DayOfWeekImage.MONDAY, "09:00", "12:00")),
        )
        val candidate = PlanningProfileImage(profileId, "Study", candidateConfiguration)
        val durable = candidate.copy(configuration = durableConfiguration)
        val local = SyncOperation(id(44), DvvSnapshot(emptyList(), DotSnapshot(id(45), 1)), HlcSnapshot(1, 0, id(45)), MutationOrigin.User, listOf(PlanningProfilePut(null, durable)))
        val remote = SyncOperation(id(42), DvvSnapshot(emptyList(), DotSnapshot(id(47), 1)), HlcSnapshot(2, 0, id(47)), MutationOrigin.User, listOf(PlanningProfilePut(null, candidate)))
        val participants = listOf(local, remote).sortedBy(SyncOperation::mutationId).map {
            SyncConflictParticipant(MutationId(it.mutationId), it.dvv, LocalJournalCodec.encode(it))
        }
        val conflict = SyncConflict(
            "profile-$profileId", SyncSpaceId("personal-space"),
            listOf(SyncConflictEntityRef(EntityKind.PLANNING_PROFILE, profileId, listOf("configuration.mode"))),
            participants, MutationId(remote.mutationId), SyncConflictKind.SEMANTIC,
            commonCausalContextOf(participants), SyncConflictStatus.OPEN,
        )

        val result = assertIs<ConflictProjection.Projected>(ConflictAwareProjection(MemoryReceive(listOf(conflict))).project(
            SyncSpaceId("personal-space"), EntityKind.PLANNING_PROFILE, profileId, PlanningProfilePut(null, durable),
        ))
        assertEquals(durableConfiguration, assertIs<PlanningProfilePut>(result.mutation).after.configuration)
    }

    @Test fun `PlanningProfile name conflict survives a later configuration mode change`() = kotlinx.coroutines.runBlocking {
        val profileId = id(62)
        val candidate = PlanningProfileImage(profileId, "Provisional", PlanningProfileConfigurationImage.Unconfigured)
        val configured = PlanningProfileConfigurationImage.Configured(
            "Asia/Shanghai", emptyList(), "PT30M", "PT1H", "PT2H", AllDayEventPolicyImage.NON_BLOCKING,
        )
        val durable = PlanningProfileImage(profileId, "Local", configured)
        val local = SyncOperation(id(61), DvvSnapshot(emptyList(), DotSnapshot(id(63), 1)), HlcSnapshot(1, 0, id(63)), MutationOrigin.User, listOf(PlanningProfilePut(null, durable)))
        val remote = SyncOperation(id(60), DvvSnapshot(emptyList(), DotSnapshot(id(64), 1)), HlcSnapshot(2, 0, id(64)), MutationOrigin.User, listOf(PlanningProfilePut(null, candidate)))
        val participants = listOf(local, remote).sortedBy(SyncOperation::mutationId).map {
            SyncConflictParticipant(MutationId(it.mutationId), it.dvv, LocalJournalCodec.encode(it))
        }
        val conflict = SyncConflict(
            "profile-name-$profileId", SyncSpaceId("personal-space"),
            listOf(SyncConflictEntityRef(EntityKind.PLANNING_PROFILE, profileId, listOf("name"))),
            participants, MutationId(remote.mutationId), SyncConflictKind.SEMANTIC,
            commonCausalContextOf(participants), SyncConflictStatus.OPEN,
        )

        val result = assertIs<ConflictProjection.Projected>(ConflictAwareProjection(MemoryReceive(listOf(conflict))).project(
            SyncSpaceId("personal-space"), EntityKind.PLANNING_PROFILE, profileId, PlanningProfilePut(null, durable),
        ))
        val projected = assertIs<PlanningProfilePut>(result.mutation).after
        assertEquals("Provisional", projected.name)
        assertEquals(configured, projected.configuration)
    }

    @Test fun `PlanningProfile name projection also preserves a later Unconfigured mode`() = kotlinx.coroutines.runBlocking {
        val profileId = id(74)
        val configured = PlanningProfileConfigurationImage.Configured(
            "Asia/Shanghai", emptyList(), "PT30M", "PT1H", "PT2H", AllDayEventPolicyImage.NON_BLOCKING,
        )
        val candidate = PlanningProfileImage(profileId, "Provisional", configured)
        val durable = PlanningProfileImage(profileId, "Local", PlanningProfileConfigurationImage.Unconfigured)
        val conflict = profileConflict(profileId, durable, candidate, listOf("name"), 70)

        val result = assertIs<ConflictProjection.Projected>(ConflictAwareProjection(MemoryReceive(listOf(conflict))).project(
            SyncSpaceId("personal-space"), EntityKind.PLANNING_PROFILE, profileId, PlanningProfilePut(null, durable),
        ))
        val projected = assertIs<PlanningProfilePut>(result.mutation).after
        assertEquals("Provisional", projected.name)
        assertEquals(PlanningProfileConfigurationImage.Unconfigured, projected.configuration)
    }

    @Test fun `PlanningProfile configured field without a compatible mode is unprojectable`() = kotlinx.coroutines.runBlocking {
        val profileId = id(84)
        val candidate = PlanningProfileImage(profileId, "Study", PlanningProfileConfigurationImage.Configured(
            "Asia/Shanghai", emptyList(), "PT30M", "PT1H", "PT2H", AllDayEventPolicyImage.NON_BLOCKING,
        ))
        val durable = PlanningProfileImage(profileId, "Study", PlanningProfileConfigurationImage.Unconfigured)
        val conflict = profileConflict(profileId, durable, candidate, listOf("configuration.timeZone"), 80)

        val result = ConflictAwareProjection(MemoryReceive(listOf(conflict))).project(
            SyncSpaceId("personal-space"), EntityKind.PLANNING_PROFILE, profileId, PlanningProfilePut(null, durable),
        )
        assertIs<ConflictProjection.Unprojectable>(result)
        Unit
    }

    @Test fun `FocusBlock put delete projection is deterministic from either durable branch`() = kotlinx.coroutines.runBlocking {
        val block = FocusBlockImage(id(50), id(51), ZonedTimeRangeImage("2026-01-01T09:00:00Z", "2026-01-01T10:00:00Z", "UTC"), FlexibilityImage.SOFT, PinStateImage.UNPINNED)
        val putWins = focusConflict(block, putMutationId = id(52), deleteMutationId = id(53))
        val deletedSide = assertIs<ConflictProjection.Projected>(ConflictAwareProjection(MemoryReceive(listOf(putWins))).project(
            SyncSpaceId("personal-space"), EntityKind.FOCUS_BLOCK, block.id, null,
        ))
        assertEquals(block, assertIs<FocusBlockPut>(deletedSide.mutation).after)

        val deleteWins = focusConflict(block, putMutationId = id(55), deleteMutationId = id(54))
        val putSide = assertIs<ConflictProjection.Projected>(ConflictAwareProjection(MemoryReceive(listOf(deleteWins))).project(
            SyncSpaceId("personal-space"), EntityKind.FOCUS_BLOCK, block.id, FocusBlockPut(null, block),
        ))
        assertEquals(null, putSide.mutation)
    }

    private fun conflict(eventId: String, localReplica: String, remoteReplica: String, provisional: EventImage): SyncConflict {
        val local = SyncOperation(id(20), DvvSnapshot(emptyList(), DotSnapshot(localReplica, 1)), HlcSnapshot(1, 0, localReplica), MutationOrigin.User, listOf(EventPut(null, provisional.copy(title = "Local"))))
        val remote = SyncOperation(id(19), DvvSnapshot(emptyList(), DotSnapshot(remoteReplica, 1)), HlcSnapshot(999, 0, remoteReplica), MutationOrigin.User, listOf(EventPut(null, provisional)))
        val participants = listOf(local, remote).sortedBy(SyncOperation::mutationId).map { SyncConflictParticipant(MutationId(it.mutationId), it.dvv, LocalJournalCodec.encode(it)) }
        return SyncConflict("conflict-$eventId", SyncSpaceId("personal-space"), listOf(SyncConflictEntityRef(EntityKind.EVENT, eventId, listOf("title"))), participants, MutationId(remote.mutationId), SyncConflictKind.SEMANTIC, commonCausalContextOf(participants), SyncConflictStatus.OPEN)
    }

    private fun focusConflict(block: FocusBlockImage, putMutationId: String, deleteMutationId: String): SyncConflict {
        val put = SyncOperation(putMutationId, DvvSnapshot(emptyList(), DotSnapshot(id(56), 1)), HlcSnapshot(10, 0, id(56)), MutationOrigin.User, listOf(FocusBlockPut(null, block)))
        val delete = SyncOperation(deleteMutationId, DvvSnapshot(emptyList(), DotSnapshot(id(57), 1)), HlcSnapshot(1, 0, id(57)), MutationOrigin.User, listOf(FocusBlockDelete(block)))
        val participants = listOf(put, delete).sortedBy(SyncOperation::mutationId).map { SyncConflictParticipant(MutationId(it.mutationId), it.dvv, LocalJournalCodec.encode(it)) }
        return SyncConflict("focus-${putMutationId}-${deleteMutationId}", SyncSpaceId("personal-space"), listOf(SyncConflictEntityRef(EntityKind.FOCUS_BLOCK, block.id, listOf("existence"))), participants, participants.first().mutationId, SyncConflictKind.SEMANTIC, commonCausalContextOf(participants), SyncConflictStatus.OPEN)
    }

    private fun profileConflict(profileId: String, durable: PlanningProfileImage, candidate: PlanningProfileImage, groups: List<String>, seed: Int): SyncConflict {
        val local = SyncOperation(id(seed + 1), DvvSnapshot(emptyList(), DotSnapshot(id(seed + 2), 1)), HlcSnapshot(1, 0, id(seed + 2)), MutationOrigin.User, listOf(PlanningProfilePut(null, durable)))
        val remote = SyncOperation(id(seed), DvvSnapshot(emptyList(), DotSnapshot(id(seed + 3), 1)), HlcSnapshot(2, 0, id(seed + 3)), MutationOrigin.User, listOf(PlanningProfilePut(null, candidate)))
        val participants = listOf(local, remote).sortedBy(SyncOperation::mutationId).map {
            SyncConflictParticipant(MutationId(it.mutationId), it.dvv, LocalJournalCodec.encode(it))
        }
        return SyncConflict(
            "profile-$profileId", SyncSpaceId("personal-space"),
            listOf(SyncConflictEntityRef(EntityKind.PLANNING_PROFILE, profileId, groups)),
            participants, MutationId(remote.mutationId), SyncConflictKind.SEMANTIC,
            commonCausalContextOf(participants), SyncConflictStatus.OPEN,
        )
    }

    private fun id(number: Int) = "00000000-0000-7000-8000-0000000000${number.toString().padStart(2, '0')}"
}

private class MemoryReceive(private val conflictsValue: List<SyncConflict>) : SyncReceiveRepository {
    var writeCount = 0
    override suspend fun serverCursor(syncSpaceId: SyncSpaceId) = 0L
    override suspend fun saveServerCursor(syncSpaceId: SyncSpaceId, cursor: Long) { writeCount++ }
    override suspend fun pending(syncSpaceId: SyncSpaceId, mutationId: String): PendingSyncReceive? = null
    override suspend fun pending(syncSpaceId: SyncSpaceId): List<PendingSyncReceive> = emptyList()
    override suspend fun savePending(value: PendingSyncReceive) { writeCount++ }
    override suspend fun removePending(syncSpaceId: SyncSpaceId, mutationId: String) { writeCount++ }
    override suspend fun handledDot(syncSpaceId: SyncSpaceId, replicaId: ReplicaId, counter: Long): HandledReceiveDot? = null
    override suspend fun handledDots(syncSpaceId: SyncSpaceId): List<HandledReceiveDot> = emptyList()
    override suspend fun saveHandledDot(value: HandledReceiveDot) { writeCount++ }
    override suspend fun quarantine(value: ProtocolQuarantine) { writeCount++ }
    override suspend fun quarantine(syncSpaceId: SyncSpaceId, mutationId: String): ProtocolQuarantine? = null
    override suspend fun saveConflict(value: SyncConflict) { writeCount++ }
    override suspend fun conflict(conflictId: String): SyncConflict? = conflictsValue.firstOrNull { it.conflictId == conflictId }
    override suspend fun conflicts(syncSpaceId: SyncSpaceId): List<SyncConflict> = conflictsValue
}
