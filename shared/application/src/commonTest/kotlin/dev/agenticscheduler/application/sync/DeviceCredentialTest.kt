package dev.agenticscheduler.application.sync

import kotlin.test.Test
import kotlin.test.assertFails
import kotlin.test.assertEquals

class DeviceCredentialTest {
    @Test
    fun `credential accepts canonical 256-bit base64url only`() {
        val value = "AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8"
        assertEquals(value, DeviceCredential(value).value)
        assertFails { DeviceCredential("not-a-credential") }
        assertFails { DeviceCredential("AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8=") }
    }
}
