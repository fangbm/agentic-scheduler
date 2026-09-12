package dev.agenticscheduler.planner

import dev.agenticscheduler.domain.academic.AcademicWeekNumber
import dev.agenticscheduler.domain.academic.CourseCancellationReason
import dev.agenticscheduler.domain.academic.CourseOccurrenceKey
import dev.agenticscheduler.domain.academic.CourseSession
import dev.agenticscheduler.domain.academic.CourseSessionState
import dev.agenticscheduler.domain.academic.Exam
import dev.agenticscheduler.domain.academic.ExamSchedule
import dev.agenticscheduler.domain.event.Event
import dev.agenticscheduler.domain.id.CourseId
import dev.agenticscheduler.domain.id.CourseScheduleRuleId
import dev.agenticscheduler.domain.id.EventId
import dev.agenticscheduler.domain.id.ExamId
import dev.agenticscheduler.domain.id.FocusBlockId
import dev.agenticscheduler.domain.id.SemesterId
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
import dev.agenticscheduler.domain.time.AllDayRange
import dev.agenticscheduler.domain.time.FloatingTimeRange
import dev.agenticscheduler.domain.time.TimePlacement
import dev.agenticscheduler.domain.time.ZonedTimeRange
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

/**
 * D6-R1 phase R10 conformance matrix (docs/tasks/D6_PLANNER_CORE_REWRITE.md section 17).
 * Every row of the frozen matrix that is not already covered by the legacy D6 tests
 * or the R0 regression set is pinned here against the rewritten engine.
 */
class PlannerConformanceMatrixTest {

    // ------------------------------------------------------------------ profile / input issues

    @Test
    fun `unconfigured profile is a structured invalid input`() {
        val profile = PlanningProfile(
            PlanningProfileId(id(9)),
            "unconfigured",
            PlanningProfileConfiguration.Unconfigured,
        )
        val result = DeterministicPlannerV2().fullReplan(snapshot(tasks = listOf(task(1, 1.hours)), profile = profile))
        val invalid = assertIs<PlannerResult.InvalidInput>(result)
        assertEquals(true, invalid.issues.any { it is PlannerIssue.ProfileUnconfigured })
    }

    @Test
    fun `adjacent availability windows form one legal region`() {
        val profile = profile(
            windows = listOf(
                WeeklyAvailabilityWindow(DayOfWeek.MONDAY, LocalTime(9, 0), LocalTime(10, 0)),
                WeeklyAvailabilityWindow(DayOfWeek.MONDAY, LocalTime(10, 0), LocalTime(12, 0)),
            ),
            minimum = 30.minutes,
            preferred = 2.hours,
            maximum = 2.hours,
        )
        val result = assertIs<PlannerResult.Success>(DeterministicPlannerV2().fullReplan(snapshot(tasks = listOf(task(1, 2.hours)), profile = profile)))
        val create = assertIs<FocusBlockMutation.Create>(result.mutations.single())
        assertEquals(at("09:00"), create.draft.time.start)
        assertEquals(2.hours, create.draft.time.endExclusive - create.draft.time.start)
    }

    @Test
    fun `availability window inside a DST gap is a structured failure`() {
        val zone = TimeZone.of("America/New_York")
        val profile = PlanningProfile(
            PlanningProfileId(id(9)),
            "New York",
            PlanningProfileConfiguration.Configured(
                zone,
                listOf(WeeklyAvailabilityWindow(DayOfWeek.SUNDAY, LocalTime(2, 0), LocalTime(3, 0))).toImmutableList(),
                30.minutes, 1.hours, 1.hours, AllDayEventPolicy.NON_BLOCKING,
            ),
        )
        // 2026-03-08 is the US spring-forward date: 02:00-03:00 local does not exist.
        val result = DeterministicPlannerV2().fullReplan(
            snapshot(
                tasks = listOf(task(1, 1.hours)),
                profile = profile,
                horizonEnd = "2026-03-08T13:00:00Z",
                referenceNow = "2026-03-08T08:00:00Z",
            ),
        )
        val invalid = assertIs<PlannerResult.InvalidInput>(result)
        assertEquals(true, invalid.issues.any { it is PlannerIssue.TimeResolutionFailure })
    }

    // ------------------------------------------------------------------ occupancy conversion

    @Test
    fun `floating event resolves in profile timezone and blocks occupancy`() {
        val floating = Event(EventId(id(20)), "floating", FloatingTimeRange(Local(9, 0), Local(10, 0)), Flexibility.HARD, PinState.UNPINNED)
        val result = assertIs<PlannerResult.Success>(DeterministicPlannerV2().fullReplan(snapshot(
            tasks = listOf(task(1, 1.hours)),
            events = listOf(floating),
        )))
        val create = assertIs<FocusBlockMutation.Create>(result.mutations.single())
        assertEquals(at("10:00"), create.draft.time.start, "work must start after the resolved floating event")
    }

    @Test
    fun `all day event follows the profile policy`() {
        val allDay = Event(EventId(id(20)), "all day", AllDayRange(LocalDate.parse("2026-01-05"), LocalDate.parse("2026-01-06")), Flexibility.HARD, PinState.UNPINNED)
        val blocking = profile(allDayPolicy = AllDayEventPolicy.BLOCK_WHOLE_LOCAL_DAY, windows = listOf(WeeklyAvailabilityWindow(DayOfWeek.MONDAY, LocalTime(9, 0), LocalTime(13, 0))))
        val blocked = DeterministicPlannerV2().fullReplan(snapshot(tasks = listOf(task(1, 1.hours)), events = listOf(allDay), profile = blocking))
        val blockedResult = assertIs<PlannerResult.Success>(blocked)
        assertEquals(true, blockedResult.mutations.isEmpty(), "blocked whole local day offers no capacity")
        assertEquals(true, blockedResult.issues.any { it is PlannerIssue.NoLegalAvailability })

        val nonBlocking = profile(allDayPolicy = AllDayEventPolicy.NON_BLOCKING)
        val free = assertIs<PlannerResult.Success>(DeterministicPlannerV2().fullReplan(snapshot(tasks = listOf(task(1, 1.hours)), events = listOf(allDay), profile = nonBlocking)))
        assertIs<FocusBlockMutation.Create>(free.mutations.single())
    }

    @Test
    fun `scheduled course session blocks and cancelled session does not`() {
        val scheduled = CourseSession(
            CourseOccurrenceKey(CourseScheduleRuleId(id(33)), AcademicWeekNumber(1)),
            CourseId(id(30)),
            ZonedTimeRange(at("09:00"), at("10:00"), TimeZone.UTC),
            CourseSessionState.Scheduled(ZonedTimeRange(at("09:00"), at("10:00"), TimeZone.UTC), null),
        )
        val cancelled = scheduled.copy(
            occurrenceKey = CourseOccurrenceKey(CourseScheduleRuleId(id(33)), AcademicWeekNumber(2)),
            state = CourseSessionState.Cancelled(CourseCancellationReason.EXPLICIT_EXCEPTION),
        )
        val result = assertIs<PlannerResult.Success>(DeterministicPlannerV2().fullReplan(snapshot(
            tasks = listOf(task(1, 1.hours)),
            courseSessions = listOf(scheduled, cancelled),
        )))
        val create = assertIs<FocusBlockMutation.Create>(result.mutations.single())
        assertEquals(at("10:00"), create.draft.time.start, "cancelled session must not consume capacity")
    }

    @Test
    fun `exact exam blocks while date only and unscheduled exams do not`() {
        fun exam(schedule: ExamSchedule) = Exam(ExamId(id(31)), SemesterId(id(32)), null, "exam", schedule)
        val result = assertIs<PlannerResult.Success>(DeterministicPlannerV2().fullReplan(snapshot(
            tasks = listOf(task(1, 1.hours)),
            exams = listOf(
                exam(ExamSchedule.Exact(ZonedTimeRange(at("09:00"), at("10:00"), TimeZone.UTC))),
                exam(ExamSchedule.DateOnly(LocalDate.parse("2026-01-05"))),
                exam(ExamSchedule.Unscheduled),
            ),
        )))
        val create = assertIs<FocusBlockMutation.Create>(result.mutations.single())
        assertEquals(at("10:00"), create.draft.time.start)
    }

    // ------------------------------------------------------------------ deadlines

    @Test
    fun `date only deadline resolves to exclusive next-day start in profile timezone`() {
        // Monday offers 4h (09:00-13:00) and Tuesday offers 00:00-01:00. The DateOnly
        // deadline for Jan 5 resolves to the exclusive cutoff Jan 6 00:00Z, so the
        // Tuesday window starts exactly at the cutoff and must not satisfy a HARD deadline.
        val profile = profile(
            windows = listOf(
                WeeklyAvailabilityWindow(DayOfWeek.MONDAY, LocalTime(9, 0), LocalTime(13, 0)),
                WeeklyAvailabilityWindow(DayOfWeek.TUESDAY, LocalTime(0, 0), LocalTime(1, 0)),
            ),
            minimum = 30.minutes,
            preferred = 2.hours,
            maximum = 2.hours,
        )
        val deadline = TaskDeadline(Deadline.DateOnly(LocalDate.parse("2026-01-05")), DeadlinePolicy.HARD, OverflowPolicy.NEVER)
        val result = DeterministicPlannerV2().fullReplan(snapshot(
            tasks = listOf(task(1, 5.hours, deadline = deadline)),
            profile = profile,
            horizonEnd = "2026-01-06T13:00:00Z",
        ))
        assertIs<PlannerResult.Infeasible>(result)
        assertEquals(true, result.issues.any { it is PlannerIssue.HardDeadlineShortfall })
    }

    @Test
    fun `ask overflow with per-run authorization places after the deadline`() {
        val deadline = TaskDeadline(Deadline.Exact(at("09:00"), TimeZone.UTC), DeadlinePolicy.NORMAL, OverflowPolicy.ASK)
        val askTask = Task(TaskId(id(1)), "Task 1", TaskStatus.OPEN, TaskPriority.NORMAL, TaskEffort(1.hours, Duration.ZERO, 1.hours), deadline)
        val snapshot = snapshot(tasks = listOf(askTask)).copy(
            askOverflowAuthorizedTaskIds = listOf(askTask.id).toImmutableList(),
        )
        val result = assertIs<PlannerResult.Success>(DeterministicPlannerV2().fullReplan(snapshot))
        val create = assertIs<FocusBlockMutation.Create>(result.mutations.single())
        assertEquals(at("09:00"), create.draft.time.start)
    }

    @Test
    fun `hard demand cannot displace an unrelocatable flexible block and fails finitely`() {
        val hardTask = task(1, 1.hours, deadline = TaskDeadline(Deadline.Exact(at("10:00"), TimeZone.UTC), DeadlinePolicy.HARD, OverflowPolicy.ALLOW))
        val owner = task(2, 1.hours)
        val flexible = FocusBlock(FocusBlockId(id(6)), owner.id, range("09:00", "10:00"), Flexibility.FLEXIBLE, PinState.UNPINNED)
        val barrier = FocusBlock(FocusBlockId(id(9)), TaskId(id(5)), range("10:00", "13:00"), Flexibility.HARD, PinState.UNPINNED)
        val result = DeterministicPlannerV2().fullReplan(snapshot(
            tasks = listOf(hardTask, owner, task(5, Duration.ZERO)),
            focusBlocks = listOf(flexible, barrier),
        ))
        val infeasible = assertIs<PlannerResult.Infeasible>(result)
        assertEquals(true, infeasible.issues.any { it is PlannerIssue.HardDeadlineShortfall && it.taskId == hardTask.id })
    }

    // ------------------------------------------------------------------ task eligibility

    @Test
    fun `ineligible tasks get no new work and their soft blocks are cleaned up`() {
        listOf(
            task(1, 1.hours, status = TaskStatus.COMPLETED),
            task(1, 1.hours, status = TaskStatus.CANCELLED),
            task(1, Duration.ZERO),
        ).forEach { ineligible ->
            val soft = FocusBlock(FocusBlockId(id(6)), ineligible.id, range("09:00", "10:00"), Flexibility.SOFT, PinState.UNPINNED)
            val result = assertIs<PlannerResult.Success>(DeterministicPlannerV2().fullReplan(snapshot(
                tasks = listOf(ineligible, task(2, 30.minutes)),
                focusBlocks = listOf(soft),
            )))
            assertEquals(true, result.mutations.none { it.taskId == ineligible.id && it !is FocusBlockMutation.Delete })
            assertEquals(true, result.mutations.any { it is FocusBlockMutation.Create && it.draft.taskId == TaskId(id(2)) })
        }
    }

    @Test
    fun `in progress tasks are eligible for automatic planning`() {
        val inProgress = task(1, 1.hours, status = TaskStatus.IN_PROGRESS)
        val result = assertIs<PlannerResult.Success>(DeterministicPlannerV2().fullReplan(snapshot(tasks = listOf(inProgress))))
        assertIs<FocusBlockMutation.Create>(result.mutations.single())
    }

    // ------------------------------------------------------------------ PLN-013 boundary matrix

    @Test
    fun `pln-013 boundary matrix`() {
        fun createDurationFor(remaining: Duration, minimum: Duration = 30.minutes, preferred: Duration = 2.hours, maximum: Duration = 2.hours): Duration? {
            val profile = profile(minimum = minimum, preferred = preferred, maximum = maximum)
            val result = assertIs<PlannerResult.Success>(DeterministicPlannerV2().fullReplan(snapshot(tasks = listOf(task(1, remaining)), profile = profile)))
            val create = result.mutations.filterIsInstance<FocusBlockMutation.Create>().firstOrNull()
            return create?.let { it.draft.time.endExclusive - it.draft.time.start }
        }
        // remaining below the minimum: one final block exactly equal to the remaining effort.
        assertEquals(20.minutes, createDurationFor(20.minutes))
        // remaining equal to the minimum.
        assertEquals(30.minutes, createDurationFor(30.minutes))
        // remaining above the maximum with preferred at the maximum: chunked at the maximum first.
        assertEquals(2.hours, createDurationFor(3.hours, preferred = 2.hours))
        // remaining above the maximum with preferred at the minimum: the preferred-sized chunk wins.
        assertEquals(30.minutes, createDurationFor(3.hours, preferred = 30.minutes))
    }

    @Test
    fun `capacity leaving a sub-minimum remainder cannot host a chunk`() {
        // Availability 09:00-09:20 (20 minutes); remaining 1h, minimum 30 minutes.
        // U = min(R, C, maximum) = 20 minutes < minimum, so the interval hosts nothing.
        val profile = profile(
            windows = listOf(WeeklyAvailabilityWindow(DayOfWeek.MONDAY, LocalTime(9, 0), LocalTime(9, 20))),
            minimum = 30.minutes,
            preferred = 1.hours,
            maximum = 2.hours,
        )
        val result = assertIs<PlannerResult.Success>(DeterministicPlannerV2().fullReplan(snapshot(tasks = listOf(task(1, 1.hours)), profile = profile)))
        assertEquals(true, result.mutations.isEmpty())
        assertEquals(true, result.issues.any { it is PlannerIssue.UnscheduledEffort && it.taskId == TaskId(id(1)) && it.remaining == 1.hours })
    }

    // ------------------------------------------------------------------ PLN-015 ordering

    @Test
    fun `preserve existing placement outranks a smaller movement elsewhere`() {
        val owner = task(1, 1.hours)
        val existing = FocusBlock(FocusBlockId(id(6)), owner.id, range("10:00", "11:00"), Flexibility.SOFT, PinState.UNPINNED)
        val result = assertIs<PlannerResult.Success>(DeterministicPlannerV2().fullReplan(snapshot(tasks = listOf(owner), focusBlocks = listOf(existing))))
        assertEquals(true, result.mutations.isEmpty(), "a legal original placement must be preserved untouched")
    }

    @Test
    fun `chunk duration rank outranks earlier start for new work`() {
        // Free 09:00-13:00, demand 1h: candidate 09:00-10:00 (1h, rank 0) must beat any
        // 30-minute candidate; both start-comparable positions are structural.
        val profile = profile(minimum = 30.minutes, preferred = 1.hours, maximum = 2.hours)
        val result = assertIs<PlannerResult.Success>(DeterministicPlannerV2().fullReplan(snapshot(tasks = listOf(task(1, 1.hours)), profile = profile)))
        val create = assertIs<FocusBlockMutation.Create>(result.mutations.single())
        assertEquals(1.hours, create.draft.time.endExclusive - create.draft.time.start)
    }

    @Test
    fun `canonical identity breaks remaining ties deterministically`() {
        val first = task(1, 30.minutes)
        val second = task(2, 30.minutes)
        val forward = DeterministicPlannerV2().fullReplan(snapshot(tasks = listOf(first, second)))
        val reverse = DeterministicPlannerV2().fullReplan(
            snapshot(tasks = listOf(second, first)).copy(tasks = listOf(second, first).toImmutableList()),
        )
        assertEquals(forward, reverse)
        val success = assertIs<PlannerResult.Success>(forward)
        val creates = success.mutations.filterIsInstance<FocusBlockMutation.Create>()
        assertEquals(2, creates.size)
        assertEquals(at("09:00"), creates[0].draft.time.start)
        assertEquals(at("09:30"), creates[1].draft.time.start)
    }

    // ------------------------------------------------------------------ Local Reflow

    @Test
    fun `local reflow is all or nothing`() {
        val movable = FocusBlock(FocusBlockId(id(6)), TaskId(id(1)), range("09:00", "10:00"), Flexibility.FLEXIBLE, PinState.UNPINNED)
        val stuck = FocusBlock(FocusBlockId(id(7)), TaskId(id(2)), range("12:00", "12:30"), Flexibility.FLEXIBLE, PinState.UNPINNED)
        val barrier = FocusBlock(FocusBlockId(id(9)), TaskId(id(5)), range("10:00", "11:00"), Flexibility.HARD, PinState.UNPINNED)
        // After the first affected block provisionally keeps [09:00,10:00), the only
        // remaining in-window capacity is [11:00,11:25): 25 minutes cannot host the
        // 30-minute second block, so the whole reflow must return Infeasible untouched.
        val request = LocalReflowRequest(
            listOf(movable.id, stuck.id).toImmutableList(),
            persistentListOf(),
            ZonedTimeRange(at("09:00"), at("11:25"), TimeZone.UTC),
        )
        val result = DeterministicPlannerV2().localReflow(
            snapshot(tasks = listOf(task(1, 1.hours), task(2, 30.minutes), task(5, Duration.ZERO)), focusBlocks = listOf(movable, stuck, barrier)),
            request,
        )
        assertIs<PlannerResult.Infeasible>(result)
    }

    @Test
    fun `local reflow reports every immovable conflict`() {
        val pinned = FocusBlock(FocusBlockId(id(6)), TaskId(id(1)), range("09:00", "10:00"), Flexibility.SOFT, PinState.PINNED)
        val hard = FocusBlock(FocusBlockId(id(7)), TaskId(id(2)), range("11:00", "12:00"), Flexibility.HARD, PinState.UNPINNED)
        val request = LocalReflowRequest(
            listOf(pinned.id, hard.id).toImmutableList(),
            persistentListOf(),
            ZonedTimeRange(at("09:00"), at("13:00"), TimeZone.UTC),
        )
        val result = DeterministicPlannerV2().localReflow(
            snapshot(tasks = listOf(task(1, 1.hours), task(2, 1.hours)), focusBlocks = listOf(pinned, hard)),
            request,
        )
        val infeasible = assertIs<PlannerResult.Infeasible>(result)
        assertEquals(2, infeasible.issues.size)
        assertEquals(true, infeasible.issues.all { it is PlannerIssue.ImmovableConflict })
    }

    @Test
    fun `local reflow disrupted ranges extend the affected set`() {
        val near = FocusBlock(FocusBlockId(id(6)), TaskId(id(1)), range("09:00", "10:00"), Flexibility.FLEXIBLE, PinState.UNPINNED)
        val unrelated = FocusBlock(FocusBlockId(id(7)), TaskId(id(2)), range("12:00", "13:00"), Flexibility.FLEXIBLE, PinState.UNPINNED)
        val request = LocalReflowRequest(
            persistentListOf(),
            listOf(ZonedTimeRange(at("08:30"), at("09:30"), TimeZone.UTC)).toImmutableList(),
            ZonedTimeRange(at("09:00"), at("13:00"), TimeZone.UTC),
        )
        val result = assertIs<PlannerResult.Success>(DeterministicPlannerV2().localReflow(
            snapshot(tasks = listOf(task(1, 1.hours), task(2, 1.hours)), focusBlocks = listOf(near, unrelated)),
            request,
        ))
        val moves = result.mutations.filterIsInstance<FocusBlockMutation.Move>()
        assertEquals(1, moves.size, "only blocks intersecting the disrupted range are affected")
        assertEquals(near.id, moves.single().id)
    }

    // ------------------------------------------------------------------ helpers

    private fun profile(
        windows: List<WeeklyAvailabilityWindow> = listOf(WeeklyAvailabilityWindow(DayOfWeek.MONDAY, LocalTime(9, 0), LocalTime(13, 0))),
        minimum: Duration = 30.minutes,
        preferred: Duration = 2.hours,
        maximum: Duration = 2.hours,
        allDayPolicy: AllDayEventPolicy = AllDayEventPolicy.NON_BLOCKING,
        zone: TimeZone = TimeZone.UTC,
    ) = PlanningProfile(
        PlanningProfileId(id(9)),
        "profile",
        PlanningProfileConfiguration.Configured(zone, windows.toImmutableList(), minimum, preferred, maximum, allDayPolicy),
    )

    private fun task(number: Int, remaining: Duration, deadline: TaskDeadline? = null, status: TaskStatus = TaskStatus.OPEN) =
        Task(TaskId(id(number)), "Task $number", status, TaskPriority.NORMAL, TaskEffort(remaining, Duration.ZERO, remaining), deadline)

    private fun range(start: String, end: String) = ZonedTimeRange(at(start), at(end), TimeZone.UTC)

    private fun at(value: String) = Instant.parse("2026-01-05T$value:00Z")

    private fun Local(hour: Int, minute: Int) = kotlinx.datetime.LocalDateTime(2026, 1, 5, hour, minute)

    private fun snapshot(
        tasks: List<Task>,
        focusBlocks: List<FocusBlock> = emptyList(),
        events: List<Event> = emptyList(),
        courseSessions: List<CourseSession> = emptyList(),
        exams: List<Exam> = emptyList(),
        profile: PlanningProfile = profile(),
        referenceNow: String = "2026-01-05T08:00:00Z",
        horizonEnd: String = "2026-01-05T13:00:00Z",
    ) = PlanningSnapshot(
        referenceNow = Instant.parse(referenceNow),
        horizon = PlanningHorizon(Instant.parse(referenceNow), Instant.parse(horizonEnd)),
        profile = profile,
        tasks = tasks.toImmutableList(),
        dependencies = persistentListOf(),
        focusBlocks = focusBlocks.toImmutableList(),
        events = events.toImmutableList(),
        courseSessions = courseSessions.toImmutableList(),
        exams = exams.toImmutableList(),
        constraints = persistentListOf(),
        askOverflowAuthorizedTaskIds = persistentListOf(),
    )

    private fun id(number: Int) = "018f6e68-7d0c-7000-8000-${number.toString().padStart(12, '0')}"
}
