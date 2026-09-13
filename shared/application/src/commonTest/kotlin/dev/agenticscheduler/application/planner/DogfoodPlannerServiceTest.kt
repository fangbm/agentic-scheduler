package dev.agenticscheduler.application.planner

import dev.agenticscheduler.application.id.EpochMillisecondsClock
import dev.agenticscheduler.application.id.RandomBytes
import dev.agenticscheduler.application.id.RfcUuidV7Generator
import dev.agenticscheduler.application.persistence.AcademicRepository
import dev.agenticscheduler.application.persistence.ApplicationTransactionRunner
import dev.agenticscheduler.application.persistence.EventRepository
import dev.agenticscheduler.application.persistence.PlanningProfileRepository
import dev.agenticscheduler.application.persistence.TaskRepository
import dev.agenticscheduler.domain.academic.*
import dev.agenticscheduler.domain.event.Event
import dev.agenticscheduler.domain.id.*
import dev.agenticscheduler.domain.planning.*
import dev.agenticscheduler.domain.task.*
import dev.agenticscheduler.planner.PlanningHorizon
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.ZERO
import kotlin.time.Instant

class DogfoodPlannerServiceTest {
    @Test fun `full replan preview stays isolated then applies its FocusBlock atomically`() = runBlocking {
        val task = Task(TaskId(id(2)), "Read", TaskStatus.OPEN, TaskPriority.NORMAL, TaskEffort(1.hours, ZERO, 1.hours), null)
        val taskRepository = MemoryTasks(task)
        val profile = PlanningProfile(
            PlanningProfileId(id(1)),
            "Study",
            PlanningProfileConfiguration.Configured(
                TimeZone.UTC,
                listOf(WeeklyAvailabilityWindow(DayOfWeek.MONDAY, LocalTime(9, 0), LocalTime(17, 0))).toImmutableList(),
                1.hours, 1.hours, 1.hours, AllDayEventPolicy.NON_BLOCKING,
            ),
        )
        val service = DogfoodPlannerService(
            tasks = taskRepository,
            events = EmptyEvents,
            profiles = MemoryProfiles(profile),
            academics = EmptyAcademics,
            transactions = IdentityTransactions,
            uuidV7 = RfcUuidV7Generator(EpochMillisecondsClock { 1 }, RandomBytes { ByteArray(it) { 1 } }),
        )
        val referenceNow = Instant.parse("2026-01-05T08:00:00Z")
        val preview = assertIs<PlannerPreview.Applicable>(service.fullReplan(
            profile.id,
            referenceNow,
            PlanningHorizon(referenceNow, Instant.parse("2026-01-05T12:00:00Z")),
        ))
        assertEquals(0, taskRepository.blocks.size, "Preview must not mutate Active State.")

        assertIs<PlanBranchApplyResult.Applied>(service.apply(preview.branch, referenceNow))
        assertEquals(1, taskRepository.blocks.size)
        assertEquals(task.id, taskRepository.blocks.values.single().taskId)
    }

    private fun id(number: Int) = "018f6e68-7d0c-7000-8000-${number.toString().padStart(12, '0')}"
}

private object IdentityTransactions : ApplicationTransactionRunner {
    override suspend fun <T> inWriteTransaction(block: suspend () -> T): T = block()
}

private class MemoryTasks(private val task: Task) : TaskRepository {
    val blocks = linkedMapOf<FocusBlockId, FocusBlock>()
    override fun observeTasks(): Flow<ImmutableList<Task>> = flowOf(listOf(task).toImmutableList())
    override suspend fun getTask(id: TaskId): Task? = task.takeIf { it.id == id }
    override suspend fun upsertTask(task: Task) = Unit
    override fun observeFocusBlocks(): Flow<ImmutableList<FocusBlock>> = flowOf(blocks.values.toList().toImmutableList())
    override suspend fun getFocusBlock(id: FocusBlockId): FocusBlock? = blocks[id]
    override suspend fun upsertFocusBlock(focusBlock: FocusBlock) { blocks[focusBlock.id] = focusBlock }
    override suspend fun deleteFocusBlock(id: FocusBlockId) { blocks.remove(id) }
    override fun observeWorkLogs(): Flow<ImmutableList<WorkLog>> = emptyFlow()
    override suspend fun getWorkLog(id: WorkLogId): WorkLog? = null
    override suspend fun upsertWorkLog(workLog: WorkLog) = Unit
    override fun observeDependencies(): Flow<ImmutableList<TaskDependency>> = emptyFlow()
    override suspend fun getDependency(id: TaskDependencyId): TaskDependency? = null
    override suspend fun upsertDependency(dependency: TaskDependency) = Unit
}

private object EmptyEvents : EventRepository {
    override fun observeAll(): Flow<ImmutableList<Event>> = emptyFlow()
    override suspend fun get(id: EventId): Event? = null
    override suspend fun upsert(event: Event) = Unit
}

private class MemoryProfiles(private val profile: PlanningProfile) : PlanningProfileRepository {
    override fun observeAll(): Flow<ImmutableList<PlanningProfile>> = flowOf(listOf(profile).toImmutableList())
    override suspend fun get(id: PlanningProfileId): PlanningProfile? = profile.takeIf { it.id == id }
    override suspend fun upsert(profile: PlanningProfile) = Unit
}

private object EmptyAcademics : AcademicRepository {
    override fun observeAcademicYears(): Flow<ImmutableList<AcademicYear>> = emptyFlow(); override suspend fun getAcademicYear(id: AcademicYearId): AcademicYear? = null; override suspend fun upsertAcademicYear(value: AcademicYear) = Unit
    override fun observeSemesters(): Flow<ImmutableList<Semester>> = emptyFlow(); override suspend fun getSemester(id: SemesterId): Semester? = null; override suspend fun upsertSemester(value: Semester) = Unit
    override fun observeCourses(): Flow<ImmutableList<Course>> = emptyFlow(); override suspend fun getCourse(id: CourseId): Course? = null; override suspend fun upsertCourse(value: Course) = Unit
    override fun observePeriodTemplates(): Flow<ImmutableList<PeriodTemplate>> = emptyFlow(); override suspend fun getPeriodTemplate(id: PeriodTemplateId): PeriodTemplate? = null; override suspend fun upsertPeriodTemplate(value: PeriodTemplate) = Unit
    override fun observeCourseScheduleRules(): Flow<ImmutableList<CourseScheduleRule>> = emptyFlow(); override suspend fun getCourseScheduleRule(id: CourseScheduleRuleId): CourseScheduleRule? = null; override suspend fun upsertCourseScheduleRule(value: CourseScheduleRule) = Unit
    override fun observeAcademicHolidays(): Flow<ImmutableList<AcademicHoliday>> = emptyFlow(); override suspend fun getAcademicHoliday(id: AcademicHolidayId): AcademicHoliday? = null; override suspend fun upsertAcademicHoliday(value: AcademicHoliday) = Unit
    override fun observeCourseOccurrenceExceptions(): Flow<ImmutableList<CourseOccurrenceException>> = emptyFlow(); override suspend fun getCourseOccurrenceException(id: CourseOccurrenceExceptionId): CourseOccurrenceException? = null; override suspend fun upsertCourseOccurrenceException(value: CourseOccurrenceException) = Unit
    override fun observeExams(): Flow<ImmutableList<Exam>> = emptyFlow(); override suspend fun getExam(id: ExamId): Exam? = null; override suspend fun upsertExam(value: Exam) = Unit
}

private fun <T> emptyFlow(): Flow<ImmutableList<T>> = flowOf(emptyList<T>().toImmutableList())
