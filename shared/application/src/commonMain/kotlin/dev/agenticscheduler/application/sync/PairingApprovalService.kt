package dev.agenticscheduler.application.sync

import dev.agenticscheduler.sync.AccountId
import dev.agenticscheduler.sync.PendingEnrollmentRequestV1

sealed interface PairingApprovalResult {
    data class Approved(val envelope: dev.agenticscheduler.sync.KeyPackageEnvelopeV1) : PairingApprovalResult
    data object NotActive : PairingApprovalResult
    data object AccountMismatch : PairingApprovalResult
    data object SasMismatch : PairingApprovalResult
    data class PackageUnavailable(val result: PairingKeyPackageBuildResult) : PairingApprovalResult
}

/**
 * Existing-device half of SYN-006 pairing.
 *
 * The local device must already be ACTIVE. The pending enrollment request is
 * remote relay data and is never reconstructed from this device's local state.
 * A package is available for relay only after an explicit SAS comparison.
 */
class PairingApprovalService(
    private val enrollments: LocalEnrollmentRepository,
    private val packageBuilder: PairingKeyPackageBuilder,
) {
    suspend fun approve(
        accountId: AccountId,
        request: PendingEnrollmentRequestV1,
        userComparedSas: String,
    ): PairingApprovalResult {
        val active = enrollments.state(accountId) as? LocalEnrollmentState.Active
            ?: return PairingApprovalResult.NotActive
        if (active.accountId != request.accountId) return PairingApprovalResult.AccountMismatch

        val expected = PairingSas.calculate(
            request.accountId,
            request.requestId,
            request.targetDeviceId,
            request.hpkePublicKeyBase64Url,
        )
        if (userComparedSas != expected) return PairingApprovalResult.SasMismatch

        return when (val result = packageBuilder.build(
            PairingKeyPackageBuildRequest(
                accountId = active.accountId,
                accountMasterKeyReference = active.accountMasterKeyReference,
                syncSpaceId = active.syncSpaceId,
                enrollmentRequest = request,
            ),
        )) {
            is PairingKeyPackageBuildResult.Built -> PairingApprovalResult.Approved(result.envelope)
            else -> PairingApprovalResult.PackageUnavailable(result)
        }
    }
}
