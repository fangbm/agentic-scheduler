package dev.agenticscheduler.application.sync

import dev.agenticscheduler.sync.AccountId
import dev.agenticscheduler.sync.DeviceId
import dev.agenticscheduler.sync.EnrollmentRequestId
import dev.agenticscheduler.sync.HistoricalSyncSpaceKeyV1
import dev.agenticscheduler.sync.KeyPackagePlaintextV1
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
    }
}
