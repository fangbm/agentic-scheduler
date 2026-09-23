package dev.agenticscheduler.application.sync

import dev.agenticscheduler.application.persistence.ApplicationTransactionRunner
import dev.agenticscheduler.sync.AccountId
import dev.agenticscheduler.sync.DeviceId
import dev.agenticscheduler.sync.EnrollmentRequestId
import dev.agenticscheduler.sync.RecoveryEnvelopePlaintextV1
import dev.agenticscheduler.sync.RecoveryWireCodec
import dev.agenticscheduler.sync.RecoveryWireDecodeResult
import dev.agenticscheduler.sync.decodeCanonicalBase64Url

sealed interface RecoveryEnrollmentServiceResult {
    data class Activated(val state: LocalEnrollmentState.Active) : RecoveryEnrollmentServiceResult
    data class Existing(val state: LocalEnrollmentState) : RecoveryEnrollmentServiceResult
    data object BootstrapFailed : RecoveryEnrollmentServiceResult
    data object InvalidEnvelope : RecoveryEnrollmentServiceResult
    data object EnvelopeAccountMismatch : RecoveryEnrollmentServiceResult
    data object EnvelopeAuthenticationFailed : RecoveryEnrollmentServiceResult
    data object EnvelopeBindingMismatch : RecoveryEnrollmentServiceResult
    data object InvalidBootstrapCounter : RecoveryEnrollmentServiceResult
    data object RecoveryEnrollmentFailed : RecoveryEnrollmentServiceResult
    data object RecoveryEnrollmentIdentityMismatch : RecoveryEnrollmentServiceResult
    data object MissingDeviceCredential : RecoveryEnrollmentServiceResult
    data object AccountMasterKeyImportFailed : RecoveryEnrollmentServiceResult
    data class KeyPackageRejected(val result: InstallSyncKeyPackageResult) : RecoveryEnrollmentServiceResult
    data object ActivationFailed : RecoveryEnrollmentServiceResult
}

/**
 * Fresh-device SYN-005B/SYN-005C recovery orchestration.
 *
 * The Recovery Secret authenticates the envelope and the rotating enrollment proof. A local
 * PENDING identity is published before the server enrollment so a crash after server success can
 * retry the same request id without generating a different credential. Key-ring metadata and
 * ACTIVE state are published together only after the server has accepted that proof.
 */
class RecoveryEnrollmentService(
    private val transport: RecoveryEnrollmentTransport,
    private val pendingEnrollment: LocalEnrollmentRequestService,
    private val enrollments: LocalEnrollmentRepository,
    private val credentials: PlatformDeviceCredentialStore,
    private val accountMasterKeys: PlatformAccountMasterKeyStore,
    private val keyPackageInstaller: SyncKeyPackageInstaller,
    private val transactions: ApplicationTransactionRunner,
) {
    suspend fun recover(
        accountId: AccountId,
        recoverySecret: RecoverySecret,
        deviceId: DeviceId,
        enrollmentRequestId: EnrollmentRequestId,
    ): RecoveryEnrollmentServiceResult {
        val bootstrap = try {
            transport.recoveryBootstrap(accountId.value)
        } catch (_: Throwable) {
            return RecoveryEnrollmentServiceResult.BootstrapFailed
        }
        if (bootstrap.counter < 0 || bootstrap.counter == Long.MAX_VALUE) {
            return RecoveryEnrollmentServiceResult.InvalidBootstrapCounter
        }
        val plaintext = when (val opened = decodeAndOpen(accountId, recoverySecret, bootstrap.recoveryEnvelopeBase64Url)) {
            is OpenedEnvelope.Decrypted -> opened.plaintext
            is OpenedEnvelope.Failed -> return opened.result
        }

        val pending = when (val local = pendingEnrollment.startPending(accountId, deviceId, enrollmentRequestId)) {
            is StartLocalEnrollmentResult.Created -> local.pending
            is StartLocalEnrollmentResult.Existing -> when (val state = local.state) {
                is LocalEnrollmentState.Active -> return RecoveryEnrollmentServiceResult.Existing(state)
                is LocalEnrollmentState.Pending -> {
                    if (state.deviceId != deviceId || state.enrollmentRequestId != enrollmentRequestId) {
                        return RecoveryEnrollmentServiceResult.Existing(state)
                    }
                    state
                }
            }
        }
        val credentialReference = pending.deviceCredentialReference
            ?: return RecoveryEnrollmentServiceResult.MissingDeviceCredential
        val credential = credentials.load(credentialReference)
            ?: return RecoveryEnrollmentServiceResult.MissingDeviceCredential
        val proof = RecoveryRegistrationProof.calculate(recoverySecret, accountId.value, bootstrap.counter)
        val nextProof = RecoveryRegistrationProof.calculate(recoverySecret, accountId.value, bootstrap.counter + 1)
        val enrollment = try {
            transport.enrollWithRecovery(
                ClientRecoveryEnrollmentRequest(
                    accountId = accountId.value,
                    requestId = enrollmentRequestId.value,
                    targetDeviceId = deviceId.value,
                    hpkePublicKeyBase64Url = pending.hpkePublicKey.value,
                    credentialHashBase64Url = DeviceCredentialHashing.sha256Base64Url(credential),
                    proofBase64Url = proof,
                    counter = bootstrap.counter,
                    nextProofHashBase64Url = RecoveryRegistrationProof.hash(nextProof),
                ),
            )
        } catch (_: Throwable) {
            return RecoveryEnrollmentServiceResult.RecoveryEnrollmentFailed
        }
        if (enrollment.accountId != accountId.value || enrollment.deviceId != deviceId.value) {
            return RecoveryEnrollmentServiceResult.RecoveryEnrollmentIdentityMismatch
        }
        return installAndActivate(pending, plaintext, credentialReference)
    }

    private fun decodeAndOpen(
        expectedAccountId: AccountId,
        secret: RecoverySecret,
        encodedEnvelope: String,
    ): OpenedEnvelope {
        val encoded = try {
            decodeCanonicalBase64Url(encodedEnvelope, null, "Recovery bootstrap envelope")
                .decodeToString(throwOnInvalidSequence = true)
        } catch (_: Throwable) {
            return OpenedEnvelope.Failed(RecoveryEnrollmentServiceResult.InvalidEnvelope)
        }
        val envelope = when (val decoded = RecoveryWireCodec.decodeEnvelope(encoded)) {
            is RecoveryWireDecodeResult.Supported -> decoded.value
            is RecoveryWireDecodeResult.Invalid,
            is RecoveryWireDecodeResult.UnsupportedVersion,
            -> return OpenedEnvelope.Failed(RecoveryEnrollmentServiceResult.InvalidEnvelope)
        }
        if (envelope.accountId != expectedAccountId) {
            return OpenedEnvelope.Failed(RecoveryEnrollmentServiceResult.EnvelopeAccountMismatch)
        }
        return when (val opened = RecoveryEnvelopeCodec.open(secret, envelope)) {
            is RecoveryEnvelopeOpenResult.Decrypted -> OpenedEnvelope.Decrypted(opened.plaintext)
            RecoveryEnvelopeOpenResult.AuthenticationFailed ->
                OpenedEnvelope.Failed(RecoveryEnrollmentServiceResult.EnvelopeAuthenticationFailed)
            is RecoveryEnvelopeOpenResult.InvalidPlaintext ->
                OpenedEnvelope.Failed(RecoveryEnrollmentServiceResult.InvalidEnvelope)
            RecoveryEnvelopeOpenResult.BindingMismatch ->
                OpenedEnvelope.Failed(RecoveryEnrollmentServiceResult.EnvelopeBindingMismatch)
        }
    }

    private sealed interface OpenedEnvelope {
        data class Decrypted(val plaintext: RecoveryEnvelopePlaintextV1) : OpenedEnvelope
        data class Failed(val result: RecoveryEnrollmentServiceResult) : OpenedEnvelope
    }

    private suspend fun installAndActivate(
        pending: LocalEnrollmentState.Pending,
        plaintext: RecoveryEnvelopePlaintextV1,
        deviceCredentialReference: SecretReference,
    ): RecoveryEnrollmentServiceResult {
        val accountMasterKey = try {
            accountMasterKeys.importAccountMasterKey(RawEphemeralKeyMaterial.fromBase64Url(plaintext.accountMasterKeyBase64Url))
        } catch (_: Throwable) {
            null
        } ?: return RecoveryEnrollmentServiceResult.AccountMasterKeyImportFailed
        val packageForInstall = DecryptedSyncKeyPackage(
            syncSpaceId = plaintext.syncSpace.syncSpaceId,
            activeEpoch = plaintext.syncSpace.activeEpoch,
            activeKey = RawEphemeralKeyMaterial.fromBase64Url(plaintext.syncSpace.activeKeyBase64Url),
            historicalKeys = plaintext.syncSpace.historicalKeys.map { key ->
                SyncKeyPackageHistoricalKey(key.keyEpoch, RawEphemeralKeyMaterial.fromBase64Url(key.keyBase64Url))
            },
        )
        val active = LocalEnrollmentState.Active(
            accountId = pending.accountId,
            deviceId = pending.deviceId,
            enrollmentRequestId = pending.enrollmentRequestId,
            hpkePublicKey = pending.hpkePublicKey,
            hpkePrivateKeyReference = pending.hpkePrivateKeyReference,
            syncSpaceId = plaintext.syncSpace.syncSpaceId,
            accountMasterKeyReference = accountMasterKey,
            deviceCredentialReference = deviceCredentialReference,
        )
        val installed = try {
            transactions.inWriteTransaction {
                val result = keyPackageInstaller.install(packageForInstall)
                if (result.isAccepted()) enrollments.saveActive(active)
                result
            }
        } catch (_: Throwable) {
            safeDeleteAccountMasterKey(accountMasterKey)
            return RecoveryEnrollmentServiceResult.ActivationFailed
        }
        if (!installed.isAccepted()) {
            safeDeleteAccountMasterKey(accountMasterKey)
            return RecoveryEnrollmentServiceResult.KeyPackageRejected(installed)
        }
        return RecoveryEnrollmentServiceResult.Activated(active)
    }

    private suspend fun safeDeleteAccountMasterKey(reference: SecretReference) {
        try {
            accountMasterKeys.delete(reference)
        } catch (_: Throwable) {
            // A secure-store orphan is safe. The durable enrollment remains PENDING.
        }
    }

    private fun InstallSyncKeyPackageResult.isAccepted(): Boolean = when (this) {
        is InstallSyncKeyPackageResult.Installed,
        is InstallSyncKeyPackageResult.Advanced,
        is InstallSyncKeyPackageResult.Idempotent,
        is InstallSyncKeyPackageResult.Repaired,
        -> true
        is InstallSyncKeyPackageResult.IntegrityError,
        is InstallSyncKeyPackageResult.RejectedRollback,
        -> false
    }
}
