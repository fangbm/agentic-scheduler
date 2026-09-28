package dev.agenticscheduler.agent.runtime

import dev.agenticscheduler.agent.context.ContextAssembler
import dev.agenticscheduler.agent.context.CompactionPressure
import dev.agenticscheduler.agent.context.ContextCompactionResult
import dev.agenticscheduler.agent.context.ContextCompactionService
import dev.agenticscheduler.agent.context.ContextCandidate
import dev.agenticscheduler.agent.context.ContextClass
import dev.agenticscheduler.agent.context.ContextRequest
import dev.agenticscheduler.agent.context.ContextAssemblyResult
import dev.agenticscheduler.agent.context.ContextSummaryProvider
import dev.agenticscheduler.agent.context.Utf8ByteBudgetMeter
import dev.agenticscheduler.agent.history.*
import dev.agenticscheduler.agent.permission.AgentPermissionMode
import dev.agenticscheduler.agent.tool.*
import dev.agenticscheduler.agent.provider.*
import dev.agenticscheduler.application.id.UuidV7Generator
import dev.agenticscheduler.application.id.EpochMillisecondsClock
import dev.agenticscheduler.application.editing.EditingResult
import dev.agenticscheduler.application.calendar.CalendarItem
import dev.agenticscheduler.application.calendar.CalendarProjectionIssue
import dev.agenticscheduler.application.calendar.CalendarProjectionResult
import dev.agenticscheduler.application.calendar.CalendarSourceRef
import dev.agenticscheduler.application.calendar.CalendarViewport
import dev.agenticscheduler.application.persistence.HistoryChange
import dev.agenticscheduler.domain.id.TaskId
import dev.agenticscheduler.domain.planning.Deadline
import dev.agenticscheduler.domain.task.Task
import dev.agenticscheduler.domain.task.TaskStatus
import dev.agenticscheduler.sync.SyncOperation
import dev.agenticscheduler.sync.EntityKind
import dev.agenticscheduler.sync.MutationId
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

fun interface AgentClock { fun nowEpochMillis(): Long }

sealed interface AgentRunResult {
    data class Completed(val assistantText: String) : AgentRunResult
    data class AwaitingConfirmation(val callId: AgentToolCallId, val previewJson: String) : AgentRunResult
    data class Failed(val redactedCode: String) : AgentRunResult
}

/** One bounded provider turn: at most one local read, then a response or write proposal. */
class AgentRunService(
    private val state: AgentStateRepository,
    private val provider: OpenAiCompatibleProvider,
    private val taskGet: TaskGetTool,
    private val taskCreate: TaskCreateTool,
    private val ids: UuidV7Generator,
    private val clock: AgentClock,
    private val taskList: TaskListTool? = null,
    private val historyReads: HistoryReadTools? = null,
    private val taskUpdate: TaskUpdateTool? = null,
    private val calendarList: CalendarListTool? = null,
    private val eventCreate: EventCreateTool? = null,
    private val eventUpdate: EventUpdateTool? = null,
    private val plannerFullReplan: PlannerPreviewFullReplanTool? = null,
    private val plannerLocalReflow: PlannerPreviewLocalReflowTool? = null,
    private val historyUndo: HistoryUndoTool? = null,
    private val planningProfileUpdate: PlanningProfileUpdateTool? = null,
    private val plannerApplyBranch: PlannerApplyBranchTool? = null,
) {
    private val json = Json { encodeDefaults = true; explicitNulls = true }
    private val threadOperationLocks = ThreadOperationLocks()
    private val transcripts = AgentTranscriptAssembler(state)
    /** Planner branches are session-local proposals; only their IDs/summaries enter the Agent audit. */
    private val planBranches = mutableMapOf<Pair<AgentThreadId, String>, dev.agenticscheduler.application.planner.PlanBranch>()
    private val system = "Use only listed typed tools for reads and writes. Prose is not a mutation. Current tool results outrank summaries. Never claim a write succeeded without a successful tool result."
    private val taskGetSchema = schema("""{"type":"object","properties":{"taskId":{"type":"string"}},"required":["taskId"],"additionalProperties":false}""")
    private val taskCreateSchema = schema("""{"type":"object","properties":{"title":{"type":"string"},"priority":{"type":"string","enum":["LOW","NORMAL","HIGH"]},"estimatedMinutes":{"type":["integer","null"]},"remainingMinutes":{"type":["integer","null"]},"deadline":{"type":["object","null"]}},"required":["title","priority","estimatedMinutes","remainingMinutes","deadline"],"additionalProperties":false}""")
    private val taskUpdateSchema = schema("""{"type":"object","properties":{"taskId":{"type":"string"},"title":{"type":"string"},"status":{"type":"string","enum":["OPEN","IN_PROGRESS","COMPLETED","CANCELLED"]},"priority":{"type":"string","enum":["LOW","NORMAL","HIGH"]},"estimatedMinutes":{"type":["integer","null"]},"completedMinutes":{"type":"integer","minimum":0},"remainingMinutes":{"type":["integer","null"]},"deadline":{"type":["object","null"]}},"required":["taskId","title","status","priority","estimatedMinutes","completedMinutes","remainingMinutes","deadline"],"additionalProperties":false}""")
    private val taskListSchema = schema("""{"type":"object","properties":{"status":{"type":["string","null"],"enum":["OPEN","IN_PROGRESS","COMPLETED","CANCELLED",null]}},"required":["status"],"additionalProperties":false}""")
    private val historyTimelineSchema = schema("""{"type":"object","properties":{"limit":{"type":["integer","null"],"minimum":1,"maximum":200}},"required":["limit"],"additionalProperties":false}""")
    private val historyMutationSchema = schema("""{"type":"object","properties":{"mutationId":{"type":"string"}},"required":["mutationId"],"additionalProperties":false}""")
    private val historyEntitySchema = schema("""{"type":"object","properties":{"entityKind":{"type":"string"},"entityId":{"type":"string"},"limit":{"type":["integer","null"],"minimum":1,"maximum":200}},"required":["entityKind","entityId","limit"],"additionalProperties":false}""")
    private val calendarSchema = schema("""{"type":"object","properties":{"startDate":{"type":"string"},"endDateExclusive":{"type":"string"},"displayTimeZone":{"type":"string"}},"required":["startDate","endDateExclusive","displayTimeZone"],"additionalProperties":false}""")
    private val eventTimeSchema = schema("""{"type":"object","properties":{"kind":{"type":"string","enum":["ZONED","ALL_DAY","FLOATING"]},"start":{"type":["string","null"]},"endExclusive":{"type":["string","null"]},"startDate":{"type":["string","null"]},"endDateExclusive":{"type":["string","null"]},"timeZone":{"type":["string","null"]}},"required":["kind","start","endExclusive","startDate","endDateExclusive","timeZone"],"additionalProperties":false}""")
    private val eventCreateSchema = schema("""{"type":"object","properties":{"title":{"type":"string"},"time":$eventTimeSchema,"flexibility":{"type":"string","enum":["HARD","FLEXIBLE","SOFT"]},"pinState":{"type":"string","enum":["PINNED","UNPINNED"]}},"required":["title","time","flexibility","pinState"],"additionalProperties":false}""")
    private val eventUpdateSchema = schema("""{"type":"object","properties":{"eventId":{"type":"string"},"title":{"type":"string"},"time":$eventTimeSchema,"flexibility":{"type":"string","enum":["HARD","FLEXIBLE","SOFT"]},"pinState":{"type":"string","enum":["PINNED","UNPINNED"]}},"required":["eventId","title","time","flexibility","pinState"],"additionalProperties":false}""")
    private val plannerFullReplanSchema = schema("""{"type":"object","properties":{"planningProfileId":{"type":"string"},"referenceNow":{"type":"string"},"horizonStart":{"type":"string"},"horizonEndExclusive":{"type":"string"}},"required":["planningProfileId","referenceNow","horizonStart","horizonEndExclusive"],"additionalProperties":false}""")
    private val plannerLocalReflowSchema = schema("""{"type":"object","properties":{"planningProfileId":{"type":"string"},"referenceNow":{"type":"string"},"horizonStart":{"type":"string"},"horizonEndExclusive":{"type":"string"},"affectedFocusBlockIds":{"type":"array","items":{"type":"string"}},"disruptedRanges":{"type":"array","items":{"type":"object","properties":{"start":{"type":"string"},"endExclusive":{"type":"string"},"timeZone":{"type":"string"}},"required":["start","endExclusive","timeZone"],"additionalProperties":false}},"searchWindow":{"type":"object","properties":{"start":{"type":"string"},"endExclusive":{"type":"string"},"timeZone":{"type":"string"}},"required":["start","endExclusive","timeZone"],"additionalProperties":false}},"required":["planningProfileId","referenceNow","horizonStart","horizonEndExclusive","affectedFocusBlockIds","disruptedRanges","searchWindow"],"additionalProperties":false}""")
    private val historyUndoSchema = schema("""{"type":"object","properties":{"mutationId":{"type":"string"}},"required":["mutationId"],"additionalProperties":false}""")
    private val planningProfileUpdateSchema = schema("""{"type":"object","properties":{"planningProfileId":{"type":"string"},"name":{"type":"string"},"configuration":{"type":"string","enum":["UNCONFIGURED","CONFIGURED"]},"timeZone":{"type":["string","null"]},"weeklyAvailability":{"type":["array","null"],"items":{"type":"object","properties":{"dayOfWeek":{"type":"string","enum":["MONDAY","TUESDAY","WEDNESDAY","THURSDAY","FRIDAY","SATURDAY","SUNDAY"]},"start":{"type":"string"},"endExclusive":{"type":"string"}},"required":["dayOfWeek","start","endExclusive"],"additionalProperties":false}},"minimumFocusBlockMinutes":{"type":["integer","null"]},"preferredFocusBlockMinutes":{"type":["integer","null"]},"maximumFocusBlockMinutes":{"type":["integer","null"]},"allDayEventPolicy":{"type":["string","null"],"enum":["AVOID","TREAT_AS_BUSY",null]}},"required":["planningProfileId","name","configuration","timeZone","weeklyAvailability","minimumFocusBlockMinutes","preferredFocusBlockMinutes","maximumFocusBlockMinutes","allDayEventPolicy"],"additionalProperties":false}""")
    private val plannerApplyBranchSchema = schema("""{"type":"object","properties":{"planBranchId":{"type":"string"},"applyNow":{"type":"string"}},"required":["planBranchId","applyNow"],"additionalProperties":false}""")

    suspend fun createThread(): AgentThreadId = AgentThreadId(ids.next()).also { state.saveThread(AgentThread(it, null, clock.nowEpochMillis())) }

    suspend fun run(threadId: AgentThreadId, command: String): AgentRunResult =
        threadOperationLocks.withLock(threadId) { runLocked(threadId, command) }

    private suspend fun runLocked(threadId: AgentThreadId, command: String): AgentRunResult {
        if (command.isBlank()) return AgentRunResult.Failed("EMPTY_COMMAND")
        if (state.thread(threadId) == null) return AgentRunResult.Failed("THREAD_NOT_FOUND")
        if (state.toolCalls(threadId).any { it.state == AgentToolCallState.WAITING_CONFIRMATION }) return AgentRunResult.Failed("CONFIRMATION_PENDING")
        val ordinal = state.messages(threadId).maxOfOrNull { it.ordinal }?.plus(1) ?: 0
        state.appendMessage(AgentMessage(AgentMessageId(ids.next()), threadId, ordinal, AgentMessageRole.USER, command, clock.nowEpochMillis()))
        val config = selectedConfig() ?: return AgentRunResult.Failed("PROVIDER_NOT_CONFIGURED")
        val tools = when (val probe = provider.probe(config)) {
            ProviderProbeResult.Supported -> supportedTools()
            ProviderProbeResult.Unsupported -> emptyList()
            is ProviderProbeResult.Unavailable -> return AgentRunResult.Failed(probe.redactedCode)
        }
        return modelStep(threadId, config, tools, allowLocalRead = true)
    }

    suspend fun confirm(threadId: AgentThreadId, callId: AgentToolCallId, approved: Boolean): AgentRunResult =
        threadOperationLocks.withLock(threadId) { confirmLocked(threadId, callId, approved) }

    private suspend fun confirmLocked(threadId: AgentThreadId, callId: AgentToolCallId, approved: Boolean): AgentRunResult {
        val call = state.toolCalls(threadId).firstOrNull { it.id == callId && it.state == AgentToolCallState.WAITING_CONFIRMATION }
            ?: return AgentRunResult.Failed("CONFIRMATION_NOT_FOUND")
        val action = state.actions(threadId).firstOrNull { call.id in it.toolCallIds }
            ?: return AgentRunResult.Failed("ACTION_NOT_FOUND")
        if (!approved) {
            finishWithoutWrite(call, action, AgentToolResultStatus.PERMISSION_DENIED, "DENIED", AgentActionStatus.DENIED)
            return resumeAfterTool(threadId)
        }
        val policy = state.permissionPolicy()
        if (call.name == EVENT_CREATE_TOOL_NAME && eventCreate != null) {
            val prepared = eventCreate.prepare(call.argumentsJson, policy)
            val preview = previewForConfirmation(prepared, call, action) ?: return resumeAfterTool(threadId)
            if (eventCreate.normalizedPreviewJson(preview) != call.previewJson) {
                finishWithoutWrite(call, action, AgentToolResultStatus.STALE, "STALE_PREVIEW", AgentActionStatus.STALE)
                return resumeAfterTool(threadId)
            }
            executeEventCreate(call, action, preview, policy)
            return resumeAfterTool(threadId)
        }
        if (call.name == EVENT_UPDATE_TOOL_NAME && eventUpdate != null) {
            val prepared = eventUpdate.prepare(call.argumentsJson, policy)
            val preview = previewForConfirmation(prepared, call, action) ?: return resumeAfterTool(threadId)
            if (eventUpdate.normalizedPreviewJson(preview) != call.previewJson) {
                finishWithoutWrite(call, action, AgentToolResultStatus.STALE, "STALE_PREVIEW", AgentActionStatus.STALE)
                return resumeAfterTool(threadId)
            }
            executeEventUpdate(call, action, preview, policy)
            return resumeAfterTool(threadId)
        }
        if (call.name == AgentToolNames.HISTORY_UNDO && historyUndo != null) {
            val prepared = historyUndo.prepare(call.argumentsJson, policy)
            val preview = previewForConfirmation(prepared, call, action) ?: return resumeAfterTool(threadId)
            if (historyUndo.normalizedPreviewJson(preview) != call.previewJson) {
                finishWithoutWrite(call, action, AgentToolResultStatus.STALE, "STALE_PREVIEW", AgentActionStatus.STALE)
                return resumeAfterTool(threadId)
            }
            executeHistoryUndo(call, action, preview, policy)
            return resumeAfterTool(threadId)
        }
        if (call.name == PLANNING_PROFILE_UPDATE_TOOL_NAME && planningProfileUpdate != null) {
            val prepared = planningProfileUpdate.prepare(call.argumentsJson, policy)
            val preview = previewForConfirmation(prepared, call, action) ?: return resumeAfterTool(threadId)
            if (planningProfileUpdate.normalizedPreviewJson(preview) != call.previewJson) {
                finishWithoutWrite(call, action, AgentToolResultStatus.STALE, "STALE_PREVIEW", AgentActionStatus.STALE)
                return resumeAfterTool(threadId)
            }
            executePlanningProfileUpdate(call, action, preview, policy)
            return resumeAfterTool(threadId)
        }
        if (call.name == PLANNER_APPLY_BRANCH_TOOL_NAME && plannerApplyBranch != null) {
            val branchId = runCatching { json.decodeFromString(PlannerApplyBranchToolInput.serializer(), call.argumentsJson).planBranchId }.getOrNull()
            val branch = branchId?.let { planBranches[threadId to it] }
            if (branch == null) {
                finishOutcome(call, action, AgentToolOutcome.Stale)
                return resumeAfterTool(threadId)
            }
            val prepared = plannerApplyBranch.prepare(call.argumentsJson, branch, policy)
            val preview = previewForConfirmation(prepared, call, action) ?: return resumeAfterTool(threadId)
            if (branch.toSnapshotJson() != call.previewJson) {
                finishWithoutWrite(call, action, AgentToolResultStatus.STALE, "STALE_PREVIEW", AgentActionStatus.STALE)
                return resumeAfterTool(threadId)
            }
            executePlannerApply(call, action.copy(planBranchReference = branch.id.value), branch, preview, policy)
            return resumeAfterTool(threadId)
        }
        if (call.name == AgentToolNames.TASK_UPDATE && taskUpdate != null) {
            val prepared = taskUpdate.prepare(call.argumentsJson, policy)
            val preview = previewForConfirmation(prepared, call, action) ?: return resumeAfterTool(threadId)
            if (taskUpdate.normalizedPreviewJson(preview) != call.previewJson) {
                finishWithoutWrite(call, action, AgentToolResultStatus.STALE, "STALE_PREVIEW", AgentActionStatus.STALE)
                return resumeAfterTool(threadId)
            }
            executeTaskUpdate(call, action, preview, true, policy)
            return resumeAfterTool(threadId)
        }
        if (call.name != AgentToolNames.TASK_CREATE) return AgentRunResult.Failed("CONFIRMATION_UNSUPPORTED")
        val prepared = taskCreate.prepare(call.argumentsJson, policy)
        val preview = previewForConfirmation(prepared, call, action) ?: return resumeAfterTool(threadId)
        if (taskCreate.normalizedPreviewJson(preview) != call.previewJson) {
            finishWithoutWrite(call, action, AgentToolResultStatus.STALE, "STALE_PREVIEW", AgentActionStatus.STALE)
            return resumeAfterTool(threadId)
        }
        executeTaskCreate(call, action, preview, true, policy)
        return resumeAfterTool(threadId)
    }

    private suspend fun <T> previewForConfirmation(
        outcome: AgentToolOutcome<T>, call: AgentToolCall, action: AgentAction,
    ): T? = when (outcome) {
        is AgentToolOutcome.ConfirmationRequired -> outcome.preview
        is AgentToolOutcome.Success -> outcome.payload
        else -> {
            finishOutcome(call, action, outcome)
            null
        }
    }

    private suspend fun executeEventCreate(
        call: AgentToolCall, action: AgentAction, preview: EventCreateWritePreview,
        policy: dev.agenticscheduler.agent.permission.AgentPermissionPolicy,
    ) {
        val tool = requireNotNull(eventCreate)
        val resultId = AgentToolResultId(ids.next())
        val outcome = tool.commit(call.argumentsJson, preview, true, policy, action.id, onCommitted = { committed ->
            val event = (committed.value as EditingResult.Success).value
            state.appendToolResult(AgentToolResult(resultId, call.threadId, call.id, call.ordinal, AgentToolResultStatus.SUCCESS,
                json.encodeToString(EventCommitSnapshot(event.id.value, committed.mutationId.value)), listOf(committed.mutationId)))
            state.saveAction(action.copy(toolResultIds = listOf(resultId), mutationIds = listOf(committed.mutationId), status = AgentActionStatus.SUCCEEDED))
            state.saveToolCall(call.copy(state = AgentToolCallState.COMPLETED))
        })
        if (outcome !is AgentToolOutcome.Success) finishOutcome(call, action, outcome)
    }

    private suspend fun executeEventUpdate(
        call: AgentToolCall, action: AgentAction, preview: dev.agenticscheduler.application.editing.EventUpdatePreview,
        policy: dev.agenticscheduler.agent.permission.AgentPermissionPolicy,
    ) {
        val tool = requireNotNull(eventUpdate)
        val resultId = AgentToolResultId(ids.next())
        val outcome = tool.commit(call.argumentsJson, preview, true, policy, action.id, onCommitted = { committed ->
            val event = (committed.value as EditingResult.Success).value
            state.appendToolResult(AgentToolResult(resultId, call.threadId, call.id, call.ordinal, AgentToolResultStatus.SUCCESS,
                json.encodeToString(EventCommitSnapshot(event.id.value, committed.mutationId.value)), listOf(committed.mutationId)))
            state.saveAction(action.copy(toolResultIds = listOf(resultId), mutationIds = listOf(committed.mutationId), status = AgentActionStatus.SUCCEEDED))
            state.saveToolCall(call.copy(state = AgentToolCallState.COMPLETED))
        })
        if (outcome !is AgentToolOutcome.Success) finishOutcome(call, action, outcome)
    }

    private suspend fun executeHistoryUndo(
        call: AgentToolCall, action: AgentAction, preview: HistoryUndoPreview,
        policy: dev.agenticscheduler.agent.permission.AgentPermissionPolicy,
    ) {
        val tool = requireNotNull(historyUndo)
        val resultId = AgentToolResultId(ids.next())
        val outcome = tool.commit(call.argumentsJson, preview, true, policy, onCommitted = { committed ->
            check(committed.value is dev.agenticscheduler.application.history.UndoResult.Applied) {
                "Undo commit callback must describe an applied result."
            }
            state.appendToolResult(AgentToolResult(resultId, call.threadId, call.id, call.ordinal, AgentToolResultStatus.SUCCESS,
                json.encodeToString(UndoCommitSnapshot(preview.originalMutationId, committed.mutationId.value)), listOf(committed.mutationId)))
            state.saveAction(action.copy(toolResultIds = listOf(resultId), mutationIds = listOf(committed.mutationId), status = AgentActionStatus.SUCCEEDED))
            state.saveToolCall(call.copy(state = AgentToolCallState.COMPLETED))
        })
        if (outcome !is AgentToolOutcome.Success) finishOutcome(call, action, outcome)
    }

    private suspend fun executePlanningProfileUpdate(
        call: AgentToolCall, action: AgentAction, preview: PlanningProfileUpdateWritePreview,
        policy: dev.agenticscheduler.agent.permission.AgentPermissionPolicy,
    ) {
        val tool = requireNotNull(planningProfileUpdate)
        val resultId = AgentToolResultId(ids.next())
        val outcome = tool.commit(call.argumentsJson, preview, true, policy, action.id, onCommitted = { committed ->
            val result = committed.value as dev.agenticscheduler.application.planner.PlanningProfileSettingsResult.Success
            state.appendToolResult(AgentToolResult(resultId, call.threadId, call.id, call.ordinal, AgentToolResultStatus.SUCCESS,
                json.encodeToString(ProfileCommitSnapshot(result.profile.id.value, result.profile.name, committed.mutationId.value)), listOf(committed.mutationId)))
            state.saveAction(action.copy(toolResultIds = listOf(resultId), mutationIds = listOf(committed.mutationId), status = AgentActionStatus.SUCCEEDED))
            state.saveToolCall(call.copy(state = AgentToolCallState.COMPLETED))
        })
        if (outcome !is AgentToolOutcome.Success) finishOutcome(call, action, outcome)
    }

    private suspend fun executePlannerApply(
        call: AgentToolCall, action: AgentAction,
        branch: dev.agenticscheduler.application.planner.PlanBranch,
        preview: PlannerApplyBranchPreview,
        policy: dev.agenticscheduler.agent.permission.AgentPermissionPolicy,
    ) {
        val tool = requireNotNull(plannerApplyBranch)
        var resultRecorded = false
        val outcome = tool.commit(call.argumentsJson, branch, preview, true, policy, action.id, onCommitted = { committed ->
            val applied = committed.value as? dev.agenticscheduler.application.planner.PlanBranchApplyResult.Applied
                ?: error("Planner Apply commit callback must describe an applied branch.")
            planBranches[call.threadId to branch.id.value] = applied.branch
            finishResult(
                call, action, AgentToolResultStatus.SUCCESS,
                json.encodeToString(PlannerApplyCommitSnapshot(branch.id.value, applied.branch.status.name, committed.mutationId.value)),
                AgentActionStatus.SUCCEEDED, listOf(committed.mutationId), branch.id.value,
            )
            resultRecorded = true
        })
        when (outcome) {
            is AgentToolOutcome.Success -> {
                planBranches[call.threadId to branch.id.value] = outcome.payload.branch
                if (!resultRecorded) finishResult(
                    call, action, AgentToolResultStatus.SUCCESS,
                    json.encodeToString(PlannerApplyCommitSnapshot(branch.id.value, outcome.payload.branch.status.name, null)),
                    AgentActionStatus.SUCCEEDED, planBranchReference = branch.id.value,
                )
            }
            else -> finishOutcome(call, action, outcome)
        }
    }

    private suspend fun executeTaskCreate(
        call: AgentToolCall, action: AgentAction, preview: TaskCreateWritePreview,
        userConfirmed: Boolean, policy: dev.agenticscheduler.agent.permission.AgentPermissionPolicy,
    ) {
        val resultId = AgentToolResultId(ids.next())
        val outcome = taskCreate.commit(call.argumentsJson, preview, userConfirmed, policy, action.id, onCommitted = { committed ->
            val task = (committed.value as EditingResult.Success).value
            state.appendToolResult(AgentToolResult(resultId, call.threadId, call.id, call.ordinal, AgentToolResultStatus.SUCCESS,
                json.encodeToString(TaskCommitSnapshot(task.id.value, committed.mutationId.value)), listOf(committed.mutationId)))
            state.saveAction(action.copy(toolResultIds = listOf(resultId), mutationIds = listOf(committed.mutationId), status = AgentActionStatus.SUCCEEDED))
            state.saveToolCall(call.copy(state = AgentToolCallState.COMPLETED))
        })
        if (outcome !is AgentToolOutcome.Success) {
            when (outcome) {
                is AgentToolOutcome.PermissionDenied -> finishWithoutWrite(call, action, AgentToolResultStatus.PERMISSION_DENIED, "PERMISSION_DENIED", AgentActionStatus.DENIED)
                AgentToolOutcome.Stale -> finishWithoutWrite(call, action, AgentToolResultStatus.STALE, "STALE", AgentActionStatus.STALE)
                is AgentToolOutcome.InvalidInput -> finishWithoutWrite(call, action, AgentToolResultStatus.INVALID_INPUT, "INVALID_INPUT", AgentActionStatus.FAILED)
                else -> finishWithoutWrite(call, action, AgentToolResultStatus.INFRASTRUCTURE_FAILURE, "COMMIT_FAILED", AgentActionStatus.FAILED)
            }
        }
    }

    private suspend fun executeTaskUpdate(
        call: AgentToolCall, action: AgentAction, preview: dev.agenticscheduler.application.editing.TaskUpdatePreview,
        userConfirmed: Boolean, policy: dev.agenticscheduler.agent.permission.AgentPermissionPolicy,
    ) {
        val tool = requireNotNull(taskUpdate)
        val resultId = AgentToolResultId(ids.next())
        val outcome = tool.commit(call.argumentsJson, preview, userConfirmed, policy, action.id, onCommitted = { committed ->
            val task = (committed.value as EditingResult.Success).value
            state.appendToolResult(AgentToolResult(resultId, call.threadId, call.id, call.ordinal, AgentToolResultStatus.SUCCESS,
                json.encodeToString(TaskCommitSnapshot(task.id.value, committed.mutationId.value)), listOf(committed.mutationId)))
            state.saveAction(action.copy(toolResultIds = listOf(resultId), mutationIds = listOf(committed.mutationId), status = AgentActionStatus.SUCCEEDED))
            state.saveToolCall(call.copy(state = AgentToolCallState.COMPLETED))
        })
        if (outcome !is AgentToolOutcome.Success) {
            when (outcome) {
                is AgentToolOutcome.PermissionDenied -> finishWithoutWrite(call, action, AgentToolResultStatus.PERMISSION_DENIED, "PERMISSION_DENIED", AgentActionStatus.DENIED)
                AgentToolOutcome.Stale -> finishWithoutWrite(call, action, AgentToolResultStatus.STALE, "STALE", AgentActionStatus.STALE)
                AgentToolOutcome.NotFound -> finishWithoutWrite(call, action, AgentToolResultStatus.NOT_FOUND, "NOT_FOUND", AgentActionStatus.FAILED)
                is AgentToolOutcome.InvalidInput -> finishWithoutWrite(call, action, AgentToolResultStatus.INVALID_INPUT, "INVALID_INPUT", AgentActionStatus.FAILED)
                else -> finishWithoutWrite(call, action, AgentToolResultStatus.INFRASTRUCTURE_FAILURE, "COMMIT_FAILED", AgentActionStatus.FAILED)
            }
        }
    }

    private suspend fun resumeAfterTool(threadId: AgentThreadId): AgentRunResult {
        val config = selectedConfig() ?: return AgentRunResult.Failed("PROVIDER_NOT_CONFIGURED")
        return modelStep(threadId, config, emptyList(), allowLocalRead = false)
    }

    private suspend fun modelStep(
        threadId: AgentThreadId,
        config: ProviderConfig,
        tools: List<ProviderToolDefinition>,
        allowLocalRead: Boolean,
    ): AgentRunResult {
        val transcript = when (val assembled = transcripts.assemble(threadId)) {
            is AgentTranscriptResult.Ready -> assembled.messages
            AgentTranscriptResult.UnresolvedToolCall -> return AgentRunResult.Failed("UNRESOLVED_TOOL_CALL")
            AgentTranscriptResult.InvalidHistory -> return AgentRunResult.Failed("INVALID_HISTORY")
        }
        val meter = Utf8ByteBudgetMeter(config.maxContextUnits, config.reservedOutputUnits)
        val budget = ContextAssembler(meter)
        val lastUser = transcript.indexOfLast { it.role == "user" }
        if (lastUser < 0) return AgentRunResult.Failed("NO_USER_MESSAGE")
        val groups = group(transcript)
        val rawMessages = state.messages(threadId).sortedWith(compareBy({ it.ordinal }, { it.id.value }))
        if (groups.size != rawMessages.size) return AgentRunResult.Failed("INVALID_HISTORY")
        val currentGroup = groups.indexOfFirst { it.first <= lastUser && lastUser < it.first + it.second.size }
        val latestToolGroup = groups.indexOfLast { (start, messages) -> start > lastUser && messages.any { it.role == "tool" } }
        val latestToolAnchor = groups.getOrNull(latestToolGroup)?.second?.let { json.encodeToString(it) }
        var summary = state.summaries(threadId).maxWithOrNull(compareBy<ContextSummary>({ it.createdAtEpochMillis }, { it.id.value }))
        if (summary != null && rawMessages.none { it.id == summary.sourceEndMessageId }) return AgentRunResult.Failed("INVALID_HISTORY")
        fun candidates(): List<ContextCandidate> {
            val coveredEnd = summary?.let { value -> rawMessages.indexOfFirst { it.id == value.sourceEndMessageId } }
            return groups.mapIndexedNotNull { index, (_, messages) ->
                val source = if (messages.any { it.role == "tool" }) ContextClass.CURRENT_DOMAIN else ContextClass.RAW_MESSAGES
                if (index == currentGroup || index == latestToolGroup || (source == ContextClass.RAW_MESSAGES && coveredEnd != null && index <= coveredEnd)) null
                else ContextCandidate(rawMessages[index].id.value, source, json.encodeToString(messages), index, rawMessages[index].createdAtEpochMillis)
            } + summary?.let { value ->
                listOf(ContextCandidate("summary:${value.id.value}", ContextClass.SUMMARY, value.text, 0, value.createdAtEpochMillis))
            }.orEmpty()
        }
        fun assemble(values: List<ContextCandidate>) = budget.assemble(ContextRequest(
            system, tools.map { it.name to it.parameters.toString() }, transcript[lastUser].content.orEmpty(), latestToolAnchor, values,
        ))
        var candidates = candidates()
        var assembled = assemble(candidates)
        if (assembled is ContextAssemblyResult.ContextTooLarge) return AgentRunResult.Failed("CONTEXT_TOO_LARGE")
        val ready = assembled as ContextAssemblyResult.Ready
        val rawDemand = candidates.asSequence().filter { it.source == ContextClass.RAW_MESSAGES }
            .fold(0L) { total, candidate ->
                val cost = meter.measure(candidate.serialized)
                if (cost > Long.MAX_VALUE - total) Long.MAX_VALUE else total + cost
            }
        val compactor = ContextCompactionService(ContextSummaryProvider { previous, prefix ->
            val prompt = json.encodeToString(prefix)
            when (val result = provider.complete(config, listOf(
                ProviderChatMessage("system", "Summarize earlier conversation without inventing current facts. This summary is non-authoritative."),
                ProviderChatMessage("user", (previous?.text?.let { "Previous summary: $it\n" } ?: "") + prompt),
            ), emptyList())) {
                is ProviderCallResult.Success -> result.message.content?.takeIf { result.message.toolCalls.isNullOrEmpty() && it.isNotBlank() }
                    ?: error("Summary response has no plain text.")
                is ProviderCallResult.Failure -> error("Summary provider unavailable.")
            }
        }, state::appendSummary, ids, EpochMillisecondsClock { clock.nowEpochMillis() }, 1)
        val protected = state.toolCalls(threadId).asSequence()
            .filter { it.state == AgentToolCallState.PROPOSED || it.state == AgentToolCallState.WAITING_CONFIRMATION || it.state == AgentToolCallState.RUNNING }
            .map { it.sourceMessageId }.toSet()
        when (val compacted = compactor.compact(rawMessages, summary, protected,
            CompactionPressure(rawDemand, ready.usedUnits, ready.maxInputUnits), config.id, config.model)) {
            is ContextCompactionResult.Saved -> {
                summary = compacted.summary
                candidates = candidates()
                assembled = assemble(candidates)
                if (assembled is ContextAssemblyResult.ContextTooLarge) return AgentRunResult.Failed("CONTEXT_TOO_LARGE")
            }
            ContextCompactionResult.NotNeeded, is ContextCompactionResult.Failed -> Unit
        }
        val selected = (assembled as ContextAssemblyResult.Ready).parts.map { it.id }.toSet()
        val messages = buildList {
            add(ProviderChatMessage("system", system))
            summary?.let { value ->
                if ("summary:${value.id.value}" in selected) add(ProviderChatMessage("user", "Earlier, potentially stale summary: ${value.text}"))
            }
            groups.forEachIndexed { index, pair -> if (index == currentGroup || index == latestToolGroup || rawMessages[index].id.value in selected) addAll(pair.second) }
        }
        return when (val response = provider.complete(config, messages, tools)) {
            is ProviderCallResult.Failure -> AgentRunResult.Failed(response.redactedCode)
            is ProviderCallResult.Success -> handleResponse(threadId, response.message, tools, allowLocalRead, config)
        }
    }

    private suspend fun handleResponse(threadId: AgentThreadId, response: ProviderChatMessage, tools: List<ProviderToolDefinition>, allowLocalRead: Boolean, config: ProviderConfig): AgentRunResult {
        val calls = response.toolCalls.orEmpty()
        invalidProviderToolCallCode(calls)?.let { return AgentRunResult.Failed(it) }
        val message = AgentMessage(AgentMessageId(ids.next()), threadId, state.messages(threadId).maxOfOrNull { it.ordinal }?.plus(1) ?: 0,
            AgentMessageRole.ASSISTANT, response.content.orEmpty(), clock.nowEpochMillis())
        state.appendMessage(message)
        if (calls.isEmpty()) return AgentRunResult.Completed(message.content)
        val proposed = calls.single()
        val call = AgentToolCall(AgentToolCallId(ids.next()), threadId, message.id, state.toolCalls(threadId).maxOfOrNull { it.ordinal }?.plus(1) ?: 0,
            proposed.function.name, proposed.function.arguments, AgentToolCallState.PROPOSED, providerCallId = proposed.id)
        state.saveToolCall(call)
        val action = AgentAction(AgentActionId(ids.next()), threadId, message.id, config.id, config.model,
            listOf(call.id), emptyList(), null, null, null, emptyList(), AgentActionStatus.PROPOSED)
        state.saveAction(action)
        if (tools.none { it.name == call.name }) {
            finishWithoutWrite(call, action, AgentToolResultStatus.PERMISSION_DENIED, "UNREGISTERED_TOOL", AgentActionStatus.DENIED)
            return AgentRunResult.Failed("UNREGISTERED_TOOL")
        }
        if (call.name == EVENT_CREATE_TOOL_NAME && eventCreate != null) {
            val policy = state.permissionPolicy()
            return when (val prepared = eventCreate.prepare(call.argumentsJson, policy)) {
                is AgentToolOutcome.ConfirmationRequired -> awaitConfirmation(call, action, eventCreate.normalizedPreviewJson(prepared.preview))
                is AgentToolOutcome.Success -> {
                    executeEventCreate(call, action.copy(permissionDecision = AgentPermissionMode.ALLOW_DIRECT), prepared.payload, policy)
                    modelStep(threadId, config, emptyList(), allowLocalRead = false)
                }
                else -> { finishOutcome(call, action, prepared); AgentRunResult.Failed(outcomeCode(prepared)) }
            }
        }
        if (call.name == EVENT_UPDATE_TOOL_NAME && eventUpdate != null) {
            val policy = state.permissionPolicy()
            return when (val prepared = eventUpdate.prepare(call.argumentsJson, policy)) {
                is AgentToolOutcome.ConfirmationRequired -> awaitConfirmation(call, action, eventUpdate.normalizedPreviewJson(prepared.preview))
                is AgentToolOutcome.Success -> {
                    executeEventUpdate(call, action.copy(permissionDecision = AgentPermissionMode.ALLOW_DIRECT), prepared.payload, policy)
                    modelStep(threadId, config, emptyList(), allowLocalRead = false)
                }
                else -> { finishOutcome(call, action, prepared); AgentRunResult.Failed(outcomeCode(prepared)) }
            }
        }
        if (call.name == AgentToolNames.HISTORY_UNDO && historyUndo != null) {
            val policy = state.permissionPolicy()
            return when (val prepared = historyUndo.prepare(call.argumentsJson, policy)) {
                is AgentToolOutcome.ConfirmationRequired -> awaitConfirmation(call, action, historyUndo.normalizedPreviewJson(prepared.preview))
                is AgentToolOutcome.Success -> {
                    executeHistoryUndo(call, action.copy(permissionDecision = AgentPermissionMode.ALLOW_DIRECT), prepared.payload, policy)
                    modelStep(threadId, config, emptyList(), allowLocalRead = false)
                }
                else -> { finishOutcome(call, action, prepared); AgentRunResult.Failed(outcomeCode(prepared)) }
            }
        }
        if (call.name == PLANNING_PROFILE_UPDATE_TOOL_NAME && planningProfileUpdate != null) {
            val policy = state.permissionPolicy()
            return when (val prepared = planningProfileUpdate.prepare(call.argumentsJson, policy)) {
                is AgentToolOutcome.ConfirmationRequired -> awaitConfirmation(call, action, planningProfileUpdate.normalizedPreviewJson(prepared.preview))
                is AgentToolOutcome.Success -> {
                    executePlanningProfileUpdate(call, action.copy(permissionDecision = AgentPermissionMode.ALLOW_DIRECT), prepared.payload, policy)
                    modelStep(threadId, config, emptyList(), allowLocalRead = false)
                }
                else -> { finishOutcome(call, action, prepared); AgentRunResult.Failed(outcomeCode(prepared)) }
            }
        }
        if (call.name == PLANNER_APPLY_BRANCH_TOOL_NAME && plannerApplyBranch != null) {
            val input = runCatching { json.decodeFromString(PlannerApplyBranchToolInput.serializer(), call.argumentsJson) }.getOrNull()
            val branch = input?.let { planBranches[threadId to it.planBranchId] }
            if (branch == null) {
                finishOutcome(call, action, AgentToolOutcome.Stale)
                return AgentRunResult.Failed("STALE")
            }
            val policy = state.permissionPolicy()
            return when (val prepared = plannerApplyBranch.prepare(call.argumentsJson, branch, policy)) {
                is AgentToolOutcome.ConfirmationRequired -> awaitConfirmation(call, action.copy(planBranchReference = branch.id.value), branch.toSnapshotJson())
                is AgentToolOutcome.Success -> {
                    executePlannerApply(call, action.copy(permissionDecision = AgentPermissionMode.ALLOW_DIRECT, planBranchReference = branch.id.value), branch, prepared.payload, policy)
                    modelStep(threadId, config, emptyList(), allowLocalRead = false)
                }
                else -> { finishOutcome(call, action, prepared); AgentRunResult.Failed(outcomeCode(prepared)) }
            }
        }
        if (call.name == AgentToolNames.TASK_CREATE) {
            val policy = state.permissionPolicy()
            return when (val prepared = taskCreate.prepare(call.argumentsJson, policy)) {
                is AgentToolOutcome.ConfirmationRequired -> {
                    val previewJson = taskCreate.normalizedPreviewJson(prepared.preview)
                    state.saveToolCall(call.copy(state = AgentToolCallState.WAITING_CONFIRMATION, previewJson = previewJson))
                    state.saveAction(action.copy(permissionDecision = AgentPermissionMode.REQUIRE_CONFIRMATION, status = AgentActionStatus.WAITING_CONFIRMATION))
                    AgentRunResult.AwaitingConfirmation(call.id, previewJson)
                }
                is AgentToolOutcome.Success -> {
                    executeTaskCreate(call, action.copy(permissionDecision = AgentPermissionMode.ALLOW_DIRECT), prepared.payload, false, policy)
                    modelStep(threadId, config, emptyList(), allowLocalRead = false)
                }
                is AgentToolOutcome.InvalidInput -> { finishWithoutWrite(call, action, AgentToolResultStatus.INVALID_INPUT, "INVALID_INPUT", AgentActionStatus.FAILED); AgentRunResult.Failed("INVALID_INPUT") }
                is AgentToolOutcome.PermissionDenied -> { finishWithoutWrite(call, action, AgentToolResultStatus.PERMISSION_DENIED, "PERMISSION_DENIED", AgentActionStatus.DENIED); AgentRunResult.Failed("PERMISSION_DENIED") }
                else -> AgentRunResult.Failed("TOOL_PREVIEW_FAILURE")
            }
        }
        if (call.name == AgentToolNames.TASK_UPDATE && taskUpdate != null) {
            val policy = state.permissionPolicy()
            return when (val prepared = taskUpdate.prepare(call.argumentsJson, policy)) {
                is AgentToolOutcome.ConfirmationRequired -> {
                    val previewJson = taskUpdate.normalizedPreviewJson(prepared.preview)
                    state.saveToolCall(call.copy(state = AgentToolCallState.WAITING_CONFIRMATION, previewJson = previewJson))
                    state.saveAction(action.copy(permissionDecision = AgentPermissionMode.REQUIRE_CONFIRMATION, status = AgentActionStatus.WAITING_CONFIRMATION))
                    AgentRunResult.AwaitingConfirmation(call.id, previewJson)
                }
                is AgentToolOutcome.Success -> {
                    executeTaskUpdate(call, action.copy(permissionDecision = AgentPermissionMode.ALLOW_DIRECT), prepared.payload, false, policy)
                    modelStep(threadId, config, emptyList(), allowLocalRead = false)
                }
                is AgentToolOutcome.InvalidInput -> { finishWithoutWrite(call, action, AgentToolResultStatus.INVALID_INPUT, "INVALID_INPUT", AgentActionStatus.FAILED); AgentRunResult.Failed("INVALID_INPUT") }
                is AgentToolOutcome.PermissionDenied -> { finishWithoutWrite(call, action, AgentToolResultStatus.PERMISSION_DENIED, "PERMISSION_DENIED", AgentActionStatus.DENIED); AgentRunResult.Failed("PERMISSION_DENIED") }
                AgentToolOutcome.NotFound -> { finishWithoutWrite(call, action, AgentToolResultStatus.NOT_FOUND, "NOT_FOUND", AgentActionStatus.FAILED); AgentRunResult.Failed("NOT_FOUND") }
                else -> AgentRunResult.Failed("TOOL_PREVIEW_FAILURE")
            }
        }
        if (call.name in setOf(AgentToolNames.CALENDAR_LIST, AgentToolNames.TASK_GET, AgentToolNames.TASK_LIST, AgentToolNames.HISTORY_TIMELINE, AgentToolNames.HISTORY_GET_MUTATION, AgentToolNames.HISTORY_GET_ENTITY_CHANGES) &&
            state.permissionPolicy().modeFor(dev.agenticscheduler.agent.permission.AgentToolCapability.READ) != AgentPermissionMode.ALLOW_DIRECT) {
            finishWithoutWrite(call, action, AgentToolResultStatus.PERMISSION_DENIED, "READ_NOT_ALLOWED", AgentActionStatus.DENIED)
            return AgentRunResult.Failed("READ_NOT_ALLOWED")
        }
        if (call.name in setOf(PlannerToolNames.PREVIEW_FULL_REPLAN, PlannerToolNames.PREVIEW_LOCAL_REFLOW) &&
            state.permissionPolicy().modeFor(dev.agenticscheduler.agent.permission.AgentToolCapability.PLAN_PREVIEW) != AgentPermissionMode.ALLOW_DIRECT) {
            finishWithoutWrite(call, action, AgentToolResultStatus.PERMISSION_DENIED, "PLAN_PREVIEW_NOT_ALLOWED", AgentActionStatus.DENIED)
            return AgentRunResult.Failed("PLAN_PREVIEW_NOT_ALLOWED")
        }
        if (call.name == AgentToolNames.TASK_GET && allowLocalRead) {
            val taskId = runCatching { TaskId(json.decodeFromString(TaskGetInput.serializer(), call.argumentsJson).taskId) }.getOrNull()
            val result = if (taskId == null) AgentToolResultStatus.INVALID_INPUT to "INVALID_INPUT" else try { when (val read = taskGet.execute(taskId)) {
                is AgentToolOutcome.Success -> AgentToolResultStatus.SUCCESS to json.encodeToString(read.payload.toSnapshot())
                AgentToolOutcome.NotFound -> AgentToolResultStatus.NOT_FOUND to "NOT_FOUND"
                else -> AgentToolResultStatus.INFRASTRUCTURE_FAILURE to "READ_FAILED"
            } } catch (cancelled: CancellationException) { throw cancelled } catch (_: Exception) {
                AgentToolResultStatus.INFRASTRUCTURE_FAILURE to "READ_FAILED"
            }
            finishWithoutWrite(call, action.copy(permissionDecision = AgentPermissionMode.ALLOW_DIRECT), result.first, result.second, if (result.first == AgentToolResultStatus.SUCCESS) AgentActionStatus.SUCCEEDED else AgentActionStatus.FAILED)
            return modelStep(threadId, config, tools, allowLocalRead = false)
        }
        if (call.name == AgentToolNames.CALENDAR_LIST && allowLocalRead && calendarList != null) {
            val viewport = runCatching {
                val input = json.decodeFromString(CalendarInput.serializer(), call.argumentsJson)
                CalendarViewport(LocalDate.parse(input.startDate), LocalDate.parse(input.endDateExclusive), TimeZone.of(input.displayTimeZone))
            }.getOrNull()
            val result = if (viewport == null) AgentToolResultStatus.INVALID_INPUT to "INVALID_INPUT" else try {
                when (val read = calendarList.execute(viewport)) {
                    is AgentToolOutcome.Success -> AgentToolResultStatus.SUCCESS to json.encodeToString(read.payload.toSnapshot())
                    else -> AgentToolResultStatus.INFRASTRUCTURE_FAILURE to "READ_FAILED"
                }
            } catch (cancelled: CancellationException) { throw cancelled } catch (_: Exception) {
                AgentToolResultStatus.INFRASTRUCTURE_FAILURE to "READ_FAILED"
            }
            finishWithoutWrite(call, action.copy(permissionDecision = AgentPermissionMode.ALLOW_DIRECT), result.first, result.second, if (result.first == AgentToolResultStatus.SUCCESS) AgentActionStatus.SUCCEEDED else AgentActionStatus.FAILED)
            return modelStep(threadId, config, tools, allowLocalRead = false)
        }
        if (call.name == AgentToolNames.TASK_LIST && allowLocalRead && taskList != null) {
            val input = decodeTaskListInput(call.argumentsJson)
            val status = input?.status?.let { name -> TaskStatus.entries.firstOrNull { it.name == name } }
            val result = if (input == null || (input.status != null && status == null)) {
                AgentToolResultStatus.INVALID_INPUT to "INVALID_INPUT"
            } else try {
                when (val read = taskList.execute(TaskListToolInput(status))) {
                    is AgentToolOutcome.Success -> AgentToolResultStatus.SUCCESS to json.encodeToString(read.payload.map { it.toSnapshot() })
                    else -> AgentToolResultStatus.INFRASTRUCTURE_FAILURE to "READ_FAILED"
                }
            } catch (cancelled: CancellationException) { throw cancelled } catch (_: Exception) {
                AgentToolResultStatus.INFRASTRUCTURE_FAILURE to "READ_FAILED"
            }
            finishWithoutWrite(call, action.copy(permissionDecision = AgentPermissionMode.ALLOW_DIRECT), result.first, result.second, if (result.first == AgentToolResultStatus.SUCCESS) AgentActionStatus.SUCCEEDED else AgentActionStatus.FAILED)
            return modelStep(threadId, config, tools, allowLocalRead = false)
        }
        if (call.name == AgentToolNames.HISTORY_TIMELINE && allowLocalRead && historyReads != null) {
            val input = runCatching { json.decodeFromString(TimelineInput.serializer(), call.argumentsJson) }.getOrNull()
            val result = if (input == null) AgentToolResultStatus.INVALID_INPUT to "INVALID_INPUT" else try {
                when (val read = historyReads.timeline(HistoryTimelineInput(limit = input.limit))) {
                    is AgentToolOutcome.Success -> AgentToolResultStatus.SUCCESS to json.encodeToString(read.payload.map { HistoryEntry(it.operation, it.committedAtEpochMillis) })
                    is AgentToolOutcome.InvalidInput -> AgentToolResultStatus.INVALID_INPUT to "INVALID_INPUT"
                    else -> AgentToolResultStatus.INFRASTRUCTURE_FAILURE to "READ_FAILED"
                }
            } catch (cancelled: CancellationException) { throw cancelled } catch (_: Exception) {
                AgentToolResultStatus.INFRASTRUCTURE_FAILURE to "READ_FAILED"
            }
            finishWithoutWrite(call, action.copy(permissionDecision = AgentPermissionMode.ALLOW_DIRECT), result.first, result.second, if (result.first == AgentToolResultStatus.SUCCESS) AgentActionStatus.SUCCEEDED else AgentActionStatus.FAILED)
            return modelStep(threadId, config, tools, allowLocalRead = false)
        }
        if (call.name == AgentToolNames.HISTORY_GET_MUTATION && allowLocalRead && historyReads != null) {
            val mutationId = runCatching { MutationId(json.decodeFromString(MutationInput.serializer(), call.argumentsJson).mutationId) }.getOrNull()
            val result = if (mutationId == null) AgentToolResultStatus.INVALID_INPUT to "INVALID_INPUT" else try {
                when (val read = historyReads.getMutation(mutationId)) {
                    is AgentToolOutcome.Success -> AgentToolResultStatus.SUCCESS to json.encodeToString(HistoryEntry(read.payload.operation, read.payload.committedAtEpochMillis))
                    AgentToolOutcome.NotFound -> AgentToolResultStatus.NOT_FOUND to "NOT_FOUND"
                    else -> AgentToolResultStatus.INFRASTRUCTURE_FAILURE to "READ_FAILED"
                }
            } catch (cancelled: CancellationException) { throw cancelled } catch (_: Exception) {
                AgentToolResultStatus.INFRASTRUCTURE_FAILURE to "READ_FAILED"
            }
            finishWithoutWrite(call, action.copy(permissionDecision = AgentPermissionMode.ALLOW_DIRECT), result.first, result.second, if (result.first == AgentToolResultStatus.SUCCESS) AgentActionStatus.SUCCEEDED else AgentActionStatus.FAILED)
            return modelStep(threadId, config, tools, allowLocalRead = false)
        }
        if (call.name == AgentToolNames.HISTORY_GET_ENTITY_CHANGES && allowLocalRead && historyReads != null) {
            val input = runCatching { json.decodeFromString(EntityChangesInput.serializer(), call.argumentsJson) }.getOrNull()
            val kind = input?.entityKind?.let { name -> EntityKind.entries.firstOrNull { it.name == name } }
            val result = if (input == null || kind == null) AgentToolResultStatus.INVALID_INPUT to "INVALID_INPUT" else try {
                when (val read = historyReads.getEntityChanges(HistoryEntityChangesInput(kind, input.entityId, limit = input.limit))) {
                    is AgentToolOutcome.Success -> AgentToolResultStatus.SUCCESS to json.encodeToString(read.payload.map { it.toSnapshot() })
                    is AgentToolOutcome.InvalidInput -> AgentToolResultStatus.INVALID_INPUT to "INVALID_INPUT"
                    else -> AgentToolResultStatus.INFRASTRUCTURE_FAILURE to "READ_FAILED"
                }
            } catch (cancelled: CancellationException) { throw cancelled } catch (_: Exception) {
                AgentToolResultStatus.INFRASTRUCTURE_FAILURE to "READ_FAILED"
            }
            finishWithoutWrite(call, action.copy(permissionDecision = AgentPermissionMode.ALLOW_DIRECT), result.first, result.second, if (result.first == AgentToolResultStatus.SUCCESS) AgentActionStatus.SUCCEEDED else AgentActionStatus.FAILED)
            return modelStep(threadId, config, tools, allowLocalRead = false)
        }
        if (call.name == PlannerToolNames.PREVIEW_FULL_REPLAN && allowLocalRead && plannerFullReplan != null) {
            val outcome = plannerFullReplan.execute(call.argumentsJson)
            if (outcome is AgentToolOutcome.Success) planBranches[threadId to outcome.payload.id.value] = outcome.payload
            finishPlannerPreview(call, action, outcome)
            return modelStep(threadId, config, tools, allowLocalRead = false)
        }
        if (call.name == PlannerToolNames.PREVIEW_LOCAL_REFLOW && allowLocalRead && plannerLocalReflow != null) {
            val outcome = plannerLocalReflow.execute(call.argumentsJson)
            if (outcome is AgentToolOutcome.Success) planBranches[threadId to outcome.payload.id.value] = outcome.payload
            finishPlannerPreview(call, action, outcome)
            return modelStep(threadId, config, tools, allowLocalRead = false)
        }
        finishWithoutWrite(call, action, AgentToolResultStatus.PERMISSION_DENIED, "TOOL_CALL_LIMIT", AgentActionStatus.DENIED)
        return AgentRunResult.Failed("TOOL_CALL_LIMIT")
    }

    private suspend fun finishWithoutWrite(call: AgentToolCall, action: AgentAction, status: AgentToolResultStatus, code: String, actionStatus: AgentActionStatus) {
        val resultId = AgentToolResultId(ids.next())
        state.appendToolResult(AgentToolResult(resultId, call.threadId, call.id, call.ordinal, status, json.encodeToString(StatusSnapshot(code))))
        state.saveToolCall(call.copy(state = when (actionStatus) {
            AgentActionStatus.DENIED -> AgentToolCallState.DENIED
            AgentActionStatus.SUCCEEDED -> AgentToolCallState.COMPLETED
            else -> AgentToolCallState.FAILED
        }))
        state.saveAction(action.copy(toolResultIds = listOf(resultId), status = actionStatus))
    }

    private suspend fun awaitConfirmation(call: AgentToolCall, action: AgentAction, previewJson: String): AgentRunResult.AwaitingConfirmation {
        state.saveToolCall(call.copy(state = AgentToolCallState.WAITING_CONFIRMATION, previewJson = previewJson))
        state.saveAction(action.copy(
            permissionDecision = AgentPermissionMode.REQUIRE_CONFIRMATION,
            confirmationReference = call.id.value,
            status = AgentActionStatus.WAITING_CONFIRMATION,
        ))
        return AgentRunResult.AwaitingConfirmation(call.id, previewJson)
    }

    private suspend fun finishOutcome(call: AgentToolCall, action: AgentAction, outcome: AgentToolOutcome<*>) {
        val status = when (outcome) {
            is AgentToolOutcome.Success -> AgentToolResultStatus.SUCCESS
            is AgentToolOutcome.InvalidInput -> AgentToolResultStatus.INVALID_INPUT
            AgentToolOutcome.NotFound -> AgentToolResultStatus.NOT_FOUND
            is AgentToolOutcome.PermissionDenied -> AgentToolResultStatus.PERMISSION_DENIED
            is AgentToolOutcome.ConfirmationRequired -> AgentToolResultStatus.INFRASTRUCTURE_FAILURE
            AgentToolOutcome.Stale -> AgentToolResultStatus.STALE
            is AgentToolOutcome.Unsupported -> AgentToolResultStatus.UNSUPPORTED
            is AgentToolOutcome.Conflict -> AgentToolResultStatus.CONFLICT
            is AgentToolOutcome.Infeasible -> AgentToolResultStatus.INFEASIBLE
            is AgentToolOutcome.InfrastructureFailure -> AgentToolResultStatus.INFRASTRUCTURE_FAILURE
        }
        val actionStatus = when (status) {
            AgentToolResultStatus.PERMISSION_DENIED -> AgentActionStatus.DENIED
            AgentToolResultStatus.STALE -> AgentActionStatus.STALE
            AgentToolResultStatus.SUCCESS -> AgentActionStatus.SUCCEEDED
            else -> AgentActionStatus.FAILED
        }
        val payload = when (outcome) {
            is AgentToolOutcome.InvalidInput -> OutcomeSnapshot("INVALID_INPUT", issues = outcome.issues.map { "${it.field}:${it.code}" })
            AgentToolOutcome.NotFound -> OutcomeSnapshot("NOT_FOUND")
            is AgentToolOutcome.PermissionDenied -> OutcomeSnapshot("PERMISSION_DENIED", code = outcome.capability.name)
            is AgentToolOutcome.ConfirmationRequired -> OutcomeSnapshot("CONFIRMATION_REQUIRED")
            AgentToolOutcome.Stale -> OutcomeSnapshot("STALE")
            is AgentToolOutcome.Unsupported -> OutcomeSnapshot("UNSUPPORTED", code = outcome.reasonCode)
            is AgentToolOutcome.Conflict -> OutcomeSnapshot("CONFLICT", ids = outcome.conflictIds)
            is AgentToolOutcome.Infeasible -> OutcomeSnapshot("INFEASIBLE", issues = outcome.reasonCodes)
            is AgentToolOutcome.InfrastructureFailure -> OutcomeSnapshot("INFRASTRUCTURE_FAILURE", code = outcome.redactedCode)
            is AgentToolOutcome.Success -> OutcomeSnapshot("SUCCESS")
        }
        finishResult(call, action, status, json.encodeToString(payload), actionStatus)
    }

    private suspend fun finishPlannerPreview(
        call: AgentToolCall, action: AgentAction, outcome: AgentToolOutcome<dev.agenticscheduler.application.planner.PlanBranch>,
    ) {
        when (outcome) {
            is AgentToolOutcome.Success -> {
                val branch = outcome.payload
                finishResult(
                    call,
                    action.copy(permissionDecision = AgentPermissionMode.ALLOW_DIRECT),
                    AgentToolResultStatus.SUCCESS,
                    json.encodeToString(branch.toSnapshot()),
                    AgentActionStatus.SUCCEEDED,
                    planBranchReference = branch.id.value,
                )
            }
            else -> finishOutcome(call, action.copy(permissionDecision = AgentPermissionMode.ALLOW_DIRECT), outcome)
        }
    }

    private suspend fun finishResult(
        call: AgentToolCall,
        action: AgentAction,
        status: AgentToolResultStatus,
        resultJson: String,
        actionStatus: AgentActionStatus,
        mutationIds: List<MutationId> = emptyList(),
        planBranchReference: String? = null,
    ) {
        val resultId = AgentToolResultId(ids.next())
        state.appendToolResult(AgentToolResult(resultId, call.threadId, call.id, call.ordinal, status, resultJson, mutationIds))
        state.saveToolCall(call.copy(state = when (actionStatus) {
            AgentActionStatus.DENIED -> AgentToolCallState.DENIED
            AgentActionStatus.SUCCEEDED -> AgentToolCallState.COMPLETED
            else -> AgentToolCallState.FAILED
        }))
        state.saveAction(action.copy(
            toolResultIds = listOf(resultId), mutationIds = mutationIds, status = actionStatus,
            planBranchReference = planBranchReference ?: action.planBranchReference,
        ))
    }

    private fun outcomeCode(outcome: AgentToolOutcome<*>): String = when (outcome) {
        is AgentToolOutcome.InvalidInput -> "INVALID_INPUT"
        AgentToolOutcome.NotFound -> "NOT_FOUND"
        is AgentToolOutcome.PermissionDenied -> "PERMISSION_DENIED"
        is AgentToolOutcome.ConfirmationRequired -> "CONFIRMATION_REQUIRED"
        AgentToolOutcome.Stale -> "STALE"
        is AgentToolOutcome.Unsupported -> "UNSUPPORTED"
        is AgentToolOutcome.Conflict -> "CONFLICT"
        is AgentToolOutcome.Infeasible -> "INFEASIBLE"
        is AgentToolOutcome.InfrastructureFailure -> outcome.redactedCode
        is AgentToolOutcome.Success -> "SUCCESS"
    }

    private fun dev.agenticscheduler.application.planner.PlanBranch.toSnapshot() = PlanBranchSnapshot(
        id = id.value,
        status = status.name,
        mutations = mutations.map { mutation -> when (mutation) {
            is dev.agenticscheduler.planner.FocusBlockMutation.Create -> PlannerMutationSnapshot(
                "CREATE", mutation.taskId.value, null, mutation.draft.time.start.toString(), mutation.draft.time.endExclusive.toString(), mutation.draft.time.timeZone.id,
            )
            is dev.agenticscheduler.planner.FocusBlockMutation.Move -> PlannerMutationSnapshot(
                "MOVE", mutation.taskId.value, mutation.id.value, mutation.time.start.toString(), mutation.time.endExclusive.toString(), mutation.time.timeZone.id,
            )
            is dev.agenticscheduler.planner.FocusBlockMutation.Resize -> PlannerMutationSnapshot(
                "RESIZE", mutation.taskId.value, mutation.id.value, mutation.time.start.toString(), mutation.time.endExclusive.toString(), mutation.time.timeZone.id,
            )
            is dev.agenticscheduler.planner.FocusBlockMutation.Delete -> PlannerMutationSnapshot(
                "DELETE", mutation.taskId.value, mutation.id.value, null, null, null,
            )
        } },
        issues = issues.map(::plannerIssueCode),
        explanations = explanations.map { explanation -> PlannerExplanationSnapshot(
            explanation.taskId.value,
            when (val mutation = explanation.mutation) {
                is dev.agenticscheduler.planner.FocusBlockMutation.Create -> "CREATE"
                is dev.agenticscheduler.planner.FocusBlockMutation.Move -> "MOVE"
                is dev.agenticscheduler.planner.FocusBlockMutation.Resize -> "RESIZE"
                is dev.agenticscheduler.planner.FocusBlockMutation.Delete -> "DELETE"
            },
            explanation.criteria.map { it.name },
        ) },
    )
    private fun dev.agenticscheduler.application.planner.PlanBranch.toSnapshotJson(): String = json.encodeToString(toSnapshot())

    private fun plannerIssueCode(issue: dev.agenticscheduler.planner.PlannerIssue): String = when (issue) {
        dev.agenticscheduler.planner.PlannerIssue.ProfileUnconfigured -> "PROFILE_UNCONFIGURED"
        is dev.agenticscheduler.planner.PlannerIssue.InvalidSnapshot -> "INVALID_SNAPSHOT"
        is dev.agenticscheduler.planner.PlannerIssue.TimeResolutionFailure -> "TIME_RESOLUTION_FAILURE"
        is dev.agenticscheduler.planner.PlannerIssue.UnknownRemainingEffort -> "UNKNOWN_REMAINING_EFFORT"
        is dev.agenticscheduler.planner.PlannerIssue.NoLegalAvailability -> "NO_LEGAL_AVAILABILITY"
        is dev.agenticscheduler.planner.PlannerIssue.DependencyBlocked -> "DEPENDENCY_BLOCKED"
        is dev.agenticscheduler.planner.PlannerIssue.OverflowApprovalRequired -> "OVERFLOW_APPROVAL_REQUIRED"
        is dev.agenticscheduler.planner.PlannerIssue.HardDeadlineShortfall -> "HARD_DEADLINE_SHORTFALL"
        is dev.agenticscheduler.planner.PlannerIssue.UnscheduledEffort -> "UNSCHEDULED_EFFORT"
        is dev.agenticscheduler.planner.PlannerIssue.OverallocatedPlannedEffort -> "OVERALLOCATED_PLANNED_EFFORT"
        is dev.agenticscheduler.planner.PlannerIssue.ImmovableConflict -> "IMMOVABLE_CONFLICT"
    }

    private suspend fun selectedConfig(): ProviderConfig? = state.selectedProviderConfigId()?.let { state.providerConfig(it) }
    private fun supportedTools() = buildList {
        add(ProviderToolDefinition(AgentToolNames.TASK_GET, "Read one Task by immutable ID", taskGetSchema))
        if (calendarList != null) add(ProviderToolDefinition(AgentToolNames.CALENDAR_LIST, "Read calendar items in explicit date window and display timezone", calendarSchema))
        if (taskList != null) add(ProviderToolDefinition(AgentToolNames.TASK_LIST, "List Tasks with explicit optional status", taskListSchema))
        if (historyReads != null) add(ProviderToolDefinition(AgentToolNames.HISTORY_TIMELINE, "Read authoritative mutation history", historyTimelineSchema))
        if (historyReads != null) add(ProviderToolDefinition(AgentToolNames.HISTORY_GET_MUTATION, "Read one authoritative MutationId", historyMutationSchema))
        if (historyReads != null) add(ProviderToolDefinition(AgentToolNames.HISTORY_GET_ENTITY_CHANGES, "Read one entity ChangeLog", historyEntitySchema))
        add(ProviderToolDefinition(AgentToolNames.TASK_CREATE, "Propose a Task with explicit values", taskCreateSchema))
        if (taskUpdate != null) add(ProviderToolDefinition(AgentToolNames.TASK_UPDATE, "Propose a Task update with full source facts", taskUpdateSchema))
        if (eventCreate != null) add(ProviderToolDefinition(EVENT_CREATE_TOOL_NAME, "Propose a typed Event creation", eventCreateSchema))
        if (eventUpdate != null) add(ProviderToolDefinition(EVENT_UPDATE_TOOL_NAME, "Propose a typed Event update", eventUpdateSchema))
        if (plannerFullReplan != null) add(ProviderToolDefinition(PlannerToolNames.PREVIEW_FULL_REPLAN, "Preview a deterministic full replan", plannerFullReplanSchema))
        if (plannerLocalReflow != null) add(ProviderToolDefinition(PlannerToolNames.PREVIEW_LOCAL_REFLOW, "Preview a deterministic local reflow", plannerLocalReflowSchema))
        if (historyUndo != null) add(ProviderToolDefinition(AgentToolNames.HISTORY_UNDO, "Propose a compensating Undo for one mutation", historyUndoSchema))
        if (planningProfileUpdate != null) add(ProviderToolDefinition(PLANNING_PROFILE_UPDATE_TOOL_NAME, "Propose a typed PlanningProfile update", planningProfileUpdateSchema))
        if (plannerApplyBranch != null) add(ProviderToolDefinition(PLANNER_APPLY_BRANCH_TOOL_NAME, "Apply a previously previewed PlanBranch", plannerApplyBranchSchema))
    }

    private fun group(messages: List<ProviderChatMessage>): List<Pair<Int, List<ProviderChatMessage>>> = buildList {
        var index = 0
        while (index < messages.size) {
            val count = if (messages[index].role == "assistant") 1 + messages[index].toolCalls.orEmpty().size else 1
            add(index to messages.subList(index, minOf(index + count, messages.size)))
            index += count
        }
    }
    private fun schema(value: String): JsonObject = Json.parseToJsonElement(value).jsonObject

    @Serializable private data class TaskGetInput(val taskId: String)
    @Serializable private data class CalendarInput(val startDate: String, val endDateExclusive: String, val displayTimeZone: String)
    @Serializable private data class CalendarSnapshot(
        val items: List<CalendarItemSnapshot>, val conflicts: List<CalendarConflictSnapshot>, val issues: List<CalendarIssueSnapshot>,
    )
    @Serializable private data class CalendarItemSnapshot(
        val source: CalendarSourceSnapshot, val title: String, val timeKind: String,
        val start: String, val endExclusive: String?, val timeZone: String?,
    )
    @Serializable private data class CalendarSourceSnapshot(val kind: String, val id: String, val academicWeekNumber: Int? = null)
    @Serializable private data class CalendarConflictSnapshot(val first: CalendarSourceSnapshot, val second: CalendarSourceSnapshot)
    @Serializable private data class CalendarIssueSnapshot(val code: String, val reference: String, val detail: String)
    private fun CalendarProjectionResult.toSnapshot() = CalendarSnapshot(
        items.map { item -> when (item) {
            is CalendarItem.Zoned -> CalendarItemSnapshot(item.source.toSnapshot(), item.title, "ZONED", item.originalRange.start.toString(), item.originalRange.endExclusive.toString(), item.originalRange.timeZone.id)
            is CalendarItem.AllDay -> CalendarItemSnapshot(item.source.toSnapshot(), item.title, "ALL_DAY", item.range.startDate.toString(), item.range.endDateExclusive.toString(), null)
            is CalendarItem.Floating -> CalendarItemSnapshot(item.source.toSnapshot(), item.title, "FLOATING", item.range.start.toString(), item.range.endExclusive.toString(), null)
            is CalendarItem.DateOnly -> CalendarItemSnapshot(item.source.toSnapshot(), item.title, "DATE_ONLY", item.date.toString(), null, null)
        } },
        conflicts.map { CalendarConflictSnapshot(it.first.toSnapshot(), it.second.toSnapshot()) },
        issues.map { issue -> when (issue) {
            is CalendarProjectionIssue.AcademicResolution -> CalendarIssueSnapshot("ACADEMIC_RESOLUTION", issue.courseId.value, issue.issue.toString())
            is CalendarProjectionIssue.MissingSemester -> CalendarIssueSnapshot("MISSING_SEMESTER", issue.courseId.value, issue.semesterId.value)
            is CalendarProjectionIssue.SyncConflictUnprojectable -> CalendarIssueSnapshot("SYNC_CONFLICT_UNPROJECTABLE", issue.conflictIds.joinToString(","), issue.reason)
        } },
    )
    private fun CalendarSourceRef.toSnapshot(): CalendarSourceSnapshot = when (this) {
        is CalendarSourceRef.Event -> CalendarSourceSnapshot("EVENT", id.value)
        is CalendarSourceRef.FocusBlock -> CalendarSourceSnapshot("FOCUS_BLOCK", id.value)
        is CalendarSourceRef.Exam -> CalendarSourceSnapshot("EXAM", id.value)
        is CalendarSourceRef.CourseSession -> CalendarSourceSnapshot("COURSE_SESSION", key.scheduleRuleId.value, key.academicWeekNumber.value)
    }
    /**
     * DeepSeek Flash has emitted the literal string `"null"` for this nullable,
     * read-only filter despite receiving a schema that calls for JSON null. Normalize
     * only that exact interop defect before typed decoding. The raw provider arguments
     * remain in the immutable ToolCall audit record; every other value stays strict.
     */
    private fun decodeTaskListInput(argumentsJson: String): TaskListInput? = runCatching {
        val root = json.parseToJsonElement(argumentsJson).jsonObject
        val normalized = root["status"]
            ?.takeIf { value -> value is JsonPrimitive && value.isString && value.content == "null" }
            ?.let { JsonObject(root.toMutableMap().apply { put("status", JsonNull) }) }
            ?: root
        json.decodeFromJsonElement(TaskListInput.serializer(), normalized)
    }.getOrNull()

    @Serializable private data class TaskListInput(val status: String? = null)
    @Serializable private data class TimelineInput(val limit: Int?)
    @Serializable private data class MutationInput(val mutationId: String)
    @Serializable private data class EntityChangesInput(val entityKind: String, val entityId: String, val limit: Int?)
    @Serializable private data class HistoryEntry(val operation: SyncOperation, val committedAtEpochMillis: Long)
    @Serializable private data class ChangeEntry(
        val mutationId: String, val ordinal: Int, val entityKind: String, val entityId: String,
        val operationKind: String, val beforeImageJson: String?, val afterImageJson: String?,
        val hlcPhysicalMillis: Long, val hlcLogical: Long, val hlcReplicaId: String,
    )
    private fun HistoryChange.toSnapshot() = ChangeEntry(
        mutationId, ordinal, entityKind.name, entityId, operationKind, beforeImageJson, afterImageJson,
        hlc.physicalMillis, hlc.logical, hlc.replicaId.value,
    )
    @Serializable private data class TaskReadSnapshot(
        val id: String, val title: String, val status: String, val priority: String,
        val estimated: String?, val completed: String, val remaining: String?,
        val deadline: DeadlineSnapshot?,
    )
    @Serializable private data class DeadlineSnapshot(
        val kind: String, val at: String?, val date: String?, val timeZone: String?,
        val policy: String, val overflowPolicy: String,
    )
    private fun Task.toSnapshot(): TaskReadSnapshot = TaskReadSnapshot(
        id.value, title, status.name, priority.name,
        effort.estimated?.toIsoString(), effort.completed.toIsoString(), effort.remaining?.toIsoString(),
        deadline?.let { value -> when (val date = value.deadline) {
            is Deadline.Exact -> DeadlineSnapshot("EXACT", date.at.toString(), null, date.timeZone.id, value.policy.name, value.overflowPolicy.name)
            is Deadline.DateOnly -> DeadlineSnapshot("DATE_ONLY", null, date.date.toString(), null, value.policy.name, value.overflowPolicy.name)
        } },
    )
    @Serializable private data class TaskCommitSnapshot(val taskId: String, val mutationId: String)
    @Serializable private data class EventCommitSnapshot(val eventId: String, val mutationId: String)
    @Serializable private data class UndoCommitSnapshot(val originalMutationId: String, val mutationId: String)
    @Serializable private data class ProfileCommitSnapshot(val planningProfileId: String, val name: String, val mutationId: String)
    @Serializable private data class PlannerApplyCommitSnapshot(val planBranchId: String, val status: String, val mutationId: String?)
    @Serializable private data class OutcomeSnapshot(
        val status: String,
        val code: String? = null,
        val issues: List<String> = emptyList(),
        val ids: List<String> = emptyList(),
    )
    @Serializable private data class PlanBranchSnapshot(
        val id: String,
        val status: String,
        val mutations: List<PlannerMutationSnapshot>,
        val issues: List<String>,
        val explanations: List<PlannerExplanationSnapshot>,
    )
    @Serializable private data class PlannerMutationSnapshot(
        val kind: String, val taskId: String, val focusBlockId: String?,
        val start: String?, val endExclusive: String?, val timeZone: String?,
    )
    @Serializable private data class PlannerExplanationSnapshot(
        val taskId: String, val mutationKind: String, val criteria: List<String>,
    )
    @Serializable private data class StatusSnapshot(val status: String)
}

/** Stable, content-free diagnostics for response shapes the bounded runtime cannot execute. */
internal fun invalidProviderToolCallCode(calls: List<ProviderToolCall>): String? = when {
    calls.size > 1 -> "MULTIPLE_TOOL_CALLS_UNSUPPORTED"
    calls.any { it.id.isBlank() || it.function.name.isBlank() || it.function.arguments.isBlank() } ->
        "INVALID_TOOL_CALL_FIELDS"
    else -> null
}

/** Serializes the complete orchestration operation for one thread, without blocking other threads. */
internal class ThreadOperationLocks {
    private data class Entry(val mutex: Mutex, var users: Int)

    private val entriesMutex = Mutex()
    private val entries = mutableMapOf<AgentThreadId, Entry>()

    suspend fun <T> withLock(threadId: AgentThreadId, operation: suspend () -> T): T {
        val entry = entriesMutex.withLock {
            (entries[threadId] ?: Entry(Mutex(), 0).also { entries[threadId] = it }).also { it.users++ }
        }
        var acquired = false
        try {
            entry.mutex.lock()
            acquired = true
            return operation()
        } finally {
            if (acquired) entry.mutex.unlock()
            withContext(NonCancellable) {
                entriesMutex.withLock {
                    entry.users--
                    if (entry.users == 0 && entries[threadId] === entry) entries.remove(threadId)
                }
            }
        }
    }
}
