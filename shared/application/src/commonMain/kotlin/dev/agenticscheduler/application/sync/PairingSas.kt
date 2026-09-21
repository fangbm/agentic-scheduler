package dev.agenticscheduler.application.sync

import dev.agenticscheduler.sync.AccountId
import dev.agenticscheduler.sync.DeviceId
import dev.agenticscheduler.sync.EnrollmentRequestId
import dev.agenticscheduler.sync.HpkePublicKeyBase64Url
import dev.agenticscheduler.sync.decodeCanonicalBase64Url

/** Platform adapter around the standard SHA-256 implementation; never a custom hash primitive. */
expect fun pairingSha256(value: ByteArray): ByteArray

/** Exact SYN-006A-3 eight-digit SAS calculation. */
object PairingSas {
    private val label = "agentic-scheduler-pairing-sas".encodeToByteArray()
    private val limit = 18_446_744_073_700_000_000uL
    private const val modulus = 100_000_000uL

    fun calculate(
        accountId: AccountId,
        requestId: EnrollmentRequestId,
        deviceId: DeviceId,
        hpkePublicKey: HpkePublicKeyBase64Url,
    ): String {
        val input = bytes {
            write(label); writeByte(0)
            writeLengthPrefixed(accountId.value)
            writeLengthPrefixed(requestId.value)
            writeLengthPrefixed(deviceId.value)
            write(decodeCanonicalBase64Url(hpkePublicKey.value, 32, "HPKE public key"))
        }
        var counter = 0u
        while (true) {
            val digest = pairingSha256(input + u32(counter))
            val sample = u64(digest, 0)
            if (sample < limit) return (sample % modulus).toString().padStart(8, '0')
            counter++
        }
    }

    private fun bytes(block: ByteBuilder.() -> Unit): ByteArray = ByteBuilder().apply(block).toByteArray()
    private fun u32(value: UInt): ByteArray = byteArrayOf(
        (value.toLong() shr 24).toByte(), (value.toLong() shr 16).toByte(),
        (value.toLong() shr 8).toByte(), value.toByte(),
    )
    private fun u64(value: ByteArray, offset: Int): ULong =
        (0 until 8).fold(0uL) { result, index -> (result shl 8) or value[offset + index].toUByte().toULong() }

    private class ByteBuilder {
        private val values = mutableListOf<Byte>()
        fun write(value: ByteArray) { values += value.toList() }
        fun writeByte(value: Int) { values += value.toByte() }
        fun writeLengthPrefixed(value: String) {
            val encoded = value.encodeToByteArray()
            write(u32(encoded.size.toUInt())); write(encoded)
        }
        fun toByteArray(): ByteArray = values.toByteArray()
    }
}
