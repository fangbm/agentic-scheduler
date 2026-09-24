package dev.agenticscheduler.sync

import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

@Serializable
data class RecoveryEnvelopeV1(
    val recoveryEnvelopeVersion: Int = RecoveryWireCodec.RECOVERY_ENVELOPE_VERSION,
    val accountId: AccountId,
    val syncSpaceId: SyncSpaceId,
    val keyEpoch: Long,
    val ciphertextBase64Url: String,
) {
    init {
        require(keyEpoch >= 0) { "Recovery key epoch must not be negative." }
        requireCanonicalBase64Url(ciphertextBase64Url, null, "Recovery envelope ciphertext")
    }
}

@Serializable
data class RecoveryEnvelopePlaintextV1(
    val recoveryEnvelopeVersion: Int = RecoveryWireCodec.RECOVERY_ENVELOPE_VERSION,
    val accountId: AccountId,
    val keyEpoch: Long,
    val accountMasterKeyBase64Url: String,
    val syncSpace: RecoverySyncSpaceKeyRingV1,
) {
    init {
        require(keyEpoch >= 0)
        requireCanonicalBase64Url(accountMasterKeyBase64Url, 32, "Recovery AMK")
        require(keyEpoch == syncSpace.activeEpoch) { "Recovery key epoch must equal the active SyncSpace epoch." }
    }
}

@Serializable
data class RecoverySyncSpaceKeyRingV1(
    val syncSpaceId: SyncSpaceId,
    val activeEpoch: Long,
    val activeKeyBase64Url: String,
    val historicalKeys: List<RecoveryHistoricalKeyV1>,
) {
    init {
        require(activeEpoch >= 0)
        requireCanonicalBase64Url(activeKeyBase64Url, 32, "Recovery active SyncSpace key")
        require(historicalKeys.map(RecoveryHistoricalKeyV1::keyEpoch).distinct().size == historicalKeys.size)
        require(historicalKeys.all { it.keyEpoch < activeEpoch })
        require(historicalKeys.zipWithNext().all { (before, after) -> before.keyEpoch < after.keyEpoch }) {
            "Recovery historical keys must be in ascending epoch order."
        }
    }
}

@Serializable
data class RecoveryHistoricalKeyV1(
    val keyEpoch: Long,
    val keyBase64Url: String,
) {
    init {
        require(keyEpoch >= 0)
        requireCanonicalBase64Url(keyBase64Url, 32, "Recovery historical SyncSpace key")
    }
}

sealed interface RecoveryWireDecodeResult<out T> {
    data class Supported<T>(val value: T) : RecoveryWireDecodeResult<T>
    data class UnsupportedVersion(val actual: Int?) : RecoveryWireDecodeResult<Nothing>
    data class Invalid(val reason: String) : RecoveryWireDecodeResult<Nothing>
}

object RecoveryWireCodec {
    const val RECOVERY_ENVELOPE_VERSION: Int = 1
    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = false }

    fun encodeEnvelope(value: RecoveryEnvelopeV1): String {
        require(value.recoveryEnvelopeVersion == RECOVERY_ENVELOPE_VERSION)
        return json.encodeToString(RecoveryEnvelopeV1.serializer(), value)
    }

    fun decodeEnvelope(encoded: String): RecoveryWireDecodeResult<RecoveryEnvelopeV1> =
        decodeStrict(encoded, envelopeKeys) { json.decodeFromJsonElement(RecoveryEnvelopeV1.serializer(), it) }

    fun encodePlaintext(value: RecoveryEnvelopePlaintextV1): String {
        require(value.recoveryEnvelopeVersion == RECOVERY_ENVELOPE_VERSION)
        return json.encodeToString(RecoveryEnvelopePlaintextV1.serializer(), value)
    }

    fun decodePlaintext(encoded: String): RecoveryWireDecodeResult<RecoveryEnvelopePlaintextV1> =
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
            json.decodeFromJsonElement(RecoveryEnvelopePlaintextV1.serializer(), root)
        }

    private fun <T> decodeStrict(
        encoded: String,
        keys: Set<String>,
        decode: (JsonObject) -> T,
    ): RecoveryWireDecodeResult<T> {
        if (!StrictJsonObjectKeys.hasNoDuplicateKeys(encoded)) {
            return RecoveryWireDecodeResult.Invalid("Recovery JSON contains duplicate object keys or invalid JSON.")
        }
        val root = try {
            json.parseToJsonElement(encoded) as? JsonObject
                ?: return RecoveryWireDecodeResult.Invalid("Recovery JSON must be an object.")
        } catch (_: SerializationException) {
            return RecoveryWireDecodeResult.Invalid("Recovery JSON is malformed.")
        }
        val version = (root["recoveryEnvelopeVersion"] as? JsonPrimitive)?.content?.toIntOrNull()
        if (version != RECOVERY_ENVELOPE_VERSION) return RecoveryWireDecodeResult.UnsupportedVersion(version)
        return try {
            requireExactKeys(root, keys)
            RecoveryWireDecodeResult.Supported(decode(root))
        } catch (failure: SerializationException) {
            RecoveryWireDecodeResult.Invalid(failure.message ?: "Recovery JSON cannot be decoded.")
        } catch (failure: IllegalArgumentException) {
            RecoveryWireDecodeResult.Invalid(failure.message ?: "Recovery JSON violates the v1 contract.")
        }
    }

    private fun requireExactKeys(value: JsonObject, expected: Set<String>) {
        require(value.keys == expected) { "Recovery JSON has missing or unexpected fields." }
    }

    private val envelopeKeys = setOf(
        "recoveryEnvelopeVersion", "accountId", "syncSpaceId", "keyEpoch", "ciphertextBase64Url",
    )
    private val plaintextKeys = setOf(
        "recoveryEnvelopeVersion", "accountId", "keyEpoch", "accountMasterKeyBase64Url", "syncSpace",
    )
    private val syncSpaceKeys = setOf(
        "syncSpaceId", "activeEpoch", "activeKeyBase64Url", "historicalKeys",
    )
    private val historicalKeyKeys = setOf("keyEpoch", "keyBase64Url")
}
