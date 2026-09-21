package dev.agenticscheduler.server.agentgateway

import dev.agenticscheduler.agent.AgentModelClient
import dev.agenticscheduler.agent.AgentToolDefinitionV1
import dev.agenticscheduler.agent.AgentTurnRequestV1
import dev.agenticscheduler.agent.AgentTurnResponseV1
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AgentGatewayApplicationTest {
    @Test
    fun `turn requires gateway token and returns typed model response`() = testApplication {
        application {
            agentGatewayModule(
                AgentGatewayConfig("127.0.0.1", 8091, "http://model", "demo", gatewayToken = "secret"),
                object : AgentModelClient {
                    override suspend fun turn(request: AgentTurnRequestV1, tools: List<AgentToolDefinitionV1>) =
                        AgentTurnResponseV1(request.runId, assistantText = "ok")
                },
            )
        }
        val body = """{"runId":"run-1","messages":[{"role":"user","content":"hello"}]}"""
        assertEquals(HttpStatusCode.Unauthorized, client.post("/v1/agent/turn") { contentType(ContentType.Application.Json); setBody(body) }.status)
        val response = client.post("/v1/agent/turn") {
            header(HttpHeaders.Authorization, "Bearer secret")
            contentType(ContentType.Application.Json)
            setBody(body)
        }
        assertEquals(HttpStatusCode.OK, response.status)
        assertTrue(response.bodyAsText().contains("ok"))
    }
}
