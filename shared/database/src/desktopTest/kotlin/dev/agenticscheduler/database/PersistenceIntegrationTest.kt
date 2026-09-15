package dev.agenticscheduler.database

import dev.agenticscheduler.application.calendar.CalendarViewport
import dev.agenticscheduler.application.calendar.RepositoryCalendarQueryService
import dev.agenticscheduler.application.editing.CreateEventInput
import dev.agenticscheduler.application.editing.CreateTaskInput
import dev.agenticscheduler.application.editing.EditingResult
import dev.agenticscheduler.application.editing.EventEditingService
import dev.agenticscheduler.application.editing.EventTimeInput
import dev.agenticscheduler.application.editing.TaskEditingService
import dev.agenticscheduler.application.editing.UpdateEventInput
import dev.agenticscheduler.application.editing.UpdateTaskInput
import dev.agenticscheduler.application.id.EpochMillisecondsClock
import dev.agenticscheduler.application.id.RandomBytes
import dev.agenticscheduler.application.id.RfcUuidV7Generator
import dev.agenticscheduler.application.history.MutationCoordinator
import dev.agenticscheduler.application.history.MutationWallClock
import dev.agenticscheduler.application.history.NoActiveSyncSpaceWritePolicy
import dev.agenticscheduler.application.history.HistoryQueryService
import dev.agenticscheduler.application.history.DecryptedPayloadReceipt
import dev.agenticscheduler.application.history.SyncEngine
import dev.agenticscheduler.application.history.SyncReceiveResult
import dev.agenticscheduler.application.history.SyncConflictResolutionService
import dev.agenticscheduler.application.history.SyncConflictResolutionResult
import dev.agenticscheduler.application.persistence.CommittedMutation
import dev.agenticscheduler.application.persistence.LocalReplicaCausalState
import dev.agenticscheduler.application.persistence.MutationJournalRepository
import dev.agenticscheduler.database.repository.RoomApplicationTransactionRunner
import dev.agenticscheduler.database.repository.RoomEventRepository
import dev.agenticscheduler.database.repository.RoomTaskRepository
import dev.agenticscheduler.database.repository.RoomPlanningProfileRepository
import dev.agenticscheduler.database.repository.RoomMutationJournalRepository
import dev.agenticscheduler.database.repository.RoomSyncReceiveRepository
import dev.agenticscheduler.domain.event.Event
import dev.agenticscheduler.domain.id.EventId
import dev.agenticscheduler.domain.id.AcademicYearId
import dev.agenticscheduler.domain.id.SemesterId
import dev.agenticscheduler.domain.id.CourseId
import dev.agenticscheduler.domain.id.CourseScheduleRuleId
import dev.agenticscheduler.domain.id.TaskId
import dev.agenticscheduler.domain.planning.Flexibility
import dev.agenticscheduler.domain.planning.PinState
import dev.agenticscheduler.domain.task.Task
import dev.agenticscheduler.domain.task.TaskEffort
import dev.agenticscheduler.domain.task.TaskPriority
import dev.agenticscheduler.domain.task.TaskStatus
import dev.agenticscheduler.domain.time.AllDayRange
import dev.agenticscheduler.domain.time.FloatingTimeRange
import dev.agenticscheduler.domain.time.ZonedTimeRange
import dev.agenticscheduler.domain.academic.*
import dev.agenticscheduler.database.repository.RoomAcademicRepository
import dev.agenticscheduler.database.mapper.*
import dev.agenticscheduler.domain.planning.Deadline
import dev.agenticscheduler.domain.planning.DeadlinePolicy
import dev.agenticscheduler.domain.planning.OverflowPolicy
import dev.agenticscheduler.domain.planning.TaskDeadline
import dev.agenticscheduler.domain.id.PeriodTemplateId
import dev.agenticscheduler.domain.id.CourseOccurrenceExceptionId
import dev.agenticscheduler.domain.id.ExamId
import dev.agenticscheduler.domain.id.FocusBlockId
import dev.agenticscheduler.domain.id.WorkLogId
import dev.agenticscheduler.domain.id.TaskDependencyId
import dev.agenticscheduler.domain.id.PlanningProfileId
import dev.agenticscheduler.domain.id.AcademicHolidayId
import dev.agenticscheduler.sync.operationKind
import dev.agenticscheduler.sync.FocusBlockImage
import dev.agenticscheduler.sync.FocusBlockPut
import dev.agenticscheduler.sync.MutationOrigin
import dev.agenticscheduler.sync.SyncSpaceId
import dev.agenticscheduler.sync.ProtocolQuarantine
import dev.agenticscheduler.sync.ProtocolQuarantineReason
import dev.agenticscheduler.sync.SyncConflict
import dev.agenticscheduler.sync.SyncConflictEntityRef
import dev.agenticscheduler.sync.SyncConflictParticipant
import dev.agenticscheduler.sync.SyncConflictStatus
import dev.agenticscheduler.sync.SyncConflictKind
import dev.agenticscheduler.sync.commonCausalContextOf
import dev.agenticscheduler.sync.MutationId
import dev.agenticscheduler.sync.ReplicaId
import dev.agenticscheduler.sync.DvvSnapshot
import dev.agenticscheduler.sync.DotSnapshot
import dev.agenticscheduler.sync.EntityKind
import dev.agenticscheduler.sync.SyncOperation
import dev.agenticscheduler.sync.SyncPayloadV1
import dev.agenticscheduler.sync.SyncWireCodec
import dev.agenticscheduler.sync.HlcSnapshot
import dev.agenticscheduler.sync.EventPut
import dev.agenticscheduler.sync.EventImage
import dev.agenticscheduler.sync.EventTimeImage
import dev.agenticscheduler.sync.AllDayRangeImage
import dev.agenticscheduler.sync.FlexibilityImage
import dev.agenticscheduler.sync.PinStateImage
import dev.agenticscheduler.sync.TaskPut
import dev.agenticscheduler.sync.TaskImage
import dev.agenticscheduler.sync.TaskStatusImage
import dev.agenticscheduler.sync.TaskPriorityImage
import dev.agenticscheduler.sync.TaskEffortImage
import dev.agenticscheduler.sync.TaskDeadlineImage
import dev.agenticscheduler.sync.DeadlineImage
import dev.agenticscheduler.sync.DeadlinePolicyImage
import dev.agenticscheduler.sync.OverflowPolicyImage
import dev.agenticscheduler.sync.PlanningProfilePut
import dev.agenticscheduler.sync.PlanningProfileImage
import dev.agenticscheduler.sync.PlanningProfileConfigurationImage
import dev.agenticscheduler.sync.CanonicalAvailabilityWindowImage
import dev.agenticscheduler.sync.DayOfWeekImage
import dev.agenticscheduler.sync.AllDayEventPolicyImage
import dev.agenticscheduler.sync.FocusBlockDelete
import dev.agenticscheduler.sync.AcademicYearPut
import dev.agenticscheduler.sync.AcademicYearImage
import dev.agenticscheduler.sync.WorkLogAppend
import dev.agenticscheduler.sync.WorkLogImage
import dev.agenticscheduler.sync.ZonedTimeRangeImage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFails
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.DayOfWeek
import kotlin.time.Instant
import kotlinx.collections.immutable.toImmutableList
import java.nio.file.Files
import androidx.room3.testing.MigrationTestHelper
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import org.junit.Rule

class PersistenceIntegrationTest {
    @get:Rule
    val migrationHelper = MigrationTestHelper(
        java.nio.file.Path.of("schemas"),
        Files.createTempFile("agentic-scheduler-migration-", ".db"),
        BundledSQLiteDriver(),
        AgenticSchedulerDatabase::class,
        { AgenticSchedulerDatabaseConstructor.initialize() },
    )

    @Test fun `event variants preserve precision through SQLite`() = runBlocking {
        val database = openInMemoryDesktopDatabase()
        val repository = RoomEventRepository(database)
        val zoned = Event(EventId(id(1)), "zoned", ZonedTimeRange(Instant.fromEpochSeconds(1_700_000_000, 123_456_789), Instant.fromEpochSeconds(1_700_000_001, 987_654_321), TimeZone.of("Asia/Shanghai")), Flexibility.FLEXIBLE, PinState.UNPINNED)
        val allDay = Event(EventId(id(2)), "all-day", AllDayRange(LocalDate(2026, 9, 1), LocalDate(2026, 9, 2)), Flexibility.FLEXIBLE, PinState.UNPINNED)
        val floating = Event(EventId(id(3)), "floating", FloatingTimeRange(LocalDateTime(2026, 9, 1, 9, 0), LocalDateTime(2026, 9, 1, 10, 0)), Flexibility.FLEXIBLE, PinState.UNPINNED)
        repository.upsert(zoned); repository.upsert(allDay); repository.upsert(floating)
        assertEquals(listOf(zoned, allDay, floating), repository.observeAll().first())
        database.close()
    }

    @Test fun `transaction runner commits and rolls back repository calls`() = runBlocking {
        val database = openInMemoryDesktopDatabase()
        val tasks = RoomTaskRepository(database)
        val runner = RoomApplicationTransactionRunner(database)
        val committed = task(1)
        runner.inWriteTransaction { tasks.upsertTask(committed) }
        assertEquals(committed, tasks.getTask(committed.id))
        val rejected = task(2)
        assertFailsWith<IllegalStateException> { runner.inWriteTransaction { tasks.upsertTask(rejected); error("rollback") } }
        assertEquals(null, tasks.getTask(rejected.id))
        database.close()
    }

    @Test fun `repository emission is explicitly UUID ascending`() = runBlocking {
        val database = openInMemoryDesktopDatabase()
        val tasks = RoomTaskRepository(database)
        tasks.upsertTask(task(3)); tasks.upsertTask(task(1)); tasks.upsertTask(task(2))
        assertEquals(listOf(id(1), id(2), id(3)), tasks.observeTasks().first().map { it.id.value })
        database.close()
    }

    @Test fun `observers receive a new emission after an upsert`() = runBlocking {
        val database = openInMemoryDesktopDatabase()
        val repository = RoomTaskRepository(database)
        val initialEmission = CompletableDeferred<Unit>()
        val next = async(start = CoroutineStart.UNDISPATCHED) {
            repository.observeTasks().onEach { initialEmission.complete(Unit) }.drop(1).first()
        }
        initialEmission.await()
        val value = task(4)
        repository.upsertTask(value)
        assertEquals(listOf(value), next.await())
        database.close()
    }

    @Test fun `source facts survive close reopen and rederive the same course session`() = runBlocking {
        val path = Files.createTempFile("agentic-scheduler-d4-", ".db")
        Files.delete(path)
        val year = AcademicYear(AcademicYearId(id(10)), "2026-27", LocalDate(2026, 9, 1), LocalDate(2027, 1, 1))
        val semester = Semester(SemesterId(id(11)), year.id, "Autumn", LocalDate(2026, 9, 1), LocalDate(2026, 12, 1), TimeZone.of("Asia/Shanghai"), listOf(AcademicWeek(AcademicWeekNumber(1), LocalDate(2026, 9, 7), LocalDate(2026, 9, 14))).toImmutableList())
        val course = Course(CourseId(id(12)), semester.id, "Math", null)
        val rule = CourseScheduleRule(CourseScheduleRuleId(id(13)), course.id, DayOfWeek.MONDAY, TeachingWeekSet.of(listOf(AcademicWeekNumber(1))), CourseTimeSpec.ClockTime(kotlinx.datetime.LocalTime(9, 0), kotlinx.datetime.LocalTime(10, 0)), "A101")
        val firstDatabase = openDesktopDatabase(path.toString())
        val first = RoomAcademicRepository(firstDatabase)
        first.upsertAcademicYear(year); first.upsertSemester(semester); first.upsertCourse(course); first.upsertCourseScheduleRule(rule)
        val before = resolveCourseSessions(semester, course, listOf(rule), emptyList(), emptyList(), emptyList())
        firstDatabase.close()
        val secondDatabase = openDesktopDatabase(path.toString())
        val second = RoomAcademicRepository(secondDatabase)
        val afterSemester = second.getSemester(semester.id)!!
        val afterCourse = second.getCourse(course.id)!!
        val afterRule = second.getCourseScheduleRule(rule.id)!!
        val after = resolveCourseSessions(afterSemester, afterCourse, listOf(afterRule), emptyList(), emptyList(), emptyList())
        assertEquals(before, after)
        secondDatabase.close(); Files.deleteIfExists(path); Unit
    }

    @Test fun `calendar projection is identical after close reopen for the same viewport`() = runBlocking {
        val path = Files.createTempFile("agentic-scheduler-d5-", ".db")
        Files.delete(path)
        val event = Event(EventId(id(14)), "D5 event", ZonedTimeRange(Instant.parse("2026-09-08T01:00:00Z"), Instant.parse("2026-09-08T02:00:00Z"), TimeZone.of("Asia/Shanghai")), Flexibility.FLEXIBLE, PinState.UNPINNED)
        val viewport = CalendarViewport(LocalDate(2026, 9, 8), LocalDate(2026, 9, 9), TimeZone.of("Asia/Shanghai"))
        val firstDatabase = openDesktopDatabase(path.toString())
        val firstEvents = RoomEventRepository(firstDatabase)
        firstEvents.upsert(event)
        val before = RepositoryCalendarQueryService(firstEvents, RoomTaskRepository(firstDatabase), RoomAcademicRepository(firstDatabase)).observe(viewport).first()
        firstDatabase.close()
        val secondDatabase = openDesktopDatabase(path.toString())
        val after = RepositoryCalendarQueryService(RoomEventRepository(secondDatabase), RoomTaskRepository(secondDatabase), RoomAcademicRepository(secondDatabase)).observe(viewport).first()
        assertEquals(before, after)
        secondDatabase.close(); Files.deleteIfExists(path); Unit
    }

    @Test fun `D5 editors persist source facts and refresh the calendar without FocusBlocks`() = runBlocking {
        val path = Files.createTempFile("agentic-scheduler-d5-edit-", ".db")
        Files.delete(path)
        var randomSeed = 0
        val ids = RfcUuidV7Generator(
            EpochMillisecondsClock { Instant.parse("2026-09-10T00:00:00Z").toEpochMilliseconds() },
            RandomBytes { size -> ByteArray(size) { index -> (randomSeed + index).toByte() }.also { randomSeed += 1 } },
        )
        val firstDatabase = openDesktopDatabase(path.toString())
        val events = RoomEventRepository(firstDatabase)
        val tasks = RoomTaskRepository(firstDatabase)
        val runner = RoomApplicationTransactionRunner(firstDatabase)
        val coordinator = MutationCoordinator(runner, RoomMutationJournalRepository(firstDatabase), ids, MutationWallClock { 1 })
        val event = assertIs<Event>(assertIs<EditingResult.Success<*>>(EventEditingService(events, ids, coordinator, NoActiveSyncSpaceWritePolicy).create(
            CreateEventInput(
                "Created event",
                EventTimeInput.AllDay(LocalDate(2026, 9, 10), LocalDate(2026, 9, 11)),
                Flexibility.HARD,
                PinState.UNPINNED,
            ),
        )).value)
        val task = assertIs<Task>(assertIs<EditingResult.Success<*>>(TaskEditingService(tasks, ids, coordinator, NoActiveSyncSpaceWritePolicy).create(
            CreateTaskInput("Created task", TaskPriority.NORMAL, null, null, null),
        )).value)
        val updatedEvent = assertIs<Event>(assertIs<EditingResult.Success<*>>(EventEditingService(events, ids, coordinator, NoActiveSyncSpaceWritePolicy).update(
            UpdateEventInput(event.id, "Updated event", EventTimeInput.AllDay(LocalDate(2026, 9, 10), LocalDate(2026, 9, 11)), Flexibility.HARD, PinState.UNPINNED),
        )).value)
        val updatedTask = assertIs<Task>(assertIs<EditingResult.Success<*>>(TaskEditingService(tasks, ids, coordinator, NoActiveSyncSpaceWritePolicy).update(
            UpdateTaskInput(task.id, "Updated task", TaskStatus.IN_PROGRESS, TaskPriority.HIGH, null, kotlin.time.Duration.ZERO, null, null),
        )).value)
        val history = RoomMutationJournalRepository(firstDatabase)
        val timeline = history.timeline()
        assertEquals(4, timeline.size, "Every Event/Task command must commit one atomic journal operation.")
        assertEquals(listOf("EventPut", "TaskPut", "EventPut", "TaskPut"), timeline.map { it.operation.orderedMutations.single().operationKind() })
        timeline.forEach { committed ->
            val diff = history.diff(committed.operation.mutationId)
            assertEquals(listOf(0), diff.map { it.ordinal }, "Single-command ChangeLog must start at ordinal zero.")
            assertEquals(committed.operation.orderedMutations.single().entityId, diff.single().entityId)
        }
        assertEquals(updatedEvent, events.get(event.id)); assertEquals(updatedTask, tasks.getTask(task.id))
        val viewport = CalendarViewport(LocalDate(2026, 9, 10), LocalDate(2026, 9, 11), TimeZone.UTC)
        val projection = RepositoryCalendarQueryService(events, tasks, RoomAcademicRepository(firstDatabase)).observe(viewport).first()
        assertTrue(projection.items.any { it.title == updatedEvent.title })
        assertTrue(tasks.observeFocusBlocks().first().isEmpty())
        firstDatabase.close()

        val secondDatabase = openDesktopDatabase(path.toString())
        val reloadedEvents = RoomEventRepository(secondDatabase)
        val reloadedTasks = RoomTaskRepository(secondDatabase)
        assertEquals(updatedEvent, reloadedEvents.get(event.id))
        assertEquals(updatedTask, reloadedTasks.getTask(task.id))
        secondDatabase.close(); Files.deleteIfExists(path); Unit
    }

    @Test fun `all D4 discriminated domain variants round trip through explicit mappers`() {
        val exactTask = task(20).copy(deadline = TaskDeadline(Deadline.Exact(Instant.fromEpochSeconds(10, 999_999_999), TimeZone.of("America/New_York")), DeadlinePolicy.HARD, OverflowPolicy.ASK))
        val dateTask = task(21).copy(deadline = TaskDeadline(Deadline.DateOnly(LocalDate(2026, 10, 1)), DeadlinePolicy.NORMAL, OverflowPolicy.NEVER))
        assertEquals(exactTask, exactTask.toRecord().toDomain()); assertEquals(dateTask, dateTask.toRecord().toDomain())
        val exactRange = ZonedTimeRange(Instant.fromEpochSeconds(100, 1), Instant.fromEpochSeconds(200, 2), TimeZone.of("Asia/Shanghai"))
        val focus = dev.agenticscheduler.domain.task.FocusBlock(FocusBlockId(id(40)), exactTask.id, exactRange, Flexibility.FLEXIBLE, PinState.UNPINNED)
        val workLog = dev.agenticscheduler.domain.task.WorkLog(WorkLogId(id(41)), exactTask.id, exactRange)
        val dependency = dev.agenticscheduler.domain.task.TaskDependency(TaskDependencyId(id(42)), exactTask.id, dateTask.id)
        val profile = dev.agenticscheduler.domain.planning.PlanningProfile(PlanningProfileId(id(43)), "Default", dev.agenticscheduler.domain.planning.PlanningProfileConfiguration.Unconfigured)
        assertEquals(focus, focus.toRecord().toDomain()); assertEquals(workLog, workLog.toRecord().toDomain()); assertEquals(dependency, dependency.toRecord().toDomain()); assertEquals(profile, profile.toRecord().toDomain())
        val year = AcademicYear(AcademicYearId(id(44)), "2026-27", LocalDate(2026, 9, 1), LocalDate(2027, 1, 1))
        val semester = Semester(SemesterId(id(45)), year.id, "Autumn", LocalDate(2026, 9, 1), LocalDate(2026, 12, 1), TimeZone.of("Asia/Shanghai"), listOf(AcademicWeek(AcademicWeekNumber(1), LocalDate(2026, 9, 7), LocalDate(2026, 9, 14))).toImmutableList())
        assertEquals(year, year.toRecord().toDomain()); assertEquals(semester, semester.toRecord().toDomain(semester.weekRecords()))
        val course = Course(CourseId(id(22)), SemesterId(id(23)), "Science", "SCI")
        val clock = CourseScheduleRule(CourseScheduleRuleId(id(24)), course.id, DayOfWeek.TUESDAY, TeachingWeekSet.of(listOf(AcademicWeekNumber(2), AcademicWeekNumber(1))), CourseTimeSpec.ClockTime(kotlinx.datetime.LocalTime(8, 0), kotlinx.datetime.LocalTime(9, 0)), null)
        val period = CourseScheduleRule(CourseScheduleRuleId(id(25)), course.id, DayOfWeek.WEDNESDAY, TeachingWeekSet.of(listOf(AcademicWeekNumber(1))), CourseTimeSpec.PeriodBased(PeriodTemplateId(id(26)), AcademicPeriodNumber(1), AcademicPeriodNumber(2)), "B201")
        assertEquals(clock, clock.toRecord().toDomain(clock.weekRecords())); assertEquals(period, period.toRecord().toDomain(period.weekRecords()))
        val template = PeriodTemplate(PeriodTemplateId(id(46)), "Periods", listOf(AcademicPeriod(AcademicPeriodNumber(1), kotlinx.datetime.LocalTime(8, 0), kotlinx.datetime.LocalTime(8, 45))).toImmutableList())
        val holiday = AcademicHoliday(AcademicHolidayId(id(47)), semester.id, "Holiday", AllDayRange(LocalDate(2026, 10, 1), LocalDate(2026, 10, 2)), AcademicHolidayTeachingEffect.SUSPEND_TEACHING)
        assertEquals(course, course.toRecord().toDomain()); assertEquals(template, template.toRecord().toDomain(template.periodRecords())); assertEquals(holiday, holiday.toRecord().toDomain())
        val key = CourseOccurrenceKey(clock.id, AcademicWeekNumber(1))
        val override = ZonedTimeRange(Instant.fromEpochSeconds(100, 1), Instant.fromEpochSeconds(200, 2), TimeZone.of("Asia/Shanghai"))
        listOf(RoomOverride.Unchanged, RoomOverride.Clear, RoomOverride.Set("C301")).forEachIndexed { index, room ->
            val exception = CourseOccurrenceException(CourseOccurrenceExceptionId(id(27 + index)), key, CourseOccurrenceDisposition.ACTIVE, override, room)
            assertEquals(exception, exception.toRecord().toDomain())
        }
        val exams = listOf<ExamSchedule>(ExamSchedule.Unscheduled, ExamSchedule.DateOnly(LocalDate(2026, 10, 2)), ExamSchedule.Exact(override))
        exams.forEachIndexed { index, schedule ->
            val exam = Exam(ExamId(id(30 + index)), course.semesterId, course.id, "Exam $index", schedule)
            assertEquals(exam, exam.toRecord().toDomain())
        }
    }

    @Test fun `outer transaction rolls back a semester and all of its child weeks`() = runBlocking {
        val database = openInMemoryDesktopDatabase()
        val repository = RoomAcademicRepository(database)
        val runner = RoomApplicationTransactionRunner(database)
        val year = AcademicYear(AcademicYearId(id(50)), "2026-27", LocalDate(2026, 9, 1), LocalDate(2027, 1, 1))
        val semester = Semester(SemesterId(id(51)), year.id, "Autumn", LocalDate(2026, 9, 1), LocalDate(2026, 12, 1), TimeZone.of("Asia/Shanghai"), listOf(AcademicWeek(AcademicWeekNumber(1), LocalDate(2026, 9, 7), LocalDate(2026, 9, 14))).toImmutableList())
        repository.upsertAcademicYear(year)
        assertFailsWith<IllegalStateException> { runner.inWriteTransaction { repository.upsertSemester(semester); error("rollback aggregate") } }
        assertEquals(null, repository.getSemester(semester.id))
        database.close()
    }

    @Test fun `all authoritative D2 and D3 source facts round trip through SQLite`() = runBlocking {
        val database = openInMemoryDesktopDatabase()
        val tasks = RoomTaskRepository(database)
        val profiles = RoomPlanningProfileRepository(database)
        val academic = RoomAcademicRepository(database)
        val range = ZonedTimeRange(Instant.fromEpochSeconds(500, 123), Instant.fromEpochSeconds(800, 456), TimeZone.of("Asia/Shanghai"))
        val firstTask = task(60).copy(deadline = TaskDeadline(Deadline.Exact(range.start, range.timeZone), DeadlinePolicy.HARD, OverflowPolicy.ASK))
        val secondTask = task(61)
        val focus = dev.agenticscheduler.domain.task.FocusBlock(FocusBlockId(id(62)), firstTask.id, range, Flexibility.FLEXIBLE, PinState.UNPINNED)
        val log = dev.agenticscheduler.domain.task.WorkLog(WorkLogId(id(63)), firstTask.id, range)
        val dependency = dev.agenticscheduler.domain.task.TaskDependency(TaskDependencyId(id(64)), firstTask.id, secondTask.id)
        val profile = dev.agenticscheduler.domain.planning.PlanningProfile(PlanningProfileId(id(65)), "Default", dev.agenticscheduler.domain.planning.PlanningProfileConfiguration.Unconfigured)
        tasks.upsertTask(firstTask); tasks.upsertTask(secondTask); tasks.upsertFocusBlock(focus); tasks.upsertWorkLog(log); tasks.upsertDependency(dependency); profiles.upsert(profile)
        assertEquals("PT0S", database.taskDao().get(firstTask.id.value)?.effortCompletedIso)
        assertEquals(firstTask, tasks.getTask(firstTask.id)); assertEquals(focus, tasks.getFocusBlock(focus.id)); assertEquals(log, tasks.getWorkLog(log.id)); assertEquals(dependency, tasks.getDependency(dependency.id)); assertEquals(profile, profiles.get(profile.id))
        val year = AcademicYear(AcademicYearId(id(66)), "2026-27", LocalDate(2026, 9, 1), LocalDate(2027, 1, 1))
        val semester = Semester(SemesterId(id(67)), year.id, "Autumn", LocalDate(2026, 9, 1), LocalDate(2026, 12, 1), TimeZone.of("Asia/Shanghai"), listOf(AcademicWeek(AcademicWeekNumber(1), LocalDate(2026, 9, 7), LocalDate(2026, 9, 14))).toImmutableList())
        val course = Course(CourseId(id(68)), semester.id, "Math", "MATH")
        val template = PeriodTemplate(PeriodTemplateId(id(69)), "Periods", listOf(AcademicPeriod(AcademicPeriodNumber(1), kotlinx.datetime.LocalTime(9, 0), kotlinx.datetime.LocalTime(10, 0))).toImmutableList())
        val rule = CourseScheduleRule(CourseScheduleRuleId(id(70)), course.id, DayOfWeek.MONDAY, TeachingWeekSet.of(listOf(AcademicWeekNumber(1))), CourseTimeSpec.PeriodBased(template.id, AcademicPeriodNumber(1), AcademicPeriodNumber(1)), "A101")
        val holiday = AcademicHoliday(AcademicHolidayId(id(71)), semester.id, "Break", AllDayRange(LocalDate(2026, 10, 1), LocalDate(2026, 10, 2)), AcademicHolidayTeachingEffect.SUSPEND_TEACHING)
        val exception = CourseOccurrenceException(CourseOccurrenceExceptionId(id(72)), CourseOccurrenceKey(rule.id, AcademicWeekNumber(1)), CourseOccurrenceDisposition.ACTIVE, null, RoomOverride.Clear)
        val exam = Exam(ExamId(id(73)), semester.id, course.id, "Final", ExamSchedule.Exact(range))
        academic.upsertAcademicYear(year); academic.upsertSemester(semester); academic.upsertCourse(course); academic.upsertPeriodTemplate(template); academic.upsertCourseScheduleRule(rule); academic.upsertAcademicHoliday(holiday); academic.upsertCourseOccurrenceException(exception); academic.upsertExam(exam)
        assertEquals(year, academic.getAcademicYear(year.id)); assertEquals(semester, academic.getSemester(semester.id)); assertEquals(course, academic.getCourse(course.id)); assertEquals(template, academic.getPeriodTemplate(template.id)); assertEquals(rule, academic.getCourseScheduleRule(rule.id)); assertEquals(holiday, academic.getAcademicHoliday(holiday.id)); assertEquals(exception, academic.getCourseOccurrenceException(exception.id)); assertEquals(exam, academic.getExam(exam.id))
        database.close()
    }

    @Test fun `foreign keys and unique constraints reject invalid records`() = runBlocking {
        val database = openInMemoryDesktopDatabase()
        val tasks = RoomTaskRepository(database)
        val range = ZonedTimeRange(Instant.fromEpochSeconds(1), Instant.fromEpochSeconds(2), TimeZone.of("UTC"))
        val orphan = dev.agenticscheduler.domain.task.FocusBlock(FocusBlockId(id(80)), TaskId(id(81)), range, Flexibility.FLEXIBLE, PinState.UNPINNED)
        assertFails { tasks.upsertFocusBlock(orphan) }
        val prerequisite = task(82); val dependent = task(83)
        tasks.upsertTask(prerequisite); tasks.upsertTask(dependent)
        tasks.upsertDependency(dev.agenticscheduler.domain.task.TaskDependency(TaskDependencyId(id(84)), prerequisite.id, dependent.id))
        assertFails { tasks.upsertDependency(dev.agenticscheduler.domain.task.TaskDependency(TaskDependencyId(id(85)), prerequisite.id, dependent.id)) }
        val academic = RoomAcademicRepository(database)
        val year = AcademicYear(AcademicYearId(id(86)), "2026-27", LocalDate(2026, 9, 1), LocalDate(2027, 1, 1))
        val semester = Semester(SemesterId(id(87)), year.id, "Autumn", LocalDate(2026, 9, 1), LocalDate(2026, 12, 1), TimeZone.of("UTC"), listOf(AcademicWeek(AcademicWeekNumber(1), LocalDate(2026, 9, 7), LocalDate(2026, 9, 14))).toImmutableList())
        val course = Course(CourseId(id(88)), semester.id, "Math", null)
        val rule = CourseScheduleRule(CourseScheduleRuleId(id(89)), course.id, DayOfWeek.MONDAY, TeachingWeekSet.of(listOf(AcademicWeekNumber(1))), CourseTimeSpec.ClockTime(kotlinx.datetime.LocalTime(9, 0), kotlinx.datetime.LocalTime(10, 0)), null)
        academic.upsertAcademicYear(year); academic.upsertSemester(semester); academic.upsertCourse(course); academic.upsertCourseScheduleRule(rule)
        val key = CourseOccurrenceKey(rule.id, AcademicWeekNumber(1))
        academic.upsertCourseOccurrenceException(CourseOccurrenceException(CourseOccurrenceExceptionId(id(90)), key, CourseOccurrenceDisposition.ACTIVE, null, RoomOverride.Unchanged))
        assertFails { academic.upsertCourseOccurrenceException(CourseOccurrenceException(CourseOccurrenceExceptionId(id(91)), key, CourseOccurrenceDisposition.ACTIVE, null, RoomOverride.Unchanged)) }
        database.close()
    }

    @Test fun `corrupt records fail visibly including strict ISO duration decoding`() {
        val event = Event(EventId(id(90)), "event", ZonedTimeRange(Instant.fromEpochSeconds(1), Instant.fromEpochSeconds(2), TimeZone.of("UTC")), Flexibility.FLEXIBLE, PinState.UNPINNED)
        assertFails { event.toRecord().copy(id = "invalid").toDomain() }
        assertFails { event.toRecord().copy(flexibility = "UNKNOWN").toDomain() }
        assertFails { event.toRecord().copy(timeKind = "ZONED", zonedStartNanos = null).toDomain() }
        assertFails { task(91).toRecord().copy(effortCompletedIso = "1h").toDomain() }
    }

    @Test fun `configured planning profile and availability windows survive SQLite round trip`() = runBlocking {
        val database = openInMemoryDesktopDatabase()
        val repository = RoomPlanningProfileRepository(database)
        val profile = dev.agenticscheduler.domain.planning.PlanningProfile(
            PlanningProfileId(id(94)), "Configured",
            dev.agenticscheduler.domain.planning.PlanningProfileConfiguration.Configured(
                TimeZone.of("Asia/Shanghai"), listOf(
                    dev.agenticscheduler.domain.planning.WeeklyAvailabilityWindow(DayOfWeek.TUESDAY, kotlinx.datetime.LocalTime(9, 0), kotlinx.datetime.LocalTime(11, 0)),
                    dev.agenticscheduler.domain.planning.WeeklyAvailabilityWindow(DayOfWeek.MONDAY, kotlinx.datetime.LocalTime(8, 0), kotlinx.datetime.LocalTime(10, 0)),
                ).toImmutableList(), kotlin.time.Duration.parseIsoString("PT30M"), kotlin.time.Duration.parseIsoString("PT45M"), kotlin.time.Duration.parseIsoString("PT1H"),
                dev.agenticscheduler.domain.planning.AllDayEventPolicy.BLOCK_WHOLE_LOCAL_DAY,
            ),
        )
        repository.upsert(profile)
        assertEquals(profile, repository.get(profile.id))
        assertEquals(2, database.planningProfileDao().windows(profile.id.value).size)
        database.close()
    }

    @Test fun `focus block delete is an active state operation`() = runBlocking {
        val database = openInMemoryDesktopDatabase(); val tasks = RoomTaskRepository(database)
        val source = task(95); tasks.upsertTask(source)
        val focus = dev.agenticscheduler.domain.task.FocusBlock(FocusBlockId(id(96)), source.id, ZonedTimeRange(Instant.fromEpochSeconds(1), Instant.fromEpochSeconds(2), TimeZone.UTC), Flexibility.SOFT, PinState.UNPINNED)
        tasks.upsertFocusBlock(focus); tasks.deleteFocusBlock(focus.id)
        assertEquals(null, tasks.getFocusBlock(focus.id)); database.close()
    }

    @Test fun `exported v1 schema migrates through Room schema v2`() = runBlocking {
        val legacy = migrationHelper.createDatabase(1)
        legacy.prepare("INSERT INTO planning_profiles (id, name) VALUES (?, ?)").use { statement ->
            statement.bindText(1, id(97))
            statement.bindText(2, "Legacy profile")
            statement.step()
        }
        legacy.close()
        val migrated = migrationHelper.runMigrationsAndValidate(2, emptyList())
        migrated.prepare("SELECT name, configuration_state FROM planning_profiles WHERE id = ?").use { statement ->
            statement.bindText(1, id(97))
            assertEquals(true, statement.step())
            assertEquals("Legacy profile", statement.getText(0))
            assertEquals("UNCONFIGURED", statement.getText(1))
        }
        migrated.close()
    }

    @Test fun `exported v2 schema migrates to v3 without losing D6 facts`() = runBlocking {
        val legacy = migrationHelper.createDatabase(2)
        legacy.prepare("INSERT INTO planning_profiles (id, name, configuration_state) VALUES (?, ?, ?)").use { statement ->
            statement.bindText(1, id(98)); statement.bindText(2, "D6 profile"); statement.bindText(3, "UNCONFIGURED"); statement.step()
        }
        legacy.close()
        val migrated = migrationHelper.runMigrationsAndValidate(3, emptyList())
        migrated.prepare("SELECT name FROM planning_profiles WHERE id = ?").use { statement ->
            statement.bindText(1, id(98)); assertEquals(true, statement.step()); assertEquals("D6 profile", statement.getText(0))
        }
        migrated.prepare("SELECT name FROM sqlite_master WHERE type='table' AND name='sync_operation_journal'").use { statement -> assertEquals(true, statement.step()) }
        migrated.close()
    }

    @Test fun `exported v3 schema migrates to v4 with D8 receive tables`() = runBlocking {
        val legacy = migrationHelper.createDatabase(3)
        legacy.prepare("INSERT INTO tasks (id, title, status, priority, effort_estimated_iso, effort_completed_iso, effort_remaining_iso) VALUES (?, ?, ?, ?, ?, ?, ?)").use { statement ->
            statement.bindText(1, id(99)); statement.bindText(2, "D7 fact"); statement.bindText(3, "OPEN"); statement.bindText(4, "NORMAL"); statement.bindNull(5); statement.bindText(6, "PT0S"); statement.bindNull(7); statement.step()
        }
        legacy.close()
        val migrated = migrationHelper.runMigrationsAndValidate(4, emptyList())
        migrated.prepare("SELECT title FROM tasks WHERE id = ?").use { statement -> statement.bindText(1, id(99)); assertEquals(true, statement.step()); assertEquals("D7 fact", statement.getText(0)) }
        listOf("sync_space_cursor", "protocol_quarantine", "sync_conflict").forEach { table ->
            migrated.prepare("SELECT name FROM sqlite_master WHERE type='table' AND name=?").use { statement -> statement.bindText(1, table); assertEquals(true, statement.step(), table) }
        }
        migrated.close()
    }

    @Test fun `exported v4 schema migrates to v5 with durable causal-gap state`() = runBlocking {
        val legacy = migrationHelper.createDatabase(4)
        legacy.prepare("INSERT INTO sync_space_cursor (sync_space_id, server_cursor) VALUES (?, ?)").use { statement ->
            statement.bindText(1, "personal-space"); statement.bindLong(2, 7); statement.step()
        }
        legacy.close()
        val migrated = migrationHelper.runMigrationsAndValidate(5, emptyList())
        migrated.prepare("SELECT server_cursor FROM sync_space_cursor WHERE sync_space_id = ?").use { statement ->
            statement.bindText(1, "personal-space"); assertEquals(true, statement.step()); assertEquals(7L, statement.getLong(0))
        }
        listOf("pending_sync_receive", "handled_receive_dot").forEach { table ->
            migrated.prepare("SELECT name FROM sqlite_master WHERE type='table' AND name=?").use { statement -> statement.bindText(1, table); assertEquals(true, statement.step(), table) }
        }
        migrated.close()
    }

    @Test fun `D8 receive metadata persists cursor quarantine and structured conflict`() = runBlocking {
        val database = openInMemoryDesktopDatabase()
        val repository = RoomSyncReceiveRepository(database)
        val space = SyncSpaceId("personal-space")
        assertEquals(0L, repository.serverCursor(space))
        repository.saveServerCursor(space, 17)
        assertEquals(17L, repository.serverCursor(space))
        val quarantine = ProtocolQuarantine(space, id(40), 18, ProtocolQuarantineReason.UNSUPPORTED_MUTATION, "FutureMutation")
        repository.quarantine(quarantine)
        assertEquals(quarantine, repository.quarantine(space, id(40)))
        val first = MutationId(id(41)); val second = MutationId(id(42))
        val participants = listOf(
            SyncConflictParticipant(first, DvvSnapshot(emptyList(), DotSnapshot(id(45), 1)), "{\"time\":\"first\"}"),
            SyncConflictParticipant(second, DvvSnapshot(emptyList(), DotSnapshot(id(46), 1)), "{\"time\":\"second\"}"),
        )
        val conflict = SyncConflict(
            conflictId = id(43),
            syncSpaceId = space,
            entityRefs = listOf(SyncConflictEntityRef(EntityKind.EVENT, id(44), listOf("time"))),
            participants = participants,
            provisionalMutationId = first,
            kind = SyncConflictKind.SEMANTIC,
            commonCausalContext = commonCausalContextOf(participants),
            status = SyncConflictStatus.OPEN,
        )
        repository.saveConflict(conflict)
        assertEquals(conflict, repository.conflict(conflict.conflictId))
        assertEquals(listOf(conflict), repository.conflicts(space))
        database.close()
    }

    @Test fun `D8 receive applies deduplicates quarantines and merges concurrent disjoint operations`() = runBlocking {
        val database = openInMemoryDesktopDatabase()
        val ids = RfcUuidV7Generator(EpochMillisecondsClock { 1 }, RandomBytes { ByteArray(it) { 1 } })
        val journal = RoomMutationJournalRepository(database)
        val receive = RoomSyncReceiveRepository(database)
        val engine = SyncEngine(
            RoomApplicationTransactionRunner(database), journal, journal, receive,
            RoomEventRepository(database), RoomTaskRepository(database), RoomPlanningProfileRepository(database), RoomAcademicRepository(database),
            ids, MutationWallClock { 1 },
        )
        val space = SyncSpaceId("personal-space")
        val first = remoteEventOperation(id(60), id(61), id(62), "First")
        val firstPayload = SyncWireCodec.encodePayload(SyncPayloadV1(operation = first))
        assertEquals(SyncReceiveResult.Applied(MutationId(first.mutationId)), engine.receive(DecryptedPayloadReceipt(space, first.mutationId, 5, firstPayload)))
        assertEquals("First", RoomEventRepository(database).get(EventId(id(62)))?.title)
        assertEquals(first, journal.mutation(first.mutationId)?.operation)
        assertEquals(5L, receive.serverCursor(space))
        assertEquals(SyncReceiveResult.Duplicate(MutationId(first.mutationId)), engine.receive(DecryptedPayloadReceipt(space, first.mutationId, 6, firstPayload)))
        assertEquals(6L, receive.serverCursor(space))

        val after = SyncOperation(
            id(67), DvvSnapshot(listOf(dev.agenticscheduler.sync.VersionComponent(id(61), 1)), DotSnapshot(id(61), 2)), HlcSnapshot(11, 0, id(61)), MutationOrigin.User,
            listOf(EventPut(EventImage(id(62), "First", EventTimeImage.AllDay(AllDayRangeImage("2026-01-01", "2026-01-02")), FlexibilityImage.HARD, PinStateImage.UNPINNED), EventImage(id(62), "After", EventTimeImage.AllDay(AllDayRangeImage("2026-01-01", "2026-01-02")), FlexibilityImage.HARD, PinStateImage.UNPINNED))),
        )
        assertEquals(SyncReceiveResult.Applied(MutationId(after.mutationId)), engine.receive(DecryptedPayloadReceipt(space, after.mutationId, 7, SyncWireCodec.encodePayload(SyncPayloadV1(operation = after)))))
        assertEquals("After", RoomEventRepository(database).get(EventId(id(62)))?.title)

        val malformedId = id(63)
        assertEquals(SyncReceiveResult.Quarantined(malformedId, ProtocolQuarantineReason.UNSUPPORTED_MUTATION), engine.receive(DecryptedPayloadReceipt(space, malformedId, 8, firstPayload.replace("\"type\":\"EventPut\"", "\"type\":\"FutureMutation\""))))
        assertEquals(8L, receive.serverCursor(space))
        assertEquals(ProtocolQuarantineReason.UNSUPPORTED_MUTATION, receive.quarantine(space, malformedId)?.reason)

        val concurrent = remoteEventOperation(id(64), id(65), id(66), "Concurrent")
        assertEquals(SyncReceiveResult.Applied(MutationId(concurrent.mutationId)), engine.receive(DecryptedPayloadReceipt(space, concurrent.mutationId, 9, SyncWireCodec.encodePayload(SyncPayloadV1(operation = concurrent)))))
        assertEquals("Concurrent", RoomEventRepository(database).get(EventId(id(66)))?.title)
        assertEquals(9L, receive.serverCursor(space))
        assertEquals(concurrent, journal.mutation(concurrent.mutationId)?.operation)
        database.close()
    }

    @Test fun `D8 WorkLog divergence becomes one durable integrity conflict without overwrite`() = runBlocking {
        val database = openInMemoryDesktopDatabase()
        val ids = RfcUuidV7Generator(EpochMillisecondsClock { 1 }, RandomBytes { ByteArray(it) { 1 } })
        val journal = RoomMutationJournalRepository(database); val receive = RoomSyncReceiveRepository(database)
        val engine = SyncEngine(RoomApplicationTransactionRunner(database), journal, journal, receive, RoomEventRepository(database), RoomTaskRepository(database), RoomPlanningProfileRepository(database), RoomAcademicRepository(database), ids, MutationWallClock { 1 })
        val space = SyncSpaceId("personal-space")
        val taskId = id(70); val logId = id(71); val eventId = id(76); val replica = id(72)
        val original = ZonedTimeRangeImage("2026-01-01T10:00:00Z", "2026-01-01T11:00:00Z", "UTC")
        val initial = SyncOperation(id(73), DvvSnapshot(emptyList(), DotSnapshot(replica, 1)), HlcSnapshot(1, 0, replica), MutationOrigin.User, listOf(
            TaskPut(null, TaskImage(taskId, "task", TaskStatusImage.OPEN, TaskPriorityImage.NORMAL, TaskEffortImage(null, "PT0S", null), null)),
            WorkLogAppend(WorkLogImage(logId, taskId, original)),
        ))
        assertEquals(SyncReceiveResult.Applied(MutationId(initial.mutationId)), engine.receive(DecryptedPayloadReceipt(space, initial.mutationId, 1, SyncWireCodec.encodePayload(SyncPayloadV1(operation = initial)))))
        val coalescingReplica = id(75)
        val equalAppend = SyncOperation(id(77), DvvSnapshot(emptyList(), DotSnapshot(coalescingReplica, 1)), HlcSnapshot(2, 0, coalescingReplica), MutationOrigin.User, listOf(
            WorkLogAppend(WorkLogImage(logId, taskId, original)),
        ))
        assertEquals(SyncReceiveResult.Applied(MutationId(equalAppend.mutationId)), engine.receive(DecryptedPayloadReceipt(space, equalAppend.mutationId, 2, SyncWireCodec.encodePayload(SyncPayloadV1(operation = equalAppend)))))
        val competingReplica = id(78)
        val divergent = SyncOperation(id(74), DvvSnapshot(emptyList(), DotSnapshot(competingReplica, 1)), HlcSnapshot(2, 0, competingReplica), MutationOrigin.User, listOf(
            EventPut(null, EventImage(eventId, "must not partially apply", EventTimeImage.AllDay(AllDayRangeImage("2026-01-01", "2026-01-02")), FlexibilityImage.HARD, PinStateImage.UNPINNED)),
            WorkLogAppend(WorkLogImage(logId, taskId, ZonedTimeRangeImage("2026-01-01T12:00:00Z", "2026-01-01T13:00:00Z", "UTC"))),
        ))
        val result = assertIs<SyncReceiveResult.Conflicted>(engine.receive(DecryptedPayloadReceipt(space, divergent.mutationId, 3, SyncWireCodec.encodePayload(SyncPayloadV1(operation = divergent)))))
        assertEquals(SyncConflictKind.INTEGRITY, result.kind)
        val preserved = requireNotNull(RoomTaskRepository(database).getWorkLog(WorkLogId(logId)))
        assertEquals(original.start, preserved.time.start.toString())
        assertEquals(original.endExclusive, preserved.time.endExclusive.toString())
        assertEquals(original.timeZone, preserved.time.timeZone.id)
        assertEquals(null, RoomEventRepository(database).get(EventId(eventId)), "A conflicting child blocks the entire MutationId group.")
        assertEquals(3L, receive.serverCursor(space))
        assertEquals(SyncConflictKind.INTEGRITY, receive.conflict(result.conflictId)?.kind)
        assertEquals(1L, journal.localReplicaState()?.observedContext?.get(ReplicaId(competingReplica)), "A future resolution must observe the received conflicting operation.")
        assertEquals(result, engine.receive(DecryptedPayloadReceipt(space, divergent.mutationId, 4, SyncWireCodec.encodePayload(SyncPayloadV1(operation = divergent)))))

        val thirdReplica = id(79)
        val third = SyncOperation(id(80), DvvSnapshot(emptyList(), DotSnapshot(thirdReplica, 1)), HlcSnapshot(3, 0, thirdReplica), MutationOrigin.User, listOf(
            WorkLogAppend(WorkLogImage(logId, taskId, ZonedTimeRangeImage("2026-01-01T14:00:00Z", "2026-01-01T15:00:00Z", "UTC"))),
        ))
        val expanded = assertIs<SyncReceiveResult.Conflicted>(engine.receive(DecryptedPayloadReceipt(space, third.mutationId, 5, SyncWireCodec.encodePayload(SyncPayloadV1(operation = third)))))
        assertEquals(SyncConflictKind.INTEGRITY, expanded.kind)
        assertNotEquals(result.conflictId, expanded.conflictId, "D8-A06 identity must expand with the complete WorkLog component.")
        assertEquals(SyncConflictStatus.SUPERSEDED, receive.conflict(result.conflictId)?.status)
        assertEquals(expanded.conflictId, receive.conflict(result.conflictId)?.supersededByConflictId)
        val component = requireNotNull(receive.conflict(expanded.conflictId))
        assertEquals(listOf(equalAppend.mutationId, divergent.mutationId, third.mutationId).sorted(), component.participants.map { it.mutationId.value })
        assertEquals(MutationId(listOf(equalAppend.mutationId, divergent.mutationId, third.mutationId).min()), component.provisionalMutationId, "The WorkLog provisional candidate is the global MutationId minimum.")
        assertEquals(listOf(expanded.conflictId), receive.conflicts(space).filter { it.status == SyncConflictStatus.OPEN }.map { it.conflictId })
        assertEquals(original.start, requireNotNull(RoomTaskRepository(database).getWorkLog(WorkLogId(logId))).time.start.toString(), "The immutable durable WorkLog remains untouched after expansion.")
        assertEquals(null, RoomEventRepository(database).get(EventId(eventId)), "The original grouped operation remains all-or-none blocked.")
        assertEquals(5L, receive.serverCursor(space))

        val reverseDatabase = openInMemoryDesktopDatabase()
        val reverseJournal = RoomMutationJournalRepository(reverseDatabase)
        val reverseReceive = RoomSyncReceiveRepository(reverseDatabase)
        val reverseEngine = SyncEngine(
            RoomApplicationTransactionRunner(reverseDatabase),
            reverseJournal,
            reverseJournal,
            reverseReceive,
            RoomEventRepository(reverseDatabase),
            RoomTaskRepository(reverseDatabase),
            RoomPlanningProfileRepository(reverseDatabase),
            RoomAcademicRepository(reverseDatabase),
            ids,
            MutationWallClock { 1 },
        )
        fun receipt(operation: SyncOperation, cursor: Long) = DecryptedPayloadReceipt(
            space,
            operation.mutationId,
            cursor,
            SyncWireCodec.encodePayload(SyncPayloadV1(operation = operation)),
        )
        assertEquals(SyncReceiveResult.Applied(MutationId(initial.mutationId)), reverseEngine.receive(receipt(initial, 1)))
        assertEquals(SyncReceiveResult.Applied(MutationId(equalAppend.mutationId)), reverseEngine.receive(receipt(equalAppend, 2)))
        assertIs<SyncReceiveResult.Conflicted>(reverseEngine.receive(receipt(third, 3)))
        val reverseExpanded = assertIs<SyncReceiveResult.Conflicted>(reverseEngine.receive(receipt(divergent, 4)))
        assertEquals(expanded.conflictId, reverseExpanded.conflictId, "Two arrival orders converge to one canonical N-way WorkLog conflict.")
        assertEquals(component.participants, requireNotNull(reverseReceive.conflict(reverseExpanded.conflictId)).participants)
        assertEquals(original.start, requireNotNull(RoomTaskRepository(reverseDatabase).getWorkLog(WorkLogId(logId))).time.start.toString())
        assertEquals(null, RoomEventRepository(reverseDatabase).get(EventId(eventId)))
        reverseDatabase.close()
        database.close()
    }

    @Test fun `D8 semantic merge preserves concurrent Event groups and persists same-group conflicts`() = runBlocking {
        val database = openInMemoryDesktopDatabase()
        val ids = RfcUuidV7Generator(EpochMillisecondsClock { 1 }, RandomBytes { ByteArray(it) { 1 } })
        val journal = RoomMutationJournalRepository(database); val receive = RoomSyncReceiveRepository(database)
        val events = RoomEventRepository(database)
        val engine = SyncEngine(RoomApplicationTransactionRunner(database), journal, journal, receive, events, RoomTaskRepository(database), RoomPlanningProfileRepository(database), RoomAcademicRepository(database), ids, MutationWallClock { 1 })
        val space = SyncSpaceId("personal-space")
        val eventId = id(80); val localReplica = id(81); val remoteReplica = id(82); val conflictReplica = id(83)
        val baseImage = EventImage(eventId, "Base", EventTimeImage.AllDay(AllDayRangeImage("2026-01-01", "2026-01-02")), FlexibilityImage.HARD, PinStateImage.UNPINNED)
        val initial = SyncOperation(id(84), DvvSnapshot(emptyList(), DotSnapshot(localReplica, 1)), HlcSnapshot(1, 0, localReplica), MutationOrigin.User, listOf(EventPut(null, baseImage)))
        val localTitle = baseImage.copy(title = "Local title")
        val localUpdate = SyncOperation(id(85), DvvSnapshot(listOf(dev.agenticscheduler.sync.VersionComponent(localReplica, 1)), DotSnapshot(localReplica, 2)), HlcSnapshot(2, 0, localReplica), MutationOrigin.User, listOf(EventPut(baseImage, localTitle)))
        val remoteTime = baseImage.copy(time = EventTimeImage.AllDay(AllDayRangeImage("2026-01-03", "2026-01-04")))
        val disjoint = SyncOperation(id(86), DvvSnapshot(listOf(dev.agenticscheduler.sync.VersionComponent(localReplica, 1)), DotSnapshot(remoteReplica, 1)), HlcSnapshot(3, 0, remoteReplica), MutationOrigin.User, listOf(EventPut(baseImage, remoteTime)))
        assertEquals(SyncReceiveResult.Applied(MutationId(initial.mutationId)), engine.receive(DecryptedPayloadReceipt(space, initial.mutationId, 1, SyncWireCodec.encodePayload(SyncPayloadV1(operation = initial)))))
        assertEquals(SyncReceiveResult.Applied(MutationId(localUpdate.mutationId)), engine.receive(DecryptedPayloadReceipt(space, localUpdate.mutationId, 2, SyncWireCodec.encodePayload(SyncPayloadV1(operation = localUpdate)))))
        assertEquals(SyncReceiveResult.Applied(MutationId(disjoint.mutationId)), engine.receive(DecryptedPayloadReceipt(space, disjoint.mutationId, 3, SyncWireCodec.encodePayload(SyncPayloadV1(operation = disjoint)))))
        val merged = requireNotNull(events.get(EventId(eventId)))
        assertEquals("Local title", merged.title)
        assertEquals("2026-01-03", (merged.time as AllDayRange).startDate.toString())

        val conflictingTitle = baseImage.copy(title = "Remote title")
        val conflict = SyncOperation(id(87), DvvSnapshot(listOf(dev.agenticscheduler.sync.VersionComponent(localReplica, 1)), DotSnapshot(conflictReplica, 1)), HlcSnapshot(999_999, 0, conflictReplica), MutationOrigin.User, listOf(EventPut(baseImage, conflictingTitle)))
        val result = assertIs<SyncReceiveResult.Conflicted>(engine.receive(DecryptedPayloadReceipt(space, conflict.mutationId, 4, SyncWireCodec.encodePayload(SyncPayloadV1(operation = conflict)))))
        assertEquals(SyncConflictKind.SEMANTIC, result.kind)
        val persisted = requireNotNull(receive.conflict(result.conflictId))
        assertEquals(MutationId(localUpdate.mutationId), persisted.provisionalMutationId, "HLC never selects a semantic-conflict winner.")
        val commonContext = requireNotNull(persisted.commonCausalContext)
        assertEquals(localReplica, commonContext.components.single().replicaId)
        assertEquals(1L, commonContext.components.single().counter)
        assertEquals("Local title", events.get(EventId(eventId))?.title, "A semantic conflict must not partially overwrite Active State.")
        assertEquals(4L, receive.serverCursor(space))
        val thirdReplica = id(88)
        val thirdTitle = baseImage.copy(title = "Third title")
        val third = SyncOperation(
            id(89),
            DvvSnapshot(listOf(dev.agenticscheduler.sync.VersionComponent(localReplica, 1)), DotSnapshot(thirdReplica, 1)),
            HlcSnapshot(0, 0, thirdReplica),
            MutationOrigin.User,
            listOf(EventPut(baseImage, thirdTitle)),
        )
        val expanded = assertIs<SyncReceiveResult.Conflicted>(engine.receive(DecryptedPayloadReceipt(space, third.mutationId, 5, SyncWireCodec.encodePayload(SyncPayloadV1(operation = third)))))
        assertNotEquals(result.conflictId, expanded.conflictId, "D8-A06 identity includes the expanded participant set.")
        assertEquals(SyncConflictStatus.SUPERSEDED, receive.conflict(result.conflictId)?.status)
        assertEquals(expanded.conflictId, receive.conflict(result.conflictId)?.supersededByConflictId)
        val component = requireNotNull(receive.conflict(expanded.conflictId))
        assertEquals(listOf(localUpdate.mutationId, conflict.mutationId, third.mutationId).sorted(), component.participants.map { it.mutationId.value })
        assertEquals(MutationId(localUpdate.mutationId), component.provisionalMutationId, "The provisional winner is the global MutationId minimum, independent of HLC and arrival order.")
        val resolver = SyncConflictResolutionService(
            MutationCoordinator(RoomApplicationTransactionRunner(database), journal, ids, MutationWallClock { 10 }),
            journal,
            receive,
            events,
            RoomTaskRepository(database),
            RoomPlanningProfileRepository(database),
            RoomAcademicRepository(database),
        )
        val chosen = baseImage.copy(title = "Chosen title")
        val resolution = assertIs<SyncConflictResolutionResult.Resolved>(resolver.resolve(expanded.conflictId, listOf(EventPut(baseImage, chosen))))
        assertEquals("Chosen title", events.get(EventId(eventId))?.title)
        assertEquals("2026-01-03", (events.get(EventId(eventId))?.time as AllDayRange).startDate.toString(), "Resolution retains previously merged non-conflicting groups.")
        assertEquals(resolution.mutationId, requireNotNull(receive.conflict(expanded.conflictId)).resolutionMutationId)
        assertEquals(SyncConflictStatus.RESOLVED, receive.conflict(expanded.conflictId)?.status)
        database.close()
    }

    @Test fun `D8 merges PlanningProfile availability by weekday`() = runBlocking {
        val database = openInMemoryDesktopDatabase()
        val ids = RfcUuidV7Generator(EpochMillisecondsClock { 1 }, RandomBytes { ByteArray(it) { 1 } })
        val journal = RoomMutationJournalRepository(database); val receive = RoomSyncReceiveRepository(database)
        val profiles = RoomPlanningProfileRepository(database)
        val engine = SyncEngine(RoomApplicationTransactionRunner(database), journal, journal, receive, RoomEventRepository(database), RoomTaskRepository(database), profiles, RoomAcademicRepository(database), ids, MutationWallClock { 1 })
        val space = SyncSpaceId("personal-space")
        val profileId = id(88); val localReplica = id(89); val remoteReplica = id(90)
        fun window(day: DayOfWeekImage, start: String) = CanonicalAvailabilityWindowImage(day, start, "${start.substring(0, 2).toInt() + 1}:00")
        val baseConfig = PlanningProfileConfigurationImage.Configured("UTC", listOf(window(DayOfWeekImage.MONDAY, "09:00"), window(DayOfWeekImage.TUESDAY, "09:00")), "PT30M", "PT1H", "PT2H", AllDayEventPolicyImage.NON_BLOCKING)
        val base = PlanningProfileImage(profileId, "Profile", baseConfig)
        val local = base.copy(configuration = baseConfig.copy(weeklyAvailability = listOf(window(DayOfWeekImage.MONDAY, "10:00"), window(DayOfWeekImage.TUESDAY, "09:00"))))
        val remote = base.copy(configuration = baseConfig.copy(weeklyAvailability = listOf(window(DayOfWeekImage.MONDAY, "09:00"), window(DayOfWeekImage.TUESDAY, "11:00"))))
        val first = SyncOperation(id(91), DvvSnapshot(emptyList(), DotSnapshot(localReplica, 1)), HlcSnapshot(1, 0, localReplica), MutationOrigin.User, listOf(PlanningProfilePut(null, base)))
        val localUpdate = SyncOperation(id(92), DvvSnapshot(listOf(dev.agenticscheduler.sync.VersionComponent(localReplica, 1)), DotSnapshot(localReplica, 2)), HlcSnapshot(2, 0, localReplica), MutationOrigin.User, listOf(PlanningProfilePut(base, local)))
        val remoteUpdate = SyncOperation(id(93), DvvSnapshot(listOf(dev.agenticscheduler.sync.VersionComponent(localReplica, 1)), DotSnapshot(remoteReplica, 1)), HlcSnapshot(3, 0, remoteReplica), MutationOrigin.User, listOf(PlanningProfilePut(base, remote)))
        listOf(first to 1L, localUpdate to 2L, remoteUpdate to 3L).forEach { (operation, cursor) ->
            assertEquals(SyncReceiveResult.Applied(MutationId(operation.mutationId)), engine.receive(DecryptedPayloadReceipt(space, operation.mutationId, cursor, SyncWireCodec.encodePayload(SyncPayloadV1(operation = operation)))))
        }
        val configuration = assertIs<dev.agenticscheduler.domain.planning.PlanningProfileConfiguration.Configured>(requireNotNull(profiles.get(PlanningProfileId(profileId))).configuration)
        assertEquals(listOf("10:00", "11:00"), configuration.weeklyAvailability.map { it.start.toString() })
        database.close()
    }

    @Test fun `D8 merges PlanningProfile name with concurrent unconfigure transition`() = runBlocking {
        val database = openInMemoryDesktopDatabase()
        val ids = RfcUuidV7Generator(EpochMillisecondsClock { 1 }, RandomBytes { ByteArray(it) { 1 } })
        val journal = RoomMutationJournalRepository(database); val receive = RoomSyncReceiveRepository(database)
        val profiles = RoomPlanningProfileRepository(database)
        val engine = SyncEngine(RoomApplicationTransactionRunner(database), journal, journal, receive, RoomEventRepository(database), RoomTaskRepository(database), profiles, RoomAcademicRepository(database), ids, MutationWallClock { 1 })
        val space = SyncSpaceId("personal-space")
        val profileId = id(10); val localReplica = id(11); val remoteReplica = id(12)
        val configured = PlanningProfileConfigurationImage.Configured(
            "UTC",
            listOf(CanonicalAvailabilityWindowImage(DayOfWeekImage.MONDAY, "09:00", "10:00")),
            "PT30M", "PT1H", "PT2H", AllDayEventPolicyImage.NON_BLOCKING,
        )
        val base = PlanningProfileImage(profileId, "Profile", configured)
        val local = base.copy(name = "Work")
        val remote = base.copy(configuration = PlanningProfileConfigurationImage.Unconfigured)
        val first = SyncOperation(id(13), DvvSnapshot(emptyList(), DotSnapshot(localReplica, 1)), HlcSnapshot(1, 0, localReplica), MutationOrigin.User, listOf(PlanningProfilePut(null, base)))
        val localUpdate = SyncOperation(id(14), DvvSnapshot(listOf(dev.agenticscheduler.sync.VersionComponent(localReplica, 1)), DotSnapshot(localReplica, 2)), HlcSnapshot(2, 0, localReplica), MutationOrigin.User, listOf(PlanningProfilePut(base, local)))
        val remoteUpdate = SyncOperation(id(15), DvvSnapshot(listOf(dev.agenticscheduler.sync.VersionComponent(localReplica, 1)), DotSnapshot(remoteReplica, 1)), HlcSnapshot(3, 0, remoteReplica), MutationOrigin.User, listOf(PlanningProfilePut(base, remote)))
        listOf(first to 1L, localUpdate to 2L, remoteUpdate to 3L).forEach { (operation, cursor) ->
            assertEquals(SyncReceiveResult.Applied(MutationId(operation.mutationId)), engine.receive(DecryptedPayloadReceipt(space, operation.mutationId, cursor, SyncWireCodec.encodePayload(SyncPayloadV1(operation = operation)))))
        }
        val merged = requireNotNull(profiles.get(PlanningProfileId(profileId)))
        assertEquals("Work", merged.name)
        assertEquals(dev.agenticscheduler.domain.planning.PlanningProfileConfiguration.Unconfigured, merged.configuration)
        database.close()
    }

    @Test fun `D8 holds an out-of-order descendant then drains it after its ancestor`() = runBlocking {
        val database = openInMemoryDesktopDatabase()
        val ids = RfcUuidV7Generator(EpochMillisecondsClock { 1 }, RandomBytes { ByteArray(it) { 1 } })
        val journal = RoomMutationJournalRepository(database); val receive = RoomSyncReceiveRepository(database)
        val events = RoomEventRepository(database); val tasks = RoomTaskRepository(database)
        val engine = SyncEngine(RoomApplicationTransactionRunner(database), journal, journal, receive, events, tasks, RoomPlanningProfileRepository(database), RoomAcademicRepository(database), ids, MutationWallClock { 1 })
        val space = SyncSpaceId("personal-space")
        val remoteReplica = id(20); val eventId = id(21); val taskId = id(22)
        val ancestor = SyncOperation(id(23), DvvSnapshot(emptyList(), DotSnapshot(remoteReplica, 1)), HlcSnapshot(1, 0, remoteReplica), MutationOrigin.User, listOf(
            EventPut(null, EventImage(eventId, "ancestor", EventTimeImage.AllDay(AllDayRangeImage("2026-01-01", "2026-01-02")), FlexibilityImage.HARD, PinStateImage.UNPINNED)),
        ))
        val descendant = SyncOperation(id(24), DvvSnapshot(listOf(dev.agenticscheduler.sync.VersionComponent(remoteReplica, 1)), DotSnapshot(remoteReplica, 2)), HlcSnapshot(2, 0, remoteReplica), MutationOrigin.User, listOf(
            TaskPut(null, TaskImage(taskId, "descendant", TaskStatusImage.OPEN, TaskPriorityImage.NORMAL, TaskEffortImage(null, "PT0S", null), null)),
        ))
        val pending = assertIs<SyncReceiveResult.PendingCausalGap>(engine.receive(DecryptedPayloadReceipt(space, descendant.mutationId, 2, SyncWireCodec.encodePayload(SyncPayloadV1(operation = descendant)))))
        assertEquals(listOf(dev.agenticscheduler.sync.Dot(ReplicaId(remoteReplica), 1)), pending.missingPrerequisites)
        assertEquals(null, events.get(EventId(eventId))); assertEquals(null, tasks.getTask(TaskId(taskId)))
        assertEquals(0L, receive.serverCursor(space)); assertEquals(descendant.mutationId, receive.pending(space, descendant.mutationId)?.mutationId)
        assertEquals(SyncReceiveResult.Applied(MutationId(ancestor.mutationId)), engine.receive(DecryptedPayloadReceipt(space, ancestor.mutationId, 1, SyncWireCodec.encodePayload(SyncPayloadV1(operation = ancestor)))))
        assertEquals("ancestor", events.get(EventId(eventId))?.title)
        assertEquals("descendant", tasks.getTask(TaskId(taskId))?.title)
        assertEquals(null, receive.pending(space, descendant.mutationId))
        assertEquals(2L, receive.serverCursor(space))
        assertEquals(MutationId(descendant.mutationId), journal.mutation(descendant.mutationId)?.operation?.let { MutationId(it.mutationId) })
        database.close()
    }

    @Test fun `D8 merges disjoint Task effort and deadline but conflicts on concurrent effort`() = runBlocking {
        val database = openInMemoryDesktopDatabase()
        val ids = RfcUuidV7Generator(EpochMillisecondsClock { 1 }, RandomBytes { ByteArray(it) { 1 } })
        val journal = RoomMutationJournalRepository(database); val receive = RoomSyncReceiveRepository(database)
        val tasks = RoomTaskRepository(database)
        val engine = SyncEngine(RoomApplicationTransactionRunner(database), journal, journal, receive, RoomEventRepository(database), tasks, RoomPlanningProfileRepository(database), RoomAcademicRepository(database), ids, MutationWallClock { 1 })
        val space = SyncSpaceId("personal-space")
        val taskId = id(30); val localReplica = id(31); val deadlineReplica = id(32); val effortReplica = id(33)
        val base = TaskImage(taskId, "Task", TaskStatusImage.OPEN, TaskPriorityImage.NORMAL, TaskEffortImage("PT1H", "PT0S", "PT1H"), null)
        val localEffort = base.copy(effort = TaskEffortImage("PT2H", "PT0S", "PT2H"))
        val remoteDeadline = base.copy(deadline = TaskDeadlineImage(DeadlineImage.DateOnly("2026-02-01"), DeadlinePolicyImage.HARD, OverflowPolicyImage.NEVER))
        val conflictingEffort = base.copy(effort = TaskEffortImage("PT3H", "PT0S", "PT3H"))
        val first = SyncOperation(id(34), DvvSnapshot(emptyList(), DotSnapshot(localReplica, 1)), HlcSnapshot(1, 0, localReplica), MutationOrigin.User, listOf(TaskPut(null, base)))
        val localUpdate = SyncOperation(id(35), DvvSnapshot(listOf(dev.agenticscheduler.sync.VersionComponent(localReplica, 1)), DotSnapshot(localReplica, 2)), HlcSnapshot(2, 0, localReplica), MutationOrigin.User, listOf(TaskPut(base, localEffort)))
        val deadlineUpdate = SyncOperation(id(36), DvvSnapshot(listOf(dev.agenticscheduler.sync.VersionComponent(localReplica, 1)), DotSnapshot(deadlineReplica, 1)), HlcSnapshot(3, 0, deadlineReplica), MutationOrigin.User, listOf(TaskPut(base, remoteDeadline)))
        listOf(first to 1L, localUpdate to 2L, deadlineUpdate to 3L).forEach { (operation, cursor) ->
            assertEquals(SyncReceiveResult.Applied(MutationId(operation.mutationId)), engine.receive(DecryptedPayloadReceipt(space, operation.mutationId, cursor, SyncWireCodec.encodePayload(SyncPayloadV1(operation = operation)))))
        }
        val merged = requireNotNull(tasks.getTask(TaskId(taskId)))
        assertEquals("PT2H", merged.effort.estimated?.toIsoString())
        assertEquals("2026-02-01", (requireNotNull(merged.deadline).deadline as Deadline.DateOnly).date.toString())
        val conflictingUpdate = SyncOperation(id(37), DvvSnapshot(listOf(dev.agenticscheduler.sync.VersionComponent(localReplica, 1)), DotSnapshot(effortReplica, 1)), HlcSnapshot(4, 0, effortReplica), MutationOrigin.User, listOf(TaskPut(base, conflictingEffort)))
        assertEquals(SyncConflictKind.SEMANTIC, assertIs<SyncReceiveResult.Conflicted>(engine.receive(DecryptedPayloadReceipt(space, conflictingUpdate.mutationId, 4, SyncWireCodec.encodePayload(SyncPayloadV1(operation = conflictingUpdate))))).kind)
        assertEquals("PT2H", tasks.getTask(TaskId(taskId))?.effort?.estimated?.toIsoString())
        database.close()
    }

    @Test fun `D8 conflicts FocusBlock move versus concurrent delete and academic aggregate writes`() = runBlocking {
        val database = openInMemoryDesktopDatabase()
        val ids = RfcUuidV7Generator(EpochMillisecondsClock { 1 }, RandomBytes { ByteArray(it) { 1 } })
        val journal = RoomMutationJournalRepository(database); val receive = RoomSyncReceiveRepository(database)
        val tasks = RoomTaskRepository(database); val academics = RoomAcademicRepository(database)
        val engine = SyncEngine(RoomApplicationTransactionRunner(database), journal, journal, receive, RoomEventRepository(database), tasks, RoomPlanningProfileRepository(database), academics, ids, MutationWallClock { 1 })
        val space = SyncSpaceId("personal-space")
        val localReplica = id(94); val remoteReplica = id(95); val taskId = id(96); val blockId = id(97)
        val block = FocusBlockImage(blockId, taskId, ZonedTimeRangeImage("2026-01-01T09:00:00Z", "2026-01-01T10:00:00Z", "UTC"), FlexibilityImage.FLEXIBLE, PinStateImage.UNPINNED)
        val first = SyncOperation(id(98), DvvSnapshot(emptyList(), DotSnapshot(localReplica, 1)), HlcSnapshot(1, 0, localReplica), MutationOrigin.User, listOf(
            TaskPut(null, TaskImage(taskId, "Task", TaskStatusImage.OPEN, TaskPriorityImage.NORMAL, TaskEffortImage(null, "PT0S", null), null)),
            FocusBlockPut(null, block),
        ))
        val delete = SyncOperation(id(99), DvvSnapshot(emptyList(), DotSnapshot(remoteReplica, 1)), HlcSnapshot(2, 0, remoteReplica), MutationOrigin.User, listOf(FocusBlockDelete(block)))
        assertEquals(SyncReceiveResult.Applied(MutationId(first.mutationId)), engine.receive(DecryptedPayloadReceipt(space, first.mutationId, 1, SyncWireCodec.encodePayload(SyncPayloadV1(operation = first)))))
        val blockConflict = assertIs<SyncReceiveResult.Conflicted>(engine.receive(DecryptedPayloadReceipt(space, delete.mutationId, 2, SyncWireCodec.encodePayload(SyncPayloadV1(operation = delete)))))
        assertEquals(SyncConflictKind.SEMANTIC, blockConflict.kind)
        assertEquals(block, tasks.getFocusBlock(FocusBlockId(blockId))?.let { FocusBlockImage(blockId, it.taskId.value, ZonedTimeRangeImage(it.time.start.toString(), it.time.endExclusive.toString(), it.time.timeZone.id), FlexibilityImage.FLEXIBLE, PinStateImage.UNPINNED) })

        val yearId = id(60); val yearReplica = id(61); val competingYearReplica = id(62)
        val year = AcademicYearImage(yearId, "Year", "2026-01-01", "2027-01-01")
        val initialYear = SyncOperation(id(63), DvvSnapshot(listOf(dev.agenticscheduler.sync.VersionComponent(localReplica, 1)), DotSnapshot(yearReplica, 1)), HlcSnapshot(3, 0, yearReplica), MutationOrigin.User, listOf(AcademicYearPut(null, year)))
        val otherYear = year.copy(name = "Other Year")
        val competingYear = SyncOperation(id(64), DvvSnapshot(listOf(dev.agenticscheduler.sync.VersionComponent(localReplica, 1)), DotSnapshot(competingYearReplica, 1)), HlcSnapshot(4, 0, competingYearReplica), MutationOrigin.User, listOf(AcademicYearPut(year, otherYear)))
        assertEquals(SyncReceiveResult.Applied(MutationId(initialYear.mutationId)), engine.receive(DecryptedPayloadReceipt(space, initialYear.mutationId, 3, SyncWireCodec.encodePayload(SyncPayloadV1(operation = initialYear)))))
        val academicConflict = assertIs<SyncReceiveResult.Conflicted>(engine.receive(DecryptedPayloadReceipt(space, competingYear.mutationId, 4, SyncWireCodec.encodePayload(SyncPayloadV1(operation = competingYear)))))
        assertEquals(SyncConflictKind.SEMANTIC, academicConflict.kind)
        assertEquals("Year", academics.getAcademicYear(AcademicYearId(yearId))?.name)
        database.close()
    }

    @Test fun `journal append failure rolls active state and causal state back together`() = runBlocking {
        val database = openInMemoryDesktopDatabase()
        val events = RoomEventRepository(database)
        val journal = RoomMutationJournalRepository(database)
        val ids = RfcUuidV7Generator(EpochMillisecondsClock { 1 }, RandomBytes { ByteArray(it) { 1 } })
        val coordinator = MutationCoordinator(RoomApplicationTransactionRunner(database), FailingJournal(journal), ids, MutationWallClock { 1 })
        val editor = EventEditingService(events, ids, coordinator, NoActiveSyncSpaceWritePolicy)
        assertFailsWith<IllegalStateException> { editor.create(CreateEventInput("rollback", EventTimeInput.AllDay(LocalDate(2026, 1, 1), LocalDate(2026, 1, 2)), Flexibility.HARD, PinState.UNPINNED)) }
        assertEquals(emptyList(), events.observeAll().first())
        assertEquals(emptyList(), database.mutationJournalDao().timeline())
        assertEquals(null, database.mutationJournalDao().localReplicaState())
        database.close()
    }

    @Test fun `grouped Planner ChangeLog preserves child ordinal and durable diff order`() = runBlocking {
        val database = openInMemoryDesktopDatabase()
        val tasks = RoomTaskRepository(database)
        val source = task(70)
        tasks.upsertTask(source)
        val first = dev.agenticscheduler.domain.task.FocusBlock(FocusBlockId(id(71)), source.id, ZonedTimeRange(Instant.parse("2026-01-01T10:00:00Z"), Instant.parse("2026-01-01T11:00:00Z"), TimeZone.UTC), Flexibility.SOFT, PinState.UNPINNED)
        val second = dev.agenticscheduler.domain.task.FocusBlock(FocusBlockId(id(72)), source.id, ZonedTimeRange(Instant.parse("2026-01-01T11:00:00Z"), Instant.parse("2026-01-01T12:00:00Z"), TimeZone.UTC), Flexibility.SOFT, PinState.UNPINNED)
        val ids = RfcUuidV7Generator(EpochMillisecondsClock { 1 }, RandomBytes { ByteArray(it) { 1 } })
        val coordinator = MutationCoordinator(RoomApplicationTransactionRunner(database), RoomMutationJournalRepository(database), ids, MutationWallClock { 1 })
        val committed = coordinator.execute(MutationOrigin.Planner) {
            tasks.upsertFocusBlock(first)
            record(FocusBlockPut(null, FocusBlockImage(first.id.value, first.taskId.value, dev.agenticscheduler.sync.ZonedTimeRangeImage(first.time.start.toString(), first.time.endExclusive.toString(), first.time.timeZone.id), dev.agenticscheduler.sync.FlexibilityImage.valueOf(first.flexibility.name), dev.agenticscheduler.sync.PinStateImage.valueOf(first.pinState.name))))
            tasks.upsertFocusBlock(second)
            record(FocusBlockPut(null, FocusBlockImage(second.id.value, second.taskId.value, dev.agenticscheduler.sync.ZonedTimeRangeImage(second.time.start.toString(), second.time.endExclusive.toString(), second.time.timeZone.id), dev.agenticscheduler.sync.FlexibilityImage.valueOf(second.flexibility.name), dev.agenticscheduler.sync.PinStateImage.valueOf(second.pinState.name))))
        }
        val diff = RoomMutationJournalRepository(database).diff(committed.mutationId.value)
        assertEquals(listOf(0, 1), diff.map { it.ordinal })
        assertEquals(listOf(first.id.value, second.id.value), diff.map { it.entityId })
        assertTrue(diff.all { it.beforeImageJson == null && it.afterImageJson != null })
        database.close()
    }

    private fun task(number: Int) = Task(TaskId(id(number)), "task $number", TaskStatus.OPEN, TaskPriority.NORMAL, TaskEffort(null, kotlin.time.Duration.ZERO, null), null)
    private fun id(number: Int) = "00000000-0000-7000-8000-0000000000${number.toString().padStart(2, '0')}"
    private fun remoteEventOperation(mutationId: String, replicaId: String, eventId: String, title: String) = SyncOperation(
        mutationId, DvvSnapshot(emptyList(), DotSnapshot(replicaId, 1)), HlcSnapshot(10, 0, replicaId), MutationOrigin.User,
        listOf(EventPut(null, EventImage(eventId, title, EventTimeImage.AllDay(AllDayRangeImage("2026-01-01", "2026-01-02")), FlexibilityImage.HARD, PinStateImage.UNPINNED))),
    )
}

private class FailingJournal(private val delegate: MutationJournalRepository) : MutationJournalRepository {
    override suspend fun localReplicaState(): LocalReplicaCausalState? = delegate.localReplicaState()
    override suspend fun saveLocalReplicaState(state: LocalReplicaCausalState) = delegate.saveLocalReplicaState(state)
    override suspend fun appendCommittedMutation(mutation: CommittedMutation) { delegate.appendCommittedMutation(mutation); error("forced journal failure") }
    override suspend fun advanceFocusBlockTombstones(operation: dev.agenticscheduler.sync.SyncOperation, acceptedDeletes: List<dev.agenticscheduler.sync.FocusBlockDelete>) = delegate.advanceFocusBlockTombstones(operation, acceptedDeletes)
}
