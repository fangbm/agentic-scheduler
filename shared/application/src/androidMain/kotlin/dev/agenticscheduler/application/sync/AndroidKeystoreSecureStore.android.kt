package dev.agenticscheduler.application.sync

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * SYN-009 Android/Wear store. SharedPreferences contains only ciphertext made
 * by a non-exportable Android Keystore AES-GCM key; it has no plaintext
 * fallback. The same store stages AMK, recovery material, pairing identities,
 * and SyncSpace content keys behind opaque references.
 */
class AndroidKeystoreSecureStore(
    context: Context,
    private val pairingHpke: TinkPairingHpke = TinkPairingHpke(),
) : PlatformSecretStore,
    PlatformKeyMaterialStore,
    PlatformPairingPrivateKeyStore,
    PlatformPairingKeyMaterialExporter,
    PlatformAccountMasterKeyStore {
    private val applicationContext = context.applicationContext
    private val preferences = applicationContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    override suspend fun importSecret(material: PlatformSecretMaterial): SecretReference =
        store(SecretKind.GENERIC, material.copyRawSecretBytesForSecureStore())

    override suspend fun readSecret(reference: SecretReference): PlatformSecretMaterial? =
        read(reference, SecretKind.GENERIC)?.let(::StoredSecret)

    override suspend fun importContentKey(material: ImportedContentKeyMaterial): ImportedContentKey {
        val raw = material.copyRawSecretBytesForSecureStore()
        require(raw.size == CONTENT_KEY_BYTES) { "SyncSpace content key must be exactly 32 bytes." }
        return ImportedContentKey(store(SecretKind.CONTENT_KEY, raw), ContentKeyIdentity.fromRawAes256Key(raw))
    }

    override suspend fun contentAead(reference: SecretReference): SyncPayloadAead? =
        read(reference, SecretKind.CONTENT_KEY)
            ?.takeIf { it.size == CONTENT_KEY_BYTES }
            ?.let(TinkSyncPayloadAead::fromRawContentKey)

    override suspend fun generatePairingDeviceKey(): PersistedPairingDeviceKey {
        val generated = pairingHpke.generateDeviceKeyPair()
        val serialized = pairingHpke.serializeForSecureStore(generated.privateKey)
        return PersistedPairingDeviceKey(generated.publicKey, store(SecretKind.PAIRING_PRIVATE_KEY, serialized))
    }

    override suspend fun privateKey(reference: SecretReference): PairingPrivateKeyMaterial? =
        try {
            read(reference, SecretKind.PAIRING_PRIVATE_KEY)?.let(pairingHpke::restoreFromSecureStore)
        } catch (_: Throwable) {
            null
        }

    override suspend fun exportAccountMasterKeyForPairing(reference: SecretReference): PairingEphemeralKeyMaterial? =
        read(reference, SecretKind.ACCOUNT_MASTER_KEY)?.let(::StoredSecret)

    override suspend fun exportContentKeyForPairing(reference: SecretReference): ExportedPairingContentKey? =
        read(reference, SecretKind.CONTENT_KEY)
            ?.takeIf { it.size == CONTENT_KEY_BYTES }
            ?.let { raw -> ExportedPairingContentKey(StoredSecret(raw), ContentKeyIdentity.fromRawAes256Key(raw)) }

    override suspend fun importAccountMasterKeyForPairing(material: PairingEphemeralKeyMaterial): SecretReference {
        val raw = material.copyRawKeyBytesForPairing()
        require(raw.size == CONTENT_KEY_BYTES) { "Account master key must be exactly 32 bytes." }
        return store(SecretKind.ACCOUNT_MASTER_KEY, raw)
    }

    override suspend fun delete(reference: SecretReference) {
        val id = referenceId(reference) ?: return
        check(preferences.edit().remove(id).commit()) { "Android secure-store ciphertext could not be deleted." }
    }

    private fun store(kind: SecretKind, raw: ByteArray): SecretReference {
        val id = UUID.randomUUID().toString()
        val reference = SecretReference("$REFERENCE_PREFIX$id")
        val plaintext = byteArrayOf(kind.tag) + raw
        val cipher = Cipher.getInstance(CIPHER)
        cipher.init(Cipher.ENCRYPT_MODE, masterKey())
        cipher.updateAAD(reference.value.encodeToByteArray())
        val encoded = byteArrayOf(FORMAT_VERSION, cipher.iv.size.toByte()) + cipher.iv + cipher.doFinal(plaintext)
        check(preferences.edit().putString(id, Base64.encodeToString(encoded, Base64.NO_WRAP)).commit()) {
            "Android secure-store ciphertext could not be persisted."
        }
        return reference
    }

    private fun read(reference: SecretReference, expected: SecretKind): ByteArray? {
        val id = referenceId(reference) ?: return null
        val encoded = preferences.getString(id, null) ?: return null
        return try {
            val packed = Base64.decode(encoded, Base64.NO_WRAP)
            if (packed.size < 2 || packed[0] != FORMAT_VERSION) return null
            val ivSize = packed[1].toInt() and 0xff
            if (ivSize !in 12..16 || packed.size <= 2 + ivSize) return null
            val cipher = Cipher.getInstance(CIPHER)
            cipher.init(Cipher.DECRYPT_MODE, masterKey(), GCMParameterSpec(GCM_TAG_BITS, packed.copyOfRange(2, 2 + ivSize)))
            cipher.updateAAD(reference.value.encodeToByteArray())
            val plaintext = cipher.doFinal(packed.copyOfRange(2 + ivSize, packed.size))
            if (plaintext.isEmpty() || plaintext[0] != expected.tag) null else plaintext.copyOfRange(1, plaintext.size)
        } catch (_: Throwable) {
            null
        }
    }

    private fun masterKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (keyStore.getKey(MASTER_KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(MASTER_KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return generator.generateKey()
    }

    private fun referenceId(reference: SecretReference): String? =
        reference.value.removePrefix(REFERENCE_PREFIX)
            .takeIf { reference.value.startsWith(REFERENCE_PREFIX) && UUID_PATTERN.matches(it) }

    private class StoredSecret(private val raw: ByteArray) : PairingEphemeralKeyMaterial, ImportedContentKeyMaterial {
        override fun copyRawKeyBytesForPairing(): ByteArray = raw.copyOf()
    }

    private enum class SecretKind(val tag: Byte) {
        GENERIC(1), CONTENT_KEY(2), ACCOUNT_MASTER_KEY(3), PAIRING_PRIVATE_KEY(4),
    }

    private companion object {
        const val PREFERENCES = "agentic_scheduler_secure_store_v1"
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val MASTER_KEY_ALIAS = "agentic-scheduler.d8.secure-store.v1"
        const val REFERENCE_PREFIX = "android-keystore://"
        const val CIPHER = "AES/GCM/NoPadding"
        const val FORMAT_VERSION: Byte = 1
        const val GCM_TAG_BITS = 128
        const val CONTENT_KEY_BYTES = 32
        val UUID_PATTERN = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
    }
}
