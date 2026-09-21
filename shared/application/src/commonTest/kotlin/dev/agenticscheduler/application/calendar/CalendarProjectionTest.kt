package dev.agenticscheduler.application.calendar

import dev.agenticscheduler.application.persistence.*
import dev.agenticscheduler.application.history.ActiveConflictAwareSourceFactQuery
import dev.agenticscheduler.application.history.ConflictAwareProjection
import dev.agenticscheduler.application.history.ConflictAwareSourceFactQuery
import dev.agenticscheduler.application.history.ConflictAwareSourceFactReadService
import dev.agenticscheduler.application.history.ConflictCollectionProjection
import dev.agenticscheduler.domain.academic.*
import dev.agenticscheduler.domain.event.Event
import dev.agenticscheduler.domain.id.*
import dev.agenticscheduler.domain.planning.Flexibility
import dev.agenticscheduler.domain.planning.PinState
import dev.agenticscheduler.domain.task.*
import dev.agenticscheduler.domain.time.*
import dev.agenticscheduler.sync.*
import kotlin.test.*
import kotlin.time.Instant
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone

class CalendarProjectionTest {
    private val zone = TimeZone.of("America/New_York")
    private val viewport = CalendarViewport(LocalDate(2026, 3, 8), LocalDate(2026, 3, 10), zone)

    @Test fun `viewport rejects empty or reversed ranges`() {
        assertFails { CalendarViewport(LocalDate(2026, 3, 8), LocalDate(2026, 3, 8), zone) }
        assertFails { CalendarViewport(LocalDate(2026, 3, 9), LocalDate(2026, 3, 8), zone) }
    }

    @Test fun `preserves all time semantics and exam variants`() {
        val zoned = event(1, "Zoned", ZonedTimeRange(Instant.parse("2026-03-08T14:00:00Z"), Instant.parse("2026-03-08T15:00:00Z"), TimeZone.of("UTC")))
        val allDay = event(2, "All day", AllDayRange(LocalDate(2026, 3, 8), LocalDate(2026, 3, 9)))
        val floating = event(3, "Floating", FloatingTimeRange(LocalDateTime(2026, 3, 8, 9, 0), LocalDateTime(2026, 3, 8, 10, 0)))
        val exact = Exam(ExamId(id(4)), semester().id, null, "Exact", ExamSchedule.Exact(ZonedTimeRange(Instant.parse("2026-03-08T16:00:00Z"), Instant.parse("2026-03-08T17:00:00Z"), TimeZone.of("UTC"))))
        val dateOnly = Exam(ExamId(id(5)), semester().id, null, "Date only", ExamSchedule.DateOnly(LocalDate(2026, 3, 9)))
        val unscheduled = Exam(ExamId(id(6)), semester().id, null, "Unscheduled", ExamSchedule.Unscheduled)
        val result = project(viewport, listOf(zoned, allDay, floating), emptyList(), input(exams = listOf(exact, dateOnly, unscheduled)))
        assertEquals(5, result.items.size)
        assertEquals(zoned.time, (result.items.filterIsInstance<CalendarItem.Zoned>().first { it.title == "Zoned" }).originalRange)
        assertTrue(result.items.any { it is CalendarItem.AllDay && it.range == allDay.time })
        assertTrue(result.items.any { it is CalendarItem.Floating && it.range == floating.time })
        assertTrue(result.items.any { it is CalendarItem.DateOnly && it.date == LocalDate(2026, 3, 9) })
        assertFalse(result.items.any { it.title == "Unscheduled" })
    }

    @Test fun `derived course session uses occurrence identity and surfaces resolver issues`() {
        val course = course()
        val courseViewport = CalendarViewport(LocalDate(2026, 3, 2), LocalDate(2026, 3, 3), zone)
        val valid = project(courseViewport, emptyList(), emptyList(), input(courses = listOf(course), rules = listOf(rule())))
        val session = assertIs<CalendarItem.Zoned>(valid.items.single())
        assertEquals(CalendarSourceRef.CourseSession(CourseOccurrenceKey(rule().id, AcademicWeekNumber(1))), session.source)
        val invalidRule = rule(week = AcademicWeekNumber(2))
        val invalid = project(courseViewport, emptyList(), emptyList(), input(courses = listOf(course), rules = listOf(invalidRule)))
        assertTrue(invalid.items.isEmpty())
        assertTrue(invalid.issues.any { it is CalendarProjectionIssue.AcademicResolution })
    }

    @Test fun `ordering conflicts and DST viewport filtering are deterministic`() {
        val first = event(10, "First", ZonedTimeRange(Instant.parse("2026-03-08T14:00:00Z"), Instant.parse("2026-03-08T15:00:00Z"), TimeZone.of("UTC")))
        val overlap = event(11, "Overlap", ZonedTimeRange(Instant.parse("2026-03-08T14:30:00Z"), Instant.parse("2026-03-08T15:30:00Z"), TimeZone.of("UTC")))
        val adjacent = event(12, "Adjacent", ZonedTimeRange(Instant.parse("2026-03-08T15:30:00Z"), Instant.parse("2026-03-08T16:00:00Z"), TimeZone.of("UTC")))
        val firstPass = project(viewport, listOf(adjacent, overlap, first), emptyList(), input())
        val secondPass = project(viewport, listOf(first, adjacent, overlap), emptyList(), input())
        assertEquals(firstPass, secondPass)
        assertEquals(1, firstPass.conflicts.size)
        assertEquals(CalendarSourceRef.Event(first.id), firstPass.conflicts.single().first)
        assertTrue(firstPass.items.any { it.title == "First" }) // March 8 is a 23-hour local DST day.
        val allDay = event(13, "All day", AllDayRange(LocalDate(2026, 3, 8), LocalDate(2026, 3, 9)))
        val floating = event(14, "Floating", FloatingTimeRange(LocalDateTime(2026, 3, 8, 14, 0), LocalDateTime(2026, 3, 8, 15, 0)))
        assertTrue(project(viewport, listOf(allDay, floating), emptyList(), input()).conflicts.isEmpty())
    }

    @Test fun `mixed source ordering uses the canonical source rank after equal times`() {
        val courseViewport = CalendarViewport(LocalDate(2026, 3, 2), LocalDate(2026, 3, 3), zone)
        val sharedTime = ZonedTimeRange(
            Instant.parse("2026-03-02T14:00:00Z"),
            Instant.parse("2026-03-02T15:00:00Z"),
            zone,
        )
        val event = event(40, "Event", sharedTime)
        val exam = Exam(ExamId(id(41)), semester().id, null, "Exam", ExamSchedule.Exact(sharedTime))
        val focus = FocusBlock(
            FocusBlockId(id(42)),
            TaskId(id(43)),
            sharedTime,
            Flexibility.HARD,
            PinState.PINNED,
        )

        val result = project(
            courseViewport,
            listOf(event),
            listOf(focus),
            input(courses = listOf(course()), rules = listOf(rule()), exams = listOf(exam)),
        )

        assertEquals(
            listOf(
                CalendarSourceRef.Event(event.id),
                CalendarSourceRef.CourseSession(CourseOccurrenceKey(rule().id, AcademicWeekNumber(1))),
                CalendarSourceRef.Exam(exam.id),
                CalendarSourceRef.FocusBlock(focus.id),
            ),
            result.items.filterIsInstance<CalendarItem.Zoned>().map { it.source },
        )
    }

    @Test fun `local date intersection includes cross midnight zoned and floating items`() {
        val date = LocalDate(2026, 3, 3)
        val zoned = CalendarItem.Zoned(
            CalendarSourceRef.Event(EventId(id(50))),
            "Overnight zoned",
            ZonedTimeRange(
                Instant.parse("2026-03-03T04:30:00Z"),
                Instant.parse("2026-03-03T06:00:00Z"),
                zone,
            ),
        )
        val floating = CalendarItem.Floating(
            CalendarSourceRef.Event(EventId(id(51))),
            "Overnight floating",
            FloatingTimeRange(
                LocalDateTime(2026, 3, 2, 23, 30),
                LocalDateTime(2026, 3, 3, 1, 0),
            ),
        )

        assertTrue(zoned.intersectsLocalDate(date, zone))
        assertTrue(floating.intersectsLocalDate(date, zone))
        assertTrue(zoned.intersectsLocalDate(LocalDate(2026, 3, 2), zone))
        assertTrue(floating.intersectsLocalDate(LocalDate(2026, 3, 2), zone))
        assertFalse(zoned.intersectsLocalDate(LocalDate(2026, 3, 4), zone))
        assertFalse(floating.intersectsLocalDate(LocalDate(2026, 3, 4), zone))
    }

    @Test fun `repository flow updates produce a new projection`() = runBlocking {
        val eventRepository = FakeEventRepository()
        val service = RepositoryCalendarQueryService(eventRepository, FakeTaskRepository(), FakeAcademicRepository())
        val initial = CompletableDeferred<Unit>()
        val next = async(start = CoroutineStart.UNDISPATCHED) { service.observe(viewport).onEach { initial.complete(Unit) }.drop(1).first() }
        initial.await()
        eventRepository.values.value = listOf(event(20, "Update", ZonedTimeRange(Instant.parse("2026-03-08T14:00:00Z"), Instant.parse("2026-03-08T15:00:00Z"), TimeZone.of("UTC")))).toImmutableList()
        assertEquals("Update", next.await().items.single().title)
    }

    @Test fun `conflict aware calendar projects the shared source fact boundary without mutating Active State`() = runBlocking {
        val durable = event(60, "Durable", ZonedTimeRange(Instant.parse("2026-03-08T14:00:00Z"), Instant.parse("2026-03-08T15:00:00Z"), TimeZone.UTC))
        val candidate = durable.copy(title = "Provisional")
        val durableTime = durable.time as ZonedTimeRange
        val candidateTime = candidate.time as ZonedTimeRange
        val localReplica = id(61); val remoteReplica = id(62)
        val local = SyncOperation(id(64), DvvSnapshot(emptyList(), DotSnapshot(localReplica, 1)), HlcSnapshot(1, 0, localReplica), MutationOrigin.User, listOf(EventPut(null, EventImage(durable.id.value, durable.title, EventTimeImage.Zoned(ZonedTimeRangeImage(durableTime.start.toString(), durableTime.endExclusive.toString(), durableTime.timeZone.id)), FlexibilityImage.HARD, PinStateImage.PINNED))))
        val remote = SyncOperation(id(63), DvvSnapshot(emptyList(), DotSnapshot(remoteReplica, 1)), HlcSnapshot(2, 0, remoteReplica), MutationOrigin.User, listOf(EventPut(null, EventImage(candidate.id.value, candidate.title, EventTimeImage.Zoned(ZonedTimeRangeImage(candidateTime.start.toString(), candidateTime.endExclusive.toString(), candidateTime.timeZone.id)), FlexibilityImage.HARD, PinStateImage.PINNED))))
        val participants = listOf(local, remote).sortedBy(SyncOperation::mutationId).map { SyncConflictParticipant(MutationId(it.mutationId), it.dvv, LocalJournalCodec.encode(it)) }
        val receive = CalendarMemoryReceive(listOf(SyncConflict("calendar-conflict", SyncSpaceId("personal-space"), listOf(SyncConflictEntityRef(EntityKind.EVENT, durable.id.value, listOf("title"))), participants, MutationId(remote.mutationId), SyncConflictKind.SEMANTIC, commonCausalContextOf(participants), SyncConflictStatus.OPEN)))
        val events = FakeEventRepository().apply { values.value = listOf(durable).toImmutableList() }
        val service = ConflictAwareSourceFactReadService(
            events,
            FakeTaskRepository(),
            FakePlanningProfileRepository(),
            FakeAcademicRepository(),
            ActiveConflictAwareSourceFactQuery(ConflictAwareProjection(receive), SyncSpaceId("personal-space")),
        )

        val result = service.observe(viewport).first()

        assertEquals("Provisional", result.items.single().title)
        assertEquals("Durable", events.values.value.single().title, "D8-P01 projection must not mutate Active State.")
        assertEquals(0, receive.writeCount, "D8-P01 projection must not journal or alter receive state.")
    }

    @Test fun `conflict aware calendar fails closed rather than rendering raw unprojectable source facts`() = runBlocking {
        val durable = event(65, "Must not render", ZonedTimeRange(Instant.parse("2026-03-08T14:00:00Z"), Instant.parse("2026-03-08T15:00:00Z"), TimeZone.UTC))
        val sourceFacts = object : ConflictAwareSourceFactQuery {
            override suspend fun project(durable: EntityMutation) = error("Calendar reads collections.")
            override suspend fun projectCollection(entityKind: EntityKind, durable: Collection<EntityMutation>) =
                if (entityKind == EntityKind.EVENT) ConflictCollectionProjection.Unprojectable(listOf("conflict-65"), "Event candidate is unavailable.")
                else ConflictCollectionProjection.Projected(durable.toList())
        }
        val events = FakeEventRepository().apply { values.value = listOf(durable).toImmutableList() }
        val service = ConflictAwareSourceFactReadService(events, FakeTaskRepository(), FakePlanningProfileRepository(), FakeAcademicRepository(), sourceFacts)

        val result = service.observe(viewport).first()

        assertTrue(result.items.isEmpty())
        val issue = assertIs<CalendarProjectionIssue.SyncConflictUnprojectable>(result.issues.single())
        assertEquals(listOf("conflict-65"), issue.conflictIds)
        assertEquals("Must not render", events.values.value.single().title, "Raw Active State is retained but deliberately not rendered.")
    }

    private fun input(
        courses: List<Course> = emptyList(), rules: List<CourseScheduleRule> = emptyList(), exams: List<Exam> = emptyList(),
    ) = CalendarProjectionInput(listOf(semester()).toImmutableList(), courses.toImmutableList(), rules.toImmutableList(), emptyList<PeriodTemplate>().toImmutableList(), emptyList<AcademicHoliday>().toImmutableList(), emptyList<CourseOccurrenceException>().toImmutableList(), exams.toImmutableList())

    private fun semester() = Semester(SemesterId(id(30)), AcademicYearId(id(31)), "Spring", LocalDate(2026, 3, 1), LocalDate(2026, 4, 1), zone, listOf(AcademicWeek(AcademicWeekNumber(1), LocalDate(2026, 3, 2), LocalDate(2026, 3, 9))).toImmutableList())
    private fun course() = Course(CourseId(id(32)), semester().id, "Math", null)
    private fun rule(week: AcademicWeekNumber = AcademicWeekNumber(1)) = CourseScheduleRule(CourseScheduleRuleId(id(33)), course().id, DayOfWeek.MONDAY, TeachingWeekSet.of(listOf(week)), CourseTimeSpec.ClockTime(LocalTime(9, 0), LocalTime(10, 0)), null)
    private fun event(n: Int, title: String, time: TimePlacement) = Event(EventId(id(n)), title, time, Flexibility.HARD, PinState.PINNED)
    private fun id(n: Int) = "00000000-0000-7000-8000-${n.toString().padStart(12, '0')}"
}

private class FakeEventRepository : EventRepository {
    val values = MutableStateFlow(emptyList<Event>().toImmutableList())
    override fun observeAll(): Flow<ImmutableList<Event>> = values
    override suspend fun get(id: EventId) = values.value.firstOrNull { it.id == id }
    override suspend fun upsert(event: Event) { values.value = (values.value.filterNot { it.id == event.id } + event).toImmutableList() }
}

private class FakeTaskRepository : TaskRepository {
    private val focus = MutableStateFlow(emptyList<FocusBlock>().toImmutableList())
    override fun observeTasks(): Flow<ImmutableList<Task>> = MutableStateFlow(emptyList<Task>().toImmutableList())
    override suspend fun getTask(id: TaskId): Task? = null
    override suspend fun upsertTask(task: Task) = Unit
    override fun observeFocusBlocks(): Flow<ImmutableList<FocusBlock>> = focus
    override suspend fun getFocusBlock(id: FocusBlockId): FocusBlock? = null
    override suspend fun upsertFocusBlock(focusBlock: FocusBlock) = Unit
    override suspend fun deleteFocusBlock(id: FocusBlockId) = Unit
    override fun observeWorkLogs(): Flow<ImmutableList<WorkLog>> = MutableStateFlow(emptyList<WorkLog>().toImmutableList())
    override suspend fun getWorkLog(id: WorkLogId): WorkLog? = null
    override suspend fun upsertWorkLog(workLog: WorkLog) = Unit
    override fun observeDependencies(): Flow<ImmutableList<TaskDependency>> = MutableStateFlow(emptyList<TaskDependency>().toImmutableList())
    override suspend fun getDependency(id: TaskDependencyId): TaskDependency? = null
    override suspend fun upsertDependency(dependency: TaskDependency) = Unit
}

private class FakeAcademicRepository : AcademicRepository {
    private fun <T> empty(): Flow<ImmutableList<T>> = MutableStateFlow(emptyList<T>().toImmutableList())
    override fun observeAcademicYears() = empty<AcademicYear>(); override suspend fun getAcademicYear(id: AcademicYearId): AcademicYear? = null; override suspend fun upsertAcademicYear(value: AcademicYear) = Unit
    override fun observeSemesters() = empty<Semester>(); override suspend fun getSemester(id: SemesterId): Semester? = null; override suspend fun upsertSemester(value: Semester) = Unit
    override fun observeCourses() = empty<Course>(); override suspend fun getCourse(id: CourseId): Course? = null; override suspend fun upsertCourse(value: Course) = Unit
    override fun observePeriodTemplates() = empty<PeriodTemplate>(); override suspend fun getPeriodTemplate(id: PeriodTemplateId): PeriodTemplate? = null; override suspend fun upsertPeriodTemplate(value: PeriodTemplate) = Unit
    override fun observeCourseScheduleRules() = empty<CourseScheduleRule>(); override suspend fun getCourseScheduleRule(id: CourseScheduleRuleId): CourseScheduleRule? = null; override suspend fun upsertCourseScheduleRule(value: CourseScheduleRule) = Unit
    override fun observeAcademicHolidays() = empty<AcademicHoliday>(); override suspend fun getAcademicHoliday(id: AcademicHolidayId): AcademicHoliday? = null; override suspend fun upsertAcademicHoliday(value: AcademicHoliday) = Unit
    override fun observeCourseOccurrenceExceptions() = empty<CourseOccurrenceException>(); override suspend fun getCourseOccurrenceException(id: CourseOccurrenceExceptionId): CourseOccurrenceException? = null; override suspend fun upsertCourseOccurrenceException(value: CourseOccurrenceException) = Unit
    override fun observeExams() = empty<Exam>(); override suspend fun getExam(id: ExamId): Exam? = null; override suspend fun upsertExam(value: Exam) = Unit
}

private class FakePlanningProfileRepository : PlanningProfileRepository {
    override fun observeAll(): Flow<ImmutableList<dev.agenticscheduler.domain.planning.PlanningProfile>> = MutableStateFlow(emptyList<dev.agenticscheduler.domain.planning.PlanningProfile>().toImmutableList())
    override suspend fun get(id: PlanningProfileId): dev.agenticscheduler.domain.planning.PlanningProfile? = null
    override suspend fun upsert(profile: dev.agenticscheduler.domain.planning.PlanningProfile) = Unit
}

private class CalendarMemoryReceive(private val conflictsValue: List<SyncConflict>) : SyncReceiveRepository {
    var writeCount = 0
    override suspend fun serverCursor(syncSpaceId: SyncSpaceId) = 0L
    override suspend fun saveServerCursor(syncSpaceId: SyncSpaceId, cursor: Long) { writeCount++ }
    override suspend fun pending(syncSpaceId: SyncSpaceId, mutationId: String): PendingSyncReceive? = null
    override suspend fun pending(syncSpaceId: SyncSpaceId): List<PendingSyncReceive> = emptyList()
    override suspend fun savePending(value: PendingSyncReceive) { writeCount++ }
    override suspend fun removePending(syncSpaceId: SyncSpaceId, mutationId: String) { writeCount++ }
    override suspend fun handledDot(syncSpaceId: SyncSpaceId, replicaId: ReplicaId, counter: Long): HandledReceiveDot? = null
    override suspend fun handledDots(syncSpaceId: SyncSpaceId): List<HandledReceiveDot> = emptyList()
    override suspend fun saveHandledDot(value: HandledReceiveDot) { writeCount++ }
    override suspend fun quarantine(value: ProtocolQuarantine) { writeCount++ }
    override suspend fun quarantine(syncSpaceId: SyncSpaceId, mutationId: String): ProtocolQuarantine? = null
    override suspend fun saveConflict(value: SyncConflict) { writeCount++ }
    override suspend fun conflict(conflictId: String): SyncConflict? = conflictsValue.firstOrNull { it.conflictId == conflictId }
    override suspend fun conflicts(syncSpaceId: SyncSpaceId): List<SyncConflict> = conflictsValue
}
