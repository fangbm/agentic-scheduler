package dev.agenticscheduler.agent.runtime

import dev.agenticscheduler.agent.context.ContextAssembler
import dev.agenticscheduler.agent.context.ContextCandidate
import dev.agenticscheduler.agent.context.ContextClass
import dev.agenticscheduler.agent.context.ContextRequest
import dev.agenticscheduler.agent.context.ContextAssemblyResult
import dev.agenticscheduler.agent.context.Utf8ByteBudgetMeter
import dev.agenticscheduler.agent.history.*
import dev.agenticscheduler.agent.permission.AgentPermissionMode
import dev.agenticscheduler.agent.tool.*
import dev.agenticscheduler.agent.provider.*
import dev.agenticscheduler.application.id.UuidV7Generator
import dev.agenticscheduler.application.editing.EditingResult
import dev.agenticscheduler.domain.id.TaskId
import dev.agenticscheduler.domain.planning.Deadline
import dev.agenticscheduler.domain.task.Task
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
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
) {
    private val json = Json { encodeDefaults = true; explicitNulls = true }
    private val transcripts = AgentTranscriptAssembler(state)
    private val system = "Use only listed typed tools for reads and writes. Prose is not a mutation. Current tool results outrank summaries. Never claim a write succeeded without a successful tool result."
    private val taskGetSchema = schema("""{"type":"object","properties":{"taskId":{"type":"string"}},"required":["taskId"],"additionalProperties":false}""")
    private val taskCreateSchema = schema("""{"type":"object","properties":{"title":{"type":"string"},"priority":{"type":"string","enum":["LOW","NORMAL","HIGH"]},"estimatedMinutes":{"type":["integer","null"]},"remainingMinutes":{"type":["integer","null"]},"deadline":{"type":["object","null"]}},"required":["title","priority","estimatedMinutes","remainingMinutes","deadline"],"additionalProperties":false}""")

    suspend fun createThread(): AgentThreadId = AgentThreadId(ids.next()).also { state.saveThread(AgentThread(it, null, clock.nowEpochMillis())) }

    suspend fun run(threadId: AgentThreadId, command: String): AgentRunResult {
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

    suspend fun confirm(threadId: AgentThreadId, callId: AgentToolCallId, approved: Boolean): AgentRunResult {
        val call = state.toolCalls(threadId).firstOrNull { it.id == callId && it.state == AgentToolCallState.WAITING_CONFIRMATION }
            ?: return AgentRunResult.Failed("CONFIRMATION_NOT_FOUND")
        val action = state.actions(threadId).firstOrNull { call.id in it.toolCallIds }
            ?: return AgentRunResult.Failed("ACTION_NOT_FOUND")
        if (!approved) {
            finishWithoutWrite(call, action, AgentToolResultStatus.PERMISSION_DENIED, "DENIED", AgentActionStatus.DENIED)
            return resumeAfterTool(threadId)
        }
        val policy = state.permissionPolicy()
        val prepared = taskCreate.prepare(call.argumentsJson, policy)
        val preview = when (prepared) {
            is AgentToolOutcome.ConfirmationRequired -> prepared.preview
            is AgentToolOutcome.Success -> prepared.payload
            else -> {
                finishWithoutWrite(call, action, AgentToolResultStatus.STALE, "PREVIEW_UNAVAILABLE", AgentActionStatus.STALE)
                return resumeAfterTool(threadId)
            }
        }
        if (taskCreate.normalizedPreviewJson(preview) != call.previewJson) {
            finishWithoutWrite(call, action, AgentToolResultStatus.STALE, "STALE_PREVIEW", AgentActionStatus.STALE)
            return resumeAfterTool(threadId)
        }
        executeTaskCreate(call, action, preview, true, policy)
        return resumeAfterTool(threadId)
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
        val budget = ContextAssembler(Utf8ByteBudgetMeter(config.maxContextUnits, config.reservedOutputUnits))
        val lastUser = transcript.indexOfLast { it.role == "user" }
        if (lastUser < 0) return AgentRunResult.Failed("NO_USER_MESSAGE")
        val groups = group(transcript)
        val currentGroup = groups.indexOfFirst { it.first <= lastUser && lastUser < it.first + it.second.size }
        val summary = state.summaries(threadId).maxWithOrNull(compareBy<ContextSummary>({ it.createdAtEpochMillis }, { it.id.value }))
        val candidates = groups.mapIndexedNotNull { index, (_, messages) ->
            if (index == currentGroup) null else ContextCandidate(index.toString(),
                if (messages.any { it.role == "tool" }) ContextClass.CURRENT_DOMAIN else ContextClass.RAW_MESSAGES,
                json.encodeToString(messages), index, index.toLong())
        } + summary?.let { value ->
            listOf(ContextCandidate("summary:${value.id.value}", ContextClass.SUMMARY, value.text, 0, value.createdAtEpochMillis))
        }.orEmpty()
        val assembled = budget.assemble(ContextRequest(system, tools.map { it.name to it.parameters.toString() }, transcript[lastUser].content.orEmpty(), null, candidates))
        if (assembled is ContextAssemblyResult.ContextTooLarge) return AgentRunResult.Failed("CONTEXT_TOO_LARGE")
        val selected = (assembled as ContextAssemblyResult.Ready).parts.map { it.id }.toSet()
        val messages = buildList {
            add(ProviderChatMessage("system", system))
            summary?.let { value ->
                if ("summary:${value.id.value}" in selected) add(ProviderChatMessage("user", "Earlier, potentially stale summary: ${value.text}"))
            }
            groups.forEachIndexed { index, pair -> if (index == currentGroup || index.toString() in selected) addAll(pair.second) }
        }
        return when (val response = provider.complete(config, messages, tools)) {
            is ProviderCallResult.Failure -> AgentRunResult.Failed(response.redactedCode)
            is ProviderCallResult.Success -> handleResponse(threadId, response.message, tools, allowLocalRead, config)
        }
    }

    private suspend fun handleResponse(threadId: AgentThreadId, response: ProviderChatMessage, tools: List<ProviderToolDefinition>, allowLocalRead: Boolean, config: ProviderConfig): AgentRunResult {
        val calls = response.toolCalls.orEmpty()
        if (calls.size > 1 || calls.any { it.id.isBlank() || it.function.name.isBlank() || it.function.arguments.isBlank() }) {
            return AgentRunResult.Failed("INVALID_TOOL_CALL")
        }
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
        if (call.name == AgentToolNames.TASK_GET && allowLocalRead) {
            if (state.permissionPolicy().modeFor(dev.agenticscheduler.agent.permission.AgentToolCapability.READ) != AgentPermissionMode.ALLOW_DIRECT) {
                finishWithoutWrite(call, action, AgentToolResultStatus.PERMISSION_DENIED, "READ_NOT_ALLOWED", AgentActionStatus.DENIED)
                return AgentRunResult.Failed("READ_NOT_ALLOWED")
            }
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

    private suspend fun selectedConfig(): ProviderConfig? = state.selectedProviderConfigId()?.let { state.providerConfig(it) }
    private fun supportedTools() = listOf(
        ProviderToolDefinition(AgentToolNames.TASK_GET, "Read one Task by immutable ID", taskGetSchema),
        ProviderToolDefinition(AgentToolNames.TASK_CREATE, "Propose a Task with explicit values", taskCreateSchema),
    )

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
    @Serializable private data class StatusSnapshot(val status: String)
}
