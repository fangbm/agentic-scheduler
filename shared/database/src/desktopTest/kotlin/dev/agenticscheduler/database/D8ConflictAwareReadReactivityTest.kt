package dev.agenticscheduler.database

import dev.agenticscheduler.application.calendar.CalendarViewport
import dev.agenticscheduler.application.history.ActiveConflictAwareSourceFactQuery
import dev.agenticscheduler.application.history.ConflictAwareProjection
import dev.agenticscheduler.application.history.ConflictAwareSourceFactReadService
import dev.agenticscheduler.application.persistence.SyncReceiveRepository
import dev.agenticscheduler.database.repository.RoomAcademicRepository
import dev.agenticscheduler.database.repository.RoomEventRepository
import dev.agenticscheduler.database.repository.RoomPlanningProfileRepository
import dev.agenticscheduler.database.repository.RoomSyncReceiveRepository
import dev.agenticscheduler.database.repository.RoomTaskRepository
import dev.agenticscheduler.domain.event.Event
import dev.agenticscheduler.domain.id.EventId
import dev.agenticscheduler.domain.planning.Flexibility
import dev.agenticscheduler.domain.planning.PinState
import dev.agenticscheduler.domain.time.AllDayRange
import dev.agenticscheduler.sync.AllDayRangeImage
import dev.agenticscheduler.sync.DotSnapshot
import dev.agenticscheduler.sync.DvvSnapshot
import dev.agenticscheduler.sync.EntityKind
import dev.agenticscheduler.sync.EventImage
import dev.agenticscheduler.sync.EventPut
import dev.agenticscheduler.sync.EventTimeImage
import dev.agenticscheduler.sync.FlexibilityImage
import dev.agenticscheduler.sync.HlcSnapshot
import dev.agenticscheduler.sync.MutationId
import dev.agenticscheduler.sync.MutationOrigin
import dev.agenticscheduler.sync.PinStateImage
import dev.agenticscheduler.sync.SyncConflict
import dev.agenticscheduler.sync.SyncConflictEntityRef
import dev.agenticscheduler.sync.SyncConflictKind
import dev.agenticscheduler.sync.SyncConflictParticipant
import dev.agenticscheduler.sync.SyncConflictStatus
import dev.agenticscheduler.sync.SyncOperation
import dev.agenticscheduler.sync.SyncSpaceId
import dev.agenticscheduler.sync.commonCausalContextOf
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.withTimeout
import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class D8ConflictAwareReadReactivityTest {
    @Test
    fun `conflict-only Room write reprojects subscribed calendar and carries D8 marker`() {
        kotlinx.coroutines.runBlocking {
            val database = openInMemoryDesktopDatabase()
        val eventId = "00000000-0000-7000-8000-000000000001"
        val syncSpaceId = SyncSpaceId("personal-space")
        val events = RoomEventRepository(database)
        val receive: SyncReceiveRepository = RoomSyncReceiveRepository(database)
        val durable = Event(
            EventId(eventId),
            "Durable title",
            AllDayRange(LocalDate(2026, 1, 1), LocalDate(2026, 1, 2)),
            Flexibility.HARD,
            PinState.UNPINNED,
        )
        events.upsert(durable)
        val reads = ConflictAwareSourceFactReadService(
            events,
            RoomTaskRepository(database),
            RoomPlanningProfileRepository(database),
            RoomAcademicRepository(database),
            ActiveConflictAwareSourceFactQuery(ConflictAwareProjection(receive), syncSpaceId),
        )
        val emissions = Channel<dev.agenticscheduler.application.calendar.CalendarProjectionResult>(Channel.UNLIMITED)
        val subscription = async(start = CoroutineStart.UNDISPATCHED) {
            reads.observe(
                CalendarViewport(LocalDate(2026, 1, 1), LocalDate(2026, 1, 2), kotlinx.datetime.TimeZone.UTC),
            ).collect(emissions::send)
        }
        try {
            val initial = withTimeout(5_000) { emissions.receive() }
            assertEquals("Durable title", initial.items.single().title)
            assertTrue(initial.syncConflictRefs.isEmpty())

            receive.saveConflict(eventTitleConflict(eventId, syncSpaceId))

            val projected = withTimeout(5_000) { emissions.receive { it.syncConflictRefs.isNotEmpty() } }
            assertEquals("Provisional title", projected.items.single().title)
            assertEquals(emptyList(), projected.conflicts, "Calendar overlap conflicts remain a separate output.")
            val marker = projected.syncConflictRefs.single()
            assertEquals(EntityKind.EVENT, marker.entityKind)
            assertEquals(eventId, marker.entityId)
            assertEquals(listOf("sync-conflict-event-1"), marker.conflictIds)
            assertEquals("Durable title", events.get(EventId(eventId))?.title, "The conflict write must not mutate Active State.")
        } finally {
            subscription.cancel()
            emissions.close()
            database.close()
        }
        }
    }

    private fun eventTitleConflict(eventId: String, syncSpaceId: SyncSpaceId): SyncConflict {
        val provisional = EventImage(
            eventId,
            "Provisional title",
            EventTimeImage.AllDay(AllDayRangeImage("2026-01-01", "2026-01-02")),
            FlexibilityImage.HARD,
            PinStateImage.UNPINNED,
        )
        val other = provisional.copy(title = "Other candidate")
        val first = SyncOperation(
            "00000000-0000-7000-8000-000000000010",
            DvvSnapshot(emptyList(), DotSnapshot("replica-a", 1)),
            HlcSnapshot(1, 0, "replica-a"),
            MutationOrigin.User,
            listOf(EventPut(null, provisional)),
        )
        val second = SyncOperation(
            "00000000-0000-7000-8000-000000000020",
            DvvSnapshot(emptyList(), DotSnapshot("replica-z", 1)),
            HlcSnapshot(2, 0, "replica-z"),
            MutationOrigin.User,
            listOf(EventPut(null, other)),
        )
        val participants = listOf(first, second).sortedBy(SyncOperation::mutationId).map {
            SyncConflictParticipant(MutationId(it.mutationId), it.dvv, dev.agenticscheduler.sync.LocalJournalCodec.encode(it))
        }
        return SyncConflict(
            conflictId = "sync-conflict-event-1",
            syncSpaceId = syncSpaceId,
            entityRefs = listOf(SyncConflictEntityRef(EntityKind.EVENT, eventId, listOf("title"))),
            participants = participants,
            provisionalMutationId = participants.first().mutationId,
            kind = SyncConflictKind.SEMANTIC,
            commonCausalContext = commonCausalContextOf(participants),
            status = SyncConflictStatus.OPEN,
        )
    }
}

private suspend fun <T> Channel<T>.receive(predicate: (T) -> Boolean): T {
    while (true) {
        val value = receive()
        if (predicate(value)) return value
    }
}
