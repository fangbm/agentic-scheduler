package dev.agenticscheduler.server.sync

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import dev.agenticscheduler.sync.EncryptedEnvelopeV1
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationStopped
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.BadRequestException
import io.ktor.server.plugins.ContentTransformationException
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.plugins.statuspages.exception
import io.ktor.server.request.header
import io.ktor.server.request.receive
import io.ktor.server.request.receiveChannel
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.utils.io.readRemaining
import kotlinx.io.readByteArray
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

fun main() {
    val config = SyncServerConfig.fromEnvironment()
    val dataSource = hikariDataSource(config, "agentic-sync")
    ServerSchemaMigrator(dataSource).migrate()
    embeddedServer(CIO, host = config.bindHost, port = config.port) {
        monitor.subscribe(ApplicationStopped) { dataSource.close() }
        syncServerModule(JdbcOpaqueSyncRepository(dataSource), config)
    }.start(wait = true)
}

fun Application.syncServerModule(
    repository: OpaqueSyncRepository,
    config: SyncServerConfig,
) {
    install(ContentNegotiation) {
        json(Json { encodeDefaults = true; ignoreUnknownKeys = true })
    }
    install(StatusPages) {
        exception<RequestTooLarge> { call, _ ->
            call.respond(HttpStatusCode.PayloadTooLarge, ServerErrorResponse("REQUEST_TOO_LARGE"))
        }
        exception<BadRequestException> { call, _ ->
            call.respond(HttpStatusCode.BadRequest, ServerErrorResponse("MALFORMED_REQUEST"))
        }
        exception<ContentTransformationException> { call, _ ->
            call.respond(HttpStatusCode.BadRequest, ServerErrorResponse("MALFORMED_REQUEST"))
        }
        exception<Throwable> { call, _ ->
            call.respond(HttpStatusCode.InternalServerError, ServerErrorResponse("INTERNAL_ERROR"))
        }
    }
    routing {
        get("/health") { call.respond(mapOf("status" to "ok")) }
        post("/v1/admin/invitations") {
            val configured = config.adminToken
                ?: return@post call.respond(HttpStatusCode.ServiceUnavailable, ServerErrorResponse("ADMIN_INVITATIONS_DISABLED"))
            val supplied = call.request.header("X-Sync-Admin-Token")
                ?: return@post call.respond(HttpStatusCode.Unauthorized, ServerErrorResponse("UNAUTHORIZED"))
            if (!java.security.MessageDigest.isEqual(configured.toByteArray(), supplied.toByteArray())) {
                return@post call.respond(HttpStatusCode.Unauthorized, ServerErrorResponse("UNAUTHORIZED"))
            }
            val bootstrap = repository as? ServerBootstrapRepository
                ?: return@post call.respond(HttpStatusCode.ServiceUnavailable, ServerErrorResponse("BOOTSTRAP_UNAVAILABLE"))
            val contentLength = call.request.headers[HttpHeaders.ContentLength]?.toLongOrNull()
            if (contentLength != null && contentLength > config.maxRequestBodyBytes) throw RequestTooLarge()
            val request = call.receive<InvitationCreateRequest>()
            call.respond(HttpStatusCode.Created, bootstrap.createInvitation(request.accountId, request.syncSpaceId, config.invitationTtlSeconds))
        }
        post("/v1/bootstrap") {
            val bootstrap = repository as? ServerBootstrapRepository
                ?: return@post call.respond(HttpStatusCode.ServiceUnavailable, ServerErrorResponse("BOOTSTRAP_UNAVAILABLE"))
            val contentLength = call.request.headers[HttpHeaders.ContentLength]?.toLongOrNull()
            if (contentLength != null && contentLength > config.maxRequestBodyBytes) throw RequestTooLarge()
            when (val result = bootstrap.bootstrap(call.receive<BootstrapRequest>())) {
                is BootstrapResult.Created -> call.respond(HttpStatusCode.Created, result.value)
                BootstrapResult.InvalidInvitation -> call.respond(HttpStatusCode.BadRequest, ServerErrorResponse("INVALID_INVITATION"))
                BootstrapResult.DeviceAlreadyExists -> call.respond(HttpStatusCode.Conflict, ServerErrorResponse("DEVICE_ALREADY_EXISTS"))
            }
        }
        post("/v1/sync/spaces/{spaceId}/envelopes") {
            val credential = call.bearerCredential()
                ?: return@post call.respond(HttpStatusCode.Unauthorized, ServerErrorResponse("UNAUTHORIZED"))
            val actor = repository.authenticate(credential)
                ?: return@post call.respond(HttpStatusCode.Unauthorized, ServerErrorResponse("UNAUTHORIZED"))
            val contentLength = call.request.headers[HttpHeaders.ContentLength]?.toLongOrNull()
            if (contentLength != null && contentLength > config.maxRequestBodyBytes) throw RequestTooLarge()
            val spaceId = call.parameters["spaceId"]
                ?: return@post call.respond(HttpStatusCode.BadRequest, ServerErrorResponse("INVALID_SPACE"))
            val envelope = call.receiveBoundedEnvelope(config.maxRequestBodyBytes)
            val validated = validateEnvelope(spaceId, envelope, config.maxCiphertextBytes)
            if (validated !is EnvelopeValidationResult.Valid) {
                val code = (validated as EnvelopeValidationResult.Invalid).code
                return@post call.respond(HttpStatusCode.BadRequest, ServerErrorResponse(code))
            }
            if (envelope.senderDeviceId.value != actor.deviceId) {
                return@post call.respond(HttpStatusCode.Forbidden, ServerErrorResponse("SENDER_DEVICE_MISMATCH"))
            }
            when (val result = repository.upload(actor, spaceId, envelope, validated.ciphertext)) {
                is UploadOutcome.Stored -> call.respond(HttpStatusCode.Created, UploadEnvelopeResponse(result.serverCursor, false))
                is UploadOutcome.Idempotent -> call.respond(HttpStatusCode.OK, UploadEnvelopeResponse(result.serverCursor, true))
                UploadOutcome.IntegrityConflict -> call.respond(HttpStatusCode.Conflict, ServerErrorResponse("INTEGRITY_CONFLICT"))
                UploadOutcome.NotFound -> call.respond(HttpStatusCode.NotFound, ServerErrorResponse("NOT_FOUND"))
            }
        }
        get("/v1/sync/spaces/{spaceId}/envelopes") {
            val credential = call.bearerCredential()
                ?: return@get call.respond(HttpStatusCode.Unauthorized, ServerErrorResponse("UNAUTHORIZED"))
            val actor = repository.authenticate(credential)
                ?: return@get call.respond(HttpStatusCode.Unauthorized, ServerErrorResponse("UNAUTHORIZED"))
            val spaceId = call.parameters["spaceId"]
                ?: return@get call.respond(HttpStatusCode.BadRequest, ServerErrorResponse("INVALID_SPACE"))
            val after = call.request.queryParameters["after"]?.toLongOrNull()
                ?: return@get call.respond(HttpStatusCode.BadRequest, ServerErrorResponse("INVALID_CURSOR"))
            if (after < 0) return@get call.respond(HttpStatusCode.BadRequest, ServerErrorResponse("INVALID_CURSOR"))
            val requestedLimit = call.request.queryParameters["limit"]?.toIntOrNull() ?: config.maxFetchLimit
            if (requestedLimit !in 1..config.maxFetchLimit) {
                return@get call.respond(HttpStatusCode.BadRequest, ServerErrorResponse("INVALID_LIMIT"))
            }
            val stored = repository.fetch(actor, spaceId, after, requestedLimit)
                ?: return@get call.respond(HttpStatusCode.NotFound, ServerErrorResponse("NOT_FOUND"))
            call.respond(stored.map { StoredEnvelopeResponse(it.serverCursor, it.envelope) })
        }
    }
}

private fun hikariDataSource(config: SyncServerConfig, poolName: String): HikariDataSource =
    HikariDataSource(
        HikariConfig().apply {
            jdbcUrl = config.jdbcUrl
            username = config.jdbcUser
            password = config.jdbcPassword
            maximumPoolSize = 8
            minimumIdle = 1
            connectionTimeout = 5_000
            validationTimeout = 2_000
            this.poolName = poolName
        },
    )

private fun io.ktor.server.application.ApplicationCall.bearerCredential(): String? {
    val header = request.header(HttpHeaders.Authorization) ?: return null
    if (!header.startsWith("Bearer ")) return null
    return header.removePrefix("Bearer ").trim().takeIf(String::isNotBlank)
}

class RequestTooLarge : RuntimeException()

private val serverJson = Json { encodeDefaults = true; ignoreUnknownKeys = true }

private suspend fun io.ktor.server.application.ApplicationCall.receiveBoundedEnvelope(maxBytes: Long): EncryptedEnvelopeV1 {
    val bytes = receiveChannel().readRemaining(maxBytes + 1).readByteArray()
    if (bytes.size.toLong() > maxBytes) throw RequestTooLarge()
    return try {
        serverJson.decodeFromString(EncryptedEnvelopeV1.serializer(), bytes.decodeToString())
    } catch (failure: SerializationException) {
        throw BadRequestException("Malformed envelope", failure)
    } catch (failure: IllegalArgumentException) {
        throw BadRequestException("Invalid envelope", failure)
    }
}
