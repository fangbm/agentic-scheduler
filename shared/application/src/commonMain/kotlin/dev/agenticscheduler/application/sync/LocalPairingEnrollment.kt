package dev.agenticscheduler.application.sync

import dev.agenticscheduler.sync.AccountId
import dev.agenticscheduler.sync.DeviceId
import dev.agenticscheduler.sync.EnrollmentRequestId
import dev.agenticscheduler.sync.HpkePublicKeyBase64Url
import dev.agenticscheduler.sync.PendingEnrollmentRequestV1
import dev.agenticscheduler.sync.SyncSpaceId

/**
 * Local durable enrollment is intentionally distinct from a remotely relayed
 * [PendingEnrollmentRequestV1]. Only this state owns the private-key handle.
 */
sealed interface LocalEnrollmentState {
    val accountId: AccountId
    val deviceId: DeviceId
    val enrollmentRequestId: EnrollmentRequestId
    val hpkePublicKey: HpkePublicKeyBase64Url
    val hpkePrivateKeyReference: SecretReference

    data class Pending(
        override val accountId: AccountId,
        override val deviceId: DeviceId,
        override val enrollmentRequestId: EnrollmentRequestId,
        override val hpkePublicKey: HpkePublicKeyBase64Url,
        override val hpkePrivateKeyReference: SecretReference,
        /** Null only for a pre-final legacy pending row; it cannot be activated. */
        val deviceCredentialReference: SecretReference?,
    ) : LocalEnrollmentState {
        fun asRemoteEnrollmentRequest(): PendingEnrollmentRequestV1 =
            PendingEnrollmentRequestV1(accountId, enrollmentRequestId, deviceId, hpkePublicKey)
    }

    data class Active(
        override val accountId: AccountId,
        override val deviceId: DeviceId,
        override val enrollmentRequestId: EnrollmentRequestId,
        override val hpkePublicKey: HpkePublicKeyBase64Url,
        override val hpkePrivateKeyReference: SecretReference,
        val syncSpaceId: SyncSpaceId,
        val accountMasterKeyReference: SecretReference,
        val deviceCredentialReference: SecretReference,
    ) : LocalEnrollmentState
}

/** Application port for the non-secret local enrollment metadata. */
interface LocalEnrollmentRepository {
    suspend fun state(accountId: AccountId): LocalEnrollmentState?
    /** One durable snapshot, so another ACTIVE account cannot masquerade as local-only. */
    suspend fun states(): List<LocalEnrollmentState>
    suspend fun savePending(value: LocalEnrollmentState.Pending)
    suspend fun saveActive(value: LocalEnrollmentState.Active)
}

/** Platform-owned persistent private HPKE identity. Room stores only [SecretReference]. */
interface PlatformPairingPrivateKeyStore {
    suspend fun generatePairingDeviceKey(): PersistedPairingDeviceKey
    suspend fun privateKey(reference: SecretReference): PairingPrivateKeyMaterial?
    suspend fun delete(reference: SecretReference)
}

data class PersistedPairingDeviceKey(
    val publicKey: HpkePublicKeyBase64Url,
    val privateKeyReference: SecretReference,
)

sealed interface StartLocalEnrollmentResult {
    data class Created(
        val pending: LocalEnrollmentState.Pending,
        val credentialHashBase64Url: String,
    ) : StartLocalEnrollmentResult
    data class Existing(val state: LocalEnrollmentState) : StartLocalEnrollmentResult
    /** Another account already owns this installation's single D8 v1 Personal space. */
    data class RejectedActiveAccount(val activeAccountIds: List<AccountId>) : StartLocalEnrollmentResult
}

/** Creates a restart-safe local PENDING identity before its public request is relayed. */
class LocalEnrollmentRequestService(
    private val enrollments: LocalEnrollmentRepository,
    private val privateKeys: PlatformPairingPrivateKeyStore,
    private val credentials: PlatformDeviceCredentialStore,
) {
    suspend fun startPending(
        accountId: AccountId,
        deviceId: DeviceId,
        enrollmentRequestId: EnrollmentRequestId,
    ): StartLocalEnrollmentResult {
        // Use the repository's one-snapshot API so an existing PENDING row cannot
        // short-circuit the single-active-account check after another account has
        // become ACTIVE.
        val states = enrollments.states()
        val existing = states.firstOrNull { it.accountId == accountId }
        val conflictingActiveAccounts = states
            .asSequence()
            .filterIsInstance<LocalEnrollmentState.Active>()
            .map(LocalEnrollmentState.Active::accountId)
            .filter { it != accountId }
            .distinct()
            .sortedBy(AccountId::value)
            .toList()
        if (conflictingActiveAccounts.isNotEmpty()) {
            return StartLocalEnrollmentResult.RejectedActiveAccount(conflictingActiveAccounts)
        }
        // Preserve the established idempotent result for a same-account ACTIVE row.
        if (existing is LocalEnrollmentState.Active) {
            return StartLocalEnrollmentResult.Existing(existing)
        }
        if (existing is LocalEnrollmentState.Pending) {
            // Revalidate the existing request at the durable transaction boundary;
            // another activation may have raced the snapshot read above.
            enrollments.savePending(existing)
            return StartLocalEnrollmentResult.Existing(existing)
        }

        val generated = privateKeys.generatePairingDeviceKey()
        val credential = try {
            credentials.generate()
        } catch (failure: Throwable) {
            try {
                privateKeys.delete(generated.privateKeyReference)
            } catch (_: Throwable) {
                // A secure-store orphan is safe; no Room row was published.
            }
            throw failure
        }
        val pending = LocalEnrollmentState.Pending(
            accountId = accountId,
            deviceId = deviceId,
            enrollmentRequestId = enrollmentRequestId,
            hpkePublicKey = generated.publicKey,
            hpkePrivateKeyReference = generated.privateKeyReference,
            deviceCredentialReference = credential.reference,
        )
        try {
            enrollments.savePending(pending)
        } catch (failure: Throwable) {
            try {
                privateKeys.delete(generated.privateKeyReference)
            } catch (_: Throwable) {
                // A secure-store orphan is safe; publishing a nonexistent
                // private-key reference is not.
            }
            try {
                credentials.delete(credential.reference)
            } catch (_: Throwable) {
                // A secure-store orphan is safe; no Room row was published.
            }
            throw failure
        }
        return StartLocalEnrollmentResult.Created(pending, credential.hashBase64Url)
    }
}
