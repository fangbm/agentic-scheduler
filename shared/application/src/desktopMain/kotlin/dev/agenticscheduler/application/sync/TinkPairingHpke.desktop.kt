package dev.agenticscheduler.application.sync

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
import java.util.Base64
import java.io.ByteArrayOutputStream

/** Desktop Tink HPKE adapter for SYN-006A's X25519/HKDF-SHA256/AES-256-GCM RAW suite. */
class TinkPairingHpke : PairingHpke {
    override fun generateDeviceKeyPair(): PairingDeviceKeyPair {
        HybridConfig.register()
        val privateHandle = KeysetHandle.generateNew(parameters)
        val publicKey = (privateHandle.publicKeysetHandle.getAt(0).key as HpkePublicKey)
            .publicKeyBytes.toByteArray()
        return PairingDeviceKeyPair(
            publicKey = HpkePublicKeyBase64Url(base64Url(publicKey)),
            privateKey = TinkPrivateKey(privateHandle),
        )
    }

    override fun encrypt(publicKey: HpkePublicKeyBase64Url, plaintext: ByteArray, contextInfo: ByteArray): HpkeCiphertextComponents {
        HybridConfig.register()
        val rawPublic = decodeCanonicalBase64Url(publicKey.value, 32, "HPKE public key")
        val key = HpkePublicKey.create(parameters, Bytes.copyFrom(rawPublic), null)
        val handle = KeysetHandle.newBuilder()
            // Tink's in-memory KeysetHandle still requires a local key ID.
            // The HPKE parameter variant remains RAW, so this ID is never
            // emitted in SYN-006A's ciphertext wire format.
            .addEntry(KeysetHandle.importKey(key).withRandomId().makePrimary())
            .build()
        val combined = handle.getPrimitive(HybridEncrypt::class.java).encrypt(plaintext, contextInfo)
        require(combined.size > ENCAPSULATED_KEY_BYTES) { "Tink HPKE ciphertext did not include an encapsulated key." }
        return HpkeCiphertextComponents(
            encapsulatedKeyBase64Url = base64Url(combined.copyOfRange(0, ENCAPSULATED_KEY_BYTES)),
            ciphertextBase64Url = base64Url(combined.copyOfRange(ENCAPSULATED_KEY_BYTES, combined.size)),
        )
    }

    override fun decrypt(
        privateKey: PairingPrivateKeyMaterial,
        encapsulatedKeyBase64Url: String,
        ciphertextBase64Url: String,
        contextInfo: ByteArray,
    ): ByteArray {
        require(privateKey is TinkPrivateKey) { "Private pairing key is not owned by the desktop Tink adapter." }
        requireCanonicalBase64Url(encapsulatedKeyBase64Url, ENCAPSULATED_KEY_BYTES, "HPKE encapsulated key")
        requireCanonicalBase64Url(ciphertextBase64Url, null, "HPKE ciphertext")
        val combined = Base64.getUrlDecoder().decode(encapsulatedKeyBase64Url) + Base64.getUrlDecoder().decode(ciphertextBase64Url)
        return privateKey.handle.getPrimitive(HybridDecrypt::class.java).decrypt(combined, contextInfo)
    }

    /**
     * Serializes only for immediate handoff to [DesktopPlatformSecureStore].
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

    /** The enclosing DPAPI or Secret Service store provides persistence encryption. */
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
        fun base64Url(value: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(value)
    }
}
