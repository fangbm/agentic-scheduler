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
 * Review-round regressions for the D6-R1 planner core rewrite: invalid-snapshot
 * handling, net-mutation output (one mutation per FocusBlockId), Local Reflow
 * dependency blocking, and per-structural-start PLN-013 duration enumeration.
 */
class PlannerReviewRegressionTest {

    // review-1: referenceNow on the horizon boundary must yield InvalidInput, never throw.
    @Test
    fun `referenceNow at horizon end returns invalid input instead of throwing`() {
        val invalid = snapshot(referenceNow = "13:00")
        val replan = DeterministicPlanner().fullReplan(invalid)
        val reflow = DeterministicPlanner().localReflow(
            invalid,
            LocalReflowRequest(persistentListOf(), persistentListOf(), range("09:00", "12:00")),
        )
        listOf(replan, reflow).forEach { result ->
            val issue = assertIs<PlannerResult.InvalidInput>(result).issues.single()
            assertIs<PlannerIssue.InvalidSnapshot>(issue)
        }
    }

    // review-1b: referenceNow before the horizon must yield InvalidInput as well.
    @Test
    fun `referenceNow before horizon start returns invalid input instead of throwing`() {
        val invalid = snapshot(referenceNow = "07:00")
        val issue = assertIs<PlannerResult.InvalidInput>(DeterministicPlanner().fullReplan(invalid)).issues.single()
        assertIs<PlannerIssue.InvalidSnapshot>(issue)
    }

    // review-2: Local Reflow validates the snapshot before any planning work.
    @Test
    fun `local reflow returns invalid input for an invalid snapshot`() {
        val duplicated = listOf(task(1, 1.hours), task(1, 1.hours))
        val result = DeterministicPlanner().localReflow(
            snapshot(tasks = duplicated),
            LocalReflowRequest(persistentListOf(), persistentListOf(), range("09:00", "12:00")),
        )
        val issue = assertIs<PlannerResult.InvalidInput>(result).issues.single()
        assertIs<PlannerIssue.InvalidSnapshot>(issue)
    }

    // review-3: one FLEXIBLE block displaced twice must yield exactly one final Move;
    // the internal event log (two Moves) is never the planner output.
    @Test
    fun `flexible block displaced twice yields exactly one final move`() {
        val b = task(3, 1.hours)
        val blockB = FocusBlock(FocusBlockId(id(6)), b.id, range("09:00", "10:00"), Flexibility.FLEXIBLE, PinState.UNPINNED)
        val a1 = task(1, 1.hours, deadline = TaskDeadline(Deadline.Exact(at("10:00"), TimeZone.UTC), DeadlinePolicy.HARD, OverflowPolicy.NEVER), priority = TaskPriority.HIGH)
        val a2 = task(2, 1.hours, deadline = TaskDeadline(Deadline.Exact(at("11:00"), TimeZone.UTC), DeadlinePolicy.HARD, OverflowPolicy.NEVER))
        val result = assertIs<PlannerResult.Success>(DeterministicPlanner().fullReplan(snapshot(
            tasks = listOf(a1, a2, b),
            focusBlocks = listOf(blockB),
        )))
        val forB = result.mutations.filter { it.taskId == b.id }
        assertEquals(1, forB.size, "net output must contain exactly one mutation for the displaced block")
        val move = assertIs<FocusBlockMutation.Move>(forB.single())
        assertEquals(range("11:00", "12:00"), move.time)
        val creates = result.mutations.filterIsInstance<FocusBlockMutation.Create>()
        assertEquals(2, creates.size, "both higher-ranked HARD demands must be scheduled")
    }

    // review-4: a SOFT block displaced and then resized must yield exactly one final
    // Resize (the coalesced net change from the original Active State placement).
    @Test
    fun `soft block displaced then resized yields exactly one final resize`() {
        val b = task(3, 30.minutes)
        val blockB = FocusBlock(FocusBlockId(id(6)), b.id, range("09:00", "10:00"), Flexibility.SOFT, PinState.UNPINNED)
        val a1 = task(1, 1.hours, deadline = TaskDeadline(Deadline.Exact(at("10:00"), TimeZone.UTC), DeadlinePolicy.HARD, OverflowPolicy.NEVER), priority = TaskPriority.HIGH)
        val result = assertIs<PlannerResult.Success>(DeterministicPlanner().fullReplan(snapshot(
            tasks = listOf(a1, b),
            focusBlocks = listOf(blockB),
        )))
        val forB = result.mutations.filter { it.taskId == b.id }
        assertEquals(1, forB.size, "net output must contain exactly one mutation for the displaced block")
        val resize = assertIs<FocusBlockMutation.Resize>(forB.single())
        assertEquals(range("10:00", "10:30"), resize.time)
    }

    // review-5: a blocked prerequisite (cancelled, unknown remaining, zero remaining but
    // not completed) leaves the dependent block unplaceable, so Local Reflow is
    // Infeasible and applies nothing (PLN-009).
    @Test
    fun `local reflow with blocked prerequisite is infeasible`() {
        listOf(
            task(1, 1.hours, status = TaskStatus.CANCELLED),
            Task(TaskId(id(1)), "Task 1", TaskStatus.OPEN, TaskPriority.NORMAL, TaskEffort(null, Duration.ZERO, null), null),
            task(1, Duration.ZERO, status = TaskStatus.OPEN),
        ).forEach { prerequisite ->
            val prerequisiteBlock = FocusBlock(FocusBlockId(id(6)), prerequisite.id, range("09:00", "10:00"), Flexibility.FLEXIBLE, PinState.UNPINNED)
            val dependent = task(2, 1.hours)
            val dependentBlock = FocusBlock(FocusBlockId(id(7)), dependent.id, range("10:00", "11:00"), Flexibility.FLEXIBLE, PinState.UNPINNED)
            val result = DeterministicPlanner().localReflow(
                snapshot(
                    tasks = listOf(prerequisite, dependent),
                    focusBlocks = listOf(prerequisiteBlock, dependentBlock),
                    dependencies = listOf(TaskDependency(TaskDependencyId(id(8)), prerequisite.id, dependent.id)),
                ),
                LocalReflowRequest(
                    listOf(prerequisiteBlock.id, dependentBlock.id).toImmutableList(),
                    persistentListOf(),
                    range("09:00", "13:00"),
                ),
            )
            val infeasible = assertIs<PlannerResult.Infeasible>(result)
            assertEquals(
                true,
                infeasible.issues.any { it is PlannerIssue.DependencyBlocked && it.taskId == dependent.id && it.prerequisiteTaskId == prerequisite.id },
                "blocked prerequisite must be reported for ${prerequisite.status}/${prerequisite.effort.remaining}",
            )
        }
    }

    // review-6: a later structural start enumerates its own PLN-013 durations from its
    // own segment capacity, not from the whole free interval's capacity.
    @Test
    fun `later structural start enumerates its own segment durations`() {
        val config = PlanningProfileConfiguration.Configured(
            TimeZone.UTC,
            listOf(WeeklyAvailabilityWindow(DayOfWeek.MONDAY, LocalTime(9, 0), LocalTime(13, 0))).toImmutableList(),
            30.minutes, 90.minutes, 2.hours, AllDayEventPolicy.NON_BLOCKING,
        )
        // Demand 2h, whole-interval capacity 4h -> U = 2h; no 1h duration in the set.
        val intervalLevel = CandidateGeneration.durationsForSegment(2.hours, 4.hours, null, config)
        assertEquals(false, intervalLevel.any { it == 1.hours }, "interval-level enumeration must not contain the 1h duration")
        // The segment after the cutoff boundary has capacity 1h -> U = 1h; the 1h
        // duration is the segment-capped PLN-013 value and must be enumerated.
        val lateSegment = CandidateGeneration.durationsForSegment(2.hours, 1.hours, null, config)
        assertEquals(true, lateSegment.any { it == 1.hours }, "the later structural start must enumerate its own segment-capped duration")
    }

    // ------------------------------------------------------------------ helpers

    private fun task(
        number: Int,
        remaining: Duration,
        deadline: TaskDeadline? = null,
        status: TaskStatus = TaskStatus.OPEN,
        priority: TaskPriority = TaskPriority.NORMAL,
    ) = Task(TaskId(id(number)), "Task $number", status, priority, TaskEffort(remaining, Duration.ZERO, remaining), deadline)

    private fun range(start: String, end: String) = ZonedTimeRange(at(start), at(end), TimeZone.UTC)

    private fun at(value: String) = Instant.parse("2026-01-05T$value:00Z")

    private fun snapshot(
        tasks: List<Task> = emptyList(),
        focusBlocks: List<FocusBlock> = emptyList(),
        dependencies: List<TaskDependency> = emptyList(),
        referenceNow: String = "08:00",
    ) = PlanningSnapshot(
        referenceNow = at(referenceNow),
        horizon = PlanningHorizon(at("08:00"), at("13:00")),
        profile = PlanningProfile(
            PlanningProfileId(id(9)),
            "UTC",
            PlanningProfileConfiguration.Configured(
                TimeZone.UTC,
                listOf(WeeklyAvailabilityWindow(DayOfWeek.MONDAY, LocalTime(9, 0), LocalTime(13, 0))).toImmutableList(),
                30.minutes, 2.hours, 2.hours, AllDayEventPolicy.NON_BLOCKING,
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
