package dev.agenticscheduler.application.sync

import com.google.crypto.tink.Aead
import com.google.crypto.tink.KeysetHandle
import com.google.crypto.tink.aead.AeadConfig
import com.google.crypto.tink.aead.PredefinedAeadParameters
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
    }
}
