package dev.agenticscheduler.application.id

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class UuidV7GeneratorTest {
    @Test fun `injected clock and bytes produce canonical UUIDv7`() {
        val generator = RfcUuidV7Generator(EpochMillisecondsClock { 0x0123456789ab }, RandomBytes { ByteArray(it) { index -> index.toByte() } })
        val value = generator.next()
        assertEquals("01234567-89ab-7607-8809-0a0b0c0d0e0f", value)
        assertTrue(Regex("^[0-9a-f]{8}-[0-9a-f]{4}-7[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$").matches(value))
    }

    @Test fun `distinct injected random bytes produce distinct IDs`() {
        var byte = 0
        val generator = RfcUuidV7Generator(EpochMillisecondsClock { 1 }, RandomBytes { ByteArray(it) { byte++.toByte() } })
        assertNotEquals(generator.next(), generator.next())
    }
}
