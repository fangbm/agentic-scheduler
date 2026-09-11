package dev.agenticscheduler.application.calendar

import dev.agenticscheduler.application.persistence.AcademicRepository
import dev.agenticscheduler.application.persistence.EventRepository
import dev.agenticscheduler.application.persistence.TaskRepository
import dev.agenticscheduler.domain.academic.*
import dev.agenticscheduler.domain.id.CourseId
import dev.agenticscheduler.domain.id.ExamId
import dev.agenticscheduler.domain.id.EventId
import dev.agenticscheduler.domain.id.FocusBlockId
import dev.agenticscheduler.domain.time.AllDayRange
import dev.agenticscheduler.domain.time.FloatingTimeRange
import dev.agenticscheduler.domain.time.ZonedTimeRange
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.plus

/** An explicit display-timezone calendar window; projection never uses a system default timezone. */
data class CalendarViewport(
    val startDate: LocalDate,
    val endDateExclusive: LocalDate,
    val displayTimeZone: TimeZone,
) {
    init { require(startDate < endDateExclusive) { "A calendar viewport must have a positive date range." } }
}

sealed interface CalendarSourceRef {
    data class Event(val id: EventId) : CalendarSourceRef
    data class FocusBlock(val id: FocusBlockId) : CalendarSourceRef
    data class CourseSession(val key: CourseOccurrenceKey) : CalendarSourceRef
    data class Exam(val id: ExamId) : CalendarSourceRef
}

sealed interface CalendarItem {
    val source: CalendarSourceRef
    val title: String

    data class Zoned(override val source: CalendarSourceRef, override val title: String, val originalRange: ZonedTimeRange) : CalendarItem
    data class AllDay(override val source: CalendarSourceRef, override val title: String, val range: AllDayRange) : CalendarItem
    data class Floating(override val source: CalendarSourceRef, override val title: String, val range: FloatingTimeRange) : CalendarItem
    data class DateOnly(override val source: CalendarSourceRef, override val title: String, val date: LocalDate) : CalendarItem
}

data class CalendarConflict(val first: CalendarSourceRef, val second: CalendarSourceRef)

sealed interface CalendarProjectionIssue {
    data class AcademicResolution(val courseId: CourseId, val issue: CourseSessionResolutionIssue) : CalendarProjectionIssue
    data class MissingSemester(val courseId: CourseId, val semesterId: dev.agenticscheduler.domain.id.SemesterId) : CalendarProjectionIssue
}

data class CalendarProjectionResult(
    val items: ImmutableList<CalendarItem>,
    val conflicts: ImmutableList<CalendarConflict>,
    val issues: ImmutableList<CalendarProjectionIssue>,
)

interface CalendarQueryService { fun observe(viewport: CalendarViewport): Flow<CalendarProjectionResult> }

class RepositoryCalendarQueryService(
    private val events: EventRepository,
    private val tasks: TaskRepository,
    private val academics: AcademicRepository,
) : CalendarQueryService {
    override fun observe(viewport: CalendarViewport): Flow<CalendarProjectionResult> {
        val academicSnapshot = combine(
            academics.observeSemesters(), academics.observeCourses(), academics.observeCourseScheduleRules(),
            academics.observePeriodTemplates(), academics.observeAcademicHolidays(),
        ) { semesters, courses, rules, templates, holidays -> CalendarProjectionInput(semesters, courses, rules, templates, holidays) }
        val completeAcademicSnapshot = combine(
            academicSnapshot, academics.observeCourseOccurrenceExceptions(), academics.observeExams(),
        ) { snapshot, exceptions, exams -> snapshot.copy(exceptions = exceptions, exams = exams) }
        return combine(events.observeAll(), tasks.observeFocusBlocks(), completeAcademicSnapshot) { eventValues, focusBlocks, snapshot ->
            project(viewport, eventValues, focusBlocks, snapshot)
        }
    }
}

internal data class CalendarProjectionInput(
    val semesters: ImmutableList<Semester>,
    val courses: ImmutableList<Course>,
    val rules: ImmutableList<CourseScheduleRule>,
    val templates: ImmutableList<PeriodTemplate>,
    val holidays: ImmutableList<AcademicHoliday>,
    val exceptions: ImmutableList<CourseOccurrenceException> = persistentEmpty(),
    val exams: ImmutableList<Exam> = persistentEmpty(),
)

private fun <T> persistentEmpty(): ImmutableList<T> = emptyList<T>().toImmutableList()

internal fun project(
    viewport: CalendarViewport,
    events: Collection<dev.agenticscheduler.domain.event.Event>,
    focusBlocks: Collection<dev.agenticscheduler.domain.task.FocusBlock>,
    academic: CalendarProjectionInput,
): CalendarProjectionResult {
    val candidates = mutableListOf<CalendarItem>()
    events.forEach { event -> candidates += event.toCalendarItem() }
    focusBlocks.forEach { focus -> candidates += CalendarItem.Zoned(CalendarSourceRef.FocusBlock(focus.id), "Focus block", focus.time) }
    academic.exams.forEach { exam -> exam.toCalendarItem()?.let(candidates::add) }

    val issues = mutableListOf<CalendarProjectionIssue>()
    val semesters = academic.semesters.associateBy { it.id }
    academic.courses.forEach { course ->
        val semester = semesters[course.semesterId]
        if (semester == null) {
            issues += CalendarProjectionIssue.MissingSemester(course.id, course.semesterId)
        } else {
            when (val resolution = resolveCourseSessions(
                semester = semester,
                course = course,
                rules = academic.rules.filter { it.courseId == course.id },
                periodTemplates = academic.templates,
                holidays = academic.holidays.filter { it.semesterId == semester.id },
                exceptions = academic.exceptions.filter { exception -> academic.rules.any { it.id == exception.occurrenceKey.scheduleRuleId && it.courseId == course.id } },
            )) {
                is CourseSessionResolutionResult.Success -> resolution.sessions.forEach { session ->
                    val scheduled = session.state as? CourseSessionState.Scheduled ?: return@forEach
                    candidates += CalendarItem.Zoned(CalendarSourceRef.CourseSession(session.occurrenceKey), course.name, scheduled.time)
                }
                is CourseSessionResolutionResult.Invalid -> resolution.issues.forEach { issue ->
                    issues += CalendarProjectionIssue.AcademicResolution(course.id, issue)
                }
            }
        }
    }

    val filtered = candidates.filter { it.intersects(viewport) }.sortedWith(calendarItemComparator).toImmutableList()
    val conflicts = filtered.filterIsInstance<CalendarItem.Zoned>().flatMapIndexed { index, first ->
        filtered.filterIsInstance<CalendarItem.Zoned>().drop(index + 1).mapNotNull { second ->
            if (first.originalRange.overlaps(second.originalRange)) canonicalConflict(first.source, second.source) else null
        }
    }.distinct().sortedWith(Comparator { first, second ->
        sourceComparator.compare(first.first, second.first).takeUnless { it == 0 }
            ?: sourceComparator.compare(first.second, second.second)
    }).toImmutableList()
    return CalendarProjectionResult(filtered, conflicts, issues.sortedBy { it.toString() }.toImmutableList())
}

private fun dev.agenticscheduler.domain.event.Event.toCalendarItem(): CalendarItem = when (val placement = time) {
    is ZonedTimeRange -> CalendarItem.Zoned(CalendarSourceRef.Event(id), title, placement)
    is AllDayRange -> CalendarItem.AllDay(CalendarSourceRef.Event(id), title, placement)
    is FloatingTimeRange -> CalendarItem.Floating(CalendarSourceRef.Event(id), title, placement)
}

private fun Exam.toCalendarItem(): CalendarItem? = when (val value = schedule) {
    is ExamSchedule.Exact -> CalendarItem.Zoned(CalendarSourceRef.Exam(id), title, value.time)
    is ExamSchedule.DateOnly -> CalendarItem.DateOnly(CalendarSourceRef.Exam(id), title, value.date)
    ExamSchedule.Unscheduled -> null
}

private fun CalendarItem.intersects(viewport: CalendarViewport): Boolean = when (this) {
    is CalendarItem.Zoned -> originalRange.start < viewport.endDateExclusive.atStartOfDayIn(viewport.displayTimeZone) &&
        viewport.startDate.atStartOfDayIn(viewport.displayTimeZone) < originalRange.endExclusive
    is CalendarItem.AllDay -> range.startDate < viewport.endDateExclusive && viewport.startDate < range.endDateExclusive
    is CalendarItem.Floating -> range.start < LocalDateTime(viewport.endDateExclusive, LocalTime(0, 0)) &&
        LocalDateTime(viewport.startDate, LocalTime(0, 0)) < range.endExclusive
    is CalendarItem.DateOnly -> date >= viewport.startDate && date < viewport.endDateExclusive
}

/** Whether this presentation item intersects one local calendar date in the explicit display timezone. */
fun CalendarItem.intersectsLocalDate(date: LocalDate, displayTimeZone: TimeZone): Boolean {
    val nextDate = date.plus(1, DateTimeUnit.DAY)
    return when (this) {
        is CalendarItem.Zoned -> {
            val dayStart = date.atStartOfDayIn(displayTimeZone)
            val dayEnd = nextDate.atStartOfDayIn(displayTimeZone)
            originalRange.start < dayEnd && dayStart < originalRange.endExclusive
        }
        is CalendarItem.AllDay -> range.startDate < nextDate && date < range.endDateExclusive
        is CalendarItem.Floating -> {
            val dayStart = LocalDateTime(date, LocalTime(0, 0))
            val dayEnd = LocalDateTime(nextDate, LocalTime(0, 0))
            range.start < dayEnd && dayStart < range.endExclusive
        }
        is CalendarItem.DateOnly -> this.date == date
    }
}

private val calendarItemComparator = Comparator<CalendarItem> { first, second ->
    val group = first.groupRank().compareTo(second.groupRank())
    if (group != 0) group else when {
        first.groupRank() == 0 && second.groupRank() == 0 -> compareDateItems(first, second)
        first is CalendarItem.Zoned && second is CalendarItem.Zoned -> compareValuesBy(first, second, { it.originalRange.start }, { it.originalRange.endExclusive }, { sourceRank(it.source) }, { sourceText(it.source) })
        first is CalendarItem.Floating && second is CalendarItem.Floating -> compareValuesBy(first, second, { it.range.start }, { it.range.endExclusive }, { sourceRank(it.source) }, { sourceText(it.source) })
        else -> 0
    }
}

private fun compareDateItems(first: CalendarItem, second: CalendarItem): Int =
    compareValues(first.dateStartForOrdering(), second.dateStartForOrdering()).takeUnless { it == 0 }
        ?: compareValues(first.dateEndForOrdering(), second.dateEndForOrdering()).takeUnless { it == 0 }
        ?: sourceRank(first.source).compareTo(sourceRank(second.source)).takeUnless { it == 0 }
        ?: sourceText(first.source).compareTo(sourceText(second.source))

private fun CalendarItem.dateStartForOrdering(): LocalDate = when (this) {
    is CalendarItem.AllDay -> range.startDate
    is CalendarItem.DateOnly -> date
    else -> error("Only all-day and date-only items have date ordering.")
}

private fun CalendarItem.dateEndForOrdering(): LocalDate = when (this) {
    is CalendarItem.AllDay -> range.endDateExclusive
    is CalendarItem.DateOnly -> date.plus(1, DateTimeUnit.DAY)
    else -> error("Only all-day and date-only items have date ordering.")
}

private fun CalendarItem.groupRank() = when (this) { is CalendarItem.AllDay, is CalendarItem.DateOnly -> 0; is CalendarItem.Zoned -> 1; is CalendarItem.Floating -> 2 }
private val sourceComparator = Comparator<CalendarSourceRef> { first, second -> compareValuesBy(first, second, ::sourceRank, ::sourceText) }
private fun sourceRank(source: CalendarSourceRef) = when (source) { is CalendarSourceRef.Event -> 0; is CalendarSourceRef.CourseSession -> 1; is CalendarSourceRef.Exam -> 2; is CalendarSourceRef.FocusBlock -> 3 }
private fun sourceText(source: CalendarSourceRef) = when (source) { is CalendarSourceRef.Event -> source.id.value; is CalendarSourceRef.FocusBlock -> source.id.value; is CalendarSourceRef.Exam -> source.id.value; is CalendarSourceRef.CourseSession -> "${source.key.scheduleRuleId.value}:${source.key.academicWeekNumber.value}" }
private fun canonicalConflict(first: CalendarSourceRef, second: CalendarSourceRef): CalendarConflict = if (sourceComparator.compare(first, second) <= 0) CalendarConflict(first, second) else CalendarConflict(second, first)
