package dev.agenticscheduler.application.sync

import dev.agenticscheduler.application.persistence.ApplicationTransactionRunner
import dev.agenticscheduler.sync.KeyPackageEnvelopeV1
import dev.agenticscheduler.sync.KeyPackagePlaintextV1
import dev.agenticscheduler.sync.decodeCanonicalBase64Url

/** Platform boundary for importing the AMK from an authenticated pairing package. */
interface PlatformAccountMasterKeyStore {
    suspend fun importAccountMasterKeyForPairing(material: PairingEphemeralKeyMaterial): SecretReference?
    suspend fun delete(reference: SecretReference)
}

sealed interface PairingRecipientAdmissionResult {
    data class Activated(val state: LocalEnrollmentState.Active) : PairingRecipientAdmissionResult
    data object NotPending : PairingRecipientAdmissionResult
    data object MissingPrivateKey : PairingRecipientAdmissionResult
    data object AuthenticationFailed : PairingRecipientAdmissionResult
    data object InvalidPackage : PairingRecipientAdmissionResult
    data object IdentityMismatch : PairingRecipientAdmissionResult
    data object AccountMasterKeyImportFailed : PairingRecipientAdmissionResult
    data class KeyPackageRejected(val result: InstallSyncKeyPackageResult) : PairingRecipientAdmissionResult
    data object ActivationFailed : PairingRecipientAdmissionResult
}

/**
 * SYN-006 recipient completion. The transaction runner, key-ring repository,
 * and local-enrollment repository must be composed over the same Room database.
 * Thus a failed ACTIVE metadata write rolls back the key-ring publish as well;
 * imported platform secrets may remain only as safe orphans.
 */
class PairingRecipientAdmissionService(
    private val enrollments: LocalEnrollmentRepository,
    private val privateKeys: PlatformPairingPrivateKeyStore,
    private val hpke: PairingHpke,
    private val accountMasterKeys: PlatformAccountMasterKeyStore,
    private val keyPackageInstaller: SyncKeyPackageInstaller,
    private val transactions: ApplicationTransactionRunner,
) {
    suspend fun admit(
        envelope: KeyPackageEnvelopeV1,
        deviceCredentialReference: SecretReference,
    ): PairingRecipientAdmissionResult {
        val pending = enrollments.state(envelope.accountId) as? LocalEnrollmentState.Pending
            ?: return PairingRecipientAdmissionResult.NotPending
        val privateKey = privateKeys.privateKey(pending.hpkePrivateKeyReference)
            ?: return PairingRecipientAdmissionResult.MissingPrivateKey
        val plaintext = when (val result = hpke.decryptKeyPackage(pending, privateKey, envelope)) {
            is DecryptKeyPackageResult.Admitted -> result.plaintext
            DecryptKeyPackageResult.NotPending -> return PairingRecipientAdmissionResult.NotPending
            DecryptKeyPackageResult.AuthenticationFailed -> return PairingRecipientAdmissionResult.AuthenticationFailed
            DecryptKeyPackageResult.InvalidPlaintext -> return PairingRecipientAdmissionResult.InvalidPackage
            DecryptKeyPackageResult.IdentityMismatch -> return PairingRecipientAdmissionResult.IdentityMismatch
        }
        return installAndActivate(pending, plaintext, deviceCredentialReference)
    }

    private suspend fun installAndActivate(
        pending: LocalEnrollmentState.Pending,
        plaintext: KeyPackagePlaintextV1,
        deviceCredentialReference: SecretReference,
    ): PairingRecipientAdmissionResult {
        val importedAccountMasterKey = try {
            accountMasterKeys.importAccountMasterKeyForPairing(RawPairingKeyMaterial.fromBase64Url(plaintext.accountMasterKeyBase64Url))
        } catch (_: Throwable) {
            null
        } ?: return PairingRecipientAdmissionResult.AccountMasterKeyImportFailed

        val packageForInstall = DecryptedSyncKeyPackage(
            syncSpaceId = plaintext.syncSpace.syncSpaceId,
            activeEpoch = plaintext.syncSpace.activeEpoch,
            activeKey = RawPairingKeyMaterial.fromBase64Url(plaintext.syncSpace.activeKeyBase64Url),
            historicalKeys = plaintext.syncSpace.historicalKeys.map { key ->
                SyncKeyPackageHistoricalKey(key.keyEpoch, RawPairingKeyMaterial.fromBase64Url(key.keyBase64Url))
            },
        )
        var installation: InstallSyncKeyPackageResult? = null
        val active = LocalEnrollmentState.Active(
            accountId = pending.accountId,
            deviceId = pending.deviceId,
            enrollmentRequestId = pending.enrollmentRequestId,
            hpkePublicKey = pending.hpkePublicKey,
            hpkePrivateKeyReference = pending.hpkePrivateKeyReference,
            syncSpaceId = plaintext.syncSpace.syncSpaceId,
            accountMasterKeyReference = importedAccountMasterKey,
            deviceCredentialReference = deviceCredentialReference,
        )
        try {
            transactions.inWriteTransaction {
                installation = keyPackageInstaller.install(packageForInstall)
                if (installation.isAccepted()) {
                    enrollments.saveActive(active)
                }
            }
        } catch (_: Throwable) {
            safeDeleteAccountMasterKey(importedAccountMasterKey)
            return PairingRecipientAdmissionResult.ActivationFailed
        }
        val result = requireNotNull(installation)
        if (!result.isAccepted()) {
            safeDeleteAccountMasterKey(importedAccountMasterKey)
            return PairingRecipientAdmissionResult.KeyPackageRejected(result)
        }
        return PairingRecipientAdmissionResult.Activated(active)
    }

    private suspend fun safeDeleteAccountMasterKey(reference: SecretReference) {
        try {
            accountMasterKeys.delete(reference)
        } catch (_: Throwable) {
            // An orphaned secure-store key is safe. The durable enrollment stays PENDING.
        }
    }

    private fun InstallSyncKeyPackageResult?.isAccepted(): Boolean = when (this) {
        is InstallSyncKeyPackageResult.Installed,
        is InstallSyncKeyPackageResult.Advanced,
        is InstallSyncKeyPackageResult.Idempotent,
        is InstallSyncKeyPackageResult.Repaired -> true
        is InstallSyncKeyPackageResult.IntegrityError,
        is InstallSyncKeyPackageResult.RejectedRollback,
        null -> false
    }
}

/** Ephemeral raw material produced from authenticated SYN-006A package JSON. */
private class RawPairingKeyMaterial private constructor(
    private val raw: ByteArray,
) : PairingEphemeralKeyMaterial, ImportedContentKeyMaterial {
    override fun copyRawKeyBytesForPairing(): ByteArray = raw.copyOf()
    override fun copyRawSecretBytesForSecureStore(): ByteArray = raw.copyOf()

    companion object {
        fun fromBase64Url(value: String): RawPairingKeyMaterial =
            RawPairingKeyMaterial(decodeCanonicalBase64Url(value, 32, "Pairing key material"))
    }
}
