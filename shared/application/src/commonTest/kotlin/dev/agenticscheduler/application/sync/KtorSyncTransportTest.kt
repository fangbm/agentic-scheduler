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

    private fun envelope() = EncryptedEnvelopeV1(
        syncSpaceId = SyncSpaceId("space/a"),
        mutationId = "mutation-1",
        senderDeviceId = DeviceId("device"),
        keyEpoch = 0,
        ciphertextBase64Url = "AQI",
    )
}
