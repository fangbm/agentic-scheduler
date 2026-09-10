package dev.agenticscheduler.domain.task

import dev.agenticscheduler.domain.id.FocusBlockId
import dev.agenticscheduler.domain.id.TaskDependencyId
import dev.agenticscheduler.domain.id.TaskId
import dev.agenticscheduler.domain.id.WorkLogId
import dev.agenticscheduler.domain.planning.Deadline
import dev.agenticscheduler.domain.planning.DeadlinePolicy
import dev.agenticscheduler.domain.planning.Flexibility
import dev.agenticscheduler.domain.planning.OverflowPolicy
import dev.agenticscheduler.domain.planning.PinState
import dev.agenticscheduler.domain.planning.TaskDeadline
import dev.agenticscheduler.domain.time.ZonedTimeRange
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone

class TaskDomainTest {
    @Test
    fun `TaskEffort accepts explicit unknown and revised effort`() {
        assertEquals(TaskEffort(null, Duration.ZERO, null), TaskEffort(null, Duration.ZERO, null))
        assertEquals(TaskEffort(2.hours, 1.hours, 5.hours).remaining, 5.hours)
    }

    @Test
    fun `TaskEffort rejects negative and infinite values`() {
        assertFailsWith<IllegalArgumentException> { TaskEffort((-1).hours, Duration.ZERO, null) }
        assertFailsWith<IllegalArgumentException> { TaskEffort(null, Duration.INFINITE, null) }
        assertFailsWith<IllegalArgumentException> { TaskEffort(null, Duration.ZERO, Duration.INFINITE) }
    }

    @Test
    fun `Task accepts deadline variants and independent status effort states`() {
        val noDeadline = Task(taskId(1), "Draft", TaskStatus.IN_PROGRESS, TaskPriority.NORMAL, TaskEffort(null, Duration.ZERO, Duration.ZERO), null)
        assertEquals(null, noDeadline.deadline)

        val exact = TaskDeadline(Deadline.Exact(Instant.parse("2026-09-10T11:00:00Z"), TimeZone.UTC), DeadlinePolicy.HARD, OverflowPolicy.NEVER)
        val completedWithRemaining = Task(taskId(2), "Review", TaskStatus.COMPLETED, TaskPriority.HIGH, TaskEffort(2.hours, 1.hours, 1.hours), exact)
        assertEquals(exact, completedWithRemaining.deadline)

        val dateOnly = TaskDeadline(Deadline.DateOnly(LocalDate(2026, 9, 10)), DeadlinePolicy.NORMAL, OverflowPolicy.ASK)
        assertEquals(dateOnly, Task(taskId(3), "Read", TaskStatus.OPEN, TaskPriority.LOW, TaskEffort(null, Duration.ZERO, null), dateOnly).deadline)
    }

    @Test
    fun `Task rejects blank titles`() {
        assertFailsWith<IllegalArgumentException> {
            Task(taskId(1), " ", TaskStatus.OPEN, TaskPriority.NORMAL, TaskEffort(null, Duration.ZERO, null), null)
        }
    }

    @Test
    fun `FocusBlock preserves every flexibility and pin combination`() {
        val time = ZonedTimeRange(Instant.parse("2026-09-10T10:00:00Z"), Instant.parse("2026-09-10T11:00:00Z"), TimeZone.UTC)
        Flexibility.entries.forEach { flexibility ->
            PinState.entries.forEach { pinState ->
                val focusBlock = FocusBlock(FocusBlockId(idValue(10)), taskId(1), time, flexibility, pinState)
                assertEquals(flexibility, focusBlock.flexibility)
                assertEquals(pinState, focusBlock.pinState)
            }
        }
    }

    @Test
    fun `WorkLog records only an exact time range`() {
        val time = ZonedTimeRange(Instant.parse("2026-09-10T10:00:00Z"), Instant.parse("2026-09-10T11:00:00Z"), TimeZone.UTC)
        val workLog = WorkLog(WorkLogId(idValue(11)), taskId(1), time)
        assertEquals(time, workLog.time)
    }

    @Test
    fun `TaskDependency rejects self references`() {
        assertFailsWith<IllegalArgumentException> {
            TaskDependency(TaskDependencyId(idValue(20)), taskId(1), taskId(1))
        }
    }

    @Test
    fun `TaskDependency validator handles duplicates and cycles deterministically`() {
        val aToB = dependency(1, 1, 2)
        val bToC = dependency(2, 2, 3)
        assertEquals(TaskDependencyValidationResult.VALID, validateDependencyAddition(listOf(aToB), dependency(3, 3, 4)))
        assertEquals(TaskDependencyValidationResult.DUPLICATE, validateDependencyAddition(listOf(aToB), dependency(4, 1, 2)))
        assertEquals(TaskDependencyValidationResult.CYCLE, validateDependencyAddition(listOf(aToB), dependency(5, 2, 1)))
        assertEquals(TaskDependencyValidationResult.CYCLE, validateDependencyAddition(listOf(aToB, bToC), dependency(6, 3, 1)))
        assertEquals(
            validateDependencyAddition(listOf(aToB, bToC), dependency(6, 3, 1)),
            validateDependencyAddition(listOf(bToC, aToB), dependency(6, 3, 1)),
        )
    }

    private fun dependency(id: Int, prerequisite: Int, dependent: Int) =
        TaskDependency(TaskDependencyId(idValue(id)), taskId(prerequisite), taskId(dependent))

    private fun taskId(index: Int) = TaskId(idValue(index))

    private fun idValue(index: Int) = "018f6e68-7d0c-7000-8000-${index.toString().padStart(12, '0')}"
}
