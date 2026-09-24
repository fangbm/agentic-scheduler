package dev.agenticscheduler.application.sync

import dev.agenticscheduler.sync.AccountId
import dev.agenticscheduler.sync.DeviceId
import dev.agenticscheduler.sync.EnrollmentRequestId
import dev.agenticscheduler.sync.HpkePublicKeyBase64Url
import dev.agenticscheduler.sync.KeyPackageEnvelopeV1
import dev.agenticscheduler.sync.KeyPackagePlaintextV1
import dev.agenticscheduler.sync.PendingEnrollmentRequestV1
import dev.agenticscheduler.sync.SyncSpaceId
import dev.agenticscheduler.sync.SyncSpaceKeyPackageV1
import kotlin.test.Test
import kotlin.test.assertEquals

class PairingAdmissionTest {
    private val publicKey = HpkePublicKeyBase64Url("AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8")
    private val key = "AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8"
    private val pending = LocalEnrollmentState.Pending(
        AccountId("acct-1"), DeviceId("device-1"), EnrollmentRequestId("req-1"), publicKey,
        SecretReference("secure://pairing-private/1"), SecretReference("secure://credential/1"),
    )
    private val plaintext = KeyPackagePlaintextV1(
        accountId = AccountId("acct-1"), requestId = EnrollmentRequestId("req-1"), targetDeviceId = DeviceId("device-1"), keyEpoch = 8,
        accountMasterKeyBase64Url = key,
        syncSpace = SyncSpaceKeyPackageV1(SyncSpaceId("personal-space"), 8, key, emptyList()),
    )
    private val envelope = KeyPackageEnvelopeV1(
        accountId = plaintext.accountId, requestId = plaintext.requestId, targetDeviceId = plaintext.targetDeviceId, keyEpoch = 8,
        encapsulatedKeyBase64Url = key, ciphertextBase64Url = "AQI",
    )

    @Test
    fun `package identity mismatch remains pending before key import`() {
        assertEquals(KeyPackageAdmissionResult.Accepted(plaintext), PairingAdmission.admitPackage(pending, envelope, plaintext))
        assertEquals(
            KeyPackageAdmissionResult.IdentityMismatch,
            PairingAdmission.admitPackage(pending, envelope.copy(keyEpoch = 9), plaintext),
        )
        assertEquals(
            KeyPackageAdmissionResult.NotPending,
            PairingAdmission.admitPackage(
                LocalEnrollmentState.Active(AccountId("acct-1"), DeviceId("device-1"), EnrollmentRequestId("req-1"), publicKey, SecretReference("secure://pairing-private/1"), SyncSpaceId("personal-space"), SecretReference("secure://amk/1"), SecretReference("secure://credential/1")),
                envelope,
                plaintext,
            ),
        )
    }

    @Test
    fun `request and target identity mismatches are rejected before key import`() {
        assertEquals(
            KeyPackageAdmissionResult.IdentityMismatch,
            PairingAdmission.admitPackage(
                pending,
                envelope.copy(requestId = EnrollmentRequestId("other-request")),
                plaintext,
            ),
        )
        assertEquals(
            KeyPackageAdmissionResult.IdentityMismatch,
            PairingAdmission.admitPackage(
                pending,
                envelope.copy(targetDeviceId = DeviceId("other-device")),
                plaintext,
            ),
        )
    }
}
