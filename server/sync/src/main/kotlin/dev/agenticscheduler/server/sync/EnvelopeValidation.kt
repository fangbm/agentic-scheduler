package dev.agenticscheduler.server.sync

import dev.agenticscheduler.sync.EncryptedEnvelopeV1
import java.util.Base64

sealed interface EnvelopeValidationResult {
    data class Valid(val ciphertext: ByteArray) : EnvelopeValidationResult
    data class Invalid(val code: String) : EnvelopeValidationResult
}

/** Validates routing metadata and ciphertext framing; inner content stays opaque. */
fun validateEnvelope(
    pathSpaceId: String,
    envelope: EncryptedEnvelopeV1,
    maxCiphertextBytes: Int,
): EnvelopeValidationResult {
    if (envelope.envelopeVersion != 1) return EnvelopeValidationResult.Invalid("UNSUPPORTED_ENVELOPE_VERSION")
    if (pathSpaceId.isBlank() || envelope.syncSpaceId.value != pathSpaceId) {
        return EnvelopeValidationResult.Invalid("SPACE_MISMATCH")
    }
    if (envelope.mutationId.length !in 1..128 || envelope.senderDeviceId.value.length !in 1..128) {
        return EnvelopeValidationResult.Invalid("INVALID_ROUTING_ID")
    }
    if (envelope.keyEpoch < 0) return EnvelopeValidationResult.Invalid("INVALID_KEY_EPOCH")
    val ciphertext = try {
        Base64.getUrlDecoder().decode(envelope.ciphertextBase64Url)
    } catch (_: IllegalArgumentException) {
        return EnvelopeValidationResult.Invalid("INVALID_CIPHERTEXT_ENCODING")
    }
    if (ciphertext.isEmpty() || ciphertext.size > maxCiphertextBytes) {
        return EnvelopeValidationResult.Invalid("INVALID_CIPHERTEXT_SIZE")
    }
    if (Base64.getUrlEncoder().withoutPadding().encodeToString(ciphertext) != envelope.ciphertextBase64Url) {
        return EnvelopeValidationResult.Invalid("NON_CANONICAL_CIPHERTEXT_ENCODING")
    }
    return EnvelopeValidationResult.Valid(ciphertext)
}
