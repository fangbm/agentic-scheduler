package dev.agenticscheduler.planner

import dev.agenticscheduler.domain.planning.Flexibility
import dev.agenticscheduler.domain.planning.PinState
import dev.agenticscheduler.domain.planning.PlanningProfileConfiguration
import dev.agenticscheduler.domain.task.FocusBlock
import dev.agenticscheduler.domain.time.ZonedTimeRange
import kotlin.math.abs
import kotlinx.collections.immutable.toImmutableList
import kotlinx.datetime.TimeZone

/**
 * R8 bounded, deterministic, minimal-disruption repair (PLN-016, D6R-009).
 *
 * Local Reflow shares the pure legality/time primitives and the one dependency
 * truth ([EffortTruth]) with Full Replan. It never reuses displacement
 * behavior: unrelated blocks stay fixed occupancy, affected blocks are
 * move-only with identity and duration preserved, placements are committed
 * provisionally in deterministic order for the dependency checks of later
 * affected blocks, and any failure returns Infeasible with no mutations.
 */
internal class LocalReflowEngine(
    private val snapshot: PlanningSnapshot,
    private val config: PlanningProfileConfiguration.Configured,
    private val request: LocalReflowRequest,
) {
    private val zone: TimeZone get() = config.timeZone
    private val askAuthorized = snapshot.askOverflowAuthorizedTaskIds.toSet()
    private val tasksById = snapshot.tasks.associateBy { it.id }

    /** Provisional placements keep later affected blocks' dependency checks correct. */
    private val provisional = mutableMapOf<dev.agenticscheduler.domain.id.FocusBlockId, Interval>()

    fun run(): PlannerResult {
        // Validation must run before any Interval or derived view is built (P0: an
        // invalid snapshot must yield InvalidInput, never a throw).
        SnapshotValidation.invalidReason(snapshot)?.let {
            return PlannerResult.InvalidInput(listOf(it).toImmutableList())
        }
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
        // PLN-007 wording: a block is already started only when its start is strictly
        // before referenceNow; start == referenceNow is still future and reflowable.
        val immovable = affected.filter { it.time.start < snapshot.referenceNow || !it.isReflowMovable() }
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
        val fixedBlocks = snapshot.focusBlocks.filter { it.id !in affectedIds }.map { Interval(it.time.start, it.time.endExclusive) }
        val free = subtractIntervals(
            availability.mapNotNull { clipInterval(it, searchBounds) },
            fixed + fixedBlocks,
        ).toMutableList()

        val moves = mutableListOf<FocusBlockMutation>()

        affected.forEach { block ->
            val duration = block.time.endExclusive - block.time.start
            val task = tasksById[block.taskId]
            // One dependency truth: a blocked prerequisite (cancelled, unknown or zero
            // remaining, not fully planned) leaves no legal placement for the dependent
            // block, so the whole reflow is Infeasible (PLN-009 / PLN-016).
            val dependencyFloor = when (task) {
                null -> null
                else -> {
                    val blocker = EffortTruth.firstBlockingPrerequisite(
                        task, snapshot.dependencies, tasksById, provisionalReservations(), snapshot.referenceNow,
                    )
                    if (blocker != null) {
                        return PlannerResult.Infeasible(
                            listOf(PlannerIssue.DependencyBlocked(block.taskId, blocker.id)).toImmutableList(),
                        )
                    }
                    EffortTruth.dependencyEarliestStart(
                        task, snapshot.dependencies, tasksById, provisionalReservations(), snapshot.referenceNow,
                    )
                }
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

    /**
     * The provisional reservation view: every future FocusBlock at its original
     * range except already-reflowed blocks at their provisional placements. This
     * is what dependency readiness is evaluated against.
     */
    private fun provisionalReservations(): List<Reservation> = snapshot.focusBlocks.map { block ->
        val original = Interval(block.time.start, block.time.endExclusive)
        Reservation(
            taskId = block.taskId,
            range = provisional[block.id] ?: original,
            authority = ReservationAuthority.FIXED,
            source = ReservationSource.Existing(block.id),
            originalRange = original,
        )
    }

    private fun isLegalFor(task: dev.agenticscheduler.domain.task.Task?, candidate: Interval): Boolean {
        if (task == null) return true
        val deadline = task.deadline ?: return true
        val cutoff = TimeResolution.effectiveCutoff(deadline.deadline, zone)
        if (candidate.endExclusive <= cutoff) return true
        return EffortTruth.overflowAuthorized(task, askAuthorized)
    }

    private fun FocusBlock.isReflowMovable(): Boolean =
        pinState == PinState.UNPINNED && (flexibility == Flexibility.FLEXIBLE || flexibility == Flexibility.SOFT)
}
