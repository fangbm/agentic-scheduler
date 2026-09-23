package dev.agenticscheduler.application.history

import dev.agenticscheduler.application.persistence.*
import dev.agenticscheduler.domain.id.*
import dev.agenticscheduler.sync.*

sealed interface SyncConflictResolutionResult {
    data class Resolved(val mutationId: MutationId) : SyncConflictResolutionResult
    data object NotFound : SyncConflictResolutionResult
    data object AlreadyResolved : SyncConflictResolutionResult
    data class Superseded(val replacementConflictId: String) : SyncConflictResolutionResult
    data class InvalidResolution(val reason: String) : SyncConflictResolutionResult
}

/**
 * SYN-015/SYN-015A explicit local resolution path. It emits a
 * CONFLICT_RESOLUTION mutation that causally dominates all candidates, then marks
 * the durable conflict resolved
 * in the same MutationCoordinator transaction.
 */
class SyncConflictResolutionService(
    private val mutations: MutationCoordinator,
    private val history: HistoryRepository,
    private val receiveState: SyncReceiveRepository,
    private val events: EventRepository,
    private val tasks: TaskRepository,
    private val profiles: PlanningProfileRepository,
    private val academics: AcademicRepository,
) {
    suspend fun resolve(conflictId: String, requested: List<EntityMutation>): SyncConflictResolutionResult {
        when (receiveState.conflict(conflictId)?.status) {
            null -> return SyncConflictResolutionResult.NotFound
            SyncConflictStatus.RESOLVED -> return SyncConflictResolutionResult.AlreadyResolved
            SyncConflictStatus.SUPERSEDED -> return SyncConflictResolutionResult.Superseded(requireNotNull(receiveState.conflict(conflictId)?.supersededByConflictId))
            SyncConflictStatus.OPEN -> Unit
        }
        return try {
            val execution = mutations.execute(MutationOrigin.ConflictResolution(conflictId), onCommitted = { committed ->
                val operation = requireNotNull(history.mutation(committed.mutationId.value)).operation
                require(observesAll(operation, committed.value.participants)) { "Resolution MutationId must observe every conflict participant." }
                receiveState.saveConflict(committed.value.copy(status = SyncConflictStatus.RESOLVED, resolutionMutationId = committed.mutationId))
            }) {
                val conflict = requireNotNull(receiveState.conflict(conflictId)) { "SyncConflict no longer exists." }
                require(conflict.status == SyncConflictStatus.OPEN) { "SyncConflict is already resolved." }
                validateTargets(conflict, requested)
                val effective = requested.map { mutation -> currentMutation(mutation)?.let { current -> rebaseOnCurrent(current, mutation) } ?: mutation }
                effective.forEach { mutation -> apply(mutation); record(mutation) }
                conflict
            }
            SyncConflictResolutionResult.Resolved(execution.mutationId)
        } catch (failure: IllegalArgumentException) {
            SyncConflictResolutionResult.InvalidResolution(failure.message ?: "Resolution is invalid.")
        }
    }

    private fun validateTargets(conflict: SyncConflict, requested: List<EntityMutation>) {
        require(requested.isNotEmpty()) { "Resolution must contain at least one typed mutation." }
        val refs = conflict.entityRefs.associateBy { it.entityKind to it.entityId }
        require(requested.map { it.entityKind to it.entityId }.toSet() == refs.keys) { "Resolution must address exactly the conflicted entities." }
        requested.forEach { mutation ->
            val ref = requireNotNull(refs[mutation.entityKind to mutation.entityId])
            require(mutation.changedSemanticGroups().map(SemanticGroupValue::name).all(ref.groups::contains)) { "Resolution changes a group outside the conflict." }
        }
    }

    private suspend fun currentMutation(incoming: EntityMutation): EntityMutation? = when (incoming) {
        is EventPut -> events.get(EventId(incoming.entityId))?.toSemanticImage()?.let { EventPut(null, it) }
        is TaskPut -> tasks.getTask(TaskId(incoming.entityId))?.toSemanticImage()?.let { TaskPut(null, it) }
        is PlanningProfilePut -> profiles.get(PlanningProfileId(incoming.entityId))?.toSemanticImage()?.let { PlanningProfilePut(null, it) }
        is FocusBlockPut -> tasks.getFocusBlock(FocusBlockId(incoming.entityId))?.toSemanticImage()?.let { FocusBlockPut(null, it) }
        is ExamPut -> academics.getExam(ExamId(incoming.entityId))?.toSemanticImage()?.let { ExamPut(null, it) }
        else -> null
    }

    private suspend fun apply(mutation: EntityMutation) {
        when (mutation) {
            is EventPut -> events.upsert(mutation.after.toDomain())
            is TaskPut -> tasks.upsertTask(mutation.after.toDomain())
            is PlanningProfilePut -> profiles.upsert(mutation.after.toDomain())
            is FocusBlockPut -> tasks.upsertFocusBlock(mutation.after.toDomain())
            is FocusBlockDelete -> tasks.deleteFocusBlock(FocusBlockId(mutation.entityId))
            is WorkLogAppend -> {
                val current = tasks.getWorkLog(WorkLogId(mutation.entityId))
                require(current == null || current.toSemanticImage() == mutation.after) { "WorkLog resolution cannot overwrite an append-only ID." }
                if (current == null) tasks.upsertWorkLog(mutation.after.toDomain())
            }
            is TaskDependencyPut -> tasks.upsertDependency(mutation.after.toDomain())
            is AcademicYearPut -> academics.upsertAcademicYear(mutation.after.toDomain())
            is SemesterPut -> academics.upsertSemester(mutation.after.toDomain())
            is CoursePut -> academics.upsertCourse(mutation.after.toDomain())
            is PeriodTemplatePut -> academics.upsertPeriodTemplate(mutation.after.toDomain())
            is AcademicHolidayPut -> academics.upsertAcademicHoliday(mutation.after.toDomain())
            is CourseScheduleRulePut -> academics.upsertCourseScheduleRule(mutation.after.toDomain())
            is CourseOccurrenceExceptionPut -> academics.upsertCourseOccurrenceException(mutation.after.toDomain())
            is ExamPut -> academics.upsertExam(mutation.after.toDomain())
        }
    }

    private fun observesAll(resolution: SyncOperation, participants: List<SyncConflictParticipant>): Boolean {
        val observed = resolution.dvv.toDottedVersionVector().observedContext()
        return participants.all { participant ->
            participant.dvv.toDottedVersionVector().observedContext().all { (replica, counter) -> (observed[replica] ?: -1L) >= counter }
        }
    }
}
