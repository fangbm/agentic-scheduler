package dev.agenticscheduler.application.sync

import dev.agenticscheduler.sync.AccountId
import dev.agenticscheduler.sync.DeviceId
import dev.agenticscheduler.sync.EnrollmentRequestId
import dev.agenticscheduler.sync.HpkePublicKeyBase64Url
import dev.agenticscheduler.sync.KeyPackageEnvelopeV1
import dev.agenticscheduler.sync.KeyPackagePlaintextV1
import dev.agenticscheduler.sync.PairingWireDecodeResult
import dev.agenticscheduler.sync.PairingWireCodec
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/** Opaque, local-only private HPKE identity. It must be retained by a secure-store implementation. */
interface PairingPrivateKeyMaterial

data class PairingDeviceKeyPair(
    val publicKey: HpkePublicKeyBase64Url,
    val privateKey: PairingPrivateKeyMaterial,
)

data class HpkeCiphertextComponents(
    val encapsulatedKeyBase64Url: String,
    val ciphertextBase64Url: String,
)

/** SYN-006A's fixed HPKE base-mode suite; implementations must use RAW/NO_PREFIX. */
interface PairingHpke {
    fun generateDeviceKeyPair(): PairingDeviceKeyPair
    fun encrypt(
        publicKey: HpkePublicKeyBase64Url,
        plaintext: ByteArray,
        contextInfo: ByteArray,
    ): HpkeCiphertextComponents

    fun decrypt(
        privateKey: PairingPrivateKeyMaterial,
        encapsulatedKeyBase64Url: String,
        ciphertextBase64Url: String,
        contextInfo: ByteArray,
    ): ByteArray
}

/** Exact SYN-006A-2 length-prefixed HPKE contextInfo/AAD bytes. */
object KeyPackageContextV1 {
    private val label = "agentic-scheduler-key-package".encodeToByteArray()

    fun bytes(
        accountId: AccountId,
        requestId: EnrollmentRequestId,
        targetDeviceId: DeviceId,
        keyPackageVersion: Int,
        keyEpoch: Long,
    ): ByteArray {
        require(keyPackageVersion == PairingWireCodec.KEY_PACKAGE_VERSION)
        require(keyEpoch >= 0)
        return ByteBuilder().apply {
            write(label); writeByte(0)
            writeLengthPrefixed(accountId.value); writeLengthPrefixed(requestId.value); writeLengthPrefixed(targetDeviceId.value)
            writeU32(keyPackageVersion.toUInt()); writeU64(keyEpoch.toULong())
        }.toByteArray()
    }
}

/** Encrypts only a validated v1 package and binds exactly its public routing identities. */
fun PairingHpke.encryptKeyPackage(
    recipient: HpkePublicKeyBase64Url,
    plaintext: KeyPackagePlaintextV1,
): KeyPackageEnvelopeV1 {
    val plaintextJson = PairingWireCodec.encodePlaintext(plaintext)
    val context = KeyPackageContextV1.bytes(
        plaintext.accountId,
        plaintext.requestId,
        plaintext.targetDeviceId,
        plaintext.keyPackageVersion,
        plaintext.keyEpoch,
    )
    val encrypted = encrypt(recipient, plaintextJson.encodeToByteArray(), context)
    return KeyPackageEnvelopeV1(
        accountId = plaintext.accountId,
        requestId = plaintext.requestId,
        targetDeviceId = plaintext.targetDeviceId,
        keyEpoch = plaintext.keyEpoch,
        encapsulatedKeyBase64Url = encrypted.encapsulatedKeyBase64Url,
        ciphertextBase64Url = encrypted.ciphertextBase64Url,
    )
}

sealed interface DecryptKeyPackageResult {
    data class Admitted(val plaintext: KeyPackagePlaintextV1) : DecryptKeyPackageResult
    data object AuthenticationFailed : DecryptKeyPackageResult
    data object InvalidPlaintext : DecryptKeyPackageResult
    data object IdentityMismatch : DecryptKeyPackageResult
    data object NotPending : DecryptKeyPackageResult
}

/**
 * SYN-006A receive gate. It derives context from the authenticated outer
 * envelope, strictly decodes inner JSON, and admits identities before any
 * caller can import key material.
 */
fun PairingHpke.decryptKeyPackage(
    state: LocalEnrollmentState,
    privateKey: PairingPrivateKeyMaterial,
    envelope: KeyPackageEnvelopeV1,
): DecryptKeyPackageResult {
    val context = KeyPackageContextV1.bytes(
        envelope.accountId,
        envelope.requestId,
        envelope.targetDeviceId,
        envelope.keyPackageVersion,
        envelope.keyEpoch,
    )
    val plaintextJson = try {
        decrypt(privateKey, envelope.encapsulatedKeyBase64Url, envelope.ciphertextBase64Url, context).decodeToString()
    } catch (_: Exception) {
        return DecryptKeyPackageResult.AuthenticationFailed
    }
    val plaintext = when (val decoded = PairingWireCodec.decodePlaintext(plaintextJson)) {
        is PairingWireDecodeResult.Supported -> decoded.value
        else -> return DecryptKeyPackageResult.InvalidPlaintext
    }
    return when (val admission = PairingAdmission.admitPackage(state, envelope, plaintext)) {
        is KeyPackageAdmissionResult.Accepted -> DecryptKeyPackageResult.Admitted(admission.plaintext)
        KeyPackageAdmissionResult.IdentityMismatch -> DecryptKeyPackageResult.IdentityMismatch
        KeyPackageAdmissionResult.NotPending -> DecryptKeyPackageResult.NotPending
    }
}

@OptIn(ExperimentalEncodingApi::class)
private class ByteBuilder {
    private val values = mutableListOf<Byte>()
    fun write(value: ByteArray) { values += value.toList() }
    fun writeByte(value: Int) { values += value.toByte() }
    fun writeLengthPrefixed(value: String) {
        val encoded = value.encodeToByteArray(); writeU32(encoded.size.toUInt()); write(encoded)
    }
    fun writeU32(value: UInt) { write(byteArrayOf((value shr 24).toByte(), (value shr 16).toByte(), (value shr 8).toByte(), value.toByte())) }
    fun writeU64(value: ULong) { write(byteArrayOf((value shr 56).toByte(), (value shr 48).toByte(), (value shr 40).toByte(), (value shr 32).toByte(), (value shr 24).toByte(), (value shr 16).toByte(), (value shr 8).toByte(), value.toByte())) }
    fun toByteArray(): ByteArray = values.toByteArray()
}
