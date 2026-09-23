package dev.agenticscheduler.sync

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class RecoveryWireProtocolTest {
    private val key0 = encodeCanonicalBase64Url(ByteArray(32) { it.toByte() })
    private val key1 = encodeCanonicalBase64Url(ByteArray(32) { (it + 32).toByte() })

    @Test
    fun `RecoveryEnvelopeV1 round trips with strict canonical fields`() {
        val envelope = RecoveryEnvelopeV1(
            accountId = AccountId("acct-1"),
            syncSpaceId = SyncSpaceId("personal-space"),
            keyEpoch = 7,
            ciphertextBase64Url = "AQI",
        )
        assertEquals(
            envelope,
            assertIs<RecoveryWireDecodeResult.Supported<RecoveryEnvelopeV1>>(
                RecoveryWireCodec.decodeEnvelope(RecoveryWireCodec.encodeEnvelope(envelope)),
            ).value,
        )
    }

    @Test
    fun `recovery plaintext rejects duplicate and unknown fields`() {
        val plaintext = RecoveryEnvelopePlaintextV1(
            accountId = AccountId("acct-1"),
            keyEpoch = 7,
            accountMasterKeyBase64Url = key0,
            syncSpace = RecoverySyncSpaceKeyRingV1(
                syncSpaceId = SyncSpaceId("personal-space"),
                activeEpoch = 7,
                activeKeyBase64Url = key1,
                historicalKeys = listOf(RecoveryHistoricalKeyV1(3, key0)),
            ),
        )
        val encoded = RecoveryWireCodec.encodePlaintext(plaintext)
        assertIs<RecoveryWireDecodeResult.Supported<RecoveryEnvelopePlaintextV1>>(RecoveryWireCodec.decodePlaintext(encoded))
        assertIs<RecoveryWireDecodeResult.Invalid>(
            RecoveryWireCodec.decodePlaintext(encoded.replaceFirst("{", "{\"unexpected\":1,")),
        )
        assertIs<RecoveryWireDecodeResult.Invalid>(
            RecoveryWireCodec.decodePlaintext(encoded.replaceFirst("\"accountId\":", "\"accountId\":\"duplicate\",\"accountId\":")),
        )
    }
}
