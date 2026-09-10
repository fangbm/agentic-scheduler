package dev.agenticscheduler.domain.event

import dev.agenticscheduler.domain.id.EventId
import dev.agenticscheduler.domain.planning.Flexibility
import dev.agenticscheduler.domain.planning.PinState
import dev.agenticscheduler.domain.time.AllDayRange
import dev.agenticscheduler.domain.time.FloatingTimeRange
import dev.agenticscheduler.domain.time.TimePlacement
import dev.agenticscheduler.domain.time.ZonedTimeRange
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.time.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone

class EventTest {
    @Test
    fun `Event accepts every TimePlacement kind`() {
        val placements: List<TimePlacement> = listOf(
            ZonedTimeRange(Instant.parse("2026-09-10T10:00:00Z"), Instant.parse("2026-09-10T11:00:00Z"), TimeZone.UTC),
            AllDayRange(LocalDate(2026, 9, 10), LocalDate(2026, 9, 11)),
            FloatingTimeRange(LocalDateTime(2026, 9, 10, 10, 0), LocalDateTime(2026, 9, 10, 11, 0)),
        )
        placements.forEach { placement ->
            val event = Event(EventId("018f6e68-7d0c-7000-8000-000000000001"), "Scheduled", placement, Flexibility.HARD, PinState.PINNED)
            assertEquals(placement, event.time)
        }
    }

    @Test
    fun `Event preserves flexibility and pin state independently`() {
        Flexibility.entries.forEach { flexibility ->
            PinState.entries.forEach { pinState ->
                val event = Event(
                    id = EventId("018f6e68-7d0c-7000-8000-000000000001"),
                    title = "Scheduled",
                    time = AllDayRange(LocalDate(2026, 9, 10), LocalDate(2026, 9, 11)),
                    flexibility = flexibility,
                    pinState = pinState,
                )
                assertEquals(flexibility, event.flexibility)
                assertEquals(pinState, event.pinState)
            }
        }
    }

    @Test
    fun `Event rejects blank title`() {
        assertFailsWith<IllegalArgumentException> {
            Event(
                EventId("018f6e68-7d0c-7000-8000-000000000001"),
                "   ",
                AllDayRange(LocalDate(2026, 9, 10), LocalDate(2026, 9, 11)),
                Flexibility.SOFT,
                PinState.UNPINNED,
            )
        }
    }
}
