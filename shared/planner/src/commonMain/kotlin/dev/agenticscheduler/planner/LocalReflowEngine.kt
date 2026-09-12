package dev.agenticscheduler.planner

import dev.agenticscheduler.domain.planning.Flexibility
import dev.agenticscheduler.domain.planning.PinState
import dev.agenticscheduler.domain.planning.PlanningProfileConfiguration
import dev.agenticscheduler.domain.task.FocusBlock
import dev.agenticscheduler.domain.task.Task
import dev.agenticscheduler.domain.task.TaskStatus
import dev.agenticscheduler.domain.time.ZonedTimeRange
import kotlin.math.abs
import kotlin.time.Duration
import kotlin.time.Instant
import kotlinx.collections.immutable.toImmutableList
import kotlinx.datetime.TimeZone

/**
 * R8 bounded, deterministic, minimal-disruption repair (PLN-016, D6R-009).
 *
 * Local Reflow shares only the pure legality/time primitives with Full Replan.
 * It never reuses displacement behavior: unrelated blocks stay fixed occupancy,
 * affected blocks are move-only with identity and duration preserved, placements
 * are committed provisionally in deterministic order for the dependency checks
 * of later affected blocks, and any failure returns Infeasible with no mutations.
 */
internal class LocalReflowEngine(
    private val snapshot: PlanningSnapshot,
    private val config: PlanningProfileConfiguration.Configured,
    private val request: LocalReflowRequest,
) {
    private val zone: TimeZone get() = config.timeZone
    private val askAuthorized = snapshot.askOverflowAuthorizedTaskIds.toSet()

    /** Provisional placements keep later affected blocks' dependency checks correct. */
    private val provisional = mutableMapOf<dev.agenticscheduler.domain.id.FocusBlockId, Interval>()

    fun run(): PlannerResult {
        val issues = mutableListOf<PlannerIssue>()
        val availability = TimeResolution.availability(
            config,
            maxOf(snapshot.horizon.start, snapshot.referenceNow),
            snapshot.horizon.endExclusive,
            zone,
            issues,
        )
        val fixed = TimeResolution.fixedOccupancy(
            events = snapshot.events,
            courseSessionRanges = TimeResolution.scheduledCourseSessionRanges(snapshot.courseSessions),
            examRanges = TimeResolution.exactExamRanges(snapshot.exams),
            constraints = snapshot.constraints,
            zone = zone,
            policy = config.allDayEventPolicy,
            issues = issues,
        )
        if (availability == null || fixed == null) {
            return PlannerResult.InvalidInput(issues.distinct().sortedWith(IssueOrder.comparator).toImmutableList())
        }

        val affected = affectedBlocks()
        val immovable = affected.filter { it.time.start <= snapshot.referenceNow || !it.isReflowMovable() }
        if (immovable.isNotEmpty()) {
            return PlannerResult.Infeasible(immovable.map { PlannerIssue.ImmovableConflict(it.id) }.toImmutableList())
        }

        val searchStart = maxOf(request.searchWindow.start, snapshot.referenceNow, snapshot.horizon.start)
        val searchEnd = minOf(request.searchWindow.endExclusive, snapshot.horizon.endExclusive)
        if (searchStart >= searchEnd) {
            return PlannerResult.InvalidInput(
                listOf(PlannerIssue.InvalidSnapshot("reflow search window does not intersect the future horizon")).toImmutableList(),
            )
        }
        val searchBounds = Interval(searchStart, searchEnd)

        val affectedIds = affected.map { it.id }.toSet()
        val tasksById = snapshot.tasks.associateBy { it.id }
        val fixedBlocks = snapshot.focusBlocks.filter { it.id !in affectedIds }.map { Interval(it.time.start, it.time.endExclusive) }
        val free = subtractIntervals(
            availability.mapNotNull { clipInterval(it, searchBounds) },
            fixed + fixedBlocks,
        ).toMutableList()

        // Provisional placements keep later affected blocks' dependency checks correct.
        val moves = mutableListOf<FocusBlockMutation>()

        affected.forEach { block ->
            val duration = block.time.endExclusive - block.time.start
            val task = tasksById[block.taskId]
            val dependencyFloor = task?.let { current ->
                dependenciesOf(current).maxOrNull()
            }
            val placement = free.mapNotNull { interval ->
                if (interval.endExclusive - interval.start < duration) {
                    null
                } else {
                    val latest = interval.endExclusive - duration
                    val clamped = block.time.start.coerceIn(interval.start, latest)
                    val start = maxOf(clamped, dependencyFloor ?: interval.start)
                    val candidate = if (start > latest) null else Interval(start, start + duration)
                    candidate?.takeIf { isLegalFor(task, it) }
                }
            }.sortedWith(
                compareBy({ abs((it.start - block.time.start).inWholeMilliseconds) }, { it.start }),
            ).firstOrNull() ?: return PlannerResult.Infeasible(
                listOf(PlannerIssue.NoLegalAvailability(block.taskId)).toImmutableList(),
            )

            moves += FocusBlockMutation.Move(block.id, block.taskId, ZonedTimeRange(placement.start, placement.endExclusive, zone))
            subtractIntervals(free, listOf(placement)).let { replaced ->
                free.clear()
                free.addAll(replaced)
            }
            provisional[block.id] = placement
        }

        val ordered = moves.toImmutableList()
        return PlannerResult.Success(
            mutations = ordered,
            issues = emptyList<PlannerIssue>().toImmutableList(),
            explanations = ordered
                .map { PlannerExplanation(it.taskId, it, listOf(PlacementCriterion.MINIMAL_MOVEMENT, PlacementCriterion.CANONICAL_IDENTITY).toImmutableList()) }
                .toImmutableList(),
        )
    }

    private fun affectedBlocks(): List<FocusBlock> = snapshot.focusBlocks
        .filter { block ->
            block.id in request.affectedFocusBlockIds ||
                (block.time.start >= snapshot.referenceNow && request.disruptedRanges.any { disrupted -> block.time.overlaps(disrupted) })
        }
        .sortedWith(compareBy({ it.time.start }, { it.id.value }))

    private fun dependenciesOf(task: Task): List<Instant> = snapshot.dependencies
        .filter { it.dependentTaskId == task.id }
        .mapNotNull { dependency ->
            val prerequisite = snapshot.tasks.firstOrNull { it.id == dependency.prerequisiteTaskId } ?: return@mapNotNull null
            when {
                prerequisite.status == TaskStatus.COMPLETED -> snapshot.referenceNow
                else -> provisionalCompletion(prerequisite)
            }
        }

    /**
     * Planned completion of a prerequisite using the provisional placements of
     * already-reflowed blocks, so a moved prerequisite constrains its dependent.
     */
    private fun provisionalCompletion(prerequisite: Task): Instant? {
        val required = prerequisite.effort.remaining ?: return null
        if (prerequisite.status == TaskStatus.CANCELLED || prerequisite.status == TaskStatus.COMPLETED) return null
        if (required <= Duration.ZERO) return null
        val future = snapshot.focusBlocks
            .filter { it.taskId == prerequisite.id && it.time.start >= snapshot.referenceNow }
            .map { provisional[it.id] ?: Interval(it.time.start, it.time.endExclusive) }
            .sortedBy { it.start }
        var covered = Duration.ZERO
        future.forEach { range ->
            val duration = range.endExclusive - range.start
            if (covered + duration >= required) return range.start + (required - covered)
            covered += duration
        }
        return null
    }

    private fun isLegalFor(task: Task?, candidate: Interval): Boolean {
        if (task == null) return true
        val deadline = task.deadline ?: return true
        val cutoff = TimeResolution.effectiveCutoff(deadline.deadline, zone)
        if (candidate.endExclusive <= cutoff) return true
        val overflow = EffortTruth.overflowAuthorized(task, askAuthorized)
        return overflow
    }

    private fun FocusBlock.isReflowMovable(): Boolean =
        pinState == PinState.UNPINNED && (flexibility == Flexibility.FLEXIBLE || flexibility == Flexibility.SOFT)
}
