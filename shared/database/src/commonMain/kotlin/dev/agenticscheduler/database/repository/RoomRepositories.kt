package dev.agenticscheduler.database.repository

import androidx.room3.withWriteTransaction
import dev.agenticscheduler.application.persistence.*
import dev.agenticscheduler.database.AgenticSchedulerDatabase
import dev.agenticscheduler.database.mapper.*
import dev.agenticscheduler.domain.academic.*
import dev.agenticscheduler.domain.event.Event
import dev.agenticscheduler.domain.id.*
import dev.agenticscheduler.domain.planning.PlanningProfile
import dev.agenticscheduler.domain.task.*
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import dev.agenticscheduler.database.record.ChangeLogEntryRecord
import dev.agenticscheduler.database.record.FocusBlockTombstoneRecord
import dev.agenticscheduler.database.record.MutationRecord
import dev.agenticscheduler.database.record.ReplicaCausalStateRecord
import dev.agenticscheduler.database.record.SyncOperationJournalRecord
import dev.agenticscheduler.sync.FocusBlockDelete
import dev.agenticscheduler.sync.LocalJournalCodec
import dev.agenticscheduler.sync.MutationOrigin
import dev.agenticscheduler.sync.MutationId
import dev.agenticscheduler.sync.ReplicaId
import dev.agenticscheduler.sync.HlcTimestamp
import dev.agenticscheduler.sync.operationKind
import dev.agenticscheduler.sync.ProtocolQuarantine
import dev.agenticscheduler.sync.ProtocolQuarantineReason
import dev.agenticscheduler.sync.SyncConflict
import dev.agenticscheduler.sync.SyncReceiveStateCodec
import dev.agenticscheduler.sync.SyncSpaceId
import dev.agenticscheduler.database.record.ProtocolQuarantineRecord
import dev.agenticscheduler.database.record.SyncConflictRecord
import dev.agenticscheduler.database.record.SyncSpaceCursorRecord

class RoomApplicationTransactionRunner(private val database: AgenticSchedulerDatabase) : ApplicationTransactionRunner {
    override suspend fun <T> inWriteTransaction(block: suspend () -> T): T = database.withWriteTransaction { block() }
}

/** Room implementation of the D7 atomic journal port; callers already own the write transaction. */
class RoomMutationJournalRepository(private val database: AgenticSchedulerDatabase) : MutationJournalRepository, HistoryRepository {
    override suspend fun localReplicaState(): LocalReplicaCausalState? = database.mutationJournalDao().localReplicaState()?.let { record ->
        LocalReplicaCausalState(
            replicaId = ReplicaId(record.replicaId),
            lastCounter = record.lastCounter,
            observedContext = LocalJournalCodec.decodeContext(record.observedContextJson),
            lastHlc = HlcTimestamp(record.lastHlcPhysicalMillis, record.lastHlcLogical, ReplicaId(record.lastHlcReplicaId)),
        )
    }

    override suspend fun saveLocalReplicaState(state: LocalReplicaCausalState) {
        database.mutationJournalDao().saveLocalReplicaState(ReplicaCausalStateRecord(
            replicaId = state.replicaId.value,
            lastCounter = state.lastCounter,
            observedContextJson = LocalJournalCodec.encodeContext(state.observedContext),
            lastHlcPhysicalMillis = state.lastHlc.physicalMillis,
            lastHlcLogical = state.lastHlc.logical,
            lastHlcReplicaId = state.lastHlc.replicaId.value,
        ))
    }

    override suspend fun appendCommittedMutation(mutation: CommittedMutation) {
        val operation = mutation.operation
        database.mutationJournalDao().insertMutationRecord(MutationRecord(
            mutationId = operation.mutationId,
            origin = operation.origin.durableName(),
            dvvJson = LocalJournalCodec.encodeDvv(operation.dvv),
            hlcPhysicalMillis = operation.hlc.physicalMillis,
            hlcLogical = operation.hlc.logical,
            hlcReplicaId = operation.hlc.replicaId,
            committedAtEpochMillis = mutation.committedAtEpochMillis,
        ))
        database.mutationJournalDao().insertChangeLogEntries(operation.orderedMutations.mapIndexed { ordinal, entry ->
            ChangeLogEntryRecord(
                entryId = "${operation.mutationId}:$ordinal",
                mutationId = operation.mutationId,
                ordinal = ordinal,
                entityKind = entry.entityKind.name,
                entityId = entry.entityId,
                operationKind = entry.operationKind(),
                beforeImageJson = LocalJournalCodec.beforeImage(entry),
                afterImageJson = LocalJournalCodec.afterImage(entry),
            )
        })
        database.mutationJournalDao().insertSyncOperation(SyncOperationJournalRecord(operation.mutationId, LocalJournalCodec.version, LocalJournalCodec.encode(operation)))
    }

    override suspend fun advanceFocusBlockTombstones(operation: dev.agenticscheduler.sync.SyncOperation, acceptedDeletes: List<FocusBlockDelete>) {
        acceptedDeletes.forEach { delete ->
            database.mutationJournalDao().upsertFocusBlockTombstone(FocusBlockTombstoneRecord(
                focusBlockId = delete.before.id,
                deletionMutationId = operation.mutationId,
                dvvJson = LocalJournalCodec.encodeDvv(operation.dvv),
                hlcPhysicalMillis = operation.hlc.physicalMillis,
                hlcLogical = operation.hlc.logical,
                hlcReplicaId = operation.hlc.replicaId,
            ))
        }
    }

    override suspend fun timeline(): List<CommittedMutation> = database.mutationJournalDao().timeline().mapNotNull { record -> mutation(record.mutationId) }

    override suspend fun mutation(mutationId: String): CommittedMutation? {
        val record = database.mutationJournalDao().syncOperation(mutationId) ?: return null
        val metadata = database.mutationJournalDao().mutationRecord(mutationId) ?: return null
        return CommittedMutation(LocalJournalCodec.decode(record.operationJson), metadata.committedAtEpochMillis)
    }

    override suspend fun entityChanges(entityKind: dev.agenticscheduler.sync.EntityKind, entityId: String): List<HistoryChange> =
        database.mutationJournalDao().entityEntries(entityKind.name, entityId).map { it.toHistoryChange(requireNotNull(database.mutationJournalDao().mutationRecord(it.mutationId))) }.sortedWith(historyChangeComparator)

    override suspend fun diff(mutationId: String): List<HistoryChange> = database.mutationJournalDao().entries(mutationId).map { it.toHistoryChange(requireNotNull(database.mutationJournalDao().mutationRecord(it.mutationId))) }

    override suspend fun focusBlockTombstone(focusBlockId: String): FocusBlockTombstone? = database.mutationJournalDao().focusBlockTombstone(focusBlockId)?.let { FocusBlockTombstone(it.focusBlockId, MutationId(it.deletionMutationId), LocalJournalCodec.decodeDvv(it.dvvJson)) }
}

/** D8 receive metadata implementation. Callers own the encompassing application transaction. */
class RoomSyncReceiveRepository(private val database: AgenticSchedulerDatabase) : SyncReceiveRepository {
    override suspend fun serverCursor(syncSpaceId: SyncSpaceId): Long =
        database.syncReceiveDao().cursor(syncSpaceId.value)?.serverCursor ?: 0L

    override suspend fun saveServerCursor(syncSpaceId: SyncSpaceId, cursor: Long) {
        require(cursor >= 0)
        database.syncReceiveDao().saveCursor(SyncSpaceCursorRecord(syncSpaceId.value, cursor))
    }

    override suspend fun quarantine(value: ProtocolQuarantine) {
        database.syncReceiveDao().saveQuarantine(ProtocolQuarantineRecord(
            value.syncSpaceId.value, value.mutationId, value.serverCursor, value.reason.name, value.detail,
        ))
    }

    override suspend fun quarantine(syncSpaceId: SyncSpaceId, mutationId: String): ProtocolQuarantine? =
        database.syncReceiveDao().quarantine(syncSpaceId.value, mutationId)?.let { record ->
            ProtocolQuarantine(SyncSpaceId(record.syncSpaceId), record.mutationId, record.serverCursor, ProtocolQuarantineReason.valueOf(record.reason), record.detail)
        }

    override suspend fun saveConflict(value: SyncConflict) {
        database.syncReceiveDao().saveConflict(SyncConflictRecord(
            value.conflictId,
            value.syncSpaceId.value,
            SyncReceiveStateCodec.encodeConflict(value),
        ))
    }

    override suspend fun conflict(conflictId: String): SyncConflict? =
        database.syncReceiveDao().conflict(conflictId)?.let { SyncReceiveStateCodec.decodeConflict(it.conflictJson) }

    override suspend fun conflicts(syncSpaceId: SyncSpaceId): List<SyncConflict> =
        database.syncReceiveDao().conflicts(syncSpaceId.value).map { SyncReceiveStateCodec.decodeConflict(it.conflictJson) }
}

private fun ChangeLogEntryRecord.toHistoryChange(record: MutationRecord) = HistoryChange(mutationId, ordinal, dev.agenticscheduler.sync.EntityKind.valueOf(entityKind), entityId, operationKind, beforeImageJson, afterImageJson, HlcTimestamp(record.hlcPhysicalMillis, record.hlcLogical, ReplicaId(record.hlcReplicaId)))
private val historyChangeComparator = compareBy<HistoryChange>({ it.hlc.physicalMillis }, { it.hlc.logical }, { it.hlc.replicaId.value }, { it.mutationId }, { it.ordinal })

private fun MutationOrigin.durableName(): String = when (this) {
    MutationOrigin.User -> "USER"
    MutationOrigin.Planner -> "PLANNER"
    MutationOrigin.System -> "SYSTEM"
    is MutationOrigin.Undo -> "UNDO:$originalMutationId"
}

class RoomEventRepository(private val database: AgenticSchedulerDatabase) : EventRepository {
    override fun observeAll(): Flow<kotlinx.collections.immutable.ImmutableList<Event>> = database.eventDao().observeAll().map { rows -> rows.map { it.toDomain() }.toImmutableList() }
    override suspend fun get(id: EventId): Event? = database.eventDao().get(id.value)?.toDomain()
    override suspend fun upsert(event: Event) = database.eventDao().upsert(event.toRecord())
}

class RoomTaskRepository(private val database: AgenticSchedulerDatabase) : TaskRepository {
    override fun observeTasks() = database.taskDao().observeAll().map { it.map { row -> row.toDomain() }.toImmutableList() }
    override suspend fun getTask(id: TaskId) = database.taskDao().get(id.value)?.toDomain()
    override suspend fun upsertTask(task: Task) = database.taskDao().upsert(task.toRecord())
    override fun observeFocusBlocks() = database.focusBlockDao().observeAll().map { it.map { row -> row.toDomain() }.toImmutableList() }
    override suspend fun getFocusBlock(id: FocusBlockId) = database.focusBlockDao().get(id.value)?.toDomain()
    override suspend fun upsertFocusBlock(focusBlock: FocusBlock) = database.focusBlockDao().upsert(focusBlock.toRecord())
    override suspend fun deleteFocusBlock(id: FocusBlockId) = database.focusBlockDao().delete(id.value)
    override fun observeWorkLogs() = database.workLogDao().observeAll().map { it.map { row -> row.toDomain() }.toImmutableList() }
    override suspend fun getWorkLog(id: WorkLogId) = database.workLogDao().get(id.value)?.toDomain()
    override suspend fun upsertWorkLog(workLog: WorkLog) = database.workLogDao().upsert(workLog.toRecord())
    override fun observeDependencies() = database.taskDependencyDao().observeAll().map { it.map { row -> row.toDomain() }.toImmutableList() }
    override suspend fun getDependency(id: TaskDependencyId) = database.taskDependencyDao().get(id.value)?.toDomain()
    override suspend fun upsertDependency(dependency: TaskDependency) {
        val record = dependency.toRecord()
        check(database.taskDependencyDao().idForPair(record.prerequisiteTaskId, record.dependentTaskId)?.let { it == record.id } != false) {
            "A TaskDependency pair must be unique."
        }
        database.taskDependencyDao().upsert(record)
    }
}

class RoomPlanningProfileRepository(private val database: AgenticSchedulerDatabase) : PlanningProfileRepository {
    override fun observeAll(): Flow<kotlinx.collections.immutable.ImmutableList<PlanningProfile>> = combine(database.planningProfileDao().observeAll(), database.planningProfileDao().observeAllWindows()) { parents,windows -> parents.map { it.toDomain(windows.filter { window -> window.planningProfileId==it.id }) }.toImmutableList() }
    override suspend fun get(id: PlanningProfileId) = database.planningProfileDao().get(id.value)?.let { it.toDomain(database.planningProfileDao().windows(it.id)) }
    override suspend fun upsert(profile: PlanningProfile) = database.withWriteTransaction { database.planningProfileDao().upsert(profile.toRecord()); database.planningProfileDao().deleteWindows(profile.id.value); database.planningProfileDao().upsertWindows(profile.windowRecords()) }
}

class RoomAcademicRepository(private val database: AgenticSchedulerDatabase) : AcademicRepository {
    override fun observeAcademicYears() = database.academicYearDao().observeAll().map { it.map { row -> row.toDomain() }.toImmutableList() }
    override suspend fun getAcademicYear(id: AcademicYearId) = database.academicYearDao().get(id.value)?.toDomain()
    override suspend fun upsertAcademicYear(value: AcademicYear) = database.academicYearDao().upsert(value.toRecord())

    override fun observeSemesters() = combine(database.semesterDao().observeAll(), database.semesterDao().observeAllWeeks()) { parents, children -> parents.map { parent -> parent.toDomain(children.filter { it.semesterId == parent.id }) }.toImmutableList() }
    override suspend fun getSemester(id: SemesterId) = database.semesterDao().get(id.value)?.let { it.toDomain(database.semesterDao().weeks(it.id)) }
    override suspend fun upsertSemester(value: Semester) = database.withWriteTransaction { database.semesterDao().upsert(value.toRecord()); database.semesterDao().deleteWeeks(value.id.value); database.semesterDao().upsertWeeks(value.weekRecords()) }

    override fun observeCourses() = database.courseDao().observeAll().map { it.map { row -> row.toDomain() }.toImmutableList() }
    override suspend fun getCourse(id: CourseId) = database.courseDao().get(id.value)?.toDomain()
    override suspend fun upsertCourse(value: Course) = database.courseDao().upsert(value.toRecord())

    override fun observePeriodTemplates() = combine(database.periodTemplateDao().observeAll(), database.periodTemplateDao().observeAllPeriods()) { parents, children -> parents.map { parent -> parent.toDomain(children.filter { it.periodTemplateId == parent.id }) }.toImmutableList() }
    override suspend fun getPeriodTemplate(id: PeriodTemplateId) = database.periodTemplateDao().get(id.value)?.let { it.toDomain(database.periodTemplateDao().periods(it.id)) }
    override suspend fun upsertPeriodTemplate(value: PeriodTemplate) = database.withWriteTransaction { database.periodTemplateDao().upsert(value.toRecord()); database.periodTemplateDao().deletePeriods(value.id.value); database.periodTemplateDao().upsertPeriods(value.periodRecords()) }

    override fun observeCourseScheduleRules() = combine(database.courseScheduleRuleDao().observeAll(), database.courseScheduleRuleDao().observeAllWeeks()) { parents, children -> parents.map { parent -> parent.toDomain(children.filter { it.scheduleRuleId == parent.id }) }.toImmutableList() }
    override suspend fun getCourseScheduleRule(id: CourseScheduleRuleId) = database.courseScheduleRuleDao().get(id.value)?.let { it.toDomain(database.courseScheduleRuleDao().weeks(it.id)) }
    override suspend fun upsertCourseScheduleRule(value: CourseScheduleRule) = database.withWriteTransaction { database.courseScheduleRuleDao().upsert(value.toRecord()); database.courseScheduleRuleDao().deleteWeeks(value.id.value); database.courseScheduleRuleDao().upsertWeeks(value.weekRecords()) }

    override fun observeAcademicHolidays() = database.academicHolidayDao().observeAll().map { it.map { row -> row.toDomain() }.toImmutableList() }
    override suspend fun getAcademicHoliday(id: AcademicHolidayId) = database.academicHolidayDao().get(id.value)?.toDomain()
    override suspend fun upsertAcademicHoliday(value: AcademicHoliday) = database.academicHolidayDao().upsert(value.toRecord())
    override fun observeCourseOccurrenceExceptions() = database.courseOccurrenceExceptionDao().observeAll().map { it.map { row -> row.toDomain() }.toImmutableList() }
    override suspend fun getCourseOccurrenceException(id: CourseOccurrenceExceptionId) = database.courseOccurrenceExceptionDao().get(id.value)?.toDomain()
    override suspend fun upsertCourseOccurrenceException(value: CourseOccurrenceException) {
        val record = value.toRecord()
        check(database.courseOccurrenceExceptionDao().idForOccurrence(record.scheduleRuleId, record.academicWeekNumber)?.let { it == record.id } != false) {
            "A CourseOccurrenceException target must be unique."
        }
        database.courseOccurrenceExceptionDao().upsert(record)
    }
    override fun observeExams() = database.examDao().observeAll().map { it.map { row -> row.toDomain() }.toImmutableList() }
    override suspend fun getExam(id: ExamId) = database.examDao().get(id.value)?.toDomain()
    override suspend fun upsertExam(value: Exam) = database.examDao().upsert(value.toRecord())
}
