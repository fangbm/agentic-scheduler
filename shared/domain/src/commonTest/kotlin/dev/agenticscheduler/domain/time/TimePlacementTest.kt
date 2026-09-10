package dev.agenticscheduler.domain.time

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.assertFailsWith
import kotlin.time.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone

class TimePlacementTest {
    private val start = Instant.parse("2026-09-10T10:00:00Z")
    private val end = Instant.parse("2026-09-10T11:00:00Z")

    @Test
    fun `ZonedTimeRange validates and uses half-open overlap`() {
        val range = ZonedTimeRange(start, end, TimeZone.UTC)
        assertTrue(range.overlaps(ZonedTimeRange(Instant.parse("2026-09-10T10:30:00Z"), Instant.parse("2026-09-10T11:30:00Z"), TimeZone.UTC)))
        assertFalse(range.overlaps(ZonedTimeRange(end, Instant.parse("2026-09-10T12:00:00Z"), TimeZone.UTC)))
        assertFailsWith<IllegalArgumentException> { ZonedTimeRange(start, start, TimeZone.UTC) }
        assertFailsWith<IllegalArgumentException> { ZonedTimeRange(end, start, TimeZone.UTC) }
    }

    @Test
    fun `ZonedTimeRange preserves instant occupancy across display zones`() {
        val utc = ZonedTimeRange(start, end, TimeZone.UTC)
        val shanghai = ZonedTimeRange(start, end, TimeZone.of("Asia/Shanghai"))
        assertTrue(utc.overlaps(shanghai))
        assertTrue(shanghai.overlaps(utc))
    }

    @Test
    fun `AllDayRange validates and uses half-open dates`() {
        val oneDay = AllDayRange(LocalDate(2026, 9, 10), LocalDate(2026, 9, 11))
        val multiDay = AllDayRange(LocalDate(2026, 9, 10), LocalDate(2026, 9, 13))
        assertTrue(oneDay.overlaps(multiDay))
        assertFalse(oneDay.overlaps(AllDayRange(LocalDate(2026, 9, 11), LocalDate(2026, 9, 12))))
        assertFailsWith<IllegalArgumentException> { AllDayRange(LocalDate(2026, 9, 10), LocalDate(2026, 9, 10)) }
        assertFailsWith<IllegalArgumentException> { AllDayRange(LocalDate(2026, 9, 11), LocalDate(2026, 9, 10)) }
    }

    @Test
    fun `FloatingTimeRange validates without a timezone`() {
        val range = FloatingTimeRange(LocalDateTime(2026, 9, 10, 10, 0), LocalDateTime(2026, 9, 10, 11, 0))
        assertFalse(range.overlaps(FloatingTimeRange(LocalDateTime(2026, 9, 10, 11, 0), LocalDateTime(2026, 9, 10, 12, 0))))
        assertFailsWith<IllegalArgumentException> { FloatingTimeRange(LocalDateTime(2026, 9, 10, 10, 0), LocalDateTime(2026, 9, 10, 10, 0)) }
        assertFailsWith<IllegalArgumentException> { FloatingTimeRange(LocalDateTime(2026, 9, 10, 11, 0), LocalDateTime(2026, 9, 10, 10, 0)) }
    }
}
