package dev.agenticscheduler.planner

import dev.agenticscheduler.domain.academic.CourseSessionState
import dev.agenticscheduler.domain.academic.ExamSchedule
import dev.agenticscheduler.domain.event.Event
import dev.agenticscheduler.domain.planning.AllDayEventPolicy
import dev.agenticscheduler.domain.planning.Deadline
import dev.agenticscheduler.domain.planning.PlanningProfileConfiguration
import dev.agenticscheduler.domain.planning.WeeklyAvailabilityWindow
import dev.agenticscheduler.domain.time.AllDayRange
import dev.agenticscheduler.domain.time.FloatingTimeRange
import dev.agenticscheduler.domain.time.TimePlacement
import dev.agenticscheduler.domain.time.ZonedTimeRange
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.offsetAt
import kotlinx.datetime.plus
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime

/**
 * R1 pure time legality helpers: DST-safe local time resolution, availability
 * expansion in the profile timezone, fixed occupancy conversion, and effective
 * deadline cutoffs. All functions are total over their inputs and report
 * unresolvable local times as structured issues instead of guessing an offset.
 */
internal object TimeResolution {

    fun effectiveCutoff(deadline: Deadline, zone: TimeZone): Instant = when (deadline) {
        is Deadline.Exact -> deadline.at
        is Deadline.DateOnly -> deadline.date.plus(1, DateTimeUnit.DAY).atStartOfDayIn(zone)
    }

    /**
     * Resolves a local wall-clock time in [zone]. Returns null when the local
     * value is ambiguous (repeated during a fall-back transition) or nonexistent
     * (skipped during a spring-forward transition). The offset is never guessed.
     */
    fun resolveSafe(local: LocalDateTime, zone: TimeZone): Instant? {
        val selected = local.toInstant(zone)
        if (selected.toLocalDateTime(zone) != local) return null
        val offset = zone.offsetAt(selected).totalSeconds
        val alternatives = listOf(
            zone.offsetAt(selected - 48.hours).totalSeconds,
            zone.offsetAt(selected + 48.hours).totalSeconds,
        )
        val ambiguous = alternatives.any { alternate ->
            alternate != offset && (selected + (offset - alternate).seconds).toLocalDateTime(zone) == local
        }
        return if (ambiguous) null else selected
    }

    /**
     * Expands configured weekly availability over the planning horizon clipped to
     * the future window. The result is the coalesced union of all windows because
     * availability is a set of time. Returns null when any occurrence cannot be
     * resolved without guessing an offset; the issue list explains why.
     */
    fun availability(
        config: PlanningProfileConfiguration.Configured,
        futureStart: Instant,
        horizonEndExclusive: Instant,
        zone: TimeZone,
        issues: MutableList<PlannerIssue>,
    ): List<Interval>? {
        val futureBounds = Interval(futureStart, horizonEndExclusive)
        val values = mutableListOf<Interval>()
        var date = futureBounds.start.toLocalDateTime(zone).date
        val finalDate = futureBounds.endExclusive.toLocalDateTime(zone).date.plus(1, DateTimeUnit.DAY)
        while (date <= finalDate) {
            config.weeklyAvailability.filter { it.dayOfWeek == date.dayOfWeek }.forEach { window ->
                val start = resolveSafe(LocalDateTime(date, window.start), zone)
                val end = resolveSafe(LocalDateTime(date, window.endExclusive), zone)
                if (start == null || end == null) {
                    issues += PlannerIssue.TimeResolutionFailure("$date ${window.start}-${window.endExclusive}")
                } else {
                    clipInterval(Interval(start, end), futureBounds)?.let(values::add)
                }
            }
            date = date.plus(1, DateTimeUnit.DAY)
        }
        return if (issues.any { it is PlannerIssue.TimeResolutionFailure }) null else coalesceIntervals(values)
    }

    /**
     * Converts every non-FocusBlock participation input into fixed occupancy.
     * Returns null when a Floating range cannot be resolved in the profile zone.
     */
    fun fixedOccupancy(
        events: List<Event>,
        courseSessionRanges: List<ZonedTimeRange>,
        examRanges: List<ZonedTimeRange>,
        constraints: List<PlanningConstraint>,
        zone: TimeZone,
        policy: AllDayEventPolicy,
        issues: MutableList<PlannerIssue>,
    ): List<Interval>? {
        val values = mutableListOf<Interval>()
        events.forEach { event ->
            when (val placement = event.time) {
                is ZonedTimeRange -> values += Interval(placement.start, placement.endExclusive)
                is FloatingTimeRange -> {
                    val start = resolveSafe(placement.start, zone)
                    val end = resolveSafe(placement.endExclusive, zone)
                    if (start == null || end == null) {
                        issues += PlannerIssue.TimeResolutionFailure("$placement")
                    } else {
                        values += Interval(start, end)
                    }
                }
                is AllDayRange -> if (policy == AllDayEventPolicy.BLOCK_WHOLE_LOCAL_DAY) {
                    values += Interval(placement.startDate.atStartOfDayIn(zone), placement.endDateExclusive.atStartOfDayIn(zone))
                }
                else -> Unit
            }
        }
        courseSessionRanges.forEach { values += Interval(it.start, it.endExclusive) }
        examRanges.forEach { values += Interval(it.start, it.endExclusive) }
        constraints.filterIsInstance<PlanningConstraint.UnavailableWindow>().forEach {
            values += Interval(it.time.start, it.time.endExclusive)
        }
        return if (issues.any { it is PlannerIssue.TimeResolutionFailure }) null else values
    }

    fun scheduledCourseSessionRanges(sessions: List<dev.agenticscheduler.domain.academic.CourseSession>): List<ZonedTimeRange> =
        sessions.mapNotNull { (it.state as? CourseSessionState.Scheduled)?.time }

    fun exactExamRanges(exams: List<dev.agenticscheduler.domain.academic.Exam>): List<ZonedTimeRange> =
        exams.mapNotNull { (it.schedule as? ExamSchedule.Exact)?.time }
}
