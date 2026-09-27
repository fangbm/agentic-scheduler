package dev.agenticscheduler.agent.tool

import dev.agenticscheduler.agent.permission.AgentPermissionDecision
import dev.agenticscheduler.agent.permission.AgentPermissionEngine
import dev.agenticscheduler.agent.permission.AgentPermissionPolicy
import dev.agenticscheduler.agent.permission.AgentToolCapability
import dev.agenticscheduler.application.history.HistoryQueryService
import dev.agenticscheduler.application.history.MutationExecution
import dev.agenticscheduler.application.history.UndoCapability
import dev.agenticscheduler.application.history.UndoResult
import dev.agenticscheduler.application.history.UndoService
import dev.agenticscheduler.application.persistence.HistoryChange
import dev.agenticscheduler.sync.MutationId
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
data class HistoryUndoToolInput(val mutationId: String)

/** A user-readable, immutable summary of the history group that will be compensated. */
@Serializable
data class HistoryUndoPreview(
    val originalMutationId: String,
    val changes: List<HistoryUndoPreviewChange>,
)

@Serializable
data class HistoryUndoPreviewChange(
    val entityKind: String,
    val entityId: String,
    val operationKind: String,
    val beforeImageJson: String?,
    val afterImageJson: String?,
)

data class CommittedHistoryUndo(val originalMutationId: String, val mutationId: MutationId)

/** Narrow application facade: production delegates to the existing D7 Undo and history query services. */
interface HistoryUndoApplication {
    suspend fun canUndo(mutationId: String): UndoCapability
    suspend fun diff(mutationId: String): List<HistoryChange>
    suspend fun undo(
        mutationId: String,
        onCommitted: suspend (MutationExecution<UndoResult>) -> Unit = {},
    ): UndoResult
}

class D7HistoryUndoApplication(
    private val undoService: UndoService,
    private val history: HistoryQueryService,
) : HistoryUndoApplication {
    override suspend fun canUndo(mutationId: String): UndoCapability = undoService.canUndo(mutationId)
    override suspend fun diff(mutationId: String): List<HistoryChange> = history.getDiff(mutationId)
    override suspend fun undo(
        mutationId: String,
        onCommitted: suspend (MutationExecution<UndoResult>) -> Unit,
    ): UndoResult = undoService.undo(mutationId, onCommitted)
}

/**
 * Confirmable D9 wrapper around D7 compensation. The tool has no repository or transaction
 * capability; the application UndoService rechecks all inverse preconditions atomically.
 */
class HistoryUndoTool(
    private val application: HistoryUndoApplication,
    private val permissions: AgentPermissionEngine = AgentPermissionEngine(),
) {
    val metadata = AgentToolMetadata(AgentToolNames.HISTORY_UNDO, AgentToolCapability.UNDO, AgentToolAccess.WRITE)
    private val json = Json { ignoreUnknownKeys = false; explicitNulls = true }

    suspend fun prepare(argumentsJson: String, policy: AgentPermissionPolicy): AgentToolOutcome<HistoryUndoPreview> {
        val id = decodeMutationId(argumentsJson) ?: return invalidInput()
        return try {
            val preview = when (val capability = application.canUndo(id.value)) {
                UndoCapability.NotFound -> return AgentToolOutcome.NotFound
                is UndoCapability.Unsupported -> return AgentToolOutcome.Unsupported(reasonCode(capability.reason))
                UndoCapability.Available -> HistoryUndoPreview(
                    originalMutationId = id.value,
                    changes = application.diff(id.value).map { it.toUndoPreviewChange() },
                )
            }
            when (permissions.evaluate(policy, metadata.capability)) {
                AgentPermissionDecision.AllowDirect -> AgentToolOutcome.Success(preview)
                AgentPermissionDecision.RequireConfirmation -> AgentToolOutcome.ConfirmationRequired(preview)
                AgentPermissionDecision.Deny -> AgentToolOutcome.PermissionDenied(metadata.capability)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            AgentToolOutcome.InfrastructureFailure("UNDO_PREVIEW")
        }
    }

    suspend fun commit(
        argumentsJson: String,
        shownPreview: HistoryUndoPreview,
        userConfirmed: Boolean,
        policy: AgentPermissionPolicy,
        onCommitted: suspend (MutationExecution<UndoResult>) -> Unit = {},
    ): AgentToolOutcome<CommittedHistoryUndo> {
        val prepared = prepare(argumentsJson, policy)
        val current = when (prepared) {
            is AgentToolOutcome.Success -> prepared.payload
            is AgentToolOutcome.ConfirmationRequired -> prepared.preview
            is AgentToolOutcome.InvalidInput -> return prepared
            AgentToolOutcome.NotFound -> return AgentToolOutcome.NotFound
            is AgentToolOutcome.Unsupported -> return prepared
            is AgentToolOutcome.PermissionDenied -> return prepared
            is AgentToolOutcome.InfrastructureFailure -> return prepared
            else -> return AgentToolOutcome.InfrastructureFailure("UNDO_PREVIEW")
        }
        if (current != shownPreview) return AgentToolOutcome.Stale
        if (prepared is AgentToolOutcome.ConfirmationRequired && !userConfirmed) {
            return AgentToolOutcome.PermissionDenied(metadata.capability)
        }

        return try {
            when (val result = application.undo(current.originalMutationId, onCommitted)) {
                is UndoResult.Applied -> AgentToolOutcome.Success(CommittedHistoryUndo(current.originalMutationId, result.mutationId))
                is UndoResult.Unsupported -> AgentToolOutcome.Unsupported(reasonCode(result.reason))
                is UndoResult.Conflict -> AgentToolOutcome.Conflict(result.entityIds)
                is UndoResult.BlockedBySyncConflict -> AgentToolOutcome.Conflict(result.blocks.map { it.conflictId }.distinct().sorted())
                UndoResult.NotFound -> AgentToolOutcome.NotFound
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            AgentToolOutcome.InfrastructureFailure("UNDO_TRANSACTION")
        }
    }

    fun normalizedPreviewJson(preview: HistoryUndoPreview): String = json.encodeToString(
        HistoryUndoPreview.serializer(), preview,
    )

    private fun decodeMutationId(argumentsJson: String): MutationId? {
        val input = try {
            json.decodeFromString(HistoryUndoToolInput.serializer(), argumentsJson)
        } catch (_: SerializationException) {
            return null
        } catch (_: IllegalArgumentException) {
            return null
        }
        return try { MutationId(input.mutationId) } catch (_: IllegalArgumentException) { null }
    }

    private fun invalidInput() = AgentToolOutcome.InvalidInput(
        listOf(AgentToolInputIssue("mutationId", "INVALID_ID")),
    )

    private fun reasonCode(reason: String): String = when {
        "Event creation" in reason -> "EVENT_CREATE_UNSUPPORTED"
        "Task creation" in reason -> "TASK_CREATE_UNSUPPORTED"
        "PlanningProfile creation" in reason -> "PROFILE_CREATE_UNSUPPORTED"
        "unsupported" in reason.lowercase() -> "MUTATION_KIND_UNSUPPORTED"
        else -> "UNDO_UNSUPPORTED"
    }

    private fun HistoryChange.toUndoPreviewChange() = HistoryUndoPreviewChange(
        entityKind = entityKind.name,
        entityId = entityId,
        operationKind = operationKind,
        beforeImageJson = beforeImageJson,
        afterImageJson = afterImageJson,
    )
}
