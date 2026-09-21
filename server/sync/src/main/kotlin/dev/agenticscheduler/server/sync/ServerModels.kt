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
    val credentialHashBase64Url: String,
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

@Serializable
data class RecoveryProofRegistrationRequest(val proofHashBase64Url: String, val counter: Long)

@Serializable
data class RecoveryEnrollmentRequestWire(
    val accountId: String,
    val requestId: String,
    val targetDeviceId: String,
    val credentialHashBase64Url: String,
    val proofBase64Url: String,
    val counter: Long,
    val nextProofHashBase64Url: String,
)

@Serializable
data class RecoveryEnrollmentCreatedResponse(val accountId: String, val deviceId: String)

@Serializable
data class RotationPackageUpload(val deviceId: String, val packageBase64Url: String)

@Serializable
data class AtomicRevocationRequest(
    val rotationId: String,
    val recoveryEnvelopeBase64Url: String,
    val packages: List<RotationPackageUpload>,
)

sealed interface EnrollmentRegistrationResult {
    data class Created(val expiresAtEpochSeconds: Long) : EnrollmentRegistrationResult
    data object UnknownAccount : EnrollmentRegistrationResult
    data object DuplicateRequest : EnrollmentRegistrationResult
}

sealed interface EnrollmentApprovalResult {
    data object Approved : EnrollmentApprovalResult
    data object NotFound : EnrollmentApprovalResult
    data object AlreadyApproved : EnrollmentApprovalResult
    data object TargetDeviceAlreadyExists : EnrollmentApprovalResult
    data object MissingCredentialHash : EnrollmentApprovalResult
}

sealed interface DeviceRevocationResult {
    data object Revoked : DeviceRevocationResult
    data object NotFound : DeviceRevocationResult
    data object AlreadyRevoked : DeviceRevocationResult
    data object SelfRevocationDenied : DeviceRevocationResult
}

sealed interface AtomicRevocationResult {
    data object Applied : AtomicRevocationResult
    data object AlreadyApplied : AtomicRevocationResult
    data object NotFound : AtomicRevocationResult
    data object SelfRevocationDenied : AtomicRevocationResult
    data object AlreadyRevoked : AtomicRevocationResult
    data object IntegrityConflict : AtomicRevocationResult
    data object InvalidPackageSet : AtomicRevocationResult
}

sealed interface RecoveryProofRegistrationResult {
    data object Stored : RecoveryProofRegistrationResult
    data object NotFound : RecoveryProofRegistrationResult
    data object RejectedRollback : RecoveryProofRegistrationResult
    data object IntegrityConflict : RecoveryProofRegistrationResult
}

sealed interface RecoveryEnrollmentResult {
    data class Created(val accountId: String, val deviceId: String) : RecoveryEnrollmentResult
    data object InvalidProof : RecoveryEnrollmentResult
    data object TargetDeviceAlreadyExists : RecoveryEnrollmentResult
    data object UnknownAccount : RecoveryEnrollmentResult
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
    fun registerRecoveryProof(actor: AuthenticatedDevice, request: RecoveryProofRegistrationRequest): RecoveryProofRegistrationResult
    fun enrollWithRecovery(request: RecoveryEnrollmentRequestWire): RecoveryEnrollmentResult
    fun revokeAndRotate(actor: AuthenticatedDevice, targetDeviceId: String, request: AtomicRevocationRequest): AtomicRevocationResult
    fun saveRecoveryEnvelope(actor: AuthenticatedDevice, envelopeBytes: ByteArray): Boolean
    fun fetchRecoveryEnvelope(actor: AuthenticatedDevice): ByteArray?
    fun revokeDevice(actor: AuthenticatedDevice, targetDeviceId: String): DeviceRevocationResult
}
