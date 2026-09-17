package dev.agenticscheduler.sync

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class PairingWireProtocolTest {
    private val key = "AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8"

    @Test
    fun `key package v1 fixtures round trip through strict codec`() {
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
                historicalKeys = listOf(HistoricalSyncSpaceKeyV1(6, key), HistoricalSyncSpaceKeyV1(7, key)),
            ),
        )
        val encoded = PairingWireCodec.encodePlaintext(plaintext)
        assertEquals(
            """{"keyPackageVersion":1,"accountId":"acct-1","requestId":"req-1","targetDeviceId":"device-1","keyEpoch":8,"accountMasterKeyBase64Url":"$key","syncSpace":{"syncSpaceId":"personal-space","activeEpoch":8,"activeKeyBase64Url":"$key","historicalKeys":[{"keyEpoch":6,"keyBase64Url":"$key"},{"keyEpoch":7,"keyBase64Url":"$key"}]}}""",
            encoded,
        )
        assertEquals(plaintext, assertIs<PairingWireDecodeResult.Supported<KeyPackagePlaintextV1>>(PairingWireCodec.decodePlaintext(encoded)).value)

        val envelope = KeyPackageEnvelopeV1(
            accountId = plaintext.accountId,
            requestId = plaintext.requestId,
            targetDeviceId = plaintext.targetDeviceId,
            keyEpoch = 8,
            encapsulatedKeyBase64Url = key,
            ciphertextBase64Url = "AQI",
        )
        val encodedEnvelope = PairingWireCodec.encodeEnvelope(envelope)
        assertEquals(
            """{"keyPackageVersion":1,"accountId":"acct-1","requestId":"req-1","targetDeviceId":"device-1","keyEpoch":8,"encapsulatedKeyBase64Url":"$key","ciphertextBase64Url":"AQI"}""",
            encodedEnvelope,
        )
        assertEquals(envelope, assertIs<PairingWireDecodeResult.Supported<KeyPackageEnvelopeV1>>(PairingWireCodec.decodeEnvelope(encodedEnvelope)).value)
    }

    @Test
    fun `key package JSON rejects duplicate unknown and noncanonical fields`() {
        val duplicate = """{"keyPackageVersion":1,"keyPackageVersion":1,"accountId":"acct","requestId":"req","targetDeviceId":"device","keyEpoch":1,"encapsulatedKeyBase64Url":"$key","ciphertextBase64Url":"AQI"}"""
        assertIs<PairingWireDecodeResult.Invalid>(PairingWireCodec.decodeEnvelope(duplicate))

        val unknown = """{"keyPackageVersion":1,"accountId":"acct","requestId":"req","targetDeviceId":"device","keyEpoch":1,"encapsulatedKeyBase64Url":"$key","ciphertextBase64Url":"AQI","future":true}"""
        assertIs<PairingWireDecodeResult.Invalid>(PairingWireCodec.decodeEnvelope(unknown))

        val paddedKey = """{"keyPackageVersion":1,"accountId":"acct","requestId":"req","targetDeviceId":"device","keyEpoch":1,"encapsulatedKeyBase64Url":"${key}=","ciphertextBase64Url":"AQI"}"""
        assertIs<PairingWireDecodeResult.Invalid>(PairingWireCodec.decodeEnvelope(paddedKey))
    }
}
