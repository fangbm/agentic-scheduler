package dev.agenticscheduler.desktop

import dev.agenticscheduler.agent.AgentToolCallV1
import dev.agenticscheduler.agent.AgentToolExecutor
import dev.agenticscheduler.agent.AgentToolResultV1
import dev.agenticscheduler.application.persistence.TaskRepository
import dev.agenticscheduler.application.editing.CreateTaskInput
import dev.agenticscheduler.application.editing.EditingResult
import dev.agenticscheduler.application.editing.TaskEditingService
import dev.agenticscheduler.domain.task.TaskPriority
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.time.Duration.Companion.minutes

class DesktopAgentToolExecutor(
    private val tasks: TaskRepository,
    private val taskEditor: TaskEditingService,
) : AgentToolExecutor {
    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun execute(call: AgentToolCallV1): AgentToolResultV1 = when (call.name) {
        "task.list" -> {
            val value = buildJsonObject {
                put("tasks", buildJsonArray {
                    tasks.observeTasks().first().forEach { task ->
                        add(buildJsonObject {
                            put("id", task.id.value)
                            put("title", task.title)
                            put("status", task.status.name)
                            put("priority", task.priority.name)
                        })
                    }
                })
            }
            AgentToolResultV1(call.id, call.name, value.toString())
        }
        "task.get" -> {
            val id = json.parseToJsonElement(call.argumentsJson).jsonObject["taskId"]?.jsonPrimitive?.contentOrNull
                ?: return AgentToolResultV1(call.id, call.name, "{\"code\":\"INVALID_INPUT\"}", isError = true)
            val task = tasks.getTask(dev.agenticscheduler.domain.id.TaskId(id))
                ?: return AgentToolResultV1(call.id, call.name, "{\"code\":\"NOT_FOUND\"}", isError = true)
            AgentToolResultV1(call.id, call.name, "{\"id\":\"${task.id.value}\",\"title\":\"${task.title.replace("\"", "\\\"")}\",\"status\":\"${task.status.name}\"}")
        }
        else -> AgentToolResultV1(call.id, call.name, "{\"code\":\"CONFIRMATION_REQUIRED\"}", isError = true)
    }

    suspend fun executeConfirmed(call: AgentToolCallV1): AgentToolResultV1 {
        if (call.name != "task.create") return AgentToolResultV1(call.id, call.name, "{\"code\":\"UNSUPPORTED_CONFIRMED_TOOL\"}", isError = true)
        return try {
            val input = json.parseToJsonElement(call.argumentsJson).jsonObject
            val title = input["title"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
                ?: return AgentToolResultV1(call.id, call.name, "{\"code\":\"INVALID_INPUT\"}", isError = true)
            val priority = input["priority"]?.jsonPrimitive?.contentOrNull?.let(TaskPriority::valueOf)
                ?: return AgentToolResultV1(call.id, call.name, "{\"code\":\"INVALID_INPUT\"}", isError = true)
            val estimated = input["estimatedMinutes"]?.jsonPrimitive?.intOrNull
                ?: return AgentToolResultV1(call.id, call.name, "{\"code\":\"INVALID_INPUT\"}", isError = true)
            val remaining = input["remainingMinutes"]?.jsonPrimitive?.intOrNull
                ?: return AgentToolResultV1(call.id, call.name, "{\"code\":\"INVALID_INPUT\"}", isError = true)
            when (val result = taskEditor.create(CreateTaskInput(title, priority, estimated.minutes, remaining.minutes, deadline = null))) {
                is EditingResult.Success -> AgentToolResultV1(call.id, call.name, "{\"id\":\"${result.value.id.value}\",\"title\":\"${result.value.title.replace("\"", "\\\"")}\",\"status\":\"${result.value.status.name}\"}")
                is EditingResult.Invalid -> AgentToolResultV1(call.id, call.name, "{\"code\":\"INVALID_INPUT\"}", isError = true)
                EditingResult.NotFound -> AgentToolResultV1(call.id, call.name, "{\"code\":\"NOT_FOUND\"}", isError = true)
                is EditingResult.BlockedBySyncConflict -> AgentToolResultV1(call.id, call.name, "{\"code\":\"SYNC_CONFLICT\"}", isError = true)
            }
        } catch (_: Throwable) {
            AgentToolResultV1(call.id, call.name, "{\"code\":\"INVALID_INPUT\"}", isError = true)
        }
    }
}
