package dev.agenticscheduler.sync

import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull

/** D8's typed routing identity. It is metadata, never a Domain field. */
@JvmInline
@Serializable
value class AccountId(val value: String) {
    init { require(value.isNotBlank()) { "AccountId must not be blank." } }
}

/** D8's one visible v1 Personal Sync Space identity. */
@JvmInline
@Serializable
value class SyncSpaceId(val value: String) {
    init { require(value.isNotBlank()) { "SyncSpaceId must not be blank." } }
}

/** D8's enrolled-device routing identity. */
@JvmInline
@Serializable
value class DeviceId(val value: String) {
    init { require(value.isNotBlank()) { "DeviceId must not be blank." } }
}

/**
 * SYN-003's server-visible opaque routing envelope. Its ciphertext is created
 * and consumed by D8-02; D8-01 deliberately contains no cryptographic code.
 */
@Serializable
data class EncryptedEnvelopeV1(
    val envelopeVersion: Int = SyncWireCodec.ENVELOPE_VERSION,
    val syncSpaceId: SyncSpaceId,
    val mutationId: String,
    val senderDeviceId: DeviceId,
    val keyEpoch: Long,
    val ciphertextBase64Url: String,
)

/** SYN-003's encrypted inner payload; D7 causal and semantic facts stay here. */
@Serializable
data class SyncPayloadV1(
    val payloadVersion: Int = SyncWireCodec.PAYLOAD_VERSION,
    val operation: SyncOperation,
)

sealed interface EnvelopeDecodeResult {
    data class Supported(val envelope: EncryptedEnvelopeV1) : EnvelopeDecodeResult
    data class UnsupportedVersion(val actual: Int?) : EnvelopeDecodeResult
    data class Invalid(val reason: String) : EnvelopeDecodeResult
}

sealed interface PayloadDecodeResult {
    data class Supported(val payload: SyncPayloadV1) : PayloadDecodeResult
    data class UnsupportedVersion(val actual: Int?) : PayloadDecodeResult
    data class UnsupportedMutation(val discriminator: String?) : PayloadDecodeResult
    data class Invalid(val reason: String) : PayloadDecodeResult
}

/**
 * Versioned D8 JSON boundary. Unknown outer fields are deliberately ignored;
 * unknown inner versions and mutation discriminators are whole-operation
 * protocol failures, never partial application candidates.
 */
object SyncWireCodec {
    const val ENVELOPE_VERSION: Int = 1
    const val PAYLOAD_VERSION: Int = 1

    private val json = Json {
        encodeDefaults = true
        ignoreUnknownKeys = true
        classDiscriminator = "type"
    }

    fun encodeEnvelope(envelope: EncryptedEnvelopeV1): String {
        require(envelope.envelopeVersion == ENVELOPE_VERSION) { "Only envelope v$ENVELOPE_VERSION can be encoded." }
        return json.encodeToString(EncryptedEnvelopeV1.serializer(), envelope)
    }

    fun decodeEnvelope(encoded: String): EnvelopeDecodeResult {
        val objectValue = parseObject(encoded) ?: return EnvelopeDecodeResult.Invalid("Envelope is not a JSON object.")
        val version = objectValue.int("envelopeVersion")
        if (version != ENVELOPE_VERSION) return EnvelopeDecodeResult.UnsupportedVersion(version)
        return try {
            EnvelopeDecodeResult.Supported(json.decodeFromJsonElement(EncryptedEnvelopeV1.serializer(), objectValue))
        } catch (failure: SerializationException) {
            EnvelopeDecodeResult.Invalid(failure.message ?: "Envelope cannot be decoded.")
        } catch (failure: IllegalArgumentException) {
            EnvelopeDecodeResult.Invalid(failure.message ?: "Envelope violates its identity contract.")
        }
    }

    fun encodePayload(payload: SyncPayloadV1): String {
        require(payload.payloadVersion == PAYLOAD_VERSION) { "Only payload v$PAYLOAD_VERSION can be encoded." }
        return json.encodeToString(SyncPayloadV1.serializer(), payload)
    }

    fun decodePayload(encoded: String): PayloadDecodeResult {
        val objectValue = parseObject(encoded) ?: return PayloadDecodeResult.Invalid("Payload is not a JSON object.")
        if (objectValue.hasMalformedInteger("payloadVersion")) {
            return PayloadDecodeResult.Invalid("Payload version must be an integer.")
        }
        val version = objectValue.int("payloadVersion")
        if (version != PAYLOAD_VERSION) return PayloadDecodeResult.UnsupportedVersion(version)
        if (hasMalformedMutationDiscriminator(objectValue) || hasMalformedOriginDiscriminator(objectValue)) {
            return PayloadDecodeResult.Invalid("Payload discriminator must be a string.")
        }
        unknownMutationDiscriminator(objectValue)?.let { return PayloadDecodeResult.UnsupportedMutation(it) }
        unknownOriginDiscriminator(objectValue)?.let { return PayloadDecodeResult.UnsupportedMutation("origin:$it") }
        return try {
            PayloadDecodeResult.Supported(json.decodeFromJsonElement(SyncPayloadV1.serializer(), objectValue))
        } catch (failure: SerializationException) {
            PayloadDecodeResult.Invalid(failure.message ?: "Payload cannot be decoded.")
        } catch (failure: IllegalArgumentException) {
            PayloadDecodeResult.Invalid(failure.message ?: "Payload violates its wire contract.")
        }
    }

    private fun parseObject(encoded: String): JsonObject? = try {
        json.parseToJsonElement(encoded) as? JsonObject
    } catch (_: SerializationException) {
        null
    }

    private fun JsonObject.int(name: String): Int? = (this[name] as? JsonPrimitive)?.intOrNull

    private fun JsonObject.hasMalformedInteger(name: String): Boolean {
        val value = this[name] ?: return false
        val primitive = value as? JsonPrimitive ?: return true
        return primitive.isString || primitive.intOrNull == null
    }

    private fun hasMalformedMutationDiscriminator(payload: JsonObject): Boolean {
        val operation = payload["operation"] as? JsonObject ?: return false
        val mutations = operation["orderedMutations"] as? JsonArray ?: return false
        return mutations.any { mutation ->
            val type = (mutation as? JsonObject)?.get("type") ?: return@any false
            val primitive = type as? JsonPrimitive
            primitive == null || !primitive.isString
        }
    }

    private fun hasMalformedOriginDiscriminator(payload: JsonObject): Boolean {
        val operation = payload["operation"] as? JsonObject ?: return false
        val origin = operation["origin"] as? JsonObject ?: return false
        val type = origin["type"] ?: return false
        val primitive = type as? JsonPrimitive
        return primitive == null || !primitive.isString
    }

    private fun unknownMutationDiscriminator(payload: JsonObject): String? {
        val operation = payload["operation"] as? JsonObject ?: return null
        val mutations = operation["orderedMutations"] as? JsonArray ?: return null
        return mutations.asSequence()
            .mapNotNull { ((it as? JsonObject)?.get("type") as? JsonPrimitive)?.contentOrNull }
            .firstOrNull { it !in knownMutationDiscriminators }
    }

    private fun unknownOriginDiscriminator(payload: JsonObject): String? {
        val operation = payload["operation"] as? JsonObject ?: return null
        val origin = operation["origin"] as? JsonObject ?: return null
        val discriminator = (origin["type"] as? JsonPrimitive)?.contentOrNull ?: return null
        return discriminator.takeUnless(knownOriginDiscriminators::contains)
    }

    private val knownMutationDiscriminators = setOf(
        "EventPut", "TaskPut", "PlanningProfilePut", "FocusBlockPut", "FocusBlockDelete",
        "WorkLogAppend", "TaskDependencyPut", "AcademicYearPut", "SemesterPut", "CoursePut",
        "PeriodTemplatePut", "AcademicHolidayPut", "CourseScheduleRulePut",
        "CourseOccurrenceExceptionPut", "ExamPut",
    )

    private val knownOriginDiscriminators = setOf(
        "USER", "PLANNER", "SYSTEM", "CONFLICT_RESOLUTION", "UNDO",
    )
}
