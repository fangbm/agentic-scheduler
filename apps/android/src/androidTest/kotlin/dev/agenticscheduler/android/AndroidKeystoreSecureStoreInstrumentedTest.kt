package dev.agenticscheduler.android

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.agenticscheduler.application.sync.AndroidKeystoreSecureStore
import dev.agenticscheduler.application.sync.ImportedContentKeyMaterial
import dev.agenticscheduler.application.sync.PairingEphemeralKeyMaterial
import dev.agenticscheduler.application.sync.SecretReference
import dev.agenticscheduler.application.sync.TinkPairingHpke
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Runs on an emulator/device: this is not a JVM mock of Android Keystore. */
@RunWith(AndroidJUnit4::class)
class AndroidKeystoreSecureStoreInstrumentedTest {
    private val context: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Before
    fun clearEncryptedRecords() {
        assertEquals(true, context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).edit().clear().commit())
    }

    @Test
    fun keystoreBackedSecretsSurviveStoreRecreationAndFailClosed() = runBlocking {
        val first = AndroidKeystoreSecureStore(context)
        val generic = first.importSecret(RawSecret(0))
        val content = first.importContentKey(RawSecret(32))
        val device = first.generatePairingDeviceKey()

        val reopened = AndroidKeystoreSecureStore(context)
        val restoredGeneric = checkNotNull(reopened.readSecret(generic))
        assertArrayEquals(RawSecret(0).raw(), restoredGeneric.copyRawSecretBytesForSecureStore())
        assertNull(reopened.contentAead(generic))
        assertNull(reopened.readSecret(content.reference))

        val aead = checkNotNull(reopened.contentAead(content.reference))
        val encryptedPayload = aead.encryptToBase64Url("keystore payload", "keystore aad")
        assertEquals("keystore payload", aead.decryptFromBase64Url(encryptedPayload, "keystore aad"))

        val restoredPrivateKey = checkNotNull(reopened.privateKey(device.privateKeyReference))
        val contextInfo = "keystore-pairing-restart".encodeToByteArray()
        val encryptedPackage = TinkPairingHpke().encrypt(device.publicKey, "pairing package".encodeToByteArray(), contextInfo)
        assertArrayEquals(
            "pairing package".encodeToByteArray(),
            TinkPairingHpke().decrypt(
                restoredPrivateKey,
                encryptedPackage.encapsulatedKeyBase64Url,
                encryptedPackage.ciphertextBase64Url,
                contextInfo,
            ),
        )

        assertEquals(true, context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
            .edit()
            .putString(storageKey(generic), "not-valid-base64")
            .commit())
        assertNull(AndroidKeystoreSecureStore(context).readSecret(generic))

        reopened.delete(content.reference)
        assertNull(AndroidKeystoreSecureStore(context).contentAead(content.reference))
        assertNull(AndroidKeystoreSecureStore(context).readSecret(SecretReference("android-keystore://00000000-0000-0000-0000-000000000000")))
        Unit
    }

    private fun storageKey(reference: SecretReference): String {
        assertFalse(reference.value == REFERENCE_PREFIX)
        return reference.value.removePrefix(REFERENCE_PREFIX)
    }

    private class RawSecret(start: Int) : PairingEphemeralKeyMaterial, ImportedContentKeyMaterial {
        private val value = ByteArray(32) { (start + it).toByte() }
        fun raw(): ByteArray = value.copyOf()
        override fun copyRawKeyBytesForPairing(): ByteArray = value.copyOf()
        override fun copyRawSecretBytesForSecureStore(): ByteArray = value.copyOf()
    }

    private companion object {
        const val PREFERENCES = "agentic_scheduler_secure_store_v1"
        const val REFERENCE_PREFIX = "android-keystore://"
    }
}
