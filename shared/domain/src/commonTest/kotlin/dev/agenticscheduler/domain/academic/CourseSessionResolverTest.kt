package dev.agenticscheduler.domain.academic

import dev.agenticscheduler.domain.id.AcademicHolidayId
import dev.agenticscheduler.domain.id.AcademicYearId
import dev.agenticscheduler.domain.id.CourseId
import dev.agenticscheduler.domain.id.CourseOccurrenceExceptionId
import dev.agenticscheduler.domain.id.CourseScheduleRuleId
import dev.agenticscheduler.domain.id.PeriodTemplateId
import dev.agenticscheduler.domain.id.SemesterId
import dev.agenticscheduler.domain.time.AllDayRange
import dev.agenticscheduler.domain.time.ZonedTimeRange
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlin.time.Instant

class CourseSessionResolverTest {
    private val semester = Semester(
        id = SemesterId(id(100)),
        academicYearId = AcademicYearId(id(101)),
        name = "Fall",
        startDate = LocalDate(2026, 9, 1),
        endDateExclusive = LocalDate(2026, 10, 1),
        timeZone = TimeZone.UTC,
        academicWeeks = listOf(
            AcademicWeek(AcademicWeekNumber(1), LocalDate(2026, 9, 1), LocalDate(2026, 9, 8)),
            AcademicWeek(AcademicWeekNumber(2), LocalDate(2026, 9, 8), LocalDate(2026, 9, 15)),
            AcademicWeek(AcademicWeekNumber(3), LocalDate(2026, 9, 22), LocalDate(2026, 9, 29)),
        ),
    )
    private val course = Course(CourseId(id(102)), semester.id, "Algorithms", "CS101")

    @Test
    fun `resolver uses explicit weeks periods and stable key ordering`() {
        val lateRule = rule(105, DayOfWeek.TUESDAY, listOf(3, 1), CourseTimeSpec.ClockTime(LocalTime(10, 0), LocalTime(11, 0)), "R2")
        val earlyRule = rule(104, DayOfWeek.TUESDAY, listOf(1, 3), CourseTimeSpec.PeriodBased(PeriodTemplateId(id(103)), AcademicPeriodNumber(1), AcademicPeriodNumber(2)), "R1")
        val template = PeriodTemplate(
            PeriodTemplateId(id(103)), "standard", listOf(
                AcademicPeriod(AcademicPeriodNumber(1), LocalTime(8, 0), LocalTime(8, 45)),
                AcademicPeriod(AcademicPeriodNumber(2), LocalTime(9, 0), LocalTime(9, 45)),
            ),
        )
        val result = resolveCourseSessions(semester, course, listOf(lateRule, earlyRule), listOf(template), emptyList(), emptyList())
        val sessions = assertIs<CourseSessionResolutionResult.Success>(result).sessions
        assertEquals(4, sessions.size)
        assertEquals(listOf(1, 1, 3, 3), sessions.map { it.occurrenceKey.academicWeekNumber.value })
        assertEquals(listOf(earlyRule.id, lateRule.id, earlyRule.id, lateRule.id), sessions.map { it.occurrenceKey.scheduleRuleId })
        assertEquals(Instant.parse("2026-09-22T08:00:00Z"), sessions[2].baseTime.start)
        assertEquals(Instant.parse("2026-09-22T09:45:00Z"), sessions[2].baseTime.endExclusive)
        assertEquals("R1", assertIs<CourseSessionState.Scheduled>(sessions[0].state).room)
        val reordered = resolveCourseSessions(semester, course, listOf(earlyRule, lateRule), listOf(template), emptyList(), emptyList())
        assertEquals(result, reordered)
    }

    @Test
    fun `exception and holiday precedence follows the frozen state table`() {
        val baseRule = rule(110, DayOfWeek.TUESDAY, listOf(1, 2, 3), CourseTimeSpec.ClockTime(LocalTime(8, 0), LocalTime(9, 0)), "Base")
        val holiday = AcademicHoliday(
            AcademicHolidayId(id(111)), semester.id, "Break", AllDayRange(LocalDate(2026, 9, 8), LocalDate(2026, 9, 15)),
            AcademicHolidayTeachingEffect.SUSPEND_TEACHING,
        )
        val activeKey = CourseOccurrenceKey(baseRule.id, AcademicWeekNumber(2))
        val cancelledKey = CourseOccurrenceKey(baseRule.id, AcademicWeekNumber(3))
        val active = CourseOccurrenceException(
            CourseOccurrenceExceptionId(id(112)), activeKey, CourseOccurrenceDisposition.ACTIVE,
            ZonedTimeRange(Instant.parse("2026-09-08T12:00:00Z"), Instant.parse("2026-09-08T13:00:00Z"), TimeZone.UTC),
            RoomOverride.Set("Moved"),
        )
        val cancelled = CourseOccurrenceException(
            CourseOccurrenceExceptionId(id(113)), cancelledKey, CourseOccurrenceDisposition.CANCELLED, null, RoomOverride.Unchanged,
        )
        val result = assertIs<CourseSessionResolutionResult.Success>(
            resolveCourseSessions(semester, course, listOf(baseRule), emptyList(), listOf(holiday), listOf(active, cancelled)),
        )
        val first = assertIs<CourseSessionState.Scheduled>(result.sessions[0].state)
        val second = assertIs<CourseSessionState.Scheduled>(result.sessions[1].state)
        val third = assertIs<CourseSessionState.Cancelled>(result.sessions[2].state)
        assertEquals("Base", first.room)
        assertEquals(activeKey, result.sessions[1].occurrenceKey)
        assertEquals(Instant.parse("2026-09-08T08:00:00Z"), result.sessions[1].baseTime.start)
        assertEquals(Instant.parse("2026-09-08T12:00:00Z"), second.time.start)
        assertEquals("Moved", second.room)
        assertEquals(CourseCancellationReason.EXPLICIT_EXCEPTION, third.reason)
    }

    @Test
    fun `resolver applies room overrides and holiday states without duplicate sessions`() {
        val baseRule = rule(114, DayOfWeek.TUESDAY, listOf(1, 2, 3), CourseTimeSpec.ClockTime(LocalTime(8, 0), LocalTime(9, 0)), "Base")
        val clearRule = rule(115, DayOfWeek.TUESDAY, listOf(2), CourseTimeSpec.ClockTime(LocalTime(10, 0), LocalTime(11, 0)), "Clear me")
        val holidays = listOf(
            AcademicHoliday(
                AcademicHolidayId(id(116)), semester.id, "Suspended", AllDayRange(LocalDate(2026, 9, 1), LocalDate(2026, 9, 8)),
                AcademicHolidayTeachingEffect.SUSPEND_TEACHING,
            ),
            AcademicHoliday(
                AcademicHolidayId(id(117)), semester.id, "No effect", AllDayRange(LocalDate(2026, 9, 8), LocalDate(2026, 9, 15)),
                AcademicHolidayTeachingEffect.NO_EFFECT,
            ),
            AcademicHoliday(
                AcademicHolidayId(id(118)), semester.id, "Second suspension", AllDayRange(LocalDate(2026, 9, 22), LocalDate(2026, 9, 29)),
                AcademicHolidayTeachingEffect.SUSPEND_TEACHING,
            ),
            AcademicHoliday(
                AcademicHolidayId(id(121)), semester.id, "Also observed", AllDayRange(LocalDate(2026, 9, 1), LocalDate(2026, 9, 8)),
                AcademicHolidayTeachingEffect.NO_EFFECT,
            ),
        )
        val exceptions = listOf(
            CourseOccurrenceException(
                CourseOccurrenceExceptionId(id(119)), CourseOccurrenceKey(baseRule.id, AcademicWeekNumber(3)),
                CourseOccurrenceDisposition.ACTIVE, null, RoomOverride.Unchanged,
            ),
            CourseOccurrenceException(
                CourseOccurrenceExceptionId(id(120)), CourseOccurrenceKey(clearRule.id, AcademicWeekNumber(2)),
                CourseOccurrenceDisposition.ACTIVE, null, RoomOverride.Clear,
            ),
        )

        val sessions = assertIs<CourseSessionResolutionResult.Success>(
            resolveCourseSessions(semester, course, listOf(baseRule, clearRule), emptyList(), holidays, exceptions),
        ).sessions

        assertEquals(4, sessions.size)
        val baseWeekOne = sessions.single { it.occurrenceKey == CourseOccurrenceKey(baseRule.id, AcademicWeekNumber(1)) }
        val baseWeekTwo = sessions.single { it.occurrenceKey == CourseOccurrenceKey(baseRule.id, AcademicWeekNumber(2)) }
        val baseWeekThree = sessions.single { it.occurrenceKey == CourseOccurrenceKey(baseRule.id, AcademicWeekNumber(3)) }
        val cleared = sessions.single { it.occurrenceKey == CourseOccurrenceKey(clearRule.id, AcademicWeekNumber(2)) }
        assertEquals(CourseCancellationReason.ACADEMIC_HOLIDAY, assertIs<CourseSessionState.Cancelled>(baseWeekOne.state).reason)
        assertEquals("Base", assertIs<CourseSessionState.Scheduled>(baseWeekTwo.state).room)
        assertEquals("Base", assertIs<CourseSessionState.Scheduled>(baseWeekThree.state).room)
        assertEquals(null, assertIs<CourseSessionState.Scheduled>(cleared.state).room)
    }

    @Test
    fun `explicit cancellation wins over a covering teaching suspension holiday`() {
        val baseRule = rule(
            152,
            DayOfWeek.TUESDAY,
            listOf(1),
            CourseTimeSpec.ClockTime(LocalTime(8, 0), LocalTime(9, 0)),
            "Base",
        )
        val holiday = AcademicHoliday(
            AcademicHolidayId(id(153)),
            semester.id,
            "Suspended",
            AllDayRange(LocalDate(2026, 9, 1), LocalDate(2026, 9, 8)),
            AcademicHolidayTeachingEffect.SUSPEND_TEACHING,
        )
        val exception = CourseOccurrenceException(
            CourseOccurrenceExceptionId(id(154)),
            CourseOccurrenceKey(baseRule.id, AcademicWeekNumber(1)),
            CourseOccurrenceDisposition.CANCELLED,
            null,
            RoomOverride.Unchanged,
        )

        val session = assertIs<CourseSessionResolutionResult.Success>(
            resolveCourseSessions(semester, course, listOf(baseRule), emptyList(), listOf(holiday), listOf(exception)),
        ).sessions.single()

        assertEquals(
            CourseCancellationReason.EXPLICIT_EXCEPTION,
            assertIs<CourseSessionState.Cancelled>(session.state).reason,
        )
    }

    @Test
    fun `resolver permits overlapping sessions from different rules`() {
        val firstRule = rule(
            155,
            DayOfWeek.TUESDAY,
            listOf(1),
            CourseTimeSpec.ClockTime(LocalTime(8, 0), LocalTime(10, 0)),
            "First",
        )
        val secondRule = rule(
            156,
            DayOfWeek.TUESDAY,
            listOf(1),
            CourseTimeSpec.ClockTime(LocalTime(9, 0), LocalTime(11, 0)),
            "Second",
        )

        val sessions = assertIs<CourseSessionResolutionResult.Success>(
            resolveCourseSessions(semester, course, listOf(firstRule, secondRule), emptyList(), emptyList(), emptyList()),
        ).sessions

        assertEquals(2, sessions.size)
        val first = sessions.single { it.occurrenceKey.scheduleRuleId == firstRule.id }
        val second = sessions.single { it.occurrenceKey.scheduleRuleId == secondRule.id }
        assertTrue(first.baseTime.start < second.baseTime.endExclusive)
        assertTrue(second.baseTime.start < first.baseTime.endExclusive)
    }

    @Test
    fun `resolver reports all non DST public invalid input cases in deterministic order`() {
        val duplicateRuleId = CourseScheduleRuleId(id(120))
        val knownRule = rule(120, DayOfWeek.TUESDAY, listOf(1), CourseTimeSpec.ClockTime(LocalTime(8, 0), LocalTime(9, 0)), null)
        val duplicatedRule = knownRule.copy(time = CourseTimeSpec.ClockTime(LocalTime(9, 0), LocalTime(10, 0)))
        val foreignRule = knownRule.copy(id = CourseScheduleRuleId(id(121)), courseId = CourseId(id(122)))
        val unknownWeekRule = knownRule.copy(id = CourseScheduleRuleId(id(123)), teachingWeeks = TeachingWeekSet.of(listOf(AcademicWeekNumber(99))))
        val unknownTemplateRule = knownRule.copy(
            id = CourseScheduleRuleId(id(124)),
            time = CourseTimeSpec.PeriodBased(PeriodTemplateId(id(125)), AcademicPeriodNumber(1), AcademicPeriodNumber(1)),
        )
        val missingPeriodsRule = knownRule.copy(
            id = CourseScheduleRuleId(id(126)),
            time = CourseTimeSpec.PeriodBased(PeriodTemplateId(id(127)), AcademicPeriodNumber(8), AcademicPeriodNumber(9)),
        )
        val template = PeriodTemplate(PeriodTemplateId(id(127)), "only one", listOf(
            AcademicPeriod(AcademicPeriodNumber(1), LocalTime(8, 0), LocalTime(9, 0)),
        ))
        val duplicateTemplate = template.copy()
        val duplicateHoliday = AcademicHoliday(
            AcademicHolidayId(id(128)), semester.id, "h", AllDayRange(LocalDate(2026, 9, 1), LocalDate(2026, 9, 2)),
            AcademicHolidayTeachingEffect.NO_EFFECT,
        )
        val wrongAndOutsideHoliday = AcademicHoliday(
            AcademicHolidayId(id(129)), SemesterId(id(130)), "wrong", AllDayRange(LocalDate(2026, 8, 31), LocalDate(2026, 9, 2)),
            AcademicHolidayTeachingEffect.NO_EFFECT,
        )
        val duplicateTarget = CourseOccurrenceKey(knownRule.id, AcademicWeekNumber(1))
        val duplicateExceptionOne = CourseOccurrenceException(
            CourseOccurrenceExceptionId(id(131)), duplicateTarget, CourseOccurrenceDisposition.ACTIVE, null, RoomOverride.Unchanged,
        )
        val duplicateExceptionTwo = CourseOccurrenceException(
            CourseOccurrenceExceptionId(id(132)), duplicateTarget, CourseOccurrenceDisposition.ACTIVE, null, RoomOverride.Unchanged,
        )
        val orphanOverride = CourseOccurrenceException(
            CourseOccurrenceExceptionId(id(133)), CourseOccurrenceKey(CourseScheduleRuleId(id(134)), AcademicWeekNumber(1)),
            CourseOccurrenceDisposition.ACTIVE,
            ZonedTimeRange(Instant.parse("2027-01-01T00:00:00Z"), Instant.parse("2027-01-01T01:00:00Z"), TimeZone.of("Asia/Shanghai")),
            RoomOverride.Unchanged,
        )
        val duplicateId = CourseOccurrenceException(
            CourseOccurrenceExceptionId(id(131)), CourseOccurrenceKey(knownRule.id, AcademicWeekNumber(2)),
            CourseOccurrenceDisposition.ACTIVE, null, RoomOverride.Unchanged,
        )
        val input = listOf(knownRule, duplicatedRule, foreignRule, unknownWeekRule, unknownTemplateRule, missingPeriodsRule)
        val result = assertIs<CourseSessionResolutionResult.Invalid>(
            resolveCourseSessions(
                semester,
                course.copy(semesterId = SemesterId(id(135))),
                input,
                listOf(template, duplicateTemplate),
                listOf(duplicateHoliday, duplicateHoliday.copy(), wrongAndOutsideHoliday),
                listOf(duplicateExceptionOne, duplicateExceptionTwo, orphanOverride, duplicateId),
            ),
        )
        val types = result.issues.map { it::class.simpleName }.toSet()
        assertEquals(
            setOf(
                "CourseSemesterMismatch", "DuplicateRuleId", "RuleCourseMismatch", "UnknownAcademicWeek",
                "DuplicatePeriodTemplateId", "UnknownPeriodTemplate", "UnknownStartPeriod", "UnknownEndPeriod",
                "DuplicateHolidayId", "HolidaySemesterMismatch", "HolidayOutsideSemester", "DuplicateExceptionId",
                "DuplicateExceptionTarget", "OrphanException", "ExceptionTimeZoneMismatch", "ExceptionOutsideSemester",
            ),
            types,
        )
        assertEquals(
            listOf(
                "CourseSemesterMismatch", "DuplicateRuleId", "RuleCourseMismatch", "UnknownAcademicWeek",
                "DuplicatePeriodTemplateId", "UnknownPeriodTemplate", "UnknownStartPeriod", "UnknownEndPeriod",
                "DuplicateHolidayId", "HolidaySemesterMismatch", "HolidayOutsideSemester", "DuplicateExceptionId",
                "DuplicateExceptionTarget", "OrphanException", "OrphanException", "ExceptionTimeZoneMismatch",
                "ExceptionOutsideSemester",
            ),
            result.issues.map { it::class.simpleName },
        )
    }

    @Test
    fun `resolver rejects a real DST transition rather than shifting it`() {
        val newYork = TimeZone.of("America/New_York")
        val dstSemester = Semester(
            SemesterId(id(140)), AcademicYearId(id(141)), "Spring", LocalDate(2026, 3, 1), LocalDate(2026, 4, 1), newYork,
            listOf(AcademicWeek(AcademicWeekNumber(1), LocalDate(2026, 3, 2), LocalDate(2026, 3, 9))),
        )
        val dstCourse = Course(CourseId(id(142)), dstSemester.id, "DST", null)
        val dstRule = CourseScheduleRule(
            CourseScheduleRuleId(id(143)), dstCourse.id, DayOfWeek.SUNDAY,
            TeachingWeekSet.of(listOf(AcademicWeekNumber(1))), CourseTimeSpec.ClockTime(LocalTime(2, 30), LocalTime(3, 30)), null,
        )
        val result = assertIs<CourseSessionResolutionResult.Invalid>(
            resolveCourseSessions(dstSemester, dstCourse, listOf(dstRule), emptyList(), emptyList(), emptyList()),
        )
        assertEquals(listOf(CourseSessionResolutionIssue.DstTransitionRejected(CourseOccurrenceKey(dstRule.id, AcademicWeekNumber(1)))), result.issues)
    }

    @Test
    fun `resolver rejects an ambiguous fall back local time`() {
        val newYork = TimeZone.of("America/New_York")
        val dstSemester = Semester(
            SemesterId(id(144)), AcademicYearId(id(145)), "Fall", LocalDate(2026, 10, 1), LocalDate(2026, 12, 1), newYork,
            listOf(AcademicWeek(AcademicWeekNumber(1), LocalDate(2026, 10, 26), LocalDate(2026, 11, 2))),
        )
        val dstCourse = Course(CourseId(id(146)), dstSemester.id, "DST", null)
        val dstRule = CourseScheduleRule(
            CourseScheduleRuleId(id(147)), dstCourse.id, DayOfWeek.SUNDAY,
            TeachingWeekSet.of(listOf(AcademicWeekNumber(1))), CourseTimeSpec.ClockTime(LocalTime(1, 30), LocalTime(2, 30)), null,
        )
        val result = assertIs<CourseSessionResolutionResult.Invalid>(
            resolveCourseSessions(dstSemester, dstCourse, listOf(dstRule), emptyList(), emptyList(), emptyList()),
        )
        assertEquals(listOf(CourseSessionResolutionIssue.DstTransitionRejected(CourseOccurrenceKey(dstRule.id, AcademicWeekNumber(1)))), result.issues)
    }

    @Test
    fun `resolver accepts a normal local time in a DST observing timezone`() {
        val newYork = TimeZone.of("America/New_York")
        val dstSemester = Semester(
            SemesterId(id(148)), AcademicYearId(id(149)), "Fall", LocalDate(2026, 10, 1), LocalDate(2026, 12, 1), newYork,
            listOf(AcademicWeek(AcademicWeekNumber(1), LocalDate(2026, 10, 26), LocalDate(2026, 11, 2))),
        )
        val dstCourse = Course(CourseId(id(150)), dstSemester.id, "DST", null)
        val dstRule = CourseScheduleRule(
            CourseScheduleRuleId(id(151)), dstCourse.id, DayOfWeek.SATURDAY,
            TeachingWeekSet.of(listOf(AcademicWeekNumber(1))), CourseTimeSpec.ClockTime(LocalTime(1, 30), LocalTime(2, 30)), null,
        )
        val result = assertIs<CourseSessionResolutionResult.Success>(
            resolveCourseSessions(dstSemester, dstCourse, listOf(dstRule), emptyList(), emptyList(), emptyList()),
        )
        assertEquals(1, result.sessions.size)
        assertEquals(CourseOccurrenceKey(dstRule.id, AcademicWeekNumber(1)), result.sessions.single().occurrenceKey)
    }

    private fun rule(
        ruleNumber: Int,
        day: DayOfWeek,
        weeks: List<Int>,
        time: CourseTimeSpec,
        room: String?,
    ): CourseScheduleRule = CourseScheduleRule(
        CourseScheduleRuleId(id(ruleNumber)), course.id, day,
        TeachingWeekSet.of(weeks.map(::AcademicWeekNumber)), time, room,
    )
}
