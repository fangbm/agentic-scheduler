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
 * D6-R1 phase R0 regression set (docs/tasks/D6_PLANNER_CORE_REWRITE.md section 6).
 *
 * Each test captures one known Planner-core failure class from
 * docs/PLANNER_REWRITE_DECISIONS.md (D6R-001 .. D6R-011). The tests are frozen
 * before the rewritten engine exists and must fail on the legacy engine for the
 * documented semantic reason, never because a fixture is invalid.
 */
class PlannerRewriteRegressionTest {

    // R0-01: unknown remaining + existing FLEXIBLE remains occupancy (D6R-001/002).
    @Test
    fun `r0-01 unknown remaining task existing flexible block remains occupancy`() {
        val ghost = FocusBlock(FocusBlockId(id(6)), unknownTask.id, range("09:00", "10:00"), Flexibility.FLEXIBLE, PinState.UNPINNED)
        val result = assertIs<PlannerResult.Success>(engine().fullReplan(snapshot(
            tasks = listOf(unknownTask, task(2, 1.hours)),
            focusBlocks = listOf(ghost),
        )))
        assertNoProposedOverlap(result, listOf(ghost))
        assertEquals(true, result.mutations.none { it.taskId == unknownTask.id })
    }

    // R0-02: unknown remaining + existing SOFT remains occupancy (D6R-002).
    @Test
    fun `r0-02 unknown remaining task existing soft block remains occupancy`() {
        val ghost = FocusBlock(FocusBlockId(id(6)), unknownTask.id, range("09:00", "10:00"), Flexibility.SOFT, PinState.UNPINNED)
        val result = assertIs<PlannerResult.Success>(engine().fullReplan(snapshot(
            tasks = listOf(unknownTask, task(2, 1.hours)),
            focusBlocks = listOf(ghost),
        )))
        assertNoProposedOverlap(result, listOf(ghost))
        assertEquals(true, result.mutations.none { it.taskId == unknownTask.id })
    }

    // R0-03: COMPLETED / CANCELLED / remaining-zero FLEXIBLE remains occupancy (D6R-002).
    @Test
    fun `r0-03 ineligible task flexible block remains occupancy`() {
        listOf(
            task(1, 1.hours, status = TaskStatus.COMPLETED),
            task(1, 1.hours, status = TaskStatus.CANCELLED),
            task(1, Duration.ZERO, status = TaskStatus.OPEN),
        ).forEach { ineligible ->
            val ghost = FocusBlock(FocusBlockId(id(6)), ineligible.id, range("09:00", "10:00"), Flexibility.FLEXIBLE, PinState.UNPINNED)
            val result = assertIs<PlannerResult.Success>(engine().fullReplan(snapshot(
                tasks = listOf(ineligible, task(2, 1.hours)),
                focusBlocks = listOf(ghost),
            )))
            assertNoProposedOverlap(result, listOf(ghost))
            assertEquals(
                false,
                result.mutations.any { it is FocusBlockMutation.Delete && it.id == ghost.id },
                "FLEXIBLE block must never be cleanup-deleted for ${ineligible.status}",
            )
        }
    }

    // R0-04: dependency-blocked Task existing FLEXIBLE remains occupancy (D6R-002).
    @Test
    fun `r0-04 dependency blocked task existing flexible block remains occupancy`() {
        val blocked = task(3, 1.hours)
        val ghost = FocusBlock(FocusBlockId(id(8)), blocked.id, range("09:00", "10:00"), Flexibility.FLEXIBLE, PinState.UNPINNED)
        val result = assertIs<PlannerResult.Success>(engine().fullReplan(snapshot(
            tasks = listOf(unknownTask, task(2, 1.hours), blocked),
            focusBlocks = listOf(ghost),
            dependencies = listOf(TaskDependency(TaskDependencyId(id(7)), unknownTask.id, blocked.id)),
        )))
        assertNoProposedOverlap(result, listOf(ghost))
        assertEquals(true, result.mutations.none { it.taskId == blocked.id })
        assertEquals(true, result.issues.any { it is PlannerIssue.DependencyBlocked && it.taskId == blocked.id })
    }

    // R0-05: prerequisite completion after horizon -> no invalid range, no dependent
    // placement, and the pre-existing dependency violation of the dependent's own block
    // must be surfaced (D6R-005, PLN-009).
    @Test
    fun `r0-05 prerequisite completion after horizon yields no dependent placement`() {
        val prerequisite = task(1, 1.hours)
        val dependent = task(2, 1.hours)
        val future = FocusBlock(FocusBlockId(id(6)), prerequisite.id, range("13:00", "14:00"), Flexibility.HARD, PinState.UNPINNED)
        val violating = FocusBlock(FocusBlockId(id(8)), dependent.id, range("10:00", "11:00"), Flexibility.FLEXIBLE, PinState.UNPINNED)
        val result = assertIs<PlannerResult.Success>(engine().fullReplan(snapshot(
            tasks = listOf(prerequisite, dependent),
            focusBlocks = listOf(future, violating),
            dependencies = listOf(TaskDependency(TaskDependencyId(id(7)), prerequisite.id, dependent.id)),
        )))
        assertEquals(true, result.mutations.none { it.taskId == dependent.id })
        result.mutations.forEach { mutation ->
            val proposed = proposedRange(mutation)
            assertTrue(proposed.start < proposed.endExclusive, "mutation range must be non-empty")
            assertTrue(proposed.endExclusive <= at("13:00"), "mutation range must stay inside the horizon")
        }
        assertEquals(
            true,
            result.issues.any { it.subjectTaskId() == dependent.id },
            "existing dependency violation must be surfaced for the dependent",
        )
    }

    // R0-06: NORMAL/no-deadline proposal cannot erase an unrelocatable FLEXIBLE block;
    // the higher-ranked task schedules at its next legal candidate instead of relying on
    // rollback of already-committed proposals (D6R-003/004).
    @Test
    fun `r0-06 best effort demand uses next legal candidate instead of erasing flexible block`() {
        val demand = Task(TaskId(id(1)), "Task 1", TaskStatus.OPEN, TaskPriority.HIGH, TaskEffort(2.hours, Duration.ZERO, 2.hours), null)
        val owner = task(2, 1.hours)
        val flexible = FocusBlock(FocusBlockId(id(6)), owner.id, range("09:00", "10:00"), Flexibility.FLEXIBLE, PinState.UNPINNED)
        val barrier = FocusBlock(FocusBlockId(id(9)), TaskId(id(5)), range("11:00", "13:00"), Flexibility.HARD, PinState.UNPINNED)
        val result = assertIs<PlannerResult.Success>(engine().fullReplan(snapshot(
            tasks = listOf(demand, owner, task(5, Duration.ZERO)),
            focusBlocks = listOf(flexible, barrier),
        )))
        assertEquals(true, result.mutations.none { it.taskId == owner.id }, "uncontested existing block must keep its placement")
        val creates = result.mutations.filterIsInstance<FocusBlockMutation.Create>()
        assertEquals(1, creates.size, "genuinely free capacity must host new work without erasing the FLEXIBLE block")
        assertEquals(at("10:00"), creates.single().draft.time.start)
        assertEquals(
            true,
            result.issues.any { it is PlannerIssue.UnscheduledEffort && it.taskId == demand.id && it.remaining == 1.hours },
            "residual best-effort demand must be reported exactly",
        )
    }

    // R0-07: failed displacement restores every existing reservation and derived free
    // space; multi-block rollback keeps untouched original blocks in place (D6R-003/004).
    @Test
    fun `r0-07 failed displacement preserves untouched original blocks and schedules best effort work`() {
        val demand = task(1, 2.hours)
        val owner = task(2, 2.hours)
        val first = FocusBlock(FocusBlockId(id(6)), owner.id, range("09:00", "10:00"), Flexibility.FLEXIBLE, PinState.UNPINNED)
        val second = FocusBlock(FocusBlockId(id(7)), owner.id, range("11:00", "12:00"), Flexibility.FLEXIBLE, PinState.UNPINNED)
        val barrier = FocusBlock(FocusBlockId(id(9)), TaskId(id(5)), range("12:00", "13:00"), Flexibility.HARD, PinState.UNPINNED)
        val result = assertIs<PlannerResult.Success>(engine().fullReplan(snapshot(
            tasks = listOf(demand, owner, task(5, Duration.ZERO)),
            focusBlocks = listOf(first, second, barrier),
        )))
        assertEquals(true, result.mutations.none { it.taskId == owner.id }, "uncontested original blocks must keep their placement")
        val creates = result.mutations.filterIsInstance<FocusBlockMutation.Create>()
        assertEquals(1, creates.size, "only the genuinely free interval may host new work")
        assertEquals(at("10:00"), creates.single().draft.time.start)
        assertEquals(
            true,
            result.issues.any { it is PlannerIssue.UnscheduledEffort && it.taskId == demand.id },
            "remaining best-effort demand must be reported exactly",
        )
    }

    // R0-08: SOFT resize considers the same-start / clamped-original candidate (D6R-007).
    @Test
    fun `r0-08 soft resize keeps clamped original start`() {
        val owner = task(1, 1.hours)
        val oversized = FocusBlock(FocusBlockId(id(6)), owner.id, range("10:00", "12:00"), Flexibility.SOFT, PinState.UNPINNED)
        val barrier = FocusBlock(FocusBlockId(id(9)), TaskId(id(5)), range("09:00", "09:30"), Flexibility.HARD, PinState.UNPINNED)
        val result = assertIs<PlannerResult.Success>(engine().fullReplan(snapshot(
            tasks = listOf(owner, task(5, Duration.ZERO)),
            focusBlocks = listOf(oversized, barrier),
        )))
        val resize = assertIs<FocusBlockMutation.Resize>(result.mutations.single())
        assertEquals(at("10:00"), resize.time.start, "same-start resize must win the movement criterion")
    }

    // R0-09: existing SOFT smaller movement outranks later PLN-015 criteria (D6R-008).
    @Test
    fun `r0-09 existing soft smaller movement outranks context switch and earlier start`() {
        val owner = task(1, 1.hours, deadline = TaskDeadline(Deadline.Exact(at("12:00"), TimeZone.UTC), DeadlinePolicy.NORMAL, OverflowPolicy.NEVER))
        val movable = FocusBlock(FocusBlockId(id(6)), owner.id, range("11:30", "12:30"), Flexibility.SOFT, PinState.UNPINNED)
        val barrier = FocusBlock(FocusBlockId(id(9)), TaskId(id(5)), range("09:30", "10:00"), Flexibility.HARD, PinState.UNPINNED)
        val result = assertIs<PlannerResult.Success>(engine().fullReplan(snapshot(
            tasks = listOf(owner, task(5, Duration.ZERO)),
            focusBlocks = listOf(movable, barrier),
        )))
        val move = assertIs<FocusBlockMutation.Move>(result.mutations.single())
        assertEquals(at("10:30"), move.time.start, "smallest movement candidate must win")
    }

    // R0-10: alternate legal duration with better context-switch rank can beat the
    // preferred duration (D6R-007/008: all durations participate in comparison).
    @Test
    fun `r0-10 alternate duration with better context switch rank can beat preferred duration`() {
        val demand = task(1, 2.hours)
        val barrier = FocusBlock(FocusBlockId(id(9)), TaskId(id(5)), range("11:00", "11:30"), Flexibility.HARD, PinState.UNPINNED)
        val result = assertIs<PlannerResult.Success>(engine().fullReplan(snapshot(
            tasks = listOf(demand, task(5, Duration.ZERO)),
            focusBlocks = listOf(barrier),
        )))
        val creates = result.mutations.filterIsInstance<FocusBlockMutation.Create>()
        assertEquals(true, creates.isNotEmpty())
        assertEquals(
            90.minutes,
            creates.first().draft.time.endExclusive - creates.first().draft.time.start,
            "the context-switch-free alternate duration must win the first placement over the preferred duration",
        )
        assertEquals(at("09:00"), creates.first().draft.time.start)
        creates.forEach { create -> assertNoOverlapWith(create.draft.time, barrier.time) }
    }

    // R0-11: HARD deadline ignores after-cutoff coverage for satisfaction (D6R-006).
    @Test
    fun `r0-11 hard deadline ignores after cutoff coverage`() {
        val owner = task(1, 2.hours, deadline = TaskDeadline(Deadline.Exact(at("11:00"), TimeZone.UTC), DeadlinePolicy.HARD, OverflowPolicy.ALLOW))
        val afterCutoff = FocusBlock(FocusBlockId(id(6)), owner.id, range("11:00", "13:00"), Flexibility.HARD, PinState.UNPINNED)
        val result = engine().fullReplan(snapshot(tasks = listOf(owner), focusBlocks = listOf(afterCutoff)))
        assertIs<PlannerResult.Infeasible>(result)
        assertEquals(
            true,
            result.issues.any { it is PlannerIssue.HardDeadlineShortfall && it.taskId == owner.id },
            "after-cutoff coverage must not hide a HARD deadline shortfall",
        )
    }

    // R0-12: outside-horizon mutable coverage counts but is never mutated (PLN-014/D6R-002).
    @Test
    fun `r0-12 outside horizon soft coverage counts but is never mutated`() {
        val owner = task(1, 2.hours)
        val outside = FocusBlock(FocusBlockId(id(6)), owner.id, range("14:00", "15:00"), Flexibility.SOFT, PinState.UNPINNED)
        val result = assertIs<PlannerResult.Success>(engine().fullReplan(snapshot(
            tasks = listOf(owner),
            focusBlocks = listOf(outside),
        )))
        assertEquals(true, result.mutations.none { it.taskId == owner.id && it !is FocusBlockMutation.Create })
        val creates = result.mutations.filterIsInstance<FocusBlockMutation.Create>()
        assertEquals(1, creates.size, "only the in-horizon residual demand may be planned")
        assertEquals(1.hours, creates.single().draft.time.endExclusive - creates.single().draft.time.start)
        result.mutations.forEach { mutation ->
            assertTrue(proposedRange(mutation).endExclusive <= at("13:00"))
        }
    }

    private fun engine() = DeterministicPlanner()

    private fun assertNoProposedOverlap(result: PlannerResult.Success, originals: List<FocusBlock>) {
        result.mutations.forEach { mutation ->
            val proposed = proposedRange(mutation)
            originals.forEach { original -> assertNoOverlapWith(proposed, original.time) }
        }
    }

    private fun assertNoOverlapWith(proposed: ZonedTimeRange, original: ZonedTimeRange) {
        assertTrue(
            !proposed.overlaps(original),
            "proposed $proposed must not overlap existing $original",
        )
    }

    private fun proposedRange(mutation: FocusBlockMutation): ZonedTimeRange = when (mutation) {
        is FocusBlockMutation.Create -> mutation.draft.time
        is FocusBlockMutation.Move -> mutation.time
        is FocusBlockMutation.Resize -> mutation.time
        is FocusBlockMutation.Delete -> ZonedTimeRange(Instant.DISTANT_PAST, Instant.DISTANT_PAST, TimeZone.UTC)
    }

    private fun PlannerIssue.subjectTaskId(): TaskId? = when (this) {
        is PlannerIssue.UnknownRemainingEffort -> taskId
        is PlannerIssue.NoLegalAvailability -> taskId
        is PlannerIssue.DependencyBlocked -> taskId
        is PlannerIssue.OverflowApprovalRequired -> taskId
        is PlannerIssue.HardDeadlineShortfall -> taskId
        is PlannerIssue.UnscheduledEffort -> taskId
        is PlannerIssue.OverallocatedPlannedEffort -> taskId
        is PlannerIssue.ImmovableConflict -> null
        is PlannerIssue.InvalidSnapshot -> null
        is PlannerIssue.TimeResolutionFailure -> null
        PlannerIssue.ProfileUnconfigured -> null
    }

    private val unknownTask = Task(TaskId(id(1)), "Task 1", TaskStatus.OPEN, TaskPriority.NORMAL, TaskEffort(null, Duration.ZERO, null), null)

    private fun task(number: Int, remaining: Duration, deadline: TaskDeadline? = null, status: TaskStatus = TaskStatus.OPEN) =
        Task(TaskId(id(number)), "Task $number", status, TaskPriority.NORMAL, TaskEffort(remaining, Duration.ZERO, remaining), deadline)

    private fun range(start: String, end: String) = ZonedTimeRange(at(start), at(end), TimeZone.UTC)

    private fun at(value: String) = Instant.parse("2026-01-05T$value:00Z")

    private fun snapshot(
        tasks: List<Task>,
        focusBlocks: List<FocusBlock> = emptyList(),
        dependencies: List<TaskDependency> = emptyList(),
    ) = PlanningSnapshot(
        referenceNow = at("08:00"),
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
