package dev.agenticscheduler.database

import dev.agenticscheduler.database.repository.RoomApplicationTransactionRunner
import dev.agenticscheduler.database.repository.RoomEventRepository
import dev.agenticscheduler.database.repository.RoomTaskRepository
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
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlinx.coroutines.flow.first
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

    @Test fun `all D4 discriminated domain variants round trip through explicit mappers`() {
        val exactTask = task(20).copy(deadline = TaskDeadline(Deadline.Exact(Instant.fromEpochSeconds(10, 999_999_999), TimeZone.of("America/New_York")), DeadlinePolicy.HARD, OverflowPolicy.ASK))
        val dateTask = task(21).copy(deadline = TaskDeadline(Deadline.DateOnly(LocalDate(2026, 10, 1)), DeadlinePolicy.NORMAL, OverflowPolicy.NEVER))
        assertEquals(exactTask, exactTask.toRecord().toDomain()); assertEquals(dateTask, dateTask.toRecord().toDomain())
        val exactRange = ZonedTimeRange(Instant.fromEpochSeconds(100, 1), Instant.fromEpochSeconds(200, 2), TimeZone.of("Asia/Shanghai"))
        val focus = dev.agenticscheduler.domain.task.FocusBlock(FocusBlockId(id(40)), exactTask.id, exactRange, Flexibility.FLEXIBLE, PinState.UNPINNED)
        val workLog = dev.agenticscheduler.domain.task.WorkLog(WorkLogId(id(41)), exactTask.id, exactRange)
        val dependency = dev.agenticscheduler.domain.task.TaskDependency(TaskDependencyId(id(42)), exactTask.id, dateTask.id)
        val profile = dev.agenticscheduler.domain.planning.PlanningProfile(PlanningProfileId(id(43)), "Default")
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

    @Test fun `exported v1 schema opens through Room migration harness`() = runBlocking {
        migrationHelper.createDatabase(1)
        migrationHelper.runMigrationsAndValidate(1, emptyList()); Unit
    }

    private fun task(number: Int) = Task(TaskId(id(number)), "task $number", TaskStatus.OPEN, TaskPriority.NORMAL, TaskEffort(null, kotlin.time.Duration.ZERO, null), null)
    private fun id(number: Int) = "00000000-0000-7000-8000-0000000000${number.toString().padStart(2, '0')}"
}
