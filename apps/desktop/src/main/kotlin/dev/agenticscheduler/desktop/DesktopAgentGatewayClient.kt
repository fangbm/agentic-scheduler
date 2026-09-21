package dev.agenticscheduler.desktop

import dev.agenticscheduler.agent.AgentTurnRequestV1
import dev.agenticscheduler.agent.AgentTurnResponseV1
import dev.agenticscheduler.agent.AgentModelClient
import dev.agenticscheduler.agent.AgentToolDefinitionV1
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import kotlinx.serialization.json.Json

class DesktopAgentGatewayClient(
    private val baseUrl: String,
    private val token: String,
    private val http: HttpClient = HttpClient(CIO),
    private val json: Json = Json { ignoreUnknownKeys = true },
) {
    suspend fun turn(request: AgentTurnRequestV1): AgentTurnResponseV1 {
        val response = http.post("${baseUrl.trimEnd('/')}/v1/agent/turn") {
            header(HttpHeaders.Authorization, "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody(json.encodeToString(AgentTurnRequestV1.serializer(), request))
        }
        check(response.status.value in 200..299) { "Agent gateway HTTP ${response.status.value}: ${response.bodyAsText()}" }
        return json.decodeFromString(AgentTurnResponseV1.serializer(), response.bodyAsText())
    }
}

class DesktopGatewayModelClient(private val gateway: DesktopAgentGatewayClient) : AgentModelClient {
    override suspend fun turn(request: AgentTurnRequestV1, tools: List<AgentToolDefinitionV1>): AgentTurnResponseV1 = gateway.turn(request)
}
