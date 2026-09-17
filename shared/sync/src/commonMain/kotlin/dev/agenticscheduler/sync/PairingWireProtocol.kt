package dev.agenticscheduler.sync

import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** SYN-006A's opaque server enrollment-request identifier. */
@JvmInline
@Serializable
value class EnrollmentRequestId(val value: String) {
    init { require(value.isNotBlank()) { "Enrollment request id must not be blank." } }
}

/** Canonical base64url-no-padding encoding of a raw 32-byte X25519 public key. */
@JvmInline
@Serializable
value class HpkePublicKeyBase64Url(val value: String) {
    init { requireCanonicalBase64Url(value, 32, "HPKE public key") }
}

@Serializable
data class PendingEnrollmentRequestV1(
    val accountId: AccountId,
    val requestId: EnrollmentRequestId,
    val targetDeviceId: DeviceId,
    val hpkePublicKeyBase64Url: HpkePublicKeyBase64Url,
)

@Serializable
data class KeyPackageEnvelopeV1(
    val keyPackageVersion: Int = PairingWireCodec.KEY_PACKAGE_VERSION,
    val accountId: AccountId,
    val requestId: EnrollmentRequestId,
    val targetDeviceId: DeviceId,
    val keyEpoch: Long,
    val encapsulatedKeyBase64Url: String,
    val ciphertextBase64Url: String,
) {
    init {
        require(keyEpoch >= 0)
        requireCanonicalBase64Url(encapsulatedKeyBase64Url, 32, "HPKE encapsulated key")
        requireCanonicalBase64Url(ciphertextBase64Url, null, "HPKE ciphertext")
    }
}

@Serializable
data class KeyPackagePlaintextV1(
    val keyPackageVersion: Int = PairingWireCodec.KEY_PACKAGE_VERSION,
    val accountId: AccountId,
    val requestId: EnrollmentRequestId,
    val targetDeviceId: DeviceId,
    val keyEpoch: Long,
    val accountMasterKeyBase64Url: String,
    val syncSpace: SyncSpaceKeyPackageV1,
) {
    init {
        require(keyEpoch >= 0)
        requireCanonicalBase64Url(accountMasterKeyBase64Url, 32, "Account master key")
        require(keyEpoch == syncSpace.activeEpoch)
    }
}

@Serializable
data class SyncSpaceKeyPackageV1(
    val syncSpaceId: SyncSpaceId,
    val activeEpoch: Long,
    val activeKeyBase64Url: String,
    val historicalKeys: List<HistoricalSyncSpaceKeyV1>,
) {
    init {
        require(activeEpoch >= 0)
        requireCanonicalBase64Url(activeKeyBase64Url, 32, "Active SyncSpace key")
        require(historicalKeys.map(HistoricalSyncSpaceKeyV1::keyEpoch).distinct().size == historicalKeys.size)
        require(historicalKeys.all { it.keyEpoch < activeEpoch })
        require(historicalKeys.zipWithNext().all { (before, after) -> before.keyEpoch < after.keyEpoch }) {
            "Historical SyncSpace keys must be in ascending epoch order."
        }
    }
}

@Serializable
data class HistoricalSyncSpaceKeyV1(
    val keyEpoch: Long,
    val keyBase64Url: String,
) {
    init {
        require(keyEpoch >= 0)
        requireCanonicalBase64Url(keyBase64Url, 32, "Historical SyncSpace key")
    }
}

sealed interface PairingWireDecodeResult<out T> {
    data class Supported<T>(val value: T) : PairingWireDecodeResult<T>
    data class UnsupportedVersion(val actual: Int?) : PairingWireDecodeResult<Nothing>
    data class Invalid(val reason: String) : PairingWireDecodeResult<Nothing>
}

/** Strict SYN-006A JSON boundary. Pairing payloads never use SyncWireCodec's additive-field policy. */
object PairingWireCodec {
    const val KEY_PACKAGE_VERSION = 1

    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = false }

    fun encodeEnvelope(value: KeyPackageEnvelopeV1): String {
        require(value.keyPackageVersion == KEY_PACKAGE_VERSION)
        return json.encodeToString(KeyPackageEnvelopeV1.serializer(), value)
    }

    fun decodeEnvelope(encoded: String): PairingWireDecodeResult<KeyPackageEnvelopeV1> =
        decodeStrict(encoded, envelopeKeys) { json.decodeFromJsonElement(KeyPackageEnvelopeV1.serializer(), it) }

    fun encodePlaintext(value: KeyPackagePlaintextV1): String {
        require(value.keyPackageVersion == KEY_PACKAGE_VERSION)
        return json.encodeToString(KeyPackagePlaintextV1.serializer(), value)
    }

    fun decodePlaintext(encoded: String): PairingWireDecodeResult<KeyPackagePlaintextV1> =
        decodeStrict(encoded, plaintextKeys) { root ->
            val syncSpace = root["syncSpace"] as? JsonObject
                ?: throw IllegalArgumentException("syncSpace must be an object.")
            requireExactKeys(syncSpace, syncSpaceKeys)
            val historical = syncSpace["historicalKeys"] as? JsonArray
                ?: throw IllegalArgumentException("historicalKeys must be an array.")
            historical.forEach { element -> requireExactKeys(element as? JsonObject ?: throw IllegalArgumentException("Historical key must be an object."), historicalKeyKeys) }
            json.decodeFromJsonElement(KeyPackagePlaintextV1.serializer(), root)
        }

    private fun <T> decodeStrict(
        encoded: String,
        rootKeys: Set<String>,
        decode: (JsonObject) -> T,
    ): PairingWireDecodeResult<T> {
        if (!StrictJsonObjectKeys.hasNoDuplicateKeys(encoded)) {
            return PairingWireDecodeResult.Invalid("Pairing JSON contains duplicate object keys or invalid JSON.")
        }
        val root = try {
            json.parseToJsonElement(encoded) as? JsonObject
                ?: return PairingWireDecodeResult.Invalid("Pairing JSON must be an object.")
        } catch (_: SerializationException) {
            return PairingWireDecodeResult.Invalid("Pairing JSON is malformed.")
        }
        val version = (root["keyPackageVersion"] as? JsonPrimitive)?.content?.toIntOrNull()
        if (version != KEY_PACKAGE_VERSION) return PairingWireDecodeResult.UnsupportedVersion(version)
        return try {
            requireExactKeys(root, rootKeys)
            PairingWireDecodeResult.Supported(decode(root))
        } catch (failure: SerializationException) {
            PairingWireDecodeResult.Invalid(failure.message ?: "Pairing JSON cannot be decoded.")
        } catch (failure: IllegalArgumentException) {
            PairingWireDecodeResult.Invalid(failure.message ?: "Pairing JSON violates the v1 contract.")
        }
    }

    private fun requireExactKeys(value: JsonObject, expected: Set<String>) {
        require(value.keys == expected) { "Pairing JSON has missing or unexpected fields." }
    }

    private val envelopeKeys = setOf("keyPackageVersion", "accountId", "requestId", "targetDeviceId", "keyEpoch", "encapsulatedKeyBase64Url", "ciphertextBase64Url")
    private val plaintextKeys = setOf("keyPackageVersion", "accountId", "requestId", "targetDeviceId", "keyEpoch", "accountMasterKeyBase64Url", "syncSpace")
    private val syncSpaceKeys = setOf("syncSpaceId", "activeEpoch", "activeKeyBase64Url", "historicalKeys")
    private val historicalKeyKeys = setOf("keyEpoch", "keyBase64Url")
}

@OptIn(ExperimentalEncodingApi::class)
fun requireCanonicalBase64Url(value: String, expectedBytes: Int?, name: String) {
    require(value.isNotEmpty() && '=' !in value && value.all { it.isAsciiLetterOrDigit() || it == '-' || it == '_' }) {
        "$name must be non-empty unpadded base64url."
    }
    val decoded = try { unpaddedUrlSafeBase64.decode(value) } catch (_: IllegalArgumentException) {
        throw IllegalArgumentException("$name is not base64url.")
    }
    require(expectedBytes == null || decoded.size == expectedBytes) { "$name must decode to $expectedBytes bytes." }
    require(unpaddedUrlSafeBase64.encode(decoded) == value) { "$name must use canonical base64url encoding." }
}

@OptIn(ExperimentalEncodingApi::class)
fun decodeCanonicalBase64Url(value: String, expectedBytes: Int?, name: String): ByteArray {
    requireCanonicalBase64Url(value, expectedBytes, name)
    return unpaddedUrlSafeBase64.decode(value)
}

/** SYN-006A accepts and emits canonical base64url without '=' padding. */
@OptIn(ExperimentalEncodingApi::class)
private val unpaddedUrlSafeBase64 = Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT)

private fun Char.isAsciiLetterOrDigit(): Boolean = this in 'a'..'z' || this in 'A'..'Z' || this in '0'..'9'

/** Minimal JSON syntax walk used only to reject duplicate decoded object keys before serialization. */
private object StrictJsonObjectKeys {
    fun hasNoDuplicateKeys(input: String): Boolean = try {
        val parser = Parser(input)
        parser.value()
        parser.space()
        parser.end()
    } catch (_: IllegalArgumentException) { false }

    private class Parser(private val input: String) {
        private var index = 0
        fun end(): Boolean = index == input.length
        fun space() { while (index < input.length && input[index] in " \n\r\t") index++ }
        fun value() {
            space(); require(index < input.length)
            when (input[index]) {
                '{' -> obj(); '[' -> array(); '"' -> string()
                't' -> literal("true"); 'f' -> literal("false"); 'n' -> literal("null")
                '-', in '0'..'9' -> number(); else -> error("Invalid JSON token")
            }
        }
        private fun obj() {
            require(input[index++] == '{'); space(); val names = mutableSetOf<String>()
            if (index < input.length && input[index] == '}') { index++; return }
            while (true) {
                space(); val name = string(); require(names.add(name)) { "Duplicate key" }
                space(); require(index < input.length && input[index++] == ':'); value(); space()
                require(index < input.length)
                if (input[index++] == '}') return
                require(input[index - 1] == ',')
            }
        }
        private fun array() {
            require(input[index++] == '['); space(); if (index < input.length && input[index] == ']') { index++; return }
            while (true) { value(); space(); require(index < input.length); if (input[index++] == ']') return; require(input[index - 1] == ',') }
        }
        private fun string(): String {
            require(index < input.length && input[index++] == '"'); val out = StringBuilder()
            while (index < input.length) {
                when (val c = input[index++]) {
                    '"' -> return out.toString(); '\\' -> {
                        require(index < input.length); when (val escaped = input[index++]) {
                            '"', '\\', '/' -> out.append(escaped); 'b' -> out.append('\b'); 'f' -> out.append('\u000C')
                            'n' -> out.append('\n'); 'r' -> out.append('\r'); 't' -> out.append('\t')
                            'u' -> { require(index + 4 <= input.length); out.append(input.substring(index, index + 4).toInt(16).toChar()); index += 4 }
                            else -> error("Invalid JSON escape")
                        }
                    }
                    else -> { require(c.code >= 0x20); out.append(c) }
                }
            }
            error("Unclosed JSON string")
        }
        private fun literal(value: String) { require(input.startsWith(value, index)); index += value.length }
        private fun number() {
            if (input[index] == '-') index++; require(index < input.length)
            if (input[index] == '0') index++ else { require(input[index] in '1'..'9'); while (index < input.length && input[index].isDigit()) index++ }
            if (index < input.length && input[index] == '.') { index++; require(index < input.length && input[index].isDigit()); while (index < input.length && input[index].isDigit()) index++ }
            if (index < input.length && input[index] in "eE") { index++; if (index < input.length && input[index] in "+-") index++; require(index < input.length && input[index].isDigit()); while (index < input.length && input[index].isDigit()) index++ }
        }
    }
}
