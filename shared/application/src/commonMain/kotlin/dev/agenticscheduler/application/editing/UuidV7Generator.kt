package dev.agenticscheduler.application.editing

import kotlin.time.Clock

class UuidV7Generator(
    private val clock: Clock,
    private val fillRandomBytes: (ByteArray) -> Unit,
) {
    fun generate(): String {
        val epochMillis = clock.now().toEpochMilliseconds()
        require(epochMillis in 0..0xFFFF_FFFF_FFFFL) { "UUIDv7 timestamp must fit in 48 bits." }

        val bytes = ByteArray(16)
        repeat(6) { index ->
            bytes[index] = (epochMillis shr ((5 - index) * 8)).toByte()
        }

        val entropy = ByteArray(10).also(fillRandomBytes)
        bytes[6] = (0x70 or (entropy[0].toInt() and 0x0F)).toByte()
        bytes[7] = entropy[1]
        bytes[8] = (0x80 or (entropy[2].toInt() and 0x3F)).toByte()
        for (index in 9..15) {
            bytes[index] = entropy[index - 6]
        }

        return bytes.toCanonicalUuidText()
    }
}

private fun ByteArray.toCanonicalUuidText(): String = buildString(36) {
    for (index in indices) {
        if (index == 4 || index == 6 || index == 8 || index == 10) append('-')
        val unsigned = this@toCanonicalUuidText[index].toInt() and 0xFF
        append(HEX[unsigned ushr 4])
        append(HEX[unsigned and 0x0F])
    }
}

private const val HEX = "0123456789abcdef"
