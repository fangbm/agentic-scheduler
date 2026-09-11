package dev.agenticscheduler.application.id

/** Injectable production-ID boundary; domain constructors remain ID consumers only. */
fun interface EpochMillisecondsClock { fun nowMilliseconds(): Long }
fun interface RandomBytes { fun nextBytes(size: Int): ByteArray }

interface UuidV7Generator { fun next(): String }

class RfcUuidV7Generator(
    private val clock: EpochMillisecondsClock,
    private val random: RandomBytes,
) : UuidV7Generator {
    override fun next(): String {
        val timestamp = clock.nowMilliseconds()
        require(timestamp >= 0 && timestamp ushr 48 == 0L) { "UUIDv7 timestamp must fit 48 bits." }
        val bytes = random.nextBytes(16)
        require(bytes.size == 16) { "UUIDv7 random source must provide 16 bytes." }
        for (index in 0 until 6) bytes[index] = (timestamp ushr ((5 - index) * 8)).toByte()
        bytes[6] = ((bytes[6].toInt() and 0x0f) or 0x70).toByte()
        bytes[8] = ((bytes[8].toInt() and 0x3f) or 0x80).toByte()
        return bytes.joinToString("") { "%02x".format(it.toInt() and 0xff) }
            .let { "${it.substring(0, 8)}-${it.substring(8, 12)}-${it.substring(12, 16)}-${it.substring(16, 20)}-${it.substring(20)}" }
    }
}

expect fun productionUuidV7Generator(): UuidV7Generator
