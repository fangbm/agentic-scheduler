package dev.agenticscheduler.planner

import dev.agenticscheduler.domain.id.FocusBlockId
import dev.agenticscheduler.domain.id.TaskId
import dev.agenticscheduler.domain.planning.Flexibility
import dev.agenticscheduler.domain.planning.PlanningProfileConfiguration
import dev.agenticscheduler.domain.planning.PinState
import dev.agenticscheduler.domain.task.FocusBlock
import dev.agenticscheduler.domain.task.Task
import dev.agenticscheduler.domain.task.TaskStatus
import kotlin.time.Duration
import kotlin.time.Instant

/**
 * R2 planner-internal reservation model (D6R-001/002). Every existing
 * FocusBlock is represented by exactly one reservation until an authorized
 * replacement mutation for that exact block is committed, so no block can
 * disappear from semantic occupancy while it is temporarily excluded from a
 * search view. This type is planner-internal and never carries sync/history
 * metadata into the Domain.
 */
internal enum class ReservationAuthority {
    /** Started, PINNED, HARD, outside-horizon, or owned by an ineligible Task. Never moved. */
    FIXED,

    /** Future inside-horizon UNPINNED + FLEXIBLE for an eligible Task; same ID, same duration, time may move. */
    FLEXIBLE,

    /** Future inside-horizon UNPINNED + SOFT for an eligible Task; may move, resize, or be deleted. */
    SOFT,

    /** Planner-created SOFT + UNPINNED proposal. */
    NEW,
}

internal sealed interface ReservationSource {
    data class Existing(val id: FocusBlockId) : ReservationSource

    /** [ProposalKey] is deterministic per run; the application materializes real UUIDv7 IDs at apply time. */
    data class Proposed(val key: ProposalKey) : ReservationSource
}

internal data class ProposalKey(val taskId: TaskId, val sequence: Int) {
    fun canonicalText(): String = "${taskId.value}#$sequence"
}

internal data class Reservation(
    val taskId: TaskId,
    val range: Interval,
    val authority: ReservationAuthority,
    val source: ReservationSource,
    /** Range of the originating Active State FocusBlock, when this reservation replaces one. */
    val originalRange: Interval?,
) {
    val blockId: FocusBlockId?
        get() = (source as? ReservationSource.Existing)?.id

    /** Criterion 9 canonical identity text (PLN-015). */
    fun identityText(): String = when (val src = source) {
        is ReservationSource.Existing -> src.id.value
        is ReservationSource.Proposed -> src.key.canonicalText()
    }
}

/**
 * R3 task-local delta (D6R-003). A delta is evaluated against one immutable
 * accepted state; commit atomically produces the next state and reject simply
 * discards it. There is no cleanup-by-TaskId rollback anywhere in the engine.
 */
internal data class MutationWithCriteria(
    val mutation: FocusBlockMutation,
    val criteria: List<PlacementCriterion>,
)

internal data class TaskPlanDelta(
    val taskId: TaskId,
    val removedReservations: List<Reservation>,
    val addedReservations: List<Reservation>,
    val entries: List<MutationWithCriteria>,
    val issues: List<PlannerIssue>,
    /** Other Tasks whose reservations this delta displaced or removed (D6R-004). */
    val displacedTaskIds: Set<TaskId> = emptySet(),
)

/**
 * R2 accepted planning state. One semantic source of truth: derived views such
 * as free intervals, coverage, and planned completion are recomputed from
 * [reservations] on demand, so a rejected delta cannot leave a stale view.
 */
internal data class PlanningState(
    val reservations: List<Reservation>,
    val entries: List<MutationWithCriteria>,
    val issues: List<PlannerIssue>,
) {
    /** Atomic commit: accepted state + valid delta -> next accepted state. */
    fun plus(delta: TaskPlanDelta): PlanningState = PlanningState(
        reservations = reservations.filter { it !in delta.removedReservations } + delta.addedReservations,
        entries = entries + delta.entries,
        issues = issues + delta.issues,
    )

    /**
     * Invalidates the accepted contribution of [taskId] (D6R-003 closure) by
     * undoing the Task's accepted planner delta: every reservation the delta
     * replaced, resized, moved, or proposed is removed and the Task's normalized
     * original reservations are restored, so re-planning sees exactly the Active
     * State input again (D6R-001: an existing FocusBlock never disappears).
     */
    fun invalidateTask(taskId: TaskId, originals: List<Reservation>): PlanningState = PlanningState(
        reservations = reservations.filter { it.taskId != taskId } + originals,
        entries = entries,
        issues = issues,
    )
}

/**
 * R2 snapshot normalization (D6R-001/002). Every input FocusBlock becomes
 * exactly one reservation; authorized narrow cleanup is performed upfront as
 * Delete mutations for future SOFT + UNPINNED blocks of COMPLETED / CANCELLED /
 * known remaining == 0 Tasks. Dependency-blocked Tasks are not treated as
 * absent: their blocks keep FLEXIBLE/SOFT authority until an accepted delta
 * replaces them.
 */
internal object SnapshotNormalization {

    data class Normalized(
        val state: PlanningState,
        val eligibleTasks: List<Task>,
        val tasksById: Map<TaskId, Task>,
    )

    fun normalize(snapshot: PlanningSnapshot, config: PlanningProfileConfiguration.Configured): Normalized {
        val tasksById = snapshot.tasks.associateBy { it.id }
        val horizonEnd = snapshot.horizon.endExclusive
        val reservations = mutableListOf<Reservation>()
        val mutations = mutableListOf<FocusBlockMutation>()
        val issues = mutableListOf<PlannerIssue>()

        snapshot.tasks.forEach { task ->
            if ((task.status == TaskStatus.OPEN || task.status == TaskStatus.IN_PROGRESS) && task.effort.remaining == null) {
                issues += PlannerIssue.UnknownRemainingEffort(task.id)
            }
        }

        snapshot.focusBlocks.forEach { block ->
            val task = tasksById[block.taskId]
            val range = Interval(block.time.start, block.time.endExclusive)
            val started = block.time.start < snapshot.referenceNow
            val outsideHorizon = block.time.endExclusive <= snapshot.horizon.start || block.time.start >= horizonEnd
            val cleanupEligible = task != null && !started && !outsideHorizon &&
                block.flexibility == Flexibility.SOFT && block.pinState == PinState.UNPINNED &&
                (task.status == TaskStatus.COMPLETED || task.status == TaskStatus.CANCELLED || task.effort.remaining == Duration.ZERO)
            if (cleanupEligible) {
                mutations += FocusBlockMutation.Delete(block.id, block.taskId)
                return@forEach
            }
            val authority = when {
                started || block.pinState == PinState.PINNED || block.flexibility == Flexibility.HARD || outsideHorizon ->
                    ReservationAuthority.FIXED
                task == null || !task.isAutomaticPlacementEligible() -> ReservationAuthority.FIXED
                block.flexibility == Flexibility.FLEXIBLE -> ReservationAuthority.FLEXIBLE
                block.flexibility == Flexibility.SOFT -> ReservationAuthority.SOFT
                else -> ReservationAuthority.FIXED
            }
            reservations += Reservation(
                taskId = block.taskId,
                range = range,
                authority = authority,
                source = ReservationSource.Existing(block.id),
                originalRange = range,
            )
        }

        return Normalized(
            state = PlanningState(
                reservations,
                entries = mutations.map { MutationWithCriteria(it, listOf(PlacementCriterion.CANONICAL_IDENTITY)) },
                issues = issues,
            ),
            eligibleTasks = snapshot.tasks.filter { it.isAutomaticPlacementEligible() },
            tasksById = tasksById,
        )
    }

    fun Task.isAutomaticPlacementEligible(): Boolean {
        val remaining = effort.remaining
        return (status == TaskStatus.OPEN || status == TaskStatus.IN_PROGRESS) && remaining != null && remaining > Duration.ZERO
    }

    fun futureCoverageOf(reservations: List<Reservation>, taskId: TaskId, referenceNow: Instant): Duration =
        reservations.filter { it.taskId == taskId && it.range.start >= referenceNow }
            .fold(Duration.ZERO) { total, reservation -> total + (reservation.range.endExclusive - reservation.range.start) }
}
