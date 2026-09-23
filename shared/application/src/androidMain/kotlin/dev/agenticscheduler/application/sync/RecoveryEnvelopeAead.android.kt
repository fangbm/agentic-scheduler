package dev.agenticscheduler.application.sync

import com.google.crypto.tink.Aead
import com.google.crypto.tink.InsecureSecretKeyAccess
import com.google.crypto.tink.KeysetHandle
import com.google.crypto.tink.aead.AeadConfig
import com.google.crypto.tink.aead.AesGcmKey
import com.google.crypto.tink.aead.AesGcmParameters
import com.google.crypto.tink.util.SecretBytes

internal actual fun recoveryEnvelopeAeadEncrypt(
    rawKey: ByteArray,
    plaintext: ByteArray,
    associatedData: ByteArray,
): ByteArray = recoveryEnvelopeAead(rawKey).encrypt(plaintext, associatedData)

internal actual fun recoveryEnvelopeAeadDecrypt(
    rawKey: ByteArray,
    ciphertext: ByteArray,
    associatedData: ByteArray,
): ByteArray = recoveryEnvelopeAead(rawKey).decrypt(ciphertext, associatedData)

private fun recoveryEnvelopeAead(rawKey: ByteArray): Aead {
    require(rawKey.size == 32) { "Recovery envelope key must be exactly 32 bytes." }
    AeadConfig.register()
    val parameters = AesGcmParameters.builder()
        .setKeySizeBytes(32)
        .setIvSizeBytes(12)
        .setTagSizeBytes(16)
        .setVariant(AesGcmParameters.Variant.NO_PREFIX)
        .build()
    val key = AesGcmKey.builder()
        .setParameters(parameters)
        .setKeyBytes(SecretBytes.copyFrom(rawKey, InsecureSecretKeyAccess.get()))
        .build()
    return KeysetHandle.newBuilder()
        .addEntry(KeysetHandle.importKey(key).withRandomId().makePrimary())
        .build()
        .getPrimitive(Aead::class.java)
}
