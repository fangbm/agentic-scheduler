package dev.agenticscheduler.application.sync

import android.content.Context
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.security.KeyStore
import java.security.SecureRandom
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties

/** Android/Wear secure credential store; preferences contain ciphertext only. */
class AndroidKeystoreDeviceCredentialStore(context: Context) : PlatformDeviceCredentialStore {
    private val preferences = context.applicationContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    override suspend fun generate(): GeneratedDeviceCredential {
        val raw = ByteArray(CREDENTIAL_BYTES)
        SecureRandom().nextBytes(raw)
        val credential = try {
            DeviceCredential(Base64.encodeToString(raw, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING))
        } finally {
            raw.fill(0)
        }
        return GeneratedDeviceCredential(
            reference = store(credential),
            hashBase64Url = DeviceCredentialHashing.sha256Base64Url(credential),
        )
    }

    override suspend fun store(value: DeviceCredential): SecretReference = withContext(Dispatchers.IO) {
        val id = UUID.randomUUID().toString()
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val iv = Base64.encodeToString(cipher.iv, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
        val ciphertext = Base64.encodeToString(cipher.doFinal(value.value.encodeToByteArray()), Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
        check(preferences.edit().putString(id, "$iv.$ciphertext").commit()) { "DeviceCredential storage failed." }
        SecretReference(REFERENCE_PREFIX + id)
    }

    override suspend fun load(reference: SecretReference): DeviceCredential? = withContext(Dispatchers.IO) {
        val id = reference.value.removePrefix(REFERENCE_PREFIX).takeIf { reference.value.startsWith(REFERENCE_PREFIX) && it.isNotBlank() }
            ?: return@withContext null
        val encoded = preferences.getString(id, null) ?: return@withContext null
        val parts = encoded.split('.', limit = 2)
        if (parts.size != 2) return@withContext null
        try {
            val iv = Base64.decode(parts[0], Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
            val ciphertext = Base64.decode(parts[1], Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, iv))
            DeviceCredential(cipher.doFinal(ciphertext).decodeToString())
        } catch (_: Throwable) {
            null
        }
    }

    override suspend fun delete(reference: SecretReference) = withContext(Dispatchers.IO) {
        val id = reference.value.removePrefix(REFERENCE_PREFIX).takeIf { reference.value.startsWith(REFERENCE_PREFIX) }
            ?: return@withContext
        check(preferences.edit().remove(id).commit()) { "DeviceCredential deletion failed." }
    }

    private fun key(): SecretKey = synchronized(this) {
        val store = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (store.getKey(KEY_ALIAS, null) as? SecretKey) ?: KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE).apply {
            init(
                KeyGenParameterSpec.Builder(
                    KEY_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                ).setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .build(),
            )
        }.generateKey()
    }

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val KEY_ALIAS = "agentic-scheduler-device-credential-v1"
        const val PREFERENCES = "agentic-scheduler-secure-credential-v1"
        const val REFERENCE_PREFIX = "android-keystore://device-credential/"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val CREDENTIAL_BYTES = 32
    }
}
