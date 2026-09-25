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
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class OpenAiCompatibleProviderTest {
    @Test fun `capability probe requires an actual structured call`() = runBlocking {
        val requests = mutableListOf<HttpRequestData>()
        val client = HttpClient(MockEngine) { engine { addHandler { request ->
            requests += request
            respond(
                """{"choices":[{"message":{"role":"assistant","tool_calls":[{"id":"probe-1","type":"function","function":{"name":"d9_capability_probe","arguments":"{}"}}]}}]}""",
                headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
            )
        } } }
        val provider = OpenAiCompatibleProvider(client, ProviderCredentialResolver { "secret-token" })
        assertEquals(ProviderProbeResult.Supported, provider.probe(config()))
        assertEquals("Bearer secret-token", requests.single().headers[HttpHeaders.Authorization])
        val requestBody = (requests.single().body as TextContent).text
        assertTrue(requestBody.contains("\"tool_choice\":\"required\""))
        assertTrue(!requestBody.contains("secret-token"))
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
        ), listOf(ProviderToolDefinition("task.get", "Read task", JsonObject(emptyMap()))))
        assertEquals("Done", assertIs<ProviderCallResult.Success>(result).message.content)
        val body = (requests.single().body as TextContent).text
        assertTrue(body.contains("\"tool_calls\""))
        assertTrue(body.contains("\"tool_call_id\":\"call-1\""))
        assertTrue(body.contains("\"name\":\"task.get\""))
        client.close()
    }

    @Test fun `missing credential fails before network without echoing reference`() = runBlocking {
        val client = HttpClient(MockEngine) { engine { addHandler { error("Network must not be used") } } }
        val provider = OpenAiCompatibleProvider(client, ProviderCredentialResolver { null })
        assertEquals(ProviderCallResult.Failure("MISSING_CREDENTIAL"), provider.complete(config(), listOf(ProviderChatMessage("user", "Hello")), emptyList()))
        client.close()
    }

    @Test fun `SSE tool deltas assemble by index before entering Agent history`() = runBlocking {
        val client = HttpClient(MockEngine) { engine { addHandler {
            respond(
                """data: {"choices":[{"delta":{"content":"Checking ","tool_calls":[{"index":0,"id":"call-1","type":"function","function":{"name":"task.","arguments":"{\\\"taskId\\\":\\\""}}]}}]}

data: {"choices":[{"delta":{"content":"now","tool_calls":[{"index":0,"function":{"name":"get","arguments":"abc\\\"}"}}]}}]}

data: [DONE]

""",
                headers = headersOf(HttpHeaders.ContentType, "text/event-stream"),
            )
        } } }
        val provider = OpenAiCompatibleProvider(client, ProviderCredentialResolver { null })
        val result = assertIs<ProviderCallResult.Success>(provider.complete(
            config(credential = null).copy(streamingSupported = true),
            listOf(ProviderChatMessage("user", "Check task")), emptyList(),
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
}
