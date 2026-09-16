package dev.agenticscheduler.application.sync

import dev.agenticscheduler.sync.DeviceId
import dev.agenticscheduler.sync.EncryptedEnvelopeV1
import dev.agenticscheduler.sync.EnvelopeDecodeResult
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
    suspend fun keyFor(syncSpaceId: SyncSpaceId, keyEpoch: Long): SyncPayloadKeyLookup
}

sealed interface SyncPayloadKeyLookup {
    data class Available(val aead: SyncPayloadAead) : SyncPayloadKeyLookup
    data object Missing : SyncPayloadKeyLookup
    /** A local device that accepted N must not be downgraded by an N-1 envelope. */
    data class RejectedRollback(val acceptedEpoch: Long) : SyncPayloadKeyLookup
}

interface CurrentEncryptionKeyProvider {
    suspend fun currentEncryptionKey(syncSpaceId: SyncSpaceId): CurrentEncryptionKeyLookup
}

sealed interface CurrentEncryptionKeyLookup {
    data class Available(val keyEpoch: Long, val aead: SyncPayloadAead) : CurrentEncryptionKeyLookup
    data object Missing : CurrentEncryptionKeyLookup
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
    data class NonActiveKeyEpoch(val activeEpoch: Long) : EncryptSyncPayloadResult
    data class InvalidPayload(val reason: String) : EncryptSyncPayloadResult
}

sealed interface DecryptSyncEnvelopeResult {
    /** AEAD-authenticated plaintext. Inner protocol validation belongs to SyncEngine. */
    data class AuthenticatedPlaintext(
        val envelope: EncryptedEnvelopeV1,
        val payloadJson: String,
    ) : DecryptSyncEnvelopeResult

    data object MissingContentKey : DecryptSyncEnvelopeResult
    data class RejectedKeyEpochRollback(val acceptedEpoch: Long) : DecryptSyncEnvelopeResult
    data object AuthenticationFailed : DecryptSyncEnvelopeResult
    data class UnsupportedEnvelopeVersion(val actual: Int?) : DecryptSyncEnvelopeResult
    data class InvalidEnvelope(val reason: String) : DecryptSyncEnvelopeResult
}

/**
 * D8-02's authenticated envelope boundary. It validates the outer routing
 * identity before key lookup and authenticates that exact identity as AAD.
 * It deliberately does not decode inner protocol JSON: SyncEngine owns its
 * durable quarantine/cursor semantics for authenticated protocol failures.
 */
class AuthenticatedSyncEnvelopeCodec(
    private val decryptionKeys: SyncPayloadKeyProvider,
    private val encryptionKeys: CurrentEncryptionKeyProvider,
) {
    suspend fun encrypt(binding: SyncEnvelopeBinding, payload: SyncPayloadV1): EncryptSyncPayloadResult {
        if (payload.operation.mutationId != binding.mutationId) {
            return EncryptSyncPayloadResult.InvalidPayload("Outer and inner MutationId differ.")
        }
        val key = when (val lookup = encryptionKeys.currentEncryptionKey(binding.syncSpaceId)) {
            is CurrentEncryptionKeyLookup.Available -> {
                if (binding.keyEpoch != lookup.keyEpoch) return EncryptSyncPayloadResult.NonActiveKeyEpoch(lookup.keyEpoch)
                lookup.aead
            }
            CurrentEncryptionKeyLookup.Missing -> return EncryptSyncPayloadResult.MissingContentKey
        }
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

    suspend fun decrypt(encodedEnvelope: String): DecryptSyncEnvelopeResult = when (val decoded = SyncWireCodec.decodeEnvelope(encodedEnvelope)) {
        is EnvelopeDecodeResult.Supported -> decrypt(decoded.envelope)
        is EnvelopeDecodeResult.UnsupportedVersion -> DecryptSyncEnvelopeResult.UnsupportedEnvelopeVersion(decoded.actual)
        is EnvelopeDecodeResult.Invalid -> DecryptSyncEnvelopeResult.InvalidEnvelope(decoded.reason)
    }

    suspend fun decrypt(envelope: EncryptedEnvelopeV1): DecryptSyncEnvelopeResult {
        val binding = try {
            SyncEnvelopeBinding.from(envelope)
        } catch (failure: IllegalArgumentException) {
            return DecryptSyncEnvelopeResult.InvalidEnvelope(failure.message ?: "Envelope identity is invalid.")
        }
        if (envelope.envelopeVersion != SyncWireCodec.ENVELOPE_VERSION) {
            return DecryptSyncEnvelopeResult.UnsupportedEnvelopeVersion(envelope.envelopeVersion)
        }
        val key = when (val lookup = decryptionKeys.keyFor(binding.syncSpaceId, binding.keyEpoch)) {
            is SyncPayloadKeyLookup.Available -> lookup.aead
            SyncPayloadKeyLookup.Missing -> return DecryptSyncEnvelopeResult.MissingContentKey
            is SyncPayloadKeyLookup.RejectedRollback -> return DecryptSyncEnvelopeResult.RejectedKeyEpochRollback(lookup.acceptedEpoch)
        }
        val payloadJson = try {
            key.decryptFromBase64Url(envelope.ciphertextBase64Url, binding.authenticatedAssociatedData())
        } catch (_: Exception) {
            return DecryptSyncEnvelopeResult.AuthenticationFailed
        }
        return DecryptSyncEnvelopeResult.AuthenticatedPlaintext(envelope, payloadJson)
    }
}
