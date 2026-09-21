package dev.agenticscheduler.application.sync

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class RecoveryRegistrationProofTest {
    @Test
    fun `proof binds account and counter`() {
        val secret = RecoverySecret("AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8")
        val proof = RecoveryRegistrationProof.calculate(secret, "account", 0)
        assertEquals("eEt19dQBo6guJUAT8PqFGq-Tmpl7qgT-nx4paRcS9KI", proof)
        assertEquals("Lh1bOGk1mzNvYzsEvuO4yOzwn9OZzZiigx43inc02tU", RecoveryRegistrationProof.hash(proof))
        assertEquals(proof, RecoveryRegistrationProof.calculate(secret, "account", 0))
        assertNotEquals(proof, RecoveryRegistrationProof.calculate(secret, "account", 1))
    }
}
