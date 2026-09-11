package dev.agenticscheduler.domain.academic

import dev.agenticscheduler.domain.id.AcademicHolidayId
import dev.agenticscheduler.domain.id.CourseId
import dev.agenticscheduler.domain.id.CourseOccurrenceExceptionId
import dev.agenticscheduler.domain.id.CourseScheduleRuleId
import dev.agenticscheduler.domain.id.PeriodTemplateId
import dev.agenticscheduler.domain.id.SemesterId
import dev.agenticscheduler.domain.time.ZonedTimeRange
import kotlin.time.Instant
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.seconds
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.datetime.plus
import kotlinx.datetime.offsetAt
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.toImmutableList

sealed interface CourseSessionResolutionResult {
    data class Success(val sessions: ImmutableList<CourseSession>) : CourseSessionResolutionResult

    data class Invalid(val issues: ImmutableList<CourseSessionResolutionIssue>) : CourseSessionResolutionResult {
        init {
            require(issues.isNotEmpty()) { "An invalid resolution must report at least one issue." }
        }
    }
}

sealed interface CourseSessionResolutionIssue {
    data class CourseSemesterMismatch(
        val expectedSemesterId: SemesterId,
        val actualSemesterId: SemesterId,
    ) : CourseSessionResolutionIssue

    data class DuplicateRuleId(val ruleId: CourseScheduleRuleId) : CourseSessionResolutionIssue

    data class RuleCourseMismatch(
        val ruleId: CourseScheduleRuleId,
        val expectedCourseId: CourseId,
        val actualCourseId: CourseId,
    ) : CourseSessionResolutionIssue

    data class UnknownAcademicWeek(
        val ruleId: CourseScheduleRuleId,
        val academicWeekNumber: AcademicWeekNumber,
    ) : CourseSessionResolutionIssue

    data class DuplicatePeriodTemplateId(
        val periodTemplateId: PeriodTemplateId,
    ) : CourseSessionResolutionIssue

    data class UnknownPeriodTemplate(
        val ruleId: CourseScheduleRuleId,
        val periodTemplateId: PeriodTemplateId,
    ) : CourseSessionResolutionIssue

    data class UnknownStartPeriod(
        val ruleId: CourseScheduleRuleId,
        val periodTemplateId: PeriodTemplateId,
        val periodNumber: AcademicPeriodNumber,
    ) : CourseSessionResolutionIssue

    data class UnknownEndPeriod(
        val ruleId: CourseScheduleRuleId,
        val periodTemplateId: PeriodTemplateId,
        val periodNumber: AcademicPeriodNumber,
    ) : CourseSessionResolutionIssue

    data class DuplicateHolidayId(val holidayId: AcademicHolidayId) : CourseSessionResolutionIssue

    data class HolidaySemesterMismatch(
        val holidayId: AcademicHolidayId,
        val expectedSemesterId: SemesterId,
        val actualSemesterId: SemesterId,
    ) : CourseSessionResolutionIssue

    data class HolidayOutsideSemester(val holidayId: AcademicHolidayId) : CourseSessionResolutionIssue

    data class DuplicateExceptionId(
        val exceptionId: CourseOccurrenceExceptionId,
    ) : CourseSessionResolutionIssue

    data class DuplicateExceptionTarget(
        val occurrenceKey: CourseOccurrenceKey,
    ) : CourseSessionResolutionIssue

    data class OrphanException(
        val exceptionId: CourseOccurrenceExceptionId,
        val occurrenceKey: CourseOccurrenceKey,
    ) : CourseSessionResolutionIssue

    data class ExceptionTimeZoneMismatch(
        val exceptionId: CourseOccurrenceExceptionId,
    ) : CourseSessionResolutionIssue

    data class ExceptionOutsideSemester(
        val exceptionId: CourseOccurrenceExceptionId,
    ) : CourseSessionResolutionIssue

    data class DstTransitionRejected(
        val occurrenceKey: CourseOccurrenceKey,
    ) : CourseSessionResolutionIssue
}

private data class BaseOccurrence(
    val rule: CourseScheduleRule,
    val week: AcademicWeek,
    val date: LocalDate,
) {
    val key: CourseOccurrenceKey = CourseOccurrenceKey(rule.id, week.number)
}

fun resolveCourseSessions(
    semester: Semester,
    course: Course,
    rules: Collection<CourseScheduleRule>,
    periodTemplates: Collection<PeriodTemplate>,
    holidays: Collection<AcademicHoliday>,
    exceptions: Collection<CourseOccurrenceException>,
): CourseSessionResolutionResult {
    val issues = mutableListOf<CourseSessionResolutionIssue>()

    if (course.semesterId != semester.id) {
        issues += CourseSessionResolutionIssue.CourseSemesterMismatch(semester.id, course.semesterId)
    }

    duplicateValues(rules, CourseScheduleRule::id).forEach { ruleId ->
        issues += CourseSessionResolutionIssue.DuplicateRuleId(ruleId)
    }
    rules.filter { it.courseId != course.id }.forEach { rule ->
        issues += CourseSessionResolutionIssue.RuleCourseMismatch(rule.id, course.id, rule.courseId)
    }

    val weeksByNumber = semester.academicWeeks.associateBy(AcademicWeek::number)
    rules.forEach { rule ->
        rule.teachingWeeks.weeks.filter { it !in weeksByNumber }.forEach { weekNumber ->
            issues += CourseSessionResolutionIssue.UnknownAcademicWeek(rule.id, weekNumber)
        }
    }

    duplicateValues(periodTemplates, PeriodTemplate::id).forEach { templateId ->
        issues += CourseSessionResolutionIssue.DuplicatePeriodTemplateId(templateId)
    }
    val templatesById = periodTemplates.groupBy(PeriodTemplate::id)
    rules.forEach { rule ->
        val time = rule.time
        if (time is CourseTimeSpec.PeriodBased) {
            val template = templatesById[time.periodTemplateId]?.firstOrNull()
            if (template == null) {
                issues += CourseSessionResolutionIssue.UnknownPeriodTemplate(rule.id, time.periodTemplateId)
            } else {
                val periodsByNumber = template.periods.associateBy(AcademicPeriod::number)
                if (time.startPeriod !in periodsByNumber) {
                    issues += CourseSessionResolutionIssue.UnknownStartPeriod(
                        rule.id,
                        time.periodTemplateId,
                        time.startPeriod,
                    )
                }
                if (time.endPeriodInclusive !in periodsByNumber) {
                    issues += CourseSessionResolutionIssue.UnknownEndPeriod(
                        rule.id,
                        time.periodTemplateId,
                        time.endPeriodInclusive,
                    )
                }
            }
        }
    }

    duplicateValues(holidays, AcademicHoliday::id).forEach { holidayId ->
        issues += CourseSessionResolutionIssue.DuplicateHolidayId(holidayId)
    }
    holidays.forEach { holiday ->
        if (holiday.semesterId != semester.id) {
            issues += CourseSessionResolutionIssue.HolidaySemesterMismatch(holiday.id, semester.id, holiday.semesterId)
        }
        if (holiday.dates.startDate < semester.startDate || holiday.dates.endDateExclusive > semester.endDateExclusive) {
            issues += CourseSessionResolutionIssue.HolidayOutsideSemester(holiday.id)
        }
    }

    duplicateValues(exceptions, CourseOccurrenceException::id).forEach { exceptionId ->
        issues += CourseSessionResolutionIssue.DuplicateExceptionId(exceptionId)
    }
    duplicateValues(exceptions, CourseOccurrenceException::occurrenceKey).forEach { occurrenceKey ->
        issues += CourseSessionResolutionIssue.DuplicateExceptionTarget(occurrenceKey)
    }

    val baseOccurrenceKeys = rules.flatMap { rule ->
        rule.teachingWeeks.weeks.mapNotNull { weekNumber ->
            weeksByNumber[weekNumber]?.let { CourseOccurrenceKey(rule.id, it.number) }
        }
    }.toSet()
    exceptions.forEach { exception ->
        if (exception.occurrenceKey !in baseOccurrenceKeys) {
            issues += CourseSessionResolutionIssue.OrphanException(exception.id, exception.occurrenceKey)
        }
        exception.timeOverride?.let { override ->
            if (override.timeZone != semester.timeZone) {
                issues += CourseSessionResolutionIssue.ExceptionTimeZoneMismatch(exception.id)
            }
            if (!semester.containsExactRange(override.start, override.endExclusive)) {
                issues += CourseSessionResolutionIssue.ExceptionOutsideSemester(exception.id)
            }
        }
    }

    if (issues.isNotEmpty()) return CourseSessionResolutionResult.Invalid(issues.sortedForResolution().toImmutableList())

    val validTemplatesById = periodTemplates.associateBy(PeriodTemplate::id)
    val baseOccurrences = rules.flatMap { rule ->
        rule.teachingWeeks.weeks.map { weekNumber ->
            val week = checkNotNull(weeksByNumber[weekNumber])
            BaseOccurrence(rule, week, occurrenceDate(week, rule))
        }
    }

    val dstIssues = mutableListOf<CourseSessionResolutionIssue>()
    val baseTimes = mutableMapOf<CourseOccurrenceKey, ZonedTimeRange>()
    baseOccurrences.forEach { occurrence ->
        val range = resolveBaseTime(occurrence, semester, validTemplatesById)
        if (range == null) {
            dstIssues += CourseSessionResolutionIssue.DstTransitionRejected(occurrence.key)
        } else {
            baseTimes[occurrence.key] = range
        }
    }
    if (dstIssues.isNotEmpty()) return CourseSessionResolutionResult.Invalid(dstIssues.sortedForResolution().toImmutableList())

    val exceptionsByKey = exceptions.associateBy(CourseOccurrenceException::occurrenceKey)
    val sessions = baseOccurrences.map { occurrence ->
        val baseTime = checkNotNull(baseTimes[occurrence.key])
        val exception = exceptionsByKey[occurrence.key]
        val holidaySuspendsTeaching = holidays.any { holiday ->
            holiday.teachingEffect == AcademicHolidayTeachingEffect.SUSPEND_TEACHING &&
                occurrence.date >= holiday.dates.startDate && occurrence.date < holiday.dates.endDateExclusive
        }
        val state = when {
            exception?.disposition == CourseOccurrenceDisposition.CANCELLED ->
                CourseSessionState.Cancelled(CourseCancellationReason.EXPLICIT_EXCEPTION)
            exception?.disposition == CourseOccurrenceDisposition.ACTIVE ->
                CourseSessionState.Scheduled(
                    time = exception.timeOverride ?: baseTime,
                    room = applyRoomOverride(occurrence.rule.room, exception.roomOverride),
                )
            holidaySuspendsTeaching -> CourseSessionState.Cancelled(CourseCancellationReason.ACADEMIC_HOLIDAY)
            else -> CourseSessionState.Scheduled(baseTime, occurrence.rule.room)
        }
        CourseSession(occurrence.key, course.id, baseTime, state)
    }.sortedWith(
        compareBy<CourseSession> { it.occurrenceKey.academicWeekNumber.value }
            .thenBy { it.occurrenceKey.scheduleRuleId.value },
    )

    return CourseSessionResolutionResult.Success(sessions.toImmutableList())
}

private fun occurrenceDate(week: AcademicWeek, rule: CourseScheduleRule): LocalDate =
    (0 until 7)
        .map { week.startDate.plus(it, DateTimeUnit.DAY) }
        .single { it.dayOfWeek == rule.dayOfWeek }

private fun resolveBaseTime(
    occurrence: BaseOccurrence,
    semester: Semester,
    templatesById: Map<PeriodTemplateId, PeriodTemplate>,
): ZonedTimeRange? {
    val localTimes = when (val time = occurrence.rule.time) {
        is CourseTimeSpec.ClockTime -> time.start to time.endExclusive
        is CourseTimeSpec.PeriodBased -> {
            val template = checkNotNull(templatesById[time.periodTemplateId])
            val periods = template.periods.associateBy(AcademicPeriod::number)
            checkNotNull(periods[time.startPeriod]).start to checkNotNull(periods[time.endPeriodInclusive]).endExclusive
        }
    }
    val start = LocalDateTime(occurrence.date, localTimes.first).toInstantOrNull(semester)
    val endExclusive = LocalDateTime(occurrence.date, localTimes.second).toInstantOrNull(semester)
    return if (start == null || endExclusive == null) null else ZonedTimeRange(start, endExclusive, semester.timeZone)
}

private fun LocalDateTime.toInstantOrNull(semester: Semester): Instant? {
    val selected = try {
        toInstant(semester.timeZone)
    } catch (_: IllegalArgumentException) {
        return null
    }
    if (selected.toLocalDateTime(semester.timeZone) != this) return null

    // kotlinx-datetime 0.8.0 does not expose a TransitionHandler. Detect a second
    // valid instant explicitly so its legacy default offset choice cannot leak into
    // CourseSession semantics.
    val selectedOffset = semester.timeZone.offsetAt(selected).totalSeconds
    val nearbyOffsets = listOf(
        semester.timeZone.offsetAt(selected - 48.hours),
        semester.timeZone.offsetAt(selected + 48.hours),
    ).map { it.totalSeconds }.distinct()
    val hasAlternativeInstant = nearbyOffsets.any { alternateOffset ->
        alternateOffset != selectedOffset &&
            (selected + (selectedOffset - alternateOffset).seconds).toLocalDateTime(semester.timeZone) == this
    }
    return if (hasAlternativeInstant) null else selected
}

private fun applyRoomOverride(baseRoom: String?, override: RoomOverride): String? = when (override) {
    RoomOverride.Unchanged -> baseRoom
    is RoomOverride.Set -> override.value
    RoomOverride.Clear -> null
}

private fun <T, K> duplicateValues(values: Collection<T>, key: (T) -> K): List<K> =
    values.groupingBy(key).eachCount().filterValues { it > 1 }.keys.toList()

private fun List<CourseSessionResolutionIssue>.sortedForResolution(): List<CourseSessionResolutionIssue> =
    sortedWith(::compareResolutionIssues)

private fun compareResolutionIssues(
    left: CourseSessionResolutionIssue,
    right: CourseSessionResolutionIssue,
): Int {
    val kindComparison = issueKind(left).compareTo(issueKind(right))
    if (kindComparison != 0) return kindComparison
    val idComparison = compareLists(issueIds(left), issueIds(right), String::compareTo)
    if (idComparison != 0) return idComparison
    return compareLists(issueNumbers(left), issueNumbers(right), Int::compareTo)
}

private fun <T> compareLists(left: List<T>, right: List<T>, compare: (T, T) -> Int): Int {
    val sharedSize = minOf(left.size, right.size)
    for (index in 0 until sharedSize) {
        val result = compare(left[index], right[index])
        if (result != 0) return result
    }
    return left.size.compareTo(right.size)
}

private fun issueKind(issue: CourseSessionResolutionIssue): Int = when (issue) {
    is CourseSessionResolutionIssue.CourseSemesterMismatch -> 1
    is CourseSessionResolutionIssue.DuplicateRuleId -> 2
    is CourseSessionResolutionIssue.RuleCourseMismatch -> 3
    is CourseSessionResolutionIssue.UnknownAcademicWeek -> 4
    is CourseSessionResolutionIssue.DuplicatePeriodTemplateId -> 5
    is CourseSessionResolutionIssue.UnknownPeriodTemplate -> 6
    is CourseSessionResolutionIssue.UnknownStartPeriod -> 7
    is CourseSessionResolutionIssue.UnknownEndPeriod -> 8
    is CourseSessionResolutionIssue.DuplicateHolidayId -> 9
    is CourseSessionResolutionIssue.HolidaySemesterMismatch -> 10
    is CourseSessionResolutionIssue.HolidayOutsideSemester -> 11
    is CourseSessionResolutionIssue.DuplicateExceptionId -> 12
    is CourseSessionResolutionIssue.DuplicateExceptionTarget -> 13
    is CourseSessionResolutionIssue.OrphanException -> 14
    is CourseSessionResolutionIssue.ExceptionTimeZoneMismatch -> 15
    is CourseSessionResolutionIssue.ExceptionOutsideSemester -> 16
    is CourseSessionResolutionIssue.DstTransitionRejected -> 17
}

private fun issueIds(issue: CourseSessionResolutionIssue): List<String> = when (issue) {
    is CourseSessionResolutionIssue.CourseSemesterMismatch -> listOf(
        issue.expectedSemesterId.value,
        issue.actualSemesterId.value,
    ).sorted()
    is CourseSessionResolutionIssue.DuplicateRuleId -> listOf(issue.ruleId.value)
    is CourseSessionResolutionIssue.RuleCourseMismatch -> listOf(
        issue.ruleId.value,
        issue.expectedCourseId.value,
        issue.actualCourseId.value,
    ).sorted()
    is CourseSessionResolutionIssue.UnknownAcademicWeek -> listOf(issue.ruleId.value)
    is CourseSessionResolutionIssue.DuplicatePeriodTemplateId -> listOf(issue.periodTemplateId.value)
    is CourseSessionResolutionIssue.UnknownPeriodTemplate -> listOf(issue.ruleId.value, issue.periodTemplateId.value).sorted()
    is CourseSessionResolutionIssue.UnknownStartPeriod -> listOf(issue.ruleId.value, issue.periodTemplateId.value).sorted()
    is CourseSessionResolutionIssue.UnknownEndPeriod -> listOf(issue.ruleId.value, issue.periodTemplateId.value).sorted()
    is CourseSessionResolutionIssue.DuplicateHolidayId -> listOf(issue.holidayId.value)
    is CourseSessionResolutionIssue.HolidaySemesterMismatch -> listOf(
        issue.holidayId.value,
        issue.expectedSemesterId.value,
        issue.actualSemesterId.value,
    ).sorted()
    is CourseSessionResolutionIssue.HolidayOutsideSemester -> listOf(issue.holidayId.value)
    is CourseSessionResolutionIssue.DuplicateExceptionId -> listOf(issue.exceptionId.value)
    is CourseSessionResolutionIssue.DuplicateExceptionTarget -> listOf(issue.occurrenceKey.scheduleRuleId.value)
    is CourseSessionResolutionIssue.OrphanException -> listOf(
        issue.exceptionId.value,
        issue.occurrenceKey.scheduleRuleId.value,
    ).sorted()
    is CourseSessionResolutionIssue.ExceptionTimeZoneMismatch -> listOf(issue.exceptionId.value)
    is CourseSessionResolutionIssue.ExceptionOutsideSemester -> listOf(issue.exceptionId.value)
    is CourseSessionResolutionIssue.DstTransitionRejected -> listOf(issue.occurrenceKey.scheduleRuleId.value)
}

private fun issueNumbers(issue: CourseSessionResolutionIssue): List<Int> = when (issue) {
    is CourseSessionResolutionIssue.UnknownAcademicWeek -> listOf(issue.academicWeekNumber.value)
    is CourseSessionResolutionIssue.UnknownStartPeriod -> listOf(issue.periodNumber.value)
    is CourseSessionResolutionIssue.UnknownEndPeriod -> listOf(issue.periodNumber.value)
    is CourseSessionResolutionIssue.DuplicateExceptionTarget -> listOf(issue.occurrenceKey.academicWeekNumber.value)
    is CourseSessionResolutionIssue.OrphanException -> listOf(issue.occurrenceKey.academicWeekNumber.value)
    is CourseSessionResolutionIssue.DstTransitionRejected -> listOf(issue.occurrenceKey.academicWeekNumber.value)
    else -> emptyList()
}
