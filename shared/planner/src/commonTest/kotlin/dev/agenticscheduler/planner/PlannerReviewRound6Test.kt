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
 * Sixth review round regressions: the relocation provisional state excludes the
 * planning Task's own replaced subject (a legal swap is provable), the
 * displacement DFS places displaced dependency chains in dependency-resolvable
 * order, and the selected Task can never displace its own prerequisite past its
 * own start (PLN-009 enforced pre-acceptance).
 */
class PlannerReviewRound6Test {

    // R6-01: A's HARD demand displaces B; the relocation provisional state must
    // exclude A's own replaced subject so the swap A 11-12 -> 10-11 with
    // B 10-11 -> 11-12 is provable instead of falsely infeasible.
    @Test
    fun `existing hard subject can swap with displaced flexible block`() {
        val demand = task(1, 1.hours, deadline = TaskDeadline(Deadline.Exact(at("11:00"), TimeZone.UTC), DeadlinePolicy.HARD, OverflowPolicy.NEVER), priority = TaskPriority.HIGH)
        val demandBlock = FocusBlock(FocusBlockId(id(6)), demand.id, range("11:00", "12:00"), Flexibility.FLEXIBLE, PinState.UNPINNED)
        val other = task(2, 1.hours)
        val otherBlock = FocusBlock(FocusBlockId(id(7)), other.id, range("10:00", "11:00"), Flexibility.FLEXIBLE, PinState.UNPINNED)
        val barrier = FocusBlock(FocusBlockId(id(9)), TaskId(id(5)), range("09:00", "10:00"), Flexibility.HARD, PinState.UNPINNED)
        val result = assertIs<PlannerResult.Success>(DeterministicPlanner().fullReplan(snapshot(
            tasks = listOf(demand, other, task(5, Duration.ZERO)),
            focusBlocks = listOf(demandBlock, otherBlock, barrier),
            horizonEnd = "2026-01-05T12:00:00Z",
        )))
        val moves = result.mutations.filterIsInstance<FocusBlockMutation.Move>().associateBy { it.id }
        assertEquals(range("10:00", "11:00"), moves.getValue(demandBlock.id).time, "the HARD demand takes the earlier slot")
        assertEquals(range("11:00", "12:00"), moves.getValue(otherBlock.id).time, "the displaced block takes the swapped slot")
    }

    // R6-02: two displaced reservations form a dependency chain (D depends on P).
    // The arrangement must place P first even though D starts earlier in the free
    // interval - the DFS defers D until P's replacement makes D's dependency floor
    // resolvable, and D ends after P's new completion.
    @Test
    fun `displaced dependency chain is arrangement order independent`() {
        val demand = task(1, 2.hours, deadline = TaskDeadline(Deadline.Exact(at("11:30"), TimeZone.UTC), DeadlinePolicy.HARD, OverflowPolicy.NEVER), priority = TaskPriority.HIGH)
        val dependent = task(2, 1.hours)
        val dependentBlock = FocusBlock(FocusBlockId(id(6)), dependent.id, range("09:00", "10:00"), Flexibility.FLEXIBLE, PinState.UNPINNED)
        val prerequisite = task(3, 1.hours)
        val prerequisiteBlock = FocusBlock(FocusBlockId(id(7)), prerequisite.id, range("10:00", "11:00"), Flexibility.FLEXIBLE, PinState.UNPINNED)
        val twoHourChunks = PlanningProfile(
            PlanningProfileId(id(9)),
            "UTC 2h chunks",
            PlanningProfileConfiguration.Configured(
                TimeZone.UTC,
                listOf(WeeklyAvailabilityWindow(DayOfWeek.MONDAY, LocalTime(9, 0), LocalTime(13, 0))).toImmutableList(),
                1.hours, 2.hours, 2.hours, AllDayEventPolicy.NON_BLOCKING,
            ),
        )
        val result = assertIs<PlannerResult.Success>(DeterministicPlanner().fullReplan(snapshot(
            tasks = listOf(demand, dependent, prerequisite),
            focusBlocks = listOf(dependentBlock, prerequisiteBlock),
            dependencies = listOf(TaskDependency(TaskDependencyId(id(8)), prerequisite.id, dependent.id)),
            profile = twoHourChunks,
        )))
        val create = assertIs<FocusBlockMutation.Create>(result.mutations.filterIsInstance<FocusBlockMutation.Create>().single())
        assertEquals(range("09:00", "11:00"), create.draft.time, "the candidate occupies both displaced blocks' slots")
        val moves = result.mutations.filterIsInstance<FocusBlockMutation.Move>().associateBy { it.id }
        assertEquals(range("11:00", "12:00"), moves.getValue(prerequisiteBlock.id).time, "the prerequisite is arranged before its dependent")
        assertEquals(range("12:00", "13:00"), moves.getValue(dependentBlock.id).time, "the dependent lands after the relocated prerequisite's completion")
    }

    // R6-03 (PLN-009 pre-acceptance): the selected Task's only displacement
    // candidate pushes its own prerequisite past the candidate's start - the
    // candidate is rejected and the run reports the HARD shortfall instead of
    // entering an illegal state or crashing on the final invariant.
    @Test
    fun `selected task cannot displace its own prerequisite past its own start`() {
        val prerequisite = task(2, 30.minutes)
        val prerequisiteBlock = FocusBlock(FocusBlockId(id(6)), prerequisite.id, range("09:00", "10:00"), Flexibility.FLEXIBLE, PinState.UNPINNED)
        val demand = task(1, 90.minutes, deadline = TaskDeadline(Deadline.Exact(at("11:00"), TimeZone.UTC), DeadlinePolicy.HARD, OverflowPolicy.NEVER), priority = TaskPriority.HIGH)
        val result = DeterministicPlanner().fullReplan(snapshot(
            tasks = listOf(demand, prerequisite),
            focusBlocks = listOf(prerequisiteBlock),
            dependencies = listOf(TaskDependency(TaskDependencyId(id(8)), prerequisite.id, demand.id)),
        ))
        val infeasible = assertIs<PlannerResult.Infeasible>(result)
        assertEquals(
            true,
            infeasible.issues.any { it is PlannerIssue.HardDeadlineShortfall && it.taskId == demand.id },
            "the demand cannot be covered without illegally displacing its own prerequisite",
        )
    }

    private fun task(
        number: Int,
        remaining: Duration,
        deadline: TaskDeadline? = null,
        priority: TaskPriority = TaskPriority.NORMAL,
    ) = Task(TaskId(id(number)), "Task $number", TaskStatus.OPEN, priority, TaskEffort(remaining, Duration.ZERO, remaining), deadline)

    private fun range(start: String, end: String) = ZonedTimeRange(at(start), at(end), TimeZone.UTC)

    private fun at(value: String) = Instant.parse("2026-01-05T$value:00Z")

    private fun snapshot(
        tasks: List<Task>,
        focusBlocks: List<FocusBlock> = emptyList(),
        dependencies: List<TaskDependency> = emptyList(),
        horizonEnd: String = "2026-01-05T13:00:00Z",
        profile: PlanningProfile = PlanningProfile(
            PlanningProfileId(id(9)),
            "UTC",
            PlanningProfileConfiguration.Configured(
                TimeZone.UTC,
                listOf(WeeklyAvailabilityWindow(DayOfWeek.MONDAY, LocalTime(9, 0), LocalTime(13, 0))).toImmutableList(),
                1.hours, 1.hours, 2.hours, AllDayEventPolicy.NON_BLOCKING,
            ),
        ),
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
        askOverflowAuthorizedTaskIds = persistentListOf(),
    )

    private fun id(number: Int) = "018f6e68-7d0c-7000-8000-${number.toString().padStart(12, '0')}"
}
