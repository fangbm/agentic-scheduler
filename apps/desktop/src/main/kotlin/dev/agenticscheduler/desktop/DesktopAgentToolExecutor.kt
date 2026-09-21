package dev.agenticscheduler.desktop

import dev.agenticscheduler.agent.AgentToolCallV1
import dev.agenticscheduler.agent.AgentToolExecutor
import dev.agenticscheduler.agent.AgentToolResultV1
import dev.agenticscheduler.application.persistence.TaskRepository
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

class DesktopAgentToolExecutor(private val tasks: TaskRepository) : AgentToolExecutor {
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
}
