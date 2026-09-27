package dev.agenticscheduler.application.persistence

import dev.agenticscheduler.domain.academic.*
import dev.agenticscheduler.domain.event.Event
import dev.agenticscheduler.domain.id.*
import dev.agenticscheduler.domain.planning.PlanningProfile
import dev.agenticscheduler.domain.task.*
import dev.agenticscheduler.sync.HlcTimestamp
import dev.agenticscheduler.sync.ReplicaId
import dev.agenticscheduler.sync.SyncOperation
import dev.agenticscheduler.sync.EntityKind
import dev.agenticscheduler.sync.MutationId
import dev.agenticscheduler.sync.DvvSnapshot
import dev.agenticscheduler.sync.SyncSpaceId
import dev.agenticscheduler.sync.EncryptedEnvelopeV1
import dev.agenticscheduler.sync.ProtocolQuarantine
import dev.agenticscheduler.sync.SyncConflict
import dev.agenticscheduler.sync.FocusBlockDelete
import kotlinx.collections.immutable.ImmutableList
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

interface ApplicationTransactionRunner { suspend fun <T> inWriteTransaction(block: suspend () -> T): T }

/** Durable D7 journal boundary. Implementations participate in the caller's application transaction. */
interface MutationJournalRepository {
    suspend fun localReplicaState(): LocalReplicaCausalState?
    suspend fun saveLocalReplicaState(state: LocalReplicaCausalState)
    suspend fun appendCommittedMutation(mutation: CommittedMutation)
    suspend fun advanceFocusBlockTombstones(operation: SyncOperation, acceptedDeletes: List<FocusBlockDelete>)
}

data class LocalReplicaCausalState(
    val replicaId: ReplicaId,
    val lastCounter: Long,
    val observedContext: Map<ReplicaId, Long>,
    val lastHlc: HlcTimestamp,
) { init { require(lastCounter >= 0) } }

data class CommittedMutation(
    val operation: SyncOperation,
    val committedAtEpochMillis: Long,
    val outboundEligible: Boolean = true,
)

interface HistoryRepository {
    suspend fun timeline(): List<CommittedMutation>
    suspend fun mutation(mutationId: String): CommittedMutation?
    suspend fun entityChanges(entityKind: EntityKind, entityId: String): List<HistoryChange>
    suspend fun diff(mutationId: String): List<HistoryChange>
    suspend fun focusBlockTombstone(focusBlockId: String): FocusBlockTombstone?
}

data class HistoryChange(
    val mutationId: String,
    val ordinal: Int,
    val entityKind: EntityKind,
    val entityId: String,
    val operationKind: String,
    val beforeImageJson: String?,
    val afterImageJson: String?,
    val hlc: HlcTimestamp,
)

data class FocusBlockTombstone(val focusBlockId: String, val deletionMutationId: MutationId, val dvv: DvvSnapshot)

/** D8 receive-state boundary. Values here are local transport/audit metadata, never Domain facts. */
interface SyncReceiveRepository {
    suspend fun serverCursor(syncSpaceId: SyncSpaceId): Long
    suspend fun saveServerCursor(syncSpaceId: SyncSpaceId, cursor: Long)
    suspend fun pending(syncSpaceId: SyncSpaceId, mutationId: String): PendingSyncReceive?
    suspend fun pending(syncSpaceId: SyncSpaceId): List<PendingSyncReceive>
    suspend fun savePending(value: PendingSyncReceive)
    suspend fun removePending(syncSpaceId: SyncSpaceId, mutationId: String)
    suspend fun handledDot(syncSpaceId: SyncSpaceId, replicaId: ReplicaId, counter: Long): HandledReceiveDot?
    suspend fun handledDots(syncSpaceId: SyncSpaceId): List<HandledReceiveDot>
    suspend fun saveHandledDot(value: HandledReceiveDot)
    suspend fun quarantine(value: ProtocolQuarantine)
    suspend fun quarantine(syncSpaceId: SyncSpaceId, mutationId: String): ProtocolQuarantine?
    suspend fun saveConflict(value: SyncConflict)
    suspend fun conflict(conflictId: String): SyncConflict?
    suspend fun conflicts(syncSpaceId: SyncSpaceId): List<SyncConflict>

    /** Emits persisted conflict snapshots; implementations should stay reactive when supported. */
    fun observeConflicts(syncSpaceId: SyncSpaceId): Flow<List<SyncConflict>> = flow {
        emit(conflicts(syncSpaceId))
    }
}

/** Durable outbound ciphertext; retaining the exact envelope makes retries idempotent. */
interface SyncOutboundEnvelopeRepository {
    suspend fun envelope(syncSpaceId: SyncSpaceId, mutationId: String): StoredOutboundEnvelope?
    suspend fun save(value: StoredOutboundEnvelope)
    suspend fun markUploaded(syncSpaceId: SyncSpaceId, mutationId: String)
}

data class StoredOutboundEnvelope(
    val syncSpaceId: SyncSpaceId,
    val mutationId: String,
    val envelope: EncryptedEnvelopeV1,
    val uploaded: Boolean,
) {
    init {
        require(mutationId.isNotBlank())
        require(envelope.syncSpaceId == syncSpaceId)
    }
}

/** A valid but causally blocked D8 receipt. It is trusted-local transport state, never a Domain fact. */
data class PendingSyncReceive(
    val syncSpaceId: SyncSpaceId,
    val mutationId: String,
    val serverCursor: Long,
    val payloadJson: String,
) {
    init {
        require(mutationId.isNotBlank())
        require(serverCursor >= 0)
        require(payloadJson.isNotBlank())
    }
}

/** A remote operation dot whose result (apply, conflict, or causal acknowledgement) is durable locally. */
data class HandledReceiveDot(
    val syncSpaceId: SyncSpaceId,
    val replicaId: ReplicaId,
    val counter: Long,
    val mutationId: String,
) {
    init {
        require(counter >= 0)
        require(mutationId.isNotBlank())
    }
}

interface EventRepository { fun observeAll(): Flow<ImmutableList<Event>>; suspend fun get(id: EventId): Event?; suspend fun upsert(event: Event) }
interface TaskRepository {
    fun observeTasks(): Flow<ImmutableList<Task>>; suspend fun getTask(id: TaskId): Task?; suspend fun upsertTask(task: Task)
    fun observeFocusBlocks(): Flow<ImmutableList<FocusBlock>>; suspend fun getFocusBlock(id: FocusBlockId): FocusBlock?; suspend fun upsertFocusBlock(focusBlock: FocusBlock); suspend fun deleteFocusBlock(id: FocusBlockId)
    fun observeWorkLogs(): Flow<ImmutableList<WorkLog>>; suspend fun getWorkLog(id: WorkLogId): WorkLog?; suspend fun upsertWorkLog(workLog: WorkLog)
    fun observeDependencies(): Flow<ImmutableList<TaskDependency>>; suspend fun getDependency(id: TaskDependencyId): TaskDependency?; suspend fun upsertDependency(dependency: TaskDependency)
}
interface PlanningProfileRepository { fun observeAll(): Flow<ImmutableList<PlanningProfile>>; suspend fun get(id: PlanningProfileId): PlanningProfile?; suspend fun upsert(profile: PlanningProfile) }
interface AcademicRepository {
    fun observeAcademicYears(): Flow<ImmutableList<AcademicYear>>; suspend fun getAcademicYear(id: AcademicYearId): AcademicYear?; suspend fun upsertAcademicYear(value: AcademicYear)
    fun observeSemesters(): Flow<ImmutableList<Semester>>; suspend fun getSemester(id: SemesterId): Semester?; suspend fun upsertSemester(value: Semester)
    fun observeCourses(): Flow<ImmutableList<Course>>; suspend fun getCourse(id: CourseId): Course?; suspend fun upsertCourse(value: Course)
    fun observePeriodTemplates(): Flow<ImmutableList<PeriodTemplate>>; suspend fun getPeriodTemplate(id: PeriodTemplateId): PeriodTemplate?; suspend fun upsertPeriodTemplate(value: PeriodTemplate)
    fun observeCourseScheduleRules(): Flow<ImmutableList<CourseScheduleRule>>; suspend fun getCourseScheduleRule(id: CourseScheduleRuleId): CourseScheduleRule?; suspend fun upsertCourseScheduleRule(value: CourseScheduleRule)
    fun observeAcademicHolidays(): Flow<ImmutableList<AcademicHoliday>>; suspend fun getAcademicHoliday(id: AcademicHolidayId): AcademicHoliday?; suspend fun upsertAcademicHoliday(value: AcademicHoliday)
    fun observeCourseOccurrenceExceptions(): Flow<ImmutableList<CourseOccurrenceException>>; suspend fun getCourseOccurrenceException(id: CourseOccurrenceExceptionId): CourseOccurrenceException?; suspend fun upsertCourseOccurrenceException(value: CourseOccurrenceException)
    fun observeExams(): Flow<ImmutableList<Exam>>; suspend fun getExam(id: ExamId): Exam?; suspend fun upsertExam(value: Exam)
}
