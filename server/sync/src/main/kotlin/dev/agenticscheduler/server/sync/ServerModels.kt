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
