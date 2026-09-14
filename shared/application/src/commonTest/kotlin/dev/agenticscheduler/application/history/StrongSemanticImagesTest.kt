package dev.agenticscheduler.application.history

import dev.agenticscheduler.domain.academic.*
import dev.agenticscheduler.domain.id.*
import dev.agenticscheduler.domain.planning.*
import dev.agenticscheduler.domain.task.*
import dev.agenticscheduler.domain.time.AllDayRange
import dev.agenticscheduler.domain.time.ZonedTimeRange
import kotlinx.collections.immutable.toImmutableList
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant

class StrongSemanticImagesTest {
    @Test fun `every synchronized source fact round trips through its typed semantic image`() {
        val zone = TimeZone.of("UTC")
        val year = AcademicYear(AcademicYearId(id(1)), "2026", date("2026-01-01"), date("2027-01-01"))
        val semester = Semester(SemesterId(id(2)), year.id, "Spring", date("2026-01-01"), date("2026-03-01"), zone, listOf(AcademicWeek(AcademicWeekNumber(1), date("2026-01-01"), date("2026-01-08")), AcademicWeek(AcademicWeekNumber(2), date("2026-01-08"), date("2026-01-15"))).toImmutableList())
        val course = Course(CourseId(id(3)), semester.id, "Math", "M101")
        val periods = PeriodTemplate(PeriodTemplateId(id(4)), "Day", listOf(AcademicPeriod(AcademicPeriodNumber(1), time("09:00"), time("10:00")), AcademicPeriod(AcademicPeriodNumber(2), time("10:00"), time("11:00"))).toImmutableList())
        val rule = CourseScheduleRule(CourseScheduleRuleId(id(5)), course.id, DayOfWeek.MONDAY, TeachingWeekSet.of(listOf(AcademicWeekNumber(2), AcademicWeekNumber(1), AcademicWeekNumber(2))), CourseTimeSpec.PeriodBased(periods.id, AcademicPeriodNumber(1), AcademicPeriodNumber(2)), "A101")
        val occurrence = CourseOccurrenceException(CourseOccurrenceExceptionId(id(6)), CourseOccurrenceKey(rule.id, AcademicWeekNumber(1)), CourseOccurrenceDisposition.ACTIVE, range("2026-01-05T09:00:00Z", "2026-01-05T10:00:00Z", zone), RoomOverride.Set("B201"))
        val holiday = AcademicHoliday(AcademicHolidayId(id(7)), semester.id, "Break", AllDayRange(date("2026-01-20"), date("2026-01-21")), AcademicHolidayTeachingEffect.SUSPEND_TEACHING)
        val task = Task(TaskId(id(8)), "Read", TaskStatus.OPEN, TaskPriority.NORMAL, TaskEffort(2.hours, 0.hours, 2.hours), TaskDeadline(Deadline.DateOnly(date("2026-01-31")), DeadlinePolicy.NORMAL, OverflowPolicy.ASK))
        val event = dev.agenticscheduler.domain.event.Event(EventId(id(9)), "Meeting", AllDayRange(date("2026-01-02"), date("2026-01-03")), Flexibility.HARD, PinState.UNPINNED)
        val focus = FocusBlock(FocusBlockId(id(10)), task.id, range("2026-01-03T09:00:00Z", "2026-01-03T10:00:00Z", zone), Flexibility.SOFT, PinState.PINNED)
        val profile = PlanningProfile(PlanningProfileId(id(11)), "Study", PlanningProfileConfiguration.Configured(zone, listOf(WeeklyAvailabilityWindow(DayOfWeek.MONDAY, time("09:00"), time("12:00"))).toImmutableList(), 1.hours, 2.hours, 3.hours, AllDayEventPolicy.NON_BLOCKING))
        val log = WorkLog(WorkLogId(id(12)), task.id, range("2026-01-04T09:00:00Z", "2026-01-04T10:00:00Z", zone))
        val dependency = TaskDependency(TaskDependencyId(id(13)), task.id, TaskId(id(14)))
        val exam = Exam(ExamId(id(15)), semester.id, course.id, "Final", ExamSchedule.Exact(range("2026-02-01T09:00:00Z", "2026-02-01T10:00:00Z", zone)))

        assertEquals(event, event.toSemanticImage().toDomain()); assertEquals(task, task.toSemanticImage().toDomain())
        assertEquals(profile, profile.toSemanticImage().toDomain()); assertEquals(focus, focus.toSemanticImage().toDomain())
        assertEquals(log, log.toSemanticImage().toDomain()); assertEquals(dependency, dependency.toSemanticImage().toDomain())
        assertEquals(year, year.toSemanticImage().toDomain()); assertEquals(semester, semester.toSemanticImage().toDomain())
        assertEquals(course, course.toSemanticImage().toDomain()); assertEquals(periods, periods.toSemanticImage().toDomain())
        assertEquals(holiday, holiday.toSemanticImage().toDomain()); assertEquals(rule, rule.toSemanticImage().toDomain())
        assertEquals(occurrence, occurrence.toSemanticImage().toDomain()); assertEquals(exam, exam.toSemanticImage().toDomain())
    }

    @Test fun `sealed semantic variants retain their complete state`() {
        val zone = TimeZone.of("UTC")
        val semesterId = SemesterId(id(20))
        listOf(ExamSchedule.Unscheduled, ExamSchedule.DateOnly(date("2026-01-01")), ExamSchedule.Exact(range("2026-01-01T09:00:00Z", "2026-01-01T10:00:00Z", zone))).forEachIndexed { index, schedule ->
            val exam = Exam(ExamId(id(21 + index)), semesterId, null, "Exam", schedule)
            assertEquals(exam, exam.toSemanticImage().toDomain())
        }
        val cancelled = CourseOccurrenceException(CourseOccurrenceExceptionId(id(24)), CourseOccurrenceKey(CourseScheduleRuleId(id(25)), AcademicWeekNumber(1)), CourseOccurrenceDisposition.CANCELLED, null, RoomOverride.Unchanged)
        assertEquals(cancelled, cancelled.toSemanticImage().toDomain())
    }

    @Test fun `non canonical aggregates and invalid cancelled occurrences are rejected`() {
        assertFailsWith<IllegalArgumentException> {
            dev.agenticscheduler.sync.SemesterImage(id(30), id(31), "S", "2026-01-01", "2026-03-01", "UTC", listOf(dev.agenticscheduler.sync.AcademicWeekImage(2, "2026-01-08", "2026-01-15"), dev.agenticscheduler.sync.AcademicWeekImage(1, "2026-01-01", "2026-01-08")))
        }
        assertFailsWith<IllegalArgumentException> {
            dev.agenticscheduler.sync.CourseScheduleRuleImage(id(32), id(33), dev.agenticscheduler.sync.DayOfWeekImage.MONDAY, listOf(2, 1), dev.agenticscheduler.sync.CourseTimeSpecImage.ClockTime("09:00", "10:00"), null)
        }
        assertFailsWith<IllegalArgumentException> {
            dev.agenticscheduler.sync.CourseOccurrenceExceptionImage(id(34), dev.agenticscheduler.sync.CourseOccurrenceKeyImage(id(35), 1), dev.agenticscheduler.sync.CourseOccurrenceDispositionImage.CANCELLED, dev.agenticscheduler.sync.ZonedTimeRangeImage("2026-01-01T09:00:00Z", "2026-01-01T10:00:00Z", "UTC"), dev.agenticscheduler.sync.RoomOverrideImage.Unchanged).toDomain()
        }
    }

    private fun id(number: Int) = "00000000-0000-7000-8000-${number.toString().padStart(12, '0')}"
    private fun date(value: String) = LocalDate.parse(value)
    private fun time(value: String) = LocalTime.parse(value)
    private fun range(start: String, end: String, zone: TimeZone) = ZonedTimeRange(Instant.parse(start), Instant.parse(end), zone)
}
