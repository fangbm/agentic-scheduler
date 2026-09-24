package dev.agenticscheduler.sync

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class RotationWireProtocolTest {
    private val hpkeKey = "AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8"
    private val rawKey = encodeCanonicalBase64Url(ByteArray(32) { it.toByte() })

    @Test
    fun `rotation envelope exact JSON fixture is stable`() {
        val envelope = RotationKeyPackageEnvelopeV1(
            accountId = AccountId("acct-1"),
            rotationId = "rotation-1",
            targetDeviceId = DeviceId("device-1"),
            keyEpoch = 8,
            encapsulatedKeyBase64Url = hpkeKey,
            ciphertextBase64Url = "AQI",
        )
        val encoded = RotationWireCodec.encodeEnvelope(envelope)
        assertEquals(
            """{"rotationPackageVersion":1,"accountId":"acct-1","rotationId":"rotation-1","targetDeviceId":"device-1","keyEpoch":8,"encapsulatedKeyBase64Url":"$hpkeKey","ciphertextBase64Url":"AQI"}""",
            encoded,
        )
        assertEquals(
            envelope,
            assertIs<RotationWireDecodeResult.Supported<RotationKeyPackageEnvelopeV1>>(
                RotationWireCodec.decodeEnvelope(encoded),
            ).value,
        )
    }

    @Test
    fun `rotation plaintext is strict and carries complete ring shape`() {
        val plaintext = RotationKeyPackagePlaintextV1(
            accountId = AccountId("acct-1"),
            rotationId = "rotation-1",
            targetDeviceId = DeviceId("device-1"),
            keyEpoch = 8,
            accountMasterKeyBase64Url = rawKey,
            syncSpace = SyncSpaceKeyPackageV1(
                syncSpaceId = SyncSpaceId("personal-space"),
                activeEpoch = 8,
                activeKeyBase64Url = rawKey,
                historicalKeys = listOf(HistoricalSyncSpaceKeyV1(7, rawKey)),
            ),
        )
        val encoded = RotationWireCodec.encodePlaintext(plaintext)
        assertIs<RotationWireDecodeResult.Supported<RotationKeyPackagePlaintextV1>>(
            RotationWireCodec.decodePlaintext(encoded),
        )
        assertIs<RotationWireDecodeResult.Invalid>(
            RotationWireCodec.decodePlaintext(encoded.replaceFirst("{", "{\"unexpected\":1,")),
        )
        assertIs<RotationWireDecodeResult.Invalid>(
            RotationWireCodec.decodePlaintext(
                encoded.replaceFirst(
                    "\"rotationId\":",
                    "\"rotationId\":\"duplicate\",\"rotationId\":",
                ),
            ),
        )
    }
}
