package dev.agenticscheduler.agent.tool

import dev.agenticscheduler.agent.history.AgentActionId
import dev.agenticscheduler.agent.permission.AgentPermissionDecision
import dev.agenticscheduler.agent.permission.AgentPermissionEngine
import dev.agenticscheduler.agent.permission.AgentPermissionPolicy
import dev.agenticscheduler.agent.permission.AgentToolCapability
import dev.agenticscheduler.application.editing.EditingResult
import dev.agenticscheduler.application.editing.TaskEditingService
import dev.agenticscheduler.application.editing.TaskUpdatePreview
import dev.agenticscheduler.application.editing.UpdateTaskInput
import dev.agenticscheduler.application.history.AgentOriginWriteNotAllowed
import dev.agenticscheduler.application.history.MutationExecution
import dev.agenticscheduler.domain.id.TaskId
import dev.agenticscheduler.domain.task.Task
import dev.agenticscheduler.domain.task.TaskPriority
import dev.agenticscheduler.domain.task.TaskStatus
import dev.agenticscheduler.sync.MutationId
import dev.agenticscheduler.sync.MutationOrigin
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.time.Duration.Companion.minutes

@Serializable
data class TaskUpdateToolInput(
    val taskId: String,
    val title: String,
    val status: String,
    val priority: String,
    val estimatedMinutes: Long?,
    val completedMinutes: Long,
    val remainingMinutes: Long?,
    val deadline: TaskDeadlineToolInput?,
)

data class CommittedTaskUpdate(val task: Task, val mutationId: MutationId)

/** Typed Task update with a transaction-time before-image check. */
class TaskUpdateTool(
    private val editing: TaskEditingService,
    private val permissions: AgentPermissionEngine = AgentPermissionEngine(),
) {
    val metadata = AgentToolMetadata(AgentToolNames.TASK_UPDATE, AgentToolCapability.SOURCE_FACT_UPDATE, AgentToolAccess.WRITE)
    private val json = Json { ignoreUnknownKeys = false; explicitNulls = true }

    suspend fun prepare(argumentsJson: String, policy: AgentPermissionPolicy): AgentToolOutcome<TaskUpdatePreview> {
        val input = when (val decoded = decode(argumentsJson)) {
            is Decoded.Invalid -> return decoded.outcome
            is Decoded.Valid -> decoded.input
        }
        val preview = when (val checked = editing.previewUpdate(input)) {
            is EditingResult.Success -> checked.value
            is EditingResult.Invalid -> return AgentToolOutcome.InvalidInput(checked.issues.map { it.toToolIssue() })
            EditingResult.NotFound -> return AgentToolOutcome.NotFound
            else -> return AgentToolOutcome.InfrastructureFailure("UNEXPECTED_PREVIEW_RESULT")
        }
        return when (permissions.evaluate(policy, metadata.capability)) {
            AgentPermissionDecision.AllowDirect -> AgentToolOutcome.Success(preview)
            AgentPermissionDecision.RequireConfirmation -> AgentToolOutcome.ConfirmationRequired(preview)
            AgentPermissionDecision.Deny -> AgentToolOutcome.PermissionDenied(metadata.capability)
        }
    }

    suspend fun commit(
        argumentsJson: String,
        shownPreview: TaskUpdatePreview,
        userConfirmed: Boolean,
        policy: AgentPermissionPolicy,
        agentActionId: AgentActionId,
        onCommitted: suspend (MutationExecution<EditingResult<Task>>) -> Unit = {},
    ): AgentToolOutcome<CommittedTaskUpdate> {
        val prepared = prepare(argumentsJson, policy)
        val current = when (prepared) {
            is AgentToolOutcome.Success -> prepared.payload
            is AgentToolOutcome.ConfirmationRequired -> prepared.preview
            is AgentToolOutcome.InvalidInput -> return prepared
            is AgentToolOutcome.PermissionDenied -> return prepared
            AgentToolOutcome.NotFound -> return AgentToolOutcome.NotFound
            else -> return AgentToolOutcome.InfrastructureFailure("UNEXPECTED_PREVIEW_RESULT")
        }
        if (current != shownPreview) return AgentToolOutcome.Stale
        if (prepared is AgentToolOutcome.ConfirmationRequired && !userConfirmed) return AgentToolOutcome.PermissionDenied(metadata.capability)
        val input = (decode(argumentsJson) as Decoded.Valid).input
        return try {
            when (val result = editing.update(input, MutationOrigin.Agent(agentActionId.value), onCommitted, expectedBefore = shownPreview.before)) {
                is EditingResult.Success -> AgentToolOutcome.Success(CommittedTaskUpdate(result.value, requireNotNull(result.mutationId)))
                is EditingResult.Invalid -> AgentToolOutcome.InvalidInput(result.issues.map { it.toToolIssue() })
                EditingResult.NotFound -> AgentToolOutcome.NotFound
                EditingResult.Stale -> AgentToolOutcome.Stale
                is EditingResult.BlockedBySyncConflict -> AgentToolOutcome.Conflict(result.blocks.map { it.conflictId })
            }
        } catch (_: AgentOriginWriteNotAllowed) {
            AgentToolOutcome.PermissionDenied(metadata.capability)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            AgentToolOutcome.InfrastructureFailure("TASK_UPDATE_TRANSACTION")
        }
    }

    fun normalizedPreviewJson(preview: TaskUpdatePreview): String = json.encodeToString(PreviewSnapshot(
        before = preview.before.toSnapshot(), after = preview.after.toSnapshot(),
    ))

    private fun decode(argumentsJson: String): Decoded {
        val value = try { json.decodeFromString(TaskUpdateToolInput.serializer(), argumentsJson) }
        catch (_: SerializationException) { return Decoded.Invalid(AgentToolOutcome.InvalidInput(listOf(AgentToolInputIssue("arguments", "INVALID_JSON")))) }
        catch (_: IllegalArgumentException) { return Decoded.Invalid(AgentToolOutcome.InvalidInput(listOf(AgentToolInputIssue("arguments", "INVALID_JSON")))) }
        val issues = mutableListOf<AgentToolInputIssue>()
        val id = runCatching { TaskId(value.taskId) }.getOrNull()
            ?: run { issues += AgentToolInputIssue("taskId", "INVALID_ID"); null }
        val status = TaskStatus.entries.firstOrNull { it.name == value.status }
            ?: run { issues += AgentToolInputIssue("status", "UNKNOWN_VALUE"); null }
        val priority = TaskPriority.entries.firstOrNull { it.name == value.priority }
            ?: run { issues += AgentToolInputIssue("priority", "UNKNOWN_VALUE"); null }
        if (value.estimatedMinutes != null && value.estimatedMinutes < 0) issues += AgentToolInputIssue("estimatedMinutes", "NEGATIVE")
        if (value.completedMinutes < 0) issues += AgentToolInputIssue("completedMinutes", "NEGATIVE")
        if (value.remainingMinutes != null && value.remainingMinutes < 0) issues += AgentToolInputIssue("remainingMinutes", "NEGATIVE")
        val deadline = decodeTaskDeadline(value.deadline, issues)
        if (issues.isNotEmpty()) return Decoded.Invalid(AgentToolOutcome.InvalidInput(issues))
        return Decoded.Valid(UpdateTaskInput(requireNotNull(id), value.title, requireNotNull(status), requireNotNull(priority),
            value.estimatedMinutes?.minutes, value.completedMinutes.minutes, value.remainingMinutes?.minutes, deadline))
    }

    private fun Task.toSnapshot() = TaskSnapshot(
        id.value, title, status.name, priority.name,
        effort.estimated?.toIsoString(), effort.completed.toIsoString(), effort.remaining?.toIsoString(),
        deadline?.toString(),
    )

    private sealed interface Decoded {
        data class Valid(val input: UpdateTaskInput) : Decoded
        data class Invalid(val outcome: AgentToolOutcome.InvalidInput) : Decoded
    }
    @Serializable private data class PreviewSnapshot(val before: TaskSnapshot, val after: TaskSnapshot)
    @Serializable private data class TaskSnapshot(
        val id: String, val title: String, val status: String, val priority: String,
        val estimated: String?, val completed: String, val remaining: String?, val deadline: String?,
    )
}
