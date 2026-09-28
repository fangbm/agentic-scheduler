package dev.agenticscheduler.agent.provider

import dev.agenticscheduler.agent.history.ProviderConfig
import dev.agenticscheduler.agent.history.ProviderConfigId
import dev.agenticscheduler.application.sync.SecretReference
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class OpenAiCompatibleProviderTest {
    @Test fun `capability probe requires an actual structured call`() = runBlocking {
        val requests = mutableListOf<HttpRequestData>()
        val client = HttpClient(MockEngine) { engine { addHandler { request ->
            requests += request
            respond(
                """{"choices":[{"message":{"role":"assistant","tool_calls":[{"id":"probe-1","type":"function","function":{"name":"d9_64395f6361706162696c6974795f70726f6265","arguments":"{}"}}]}}]}""",
                headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
            )
        } } }
        val provider = OpenAiCompatibleProvider(client, ProviderCredentialResolver { "secret-token" })
        assertEquals(ProviderProbeResult.Supported, provider.probe(config()))
        assertEquals("Bearer secret-token", requests.single().headers[HttpHeaders.Authorization])
        val requestBody = (requests.single().body as TextContent).text
        assertFalse(requestBody.contains("\"tool_choice\""), "The capability probe must avoid tool_choice=required for providers that reject it in thinking mode")
        assertTrue(!requestBody.contains("secret-token"))
        val probeParameters = Json.parseToJsonElement(requestBody).jsonObject["tools"]!!.jsonArray.single()
            .jsonObject["function"]!!.jsonObject["parameters"]!!.jsonObject
        assertEquals("object", probeParameters["type"]!!.jsonPrimitive.content)
        assertEquals(0, probeParameters["properties"]!!.jsonObject.size)
        assertTrue(probeParameters["required"]!!.jsonArray.isEmpty())
        assertEquals("false", probeParameters["additionalProperties"]!!.jsonPrimitive.content)
        client.close()
    }

    @Test fun `assistant tool call and matching tool result stay in next request`() = runBlocking {
        val requests = mutableListOf<HttpRequestData>()
        val client = HttpClient(MockEngine) { engine { addHandler { request ->
            requests += request
            respond("""{"choices":[{"message":{"role":"assistant","content":"Done"}}]}""")
        } } }
        val provider = OpenAiCompatibleProvider(client, ProviderCredentialResolver { null })
        val call = ProviderToolCall("call-1", function = ProviderFunctionCall("task.get", "{\"taskId\":\"t\"}"))
        val result = provider.complete(config(credential = null), listOf(
            ProviderChatMessage("user", "Check my task"),
            ProviderChatMessage("assistant", toolCalls = listOf(call)),
            ProviderChatMessage("tool", "{\"title\":\"Read\"}", toolCallId = call.id),
        ), emptyList())
        assertEquals("Done", assertIs<ProviderCallResult.Success>(result).message.content)
        val body = (requests.single().body as TextContent).text
        assertTrue(body.contains("\"tool_calls\""))
        assertTrue(body.contains("\"tool_call_id\":\"call-1\""))
        val historyCallName = Json.parseToJsonElement(body).jsonObject["messages"]!!.jsonArray
            .single { it.jsonObject["role"]!!.jsonPrimitive.content == "assistant" }
            .jsonObject["tool_calls"]!!.jsonArray.single()
            .jsonObject["function"]!!.jsonObject["name"]!!.jsonPrimitive.content
        assertEquals("d9_7461736b2e676574", historyCallName, "Historical assistant Tool calls must be encoded before replay")
        assertFalse(Json.parseToJsonElement(body).jsonObject.containsKey("thinking"), "Generic compatible endpoints must not receive DeepSeek-only options")
        client.close()
    }

    @Test fun `unknown or canonical dotted provider response name fails closed`() = runBlocking {
        val client = HttpClient(MockEngine) { engine { addHandler {
            respond("""{"choices":[{"message":{"role":"assistant","tool_calls":[{"id":"call-1","type":"function","function":{"name":"task.list","arguments":"{}"}}]}}]}""")
        } } }
        val provider = OpenAiCompatibleProvider(client, ProviderCredentialResolver { null })

        val result = provider.complete(
            config(credential = null),
            listOf(ProviderChatMessage("user", "List tasks")),
            listOf(ProviderToolDefinition("task.list", "List tasks", JsonObject(emptyMap()))),
        )

        assertEquals(ProviderCallResult.Failure("INVALID_TOOL_CALL"), result)
        client.close()
    }

    @Test fun `provider wire tool names are identifier-safe and responses recover canonical names`() = runBlocking {
        val requests = mutableListOf<HttpRequestData>()
        val client = HttpClient(MockEngine) { engine { addHandler { request ->
            requests += request
            val requestBody = (request.body as TextContent).text
            val advertisedTools = Json.parseToJsonElement(requestBody)
                .jsonObject["tools"]!!.jsonArray
            val wireNames = advertisedTools.map { tool ->
                tool.jsonObject["function"]!!.jsonObject["name"]!!.jsonPrimitive.content
            }

            assertEquals(2, wireNames.toSet().size, "Distinct internal Tools must keep distinct wire names")
            assertTrue(wireNames.all { PROVIDER_FUNCTION_NAME.matches(it) }, "Provider function names must use the portable identifier alphabet")

            val taskListWireName = advertisedTools.single { tool ->
                tool.jsonObject["function"]!!.jsonObject["description"]!!.jsonPrimitive.content == "List tasks"
            }.jsonObject["function"]!!.jsonObject["name"]!!.jsonPrimitive.content
            respond(
                """{"choices":[{"message":{"role":"assistant","tool_calls":[{"id":"call-1","type":"function","function":{"name":"$taskListWireName","arguments":"{}"}}]}}]}""",
                headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
            )
        } } }
        val provider = OpenAiCompatibleProvider(client, ProviderCredentialResolver { null })

        val result = assertIs<ProviderCallResult.Success>(provider.complete(
            config(credential = null).copy(baseUrl = "https://api.deepseek.com/v1/"),
            listOf(ProviderChatMessage("user", "List tasks")),
            listOf(
                ProviderToolDefinition("task.list", "List tasks", JsonObject(emptyMap())),
                ProviderToolDefinition("event.update", "Update event", JsonObject(emptyMap())),
            ),
        ))

        assertEquals("task.list", result.message.toolCalls?.single()?.function?.name)
        val body = (requests.single().body as TextContent).text
        assertEquals(
            "disabled",
            Json.parseToJsonElement(body).jsonObject["thinking"]!!.jsonObject["type"]!!.jsonPrimitive.content,
            "DeepSeek must use non-thinking mode for the frozen tool-call continuation profile",
        )
        client.close()
    }

    @Test fun `missing credential fails before network without echoing reference`() = runBlocking {
        val client = HttpClient(MockEngine) { engine { addHandler { error("Network must not be used") } } }
        val provider = OpenAiCompatibleProvider(client, ProviderCredentialResolver { null })
        assertEquals(ProviderCallResult.Failure("MISSING_CREDENTIAL"), provider.complete(config(), listOf(ProviderChatMessage("user", "Hello")), emptyList()))
        client.close()
    }

    @Test fun `credential is never resolved or sent to plain HTTP endpoint`() = runBlocking {
        var secretReads = 0
        val client = HttpClient(MockEngine) { engine { addHandler { error("Network must not be used") } } }
        val provider = OpenAiCompatibleProvider(client, ProviderCredentialResolver { secretReads++; "secret-token" })
        val result = provider.complete(config().copy(baseUrl = "http://model.example/v1"), listOf(ProviderChatMessage("user", "Hello")), emptyList())
        assertEquals(ProviderCallResult.Failure("INSECURE_CREDENTIAL_TRANSPORT"), result)
        assertEquals(0, secretReads)
        client.close()
    }

    @Test fun `SSE tool deltas assemble by index before entering Agent history`() = runBlocking {
        val client = HttpClient(MockEngine) { engine { addHandler {
            respond(
                """data: {"choices":[{"delta":{"content":"Checking ","tool_calls":[{"index":0,"id":"call-1","type":"function","function":{"name":"d9_746173","arguments":"{\\\"taskId\\\":\\\""}}]}}]}

data: {"choices":[{"delta":{"content":"now","tool_calls":[{"index":0,"function":{"name":"6b2e676574","arguments":"abc\\\"}"}}]}}]}

data: [DONE]

""",
                headers = headersOf(HttpHeaders.ContentType, "text/event-stream"),
            )
        } } }
        val provider = OpenAiCompatibleProvider(client, ProviderCredentialResolver { null })
        val result = assertIs<ProviderCallResult.Success>(provider.complete(
            config(credential = null).copy(streamingSupported = true),
            listOf(ProviderChatMessage("user", "Check task")),
            listOf(ProviderToolDefinition("task.get", "Read task", JsonObject(emptyMap()))),
        ))
        assertEquals("Checking now", result.message.content)
        assertEquals("task.get", result.message.toolCalls?.single()?.function?.name)
        assertEquals("call-1", result.message.toolCalls?.single()?.id)
        client.close()
    }

    private fun config(credential: SecretReference? = SecretReference("secure://provider")) = ProviderConfig(
        ProviderConfigId("00000000-0000-7000-8000-000000000001"),
        "https://model.example/v1", "chosen-model", 8192, 2048,
        streamingSupported = false, toolCallingSupported = true, credentialReference = credential,
    )

    private companion object {
        val PROVIDER_FUNCTION_NAME = Regex("^[A-Za-z0-9_-]+$")
    }
}
