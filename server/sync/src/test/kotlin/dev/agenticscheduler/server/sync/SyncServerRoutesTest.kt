package dev.agenticscheduler.server.sync

import dev.agenticscheduler.sync.DeviceId
import dev.agenticscheduler.sync.EncryptedEnvelopeV1
import dev.agenticscheduler.sync.SyncSpaceId
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SyncServerRoutesTest {
    @Test
    fun `admin invitation and bootstrap are one time`() = testApplication {
        val repository = FakeRepository()
        application { syncServerModule(repository, testConfig().copy(adminToken = "admin")) }
        val invitationBody = """{"accountId":"account","syncSpaceId":"space"}"""
        assertEquals(HttpStatusCode.Unauthorized, client.post("/v1/admin/invitations") {
            contentType(ContentType.Application.Json)
            setBody(invitationBody)
        }.status)
        val invitation = client.post("/v1/admin/invitations") {
            header("X-Sync-Admin-Token", "admin")
            contentType(ContentType.Application.Json)
            setBody(invitationBody)
        }
        assertEquals(HttpStatusCode.Created, invitation.status)
        val bootstrapBody = """{"invitationToken":"invite","deviceId":"device"}"""
        val bootstrap = client.post("/v1/bootstrap") {
            contentType(ContentType.Application.Json)
            setBody(bootstrapBody)
        }
        assertEquals(HttpStatusCode.Created, bootstrap.status)
        assertTrue(bootstrap.bodyAsText().contains("deviceCredential"))
        assertEquals(HttpStatusCode.Conflict, client.post("/v1/bootstrap") {
            contentType(ContentType.Application.Json)
            setBody(bootstrapBody)
        }.status)
    }

    @Test
    fun `opaque upload is authenticated idempotent and cursor fetchable`() = testApplication {
        val repository = FakeRepository()
        application { syncServerModule(repository, testConfig()) }
        val body = Json.encodeToString(EncryptedEnvelopeV1.serializer(), envelope())

        assertEquals(HttpStatusCode.Unauthorized, client.post("/v1/sync/spaces/space/envelopes") { setBody(body) }.status)

        val first = client.post("/v1/sync/spaces/space/envelopes") {
            header(HttpHeaders.Authorization, "Bearer credential")
            contentType(ContentType.Application.Json)
            setBody(body)
        }
        assertEquals(HttpStatusCode.Created, first.status)
        assertTrue(first.bodyAsText().contains("\"idempotent\":false"))

        val duplicate = client.post("/v1/sync/spaces/space/envelopes") {
            header(HttpHeaders.Authorization, "Bearer credential")
            contentType(ContentType.Application.Json)
            setBody(body)
        }
        assertEquals(HttpStatusCode.OK, duplicate.status)
        assertTrue(duplicate.bodyAsText().contains("\"idempotent\":true"))

        val fetched = client.get("/v1/sync/spaces/space/envelopes?after=0&limit=10") {
            header(HttpHeaders.Authorization, "Bearer credential")
        }
        assertEquals(HttpStatusCode.OK, fetched.status)
        assertTrue(fetched.bodyAsText().contains("mutation-1"))
        assertTrue(fetched.bodyAsText().contains("AQI"))
    }

    @Test
    fun `sender identity and mutation integrity are enforced`() = testApplication {
        val repository = FakeRepository()
        application { syncServerModule(repository, testConfig()) }
        val body = Json.encodeToString(EncryptedEnvelopeV1.serializer(), envelope())
        val spoofed = Json.encodeToString(EncryptedEnvelopeV1.serializer(), envelope().copy(senderDeviceId = DeviceId("other")))
        assertEquals(HttpStatusCode.Forbidden, client.post("/v1/sync/spaces/space/envelopes") {
            header(HttpHeaders.Authorization, "Bearer credential")
            contentType(ContentType.Application.Json)
            setBody(spoofed)
        }.status)
        client.post("/v1/sync/spaces/space/envelopes") {
            header(HttpHeaders.Authorization, "Bearer credential")
            contentType(ContentType.Application.Json)
            setBody(body)
        }
        val changed = Json.encodeToString(EncryptedEnvelopeV1.serializer(), envelope().copy(ciphertextBase64Url = "AwQ"))
        assertEquals(HttpStatusCode.Conflict, client.post("/v1/sync/spaces/space/envelopes") {
            header(HttpHeaders.Authorization, "Bearer credential")
            contentType(ContentType.Application.Json)
            setBody(changed)
        }.status)
    }

    private fun envelope() = EncryptedEnvelopeV1(
        syncSpaceId = SyncSpaceId("space"),
        mutationId = "mutation-1",
        senderDeviceId = DeviceId("device"),
        keyEpoch = 0,
        ciphertextBase64Url = "AQI",
    )

    private fun testConfig() = SyncServerConfig("jdbc:test", "user", "password", maxFetchLimit = 10)

    private class FakeRepository : OpaqueSyncRepository, ServerBootstrapRepository {
        private val stored = linkedMapOf<String, StoredEnvelope>()
        private var consumed = false
        override fun authenticate(credential: String): AuthenticatedDevice? =
            if (credential == "credential") AuthenticatedDevice("account", "device") else null

        override fun upload(actor: AuthenticatedDevice, spaceId: String, envelope: EncryptedEnvelopeV1, ciphertext: ByteArray): UploadOutcome {
            val existing = stored[envelope.mutationId]
            if (existing != null) {
                return if (existing.envelope == envelope) UploadOutcome.Idempotent(existing.serverCursor) else UploadOutcome.IntegrityConflict
            }
            val cursor = stored.size.toLong() + 1
            stored[envelope.mutationId] = StoredEnvelope(cursor, envelope)
            return UploadOutcome.Stored(cursor)
        }

        override fun fetch(actor: AuthenticatedDevice, spaceId: String, afterCursor: Long, limit: Int): List<StoredEnvelope> =
            stored.values.filter { it.serverCursor > afterCursor }.take(limit)

        override fun createInvitation(accountId: String, syncSpaceId: String, ttlSeconds: Long) =
            InvitationCreateResponse("invite", 123)

        override fun bootstrap(request: BootstrapRequest): BootstrapResult = when {
            request.invitationToken != "invite" -> BootstrapResult.InvalidInvitation
            consumed -> BootstrapResult.DeviceAlreadyExists
            else -> {
                consumed = true
                BootstrapResult.Created(BootstrapResponse("account", "space", request.deviceId, "credential"))
            }
        }
    }
}
