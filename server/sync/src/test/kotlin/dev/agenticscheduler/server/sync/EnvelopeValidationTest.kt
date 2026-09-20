package dev.agenticscheduler.server.sync

import dev.agenticscheduler.sync.DeviceId
import dev.agenticscheduler.sync.EncryptedEnvelopeV1
import dev.agenticscheduler.sync.SyncSpaceId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class EnvelopeValidationTest {
    private val envelope = EncryptedEnvelopeV1(
        syncSpaceId = SyncSpaceId("space"),
        mutationId = "mutation-1",
        senderDeviceId = DeviceId("device"),
        keyEpoch = 3,
        ciphertextBase64Url = "AQI",
    )

    @Test
    fun `validates only opaque routing envelope`() {
        val result = assertIs<EnvelopeValidationResult.Valid>(validateEnvelope("space", envelope, 16))
        assertEquals(byteArrayOf(1, 2).toList(), result.ciphertext.toList())
    }

    @Test
    fun `rejects unsupported version space mismatch and oversized ciphertext`() {
        assertEquals(
            "UNSUPPORTED_ENVELOPE_VERSION",
            (validateEnvelope("space", envelope.copy(envelopeVersion = 2), 16) as EnvelopeValidationResult.Invalid).code,
        )
        assertEquals(
            "SPACE_MISMATCH",
            (validateEnvelope("other", envelope, 16) as EnvelopeValidationResult.Invalid).code,
        )
        assertEquals(
            "INVALID_CIPHERTEXT_SIZE",
            (validateEnvelope("space", envelope, 1) as EnvelopeValidationResult.Invalid).code,
        )
    }

    @Test
    fun `rejects noncanonical and malformed ciphertext`() {
        assertEquals(
            "NON_CANONICAL_CIPHERTEXT_ENCODING",
            (validateEnvelope("space", envelope.copy(ciphertextBase64Url = "AQI="), 16) as EnvelopeValidationResult.Invalid).code,
        )
        assertEquals(
            "INVALID_CIPHERTEXT_ENCODING",
            (validateEnvelope("space", envelope.copy(ciphertextBase64Url = "not base64"), 16) as EnvelopeValidationResult.Invalid).code,
        )
    }
}
