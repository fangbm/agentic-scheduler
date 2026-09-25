package dev.agenticscheduler.agent.provider

import dev.agenticscheduler.agent.history.ProviderConfig
import dev.agenticscheduler.application.sync.PlatformSecretStore
import dev.agenticscheduler.application.sync.SecretReference
import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.preparePost
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.coroutines.CancellationException
import io.ktor.utils.io.readUTF8Line

@Serializable
data class ProviderFunctionCall(val name: String, val arguments: String)

@Serializable
data class ProviderToolCall(val id: String, val type: String = "function", val function: ProviderFunctionCall)

@Serializable
data class ProviderChatMessage(
    val role: String,
    val content: String? = null,
    @SerialName("tool_call_id") val toolCallId: String? = null,
    @SerialName("tool_calls") val toolCalls: List<ProviderToolCall>? = null,
)

data class ProviderToolDefinition(val name: String, val description: String, val parameters: JsonObject)

sealed interface ProviderCallResult {
    data class Success(val message: ProviderChatMessage) : ProviderCallResult
    /** Stable code only; neither response body nor credential is exposed to Agent history. */
    data class Failure(val redactedCode: String) : ProviderCallResult
}

sealed interface ProviderProbeResult {
    data object Supported : ProviderProbeResult
    data object Unsupported : ProviderProbeResult
    data class Unavailable(val redactedCode: String) : ProviderProbeResult
}

fun interface ProviderCredentialResolver { suspend fun resolve(reference: SecretReference): String? }

/** Copies a credential only for the HTTP request; never persists it in Agent state. */
class SecretStoreProviderCredentialResolver(private val secrets: PlatformSecretStore) : ProviderCredentialResolver {
    override suspend fun resolve(reference: SecretReference): String? {
        val raw = secrets.readSecret(reference)?.copyRawSecretBytesForSecureStore() ?: return null
        return try { raw.decodeToString() } finally { raw.fill(0) }
    }
}

/** Strict chat-completions profile. Tool names/results remain application-owned. */
class OpenAiCompatibleProvider(
    private val client: HttpClient,
    private val credentials: ProviderCredentialResolver,
) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; explicitNulls = false }

    suspend fun probe(config: ProviderConfig): ProviderProbeResult {
        if (!config.toolCallingSupported) return ProviderProbeResult.Unsupported
        val probe = ProviderToolDefinition("d9_capability_probe", "Return a structured function call.", JsonObject(emptyMap()))
        return when (val result = request(
            config,
            listOf(ProviderChatMessage("user", "Call d9_capability_probe now.")),
            listOf(probe),
            toolChoice = "required",
        )) {
            is ProviderCallResult.Failure -> ProviderProbeResult.Unavailable(result.redactedCode)
            is ProviderCallResult.Success -> if (result.message.toolCalls?.singleOrNull()?.let { call ->
                call.type == "function" && call.function.name == probe.name &&
                    runCatching { json.parseToJsonElement(call.function.arguments) is JsonObject }.getOrDefault(false)
            } == true) ProviderProbeResult.Supported else ProviderProbeResult.Unsupported
        }
    }

    suspend fun complete(
        config: ProviderConfig,
        messages: List<ProviderChatMessage>,
        tools: List<ProviderToolDefinition>,
    ): ProviderCallResult = request(config, messages, tools, toolChoice = null, streaming = config.streamingSupported)

    private suspend fun request(
        config: ProviderConfig,
        messages: List<ProviderChatMessage>,
        tools: List<ProviderToolDefinition>,
        toolChoice: String?,
        streaming: Boolean = false,
    ): ProviderCallResult {
        if (messages.isEmpty()) return ProviderCallResult.Failure("EMPTY_TRANSCRIPT")
        val credential = try {
            config.credentialReference?.let { credentials.resolve(it) }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return ProviderCallResult.Failure("CREDENTIAL_FAILURE")
        }
        if (config.credentialReference != null && credential == null) return ProviderCallResult.Failure("MISSING_CREDENTIAL")
        val payload = ChatRequest(
            model = config.model,
            messages = messages,
            tools = tools.map { ApiTool(it.name, it.description, it.parameters) }.takeIf { it.isNotEmpty() },
            toolChoice = toolChoice,
            stream = streaming,
        )
        return try {
            if (streaming) return client.preparePost(config.baseUrl.trimEnd('/') + "/chat/completions") {
                contentType(ContentType.Application.Json)
                header(HttpHeaders.Accept, "text/event-stream")
                credential?.let { header(HttpHeaders.Authorization, "Bearer $it") }
                setBody(json.encodeToString(ChatRequest.serializer(), payload))
            }.execute { response ->
                if (response.status != HttpStatusCode.OK) ProviderCallResult.Failure("HTTP_${response.status.value}")
                else readStream(response.bodyAsChannel())
            }
            val response = client.post(config.baseUrl.trimEnd('/') + "/chat/completions") {
                contentType(ContentType.Application.Json)
                credential?.let { header(HttpHeaders.Authorization, "Bearer $it") }
                setBody(json.encodeToString(ChatRequest.serializer(), payload))
            }
            if (response.status != HttpStatusCode.OK) return ProviderCallResult.Failure("HTTP_${response.status.value}")
            val choice = json.decodeFromString(ChatResponse.serializer(), response.bodyAsText()).choices.firstOrNull()
                ?: return ProviderCallResult.Failure("EMPTY_RESPONSE")
            val message = choice.message
            if (message.role != "assistant") return ProviderCallResult.Failure("INVALID_ROLE")
            if (message.toolCalls.orEmpty().any { it.id.isBlank() || it.type != "function" || it.function.name.isBlank() }) {
                return ProviderCallResult.Failure("INVALID_TOOL_CALL")
            }
            ProviderCallResult.Success(message)
        } catch (_: SerializationException) {
            ProviderCallResult.Failure("INVALID_RESPONSE")
        } catch (_: IllegalArgumentException) {
            ProviderCallResult.Failure("INVALID_RESPONSE")
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            ProviderCallResult.Failure("NETWORK_FAILURE")
        }
    }

    private suspend fun readStream(channel: io.ktor.utils.io.ByteReadChannel): ProviderCallResult {
        val content = StringBuilder()
        val calls = linkedMapOf<Int, PartialCall>()
        val eventLines = mutableListOf<String>()
        var seenChoice = false
        var bytes = 0
        while (true) {
            val line = channel.readUTF8Line(65_536) ?: break
            bytes += line.length
            if (bytes > 1_048_576) return ProviderCallResult.Failure("STREAM_TOO_LARGE")
            if (line.isNotEmpty()) {
                if (line.startsWith("data:")) eventLines += line.removePrefix("data:").trimStart()
                continue
            }
            if (eventLines.isEmpty()) continue
            val data = eventLines.joinToString("\n")
            eventLines.clear()
            if (data == "[DONE]") break
            val delta = json.decodeFromString(StreamChunk.serializer(), data).choices.firstOrNull()?.delta ?: continue
            seenChoice = true
            delta.content?.let(content::append)
            delta.toolCalls.orEmpty().forEach { piece ->
                if (piece.index < 0) return ProviderCallResult.Failure("INVALID_TOOL_CALL")
                val call = calls.getOrPut(piece.index) { PartialCall() }
                piece.id?.let { call.id.append(it) }
                piece.type?.let { call.type = it }
                piece.function?.name?.let { call.name.append(it) }
                piece.function?.arguments?.let { call.arguments.append(it) }
            }
        }
        if (!seenChoice) return ProviderCallResult.Failure("EMPTY_RESPONSE")
        val completedCalls = calls.toSortedMap().values.map { call ->
            if (call.id.isEmpty() || call.name.isEmpty() || call.type != "function") return ProviderCallResult.Failure("INVALID_TOOL_CALL")
            ProviderToolCall(call.id.toString(), function = ProviderFunctionCall(call.name.toString(), call.arguments.toString()))
        }
        return ProviderCallResult.Success(ProviderChatMessage("assistant", content.toString().ifEmpty { null }, toolCalls = completedCalls.ifEmpty { null }))
    }

    @Serializable private data class ChatRequest(
        val model: String,
        val messages: List<ProviderChatMessage>,
        val tools: List<ApiTool>? = null,
        @SerialName("tool_choice") val toolChoice: String? = null,
        val stream: Boolean = false,
    )

    @Serializable private data class ApiTool(val type: String = "function", val function: ApiFunction) {
        constructor(name: String, description: String, parameters: JsonObject) : this("function", ApiFunction(name, description, parameters))
    }

    @Serializable private data class ApiFunction(val name: String, val description: String, val parameters: JsonObject)
    @Serializable private data class ChatResponse(val choices: List<ChatChoice>)
    @Serializable private data class ChatChoice(val message: ProviderChatMessage)
    @Serializable private data class StreamChunk(val choices: List<StreamChoice>)
    @Serializable private data class StreamChoice(val delta: StreamDelta)
    @Serializable private data class StreamDelta(
        val content: String? = null,
        @SerialName("tool_calls") val toolCalls: List<StreamToolCall>? = null,
    )
    @Serializable private data class StreamToolCall(
        val index: Int,
        val id: String? = null,
        val type: String? = null,
        val function: StreamFunction? = null,
    )
    @Serializable private data class StreamFunction(val name: String? = null, val arguments: String? = null)
    private class PartialCall {
        val id = StringBuilder()
        var type = "function"
        val name = StringBuilder()
        val arguments = StringBuilder()
    }
}
