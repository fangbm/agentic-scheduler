package dev.agenticscheduler.wear

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.agenticscheduler.application.sync.AndroidKeystoreSecureStore
import dev.agenticscheduler.application.sync.ImportedContentKeyMaterial
import dev.agenticscheduler.application.sync.PairingEphemeralKeyMaterial
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Wear uses the same Android Keystore-backed SYN-009 boundary as the phone. */
@RunWith(AndroidJUnit4::class)
class WearKeystoreSecureStoreInstrumentedTest {
    private val context: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Before
    fun clearEncryptedRecords() {
        assertEquals(true, context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).edit().clear().commit())
    }

    @Test
    fun contentKeySurvivesStoreRecreationAndDeleteFailsClosed() = runBlocking {
        val first = AndroidKeystoreSecureStore(context)
        val content = first.importContentKey(RawContentKey())
        val reopened = AndroidKeystoreSecureStore(context)
        val aead = assertNotNull(reopened.contentAead(content.reference))
        val ciphertext = aead.encryptToBase64Url("wear payload", "wear aad")
        assertEquals("wear payload", aead.decryptFromBase64Url(ciphertext, "wear aad"))
        reopened.delete(content.reference)
        assertNull(AndroidKeystoreSecureStore(context).contentAead(content.reference))
    }

    private class RawContentKey : PairingEphemeralKeyMaterial, ImportedContentKeyMaterial {
        private val raw = ByteArray(32) { it.toByte() }
        override fun copyRawKeyBytesForPairing(): ByteArray = raw.copyOf()
    }

    private companion object {
        const val PREFERENCES = "agentic_scheduler_secure_store_v1"
    }
}
