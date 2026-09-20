package dev.agenticscheduler.application.sync

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * Runs against the actual libsecret `secret-tool` bridge only when the test
 * process was explicitly provisioned with a Secret Service session. Ordinary
 * desktop builds deliberately do not pretend a developer's keyring is usable.
 */
class LinuxSecretServiceSecureStoreIntegrationTest {
    @Test
    fun `Secret Service preserves generic content and pairing keys across store recreation`() = runBlocking {
        if (!System.getProperty("os.name").startsWith("Linux", ignoreCase = true) ||
            System.getenv(SECRET_SERVICE_INTEGRATION_ENV) != "1"
        ) return@runBlocking

        val first = DesktopPlatformSecureStore()
        val references = mutableListOf<SecretReference>()
        try {
            val generic = first.importSecret(RawSecret(0)).also(references::add)
            assertEquals(RawSecret(0).bytes(), first.readSecret(generic)?.copyRawSecretBytesForSecureStore()?.toList())

            val content = first.importContentKey(RawSecret(32)).also { references += it.reference }
            val reopened = DesktopPlatformSecureStore()
            val aead = assertNotNull(reopened.contentAead(content.reference))
            val ciphertext = aead.encryptToBase64Url("secret-service", "linux-aad")
            assertEquals("secret-service", aead.decryptFromBase64Url(ciphertext, "linux-aad"))

            val device = first.generatePairingDeviceKey().also { references += it.privateKeyReference }
            val restored = assertNotNull(reopened.privateKey(device.privateKeyReference))
            val context = "linux-secret-service-pairing".encodeToByteArray()
            val packageCiphertext = TinkPairingHpke().encrypt(device.publicKey, "pairing package".encodeToByteArray(), context)
            assertEquals(
                "pairing package",
                TinkPairingHpke().decrypt(
                    restored,
                    packageCiphertext.encapsulatedKeyBase64Url,
                    packageCiphertext.ciphertextBase64Url,
                    context,
                ).decodeToString(),
            )

            reopened.delete(generic)
            assertNull(DesktopPlatformSecureStore().readSecret(generic))
        } finally {
            for (reference in references.asReversed()) {
                try {
                    first.delete(reference)
                } catch (_: Throwable) {
                    // The random test reference can safely expire with the
                    // temporary CI keyring if a cleanup assertion already failed.
                }
            }
        }
    }

    private class RawSecret(start: Int) : PairingEphemeralKeyMaterial, ImportedContentKeyMaterial {
        private val raw = ByteArray(32) { (start + it).toByte() }
        fun bytes(): List<Byte> = raw.toList()
        override fun copyRawKeyBytesForPairing(): ByteArray = raw.copyOf()
        override fun copyRawSecretBytesForSecureStore(): ByteArray = raw.copyOf()
    }

    private companion object {
        const val SECRET_SERVICE_INTEGRATION_ENV = "AGENTIC_SCHEDULER_SECRET_SERVICE_INTEGRATION"
    }
}
