package dev.agenticscheduler.application.sync

import dev.agenticscheduler.sync.SyncSpaceId

sealed interface BootstrapEnrollmentResult {
    data class Stored(
        val accountId: String,
        val syncSpaceId: SyncSpaceId,
        val deviceId: String,
        val credentialReference: SecretReference,
    ) : BootstrapEnrollmentResult
    data object InvalidCredential : BootstrapEnrollmentResult
    data class StorageFailed(val detail: String) : BootstrapEnrollmentResult
}

/** Consumes the one-time bootstrap response without returning or persisting raw credential text. */
class BootstrapEnrollmentService(
    private val bootstrap: suspend (invitationToken: String, deviceId: String) -> ClientBootstrapResponse,
    private val credentials: PlatformDeviceCredentialStore,
) {
    suspend fun enroll(invitationToken: String, deviceId: String): BootstrapEnrollmentResult {
        val response = bootstrap(invitationToken, deviceId)
        val credential = try {
            DeviceCredential(response.deviceCredential)
        } catch (_: IllegalArgumentException) {
            return BootstrapEnrollmentResult.InvalidCredential
        }
        return try {
            BootstrapEnrollmentResult.Stored(
                accountId = response.accountId,
                syncSpaceId = SyncSpaceId(response.syncSpaceId),
                deviceId = response.deviceId,
                credentialReference = credentials.store(credential),
            )
        } catch (_: Throwable) {
            BootstrapEnrollmentResult.StorageFailed("DEVICE_CREDENTIAL_STORAGE_FAILED")
        }
    }
}
