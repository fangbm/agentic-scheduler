package dev.agenticscheduler.application.sync

import kotlin.test.Test
import kotlin.test.assertEquals

class DeviceCredentialHashingTest {
    @Test
    fun `hash is canonical and stable`() {
        val credential = DeviceCredential("AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8")
        assertEquals(
            "Yw3NKWbEM2aRElRIu7JbT_QSpJxzLbLIq8G4WBvXEN0",
            DeviceCredentialHashing.sha256Base64Url(credential),
        )
    }
}
