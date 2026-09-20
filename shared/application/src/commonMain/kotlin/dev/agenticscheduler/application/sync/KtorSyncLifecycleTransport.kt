package dev.agenticscheduler.application.sync

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class ClientEnrollmentRequest(
    val accountId: String,
    val requestId: String,
    val targetDeviceId: String,
    val hpkePublicKeyBase64Url: String,
)

@Serializable
data class ClientPendingEnrollment(
    val accountId: String,
    val requestId: String,
    val targetDeviceId: String,
    val hpkePublicKeyBase64Url: String,
)

@Serializable
data class ClientEnrollmentCreated(val requestId: String, val expiresAtEpochSeconds: Long)

@Serializable
data class ClientBootstrapResponse(
    val accountId: String,
    val syncSpaceId: String,
    val deviceId: String,
    val deviceCredential: String,
)

@Serializable
private data class ClientBootstrapRequest(val invitationToken: String, val deviceId: String)

@Serializable
private data class ClientPackageUpload(val packageBase64Url: String)

@Serializable
private data class ClientPackageResponse(val packageBase64Url: String)

@Serializable
private data class ClientBlob(val blobBase64Url: String)

/** HTTP lifecycle adapter for the already-frozen opaque enrollment/recovery routes. */
class KtorSyncLifecycleTransport(
    private val client: HttpClient,
    baseUrl: String,
    private val deviceCredential: suspend () -> DeviceCredential?,
    private val json: Json = Json { encodeDefaults = true; ignoreUnknownKeys = true },
) {
    private val baseUrl = baseUrl.trimEnd('/')

    init { require(this.baseUrl.startsWith("https://")) { "D8 sync transport requires HTTPS." } }

    suspend fun bootstrap(invitationToken: String, deviceId: String): ClientBootstrapResponse {
        val response = client.post("$baseUrl/v1/bootstrap") {
            contentType(ContentType.Application.Json)
            setBody(json.encodeToString(ClientBootstrapRequest.serializer(), ClientBootstrapRequest(invitationToken, deviceId)))
        }
        requireStatus(response, HttpStatusCode.Created)
        return decode(response.bodyAsText(), ClientBootstrapResponse.serializer())
    }

    suspend fun registerEnrollment(request: ClientEnrollmentRequest): ClientEnrollmentCreated {
        val response = client.post("$baseUrl/v1/enrollments") {
            contentType(ContentType.Application.Json)
            setBody(json.encodeToString(ClientEnrollmentRequest.serializer(), request))
        }
        requireStatus(response, HttpStatusCode.Created)
        return decode(response.bodyAsText(), ClientEnrollmentCreated.serializer())
    }

    suspend fun pendingEnrollments(): List<ClientPendingEnrollment> {
        val response = client.get("$baseUrl/v1/enrollments/pending") { authorization() }
        requireStatus(response, HttpStatusCode.OK)
        return decode(response.bodyAsText(), kotlinx.serialization.builtins.ListSerializer(ClientPendingEnrollment.serializer()))
    }

    suspend fun approveEnrollment(requestId: String, packageBase64Url: String) {
        val response = client.post("$baseUrl/v1/enrollments/${encodePathSegment(requestId)}/approve") {
            authorization()
            contentType(ContentType.Application.Json)
            setBody(json.encodeToString(ClientPackageUpload.serializer(), ClientPackageUpload(packageBase64Url)))
        }
        requireStatus(response, HttpStatusCode.Created)
    }

    suspend fun fetchKeyPackage(requestId: String, targetDeviceId: String): String {
        val response = client.get("$baseUrl/v1/enrollments/${encodePathSegment(requestId)}/package?targetDeviceId=${encodePathSegment(targetDeviceId)}")
        requireStatus(response, HttpStatusCode.OK)
        return decode(response.bodyAsText(), ClientPackageResponse.serializer()).packageBase64Url
    }

    suspend fun saveRecoveryEnvelope(blobBase64Url: String) {
        val response = client.put("$baseUrl/v1/recovery/envelope") {
            authorization()
            contentType(ContentType.Application.Json)
            setBody(json.encodeToString(ClientBlob.serializer(), ClientBlob(blobBase64Url)))
        }
        requireStatus(response, HttpStatusCode.OK)
    }

    suspend fun fetchRecoveryEnvelope(): String {
        val response = client.get("$baseUrl/v1/recovery/envelope") { authorization() }
        requireStatus(response, HttpStatusCode.OK)
        return decode(response.bodyAsText(), ClientBlob.serializer()).blobBase64Url
    }

    suspend fun revokeDevice(deviceId: String) {
        val response = client.post("$baseUrl/v1/devices/${encodePathSegment(deviceId)}/revoke") { authorization() }
        requireStatus(response, HttpStatusCode.OK)
    }

    private suspend fun io.ktor.client.request.HttpRequestBuilder.authorization() {
        val credential = deviceCredential() ?: throw SyncTransportException("MISSING_DEVICE_CREDENTIAL")
        header(HttpHeaders.Authorization, "Bearer ${credential.value}")
    }

    private fun requireStatus(response: io.ktor.client.statement.HttpResponse, expected: HttpStatusCode) {
        if (response.status != expected) throw SyncTransportException("HTTP_${response.status.value}")
    }

    private fun <T> decode(value: String, serializer: kotlinx.serialization.KSerializer<T>): T = try {
        json.decodeFromString(serializer, value)
    } catch (failure: Exception) {
        throw SyncTransportException("MALFORMED_SERVER_RESPONSE", failure)
    }
}
