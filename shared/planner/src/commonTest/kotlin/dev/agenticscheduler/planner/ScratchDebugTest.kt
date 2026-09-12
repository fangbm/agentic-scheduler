package dev.agenticscheduler.planner

import dev.agenticscheduler.domain.id.PlanningProfileId
import dev.agenticscheduler.domain.id.TaskId
import dev.agenticscheduler.domain.planning.AllDayEventPolicy
import dev.agenticscheduler.domain.planning.Deadline
import dev.agenticscheduler.domain.planning.DeadlinePolicy
import dev.agenticscheduler.domain.planning.OverflowPolicy
import dev.agenticscheduler.domain.planning.PlanningProfile
import dev.agenticscheduler.domain.planning.PlanningProfileConfiguration
import dev.agenticscheduler.domain.planning.TaskDeadline
import dev.agenticscheduler.domain.planning.WeeklyAvailabilityWindow
import dev.agenticscheduler.domain.task.Task
import dev.agenticscheduler.domain.task.TaskEffort
import dev.agenticscheduler.domain.task.TaskPriority
import dev.agenticscheduler.domain.task.TaskStatus
import kotlin.test.Test
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlin.time.Instant
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes

class ScratchDebugTest {
    @Test
    fun debugDateOnly() {
        val profile = PlanningProfile(
            PlanningProfileId(id(9)), "profile",
            PlanningProfileConfiguration.Configured(
                TimeZone.UTC,
                listOf(
                    WeeklyAvailabilityWindow(DayOfWeek.MONDAY, LocalTime(9, 0), LocalTime(13, 0)),
                    WeeklyAvailabilityWindow(DayOfWeek.TUESDAY, LocalTime(9, 0), LocalTime(10, 0)),
                ).toImmutableList(),
                30.minutes, 2.hours, 2.hours, AllDayEventPolicy.NON_BLOCKING,
            ),
        )
        val deadline = TaskDeadline(Deadline.DateOnly(LocalDate.parse("2026-01-06")), DeadlinePolicy.HARD, OverflowPolicy.NEVER)
        val t = Task(TaskId(id(1)), "Task 1", TaskStatus.OPEN, TaskPriority.NORMAL, TaskEffort(5.hours, kotlin.time.Duration.ZERO, 5.hours), deadline)
        val result = DeterministicPlannerV2().fullReplan(
            PlanningSnapshot(
                referenceNow = Instant.parse("2026-01-05T08:00:00Z"),
                horizon = PlanningHorizon(Instant.parse("2026-01-05T08:00:00Z"), Instant.parse("2026-01-06T13:00:00Z")),
                profile = profile,
                tasks = listOf(t).toImmutableList(),
                dependencies = persistentListOf(), focusBlocks = persistentListOf(), events = persistentListOf(),
                courseSessions = persistentListOf(), exams = persistentListOf(), constraints = persistentListOf(),
                askOverflowAuthorizedTaskIds = persistentListOf(),
            ),
        )
        println("DATEONLY RESULT: $result")
    }

    private fun id(number: Int) = "018f6e68-7d0c-7000-8000-${number.toString().padStart(12, '0')}"
}
