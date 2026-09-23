package dev.agenticscheduler.application.sync

import dev.agenticscheduler.sync.HpkePublicKeyBase64Url
import dev.agenticscheduler.sync.SyncSpaceId

sealed interface BootstrapEnrollmentResult {
    data class Stored(
        val accountId: String,
        val syncSpaceId: SyncSpaceId,
        val deviceId: String,
        val credentialReference: SecretReference,
        val hpkePublicKey: HpkePublicKeyBase64Url,
        val hpkePrivateKeyReference: SecretReference,
    ) : BootstrapEnrollmentResult
    data object InvalidCredential : BootstrapEnrollmentResult
    data class StorageFailed(val detail: String) : BootstrapEnrollmentResult
}

/**
 * First-device bootstrap creates the durable HPKE identity before the server invitation is
 * consumed. The server receives only the canonical public key; the private key never leaves the
 * platform secure store.
 */
class BootstrapEnrollmentService(
    private val bootstrap: suspend (
        invitationToken: String,
        deviceId: String,
        hpkePublicKeyBase64Url: String,
    ) -> ClientBootstrapResponse,
    private val credentials: PlatformDeviceCredentialStore,
    private val privateKeys: PlatformPairingPrivateKeyStore,
) {
    suspend fun enroll(invitationToken: String, deviceId: String): BootstrapEnrollmentResult {
        val generated = try {
            privateKeys.generatePairingDeviceKey()
        } catch (_: Throwable) {
            return BootstrapEnrollmentResult.StorageFailed("DEVICE_HPKE_KEY_GENERATION_FAILED")
        }

        val response = try {
            bootstrap(invitationToken, deviceId, generated.publicKey.value)
        } catch (failure: Throwable) {
            safeDeletePrivateKey(generated.privateKeyReference)
            throw failure
        }

        val credential = try {
            DeviceCredential(response.deviceCredential)
        } catch (_: IllegalArgumentException) {
            safeDeletePrivateKey(generated.privateKeyReference)
            return BootstrapEnrollmentResult.InvalidCredential
        }

        return try {
            BootstrapEnrollmentResult.Stored(
                accountId = response.accountId,
                syncSpaceId = SyncSpaceId(response.syncSpaceId),
                deviceId = response.deviceId,
                credentialReference = credentials.store(credential),
                hpkePublicKey = generated.publicKey,
                hpkePrivateKeyReference = generated.privateKeyReference,
            )
        } catch (_: Throwable) {
            safeDeletePrivateKey(generated.privateKeyReference)
            BootstrapEnrollmentResult.StorageFailed("DEVICE_CREDENTIAL_STORAGE_FAILED")
        }
    }

    private suspend fun safeDeletePrivateKey(reference: SecretReference) {
        try {
            privateKeys.delete(reference)
        } catch (_: Throwable) {
            // An orphaned secure-store key is safer than publishing a broken reference.
        }
    }
}
