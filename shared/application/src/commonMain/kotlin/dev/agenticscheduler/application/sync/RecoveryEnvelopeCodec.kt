package dev.agenticscheduler.application.sync

import dev.agenticscheduler.sync.AccountId
import dev.agenticscheduler.sync.RecoveryEnvelopePlaintextV1
import dev.agenticscheduler.sync.RecoveryEnvelopeV1
import dev.agenticscheduler.sync.RecoveryWireCodec
import dev.agenticscheduler.sync.RecoveryWireDecodeResult
import dev.agenticscheduler.sync.SyncSpaceId
import dev.agenticscheduler.sync.decodeCanonicalBase64Url
import dev.agenticscheduler.sync.encodeCanonicalBase64Url

sealed interface RecoveryEnvelopeOpenResult {
    data class Decrypted(val plaintext: RecoveryEnvelopePlaintextV1) : RecoveryEnvelopeOpenResult
    data object AuthenticationFailed : RecoveryEnvelopeOpenResult
    data class InvalidPlaintext(val reason: String) : RecoveryEnvelopeOpenResult
    data object BindingMismatch : RecoveryEnvelopeOpenResult
}

object RecoveryEnvelopeCodec {
    fun seal(secret: RecoverySecret, plaintext: RecoveryEnvelopePlaintextV1): RecoveryEnvelopeV1 {
        require(plaintext.recoveryEnvelopeVersion == RecoveryWireCodec.RECOVERY_ENVELOPE_VERSION)
        val accountId = plaintext.accountId
        val syncSpaceId = plaintext.syncSpace.syncSpaceId
        val keyEpoch = plaintext.keyEpoch
        val key = deriveRecoveryEnvelopeKey(secret, accountId, syncSpaceId, keyEpoch)
        return try {
            val ciphertext = recoveryEnvelopeAeadEncrypt(
                key,
                RecoveryWireCodec.encodePlaintext(plaintext).encodeToByteArray(),
                recoveryEnvelopeAad(accountId, syncSpaceId, keyEpoch),
            )
            RecoveryEnvelopeV1(
                accountId = accountId,
                syncSpaceId = syncSpaceId,
                keyEpoch = keyEpoch,
                ciphertextBase64Url = encodeCanonicalBase64Url(ciphertext),
            )
        } finally {
            key.fill(0)
        }
    }

    fun open(secret: RecoverySecret, envelope: RecoveryEnvelopeV1): RecoveryEnvelopeOpenResult {
        if (envelope.recoveryEnvelopeVersion != RecoveryWireCodec.RECOVERY_ENVELOPE_VERSION) {
            return RecoveryEnvelopeOpenResult.InvalidPlaintext("Unsupported recovery envelope version.")
        }
        val key = deriveRecoveryEnvelopeKey(secret, envelope.accountId, envelope.syncSpaceId, envelope.keyEpoch)
        val plaintextBytes = try {
            recoveryEnvelopeAeadDecrypt(
                key,
                decodeCanonicalBase64Url(envelope.ciphertextBase64Url, null, "Recovery envelope ciphertext"),
                recoveryEnvelopeAad(envelope.accountId, envelope.syncSpaceId, envelope.keyEpoch),
            )
        } catch (_: Throwable) {
            return RecoveryEnvelopeOpenResult.AuthenticationFailed
        } finally {
            key.fill(0)
        }
        return when (val decoded = RecoveryWireCodec.decodePlaintext(plaintextBytes.decodeToString())) {
            is RecoveryWireDecodeResult.UnsupportedVersion ->
                RecoveryEnvelopeOpenResult.InvalidPlaintext("Unsupported recovery plaintext version.")
            is RecoveryWireDecodeResult.Invalid ->
                RecoveryEnvelopeOpenResult.InvalidPlaintext(decoded.reason)
            is RecoveryWireDecodeResult.Supported -> {
                val value = decoded.value
                if (
                    value.accountId != envelope.accountId ||
                    value.syncSpace.syncSpaceId != envelope.syncSpaceId ||
                    value.keyEpoch != envelope.keyEpoch ||
                    value.syncSpace.activeEpoch != envelope.keyEpoch
                ) RecoveryEnvelopeOpenResult.BindingMismatch
                else RecoveryEnvelopeOpenResult.Decrypted(value)
            }
        }
    }
}

internal fun deriveRecoveryEnvelopeKey(
    secret: RecoverySecret,
    accountId: AccountId,
    syncSpaceId: SyncSpaceId,
    keyEpoch: Long,
): ByteArray {
    require(keyEpoch >= 0)
    val secretBytes = decodeCanonicalBase64Url(secret.value, 32, "RecoverySecret")
    return try {
        hmacSha256(
            secretBytes,
            recoveryEnvelopeContext(
                "agentic-scheduler-recovery-envelope-key-v1",
                accountId,
                syncSpaceId,
                keyEpoch,
            ),
        )
    } finally {
        secretBytes.fill(0)
    }
}

internal fun recoveryEnvelopeAad(
    accountId: AccountId,
    syncSpaceId: SyncSpaceId,
    keyEpoch: Long,
): ByteArray = recoveryEnvelopeContext(
    "agentic-scheduler-recovery-envelope",
    accountId,
    syncSpaceId,
    keyEpoch,
)

private fun recoveryEnvelopeContext(
    label: String,
    accountId: AccountId,
    syncSpaceId: SyncSpaceId,
    keyEpoch: Long,
): ByteArray {
    val account = accountId.value.encodeToByteArray()
    val space = syncSpaceId.value.encodeToByteArray()
    return label.encodeToByteArray() +
        byteArrayOf(0) +
        u32(account.size) + account +
        u32(space.size) + space +
        u32(RecoveryWireCodec.RECOVERY_ENVELOPE_VERSION) +
        u64(keyEpoch)
}

private fun u32(value: Int): ByteArray = byteArrayOf(
    (value ushr 24).toByte(), (value ushr 16).toByte(), (value ushr 8).toByte(), value.toByte(),
)

private fun u64(value: Long): ByteArray = byteArrayOf(
    (value ushr 56).toByte(), (value ushr 48).toByte(), (value ushr 40).toByte(), (value ushr 32).toByte(),
    (value ushr 24).toByte(), (value ushr 16).toByte(), (value ushr 8).toByte(), value.toByte(),
)

internal expect fun recoveryEnvelopeAeadEncrypt(
    rawKey: ByteArray,
    plaintext: ByteArray,
    associatedData: ByteArray,
): ByteArray

internal expect fun recoveryEnvelopeAeadDecrypt(
    rawKey: ByteArray,
    ciphertext: ByteArray,
    associatedData: ByteArray,
): ByteArray
