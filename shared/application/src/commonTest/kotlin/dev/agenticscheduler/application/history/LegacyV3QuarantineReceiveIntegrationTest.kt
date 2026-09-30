package dev.agenticscheduler.application.history

import dev.agenticscheduler.application.id.UuidV7Generator
import dev.agenticscheduler.application.persistence.*
import dev.agenticscheduler.domain.academic.*
import dev.agenticscheduler.domain.event.Event
import dev.agenticscheduler.domain.id.*
import dev.agenticscheduler.domain.planning.PlanningProfile
import dev.agenticscheduler.domain.task.*
import dev.agenticscheduler.sync.*
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/** Exercises the real D8 SyncEngine receive path: whole V3 quarantine, then independent V1 apply. */
class LegacyV3QuarantineReceiveIntegrationTest {
    @Test fun `quarantined Agent V3 does not block later independent V1 business receive`() = kotlinx.coroutines.runBlocking {
        val space = SyncSpaceId("personal-space")
        val harness = ReceiverHarness()
        val engine = harness.engine()
        val v3Id = id(1)
        val agentReplica = id(2)
        val v3 = AgentSyncWireCodec.encodePayload(SyncPayloadV3(operation = AgentSyncOperation(
            operationId = MutationId(v3Id),
            agentDvv = AgentDvvSnapshot(emptyList(), AgentDot(AgentReplicaId(agentReplica), 1)),
            hlc = AgentHlcSnapshot(100, 0, AgentReplicaId(agentReplica)),
            agentEvent = ThreadCreated(AgentThreadSyncId(id(3)), "Private history", 90),
        )))

        assertEquals(
            SyncReceiveResult.Quarantined(v3Id, ProtocolQuarantineReason.UNSUPPORTED_PAYLOAD_VERSION),
            engine.receive(DecryptedPayloadReceipt(space, v3Id, 10, v3)),
        )

        val businessId = id(30)
        val businessReplica = id(31)
        val v1 = SyncWireCodec.encodePayload(SyncPayloadV1(operation = SyncOperation(
            mutationId = businessId,
            dvv = DvvSnapshot(emptyList(), DotSnapshot(businessReplica, 1)),
            hlc = HlcSnapshot(200, 0, businessReplica),
            origin = MutationOrigin.User,
            orderedMutations = listOf(EventPut(null, EventImage(
                id(32), "Independent business change",
                EventTimeImage.AllDay(AllDayRangeImage("2026-01-01", "2026-01-02")),
                FlexibilityImage.HARD, PinStateImage.UNPINNED,
            ))),
        )))
        assertEquals(SyncReceiveResult.Applied(MutationId(businessId)), engine.receive(DecryptedPayloadReceipt(space, businessId, 11, v1)))

        assertEquals(11L, harness.receiveState.serverCursor(space))
        assertEquals(ProtocolQuarantineReason.UNSUPPORTED_PAYLOAD_VERSION, harness.receiveState.quarantine(space, v3Id)?.reason)
        assertEquals(listOf("Independent business change"), harness.events.values.map(Event::title))
        assertEquals(listOf(businessId), harness.journal.mutations.map { it.operation.mutationId })
        assertEquals(listOf(HandledReceiveDot(space, ReplicaId(businessReplica), 1, businessId)), harness.receiveState.handledDots(space))
    }

    private class ReceiverHarness {
        val journal = MemoryJournal()
        val receiveState = MemoryReceive()
        val events = MemoryEvents()
        fun engine() = SyncEngine(
            transactions = object : ApplicationTransactionRunner {
                override suspend fun <T> inWriteTransaction(block: suspend () -> T): T = block()
            },
            journal = journal,
            history = MemoryHistory(journal),
            receiveState = receiveState,
            events = events,
            tasks = EmptyTasks,
            profiles = EmptyProfiles,
            academics = EmptyAcademics,
            ids = object : UuidV7Generator { override fun next() = "00000000-0000-7000-8000-000000000099" },
            wallClock = MutationWallClock { 250 },
        )
    }

    private class MemoryJournal : MutationJournalRepository {
        var replicaState: LocalReplicaCausalState? = null
        val mutations = mutableListOf<CommittedMutation>()
        override suspend fun localReplicaState() = replicaState
        override suspend fun saveLocalReplicaState(state: LocalReplicaCausalState) { replicaState = state }
        override suspend fun appendCommittedMutation(mutation: CommittedMutation) { mutations += mutation }
        override suspend fun advanceFocusBlockTombstones(operation: SyncOperation, acceptedDeletes: List<FocusBlockDelete>) = Unit
    }

    private class MemoryHistory(private val journal: MemoryJournal) : HistoryRepository {
        override suspend fun timeline() = journal.mutations.toList()
        override suspend fun mutation(mutationId: String) = journal.mutations.singleOrNull { it.operation.mutationId == mutationId }
        override suspend fun entityChanges(entityKind: EntityKind, entityId: String) = emptyList<HistoryChange>()
        override suspend fun diff(mutationId: String) = emptyList<HistoryChange>()
        override suspend fun focusBlockTombstone(focusBlockId: String): FocusBlockTombstone? = null
    }

    private class MemoryEvents : EventRepository {
        val values = mutableListOf<Event>()
        override fun observeAll(): Flow<ImmutableList<Event>> = flowOf(values.toImmutableList())
        override suspend fun get(id: EventId) = values.singleOrNull { it.id == id }
        override suspend fun upsert(event: Event) { values.removeAll { it.id == event.id }; values += event }
    }

    private class MemoryReceive : SyncReceiveRepository {
        private val cursors = mutableMapOf<SyncSpaceId, Long>()
        private val quarantines = mutableMapOf<Pair<SyncSpaceId, String>, ProtocolQuarantine>()
        private val handled = mutableMapOf<Triple<SyncSpaceId, ReplicaId, Long>, HandledReceiveDot>()
        override suspend fun serverCursor(syncSpaceId: SyncSpaceId) = cursors[syncSpaceId] ?: 0L
        override suspend fun saveServerCursor(syncSpaceId: SyncSpaceId, cursor: Long) { cursors[syncSpaceId] = cursor }
        override suspend fun pending(syncSpaceId: SyncSpaceId, mutationId: String): PendingSyncReceive? = null
        override suspend fun pending(syncSpaceId: SyncSpaceId) = emptyList<PendingSyncReceive>()
        override suspend fun savePending(value: PendingSyncReceive) = Unit
        override suspend fun removePending(syncSpaceId: SyncSpaceId, mutationId: String) = Unit
        override suspend fun handledDot(syncSpaceId: SyncSpaceId, replicaId: ReplicaId, counter: Long) = handled[Triple(syncSpaceId, replicaId, counter)]
        override suspend fun handledDots(syncSpaceId: SyncSpaceId) = handled.values.filter { it.syncSpaceId == syncSpaceId }
        override suspend fun saveHandledDot(value: HandledReceiveDot) { handled[Triple(value.syncSpaceId, value.replicaId, value.counter)] = value }
        override suspend fun quarantine(value: ProtocolQuarantine) { quarantines.putIfAbsent(value.syncSpaceId to value.mutationId, value) }
        override suspend fun quarantine(syncSpaceId: SyncSpaceId, mutationId: String) = quarantines[syncSpaceId to mutationId]
        override suspend fun saveConflict(value: SyncConflict) = Unit
        override suspend fun conflict(conflictId: String): SyncConflict? = null
        override suspend fun conflicts(syncSpaceId: SyncSpaceId) = emptyList<SyncConflict>()
    }

    private object EmptyTasks : TaskRepository {
        override fun observeTasks(): Flow<ImmutableList<Task>> = flowOf(emptyList<Task>().toImmutableList())
        override suspend fun getTask(id: TaskId): Task? = null
        override suspend fun upsertTask(task: Task) = Unit
        override fun observeFocusBlocks(): Flow<ImmutableList<FocusBlock>> = flowOf(emptyList<FocusBlock>().toImmutableList())
        override suspend fun getFocusBlock(id: FocusBlockId): FocusBlock? = null
        override suspend fun upsertFocusBlock(focusBlock: FocusBlock) = Unit
        override suspend fun deleteFocusBlock(id: FocusBlockId) = Unit
        override fun observeWorkLogs(): Flow<ImmutableList<WorkLog>> = flowOf(emptyList<WorkLog>().toImmutableList())
        override suspend fun getWorkLog(id: WorkLogId): WorkLog? = null
        override suspend fun upsertWorkLog(workLog: WorkLog) = Unit
        override fun observeDependencies(): Flow<ImmutableList<TaskDependency>> = flowOf(emptyList<TaskDependency>().toImmutableList())
        override suspend fun getDependency(id: TaskDependencyId): TaskDependency? = null
        override suspend fun upsertDependency(dependency: TaskDependency) = Unit
    }

    private object EmptyProfiles : PlanningProfileRepository {
        override fun observeAll(): Flow<ImmutableList<PlanningProfile>> = flowOf(emptyList<PlanningProfile>().toImmutableList())
        override suspend fun get(id: PlanningProfileId): PlanningProfile? = null
        override suspend fun upsert(profile: PlanningProfile) = Unit
    }

    private object EmptyAcademics : AcademicRepository {
        private fun <T> empty(): Flow<ImmutableList<T>> = flowOf(emptyList<T>().toImmutableList())
        override fun observeAcademicYears() = empty<AcademicYear>(); override suspend fun getAcademicYear(id: AcademicYearId): AcademicYear? = null; override suspend fun upsertAcademicYear(value: AcademicYear) = Unit
        override fun observeSemesters() = empty<Semester>(); override suspend fun getSemester(id: SemesterId): Semester? = null; override suspend fun upsertSemester(value: Semester) = Unit
        override fun observeCourses() = empty<Course>(); override suspend fun getCourse(id: CourseId): Course? = null; override suspend fun upsertCourse(value: Course) = Unit
        override fun observePeriodTemplates() = empty<PeriodTemplate>(); override suspend fun getPeriodTemplate(id: PeriodTemplateId): PeriodTemplate? = null; override suspend fun upsertPeriodTemplate(value: PeriodTemplate) = Unit
        override fun observeCourseScheduleRules() = empty<CourseScheduleRule>(); override suspend fun getCourseScheduleRule(id: CourseScheduleRuleId): CourseScheduleRule? = null; override suspend fun upsertCourseScheduleRule(value: CourseScheduleRule) = Unit
        override fun observeAcademicHolidays() = empty<AcademicHoliday>(); override suspend fun getAcademicHoliday(id: AcademicHolidayId): AcademicHoliday? = null; override suspend fun upsertAcademicHoliday(value: AcademicHoliday) = Unit
        override fun observeCourseOccurrenceExceptions() = empty<CourseOccurrenceException>(); override suspend fun getCourseOccurrenceException(id: CourseOccurrenceExceptionId): CourseOccurrenceException? = null; override suspend fun upsertCourseOccurrenceException(value: CourseOccurrenceException) = Unit
        override fun observeExams() = empty<Exam>(); override suspend fun getExam(id: ExamId): Exam? = null; override suspend fun upsertExam(value: Exam) = Unit
    }
    private fun id(value: Int) = "00000000-0000-7000-8000-${value.toString().padStart(12, '0')}"
}
