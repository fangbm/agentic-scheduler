package dev.agenticscheduler.server.agentgateway

import dev.agenticscheduler.agent.AgentErrorResponseV1
import dev.agenticscheduler.agent.AgentHealthResponseV1
import dev.agenticscheduler.agent.AgentModelClient
import dev.agenticscheduler.agent.AgentTurnRequestV1
import dev.agenticscheduler.agent.GeneralSchedulerSkillV1
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.cio.CIO as ServerCio
import io.ktor.server.engine.embeddedServer
import io.ktor.server.plugins.BadRequestException
import io.ktor.server.plugins.ContentTransformationException
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.plugins.statuspages.exception
import io.ktor.server.request.header
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import kotlinx.serialization.json.Json
import java.security.MessageDigest

fun main() {
    val config = AgentGatewayConfig.fromEnvironment()
    val http = HttpClient(CIO)
    embeddedServer(ServerCio, host = config.bindHost, port = config.port) {
        agentGatewayModule(config, OpenAiCompatibleModelClient(http, config))
    }.start(wait = true)
}

fun Application.agentGatewayModule(config: AgentGatewayConfig, model: AgentModelClient) {
    install(ContentNegotiation) { json(Json { encodeDefaults = true; ignoreUnknownKeys = true }) }
    install(StatusPages) {
        exception<BadRequestException> { call, _ -> call.respond(HttpStatusCode.BadRequest, AgentErrorResponseV1("MALFORMED_REQUEST")) }
        exception<ContentTransformationException> { call, _ -> call.respond(HttpStatusCode.BadRequest, AgentErrorResponseV1("MALFORMED_REQUEST")) }
        exception<AgentGatewayException> { call, failure -> call.respond(HttpStatusCode.BadGateway, AgentErrorResponseV1(failure.code)) }
        exception<Throwable> { call, _ -> call.respond(HttpStatusCode.InternalServerError, AgentErrorResponseV1("INTERNAL_ERROR")) }
    }
    routing {
        get("/health") { call.respond(AgentHealthResponseV1("ok", config.modelName)) }
        get("/v1/model/health") { call.respond(AgentHealthResponseV1("configured", config.modelName)) }
        post("/v1/agent/turn") {
            val supplied = call.request.header(HttpHeaders.Authorization)?.removePrefix("Bearer ")
            if (supplied == null || !MessageDigest.isEqual(supplied.toByteArray(), config.gatewayToken.toByteArray())) {
                return@post call.respond(HttpStatusCode.Unauthorized, AgentErrorResponseV1("UNAUTHORIZED"))
            }
            val request = call.receive<AgentTurnRequestV1>()
            call.respond(model.turn(request, GeneralSchedulerSkillV1.tools))
        }
    }
}
