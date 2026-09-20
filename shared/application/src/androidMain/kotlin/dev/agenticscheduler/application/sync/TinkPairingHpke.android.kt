package dev.agenticscheduler.application.sync

import android.util.Base64
import com.google.crypto.tink.HybridDecrypt
import com.google.crypto.tink.HybridEncrypt
import com.google.crypto.tink.Aead
import com.google.crypto.tink.BinaryKeysetReader
import com.google.crypto.tink.BinaryKeysetWriter
import com.google.crypto.tink.KeysetHandle
import com.google.crypto.tink.hybrid.HpkeParameters
import com.google.crypto.tink.hybrid.HpkePublicKey
import com.google.crypto.tink.hybrid.HybridConfig
import com.google.crypto.tink.util.Bytes
import dev.agenticscheduler.sync.HpkePublicKeyBase64Url
import dev.agenticscheduler.sync.decodeCanonicalBase64Url
import dev.agenticscheduler.sync.requireCanonicalBase64Url
import java.io.ByteArrayOutputStream

/** Android Tink HPKE adapter for SYN-006A's X25519/HKDF-SHA256/AES-256-GCM RAW suite. */
class TinkPairingHpke : PairingHpke {
    override fun generateDeviceKeyPair(): PairingDeviceKeyPair {
        HybridConfig.register()
        val privateHandle = KeysetHandle.generateNew(parameters)
        val publicKey = (privateHandle.publicKeysetHandle.getAt(0).key as HpkePublicKey)
            .publicKeyBytes.toByteArray()
        return PairingDeviceKeyPair(HpkePublicKeyBase64Url(base64Url(publicKey)), TinkPrivateKey(privateHandle))
    }

    override fun encrypt(publicKey: HpkePublicKeyBase64Url, plaintext: ByteArray, contextInfo: ByteArray): HpkeCiphertextComponents {
        HybridConfig.register()
        val rawPublic = decodeCanonicalBase64Url(publicKey.value, 32, "HPKE public key")
        val key = HpkePublicKey.create(parameters, Bytes.copyFrom(rawPublic), null)
        val handle = KeysetHandle.newBuilder()
            // Tink requires a local KeysetHandle ID even for RAW output.
            // SYN-006A's HPKE ciphertext remains NO_PREFIX on the wire.
            .addEntry(KeysetHandle.importKey(key).withRandomId().makePrimary())
            .build()
        val combined = handle.getPrimitive(HybridEncrypt::class.java).encrypt(plaintext, contextInfo)
        require(combined.size > ENCAPSULATED_KEY_BYTES)
        return HpkeCiphertextComponents(base64Url(combined.copyOfRange(0, ENCAPSULATED_KEY_BYTES)), base64Url(combined.copyOfRange(ENCAPSULATED_KEY_BYTES, combined.size)))
    }

    override fun decrypt(privateKey: PairingPrivateKeyMaterial, encapsulatedKeyBase64Url: String, ciphertextBase64Url: String, contextInfo: ByteArray): ByteArray {
        require(privateKey is TinkPrivateKey)
        requireCanonicalBase64Url(encapsulatedKeyBase64Url, ENCAPSULATED_KEY_BYTES, "HPKE encapsulated key")
        requireCanonicalBase64Url(ciphertextBase64Url, null, "HPKE ciphertext")
        val combined = Base64.decode(encapsulatedKeyBase64Url, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING) + Base64.decode(ciphertextBase64Url, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
        return privateKey.handle.getPrimitive(HybridDecrypt::class.java).decrypt(combined, contextInfo)
    }

    /**
     * Serializes only for immediate handoff to [AndroidKeystoreSecureStore].
     * The returned bytes must never be persisted except through that store.
     */
    internal fun serializeForSecureStore(privateKey: PairingPrivateKeyMaterial): ByteArray {
        require(privateKey is TinkPrivateKey)
        val output = ByteArrayOutputStream()
        privateKey.handle.write(BinaryKeysetWriter.withOutputStream(output), TransitAead)
        return output.toByteArray()
    }

    internal fun restoreFromSecureStore(serialized: ByteArray): PairingPrivateKeyMaterial =
        TinkPrivateKey(KeysetHandle.read(BinaryKeysetReader.withBytes(serialized), TransitAead))

    private data class TinkPrivateKey(val handle: KeysetHandle) : PairingPrivateKeyMaterial

    /** The enclosing Android Keystore store provides persistence encryption. */
    private object TransitAead : Aead {
        override fun encrypt(plaintext: ByteArray, associatedData: ByteArray): ByteArray = plaintext
        override fun decrypt(ciphertext: ByteArray, associatedData: ByteArray): ByteArray = ciphertext
    }

    private companion object {
        const val ENCAPSULATED_KEY_BYTES = 32
        val parameters: HpkeParameters = HpkeParameters.builder()
            .setKemId(HpkeParameters.KemId.DHKEM_X25519_HKDF_SHA256)
            .setKdfId(HpkeParameters.KdfId.HKDF_SHA256)
            .setAeadId(HpkeParameters.AeadId.AES_256_GCM)
            .setVariant(HpkeParameters.Variant.NO_PREFIX)
            .build()
        fun base64Url(value: ByteArray): String = Base64.encodeToString(value, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
    }
}
