package dev.agenticscheduler.application.sync

import dev.agenticscheduler.sync.decodeCanonicalBase64Url
import dev.agenticscheduler.sync.encodeCanonicalBase64Url
import dev.agenticscheduler.sync.requireCanonicalBase64Url

expect fun hmacSha256(key: ByteArray, value: ByteArray): ByteArray

@JvmInline
value class RecoverySecret(val value: String) {
    init { requireCanonicalBase64Url(value, 32, "RecoverySecret") }
}

/** Rotating proof for secret-only recovery registration; the raw secret never crosses the HTTP boundary. */
object RecoveryRegistrationProof {
    private val label = "agentic-scheduler-recovery-registration-v1".encodeToByteArray()

    fun calculate(secret: RecoverySecret, accountId: String, counter: Long): String {
        require(counter >= 0) { "Recovery proof counter must not be negative." }
        val account = accountId.encodeToByteArray()
        val context = ByteArray(label.size + 1 + 4 + account.size + 8).also { bytes ->
            var offset = 0
            label.copyInto(bytes, offset); offset += label.size
            bytes[offset++] = 0
            u32(account.size).copyInto(bytes, offset); offset += 4
            account.copyInto(bytes, offset); offset += account.size
            u64(counter).copyInto(bytes, offset)
        }
        return encodeCanonicalBase64Url(hmacSha256(decodeCanonicalBase64Url(secret.value, 32, "RecoverySecret"), context))
    }

    fun hash(proofBase64Url: String): String =
        encodeCanonicalBase64Url(pairingSha256(decodeCanonicalBase64Url(proofBase64Url, 32, "Recovery proof")))

    private fun u32(value: Int): ByteArray = byteArrayOf(
        (value ushr 24).toByte(), (value ushr 16).toByte(), (value ushr 8).toByte(), value.toByte(),
    )

    private fun u64(value: Long): ByteArray = byteArrayOf(
        (value ushr 56).toByte(), (value ushr 48).toByte(), (value ushr 40).toByte(), (value ushr 32).toByte(),
        (value ushr 24).toByte(), (value ushr 16).toByte(), (value ushr 8).toByte(), value.toByte(),
    )
}
