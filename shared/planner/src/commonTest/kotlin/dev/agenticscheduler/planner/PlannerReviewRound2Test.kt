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
 * Second review round regressions: globally comparable PLN-013 chunk ranks across
 * segments, complete relocation search for displacements, SOFT identity
 * preservation through resized relocation, and displaced-reservation boundaries
 * as structural start points of the displacement view.
 */
class PlannerReviewRound2Test {

    // review-r2-1: chunk rank is global PLN-013 order (distance from preferred, then
    // longer), not a per-segment list index. The 90m chunk in the far segment must
    // beat the 30m chunk in the near segment in round 2.
    @Test
    fun `cross segment chunk ranks compare by global pln-013 order`() {
        val demand = task(1, 2.hours)
        val barrier = FocusBlock(FocusBlockId(id(9)), TaskId(id(5)), range("10:00", "11:00"), Flexibility.HARD, PinState.UNPINNED)
        val profile = profile(minimum = 30.minutes, preferred = 90.minutes, maximum = 2.hours)
        val result = assertIs<PlannerResult.Success>(DeterministicPlanner().fullReplan(snapshot(
            tasks = listOf(demand, task(5, Duration.ZERO)),
            focusBlocks = listOf(barrier),
            profile = profile,
        )))
        val creates = result.mutations.filterIsInstance<FocusBlockMutation.Create>()
        val total = creates.fold(Duration.ZERO) { total, create -> total + (create.draft.time.endExclusive - create.draft.time.start) }
        assertEquals(2.hours, total)
        assertEquals(
            true,
            creates.any { it.draft.time == range("11:00", "12:30") },
            "the 90m preferred-distance chunk from the far segment must win round 2",
        )
        assertEquals(
            false,
            creates.any { it.draft.time.start == at("09:30") },
            "the locally-ranked but globally-worse 30m chunk at 09:30 must not win",
        )
    }

    // review-r2-2 + review-r2-4: the relocation search enumerates every structural
    // start of the free interval (including the displaced reservation's own start
    // boundary), so the blocked FLEXIBLE block relocates to the earlier legal
    // position [09:00,10:00) even though the closest position [11:00,12:00)
    // violates its deadline.
    @Test
    fun `flexible relocation finds the earlier legal position when the closest violates the deadline`() {
        val prerequisite = Task(TaskId(id(4)), "Task 4", TaskStatus.OPEN, TaskPriority.NORMAL, TaskEffort(null, Duration.ZERO, null), null)
        val blocked = task(3, 1.hours, deadline = TaskDeadline(Deadline.Exact(at("10:15"), TimeZone.UTC), DeadlinePolicy.NORMAL, OverflowPolicy.NEVER))
        val blockedBlock = FocusBlock(FocusBlockId(id(6)), blocked.id, range("10:00", "11:00"), Flexibility.FLEXIBLE, PinState.UNPINNED)
        val demand = task(1, 90.minutes, deadline = TaskDeadline(Deadline.Exact(at("11:30"), TimeZone.UTC), DeadlinePolicy.HARD, OverflowPolicy.NEVER), priority = TaskPriority.HIGH)
        val profile = profile(minimum = 90.minutes, preferred = 90.minutes, maximum = 2.hours)
        val result = assertIs<PlannerResult.Success>(DeterministicPlanner().fullReplan(snapshot(
            tasks = listOf(demand, blocked, prerequisite),
            focusBlocks = listOf(blockedBlock),
            dependencies = listOf(TaskDependency(TaskDependencyId(id(8)), prerequisite.id, blocked.id)),
            profile = profile,
        )))
        val create = assertIs<FocusBlockMutation.Create>(result.mutations.filterIsInstance<FocusBlockMutation.Create>().single())
        assertEquals(range("10:00", "11:30"), create.draft.time, "the HARD demand takes the displaced block's slot")
        val forBlocked = result.mutations.filter { it.taskId == blocked.id }
        assertEquals(1, forBlocked.size)
        val move = assertIs<FocusBlockMutation.Move>(forBlocked.single())
        assertEquals(range("09:00", "10:00"), move.time, "the blocked block relocates to the earlier deadline-legal position, same ID")
    }

    // review-r2-3: when no same-duration relocation exists, a SOFT block is resized
    // into the remaining legal slot instead of being deleted and re-created, so the
    // FocusBlock identity is preserved (D6R-004).
    @Test
    fun `soft displacement falls back to identity preserving resize instead of delete`() {
        val owner = task(2, 30.minutes)
        val oversized = FocusBlock(FocusBlockId(id(6)), owner.id, range("09:00", "10:00"), Flexibility.SOFT, PinState.UNPINNED)
        val demand = task(1, 1.hours, deadline = TaskDeadline(Deadline.Exact(at("10:00"), TimeZone.UTC), DeadlinePolicy.HARD, OverflowPolicy.NEVER), priority = TaskPriority.HIGH)
        val barrier = FocusBlock(FocusBlockId(id(9)), TaskId(id(5)), range("10:30", "13:00"), Flexibility.HARD, PinState.UNPINNED)
        val result = assertIs<PlannerResult.Success>(DeterministicPlanner().fullReplan(snapshot(
            tasks = listOf(demand, owner, task(5, Duration.ZERO)),
            focusBlocks = listOf(oversized, barrier),
        )))
        val forOwner = result.mutations.filter { it.taskId == owner.id }
        assertEquals(1, forOwner.size, "exactly one net mutation for the displaced SOFT block")
        val resize = assertIs<FocusBlockMutation.Resize>(forOwner.single())
        assertEquals(range("10:00", "10:30"), resize.time, "identity preserved: resized into the only legal slot, not deleted")
        val create = assertIs<FocusBlockMutation.Create>(result.mutations.filterIsInstance<FocusBlockMutation.Create>().single())
        assertEquals(range("09:00", "10:00"), create.draft.time)
        assertEquals(
            false,
            result.issues.any { it is PlannerIssue.UnscheduledEffort && it.taskId == demand.id },
            "the HARD demand is fully scheduled",
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
        minimum: Duration = 30.minutes,
        preferred: Duration = 2.hours,
        maximum: Duration = 2.hours,
    ) = PlanningProfile(
        PlanningProfileId(id(9)),
        "UTC",
        PlanningProfileConfiguration.Configured(
            TimeZone.UTC,
            listOf(WeeklyAvailabilityWindow(DayOfWeek.MONDAY, LocalTime(9, 0), LocalTime(13, 0))).toImmutableList(),
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
    ) = PlanningSnapshot(
        referenceNow = at("08:00"),
        horizon = PlanningHorizon(at("08:00"), at("13:00")),
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
