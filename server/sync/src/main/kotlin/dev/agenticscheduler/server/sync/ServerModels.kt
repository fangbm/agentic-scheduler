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

@Serializable
data class EnrollmentRequestWire(
    val accountId: String,
    val requestId: String,
    val targetDeviceId: String,
    val hpkePublicKeyBase64Url: String,
)

@Serializable
data class EnrollmentCreatedResponse(val requestId: String, val expiresAtEpochSeconds: Long)

@Serializable
data class PendingEnrollmentResponse(
    val accountId: String,
    val requestId: String,
    val targetDeviceId: String,
    val hpkePublicKeyBase64Url: String,
)

@Serializable
data class KeyPackageUploadRequest(val packageBase64Url: String)

@Serializable
data class KeyPackageResponse(val packageBase64Url: String)

@Serializable
data class OpaqueBlobRequest(val blobBase64Url: String)

@Serializable
data class OpaqueBlobResponse(val blobBase64Url: String)

sealed interface EnrollmentRegistrationResult {
    data class Created(val expiresAtEpochSeconds: Long) : EnrollmentRegistrationResult
    data object UnknownAccount : EnrollmentRegistrationResult
    data object DuplicateRequest : EnrollmentRegistrationResult
}

sealed interface EnrollmentApprovalResult {
    data object Approved : EnrollmentApprovalResult
    data object NotFound : EnrollmentApprovalResult
    data object AlreadyApproved : EnrollmentApprovalResult
}

sealed interface DeviceRevocationResult {
    data object Revoked : DeviceRevocationResult
    data object NotFound : DeviceRevocationResult
    data object AlreadyRevoked : DeviceRevocationResult
    data object SelfRevocationDenied : DeviceRevocationResult
}

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

interface ServerEnrollmentRepository {
    fun registerEnrollment(request: EnrollmentRequestWire, ttlSeconds: Long): EnrollmentRegistrationResult
    fun pendingEnrollments(actor: AuthenticatedDevice): List<PendingEnrollmentResponse>?
    fun approveEnrollment(actor: AuthenticatedDevice, requestId: String, packageBytes: ByteArray): EnrollmentApprovalResult
    fun fetchKeyPackage(requestId: String, targetDeviceId: String): ByteArray?
}

interface ServerSecurityLifecycleRepository {
    fun saveRecoveryEnvelope(actor: AuthenticatedDevice, envelopeBytes: ByteArray): Boolean
    fun fetchRecoveryEnvelope(actor: AuthenticatedDevice): ByteArray?
    fun revokeDevice(actor: AuthenticatedDevice, targetDeviceId: String): DeviceRevocationResult
}
