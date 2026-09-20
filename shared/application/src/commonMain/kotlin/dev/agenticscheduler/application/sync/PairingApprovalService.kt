package dev.agenticscheduler.application.sync

import dev.agenticscheduler.sync.AccountId
import dev.agenticscheduler.sync.KeyPackageEnvelopeV1
import dev.agenticscheduler.sync.PendingEnrollmentRequestV1

sealed interface PairingApprovalResult {
    /** A user-approved, already HPKE-encrypted package ready for opaque relay. */
    data class Approved(val envelope: KeyPackageEnvelopeV1) : PairingApprovalResult
    data object SasMismatch : PairingApprovalResult
    data object NotActive : PairingApprovalResult
    data object AccountMismatch : PairingApprovalResult
    data class PackageBuildFailed(val result: PairingKeyPackageBuildResult) : PairingApprovalResult
}

/**
 * SYN-006 approver half. Only an already ACTIVE local device may approve a
 * remote enrollment request. Approval and package creation are one capability:
 * callers cannot obtain a relayable key package without a successful SAS check.
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

        val expectedSas = PairingSas.calculate(
            request.accountId,
            request.requestId,
            request.targetDeviceId,
            request.hpkePublicKeyBase64Url,
        )
        if (userComparedSas != expectedSas) return PairingApprovalResult.SasMismatch

        return when (
            val built = packageBuilder.build(
                PairingKeyPackageBuildRequest(
                    accountId = active.accountId,
                    accountMasterKeyReference = active.accountMasterKeyReference,
                    syncSpaceId = active.syncSpaceId,
                    enrollmentRequest = request,
                ),
            )
        ) {
            is PairingKeyPackageBuildResult.Built -> PairingApprovalResult.Approved(built.envelope)
            else -> PairingApprovalResult.PackageBuildFailed(built)
        }
    }
}
