package dev.agenticscheduler.agent

import kotlinx.serialization.Serializable

@Serializable
data class AgentMessageV1(val role: String, val content: String) {
    init { require(role in setOf("system", "user", "assistant", "tool")) }
}

@Serializable
data class AgentToolCallV1(val id: String, val name: String, val argumentsJson: String)

@Serializable
data class AgentToolResultV1(
    val toolCallId: String,
    val name: String,
    val resultJson: String,
    val isError: Boolean = false,
)

@Serializable
data class AgentTurnRequestV1(
    val runId: String,
    val messages: List<AgentMessageV1>,
    val contextJson: String = "{}",
    val toolResults: List<AgentToolResultV1> = emptyList(),
) {
    init { require(runId.isNotBlank()); require(messages.isNotEmpty()); require(contextJson.isNotBlank()) }
}

@Serializable
data class AgentTurnResponseV1(
    val runId: String,
    val assistantText: String? = null,
    val toolCalls: List<AgentToolCallV1> = emptyList(),
    val finishReason: String? = null,
)

@Serializable
data class AgentHealthResponseV1(val status: String, val model: String)

@Serializable
data class AgentErrorResponseV1(val code: String)

data class AgentToolDefinitionV1(
    val name: String,
    val description: String,
    val inputSchemaJson: String,
    val readOnly: Boolean,
)

interface AgentModelClient {
    suspend fun turn(request: AgentTurnRequestV1, tools: List<AgentToolDefinitionV1>): AgentTurnResponseV1
}

fun interface AgentToolExecutor {
    suspend fun execute(call: AgentToolCallV1): AgentToolResultV1
}

data class AgentRunResult(
    val response: AgentTurnResponseV1,
    val toolResults: List<AgentToolResultV1>,
    val steps: Int,
)

class AgentRunLoop(
    private val model: AgentModelClient,
    private val tools: List<AgentToolDefinitionV1>,
    private val executor: AgentToolExecutor,
    private val maxSteps: Int = 8,
) {
    init { require(maxSteps > 0) }

    suspend fun run(initial: AgentTurnRequestV1): AgentRunResult {
        var request = initial
        val results = mutableListOf<AgentToolResultV1>()
        repeat(maxSteps) { step ->
            val response = model.turn(request, tools)
            if (response.toolCalls.isEmpty()) return AgentRunResult(response, results.toList(), step + 1)
            response.toolCalls.forEach { call ->
                val known = tools.any { it.name == call.name }
                val result = if (!known) {
                    AgentToolResultV1(call.id, call.name, "{\"code\":\"UNKNOWN_TOOL\"}", isError = true)
                } else {
                    try {
                        executor.execute(call)
                    } catch (_: Throwable) {
                        AgentToolResultV1(call.id, call.name, "{\"code\":\"TOOL_FAILURE\"}", isError = true)
                    }
                }
                results += result
            }
            request = request.copy(toolResults = results.toList())
        }
        return AgentRunResult(
            AgentTurnResponseV1(initial.runId, finishReason = "MAX_TOOL_STEPS"),
            results.toList(),
            maxSteps,
        )
    }
}

object GeneralSchedulerSkillV1 {
    val tools = listOf(
        AgentToolDefinitionV1("calendar.list", "List schedule items for an explicit date window.", "{\"type\":\"object\",\"required\":[\"start\",\"end\"]}", true),
        AgentToolDefinitionV1("task.get", "Read one Task by explicit ID.", "{\"type\":\"object\",\"required\":[\"taskId\"]}", true),
        AgentToolDefinitionV1("task.list", "List current Tasks using an explicit status filter.", "{\"type\":\"object\",\"properties\":{\"status\":{\"type\":\"string\"}}}", true),
        AgentToolDefinitionV1("event.create", "Create an Event after confirmation.", "{\"type\":\"object\",\"required\":[\"title\",\"time\",\"flexibility\",\"pinState\"]}", false),
        AgentToolDefinitionV1("event.update", "Update an Event after confirmation.", "{\"type\":\"object\",\"required\":[\"id\",\"title\",\"time\",\"flexibility\",\"pinState\"]}", false),
        AgentToolDefinitionV1("task.create", "Create a Task after confirmation.", "{\"type\":\"object\",\"required\":[\"title\",\"priority\",\"estimatedMinutes\",\"remainingMinutes\"]}", false),
        AgentToolDefinitionV1("task.update", "Update a Task after confirmation.", "{\"type\":\"object\",\"required\":[\"id\",\"title\",\"status\",\"priority\",\"completedMinutes\",\"remainingMinutes\"]}", false),
        AgentToolDefinitionV1("planner.previewFullReplan", "Preview a deterministic full replan with explicit profile and horizon.", "{\"type\":\"object\",\"required\":[\"profileId\",\"referenceNow\",\"horizonDays\"]}", true),
        AgentToolDefinitionV1("planner.applyBranch", "Apply a previously previewed PlanBranch after confirmation.", "{\"type\":\"object\",\"required\":[\"branchId\",\"applyNow\"]}", false),
        AgentToolDefinitionV1("history.timeline", "Read the authoritative mutation timeline.", "{\"type\":\"object\"}", true),
    )
}
