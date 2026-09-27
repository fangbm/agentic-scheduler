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
import io.ktor.server.request.receiveChannel
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.routing
import io.ktor.utils.io.readRemaining
import kotlinx.serialization.KSerializer
import kotlinx.io.readByteArray
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import java.util.Base64

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
            val request = call.receiveBoundedJson(InvitationCreateRequest.serializer(), config.maxRequestBodyBytes)
            call.respond(HttpStatusCode.Created, bootstrap.createInvitation(request.accountId, request.syncSpaceId, config.invitationTtlSeconds))
        }
        post("/v1/bootstrap") {
            val bootstrap = repository as? ServerBootstrapRepository
                ?: return@post call.respond(HttpStatusCode.ServiceUnavailable, ServerErrorResponse("BOOTSTRAP_UNAVAILABLE"))
            val request = call.receiveBoundedJson(BootstrapRequest.serializer(), config.maxRequestBodyBytes)
            try {
                decodeCanonicalBase64(request.hpkePublicKeyBase64Url, 32, 32)
            } catch (_: IllegalArgumentException) {
                return@post call.respond(HttpStatusCode.BadRequest, ServerErrorResponse("INVALID_DEVICE_HPKE_KEY"))
            }
            when (val result = bootstrap.bootstrap(request)) {
                is BootstrapResult.Created -> call.respond(HttpStatusCode.Created, result.value)
                BootstrapResult.InvalidInvitation -> call.respond(HttpStatusCode.BadRequest, ServerErrorResponse("INVALID_INVITATION"))
                BootstrapResult.DeviceAlreadyExists -> call.respond(HttpStatusCode.Conflict, ServerErrorResponse("DEVICE_ALREADY_EXISTS"))
            }
        }
        post("/v1/enrollments") {
            val enrollment = repository as? ServerEnrollmentRepository
                ?: return@post call.respond(HttpStatusCode.ServiceUnavailable, ServerErrorResponse("ENROLLMENT_UNAVAILABLE"))
            val request = call.receiveBoundedJson(EnrollmentRequestWire.serializer(), config.maxRequestBodyBytes)
            try {
                decodeCanonicalBase64(request.hpkePublicKeyBase64Url, 32, 32)
                decodeCanonicalBase64(request.credentialHashBase64Url, 32, 32)
            } catch (_: IllegalArgumentException) {
                return@post call.respond(HttpStatusCode.BadRequest, ServerErrorResponse("INVALID_ENROLLMENT_CRYPTO"))
            }
            when (val result = enrollment.registerEnrollment(request, config.enrollmentTtlSeconds)) {
                is EnrollmentRegistrationResult.Created -> call.respond(HttpStatusCode.Created, EnrollmentCreatedResponse(request.requestId, result.expiresAtEpochSeconds))
                EnrollmentRegistrationResult.UnknownAccount -> call.respond(HttpStatusCode.NotFound, ServerErrorResponse("UNKNOWN_ACCOUNT"))
                EnrollmentRegistrationResult.DuplicateRequest -> call.respond(HttpStatusCode.Conflict, ServerErrorResponse("DUPLICATE_REQUEST"))
            }
        }
        get("/v1/enrollments/pending") {
            val enrollment = repository as? ServerEnrollmentRepository
                ?: return@get call.respond(HttpStatusCode.ServiceUnavailable, ServerErrorResponse("ENROLLMENT_UNAVAILABLE"))
            val credential = call.bearerCredential()
                ?: return@get call.respond(HttpStatusCode.Unauthorized, ServerErrorResponse("UNAUTHORIZED"))
            val actor = repository.authenticate(credential)
                ?: return@get call.respond(HttpStatusCode.Unauthorized, ServerErrorResponse("UNAUTHORIZED"))
            val pending = enrollment.pendingEnrollments(actor)
                ?: return@get call.respond(HttpStatusCode.NotFound, ServerErrorResponse("NOT_FOUND"))
            call.respond(pending)
        }
        post("/v1/enrollments/{requestId}/approve") {
            val enrollment = repository as? ServerEnrollmentRepository
                ?: return@post call.respond(HttpStatusCode.ServiceUnavailable, ServerErrorResponse("ENROLLMENT_UNAVAILABLE"))
            val credential = call.bearerCredential()
                ?: return@post call.respond(HttpStatusCode.Unauthorized, ServerErrorResponse("UNAUTHORIZED"))
            val actor = repository.authenticate(credential)
                ?: return@post call.respond(HttpStatusCode.Unauthorized, ServerErrorResponse("UNAUTHORIZED"))
            val requestId = call.parameters["requestId"]
                ?: return@post call.respond(HttpStatusCode.BadRequest, ServerErrorResponse("INVALID_REQUEST"))
            val packageRequest = call.receiveBoundedJson(KeyPackageUploadRequest.serializer(), config.maxRequestBodyBytes)
            val packageBytes = try {
                decodeCanonicalBase64(packageRequest.packageBase64Url, null, config.maxCiphertextBytes)
            } catch (_: IllegalArgumentException) {
                return@post call.respond(HttpStatusCode.BadRequest, ServerErrorResponse("INVALID_KEY_PACKAGE"))
            }
            when (enrollment.approveEnrollment(actor, requestId, packageBytes)) {
                EnrollmentApprovalResult.Approved -> call.respond(HttpStatusCode.Created, ServerErrorResponse("APPROVED"))
                EnrollmentApprovalResult.NotFound -> call.respond(HttpStatusCode.NotFound, ServerErrorResponse("NOT_FOUND"))
                EnrollmentApprovalResult.AlreadyApproved -> call.respond(HttpStatusCode.Conflict, ServerErrorResponse("ALREADY_APPROVED"))
                EnrollmentApprovalResult.TargetDeviceAlreadyExists -> call.respond(HttpStatusCode.Conflict, ServerErrorResponse("TARGET_DEVICE_EXISTS"))
                EnrollmentApprovalResult.MissingCredentialHash -> call.respond(HttpStatusCode.Conflict, ServerErrorResponse("MISSING_CREDENTIAL_HASH"))
            }
        }
        get("/v1/enrollments/{requestId}/package") {
            val enrollment = repository as? ServerEnrollmentRepository
                ?: return@get call.respond(HttpStatusCode.ServiceUnavailable, ServerErrorResponse("ENROLLMENT_UNAVAILABLE"))
            val requestId = call.parameters["requestId"]
                ?: return@get call.respond(HttpStatusCode.BadRequest, ServerErrorResponse("INVALID_REQUEST"))
            val targetDeviceId = call.request.queryParameters["targetDeviceId"]
                ?: return@get call.respond(HttpStatusCode.BadRequest, ServerErrorResponse("INVALID_TARGET"))
            val packageBytes = enrollment.fetchKeyPackage(requestId, targetDeviceId)
                ?: return@get call.respond(HttpStatusCode.NotFound, ServerErrorResponse("NOT_FOUND"))
            call.respond(KeyPackageResponse(Base64.getUrlEncoder().withoutPadding().encodeToString(packageBytes)))
        }
        put("/v1/recovery/envelope") {
            val lifecycle = repository as? ServerSecurityLifecycleRepository
                ?: return@put call.respond(HttpStatusCode.ServiceUnavailable, ServerErrorResponse("SECURITY_LIFECYCLE_UNAVAILABLE"))
            val credential = call.bearerCredential()
                ?: return@put call.respond(HttpStatusCode.Unauthorized, ServerErrorResponse("UNAUTHORIZED"))
            val actor = repository.authenticate(credential)
                ?: return@put call.respond(HttpStatusCode.Unauthorized, ServerErrorResponse("UNAUTHORIZED"))
            val encoded = call.receiveBoundedJson(OpaqueBlobRequest.serializer(), config.maxRequestBodyBytes).blobBase64Url
            val bytes = try {
                decodeCanonicalBase64(encoded, null, config.maxCiphertextBytes)
            } catch (_: IllegalArgumentException) {
                return@put call.respond(HttpStatusCode.BadRequest, ServerErrorResponse("INVALID_RECOVERY_ENVELOPE"))
            }
            if (!lifecycle.saveRecoveryEnvelope(actor, bytes)) {
                return@put call.respond(HttpStatusCode.NotFound, ServerErrorResponse("NOT_FOUND"))
            }
            call.respond(HttpStatusCode.OK, ServerErrorResponse("STORED"))
        }
        put("/v1/recovery/proof") {
            val security = repository as? ServerSecurityLifecycleRepository
                ?: return@put call.respond(HttpStatusCode.ServiceUnavailable, ServerErrorResponse("SECURITY_UNAVAILABLE"))
            val credential = call.bearerCredential()
                ?: return@put call.respond(HttpStatusCode.Unauthorized, ServerErrorResponse("UNAUTHORIZED"))
            val actor = repository.authenticate(credential)
                ?: return@put call.respond(HttpStatusCode.Unauthorized, ServerErrorResponse("UNAUTHORIZED"))
            val request = call.receiveBoundedJson(RecoveryProofRegistrationRequest.serializer(), config.maxRequestBodyBytes)
            try {
                decodeCanonicalBase64(request.proofHashBase64Url, 32, 32)
                require(request.counter >= 0)
            } catch (_: IllegalArgumentException) {
                return@put call.respond(HttpStatusCode.BadRequest, ServerErrorResponse("INVALID_RECOVERY_PROOF"))
            }
            when (security.registerRecoveryProof(actor, request)) {
                RecoveryProofRegistrationResult.Stored -> call.respond(HttpStatusCode.OK)
                RecoveryProofRegistrationResult.NotFound -> call.respond(HttpStatusCode.NotFound, ServerErrorResponse("NOT_FOUND"))
                RecoveryProofRegistrationResult.RejectedRollback -> call.respond(HttpStatusCode.Conflict, ServerErrorResponse("PROOF_ROLLBACK"))
                RecoveryProofRegistrationResult.IntegrityConflict -> call.respond(HttpStatusCode.Conflict, ServerErrorResponse("PROOF_INTEGRITY_CONFLICT"))
            }
        }
        post("/v1/recovery/bootstrap") {
            val security = repository as? ServerSecurityLifecycleRepository
                ?: return@post call.respond(HttpStatusCode.ServiceUnavailable, ServerErrorResponse("SECURITY_UNAVAILABLE"))
            val request = call.receiveBoundedJson(RecoveryBootstrapRequest.serializer(), config.maxRequestBodyBytes)
            if (request.accountId.isBlank() || request.accountId.length > 128) {
                return@post call.respond(HttpStatusCode.BadRequest, ServerErrorResponse("INVALID_ACCOUNT"))
            }
            val descriptor = security.recoveryBootstrap(request.accountId)
                ?: return@post call.respond(HttpStatusCode.NotFound, ServerErrorResponse("NOT_FOUND"))
            call.respond(
                RecoveryBootstrapResponse(
                    counter = descriptor.counter,
                    recoveryEnvelopeBase64Url = Base64.getUrlEncoder().withoutPadding()
                        .encodeToString(descriptor.recoveryEnvelope),
                ),
            )
        }
        post("/v1/recovery/enroll") {
            val security = repository as? ServerSecurityLifecycleRepository
                ?: return@post call.respond(HttpStatusCode.ServiceUnavailable, ServerErrorResponse("SECURITY_UNAVAILABLE"))
            val request = call.receiveBoundedJson(RecoveryEnrollmentRequestWire.serializer(), config.maxRequestBodyBytes)
            try {
                decodeCanonicalBase64(request.hpkePublicKeyBase64Url, 32, 32)
                decodeCanonicalBase64(request.credentialHashBase64Url, 32, 32)
                decodeCanonicalBase64(request.proofBase64Url, 32, 32)
                decodeCanonicalBase64(request.nextProofHashBase64Url, 32, 32)
                require(request.counter >= 0)
            } catch (_: IllegalArgumentException) {
                return@post call.respond(HttpStatusCode.BadRequest, ServerErrorResponse("INVALID_RECOVERY_PROOF"))
            }
            when (val result = security.enrollWithRecovery(request)) {
                is RecoveryEnrollmentResult.Created -> call.respond(HttpStatusCode.Created, RecoveryEnrollmentCreatedResponse(result.accountId, result.deviceId))
                is RecoveryEnrollmentResult.Idempotent -> call.respond(HttpStatusCode.OK, RecoveryEnrollmentCreatedResponse(result.accountId, result.deviceId))
                RecoveryEnrollmentResult.InvalidProof -> call.respond(HttpStatusCode.Unauthorized, ServerErrorResponse("INVALID_RECOVERY_PROOF"))
                RecoveryEnrollmentResult.RequestIdentityConflict -> call.respond(HttpStatusCode.Conflict, ServerErrorResponse("RECOVERY_REQUEST_CONFLICT"))
                RecoveryEnrollmentResult.TargetDeviceAlreadyExists -> call.respond(HttpStatusCode.Conflict, ServerErrorResponse("TARGET_DEVICE_EXISTS"))
                RecoveryEnrollmentResult.UnknownAccount -> call.respond(HttpStatusCode.NotFound, ServerErrorResponse("NOT_FOUND"))
            }
        }
        get("/v1/recovery/envelope") {
            val lifecycle = repository as? ServerSecurityLifecycleRepository
                ?: return@get call.respond(HttpStatusCode.ServiceUnavailable, ServerErrorResponse("SECURITY_LIFECYCLE_UNAVAILABLE"))
            val credential = call.bearerCredential()
                ?: return@get call.respond(HttpStatusCode.Unauthorized, ServerErrorResponse("UNAUTHORIZED"))
            val actor = repository.authenticate(credential)
                ?: return@get call.respond(HttpStatusCode.Unauthorized, ServerErrorResponse("UNAUTHORIZED"))
            val bytes = lifecycle.fetchRecoveryEnvelope(actor)
                ?: return@get call.respond(HttpStatusCode.NotFound, ServerErrorResponse("NOT_FOUND"))
            call.respond(OpaqueBlobResponse(Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)))
        }
        get("/v1/devices/active") {
            val lifecycle = repository as? ServerSecurityLifecycleRepository
                ?: return@get call.respond(HttpStatusCode.ServiceUnavailable, ServerErrorResponse("SECURITY_LIFECYCLE_UNAVAILABLE"))
            val credential = call.bearerCredential()
                ?: return@get call.respond(HttpStatusCode.Unauthorized, ServerErrorResponse("UNAUTHORIZED"))
            val actor = repository.authenticate(credential)
                ?: return@get call.respond(HttpStatusCode.Unauthorized, ServerErrorResponse("UNAUTHORIZED"))
            when (val result = lifecycle.activeDevices(actor)) {
                is ActiveDeviceDirectoryResult.Available -> call.respond(HttpStatusCode.OK, result.devices)
                ActiveDeviceDirectoryResult.IncompleteIdentity ->
                    call.respond(HttpStatusCode.Conflict, ServerErrorResponse("DEVICE_DIRECTORY_INCOMPLETE"))
                ActiveDeviceDirectoryResult.NotFound ->
                    call.respond(HttpStatusCode.NotFound, ServerErrorResponse("NOT_FOUND"))
            }
        }
        get("/v1/rotations/packages") {
            val lifecycle = repository as? ServerSecurityLifecycleRepository
                ?: return@get call.respond(HttpStatusCode.ServiceUnavailable, ServerErrorResponse("SECURITY_LIFECYCLE_UNAVAILABLE"))
            val credential = call.bearerCredential()
                ?: return@get call.respond(HttpStatusCode.Unauthorized, ServerErrorResponse("UNAUTHORIZED"))
            val actor = repository.authenticate(credential)
                ?: return@get call.respond(HttpStatusCode.Unauthorized, ServerErrorResponse("UNAUTHORIZED"))
            val packages = lifecycle.rotationPackages(actor)
                ?: return@get call.respond(HttpStatusCode.NotFound, ServerErrorResponse("NOT_FOUND"))
            call.respond(
                packages.map { stored ->
                    RotationPackageResponse(
                        rotationId = stored.rotationId,
                        targetDeviceId = actor.deviceId,
                        packageBase64Url = Base64.getUrlEncoder().withoutPadding().encodeToString(stored.packageBytes),
                    )
                },
            )
        }
        post("/v1/devices/{deviceId}/revoke-and-rotate") {
            val lifecycle = repository as? ServerSecurityLifecycleRepository
                ?: return@post call.respond(HttpStatusCode.ServiceUnavailable, ServerErrorResponse("SECURITY_LIFECYCLE_UNAVAILABLE"))
            val credential = call.bearerCredential()
                ?: return@post call.respond(HttpStatusCode.Unauthorized, ServerErrorResponse("UNAUTHORIZED"))
            val actor = repository.authenticate(credential)
                ?: return@post call.respond(HttpStatusCode.Unauthorized, ServerErrorResponse("UNAUTHORIZED"))
            val target = call.parameters["deviceId"]
                ?: return@post call.respond(HttpStatusCode.BadRequest, ServerErrorResponse("INVALID_DEVICE"))
            val request = call.receiveBoundedJson(AtomicRevocationRequest.serializer(), config.maxRequestBodyBytes)
            try {
                decodeCanonicalBase64(request.recoveryEnvelopeBase64Url, null, config.maxCiphertextBytes)
                require(request.rotationId.isNotBlank() && request.packages.isNotEmpty())
                request.packages.forEach { packageUpload ->
                    require(packageUpload.deviceId.isNotBlank())
                    decodeCanonicalBase64(packageUpload.packageBase64Url, null, config.maxCiphertextBytes)
                }
            } catch (_: IllegalArgumentException) {
                return@post call.respond(HttpStatusCode.BadRequest, ServerErrorResponse("INVALID_ROTATION"))
            }
            when (lifecycle.revokeAndRotate(actor, target, request)) {
                AtomicRevocationResult.Applied -> call.respond(HttpStatusCode.OK, ServerErrorResponse("ROTATED"))
                AtomicRevocationResult.AlreadyApplied -> call.respond(HttpStatusCode.OK, ServerErrorResponse("ALREADY_APPLIED"))
                AtomicRevocationResult.NotFound -> call.respond(HttpStatusCode.NotFound, ServerErrorResponse("NOT_FOUND"))
                AtomicRevocationResult.SelfRevocationDenied -> call.respond(HttpStatusCode.BadRequest, ServerErrorResponse("SELF_REVOCATION_DENIED"))
                AtomicRevocationResult.AlreadyRevoked -> call.respond(HttpStatusCode.Conflict, ServerErrorResponse("ALREADY_REVOKED"))
                AtomicRevocationResult.IntegrityConflict -> call.respond(HttpStatusCode.Conflict, ServerErrorResponse("ROTATION_INTEGRITY_CONFLICT"))
                AtomicRevocationResult.InvalidPackageSet -> call.respond(HttpStatusCode.BadRequest, ServerErrorResponse("INVALID_PACKAGE_SET"))
            }
        }
        post("/v1/sync/spaces/{spaceId}/envelopes") {
            val credential = call.bearerCredential()
                ?: return@post call.respond(HttpStatusCode.Unauthorized, ServerErrorResponse("UNAUTHORIZED"))
            val actor = repository.authenticate(credential)
                ?: return@post call.respond(HttpStatusCode.Unauthorized, ServerErrorResponse("UNAUTHORIZED"))
            val spaceId = call.parameters["spaceId"]
                ?: return@post call.respond(HttpStatusCode.BadRequest, ServerErrorResponse("INVALID_SPACE"))
            val envelope = call.receiveBoundedJson(EncryptedEnvelopeV1.serializer(), config.maxRequestBodyBytes)
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

private suspend fun <T> io.ktor.server.application.ApplicationCall.receiveBoundedJson(
    serializer: KSerializer<T>,
    maxBytes: Long,
): T {
    val contentLength = request.headers[HttpHeaders.ContentLength]?.toLongOrNull()
    if (contentLength != null && contentLength > maxBytes) throw RequestTooLarge()
    val bytes = receiveChannel().readRemaining(maxBytes + 1).readByteArray()
    if (bytes.size.toLong() > maxBytes) throw RequestTooLarge()
    return try {
        serverJson.decodeFromString(serializer, bytes.decodeToString())
    } catch (failure: SerializationException) {
        throw BadRequestException("Malformed request", failure)
    } catch (failure: IllegalArgumentException) {
        throw BadRequestException("Invalid request", failure)
    }
}

private fun decodeCanonicalBase64(value: String, expectedBytes: Int?, maxBytes: Int): ByteArray {
    require(value.isNotEmpty() && '=' !in value)
    val decoded = Base64.getUrlDecoder().decode(value)
    require(decoded.isNotEmpty() && decoded.size <= maxBytes)
    require(expectedBytes == null || decoded.size == expectedBytes)
    require(Base64.getUrlEncoder().withoutPadding().encodeToString(decoded) == value)
    return decoded
}
