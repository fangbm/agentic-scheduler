package dev.agenticscheduler.server.sync

import dev.agenticscheduler.sync.EncryptedEnvelopeV1
import kotlinx.serialization.Serializable

@Serializable
data class StoredEnvelopeResponse(
    val serverCursor: Long,
    val envelope: EncryptedEnvelopeV1,
)

@Serializable
data class UploadEnvelopeResponse(
    val serverCursor: Long,
    val idempotent: Boolean,
)

@Serializable
data class ServerErrorResponse(val code: String)

@Serializable
data class InvitationCreateRequest(val accountId: String, val syncSpaceId: String)

@Serializable
data class InvitationCreateResponse(val invitationToken: String, val expiresAtEpochSeconds: Long)

@Serializable
data class BootstrapRequest(val invitationToken: String, val deviceId: String)

@Serializable
data class BootstrapResponse(
    val accountId: String,
    val syncSpaceId: String,
    val deviceId: String,
    val deviceCredential: String,
)

sealed interface BootstrapResult {
    data class Created(val value: BootstrapResponse) : BootstrapResult
    data object InvalidInvitation : BootstrapResult
    data object DeviceAlreadyExists : BootstrapResult
}

data class AuthenticatedDevice(val accountId: String, val deviceId: String)

data class StoredEnvelope(val serverCursor: Long, val envelope: EncryptedEnvelopeV1)

sealed interface UploadOutcome {
    data class Stored(val serverCursor: Long) : UploadOutcome
    data class Idempotent(val serverCursor: Long) : UploadOutcome
    data object IntegrityConflict : UploadOutcome
    data object NotFound : UploadOutcome
}

interface OpaqueSyncRepository {
    fun authenticate(credential: String): AuthenticatedDevice?

    fun upload(
        actor: AuthenticatedDevice,
        spaceId: String,
        envelope: EncryptedEnvelopeV1,
        ciphertext: ByteArray,
    ): UploadOutcome

    fun fetch(
        actor: AuthenticatedDevice,
        spaceId: String,
        afterCursor: Long,
        limit: Int,
    ): List<StoredEnvelope>?
}

interface ServerBootstrapRepository {
    fun createInvitation(accountId: String, syncSpaceId: String, ttlSeconds: Long): InvitationCreateResponse
    fun bootstrap(request: BootstrapRequest): BootstrapResult
}
