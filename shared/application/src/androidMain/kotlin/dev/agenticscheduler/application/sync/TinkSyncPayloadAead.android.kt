package dev.agenticscheduler.application.sync

import android.util.Base64
import com.google.crypto.tink.Aead
import com.google.crypto.tink.KeysetHandle
import com.google.crypto.tink.aead.AeadConfig
import com.google.crypto.tink.aead.PredefinedAeadParameters
import java.nio.charset.StandardCharsets.UTF_8

/** Android Tink AES256_GCM adapter. Persisting its keyset is D8-02b's secure-store work. */
class TinkSyncPayloadAead private constructor(
    private val primitive: Aead,
) : SyncPayloadAead {
    override fun encryptToBase64Url(plaintextUtf8: String, associatedDataUtf8: String): String =
        Base64.encodeToString(
            primitive.encrypt(plaintextUtf8.toByteArray(UTF_8), associatedDataUtf8.toByteArray(UTF_8)),
            Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING,
        )

    override fun decryptFromBase64Url(ciphertextBase64Url: String, associatedDataUtf8: String): String =
        primitive.decrypt(
            Base64.decode(ciphertextBase64Url, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING),
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
