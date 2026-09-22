package dev.agenticscheduler.application.sync

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class DesktopPlatformSecureStoreTest {
    @Test
    fun `secure-store contract survives reopen and fails closed for missing or deleted references`() = runBlocking {
        val backend = MemoryBackend()
        val first = DesktopPlatformSecureStore(backend, TinkPairingHpke())
        val rawContent = RawMaterial(0)
        val content = first.importContentKey(rawContent)
        assertEquals(ContentKeyIdentity.fromRawAes256Key(rawContent.copyRawKeyBytesForPairing()), content.identity)

        val aead = assertNotNull(first.contentAead(content.reference))
        val ciphertext = aead.encryptToBase64Url("payload", "aad")
        assertEquals("payload", aead.decryptFromBase64Url(ciphertext, "aad"))
        assertEquals(rawContent.copyRawKeyBytesForPairing().toList(), first.exportContentKeyForPairing(content.reference)?.material?.copyRawKeyBytesForPairing()?.toList())

        val account = first.importAccountMasterKeyForPairing(RawMaterial(32))
        assertEquals(32, first.exportAccountMasterKeyForPairing(account)?.copyRawKeyBytesForPairing()?.size)

        val generic = first.importSecret(RawMaterial(64))
        assertEquals(RawMaterial(64).copyRawKeyBytesForPairing().toList(), first.readSecret(generic)?.copyRawSecretBytesForSecureStore()?.toList())

        val second = DesktopPlatformSecureStore(backend, TinkPairingHpke())
        val credential = DeviceCredential("AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8")
        val credentialReference = first.store(credential)
        assertEquals(credential, second.load(credentialReference))

        val device = first.generatePairingDeviceKey()
        val restored = assertNotNull(second.privateKey(device.privateKeyReference))
        val context = "pairing-restart-context".encodeToByteArray()
        val encrypted = TinkPairingHpke().encrypt(device.publicKey, "secret package".encodeToByteArray(), context)
        assertEquals(
            "secret package",
            TinkPairingHpke().decrypt(restored, encrypted.encapsulatedKeyBase64Url, encrypted.ciphertextBase64Url, context).decodeToString(),
        )

        first.delete(content.reference)
        assertNull(second.contentAead(content.reference))
        first.delete(credentialReference)
        assertNull(second.load(credentialReference))
        assertNull(second.contentAead(SecretReference(backend.referencePrefix + "00000000-0000-0000-0000-000000000000")))
    }

    @Test
    fun `secure-store write failure returns no reference to publish`() = runBlocking {
        val store = DesktopPlatformSecureStore(ThrowingBackend, TinkPairingHpke())
        assertFails { store.importContentKey(RawMaterial(0)) }
    }

    @Test
    fun `unavailable Linux Secret Service is explicit and never falls back to a file`() {
        val failure = assertFailsWith<SecureStoreUnavailableException> {
            LinuxSecretServiceSecureBackend(command = "agentic-scheduler-no-secret-tool").store("test", ByteArray(32))
        }
        assertEquals(
            "Linux Secret Service is unavailable or locked. Start and unlock a Secret Service keyring, then retry sync or pairing.",
            failure.message,
        )
    }

    @Test
    fun `Windows DPAPI implementation survives a real read and delete when available`() = runBlocking {
        if (!System.getProperty("os.name").startsWith("Windows", ignoreCase = true)) return@runBlocking
        val store = DesktopPlatformSecureStore()
        val reference = store.importSecret(RawMaterial(96))
        assertEquals(32, store.readSecret(reference)?.copyRawSecretBytesForSecureStore()?.size)
        store.delete(reference)
        assertNull(store.readSecret(reference))
    }

    private class RawMaterial(start: Int) : PairingEphemeralKeyMaterial, ImportedContentKeyMaterial {
        private val raw = ByteArray(32) { (start + it).toByte() }
        override fun copyRawKeyBytesForPairing(): ByteArray = raw.copyOf()
    }

    private class MemoryBackend : DesktopSecureBackend {
        override val referencePrefix = "test-memory://"
        private val values = mutableMapOf<String, ByteArray>()
        override fun store(id: String, value: ByteArray) { values[id] = value.copyOf() }
        override fun read(id: String): ByteArray? = values[id]?.copyOf()
        override fun delete(id: String) { values.remove(id) }
    }

    private object ThrowingBackend : DesktopSecureBackend {
        override val referencePrefix = "test-throwing://"
        override fun store(id: String, value: ByteArray): Nothing = error("secure store unavailable")
        override fun read(id: String): ByteArray? = null
        override fun delete(id: String) = Unit
    }
}
