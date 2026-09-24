package dev.agenticscheduler.server.sync

import dev.agenticscheduler.sync.DeviceId
import dev.agenticscheduler.sync.EncryptedEnvelopeV1
import dev.agenticscheduler.sync.SyncSpaceId
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.put
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
        val bootstrapBody = """{"invitationToken":"invite","deviceId":"device","hpkePublicKeyBase64Url":"AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8"}"""
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
    fun `secondary enrollment relays HPKE request and opaque package`() = testApplication {
        val repository = FakeRepository()
        application { syncServerModule(repository, testConfig()) }
        val publicKey = "AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8"
        val requestBody = """{"accountId":"account","requestId":"req-1","targetDeviceId":"target","hpkePublicKeyBase64Url":"$publicKey","credentialHashBase64Url":"$publicKey"}"""
        assertEquals(HttpStatusCode.Created, client.post("/v1/enrollments") {
            contentType(ContentType.Application.Json)
            setBody(requestBody)
        }.status)
        assertEquals(HttpStatusCode.OK, client.get("/v1/enrollments/pending") {
            header(HttpHeaders.Authorization, "Bearer credential")
        }.status)
        assertEquals(HttpStatusCode.Created, client.post("/v1/enrollments/req-1/approve") {
            header(HttpHeaders.Authorization, "Bearer credential")
            contentType(ContentType.Application.Json)
            setBody("""{"packageBase64Url":"AQI"}""")
        }.status)
        val packageResponse = client.get("/v1/enrollments/req-1/package?targetDeviceId=target")
        assertEquals(HttpStatusCode.OK, packageResponse.status)
        assertTrue(packageResponse.bodyAsText().contains("AQI"))
    }

    @Test
    fun `fresh recovery bootstrap returns opaque envelope and counter without device credential`() = testApplication {
        val repository = FakeRepository()
        application { syncServerModule(repository, testConfig()) }
        val hash = "AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8"
        assertEquals(HttpStatusCode.OK, client.put("/v1/recovery/envelope") {
            header(HttpHeaders.Authorization, "Bearer credential")
            contentType(ContentType.Application.Json)
            setBody("""{"blobBase64Url":"AQI"}""")
        }.status)
        assertEquals(HttpStatusCode.OK, client.put("/v1/recovery/proof") {
            header(HttpHeaders.Authorization, "Bearer credential")
            contentType(ContentType.Application.Json)
            setBody("""{"proofHashBase64Url":"$hash","counter":7}""")
        }.status)
        val response = client.post("/v1/recovery/bootstrap") {
            contentType(ContentType.Application.Json)
            setBody("""{"accountId":"account"}""")
        }
        assertEquals(HttpStatusCode.OK, response.status)
        assertTrue(response.bodyAsText().contains("\"counter\":7"))
        assertTrue(response.bodyAsText().contains("\"recoveryEnvelopeBase64Url\":\"AQI\""))
        val missing = client.post("/v1/recovery/bootstrap") {
            contentType(ContentType.Application.Json)
            setBody("""{"accountId":"missing"}""")
        }
        assertEquals(HttpStatusCode.NotFound, missing.status)
    }

    @Test
    fun `recovery proof registration and enrollment use rotating verifier`() = testApplication {
        val repository = FakeRepository()
        application { syncServerModule(repository, testConfig()) }
        val hash = "AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8"
        assertEquals(HttpStatusCode.OK, client.put("/v1/recovery/proof") {
            header(HttpHeaders.Authorization, "Bearer credential")
            contentType(ContentType.Application.Json)
            setBody("""{"proofHashBase64Url":"$hash","counter":0}""")
        }.status)
        val response = client.post("/v1/recovery/enroll") {
            contentType(ContentType.Application.Json)
            setBody("""{"accountId":"account","requestId":"recovery-1","targetDeviceId":"recovered","hpkePublicKeyBase64Url":"$hash","credentialHashBase64Url":"$hash","proofBase64Url":"$hash","counter":0,"nextProofHashBase64Url":"$hash"}""")
        }
        assertEquals(HttpStatusCode.Created, response.status)
    }


    @Test
    fun `active device directory is authenticated deterministic and fails closed when incomplete`() = testApplication {
        val repository = FakeRepository()
        application { syncServerModule(repository, testConfig()) }

        assertEquals(HttpStatusCode.Unauthorized, client.get("/v1/devices/active").status)

        val response = client.get("/v1/devices/active") {
            header(HttpHeaders.Authorization, "Bearer credential")
        }
        assertEquals(HttpStatusCode.OK, response.status)
        val body = response.bodyAsText()
        assertTrue(body.indexOf("\"device-a\"") < body.indexOf("\"device-b\""))
        assertTrue(body.contains("AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8"))

        repository.incompleteDirectory = true
        assertEquals(HttpStatusCode.Conflict, client.get("/v1/devices/active") {
            header(HttpHeaders.Authorization, "Bearer credential")
        }.status)
    }


    @Test
    fun `rotation package fetch is bearer bound to current device`() = testApplication {
        val repository = FakeRepository()
        application { syncServerModule(repository, testConfig()) }

        assertEquals(HttpStatusCode.Unauthorized, client.get("/v1/rotations/packages").status)

        val response = client.get("/v1/rotations/packages") {
            header(HttpHeaders.Authorization, "Bearer credential")
        }
        assertEquals(HttpStatusCode.OK, response.status)
        val body = response.bodyAsText()
        assertTrue(body.contains("\"rotationId\":\"rotation-1\""))
        assertTrue(body.contains("\"targetDeviceId\":\"device\""))
        assertTrue(body.contains("\"packageBase64Url\":\"AQI\""))
    }

    @Test
    fun `atomic revocation accepts opaque package set`() = testApplication {
        val repository = FakeRepository()
        application { syncServerModule(repository, testConfig()) }
        val blob = "AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8"
        val response = client.post("/v1/devices/other/revoke-and-rotate") {
            header(HttpHeaders.Authorization, "Bearer credential")
            contentType(ContentType.Application.Json)
            setBody("""{"rotationId":"rotation-1","recoveryEnvelopeBase64Url":"$blob","packages":[{"deviceId":"device","packageBase64Url":"$blob"}]}""")
        }
        assertEquals(HttpStatusCode.OK, response.status)
    }

    @Test
    fun `recovery envelope is opaque and credential-only revocation is unavailable`() = testApplication {
        val repository = FakeRepository()
        application { syncServerModule(repository, testConfig()) }
        val stored = client.put("/v1/recovery/envelope") {
            header(HttpHeaders.Authorization, "Bearer credential")
            contentType(ContentType.Application.Json)
            setBody("""{"blobBase64Url":"AQI"}""")
        }
        assertEquals(HttpStatusCode.OK, stored.status)
        val fetched = client.get("/v1/recovery/envelope") { header(HttpHeaders.Authorization, "Bearer credential") }
        assertEquals(HttpStatusCode.OK, fetched.status)
        assertTrue(fetched.bodyAsText().contains("AQI"))
        assertEquals(HttpStatusCode.NotFound, client.post("/v1/devices/other/revoke") {
            header(HttpHeaders.Authorization, "Bearer credential")
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

    private class FakeRepository : OpaqueSyncRepository, ServerBootstrapRepository, ServerEnrollmentRepository, ServerSecurityLifecycleRepository {
        private val stored = linkedMapOf<String, StoredEnvelope>()
        private var enrollment: EnrollmentRequestWire? = null
        private var packageBytes: ByteArray? = null
        private var recoveryBytes: ByteArray? = null
        private var recoveryProof: RecoveryProofRegistrationRequest? = null
        private var consumed = false
        var incompleteDirectory = false
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

        override fun registerEnrollment(request: EnrollmentRequestWire, ttlSeconds: Long): EnrollmentRegistrationResult {
            if (enrollment != null) return EnrollmentRegistrationResult.DuplicateRequest
            enrollment = request
            return EnrollmentRegistrationResult.Created(123)
        }

        override fun pendingEnrollments(actor: AuthenticatedDevice): List<PendingEnrollmentResponse>? =
            enrollment?.let { listOf(PendingEnrollmentResponse(it.accountId, it.requestId, it.targetDeviceId, it.hpkePublicKeyBase64Url)) }

        override fun approveEnrollment(actor: AuthenticatedDevice, requestId: String, packageBytes: ByteArray): EnrollmentApprovalResult {
            if (enrollment?.requestId != requestId) return EnrollmentApprovalResult.NotFound
            if (this.packageBytes != null) return EnrollmentApprovalResult.AlreadyApproved
            this.packageBytes = packageBytes
            return EnrollmentApprovalResult.Approved
        }

        override fun fetchKeyPackage(requestId: String, targetDeviceId: String): ByteArray? =
            if (enrollment?.requestId == requestId && enrollment?.targetDeviceId == targetDeviceId) packageBytes else null

        override fun saveRecoveryEnvelope(actor: AuthenticatedDevice, envelopeBytes: ByteArray): Boolean {
            recoveryBytes = envelopeBytes
            return true
        }

        override fun registerRecoveryProof(actor: AuthenticatedDevice, request: RecoveryProofRegistrationRequest): RecoveryProofRegistrationResult {
            recoveryProof = request
            return RecoveryProofRegistrationResult.Stored
        }

        override fun recoveryBootstrap(accountId: String): RecoveryBootstrapDescriptor? {
            val proof = recoveryProof ?: return null
            val envelope = recoveryBytes ?: return null
            if (accountId != "account") return null
            return RecoveryBootstrapDescriptor(proof.counter, envelope)
        }

        override fun enrollWithRecovery(request: RecoveryEnrollmentRequestWire): RecoveryEnrollmentResult =
            RecoveryEnrollmentResult.Created(request.accountId, request.targetDeviceId)

        override fun activeDevices(actor: AuthenticatedDevice): ActiveDeviceDirectoryResult =
            if (incompleteDirectory) ActiveDeviceDirectoryResult.IncompleteIdentity
            else ActiveDeviceDirectoryResult.Available(
                listOf(
                    ActiveDeviceDirectoryEntry("device-a", "AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8"),
                    ActiveDeviceDirectoryEntry("device-b", "AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8"),
                ),
            )

        override fun rotationPackages(actor: AuthenticatedDevice): List<StoredRotationPackage>? =
            listOf(StoredRotationPackage("rotation-1", byteArrayOf(1, 2)))

        override fun revokeAndRotate(actor: AuthenticatedDevice, targetDeviceId: String, request: AtomicRevocationRequest): AtomicRevocationResult =
            AtomicRevocationResult.Applied

        override fun fetchRecoveryEnvelope(actor: AuthenticatedDevice): ByteArray? = recoveryBytes

    }
}
