package dev.agenticscheduler.planner

import dev.agenticscheduler.domain.id.FocusBlockId
import dev.agenticscheduler.domain.id.PlanningProfileId
import dev.agenticscheduler.domain.id.TaskDependencyId
import dev.agenticscheduler.domain.id.TaskId
import dev.agenticscheduler.domain.planning.AllDayEventPolicy
import dev.agenticscheduler.domain.planning.Deadline
import dev.agenticscheduler.domain.planning.DeadlinePolicy
import dev.agenticscheduler.domain.planning.Flexibility
import dev.agenticscheduler.domain.planning.OverflowPolicy
import dev.agenticscheduler.domain.planning.PinState
import dev.agenticscheduler.domain.planning.PlanningProfile
import dev.agenticscheduler.domain.planning.PlanningProfileConfiguration
import dev.agenticscheduler.domain.planning.TaskDeadline
import dev.agenticscheduler.domain.planning.WeeklyAvailabilityWindow
import dev.agenticscheduler.domain.task.FocusBlock
import dev.agenticscheduler.domain.task.Task
import dev.agenticscheduler.domain.task.TaskDependency
import dev.agenticscheduler.domain.task.TaskEffort
import dev.agenticscheduler.domain.task.TaskPriority
import dev.agenticscheduler.domain.task.TaskStatus
import dev.agenticscheduler.domain.time.ZonedTimeRange
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

/**
 * Third review round regressions: post-arrangement dependency truth in
 * relocations, accepted-state invalidation of displaced owners and their
 * dependents (D6R-003 closure), authoritative HARD validation for blocked
 * Tasks, multi-reservation displacement backtracking, and PLN-015-ordered
 * relocation with real decision traces.
 */
class PlannerReviewRound3Test {

    // review-r3-1 (D6R-003 closure): C is scheduled after B's completion; a later
    // higher-ranked A displaces B past C. C's accepted placement is invalidated and
    // re-planned against the new completion instead of surviving the violation.
    @Test
    fun `displacing a prerequisite invalidates and replans the dependent`() {
        val owner = task(2, 1.hours)
        val ownerBlock = FocusBlock(FocusBlockId(id(6)), owner.id, range("09:00", "10:00"), Flexibility.FLEXIBLE, PinState.UNPINNED)
        val dependent = task(3, 1.hours)
        val demand = task(1, 1.hours, deadline = TaskDeadline(Deadline.Exact(at("10:30"), TimeZone.UTC), DeadlinePolicy.HARD, OverflowPolicy.NEVER), priority = TaskPriority.HIGH)
        val barrier = FocusBlock(FocusBlockId(id(9)), TaskId(id(5)), range("12:00", "15:00"), Flexibility.HARD, PinState.UNPINNED)
        val result = assertIs<PlannerResult.Success>(DeterministicPlanner().fullReplan(snapshot(
            tasks = listOf(demand, owner, dependent, task(5, Duration.ZERO)),
            focusBlocks = listOf(ownerBlock, barrier),
            dependencies = listOf(TaskDependency(TaskDependencyId(id(8)), owner.id, dependent.id)),
            horizonEnd = "2026-01-05T15:00:00Z",
        )))
        val create = assertIs<FocusBlockMutation.Create>(result.mutations.filterIsInstance<FocusBlockMutation.Create>().filter { it.draft.taskId == demand.id }.single())
        assertEquals(range("09:00", "10:00"), create.draft.time, "the HARD demand takes the displaced block's slot")
        val ownerMoves = result.mutations.filterIsInstance<FocusBlockMutation.Move>().filter { it.taskId == owner.id }
        assertEquals(1, ownerMoves.size)
        assertEquals(range("10:00", "11:00"), ownerMoves.single().time, "the displaced block relocates into the freed slot")
        // The closure re-planned the dependent: its invalidated [10:00,11:00) Create
        // is replaced by one that starts at the moved prerequisite's completion.
        val dependentCreates = result.mutations.filterIsInstance<FocusBlockMutation.Create>().filter { it.draft.taskId == dependent.id }
        assertEquals(1, dependentCreates.size, "exactly one net Create for the re-planned dependent")
        assertEquals(range("11:00", "12:00"), dependentCreates.single().draft.time, "the dependent starts at the moved prerequisite's completion")
    }

    // review-r3-2 (D6R-006): a HARD Task whose prerequisite is forever blocked never
    // reaches planTask, yet its remaining effort cannot be covered by the cutoff -
    // the run is Infeasible with HardDeadlineShortfall, not a Success.
    @Test
    fun `dependency blocked hard task yields infeasible with shortfall`() {
        val prerequisite = Task(TaskId(id(4)), "Task 4", TaskStatus.OPEN, TaskPriority.NORMAL, TaskEffort(null, Duration.ZERO, null), null)
        val hardTask = task(1, 2.hours, deadline = TaskDeadline(Deadline.Exact(at("13:00"), TimeZone.UTC), DeadlinePolicy.HARD, OverflowPolicy.ALLOW))
        val result = DeterministicPlanner().fullReplan(snapshot(
            tasks = listOf(hardTask, prerequisite),
            dependencies = listOf(TaskDependency(TaskDependencyId(id(8)), prerequisite.id, hardTask.id)),
        ))
        val infeasible = assertIs<PlannerResult.Infeasible>(result)
        assertEquals(
            true,
            infeasible.issues.any { it is PlannerIssue.HardDeadlineShortfall && it.taskId == hardTask.id },
            "the blocked HARD Task's uncovered demand must surface as a shortfall",
        )
    }

    // review-r3-3 (D6R-004): two displaced FLEXIBLE blocks whose greedy relocations
    // conflict - a finite backtracking search must find the feasible arrangement
    // instead of declaring the candidate infeasible.
    @Test
    fun `multi reservation displacement backtracks to a feasible arrangement`() {
        val demand = task(1, 90.minutes, deadline = TaskDeadline(Deadline.Exact(at("14:00"), TimeZone.UTC), DeadlinePolicy.HARD, OverflowPolicy.NEVER), priority = TaskPriority.HIGH)
        val first = FocusBlock(FocusBlockId(id(6)), task(2, 1.hours).id, range("11:00", "12:00"), Flexibility.FLEXIBLE, PinState.UNPINNED)
        val secondOwner = task(3, 1.hours, deadline = TaskDeadline(Deadline.Exact(at("14:00"), TimeZone.UTC), DeadlinePolicy.NORMAL, OverflowPolicy.NEVER))
        val second = FocusBlock(FocusBlockId(id(7)), secondOwner.id, range("12:00", "13:00"), Flexibility.FLEXIBLE, PinState.UNPINNED)
        val barrier = FocusBlock(FocusBlockId(id(9)), TaskId(id(5)), range("09:00", "11:00"), Flexibility.HARD, PinState.UNPINNED)
        val profile = profile(minimum = 90.minutes, preferred = 90.minutes, maximum = 2.hours)
        val result = assertIs<PlannerResult.Success>(DeterministicPlanner().fullReplan(snapshot(
            tasks = listOf(demand, task(2, 1.hours), secondOwner, task(5, Duration.ZERO)),
            focusBlocks = listOf(first, second, barrier),
            profile = profile,
            horizonEnd = "2026-01-05T15:00:00Z",
        )))
        val create = assertIs<FocusBlockMutation.Create>(result.mutations.filterIsInstance<FocusBlockMutation.Create>().single())
        assertEquals(range("11:00", "12:30"), create.draft.time, "the backtracked arrangement makes the candidate feasible")
        val moves = result.mutations.filterIsInstance<FocusBlockMutation.Move>().associateBy { it.id }
        assertEquals(range("14:00", "15:00"), moves.getValue(first.id).time, "the first displaced block backtracks out of the greedy slot")
        assertEquals(range("12:30", "13:30"), moves.getValue(second.id).time, "the deadline-bound second block takes its only legal relocation")
    }

    // review-r3-4 (PLN-015 / D6R-008): a displaced SOFT block's relocation is ranked
    // by the frozen comparator - a before-deadline RESIZE beats the closer
    // same-duration overflow relocation - and the explanation records the deciding
    // criterion instead of a static list.
    @Test
    fun `soft relocation obeys pln-015 and carries the decision trace`() {
        val owner = task(2, 30.minutes, deadline = TaskDeadline(Deadline.Exact(at("12:30"), TimeZone.UTC), DeadlinePolicy.NORMAL, OverflowPolicy.ALLOW))
        val soft = FocusBlock(FocusBlockId(id(6)), owner.id, range("12:30", "13:30"), Flexibility.SOFT, PinState.UNPINNED)
        val demand = task(1, 90.minutes, priority = TaskPriority.HIGH)
        val barrier = FocusBlock(FocusBlockId(id(9)), TaskId(id(5)), range("09:00", "12:00"), Flexibility.HARD, PinState.UNPINNED)
        val result = assertIs<PlannerResult.Success>(DeterministicPlanner().fullReplan(snapshot(
            tasks = listOf(demand, owner, task(5, Duration.ZERO)),
            focusBlocks = listOf(soft, barrier),
        )))
        val create = assertIs<FocusBlockMutation.Create>(result.mutations.filterIsInstance<FocusBlockMutation.Create>().single())
        assertEquals(range("12:30", "14:00"), create.draft.time, "the demand takes the displaced block's slot")
        val resize = assertIs<FocusBlockMutation.Resize>(result.mutations.filterIsInstance<FocusBlockMutation.Resize>().single())
        assertEquals(range("12:00", "12:30"), resize.time, "the before-deadline identity-preserving resize beats the closer overflow move")
        assertEquals(
            true,
            result.explanations.single { it.mutation is FocusBlockMutation.Resize && it.mutation.id == soft.id }.criteria.contains(PlacementCriterion.DEADLINE_LEGALITY),
            "the relocation explanation derives from the actual decision",
        )
    }

    private fun task(
        number: Int,
        remaining: Duration,
        deadline: TaskDeadline? = null,
        status: TaskStatus = TaskStatus.OPEN,
        priority: TaskPriority = TaskPriority.NORMAL,
    ) = Task(TaskId(id(number)), "Task $number", status, priority, TaskEffort(remaining, Duration.ZERO, remaining), deadline)

    private fun profile(
        minimum: Duration = 1.hours,
        preferred: Duration = 1.hours,
        maximum: Duration = 2.hours,
    ) = PlanningProfile(
        PlanningProfileId(id(9)),
        "UTC",
        PlanningProfileConfiguration.Configured(
            TimeZone.UTC,
            listOf(WeeklyAvailabilityWindow(DayOfWeek.MONDAY, LocalTime(9, 0), LocalTime(15, 0))).toImmutableList(),
            minimum, preferred, maximum, AllDayEventPolicy.NON_BLOCKING,
        ),
    )

    private fun range(start: String, end: String) = ZonedTimeRange(at(start), at(end), TimeZone.UTC)

    private fun at(value: String) = Instant.parse("2026-01-05T$value:00Z")

    private fun snapshot(
        tasks: List<Task>,
        focusBlocks: List<FocusBlock> = emptyList(),
        dependencies: List<TaskDependency> = emptyList(),
        profile: PlanningProfile = profile(),
        horizonEnd: String = "2026-01-05T15:00:00Z",
        askOverflowAuthorizedTaskIds: List<TaskId> = emptyList(),
    ) = PlanningSnapshot(
        referenceNow = at("08:00"),
        horizon = PlanningHorizon(at("08:00"), Instant.parse(horizonEnd)),
        profile = profile,
        tasks = tasks.toImmutableList(),
        dependencies = dependencies.toImmutableList(),
        focusBlocks = focusBlocks.toImmutableList(),
        events = persistentListOf(),
        courseSessions = persistentListOf(),
        exams = persistentListOf(),
        constraints = persistentListOf(),
        askOverflowAuthorizedTaskIds = askOverflowAuthorizedTaskIds.toImmutableList(),
    )

    private fun id(number: Int) = "018f6e68-7d0c-7000-8000-${number.toString().padStart(12, '0')}"
}
