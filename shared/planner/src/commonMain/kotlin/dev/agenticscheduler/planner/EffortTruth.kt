package dev.agenticscheduler.planner

import dev.agenticscheduler.domain.id.TaskId
import dev.agenticscheduler.domain.planning.DeadlinePolicy
import dev.agenticscheduler.domain.task.Task
import dev.agenticscheduler.domain.task.TaskDependency
import dev.agenticscheduler.domain.task.TaskStatus
import kotlin.time.Duration
import kotlin.time.Instant
import kotlinx.datetime.TimeZone

/**
 * R3 one authoritative effort / coverage / completion / dependency model
 * (D6R-005, PLN-006, PLN-009, PLN-014). Every derived value is a pure function
 * of the accepted reservation list, so coverage, planned completion, and
 * dependency readiness can never disagree with each other.
 *
 * Task.effort.remaining stays authoritative: remaining is never derived from
 * estimated - completed, and started blocks never reduce it.
 */
internal object EffortTruth {

    /**
     * General future planned coverage: retained future existing reservations
     * plus accepted proposed future reservations for the Task.
     */
    fun futureCoverage(reservations: List<Reservation>, taskId: TaskId, referenceNow: Instant): Duration =
        reservations.filter { it.taskId == taskId && it.range.start >= referenceNow }
            .fold(Duration.ZERO) { total, reservation -> total + (reservation.range.endExclusive - reservation.range.start) }

    /**
     * HARD deadline satisfaction (D6R-006): cumulative accepted future planned
     * coverage completed by the effective cutoff. A block or portion of
     * coverage completing after the cutoff never satisfies the deadline.
     */
    fun coverageCompletedByCutoff(reservations: List<Reservation>, taskId: TaskId, referenceNow: Instant, cutoff: Instant): Duration =
        reservations.filter { it.taskId == taskId && it.range.start >= referenceNow }
            .fold(Duration.ZERO) { total, reservation ->
                val end = if (reservation.range.endExclusive < cutoff) reservation.range.endExclusive else cutoff
                if (end > reservation.range.start) total + (end - reservation.range.start) else total
            }

    /**
     * Planned completion for an OPEN / IN_PROGRESS prerequisite with known
     * positive remaining effort: the earliest Instant at which cumulative
     * accepted future coverage reaches authoritative remaining effort.
     *
     * Returns null (never a guessed instant) for CANCELLED, remaining == null,
     * remaining == 0 without COMPLETED status, and partially planned
     * prerequisites.
     */
    fun plannedCompletion(
        task: Task,
        reservations: List<Reservation>,
        referenceNow: Instant,
    ): Instant? {
        val required = task.effort.remaining ?: return null
        if (task.status == TaskStatus.CANCELLED || task.status == TaskStatus.COMPLETED) return null
        if (required <= Duration.ZERO) return null
        val future = reservations.filter { it.taskId == task.id && it.range.start >= referenceNow }
            .sortedWith(compareBy({ it.range.start }, { it.identityText() }))
        var covered = Duration.ZERO
        future.forEach { reservation ->
            val duration = reservation.range.endExclusive - reservation.range.start
            if (covered + duration >= required) {
                return reservation.range.start + (required - covered)
            }
            covered += duration
        }
        return null
    }

    /**
     * Dependency readiness of [task] given the accepted state. Returns the
     * effective earliest start Instant when every prerequisite is either
     * COMPLETED or has a planned completion, or null when at least one
     * prerequisite blocks the Task. [tasksById] resolves prerequisites.
     */
    fun dependencyEarliestStart(
        task: Task,
        dependencies: List<TaskDependency>,
        tasksById: Map<dev.agenticscheduler.domain.id.TaskId, Task>,
        reservations: List<Reservation>,
        referenceNow: Instant,
    ): Instant? {
        var earliest: Instant? = null
        dependencies.filter { it.dependentTaskId == task.id }.forEach { dependency ->
            val prerequisite = tasksById[dependency.prerequisiteTaskId] ?: return null
            when {
                prerequisite.status == TaskStatus.COMPLETED -> Unit
                else -> {
                    val completion = plannedCompletion(prerequisite, reservations, referenceNow) ?: return null
                    earliest = maxOf(earliest ?: completion, completion)
                }
            }
        }
        return earliest ?: referenceNow
    }

    /** First unsatisfied prerequisite in canonical order, for structured issue reporting. */
    fun firstBlockingPrerequisite(
        task: Task,
        dependencies: List<TaskDependency>,
        tasksById: Map<dev.agenticscheduler.domain.id.TaskId, Task>,
        reservations: List<Reservation>,
        referenceNow: Instant,
    ): Task? = dependencies.filter { it.dependentTaskId == task.id }
        .mapNotNull { tasksById[it.prerequisiteTaskId] }
        .sortedBy { it.id.value }
        .firstOrNull { prerequisite ->
            prerequisite.status != TaskStatus.COMPLETED &&
                plannedCompletion(prerequisite, reservations, referenceNow) == null
        }

    fun hasHardDeadline(task: Task): Boolean = task.deadline?.policy == DeadlinePolicy.HARD

    fun effectiveCutoff(task: Task, zone: TimeZone): Instant? =
        task.deadline?.let { TimeResolution.effectiveCutoff(it.deadline, zone) }

    /**
     * Whether automatic overflow past the deadline is legal for this run
     * (PLN-010). HARD deadlines never overflow; NORMAL follows OverflowPolicy
     * with ephemeral per-run ASK authorization.
     */
    fun overflowAuthorized(task: Task, askOverflowAuthorizedTaskIds: Set<dev.agenticscheduler.domain.id.TaskId>): Boolean {
        val deadline = task.deadline ?: return true
        if (deadline.policy == DeadlinePolicy.HARD) return false
        return when (deadline.overflowPolicy) {
            dev.agenticscheduler.domain.planning.OverflowPolicy.ALLOW -> true
            dev.agenticscheduler.domain.planning.OverflowPolicy.ASK -> task.id in askOverflowAuthorizedTaskIds
            dev.agenticscheduler.domain.planning.OverflowPolicy.NEVER -> false
        }
    }
}
