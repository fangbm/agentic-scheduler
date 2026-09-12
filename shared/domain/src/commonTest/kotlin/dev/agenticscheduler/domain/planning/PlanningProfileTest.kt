package dev.agenticscheduler.domain.planning

import dev.agenticscheduler.domain.id.PlanningProfileId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlinx.collections.immutable.toImmutableList
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlin.time.Duration.Companion.minutes

class PlanningProfileTest {
    @Test
    fun `PlanningProfile accepts nonblank name`() {
        val profile = PlanningProfile(PlanningProfileId("018f6e68-7d0c-7000-8000-000000000006"), "Study", PlanningProfileConfiguration.Unconfigured)
        assertEquals("Study", profile.name)
    }

    @Test
    fun `PlanningProfile rejects blank name`() {
        assertFailsWith<IllegalArgumentException> {
            PlanningProfile(PlanningProfileId("018f6e68-7d0c-7000-8000-000000000006"), "  ", PlanningProfileConfiguration.Unconfigured)
        }
    }

    @Test fun `configured profile canonicalizes windows and rejects overlaps`() {
        val configuration = PlanningProfileConfiguration.Configured(
            TimeZone.of("Asia/Shanghai"),
            listOf(
                WeeklyAvailabilityWindow(DayOfWeek.TUESDAY, LocalTime(9, 0), LocalTime(10, 0)),
                WeeklyAvailabilityWindow(DayOfWeek.MONDAY, LocalTime(9, 0), LocalTime(10, 0)),
            ).toImmutableList(),
            30.minutes, 45.minutes, 60.minutes, AllDayEventPolicy.NON_BLOCKING,
        )
        assertEquals(DayOfWeek.MONDAY, configuration.weeklyAvailability.first().dayOfWeek)
        assertFailsWith<IllegalArgumentException> {
            PlanningProfileConfiguration.Configured(TimeZone.of("UTC"), listOf(
                WeeklyAvailabilityWindow(DayOfWeek.MONDAY, LocalTime(9, 0), LocalTime(10, 0)),
                WeeklyAvailabilityWindow(DayOfWeek.MONDAY, LocalTime(9, 30), LocalTime(10, 30)),
            ).toImmutableList(), 30.minutes, 45.minutes, 60.minutes, AllDayEventPolicy.NON_BLOCKING)
        }
    }
}
