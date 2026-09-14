package dev.agenticscheduler.application.planner

import dev.agenticscheduler.application.id.EpochMillisecondsClock
import dev.agenticscheduler.application.id.RandomBytes
import dev.agenticscheduler.application.id.RfcUuidV7Generator
import dev.agenticscheduler.application.history.MutationCoordinator
import dev.agenticscheduler.application.history.MutationWallClock
import dev.agenticscheduler.application.persistence.AcademicRepository
import dev.agenticscheduler.application.persistence.ApplicationTransactionRunner
import dev.agenticscheduler.application.persistence.CommittedMutation
import dev.agenticscheduler.application.persistence.EventRepository
import dev.agenticscheduler.application.persistence.PlanningProfileRepository
import dev.agenticscheduler.application.persistence.TaskRepository
import dev.agenticscheduler.application.persistence.LocalReplicaCausalState
import dev.agenticscheduler.application.persistence.MutationJournalRepository
import dev.agenticscheduler.domain.academic.*
import dev.agenticscheduler.domain.event.Event
import dev.agenticscheduler.domain.id.*
import dev.agenticscheduler.domain.planning.*
import dev.agenticscheduler.domain.task.*
import dev.agenticscheduler.domain.time.ZonedTimeRange
import dev.agenticscheduler.planner.FocusBlockMutation
import dev.agenticscheduler.planner.LocalReflowRequest
import dev.agenticscheduler.planner.PlannerIssue
import dev.agenticscheduler.planner.PlanningHorizon
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
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
        val profile = configuredProfile()
        val service = DogfoodPlannerService(
            tasks = taskRepository,
            events = MemoryEvents(),
            profiles = MemoryProfiles(profile),
            academics = EmptyAcademics,
            uuidV7 = RfcUuidV7Generator(EpochMillisecondsClock { 1 }, RandomBytes { ByteArray(it) { 1 } }),
            mutations = coordinator(),
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

    @Test fun `local reflow preview moves the same FocusBlock only on Apply`() = runBlocking {
        val task = task()
        val original = focusBlock(9, 10)
        val tasks = MemoryTasks(task, listOf(original))
        val profile = configuredProfile()
        val service = service(tasks, MemoryEvents(listOf(blockingEvent(9, 10))), profile)
        val referenceNow = Instant.parse("2026-01-05T08:00:00Z")

        val preview = assertIs<PlannerPreview.Applicable>(service.localReflow(
            profile.id, referenceNow, horizon(referenceNow),
            LocalReflowRequest(persistentListOf(original.id), persistentListOf(), ZonedTimeRange(
                Instant.parse("2026-01-05T08:00:00Z"), Instant.parse("2026-01-05T12:00:00Z"), TimeZone.UTC,
            )),
        ))
        val move = assertIs<FocusBlockMutation.Move>(preview.branch.mutations.single())
        assertEquals(original.id, move.id)
        assertEquals(Instant.parse("2026-01-05T10:00:00Z"), move.time.start)
        assertEquals(original, tasks.blocks[original.id], "Preview must retain Active State.")

        assertIs<PlanBranchApplyResult.Applied>(service.apply(preview.branch, referenceNow))
        assertEquals(move.time, tasks.blocks.getValue(original.id).time)
    }

    @Test fun `changed Planner source fact makes Dogfood Apply stale without writes`() = runBlocking {
        val task = task()
        val tasks = MemoryTasks(task)
        val events = MemoryEvents()
        val profile = configuredProfile()
        val service = service(tasks, events, profile)
        val referenceNow = Instant.parse("2026-01-05T08:00:00Z")
        val preview = assertIs<PlannerPreview.Applicable>(service.fullReplan(profile.id, referenceNow, horizon(referenceNow)))

        events.values = listOf(blockingEvent(10, 11))
        assertIs<PlanBranchApplyResult.Stale>(service.apply(preview.branch, referenceNow))
        assertEquals(0, tasks.blocks.size, "Stale Apply must not partially write FocusBlocks.")
    }

    @Test fun `bridge preserves unconfigured and local reflow infeasible structured results`() = runBlocking {
        val referenceNow = Instant.parse("2026-01-05T08:00:00Z")
        val unconfigured = PlanningProfile(PlanningProfileId(id(8)), "Draft", PlanningProfileConfiguration.Unconfigured)
        val invalid = service(MemoryTasks(task()), MemoryEvents(), unconfigured)
            .fullReplan(unconfigured.id, referenceNow, horizon(referenceNow))
        assertIs<PlannerIssue.ProfileUnconfigured>(assertIs<PlannerPreview.InvalidInput>(invalid).issues.single())

        val task = task()
        val original = focusBlock(9, 10)
        val constrainedProfile = configuredProfile(endHour = 10)
        val infeasible = service(MemoryTasks(task, listOf(original)), MemoryEvents(listOf(blockingEvent(9, 10))), constrainedProfile)
            .localReflow(
                constrainedProfile.id, referenceNow, horizon(referenceNow),
                LocalReflowRequest(persistentListOf(original.id), persistentListOf(), ZonedTimeRange(
                    Instant.parse("2026-01-05T08:00:00Z"), Instant.parse("2026-01-05T12:00:00Z"), TimeZone.UTC,
                )),
            )
        assertIs<PlannerIssue.NoLegalAvailability>(assertIs<PlannerPreview.Infeasible>(infeasible).issues.single())
        Unit
    }

    @Test fun `PlanningProfile commands journal create and update through the mandatory coordinator`() = runBlocking {
        val profiles = MemoryProfiles(configuredProfile())
        val journal = DogfoodJournal()
        val settings = PlanningProfileSettingsService(
            profiles,
            RfcUuidV7Generator(EpochMillisecondsClock { 1 }, RandomBytes { ByteArray(it) { 1 } }),
            coordinator(journal),
        )
        val created = settings.createUnconfigured("Draft")
        val saved = settings.save(created.copy(name = "Ready"))
        assertEquals(saved, profiles.get(saved.id))
        assertEquals(2, journal.mutations.size)
        val puts = journal.mutations.map { it.operation.orderedMutations.single() as dev.agenticscheduler.sync.PlanningProfilePut }
        assertEquals(null, puts.first().before)
        assertEquals(created.id.value, puts.last().before?.id)
    }

    private fun task() = Task(TaskId(id(2)), "Read", TaskStatus.OPEN, TaskPriority.NORMAL, TaskEffort(1.hours, ZERO, 1.hours), null)
    private fun configuredProfile(endHour: Int = 17) = PlanningProfile(
        PlanningProfileId(id(1)), "Study", PlanningProfileConfiguration.Configured(
            TimeZone.UTC,
            listOf(WeeklyAvailabilityWindow(DayOfWeek.MONDAY, LocalTime(9, 0), LocalTime(endHour, 0))).toImmutableList(),
            1.hours, 1.hours, 1.hours, AllDayEventPolicy.NON_BLOCKING,
        ),
    )
    private fun focusBlock(startHour: Int, endHour: Int) = FocusBlock(
        FocusBlockId(id(3)), TaskId(id(2)), ZonedTimeRange(
            Instant.parse("2026-01-05T${startHour.toString().padStart(2, '0')}:00:00Z"),
            Instant.parse("2026-01-05T${endHour.toString().padStart(2, '0')}:00:00Z"), TimeZone.UTC,
        ), Flexibility.FLEXIBLE, PinState.UNPINNED,
    )
    private fun blockingEvent(startHour: Int, endHour: Int) = Event(
        EventId(id(4)), "Busy", ZonedTimeRange(
            Instant.parse("2026-01-05T${startHour.toString().padStart(2, '0')}:00:00Z"),
            Instant.parse("2026-01-05T${endHour.toString().padStart(2, '0')}:00:00Z"), TimeZone.UTC,
        ), Flexibility.HARD, PinState.UNPINNED,
    )
    private fun horizon(referenceNow: Instant) = PlanningHorizon(referenceNow, Instant.parse("2026-01-05T12:00:00Z"))
    private fun service(tasks: MemoryTasks, events: MemoryEvents, profile: PlanningProfile) = DogfoodPlannerService(
        tasks, events, MemoryProfiles(profile), EmptyAcademics,
        RfcUuidV7Generator(EpochMillisecondsClock { 1 }, RandomBytes { ByteArray(it) { 1 } }),
        mutations = coordinator(),
    )

    private fun coordinator(journal: DogfoodJournal = DogfoodJournal()) = MutationCoordinator(
        IdentityTransactions,
        journal,
        RfcUuidV7Generator(EpochMillisecondsClock { 1 }, RandomBytes { ByteArray(it) { 1 } }),
        MutationWallClock { 1 },
    )

    private fun id(number: Int) = "018f6e68-7d0c-7000-8000-${number.toString().padStart(12, '0')}"
}

private object IdentityTransactions : ApplicationTransactionRunner {
    override suspend fun <T> inWriteTransaction(block: suspend () -> T): T = block()
}

private class DogfoodJournal : MutationJournalRepository {
    private var state: LocalReplicaCausalState? = null
    val mutations = mutableListOf<CommittedMutation>()
    override suspend fun localReplicaState(): LocalReplicaCausalState? = state
    override suspend fun saveLocalReplicaState(state: LocalReplicaCausalState) { this.state = state }
    override suspend fun appendCommittedMutation(mutation: CommittedMutation) { mutations += mutation }
    override suspend fun advanceFocusBlockTombstones(operation: dev.agenticscheduler.sync.SyncOperation, acceptedDeletes: List<dev.agenticscheduler.sync.FocusBlockDelete>) = Unit
}

private class MemoryTasks(private val task: Task, initialBlocks: Collection<FocusBlock> = emptyList()) : TaskRepository {
    val blocks = linkedMapOf<FocusBlockId, FocusBlock>().apply { initialBlocks.forEach { put(it.id, it) } }
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

private class MemoryEvents(var values: List<Event> = emptyList()) : EventRepository {
    override fun observeAll(): Flow<ImmutableList<Event>> = flowOf(values.toImmutableList())
    override suspend fun get(id: EventId): Event? = null
    override suspend fun upsert(event: Event) = Unit
}

private class MemoryProfiles(private var profile: PlanningProfile) : PlanningProfileRepository {
    override fun observeAll(): Flow<ImmutableList<PlanningProfile>> = flowOf(listOf(profile).toImmutableList())
    override suspend fun get(id: PlanningProfileId): PlanningProfile? = profile.takeIf { it.id == id }
    override suspend fun upsert(profile: PlanningProfile) { this.profile = profile }
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
