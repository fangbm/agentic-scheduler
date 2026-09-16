package dev.agenticscheduler.application.sync

import dev.agenticscheduler.sync.DeviceId
import dev.agenticscheduler.sync.EncryptedEnvelopeV1
import dev.agenticscheduler.sync.EnvelopeDecodeResult
import dev.agenticscheduler.sync.PayloadDecodeResult
import dev.agenticscheduler.sync.SyncPayloadV1
import dev.agenticscheduler.sync.SyncSpaceId
import dev.agenticscheduler.sync.SyncWireCodec

/**
 * Platform-backed AEAD for D8 encrypted content. Implementations must delegate
 * to the frozen Tink AES256_GCM primitive; this boundary deliberately exposes
 * neither raw key bytes nor a nonce API.
 */
interface SyncPayloadAead {
    fun encryptToBase64Url(plaintextUtf8: String, associatedDataUtf8: String): String
    fun decryptFromBase64Url(ciphertextBase64Url: String, associatedDataUtf8: String): String
}

/** Key lookup owns platform secure storage. A missing key is not an authentication failure. */
interface SyncPayloadKeyProvider {
    fun keyFor(syncSpaceId: SyncSpaceId, keyEpoch: Long): SyncPayloadAead?
}

/** Exact SYN-004 AAD identity. Its byte representation is frozen UTF-8 text. */
data class SyncEnvelopeBinding(
    val syncSpaceId: SyncSpaceId,
    val mutationId: String,
    val senderDeviceId: DeviceId,
    val keyEpoch: Long,
) {
    init {
        require(mutationId.isNotBlank()) { "MutationId must not be blank." }
        require(keyEpoch >= 0) { "Key epoch must not be negative." }
    }

    fun authenticatedAssociatedData(): String =
        "agentic-scheduler-sync|v1|${syncSpaceId.value}|$mutationId|${senderDeviceId.value}|$keyEpoch"

    companion object {
        fun from(envelope: EncryptedEnvelopeV1): SyncEnvelopeBinding = SyncEnvelopeBinding(
            syncSpaceId = envelope.syncSpaceId,
            mutationId = envelope.mutationId,
            senderDeviceId = envelope.senderDeviceId,
            keyEpoch = envelope.keyEpoch,
        )
    }
}

sealed interface EncryptSyncPayloadResult {
    data class Encrypted(val envelope: EncryptedEnvelopeV1) : EncryptSyncPayloadResult
    data object MissingContentKey : EncryptSyncPayloadResult
    data class InvalidPayload(val reason: String) : EncryptSyncPayloadResult
}

sealed interface DecryptSyncEnvelopeResult {
    data class Decrypted(
        val envelope: EncryptedEnvelopeV1,
        val payload: SyncPayloadV1,
        val payloadJson: String,
    ) : DecryptSyncEnvelopeResult

    data object MissingContentKey : DecryptSyncEnvelopeResult
    data object AuthenticationFailed : DecryptSyncEnvelopeResult
    data class UnsupportedEnvelopeVersion(val actual: Int?) : DecryptSyncEnvelopeResult
    data class InvalidEnvelope(val reason: String) : DecryptSyncEnvelopeResult
    data class InvalidPayload(val reason: String) : DecryptSyncEnvelopeResult
    data class UnsupportedPayloadVersion(val actual: Int?) : DecryptSyncEnvelopeResult
    data class UnsupportedMutation(val discriminator: String?) : DecryptSyncEnvelopeResult
}

/**
 * D8-02's authenticated envelope boundary. It validates the outer routing
 * identity before key lookup, authenticates that exact identity as AAD, and
 * only returns a typed payload after the inner wire decoder accepts it.
 */
class AuthenticatedSyncEnvelopeCodec(
    private val keys: SyncPayloadKeyProvider,
) {
    fun encrypt(binding: SyncEnvelopeBinding, payload: SyncPayloadV1): EncryptSyncPayloadResult {
        if (payload.operation.mutationId != binding.mutationId) {
            return EncryptSyncPayloadResult.InvalidPayload("Outer and inner MutationId differ.")
        }
        val key = keys.keyFor(binding.syncSpaceId, binding.keyEpoch)
            ?: return EncryptSyncPayloadResult.MissingContentKey
        val ciphertext = key.encryptToBase64Url(
            plaintextUtf8 = SyncWireCodec.encodePayload(payload),
            associatedDataUtf8 = binding.authenticatedAssociatedData(),
        )
        return EncryptSyncPayloadResult.Encrypted(
            EncryptedEnvelopeV1(
                syncSpaceId = binding.syncSpaceId,
                mutationId = binding.mutationId,
                senderDeviceId = binding.senderDeviceId,
                keyEpoch = binding.keyEpoch,
                ciphertextBase64Url = ciphertext,
            ),
        )
    }

    fun decrypt(encodedEnvelope: String): DecryptSyncEnvelopeResult = when (val decoded = SyncWireCodec.decodeEnvelope(encodedEnvelope)) {
        is EnvelopeDecodeResult.Supported -> decrypt(decoded.envelope)
        is EnvelopeDecodeResult.UnsupportedVersion -> DecryptSyncEnvelopeResult.UnsupportedEnvelopeVersion(decoded.actual)
        is EnvelopeDecodeResult.Invalid -> DecryptSyncEnvelopeResult.InvalidEnvelope(decoded.reason)
    }

    fun decrypt(envelope: EncryptedEnvelopeV1): DecryptSyncEnvelopeResult {
        val binding = try {
            SyncEnvelopeBinding.from(envelope)
        } catch (failure: IllegalArgumentException) {
            return DecryptSyncEnvelopeResult.InvalidEnvelope(failure.message ?: "Envelope identity is invalid.")
        }
        if (envelope.envelopeVersion != SyncWireCodec.ENVELOPE_VERSION) {
            return DecryptSyncEnvelopeResult.UnsupportedEnvelopeVersion(envelope.envelopeVersion)
        }
        val key = keys.keyFor(binding.syncSpaceId, binding.keyEpoch)
            ?: return DecryptSyncEnvelopeResult.MissingContentKey
        val payloadJson = try {
            key.decryptFromBase64Url(envelope.ciphertextBase64Url, binding.authenticatedAssociatedData())
        } catch (_: Exception) {
            return DecryptSyncEnvelopeResult.AuthenticationFailed
        }
        return when (val decoded = SyncWireCodec.decodePayload(payloadJson)) {
            is PayloadDecodeResult.Supported -> {
                if (decoded.payload.operation.mutationId != binding.mutationId) {
                    DecryptSyncEnvelopeResult.InvalidPayload("Outer and inner MutationId differ.")
                } else {
                    DecryptSyncEnvelopeResult.Decrypted(envelope, decoded.payload, payloadJson)
                }
            }
            is PayloadDecodeResult.UnsupportedVersion -> DecryptSyncEnvelopeResult.UnsupportedPayloadVersion(decoded.actual)
            is PayloadDecodeResult.UnsupportedMutation -> DecryptSyncEnvelopeResult.UnsupportedMutation(decoded.discriminator)
            is PayloadDecodeResult.Invalid -> DecryptSyncEnvelopeResult.InvalidPayload(decoded.reason)
        }
    }
}
