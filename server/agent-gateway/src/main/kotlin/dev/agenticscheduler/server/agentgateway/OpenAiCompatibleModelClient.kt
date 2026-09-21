package dev.agenticscheduler.server.agentgateway

import dev.agenticscheduler.agent.AgentMessageV1
import dev.agenticscheduler.agent.AgentModelClient
import dev.agenticscheduler.agent.AgentToolCallV1
import dev.agenticscheduler.agent.AgentToolDefinitionV1
import dev.agenticscheduler.agent.AgentTurnRequestV1
import dev.agenticscheduler.agent.AgentTurnResponseV1
import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

class OpenAiCompatibleModelClient(
    private val http: HttpClient,
    private val config: AgentGatewayConfig,
    private val json: Json = Json { ignoreUnknownKeys = true },
) : AgentModelClient {
    override suspend fun turn(request: AgentTurnRequestV1, tools: List<AgentToolDefinitionV1>): AgentTurnResponseV1 {
        val messages = buildJsonArray {
            request.messages.forEach { addMessage(it) }
            request.toolResults.forEach { result ->
                add(buildJsonObject {
                    put("role", "tool")
                    put("tool_call_id", result.toolCallId)
                    put("content", result.resultJson)
                })
            }
        }
        val body = json.encodeToString(JsonElement.serializer(), buildJsonObject {
            put("model", config.modelName)
            put("messages", messages)
            putJsonArray("tools") {
                tools.forEach { tool ->
                    add(buildJsonObject {
                        put("type", "function")
                        putJsonObject("function") {
                            put("name", tool.name)
                            put("description", tool.description)
                            put("parameters", json.parseToJsonElement(tool.inputSchemaJson))
                        }
                    })
                }
            }
        })
        val response = http.post("${config.modelBaseUrl}/chat/completions") {
            contentType(ContentType.Application.Json)
            config.modelApiKey?.let { header(HttpHeaders.Authorization, "Bearer $it") }
            setBody(body)
        }
        if (!response.status.value.toString().startsWith("2")) throw AgentGatewayException("MODEL_HTTP_${response.status.value}")
        return decodeResponse(request.runId, json.parseToJsonElement(response.bodyAsText()).jsonObject)
    }

    private fun kotlinx.serialization.json.JsonArrayBuilder.addMessage(message: AgentMessageV1) {
        add(buildJsonObject {
            put("role", message.role)
            put("content", message.content)
        })
    }

    private fun decodeResponse(runId: String, root: JsonObject): AgentTurnResponseV1 {
        val choice = root["choices"]?.jsonArray?.firstOrNull()?.jsonObject ?: throw AgentGatewayException("MODEL_MALFORMED_RESPONSE")
        val message = choice["message"]?.jsonObject ?: throw AgentGatewayException("MODEL_MALFORMED_RESPONSE")
        val calls = message["tool_calls"]?.jsonArray.orEmpty().map { call ->
            val value = call.jsonObject
            val function = value["function"]?.jsonObject ?: throw AgentGatewayException("MODEL_MALFORMED_TOOL_CALL")
            AgentToolCallV1(
                id = value["id"]?.jsonPrimitive?.content ?: throw AgentGatewayException("MODEL_MALFORMED_TOOL_CALL"),
                name = function["name"]?.jsonPrimitive?.content ?: throw AgentGatewayException("MODEL_MALFORMED_TOOL_CALL"),
                argumentsJson = function["arguments"]?.jsonPrimitive?.content ?: "{}",
            )
        }
        return AgentTurnResponseV1(
            runId = runId,
            assistantText = message["content"]?.jsonPrimitive?.contentOrNull,
            toolCalls = calls,
            finishReason = choice["finish_reason"]?.jsonPrimitive?.contentOrNull,
        )
    }
}

class AgentGatewayException(val code: String) : RuntimeException(code)
