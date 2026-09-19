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
import kotlin.time.Instant

/**
 * Fourth review round regressions: NEW proposal displacement, closure seeding on
 * prerequisite self-replan completion changes, and original-identity restoration
 * for invalidated existing FLEXIBLE blocks (D6R-001/002/003).
 */
class PlannerReviewRound4Test {

    // R4-01: a planner-created proposal is displaced by a later-ready higher-ranked
    // HARD Task. The proposal has no Active State ID, so the output must contain
    // Creates only - never a Move/Resize/Delete for an ID that does not exist - and
    // the run must not throw.
    @Test
    fun `later ready hard task displaces a prior new proposal without throwing`() {
        val gated = task(1, 1.hours, deadline = TaskDeadline(Deadline.Exact(at("10:00"), TimeZone.UTC), DeadlinePolicy.HARD, OverflowPolicy.NEVER), priority = TaskPriority.HIGH)
        val low = task(2, 1.hours, priority = TaskPriority.LOW)
        val gate = task(4, 2.hours)
        val gateBlock = FocusBlock(FocusBlockId(id(6)), gate.id, range("09:00", "10:00"), Flexibility.FLEXIBLE, PinState.UNPINNED)
        val result = assertIs<PlannerResult.Success>(DeterministicPlanner().fullReplan(snapshot(
            tasks = listOf(gated, low, gate),
            focusBlocks = listOf(gateBlock),
        )))
        val knownBlocks = snapshot(tasks = listOf(gated, low, gate), focusBlocks = listOf(gateBlock)).focusBlocks.map { it.id }.toSet()
        assertTrue(
            result.mutations.filterIsInstance<FocusBlockMutation.Move>().all { it.id in knownBlocks },
            "no mutation may reference a planner-proposal ID that does not exist in the Active State",
        )
        assertEquals(
            true,
            result.mutations.filter { it.taskId == low.id }.all { it is FocusBlockMutation.Create },
            "the displaced proposal is re-created at its new location - no Move/Delete for a nonexistent ID",
        )
        val creates = result.mutations.filterIsInstance<FocusBlockMutation.Create>().associateBy { it.draft.taskId }
        assertEquals(range("09:00", "10:00"), creates.getValue(gated.id).draft.time, "the HARD demand displaces the proposal and takes the slot")
        assertEquals(range("12:00", "13:00"), creates.getValue(low.id).draft.time, "the displaced proposal is re-created at its new location")
        assertEquals(range("11:00", "12:00"), creates.getValue(gate.id).draft.time)
        // The gate's own input block (a real Active State ID) is relocated legitimately.
        val gateMove = result.mutations.filterIsInstance<FocusBlockMutation.Move>().single()
        assertEquals(gateBlock.id, gateMove.id)
        assertEquals(range("10:00", "11:00"), gateMove.time)
    }

    // R4-02 + R4-03: C's own FLEXIBLE block [09:30,10:30) is legal until B's
    // availability repair moves B's completion from 09:30 to 11:30. The closure
    // must invalidate C and re-plan it into [11:30,12:30) - exactly one net Move
    // with the same FocusBlock ID, never Delete + Create.
    @Test
    fun `prerequisite self replan invalidates the dependent and preserves its flexible identity`() {
        val owner = task(2, 1.hours)
        val ownerBlock = FocusBlock(FocusBlockId(id(6)), owner.id, range("08:30", "09:30"), Flexibility.FLEXIBLE, PinState.UNPINNED)
        val dependent = task(3, 1.hours, deadline = TaskDeadline(Deadline.Exact(at("12:30"), TimeZone.UTC), DeadlinePolicy.NORMAL, OverflowPolicy.NEVER))
        val dependentBlock = FocusBlock(FocusBlockId(id(7)), dependent.id, range("09:30", "10:30"), Flexibility.FLEXIBLE, PinState.UNPINNED)
        val result = assertIs<PlannerResult.Success>(DeterministicPlanner().fullReplan(snapshot(
            tasks = listOf(owner, dependent),
            focusBlocks = listOf(ownerBlock, dependentBlock),
            dependencies = listOf(TaskDependency(TaskDependencyId(id(8)), owner.id, dependent.id)),
        )))
        val ownerMove = assertIs<FocusBlockMutation.Move>(result.mutations.filter { it.taskId == owner.id }.single())
        assertEquals(range("10:30", "11:30"), ownerMove.time, "the availability-violating block is repaired past C's placement")
        val forDependent = result.mutations.filter { it.taskId == dependent.id }
        assertEquals(1, forDependent.size, "exactly one net Move for the invalidated dependent")
        val dependentMove = assertIs<FocusBlockMutation.Move>(forDependent.single())
        assertEquals(dependentBlock.id, dependentMove.id, "the original FocusBlock identity survives")
        assertEquals(range("11:30", "12:30"), dependentMove.time, "the dependent starts at/after the moved prerequisite completion")
        assertTrue(
            result.mutations.none { it is FocusBlockMutation.Delete && it.taskId == dependent.id },
            "the dependent's original FLEXIBLE identity must survive the closure",
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
    ) = PlanningSnapshot(
        referenceNow = at("08:00"),
        horizon = PlanningHorizon(at("08:00"), at("15:00")),
        profile = PlanningProfile(
            PlanningProfileId(id(9)),
            "UTC",
            PlanningProfileConfiguration.Configured(
                TimeZone.UTC,
                listOf(WeeklyAvailabilityWindow(DayOfWeek.MONDAY, LocalTime(9, 0), LocalTime(15, 0))).toImmutableList(),
                1.hours, 1.hours, 2.hours, AllDayEventPolicy.NON_BLOCKING,
            ),
        ),
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
