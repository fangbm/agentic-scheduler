package dev.agenticscheduler.application.history

import dev.agenticscheduler.application.persistence.EventRepository
import dev.agenticscheduler.application.persistence.HistoryRepository
import dev.agenticscheduler.application.persistence.PlanningProfileRepository
import dev.agenticscheduler.application.persistence.TaskRepository
import dev.agenticscheduler.domain.id.FocusBlockId
import dev.agenticscheduler.sync.EntityMutation
import dev.agenticscheduler.sync.EventPut
import dev.agenticscheduler.sync.FocusBlockDelete
import dev.agenticscheduler.sync.FocusBlockPut
import dev.agenticscheduler.sync.MutationId
import dev.agenticscheduler.sync.MutationOrigin
import dev.agenticscheduler.sync.PlanningProfilePut
import dev.agenticscheduler.sync.TaskPut
import dev.agenticscheduler.sync.operationKind

sealed interface UndoResult {
    data class Applied(val mutationId: MutationId) : UndoResult
    data class Unsupported(val reason: String) : UndoResult
    data class Conflict(val entityIds: List<String>) : UndoResult
    data class BlockedBySyncConflict(val blocks: List<SyncConflictWriteBlock>) : UndoResult
    data object NotFound : UndoResult
}

sealed interface UndoCapability {
    data object Available : UndoCapability
    data class Unsupported(val reason: String) : UndoCapability
    data object NotFound : UndoCapability
}

/** HST-005 compensating writes. Preconditions and all children execute inside one new coordinator transaction. */
class UndoService(
    private val coordinator: MutationCoordinator,
    private val history: HistoryRepository,
    private val events: EventRepository,
    private val tasks: TaskRepository,
    private val profiles: PlanningProfileRepository,
    private val conflictWritePolicy: SyncConflictWritePolicy,
) {
    suspend fun canUndo(originalMutationId: String): UndoCapability {
        val original = history.mutation(originalMutationId) ?: return UndoCapability.NotFound
        return unsupportedReason(original.operation.orderedMutations)?.let(UndoCapability::Unsupported) ?: UndoCapability.Available
    }

    suspend fun undo(originalMutationId: String): UndoResult {
        var noMutation: UndoResult? = null
        val execution = coordinator.executeIfAny(MutationOrigin.Undo(originalMutationId)) {
            val original = history.mutation(originalMutationId)
            if (original == null) {
                noMutation = UndoResult.NotFound
                return@executeIfAny UndoResult.NotFound
            }
            unsupportedReason(original.operation.orderedMutations)?.let { reason ->
                noMutation = UndoResult.Unsupported(reason)
                return@executeIfAny noMutation!!
            }
            val actions = mutableListOf<InverseAction>()
            val conflicts = mutableListOf<String>()
            original.operation.orderedMutations.forEach { mutation ->
                when (mutation) {
                    is EventPut -> {
                        val before = requireNotNull(mutation.before)
                        if (events.get(before.toDomain().id)?.toSemanticImage() != mutation.after) conflicts += mutation.entityId
                        else actions += InverseAction.EventRestore(mutation.after, before)
                    }
                    is TaskPut -> {
                        val before = requireNotNull(mutation.before)
                        if (tasks.getTask(before.toDomain().id)?.toSemanticImage() != mutation.after) conflicts += mutation.entityId
                        else actions += InverseAction.TaskRestore(mutation.after, before)
                    }
                    is PlanningProfilePut -> {
                        val before = requireNotNull(mutation.before)
                        if (profiles.get(before.toDomain().id)?.toSemanticImage() != mutation.after) conflicts += mutation.entityId
                        else actions += InverseAction.ProfileRestore(mutation.after, before)
                    }
                    is FocusBlockPut -> {
                        val current = tasks.getFocusBlock(FocusBlockId(mutation.entityId))?.toSemanticImage()
                        if (current != mutation.after) conflicts += mutation.entityId
                        else actions += mutation.before?.let { before -> InverseAction.FocusRestore(mutation.after, before) } ?: InverseAction.FocusDelete(mutation.after)
                    }
                    is FocusBlockDelete -> {
                        val tombstone = history.focusBlockTombstone(mutation.entityId)
                        if (tasks.getFocusBlock(FocusBlockId(mutation.entityId)) != null || tombstone?.deletionMutationId?.value != originalMutationId) conflicts += mutation.entityId
                        else actions += InverseAction.FocusRestore(null, mutation.before)
                    }
                    else -> error("unsupportedReason must have rejected ${mutation.operationKind()}")
                }
            }
            if (conflicts.isNotEmpty()) {
                noMutation = UndoResult.Conflict(conflicts.distinct().sorted())
                return@executeIfAny noMutation!!
            }
            val blocked = conflictWritePolicy.blocks(actions.map { it.toMutation() })
            if (blocked.isNotEmpty()) {
                noMutation = UndoResult.BlockedBySyncConflict(blocked)
                return@executeIfAny noMutation!!
            }
            actions.forEach { action ->
                when (action) {
                    is InverseAction.EventRestore -> { val value = action.after.toDomain(); events.upsert(value); record(EventPut(action.before, value.toSemanticImage())) }
                    is InverseAction.TaskRestore -> { val value = action.after.toDomain(); tasks.upsertTask(value); record(TaskPut(action.before, value.toSemanticImage())) }
                    is InverseAction.ProfileRestore -> { val value = action.after.toDomain(); profiles.upsert(value); record(PlanningProfilePut(action.before, value.toSemanticImage())) }
                    is InverseAction.FocusRestore -> { val value = action.after.toDomain(); tasks.upsertFocusBlock(value); record(FocusBlockPut(action.before, value.toSemanticImage())) }
                    is InverseAction.FocusDelete -> { tasks.deleteFocusBlock(FocusBlockId(action.value.id)); record(FocusBlockDelete(action.value)) }
                }
            }
            UndoResult.Applied(MutationId("00000000-0000-7000-8000-000000000000"))
        }
        return execution?.let { UndoResult.Applied(it.mutationId) } ?: requireNotNull(noMutation)
    }

    private fun unsupportedReason(mutations: List<EntityMutation>): String? = mutations.firstNotNullOfOrNull { mutation -> when (mutation) {
        is EventPut -> if (mutation.before == null) "Event creation has no D7 delete contract." else null
        is TaskPut -> if (mutation.before == null) "Task creation has no D7 delete contract." else null
        is PlanningProfilePut -> if (mutation.before == null) "PlanningProfile creation has no D7 delete contract." else null
        is FocusBlockPut, is FocusBlockDelete -> null
        else -> "${mutation.operationKind()} is unsupported by the D7 Undo matrix."
    } }
}

private sealed interface InverseAction {
    data class EventRestore(val before: dev.agenticscheduler.sync.EventImage, val after: dev.agenticscheduler.sync.EventImage) : InverseAction
    data class TaskRestore(val before: dev.agenticscheduler.sync.TaskImage, val after: dev.agenticscheduler.sync.TaskImage) : InverseAction
    data class ProfileRestore(val before: dev.agenticscheduler.sync.PlanningProfileImage, val after: dev.agenticscheduler.sync.PlanningProfileImage) : InverseAction
    data class FocusRestore(val before: dev.agenticscheduler.sync.FocusBlockImage?, val after: dev.agenticscheduler.sync.FocusBlockImage) : InverseAction
    data class FocusDelete(val value: dev.agenticscheduler.sync.FocusBlockImage) : InverseAction
}

private fun InverseAction.toMutation(): EntityMutation = when (this) {
    is InverseAction.EventRestore -> EventPut(before, after)
    is InverseAction.TaskRestore -> TaskPut(before, after)
    is InverseAction.ProfileRestore -> PlanningProfilePut(before, after)
    is InverseAction.FocusRestore -> FocusBlockPut(before, after)
    is InverseAction.FocusDelete -> FocusBlockDelete(value)
}
