package dev.agenticscheduler.application.sync

import dev.agenticscheduler.sync.DeviceId
import dev.agenticscheduler.sync.EncryptedEnvelopeV1
import dev.agenticscheduler.sync.SyncSpaceId
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

class KtorSyncTransportTest {
    @Test
    fun `upload and fetch use bearer auth and cursor contract`() = runBlocking {
        val requests = mutableListOf<HttpRequestData>()
        val client = HttpClient(MockEngine) {
            engine {
                addHandler { request ->
                    requests += request
                    if (request.method == HttpMethod.Get) {
                        respond(
                            content = """[{"serverCursor":8,"envelope":{"envelopeVersion":1,"syncSpaceId":"space/a","mutationId":"mutation-1","senderDeviceId":"device","keyEpoch":0,"ciphertextBase64Url":"AQI"}}]""",
                            headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
                        )
                    } else {
                        respond(
                            content = """{"serverCursor":7,"idempotent":false}""",
                            status = HttpStatusCode.Created,
                            headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
                        )
                    }
                }
            }
        }
        val transport = KtorSyncTransport(client, "https://sync.example", { "credential" })
        val envelope = envelope()

        val upload = transport.upload(envelope)
        assertTrue(upload is SyncUploadResult.Stored, upload.toString())
        val fetched = transport.fetch(SyncSpaceId("space/a"), 7, 20)
        assertEquals(1, fetched.size)
        assertEquals(8L, fetched.single().serverCursor)
        assertEquals("https://sync.example/v1/sync/spaces/space%2Fa/envelopes", requests[0].url.toString())
        assertEquals("Bearer credential", requests[0].headers[HttpHeaders.Authorization])
        assertEquals("7", requests[1].url.parameters["after"])
        assertEquals("20", requests[1].url.parameters["limit"])
        client.close()
    }

    @Test
    fun `conflict is terminal and transient server failure is retryable`() = runBlocking {
        var response = HttpStatusCode.Conflict
        val client = HttpClient(MockEngine) {
            engine { addHandler { respond("", response) } }
        }
        val transport = KtorSyncTransport(client, "https://sync.example", { "credential" })
        assertIs<SyncUploadResult.IntegrityConflict>(transport.upload(envelope()))
        response = HttpStatusCode.ServiceUnavailable
        assertIs<SyncUploadResult.RetryableFailure>(transport.upload(envelope()))
        client.close()
    }

    @Test
    fun `missing credential fails before request`() = runBlocking {
        val client = HttpClient(MockEngine) { engine { addHandler { error("request must not be sent") } } }
        val transport = KtorSyncTransport(client, "https://sync.example", { "" })
        assertFailsWith<SyncTransportException> { transport.upload(envelope()) }
        client.close()
    }

    @Test
    fun `lifecycle package fetch is target-bound and does not require a credential`() = runBlocking {
        var observedUrl = ""
        var observedAuth: String? = null
        val client = HttpClient(MockEngine) {
            engine {
                addHandler { request ->
                    observedUrl = request.url.toString()
                    observedAuth = request.headers[HttpHeaders.Authorization]
                    respond(
                        """{"packageBase64Url":"AQI"}""",
                        headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
                    )
                }
            }
        }
        val lifecycle = KtorSyncLifecycleTransport(client, "https://sync.example", { error("unused") })
        assertEquals("AQI", lifecycle.fetchKeyPackage("req/1", "target/1"))
        assertEquals("https://sync.example/v1/enrollments/req%2F1/package?targetDeviceId=target%2F1", observedUrl)
        assertEquals(null, observedAuth)
        client.close()
    }

    @Test
    fun `lifecycle mutations and recovery use bearer auth`() = runBlocking {
        val requests = mutableListOf<Pair<String, String?>>()
        val client = HttpClient(MockEngine) {
            engine {
                addHandler { request ->
                    requests += request.url.encodedPath to request.headers[HttpHeaders.Authorization]
                    when {
                        request.url.encodedPath.endsWith("/enrollments") && request.method == HttpMethod.Post ->
                            respond("""{"requestId":"req","expiresAtEpochSeconds":1}""", status = HttpStatusCode.Created, headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()))
                        request.url.encodedPath.endsWith("/enrollments/pending") ->
                            respond("[]", headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()))
                        request.url.encodedPath.endsWith("/recovery/envelope") && request.method == HttpMethod.Get ->
                            respond("""{"blobBase64Url":"AQI"}""", headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()))
                        request.url.encodedPath.endsWith("/approve") -> respond("{}", status = HttpStatusCode.Created)
                        else -> respond("{}")
                    }
                }
            }
        }
        val lifecycle = KtorSyncLifecycleTransport(client, "https://sync.example", { "credential" })
        lifecycle.registerEnrollment(ClientEnrollmentRequest("account", "req", "target", "AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8"))
        lifecycle.pendingEnrollments()
        lifecycle.approveEnrollment("req", "AQI")
        lifecycle.saveRecoveryEnvelope("AQI")
        assertEquals("AQI", lifecycle.fetchRecoveryEnvelope())
        lifecycle.revokeDevice("target")
        assertEquals(null, requests.first().second)
        assertTrue(requests.drop(1).all { it.second == "Bearer credential" })
        client.close()
    }

    private fun envelope() = EncryptedEnvelopeV1(
        syncSpaceId = SyncSpaceId("space/a"),
        mutationId = "mutation-1",
        senderDeviceId = DeviceId("device"),
        keyEpoch = 0,
        ciphertextBase64Url = "AQI",
    )
}
