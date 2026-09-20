package dev.agenticscheduler.application.sync

import dev.agenticscheduler.sync.EncryptedEnvelopeV1
import dev.agenticscheduler.sync.SyncSpaceId
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

@Serializable
private data class UploadResponseWire(val serverCursor: Long, val idempotent: Boolean)

@Serializable
private data class StoredEnvelopeWire(val serverCursor: Long, val envelope: EncryptedEnvelopeV1)

/** Ktor-client adapter for the D8 opaque relay; it never sends decrypted payloads. */
class KtorSyncTransport(
    private val client: HttpClient,
    baseUrl: String,
    private val deviceCredential: suspend () -> String,
    private val json: Json = Json { encodeDefaults = true; ignoreUnknownKeys = true },
) : SyncTransport {
    private val baseUrl = baseUrl.trimEnd('/')

    init { require(this.baseUrl.startsWith("https://")) { "D8 sync transport requires HTTPS." } }

    override suspend fun upload(envelope: EncryptedEnvelopeV1): SyncUploadResult {
        val response = client.post(envelopeUrl(envelope.syncSpaceId)) {
            authorization()
            contentType(ContentType.Application.Json)
            setBody(json.encodeToString(EncryptedEnvelopeV1.serializer(), envelope))
        }
        return when {
            response.status == HttpStatusCode.Created || response.status == HttpStatusCode.OK -> try {
                val result = json.decodeFromString<UploadResponseWire>(response.bodyAsText())
                if (result.idempotent) SyncUploadResult.Idempotent(result.serverCursor)
                else SyncUploadResult.Stored(result.serverCursor)
            } catch (_: SerializationException) {
                SyncUploadResult.NonRetryableFailure("MALFORMED_SERVER_RESPONSE")
            }
            response.status == HttpStatusCode.Conflict -> SyncUploadResult.IntegrityConflict("INTEGRITY_CONFLICT")
            response.status == HttpStatusCode.Unauthorized || response.status == HttpStatusCode.Forbidden ->
                SyncUploadResult.NonRetryableFailure("UNAUTHORIZED")
            response.status.value == 408 || response.status == HttpStatusCode.TooManyRequests || response.status.value >= 500 ->
                SyncUploadResult.RetryableFailure("HTTP_${response.status.value}")
            else -> SyncUploadResult.NonRetryableFailure("HTTP_${response.status.value}")
        }
    }

    override suspend fun fetch(syncSpaceId: SyncSpaceId, afterCursor: Long, limit: Int): List<RemoteSyncEnvelope> {
        require(afterCursor >= 0)
        require(limit > 0)
        val response = client.get("${envelopeUrl(syncSpaceId)}?after=$afterCursor&limit=$limit") { authorization() }
        if (response.status != HttpStatusCode.OK) throw SyncTransportException("HTTP_${response.status.value}")
        return try {
            json.decodeFromString<List<StoredEnvelopeWire>>(response.bodyAsText()).map { wire ->
                require(wire.serverCursor > 0)
                require(wire.envelope.syncSpaceId == syncSpaceId)
                RemoteSyncEnvelope(wire.serverCursor, wire.envelope)
            }
        } catch (failure: SerializationException) {
            throw SyncTransportException("MALFORMED_SERVER_RESPONSE", failure)
        } catch (failure: IllegalArgumentException) {
            throw SyncTransportException("INVALID_SERVER_ENVELOPE", failure)
        }
    }

    private suspend fun io.ktor.client.request.HttpRequestBuilder.authorization() {
        val credential = deviceCredential().takeIf(String::isNotBlank) ?: throw SyncTransportException("MISSING_DEVICE_CREDENTIAL")
        header(HttpHeaders.Authorization, "Bearer $credential")
    }

    private fun envelopeUrl(syncSpaceId: SyncSpaceId): String =
        "$baseUrl/v1/sync/spaces/${encodePathSegment(syncSpaceId.value)}/envelopes"
}

class SyncTransportException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

private fun encodePathSegment(value: String): String = buildString {
    value.encodeToByteArray().forEach { byte ->
        val number = byte.toInt() and 0xff
        val character = number.toChar()
        if (character in 'A'..'Z' || character in 'a'..'z' || character in '0'..'9' || character in "-._~") {
            append(character)
        } else {
            append('%')
            append("0123456789ABCDEF"[number ushr 4])
            append("0123456789ABCDEF"[number and 0x0f])
        }
    }
}
