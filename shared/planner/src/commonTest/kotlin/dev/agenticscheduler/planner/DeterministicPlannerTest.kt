package dev.agenticscheduler.planner

import dev.agenticscheduler.domain.id.FocusBlockId
import dev.agenticscheduler.domain.id.PlanningProfileId
import dev.agenticscheduler.domain.id.TaskId
import dev.agenticscheduler.domain.id.TaskDependencyId
import dev.agenticscheduler.domain.planning.AllDayEventPolicy
import dev.agenticscheduler.domain.planning.Flexibility
import dev.agenticscheduler.domain.planning.PinState
import dev.agenticscheduler.domain.planning.PlanningProfile
import dev.agenticscheduler.domain.planning.PlanningProfileConfiguration
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
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

class DeterministicPlannerTest {
    @Test fun `full replan creates canonical chunks without hidden clock input`() {
        val snapshot = snapshot(tasks = listOf(task(1, 2.hours)))
        val first = DeterministicPlanner().fullReplan(snapshot)
        val second = DeterministicPlanner().fullReplan(snapshot)
        assertEquals(first, second)
        val success = assertIs<PlannerResult.Success>(first)
        val create = assertIs<FocusBlockMutation.Create>(success.mutations.single())
        assertEquals(Instant.parse("2026-01-05T09:00:00Z"), create.draft.time.start)
        assertEquals(2.hours, create.draft.time.endExclusive - create.draft.time.start)
        assertEquals(Flexibility.SOFT, create.draft.flexibility)
        assertEquals(PinState.UNPINNED, create.draft.pinState)
    }

    @Test fun `hard deadline shortfall is inapplicable`() {
        val deadline = dev.agenticscheduler.domain.planning.TaskDeadline(
            dev.agenticscheduler.domain.planning.Deadline.Exact(Instant.parse("2026-01-05T10:00:00Z"), TimeZone.UTC),
            dev.agenticscheduler.domain.planning.DeadlinePolicy.HARD,
            dev.agenticscheduler.domain.planning.OverflowPolicy.ALLOW,
        )
        val result = DeterministicPlanner().fullReplan(snapshot(tasks = listOf(task(1, 2.hours, deadline))))
        assertIs<PlannerResult.Infeasible>(result)
    }

    @Test fun `hard and pinned blocks remain fixed occupancy`() {
        val fixedTask = task(1, kotlin.time.Duration.ZERO)
        val plannedTask = task(2, 2.hours)
        val hard = FocusBlock(FocusBlockId(id(4)), fixedTask.id, ZonedTimeRange(Instant.parse("2026-01-05T09:00:00Z"), Instant.parse("2026-01-05T10:00:00Z"), TimeZone.UTC), Flexibility.HARD, PinState.UNPINNED)
        val result = assertIs<PlannerResult.Success>(DeterministicPlanner().fullReplan(snapshot(listOf(fixedTask, plannedTask), listOf(hard))))
        val create = assertIs<FocusBlockMutation.Create>(result.mutations.single())
        assertEquals(Instant.parse("2026-01-05T10:00:00Z"), create.draft.time.start)
    }

    @Test fun `local reflow keeps identity duration and finds closest legal interval`() {
        val original = FocusBlock(FocusBlockId(id(4)), TaskId(id(1)), ZonedTimeRange(Instant.parse("2026-01-05T09:00:00Z"), Instant.parse("2026-01-05T10:00:00Z"), TimeZone.UTC), Flexibility.FLEXIBLE, PinState.UNPINNED)
        val snapshot = snapshot(tasks = listOf(task(1, 1.hours)), focusBlocks = listOf(original))
        val request = LocalReflowRequest(persistentListOf(original.id), persistentListOf(), ZonedTimeRange(Instant.parse("2026-01-05T08:00:00Z"), Instant.parse("2026-01-05T12:00:00Z"), TimeZone.UTC))
        val success = assertIs<PlannerResult.Success>(DeterministicPlanner().localReflow(snapshot, request))
        val move = assertIs<FocusBlockMutation.Move>(success.mutations.single())
        assertEquals(original.id, move.id)
        assertEquals(original.time, move.time)
    }

    @Test fun `full replan moves future flexible block instead of treating it as fixed occupancy`() {
        val existing = FocusBlock(FocusBlockId(id(6)), TaskId(id(1)), ZonedTimeRange(Instant.parse("2026-01-05T08:00:00Z"), Instant.parse("2026-01-05T09:00:00Z"), TimeZone.UTC), Flexibility.FLEXIBLE, PinState.UNPINNED)
        val result = assertIs<PlannerResult.Success>(DeterministicPlanner().fullReplan(snapshot(listOf(task(1, 1.hours)), listOf(existing))))
        val move = assertIs<FocusBlockMutation.Move>(result.mutations.single())
        assertEquals(existing.id, move.id)
        assertEquals(Instant.parse("2026-01-05T09:00:00Z"), move.time.start)
    }

    @Test fun `dependent stays blocked until prerequisite is fully planned`() {
        val prerequisite = task(1, 4.hours)
        val dependent = task(2, 1.hours)
        val result = assertIs<PlannerResult.Success>(DeterministicPlanner().fullReplan(snapshot(
            tasks = listOf(prerequisite, dependent),
            dependencies = listOf(TaskDependency(TaskDependencyId(id(7)), prerequisite.id, dependent.id)),
        )))
        assertEquals(true, result.issues.any { it is PlannerIssue.DependencyBlocked && it.taskId == dependent.id })
        assertEquals(false, result.mutations.any { it.taskId == dependent.id })
    }

    @Test fun `outside horizon flexible coverage is counted but never mutated`() {
        val outside = FocusBlock(
            FocusBlockId(id(8)), TaskId(id(1)),
            ZonedTimeRange(Instant.parse("2026-01-05T14:00:00Z"), Instant.parse("2026-01-05T15:00:00Z"), TimeZone.UTC),
            Flexibility.FLEXIBLE, PinState.UNPINNED,
        )
        val result = assertIs<PlannerResult.Success>(DeterministicPlanner().fullReplan(snapshot(listOf(task(1, 1.hours)), listOf(outside))))
        assertEquals(emptyList(), result.mutations)
    }

    @Test fun `cancelled prerequisite remains blocked even when fixed future coverage exists`() {
        val cancelled = task(1, 1.hours, status = TaskStatus.CANCELLED)
        val dependent = task(2, 1.hours)
        val fixed = FocusBlock(
            FocusBlockId(id(8)), cancelled.id,
            ZonedTimeRange(Instant.parse("2026-01-05T09:00:00Z"), Instant.parse("2026-01-05T10:00:00Z"), TimeZone.UTC),
            Flexibility.HARD, PinState.UNPINNED,
        )
        val result = assertIs<PlannerResult.Success>(DeterministicPlanner().fullReplan(snapshot(
            tasks = listOf(cancelled, dependent), focusBlocks = listOf(fixed),
            dependencies = listOf(TaskDependency(TaskDependencyId(id(7)), cancelled.id, dependent.id)),
        )))
        assertEquals(true, result.issues.any { it is PlannerIssue.DependencyBlocked && it.taskId == dependent.id })
        assertEquals(false, result.mutations.any { it.taskId == dependent.id })
    }

    @Test fun `local reflow uses provisional prerequisite completion`() {
        val prerequisite = task(1, 1.hours)
        val dependent = task(2, 1.hours)
        val first = FocusBlock(FocusBlockId(id(4)), prerequisite.id, ZonedTimeRange(Instant.parse("2026-01-05T09:00:00Z"), Instant.parse("2026-01-05T10:00:00Z"), TimeZone.UTC), Flexibility.FLEXIBLE, PinState.UNPINNED)
        val second = FocusBlock(FocusBlockId(id(5)), dependent.id, ZonedTimeRange(Instant.parse("2026-01-05T10:00:00Z"), Instant.parse("2026-01-05T11:00:00Z"), TimeZone.UTC), Flexibility.FLEXIBLE, PinState.UNPINNED)
        val base = snapshot(
            tasks = listOf(prerequisite, dependent), focusBlocks = listOf(first, second),
            dependencies = listOf(TaskDependency(TaskDependencyId(id(7)), prerequisite.id, dependent.id)),
        )
        val snapshot = base.copy(constraints = listOf(PlanningConstraint.UnavailableWindow(
            ZonedTimeRange(Instant.parse("2026-01-05T09:00:00Z"), Instant.parse("2026-01-05T11:00:00Z"), TimeZone.UTC), ConstraintSource.USER,
        )).toImmutableList())
        val result = assertIs<PlannerResult.Success>(DeterministicPlanner().localReflow(snapshot, LocalReflowRequest(
            listOf(first.id, second.id).toImmutableList(), persistentListOf(),
            ZonedTimeRange(Instant.parse("2026-01-05T09:00:00Z"), Instant.parse("2026-01-05T13:00:00Z"), TimeZone.UTC),
        )))
        val moves = result.mutations.filterIsInstance<FocusBlockMutation.Move>().associateBy { it.id }
        assertEquals(Instant.parse("2026-01-05T11:00:00Z"), moves.getValue(first.id).time.start)
        assertEquals(Instant.parse("2026-01-05T12:00:00Z"), moves.getValue(second.id).time.start)
    }

    private fun snapshot(tasks: List<Task>, focusBlocks: List<FocusBlock> = emptyList(), dependencies: List<TaskDependency> = emptyList()) = PlanningSnapshot(
        referenceNow = Instant.parse("2026-01-05T08:00:00Z"),
        horizon = PlanningHorizon(Instant.parse("2026-01-05T08:00:00Z"), Instant.parse("2026-01-05T13:00:00Z")),
        profile = PlanningProfile(PlanningProfileId(id(9)), "UTC", PlanningProfileConfiguration.Configured(TimeZone.UTC, listOf(WeeklyAvailabilityWindow(DayOfWeek.MONDAY, LocalTime(9, 0), LocalTime(13, 0))).toImmutableList(), 30.minutes, 2.hours, 2.hours, AllDayEventPolicy.NON_BLOCKING)),
        tasks = tasks.toImmutableList(), dependencies = dependencies.toImmutableList(), focusBlocks = focusBlocks.toImmutableList(), events = persistentListOf(), courseSessions = persistentListOf(), exams = persistentListOf(), constraints = persistentListOf(), askOverflowAuthorizedTaskIds = persistentListOf(),
    )

    private fun task(number: Int, remaining: kotlin.time.Duration, deadline: dev.agenticscheduler.domain.planning.TaskDeadline? = null, status: TaskStatus = TaskStatus.OPEN) = Task(TaskId(id(number)), "Task $number", status, TaskPriority.NORMAL, TaskEffort(remaining, kotlin.time.Duration.ZERO, remaining), deadline)
    private fun id(number: Int) = "018f6e68-7d0c-7000-8000-${number.toString().padStart(12, '0')}"
}
