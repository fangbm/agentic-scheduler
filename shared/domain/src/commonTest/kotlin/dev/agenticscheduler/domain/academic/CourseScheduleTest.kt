package dev.agenticscheduler.domain.academic

import dev.agenticscheduler.domain.id.CourseId
import dev.agenticscheduler.domain.id.CourseOccurrenceExceptionId
import dev.agenticscheduler.domain.id.CourseScheduleRuleId
import dev.agenticscheduler.domain.id.PeriodTemplateId
import dev.agenticscheduler.domain.id.SemesterId
import dev.agenticscheduler.domain.time.ZonedTimeRange
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.collections.immutable.toImmutableList
import kotlin.time.Instant

class CourseScheduleTest {
    @Test
    fun `teaching weeks are canonical explicit values`() {
        val canonical = TeachingWeekSet.of(listOf(AcademicWeekNumber(3), AcademicWeekNumber(1), AcademicWeekNumber(3)))
        assertEquals(listOf(1, 3), canonical.weeks.map { it.value })
        assertEquals(canonical, TeachingWeekSet.of(listOf(AcademicWeekNumber(1), AcademicWeekNumber(3))))
        assertEquals(listOf(1), TeachingWeekSet.of(listOf(AcademicWeekNumber(1))).weeks.map { it.value })
        assertEquals(listOf(1, 3, 5), TeachingWeekSet.of(listOf(AcademicWeekNumber(5), AcademicWeekNumber(1), AcademicWeekNumber(3))).weeks.map { it.value })
        assertFailsWith<IllegalArgumentException> { TeachingWeekSet.of(emptyList()) }
    }

    @Test
    fun `period templates and time specifications enforce explicit ordering`() {
        val first = AcademicPeriod(AcademicPeriodNumber(1), LocalTime(8, 0), LocalTime(8, 45))
        val second = AcademicPeriod(AcademicPeriodNumber(3), LocalTime(9, 0), LocalTime(9, 45))
        PeriodTemplate(PeriodTemplateId(id(20)), "Regular", listOf(first, second).toImmutableList())
        PeriodTemplate(PeriodTemplateId(id(20)), "Adjacent", listOf(first, AcademicPeriod(AcademicPeriodNumber(2), LocalTime(8, 45), LocalTime(9, 30))).toImmutableList())
        assertFailsWith<IllegalArgumentException> { AcademicPeriod(AcademicPeriodNumber(1), LocalTime(8, 0), LocalTime(8, 0)) }
        assertFailsWith<IllegalArgumentException> {
            PeriodTemplate(PeriodTemplateId(id(21)), "x", listOf(second, first).toImmutableList())
        }
        assertFailsWith<IllegalArgumentException> {
            PeriodTemplate(
                PeriodTemplateId(id(22)),
                "x",
                listOf(first, AcademicPeriod(AcademicPeriodNumber(2), LocalTime(8, 30), LocalTime(9, 0))).toImmutableList(),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            PeriodTemplate(PeriodTemplateId(id(23)), "x", listOf(first, AcademicPeriod(AcademicPeriodNumber(2), LocalTime(7, 0), LocalTime(7, 45))).toImmutableList())
        }
        CourseTimeSpec.ClockTime(LocalTime(8, 0), LocalTime(9, 0))
        CourseTimeSpec.PeriodBased(PeriodTemplateId(id(20)), AcademicPeriodNumber(1), AcademicPeriodNumber(1))
        CourseTimeSpec.PeriodBased(PeriodTemplateId(id(20)), AcademicPeriodNumber(1), AcademicPeriodNumber(3))
        assertFailsWith<IllegalArgumentException> { CourseTimeSpec.ClockTime(LocalTime(8, 0), LocalTime(8, 0)) }
        assertFailsWith<IllegalArgumentException> { CourseTimeSpec.ClockTime(LocalTime(9, 0), LocalTime(8, 0)) }
        assertFailsWith<IllegalArgumentException> { AcademicPeriodNumber(0) }
        assertFailsWith<IllegalArgumentException> { AcademicPeriod(AcademicPeriodNumber(1), LocalTime(9, 0), LocalTime(8, 0)) }
        assertFailsWith<IllegalArgumentException> {
            CourseTimeSpec.PeriodBased(PeriodTemplateId(id(20)), AcademicPeriodNumber(3), AcademicPeriodNumber(1))
        }
    }

    @Test
    fun `course rule key and occurrence exception retain frozen semantics`() {
        val course = Course(CourseId(id(30)), SemesterId(id(31)), "Algorithms", null)
        assertFailsWith<IllegalArgumentException> { course.copy(name = " ") }
        assertFailsWith<IllegalArgumentException> { course.copy(code = " ") }
        val rule = CourseScheduleRule(
            CourseScheduleRuleId(id(32)),
            course.id,
            DayOfWeek.MONDAY,
            TeachingWeekSet.of(listOf(AcademicWeekNumber(1))),
            CourseTimeSpec.ClockTime(LocalTime(8, 0), LocalTime(9, 0)),
            null,
        )
        val key = CourseOccurrenceKey(rule.id, AcademicWeekNumber(1))
        assertEquals(key, CourseOccurrenceKey(rule.id, AcademicWeekNumber(1)))
        assertFailsWith<IllegalArgumentException> { rule.copy(room = " ") }
        assertFailsWith<IllegalArgumentException> { RoomOverride.Set(" ") }
        assertFailsWith<IllegalArgumentException> {
            CourseOccurrenceException(
                CourseOccurrenceExceptionId(id(33)), key, CourseOccurrenceDisposition.CANCELLED,
                ZonedTimeRange(Instant.parse("2026-09-01T08:00:00Z"), Instant.parse("2026-09-01T09:00:00Z"), TimeZone.UTC),
                RoomOverride.Unchanged,
            )
        }
        assertFailsWith<IllegalArgumentException> {
            CourseOccurrenceException(CourseOccurrenceExceptionId(id(35)), key, CourseOccurrenceDisposition.CANCELLED, null, RoomOverride.Clear)
        }
        val active = CourseOccurrenceException(
            CourseOccurrenceExceptionId(id(34)), key, CourseOccurrenceDisposition.ACTIVE, null, RoomOverride.Clear,
        )
        assertEquals(RoomOverride.Clear, active.roomOverride)
        assertIs<RoomOverride.Set>(RoomOverride.Set("A101"))
    }

    @Test
    fun `period template owns an immutable snapshot of periods`() {
        val suppliedPeriods = mutableListOf(AcademicPeriod(AcademicPeriodNumber(1), LocalTime(8, 0), LocalTime(9, 0)))
        val template = PeriodTemplate(PeriodTemplateId(id(36)), "Regular", suppliedPeriods.toImmutableList())
        suppliedPeriods.clear()
        assertEquals(listOf(1), template.periods.map { it.number.value })
        assertEquals(template, template.copy())
        assertEquals(template.hashCode(), template.copy().hashCode())
        assertEquals(template.toString(), template.copy().toString())
        assertFailsWith<IllegalArgumentException> { template.copy(periods = emptyList<AcademicPeriod>().toImmutableList()) }
    }
}
