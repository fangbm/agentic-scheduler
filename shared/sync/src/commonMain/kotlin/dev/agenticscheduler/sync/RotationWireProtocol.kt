package dev.agenticscheduler.sync

import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

@Serializable
data class RotationKeyPackageEnvelopeV1(
    val rotationPackageVersion: Int = RotationWireCodec.ROTATION_PACKAGE_VERSION,
    val accountId: AccountId,
    val rotationId: String,
    val targetDeviceId: DeviceId,
    val keyEpoch: Long,
    val encapsulatedKeyBase64Url: String,
    val ciphertextBase64Url: String,
) {
    init {
        require(rotationId.isNotBlank() && rotationId.length <= 128) { "rotationId is invalid." }
        require(keyEpoch >= 0) { "Rotation key epoch must not be negative." }
        requireCanonicalBase64Url(encapsulatedKeyBase64Url, 32, "Rotation HPKE encapsulated key")
        requireCanonicalBase64Url(ciphertextBase64Url, null, "Rotation HPKE ciphertext")
    }
}

@Serializable
data class RotationKeyPackagePlaintextV1(
    val rotationPackageVersion: Int = RotationWireCodec.ROTATION_PACKAGE_VERSION,
    val accountId: AccountId,
    val rotationId: String,
    val targetDeviceId: DeviceId,
    val keyEpoch: Long,
    val accountMasterKeyBase64Url: String,
    val syncSpace: SyncSpaceKeyPackageV1,
) {
    init {
        require(rotationId.isNotBlank() && rotationId.length <= 128) { "rotationId is invalid." }
        require(keyEpoch >= 0) { "Rotation key epoch must not be negative." }
        requireCanonicalBase64Url(accountMasterKeyBase64Url, 32, "Rotation account master key")
        require(keyEpoch == syncSpace.activeEpoch) { "Rotation key epoch must equal active SyncSpace epoch." }
    }
}

sealed interface RotationWireDecodeResult<out T> {
    data class Supported<T>(val value: T) : RotationWireDecodeResult<T>
    data class UnsupportedVersion(val actual: Int?) : RotationWireDecodeResult<Nothing>
    data class Invalid(val reason: String) : RotationWireDecodeResult<Nothing>
}

/** Strict SYN-007B rotation package JSON boundary. */
object RotationWireCodec {
    const val ROTATION_PACKAGE_VERSION = 1
    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = false }

    fun encodeEnvelope(value: RotationKeyPackageEnvelopeV1): String {
        require(value.rotationPackageVersion == ROTATION_PACKAGE_VERSION)
        return json.encodeToString(RotationKeyPackageEnvelopeV1.serializer(), value)
    }

    fun decodeEnvelope(encoded: String): RotationWireDecodeResult<RotationKeyPackageEnvelopeV1> =
        decodeStrict(encoded, envelopeKeys) { json.decodeFromJsonElement(RotationKeyPackageEnvelopeV1.serializer(), it) }

    fun encodePlaintext(value: RotationKeyPackagePlaintextV1): String {
        require(value.rotationPackageVersion == ROTATION_PACKAGE_VERSION)
        return json.encodeToString(RotationKeyPackagePlaintextV1.serializer(), value)
    }

    fun decodePlaintext(encoded: String): RotationWireDecodeResult<RotationKeyPackagePlaintextV1> =
        decodeStrict(encoded, plaintextKeys) { root ->
            val syncSpace = root["syncSpace"] as? JsonObject
                ?: throw IllegalArgumentException("syncSpace must be an object.")
            requireExactKeys(syncSpace, syncSpaceKeys)
            val historical = syncSpace["historicalKeys"] as? JsonArray
                ?: throw IllegalArgumentException("historicalKeys must be an array.")
            historical.forEach { element ->
                requireExactKeys(
                    element as? JsonObject ?: throw IllegalArgumentException("Historical key must be an object."),
                    historicalKeyKeys,
                )
            }
            json.decodeFromJsonElement(RotationKeyPackagePlaintextV1.serializer(), root)
        }

    private fun <T> decodeStrict(
        encoded: String,
        keys: Set<String>,
        decode: (JsonObject) -> T,
    ): RotationWireDecodeResult<T> {
        if (!StrictJsonObjectKeys.hasNoDuplicateKeys(encoded)) {
            return RotationWireDecodeResult.Invalid("Rotation JSON contains duplicate object keys or invalid JSON.")
        }
        val root = try {
            json.parseToJsonElement(encoded) as? JsonObject
                ?: return RotationWireDecodeResult.Invalid("Rotation JSON must be an object.")
        } catch (_: SerializationException) {
            return RotationWireDecodeResult.Invalid("Rotation JSON is malformed.")
        }
        val version = (root["rotationPackageVersion"] as? JsonPrimitive)?.content?.toIntOrNull()
        if (version != ROTATION_PACKAGE_VERSION) return RotationWireDecodeResult.UnsupportedVersion(version)
        return try {
            requireExactKeys(root, keys)
            RotationWireDecodeResult.Supported(decode(root))
        } catch (failure: SerializationException) {
            RotationWireDecodeResult.Invalid(failure.message ?: "Rotation JSON cannot be decoded.")
        } catch (failure: IllegalArgumentException) {
            RotationWireDecodeResult.Invalid(failure.message ?: "Rotation JSON violates the v1 contract.")
        }
    }

    private fun requireExactKeys(value: JsonObject, expected: Set<String>) {
        require(value.keys == expected) { "Rotation JSON has missing or unexpected fields." }
    }

    private val envelopeKeys = setOf(
        "rotationPackageVersion", "accountId", "rotationId", "targetDeviceId", "keyEpoch",
        "encapsulatedKeyBase64Url", "ciphertextBase64Url",
    )
    private val plaintextKeys = setOf(
        "rotationPackageVersion", "accountId", "rotationId", "targetDeviceId", "keyEpoch",
        "accountMasterKeyBase64Url", "syncSpace",
    )
    private val syncSpaceKeys = setOf("syncSpaceId", "activeEpoch", "activeKeyBase64Url", "historicalKeys")
    private val historicalKeyKeys = setOf("keyEpoch", "keyBase64Url")
}
