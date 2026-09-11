package dev.agenticscheduler.planner

import dev.agenticscheduler.domain.id.FocusBlockId
import dev.agenticscheduler.domain.id.PlanningProfileId
import dev.agenticscheduler.domain.id.TaskId
import dev.agenticscheduler.domain.planning.AllDayEventPolicy
import dev.agenticscheduler.domain.planning.Flexibility
import dev.agenticscheduler.domain.planning.PinState
import dev.agenticscheduler.domain.planning.PlanningProfile
import dev.agenticscheduler.domain.planning.PlanningProfileConfiguration
import dev.agenticscheduler.domain.planning.WeeklyAvailabilityWindow
import dev.agenticscheduler.domain.task.FocusBlock
import dev.agenticscheduler.domain.task.Task
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

    @Test fun `local reflow keeps identity duration and finds closest legal interval`() {
        val original = FocusBlock(FocusBlockId(id(4)), TaskId(id(1)), ZonedTimeRange(Instant.parse("2026-01-05T09:00:00Z"), Instant.parse("2026-01-05T10:00:00Z"), TimeZone.UTC), Flexibility.FLEXIBLE, PinState.UNPINNED)
        val snapshot = snapshot(tasks = listOf(task(1, 1.hours)), focusBlocks = listOf(original))
        val request = LocalReflowRequest(persistentListOf(original.id), persistentListOf(), ZonedTimeRange(Instant.parse("2026-01-05T08:00:00Z"), Instant.parse("2026-01-05T12:00:00Z"), TimeZone.UTC))
        val success = assertIs<PlannerResult.Success>(DeterministicPlanner().localReflow(snapshot, request))
        val move = assertIs<FocusBlockMutation.Move>(success.mutations.single())
        assertEquals(original.id, move.id)
        assertEquals(original.time, move.time)
    }

    private fun snapshot(tasks: List<Task>, focusBlocks: List<FocusBlock> = emptyList()) = PlanningSnapshot(
        referenceNow = Instant.parse("2026-01-05T08:00:00Z"),
        horizon = PlanningHorizon(Instant.parse("2026-01-05T08:00:00Z"), Instant.parse("2026-01-05T13:00:00Z")),
        profile = PlanningProfile(PlanningProfileId(id(9)), "UTC", PlanningProfileConfiguration.Configured(TimeZone.UTC, listOf(WeeklyAvailabilityWindow(DayOfWeek.MONDAY, LocalTime(9, 0), LocalTime(12, 0))).toImmutableList(), 30.minutes, 2.hours, 2.hours, AllDayEventPolicy.NON_BLOCKING)),
        tasks = tasks.toImmutableList(), dependencies = persistentListOf(), focusBlocks = focusBlocks.toImmutableList(), events = persistentListOf(), courseSessions = persistentListOf(), exams = persistentListOf(), constraints = persistentListOf(), askOverflowAuthorizedTaskIds = persistentListOf(),
    )

    private fun task(number: Int, remaining: kotlin.time.Duration, deadline: dev.agenticscheduler.domain.planning.TaskDeadline? = null) = Task(TaskId(id(number)), "Task $number", TaskStatus.OPEN, TaskPriority.NORMAL, TaskEffort(remaining, kotlin.time.Duration.ZERO, remaining), deadline)
    private fun id(number: Int) = "018f6e68-7d0c-7000-8000-${number.toString().padStart(12, '0')}"
}
