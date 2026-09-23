package dev.agenticscheduler.application.sync

import dev.agenticscheduler.sync.AccountId
import dev.agenticscheduler.sync.DeviceId
import dev.agenticscheduler.sync.EnrollmentRequestId
import dev.agenticscheduler.sync.HistoricalSyncSpaceKeyV1
import dev.agenticscheduler.sync.KeyPackagePlaintextV1
import dev.agenticscheduler.sync.PendingEnrollmentRequestV1
import dev.agenticscheduler.sync.PairingWireCodec
import dev.agenticscheduler.sync.SyncSpaceId
import dev.agenticscheduler.sync.SyncSpaceKeyPackageV1
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails

class TinkPairingHpkeTest {
    private val key = "AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8"

    @Test
    fun `Tink HPKE encrypts v1 package and rejects wrong context`() {
        val hpke = TinkPairingHpke()
        val recipient = hpke.generateDeviceKeyPair()
        val plaintext = KeyPackagePlaintextV1(
            accountId = AccountId("acct-1"),
            requestId = EnrollmentRequestId("req-1"),
            targetDeviceId = DeviceId("device-1"),
            keyEpoch = 8,
            accountMasterKeyBase64Url = key,
            syncSpace = SyncSpaceKeyPackageV1(
                syncSpaceId = SyncSpaceId("personal-space"),
                activeEpoch = 8,
                activeKeyBase64Url = key,
                historicalKeys = listOf(HistoricalSyncSpaceKeyV1(7, key)),
            ),
        )
        val envelope = hpke.encryptKeyPackage(recipient.publicKey, plaintext)
        val context = KeyPackageContextV1.bytes(
            plaintext.accountId,
            plaintext.requestId,
            plaintext.targetDeviceId,
            plaintext.keyPackageVersion,
            plaintext.keyEpoch,
        )
        val decrypted = hpke.decrypt(
            recipient.privateKey,
            envelope.encapsulatedKeyBase64Url,
            envelope.ciphertextBase64Url,
            context,
        ).decodeToString()
        assertEquals(plaintext, (PairingWireCodec.decodePlaintext(decrypted) as dev.agenticscheduler.sync.PairingWireDecodeResult.Supported).value)
        val pending = LocalEnrollmentState.Pending(
            plaintext.accountId,
            plaintext.targetDeviceId,
            plaintext.requestId,
            recipient.publicKey,
            SecretReference("secure://pairing-private/1"),
            SecretReference("secure://credential/1"),
        )
        assertEquals(
            DecryptKeyPackageResult.Admitted(plaintext),
            hpke.decryptKeyPackage(pending, recipient.privateKey, envelope),
        )

        val wrongContext = KeyPackageContextV1.bytes(
            plaintext.accountId,
            plaintext.requestId,
            DeviceId("other-device"),
            plaintext.keyPackageVersion,
            plaintext.keyEpoch,
        )
        assertFails {
            hpke.decrypt(recipient.privateKey, envelope.encapsulatedKeyBase64Url, envelope.ciphertextBase64Url, wrongContext)
        }
        assertEquals(
            DecryptKeyPackageResult.AuthenticationFailed,
            hpke.decryptKeyPackage(pending, recipient.privateKey, envelope.copy(targetDeviceId = DeviceId("other-device"))),
        )

        val mutatedPublicKeyRecipient = hpke.generateDeviceKeyPair()
        val publicKeyMutatedEnvelope = hpke.encryptKeyPackage(mutatedPublicKeyRecipient.publicKey, plaintext)
        assertEquals(
            DecryptKeyPackageResult.AuthenticationFailed,
            hpke.decryptKeyPackage(pending, recipient.privateKey, publicKeyMutatedEnvelope),
        )
    }
}
