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
import kotlinx.collections.immutable.ImmutableList
import kotlinx.coroutines.flow.Flow

interface ApplicationTransactionRunner { suspend fun <T> inWriteTransaction(block: suspend () -> T): T }

/** Durable D7 journal boundary. Implementations participate in the caller's application transaction. */
interface MutationJournalRepository {
    suspend fun localReplicaState(): LocalReplicaCausalState?
    suspend fun saveLocalReplicaState(state: LocalReplicaCausalState)
    suspend fun appendCommittedMutation(mutation: CommittedMutation)
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
)

data class FocusBlockTombstone(val focusBlockId: String, val deletionMutationId: MutationId)
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
