package dev.agenticscheduler.planner

import dev.agenticscheduler.domain.academic.CourseSession
import dev.agenticscheduler.domain.academic.Exam
import dev.agenticscheduler.domain.event.Event
import dev.agenticscheduler.domain.id.FocusBlockId
import dev.agenticscheduler.domain.id.TaskId
import dev.agenticscheduler.domain.planning.Flexibility
import dev.agenticscheduler.domain.planning.PinState
import dev.agenticscheduler.domain.planning.PlanningProfile
import dev.agenticscheduler.domain.task.Task
import dev.agenticscheduler.domain.task.TaskDependency
import dev.agenticscheduler.domain.time.ZonedTimeRange
import kotlin.time.Duration
import kotlin.time.Instant
import kotlinx.collections.immutable.ImmutableList

enum class ConstraintSource { USER, PROFILE, AGENT_INTERPRETED, SYSTEM }

sealed interface PlanningConstraint {
    val source: ConstraintSource
    data class UnavailableWindow(val time: ZonedTimeRange, override val source: ConstraintSource) : PlanningConstraint
}

data class PlanningHorizon(val start: Instant, val endExclusive: Instant) {
    init { require(start < endExclusive) { "Planning horizon must be positive." } }
}

/** Complete, explicit input to the pure deterministic planner. */
data class PlanningSnapshot(
    val referenceNow: Instant,
    val horizon: PlanningHorizon,
    val profile: PlanningProfile,
    val tasks: ImmutableList<Task>,
    val dependencies: ImmutableList<TaskDependency>,
    val focusBlocks: ImmutableList<dev.agenticscheduler.domain.task.FocusBlock>,
    val events: ImmutableList<Event>,
    val courseSessions: ImmutableList<CourseSession>,
    val exams: ImmutableList<Exam>,
    val constraints: ImmutableList<PlanningConstraint>,
    val askOverflowAuthorizedTaskIds: ImmutableList<TaskId>,
)

/** Planner does not generate IDs; application materializes drafts with UUIDv7. */
data class FocusBlockDraft(
    val taskId: TaskId,
    val time: ZonedTimeRange,
    val flexibility: Flexibility = Flexibility.SOFT,
    val pinState: PinState = PinState.UNPINNED,
)

sealed interface FocusBlockMutation {
    val taskId: TaskId
    data class Create(val draft: FocusBlockDraft) : FocusBlockMutation { override val taskId = draft.taskId }
    data class Move(val id: FocusBlockId, override val taskId: TaskId, val time: ZonedTimeRange) : FocusBlockMutation
    data class Resize(val id: FocusBlockId, override val taskId: TaskId, val time: ZonedTimeRange) : FocusBlockMutation
    data class Delete(val id: FocusBlockId, override val taskId: TaskId) : FocusBlockMutation
}

data class LocalReflowRequest(
    val affectedFocusBlockIds: ImmutableList<FocusBlockId>,
    val disruptedRanges: ImmutableList<ZonedTimeRange>,
    val searchWindow: ZonedTimeRange,
)

sealed interface PlannerIssue {
    data object ProfileUnconfigured : PlannerIssue
    data class InvalidSnapshot(val reason: String) : PlannerIssue
    data class TimeResolutionFailure(val localValue: String) : PlannerIssue
    data class UnknownRemainingEffort(val taskId: TaskId) : PlannerIssue
    data class NoLegalAvailability(val taskId: TaskId) : PlannerIssue
    data class DependencyBlocked(val taskId: TaskId, val prerequisiteTaskId: TaskId) : PlannerIssue
    data class OverflowApprovalRequired(val taskId: TaskId) : PlannerIssue
    data class HardDeadlineShortfall(val taskId: TaskId, val remaining: Duration) : PlannerIssue
    data class UnscheduledEffort(val taskId: TaskId, val remaining: Duration) : PlannerIssue
    data class OverallocatedPlannedEffort(val taskId: TaskId) : PlannerIssue
    data class ImmovableConflict(val focusBlockId: FocusBlockId) : PlannerIssue
}

/** Records the lexicographic decision facts rather than hiding weighted scoring. */
data class PlannerExplanation(
    val taskId: TaskId,
    val mutation: FocusBlockMutation,
    val criteria: ImmutableList<PlacementCriterion>,
)

enum class PlacementCriterion {
    DEADLINE_LEGALITY,
    LATENESS,
    PRESERVED_EXISTING_PLACEMENT,
    MINIMAL_MOVEMENT,
    MINIMAL_CONTEXT_SWITCHES,
    CHUNK_DURATION,
    EARLIER_START,
    EARLIER_END,
    CANONICAL_IDENTITY,
}

sealed interface PlannerResult {
    data class Success(
        val mutations: ImmutableList<FocusBlockMutation>,
        val issues: ImmutableList<PlannerIssue>,
        val explanations: ImmutableList<PlannerExplanation>,
    ) : PlannerResult
    data class Infeasible(val issues: ImmutableList<PlannerIssue>) : PlannerResult
    data class InvalidInput(val issues: ImmutableList<PlannerIssue>) : PlannerResult
}
