package dev.agenticscheduler.application.sync

import com.google.crypto.tink.Aead
import com.google.crypto.tink.InsecureSecretKeyAccess
import com.google.crypto.tink.KeysetHandle
import com.google.crypto.tink.aead.AeadConfig
import com.google.crypto.tink.aead.AesGcmKey
import com.google.crypto.tink.aead.AesGcmParameters
import com.google.crypto.tink.aead.PredefinedAeadParameters
import com.google.crypto.tink.util.SecretBytes
import java.nio.charset.StandardCharsets.UTF_8
import java.util.Base64

/** Desktop Tink AES256_GCM adapter. Tink owns key generation and nonce handling. */
class TinkSyncPayloadAead private constructor(
    private val primitive: Aead,
) : SyncPayloadAead {
    override fun encryptToBase64Url(plaintextUtf8: String, associatedDataUtf8: String): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(
            primitive.encrypt(plaintextUtf8.toByteArray(UTF_8), associatedDataUtf8.toByteArray(UTF_8)),
        )

    override fun decryptFromBase64Url(ciphertextBase64Url: String, associatedDataUtf8: String): String =
        primitive.decrypt(
            Base64.getUrlDecoder().decode(ciphertextBase64Url),
            associatedDataUtf8.toByteArray(UTF_8),
        ).toString(UTF_8)

    companion object {
        fun generate(): TinkSyncPayloadAead {
            AeadConfig.register()
            val handle = KeysetHandle.generateNew(PredefinedAeadParameters.AES256_GCM)
            return TinkSyncPayloadAead(handle.getPrimitive(Aead::class.java))
        }

        /** Reconstructs the v1 raw AES-256 content key without a local Tink key ID. */
        internal fun fromRawContentKey(raw: ByteArray): TinkSyncPayloadAead {
            require(raw.size == 32) { "SyncSpace content key must be exactly 32 bytes." }
            AeadConfig.register()
            val parameters = AesGcmParameters.builder()
                .setKeySizeBytes(32)
                .setIvSizeBytes(12)
                .setTagSizeBytes(16)
                .setVariant(AesGcmParameters.Variant.NO_PREFIX)
                .build()
            val key = AesGcmKey.builder()
                .setParameters(parameters)
                .setKeyBytes(SecretBytes.copyFrom(raw, InsecureSecretKeyAccess.get()))
                .build()
            val handle = KeysetHandle.newBuilder()
                // Tink requires a local key ID in an in-memory keyset.  The
                // NO_PREFIX variant keeps that ID out of the D8 v1 payload
                // ciphertext, so replicas still share the raw AES key only.
                .addEntry(KeysetHandle.importKey(key).withRandomId().makePrimary())
                .build()
            return TinkSyncPayloadAead(handle.getPrimitive(Aead::class.java))
        }
    }
}
