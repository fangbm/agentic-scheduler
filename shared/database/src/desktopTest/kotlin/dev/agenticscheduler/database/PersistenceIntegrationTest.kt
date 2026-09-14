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
import dev.agenticscheduler.application.history.HistoryQueryService
import dev.agenticscheduler.application.persistence.CommittedMutation
import dev.agenticscheduler.application.persistence.LocalReplicaCausalState
import dev.agenticscheduler.application.persistence.MutationJournalRepository
import dev.agenticscheduler.database.repository.RoomApplicationTransactionRunner
import dev.agenticscheduler.database.repository.RoomEventRepository
import dev.agenticscheduler.database.repository.RoomTaskRepository
import dev.agenticscheduler.database.repository.RoomPlanningProfileRepository
import dev.agenticscheduler.database.repository.RoomMutationJournalRepository
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
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFails
import kotlin.test.assertIs
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
        val event = assertIs<Event>(assertIs<EditingResult.Success<*>>(EventEditingService(events, ids, coordinator).create(
            CreateEventInput(
                "Created event",
                EventTimeInput.AllDay(LocalDate(2026, 9, 10), LocalDate(2026, 9, 11)),
                Flexibility.HARD,
                PinState.UNPINNED,
            ),
        )).value)
        val task = assertIs<Task>(assertIs<EditingResult.Success<*>>(TaskEditingService(tasks, ids, coordinator).create(
            CreateTaskInput("Created task", TaskPriority.NORMAL, null, null, null),
        )).value)
        val updatedEvent = assertIs<Event>(assertIs<EditingResult.Success<*>>(EventEditingService(events, ids, coordinator).update(
            UpdateEventInput(event.id, "Updated event", EventTimeInput.AllDay(LocalDate(2026, 9, 10), LocalDate(2026, 9, 11)), Flexibility.HARD, PinState.UNPINNED),
        )).value)
        val updatedTask = assertIs<Task>(assertIs<EditingResult.Success<*>>(TaskEditingService(tasks, ids, coordinator).update(
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

    @Test fun `journal append failure rolls active state and causal state back together`() = runBlocking {
        val database = openInMemoryDesktopDatabase()
        val events = RoomEventRepository(database)
        val journal = RoomMutationJournalRepository(database)
        val ids = RfcUuidV7Generator(EpochMillisecondsClock { 1 }, RandomBytes { ByteArray(it) { 1 } })
        val coordinator = MutationCoordinator(RoomApplicationTransactionRunner(database), FailingJournal(journal), ids, MutationWallClock { 1 })
        val editor = EventEditingService(events, ids, coordinator)
        assertFailsWith<IllegalStateException> { editor.create(CreateEventInput("rollback", EventTimeInput.AllDay(LocalDate(2026, 1, 1), LocalDate(2026, 1, 2)), Flexibility.HARD, PinState.UNPINNED)) }
        assertEquals(emptyList(), events.observeAll().first())
        assertEquals(emptyList(), database.mutationJournalDao().timeline())
        assertEquals(null, database.mutationJournalDao().localReplicaState())
        database.close()
    }

    private fun task(number: Int) = Task(TaskId(id(number)), "task $number", TaskStatus.OPEN, TaskPriority.NORMAL, TaskEffort(null, kotlin.time.Duration.ZERO, null), null)
    private fun id(number: Int) = "00000000-0000-7000-8000-0000000000${number.toString().padStart(2, '0')}"
}

private class FailingJournal(private val delegate: MutationJournalRepository) : MutationJournalRepository {
    override suspend fun localReplicaState(): LocalReplicaCausalState? = delegate.localReplicaState()
    override suspend fun saveLocalReplicaState(state: LocalReplicaCausalState) = delegate.saveLocalReplicaState(state)
    override suspend fun appendCommittedMutation(mutation: CommittedMutation) { delegate.appendCommittedMutation(mutation); error("forced journal failure") }
    override suspend fun advanceFocusBlockTombstones(operation: dev.agenticscheduler.sync.SyncOperation, acceptedDeletes: List<dev.agenticscheduler.sync.FocusBlockDelete>) = delegate.advanceFocusBlockTombstones(operation, acceptedDeletes)
}
