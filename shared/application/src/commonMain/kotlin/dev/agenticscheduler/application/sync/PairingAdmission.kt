package dev.agenticscheduler.application.sync

import dev.agenticscheduler.sync.KeyPackageEnvelopeV1
import dev.agenticscheduler.sync.KeyPackagePlaintextV1
import dev.agenticscheduler.sync.PendingEnrollmentRequestV1

/** A pairing recipient cannot transition to ACTIVE until this pending request is admitted. */
sealed interface PairingEnrollmentState {
    data class Pending(val request: PendingEnrollmentRequestV1) : PairingEnrollmentState
    data object Active : PairingEnrollmentState
}

sealed interface PairingApprovalResult {
    data object Approved : PairingApprovalResult
    data object SasMismatch : PairingApprovalResult
    data object NotPending : PairingApprovalResult
}

sealed interface KeyPackageAdmissionResult {
    data class Accepted(val plaintext: KeyPackagePlaintextV1) : KeyPackageAdmissionResult
    data object NotPending : KeyPackageAdmissionResult
    data object IdentityMismatch : KeyPackageAdmissionResult
}

/** Pure state guard shared by all UI and transport adapters. It imports no key material. */
object PairingAdmission {
    fun approve(state: PairingEnrollmentState, userComparedSas: String): PairingApprovalResult {
        val pending = state as? PairingEnrollmentState.Pending ?: return PairingApprovalResult.NotPending
        val expected = PairingSas.calculate(
            pending.request.accountId,
            pending.request.requestId,
            pending.request.targetDeviceId,
            pending.request.hpkePublicKeyBase64Url,
        )
        return if (userComparedSas == expected) PairingApprovalResult.Approved else PairingApprovalResult.SasMismatch
    }

    /** SYN-006A-2 validation that must finish before any secure-store import. */
    fun admitPackage(
        state: PairingEnrollmentState,
        envelope: KeyPackageEnvelopeV1,
        plaintext: KeyPackagePlaintextV1,
    ): KeyPackageAdmissionResult {
        val pending = state as? PairingEnrollmentState.Pending ?: return KeyPackageAdmissionResult.NotPending
        val request = pending.request
        if (
            plaintext.keyPackageVersion != envelope.keyPackageVersion ||
            plaintext.accountId != envelope.accountId || plaintext.accountId != request.accountId ||
            plaintext.requestId != envelope.requestId || plaintext.requestId != request.requestId ||
            plaintext.targetDeviceId != envelope.targetDeviceId || plaintext.targetDeviceId != request.targetDeviceId ||
            plaintext.keyEpoch != envelope.keyEpoch || plaintext.keyEpoch != plaintext.syncSpace.activeEpoch
        ) return KeyPackageAdmissionResult.IdentityMismatch
        return KeyPackageAdmissionResult.Accepted(plaintext)
    }
}
