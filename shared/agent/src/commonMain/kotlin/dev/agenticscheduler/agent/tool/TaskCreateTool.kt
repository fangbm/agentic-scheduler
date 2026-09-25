package dev.agenticscheduler.agent.tool

import dev.agenticscheduler.agent.history.AgentActionId
import dev.agenticscheduler.agent.permission.AgentPermissionDecision
import dev.agenticscheduler.agent.permission.AgentPermissionEngine
import dev.agenticscheduler.agent.permission.AgentPermissionPolicy
import dev.agenticscheduler.agent.permission.AgentToolCapability
import dev.agenticscheduler.application.editing.CreateTaskInput
import dev.agenticscheduler.application.editing.EditingIssue
import dev.agenticscheduler.application.editing.EditingResult
import dev.agenticscheduler.application.editing.TaskCreatePreview
import dev.agenticscheduler.application.editing.TaskDeadlineInput
import dev.agenticscheduler.application.editing.TaskEditingService
import dev.agenticscheduler.application.history.AgentOriginWriteNotAllowed
import dev.agenticscheduler.application.history.MutationExecution
import dev.agenticscheduler.domain.planning.DeadlinePolicy
import dev.agenticscheduler.domain.planning.Deadline
import dev.agenticscheduler.domain.planning.OverflowPolicy
import dev.agenticscheduler.domain.task.Task
import dev.agenticscheduler.domain.task.TaskPriority
import dev.agenticscheduler.sync.MutationId
import dev.agenticscheduler.sync.MutationOrigin
import kotlinx.coroutines.CancellationException
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString
import kotlin.time.Duration.Companion.minutes

/** Nullable values must still be explicitly present in the model's JSON. */
@Serializable
data class TaskCreateToolInput(
    val title: String,
    val priority: String,
    val estimatedMinutes: Long?,
    val remainingMinutes: Long?,
    val deadline: TaskDeadlineToolInput?,
)

@Serializable
data class TaskDeadlineToolInput(
    val kind: String,
    val policy: String,
    val overflowPolicy: String,
    val date: String? = null,
    val at: String? = null,
    val timeZone: String? = null,
)

data class TaskCreateWritePreview(val before: Task? = null, val after: TaskCreatePreview)
data class CommittedTaskCreate(val task: Task, val mutationId: MutationId)

/** Typed, confirmable task creation. The model never gets a repository or policy setter. */
class TaskCreateTool(
    private val editing: TaskEditingService,
    private val permissions: AgentPermissionEngine = AgentPermissionEngine(),
) {
    val metadata = AgentToolMetadata(AgentToolNames.TASK_CREATE, AgentToolCapability.LOW_RISK_CREATE, AgentToolAccess.WRITE)
    private val json = Json { ignoreUnknownKeys = false; explicitNulls = true }

    fun normalizedPreviewJson(preview: TaskCreateWritePreview): String {
        val after = preview.after
        val deadline = after.deadline?.let { value -> when (val date = value.deadline) {
            is Deadline.DateOnly -> "DATE_ONLY:${date.date}:${value.policy}:${value.overflowPolicy}"
            is Deadline.Exact -> "EXACT:${date.at}:${date.timeZone.id}:${value.policy}:${value.overflowPolicy}"
        } }
        return json.encodeToString(PreviewSnapshot(
            before = null, title = after.title, priority = after.priority.name,
            estimatedMinutes = after.effort.estimated?.inWholeMinutes,
            remainingMinutes = after.effort.remaining?.inWholeMinutes,
            deadline = deadline,
        ))
    }

    suspend fun prepare(argumentsJson: String, policy: AgentPermissionPolicy): AgentToolOutcome<TaskCreateWritePreview> {
        val input = when (val decoded = decode(argumentsJson)) {
            is Decoded.Invalid -> return decoded.outcome
            is Decoded.Valid -> decoded.value
        }
        val preview = when (val checked = editing.previewCreate(input)) {
            is EditingResult.Invalid -> return invalid(checked.issues.map { it.toToolIssue() })
            is EditingResult.Success -> TaskCreateWritePreview(after = checked.value)
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
        shownPreview: TaskCreateWritePreview,
        userConfirmed: Boolean,
        policy: AgentPermissionPolicy,
        agentActionId: AgentActionId,
        onCommitted: suspend (MutationExecution<EditingResult<Task>>) -> Unit = {},
    ): AgentToolOutcome<CommittedTaskCreate> {
        val prepared = prepare(argumentsJson, policy)
        val currentPreview = when (prepared) {
            is AgentToolOutcome.Success -> prepared.payload
            is AgentToolOutcome.ConfirmationRequired -> prepared.preview
            is AgentToolOutcome.InvalidInput -> return prepared
            is AgentToolOutcome.PermissionDenied -> return prepared
            else -> return AgentToolOutcome.InfrastructureFailure("UNEXPECTED_PREVIEW_RESULT")
        }
        if (currentPreview != shownPreview) return AgentToolOutcome.Stale
        if (prepared is AgentToolOutcome.ConfirmationRequired && !userConfirmed) return AgentToolOutcome.PermissionDenied(metadata.capability)
        val input = (decode(argumentsJson) as Decoded.Valid).value
        return try {
            when (val result = editing.create(input, MutationOrigin.Agent(agentActionId.value), onCommitted)) {
                is EditingResult.Success -> AgentToolOutcome.Success(CommittedTaskCreate(result.value, requireNotNull(result.mutationId)))
                is EditingResult.Invalid -> invalid(result.issues.map { it.toToolIssue() })
                is EditingResult.BlockedBySyncConflict -> AgentToolOutcome.Conflict(result.blocks.map { it.conflictId })
                EditingResult.NotFound -> AgentToolOutcome.InfrastructureFailure("UNEXPECTED_NOT_FOUND")
            }
        } catch (_: AgentOriginWriteNotAllowed) {
            AgentToolOutcome.PermissionDenied(metadata.capability)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            AgentToolOutcome.InfrastructureFailure("TASK_CREATE_TRANSACTION")
        }
    }

    private fun decode(argumentsJson: String): Decoded {
        val value = try { json.decodeFromString(TaskCreateToolInput.serializer(), argumentsJson) }
        catch (_: SerializationException) { return Decoded.Invalid(invalid(listOf(AgentToolInputIssue("arguments", "INVALID_JSON")))) }
        catch (_: IllegalArgumentException) { return Decoded.Invalid(invalid(listOf(AgentToolInputIssue("arguments", "INVALID_JSON")))) }
        val issues = mutableListOf<AgentToolInputIssue>()
        val priority = TaskPriority.entries.firstOrNull { it.name == value.priority }
            ?: run { issues += AgentToolInputIssue("priority", "UNKNOWN_VALUE"); null }
        if (value.estimatedMinutes != null && value.estimatedMinutes < 0) issues += AgentToolInputIssue("estimatedMinutes", "NEGATIVE")
        if (value.remainingMinutes != null && value.remainingMinutes < 0) issues += AgentToolInputIssue("remainingMinutes", "NEGATIVE")
        val deadline = value.deadline?.let { value ->
            val policy = DeadlinePolicy.entries.firstOrNull { it.name == value.policy }
                ?: run { issues += AgentToolInputIssue("deadline.policy", "UNKNOWN_VALUE"); null }
            val overflow = OverflowPolicy.entries.firstOrNull { it.name == value.overflowPolicy }
                ?: run { issues += AgentToolInputIssue("deadline.overflowPolicy", "UNKNOWN_VALUE"); null }
            if (policy == null || overflow == null) null else try {
                when (value.kind) {
                    "DATE_ONLY" -> TaskDeadlineInput.DateOnly(LocalDate.parse(requireNotNull(value.date)), policy, overflow)
                    "EXACT" -> TaskDeadlineInput.Exact(LocalDateTime.parse(requireNotNull(value.at)), TimeZone.of(requireNotNull(value.timeZone)), policy, overflow)
                    else -> { issues += AgentToolInputIssue("deadline.kind", "UNKNOWN_VALUE"); null }
                }
            } catch (_: IllegalArgumentException) {
                issues += AgentToolInputIssue("deadline", "INVALID_TIME")
                null
            }
        }
        if (issues.isNotEmpty()) return Decoded.Invalid(invalid(issues))
        return Decoded.Valid(CreateTaskInput(
            value.title, requireNotNull(priority), value.estimatedMinutes?.minutes,
            value.remainingMinutes?.minutes, deadline,
        ))
    }

    private fun invalid(issues: List<AgentToolInputIssue>) = AgentToolOutcome.InvalidInput(issues)
    private fun EditingIssue.toToolIssue(): AgentToolInputIssue = when (this) {
        EditingIssue.BlankTitle -> AgentToolInputIssue("title", "BLANK")
        EditingIssue.MissingEventTime -> AgentToolInputIssue("deadline", "MISSING_TIME")
        EditingIssue.InvalidTimeRange -> AgentToolInputIssue("deadline", "INVALID_TIME_RANGE")
        is EditingIssue.ZonedTimeTransitionRejected -> AgentToolInputIssue("deadline", "ZONED_TIME_TRANSITION")
        is EditingIssue.InvalidEffort -> AgentToolInputIssue(field.name.lowercase(), "INVALID_EFFORT")
    }

    private sealed interface Decoded {
        data class Valid(val value: CreateTaskInput) : Decoded
        data class Invalid(val outcome: AgentToolOutcome.InvalidInput) : Decoded
    }

    @Serializable private data class PreviewSnapshot(
        val before: String?,
        val title: String,
        val priority: String,
        val estimatedMinutes: Long?,
        val remainingMinutes: Long?,
        val deadline: String?,
    )
}
