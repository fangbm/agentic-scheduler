package dev.agenticscheduler.application.sync

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import dev.agenticscheduler.application.persistence.ApplicationTransactionRunner
import dev.agenticscheduler.application.history.MutationWallClock
import dev.agenticscheduler.application.history.SyncEngine
import dev.agenticscheduler.application.history.SyncReceiveResult
import dev.agenticscheduler.database.openDesktopDatabase
import dev.agenticscheduler.database.repository.RoomApplicationTransactionRunner
import dev.agenticscheduler.database.repository.RoomAcademicRepository
import dev.agenticscheduler.database.repository.RoomEventRepository
import dev.agenticscheduler.database.repository.RoomLocalEnrollmentRepository
import dev.agenticscheduler.database.repository.RoomMutationJournalRepository
import dev.agenticscheduler.database.repository.RoomSyncKeyMetadataRepository
import dev.agenticscheduler.database.repository.RoomSyncReceiveRepository
import dev.agenticscheduler.database.repository.RoomTaskRepository
import dev.agenticscheduler.database.repository.RoomPlanningProfileRepository
import dev.agenticscheduler.server.sync.BootstrapRequest
import dev.agenticscheduler.server.sync.BootstrapResponse
import dev.agenticscheduler.server.sync.InvitationCreateRequest
import dev.agenticscheduler.server.sync.InvitationCreateResponse
import dev.agenticscheduler.server.sync.JdbcOpaqueSyncRepository
import dev.agenticscheduler.server.sync.OpaqueBlobRequest
import dev.agenticscheduler.server.sync.RecoveryProofRegistrationRequest
import dev.agenticscheduler.server.sync.ServerSchemaMigrator
import dev.agenticscheduler.server.sync.SyncServerConfig
import dev.agenticscheduler.server.sync.syncServerModule
import dev.agenticscheduler.sync.AccountId
import dev.agenticscheduler.sync.DeviceId
import dev.agenticscheduler.sync.DotSnapshot
import dev.agenticscheduler.sync.DvvSnapshot
import dev.agenticscheduler.sync.EncryptedEnvelopeV1
import dev.agenticscheduler.sync.EnrollmentRequestId
import dev.agenticscheduler.sync.EventImage
import dev.agenticscheduler.sync.EventPut
import dev.agenticscheduler.sync.EventTimeImage
import dev.agenticscheduler.sync.FlexibilityImage
import dev.agenticscheduler.sync.HlcSnapshot
import dev.agenticscheduler.sync.MutationOrigin
import dev.agenticscheduler.sync.PinStateImage
import dev.agenticscheduler.sync.RecoveryEnvelopePlaintextV1
import dev.agenticscheduler.sync.RecoveryHistoricalKeyV1
import dev.agenticscheduler.sync.RecoverySyncSpaceKeyRingV1
import dev.agenticscheduler.sync.RecoveryWireCodec
import dev.agenticscheduler.sync.SyncSpaceId
import dev.agenticscheduler.sync.SyncPayloadV1
import dev.agenticscheduler.sync.SyncWireCodec
import dev.agenticscheduler.sync.SyncOperation
import dev.agenticscheduler.sync.encodeCanonicalBase64Url
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
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import dev.agenticscheduler.domain.id.EventId
import dev.agenticscheduler.sync.AllDayRangeImage

/**
 * SYN-005B/005C acceptance: actual Ktor routes and JDBC/PostgreSQL are used
 * with a fresh Room replica and the production desktop secure store. The
 * test is intentionally opt-in: CI supplies SYNC_TEST_DATABASE_URL.
 */
class D8RecoveryLifecyclePostgresAcceptanceTest {
    @Test
    fun `fresh device recovers complete key ring through Ktor and PostgreSQL`() = testApplication {
        val jdbcUrl = System.getenv("SYNC_TEST_DATABASE_URL") ?: return@testApplication
        val dataSource = HikariDataSource(HikariConfig().apply {
            this.jdbcUrl = jdbcUrl
            username = System.getenv("SYNC_TEST_DATABASE_USER") ?: "agentic"
            password = System.getenv("SYNC_TEST_DATABASE_PASSWORD") ?: "agentic-test"
            maximumPoolSize = 3
        })
        val databaseFile = File.createTempFile("agentic-d8-recovery-", ".db")
        val repository = JdbcOpaqueSyncRepository(dataSource)
        val serverConfig = SyncServerConfig(jdbcUrl, "agentic", "agentic-test", adminToken = "d8-acceptance-admin")
        val aStore = DesktopPlatformSecureStore()
        var aCredential: SecretReference? = null
        var amk: SecretReference? = null
        var activeKey: ImportedContentKey? = null
        try {
            ServerSchemaMigrator(dataSource).migrate()
            application { syncServerModule(repository, serverConfig) }

            val account = AccountId("d8-recovery-account")
            val space = SyncSpaceId("d8-recovery-space")
            val secret = RecoverySecret(encodeCanonicalBase64Url(ByteArray(32) { (it + 11).toByte() }))
            val aPairing = TinkPairingHpke().generateDeviceKeyPair()
            val invitation = createInvitation(client, account, space)
            val bootstrapped = bootstrap(client, invitation.invitationToken, "device-a", aPairing.publicKey.value)

            aCredential = aStore.store(DeviceCredential(bootstrapped.deviceCredential))
            amk = aStore.generateAccountMasterKey()
            activeKey = aStore.generateContentKey()
            val envelope = RecoveryEnvelopeCodec.seal(
                secret,
                RecoveryEnvelopePlaintextV1(
                    accountId = account,
                    keyEpoch = 7,
                    accountMasterKeyBase64Url = encodeCanonicalBase64Url(
                        requireNotNull(aStore.exportAccountMasterKeyForPairing(requireNotNull(amk))).copyRawKeyBytesForPairing(),
                    ),
                    syncSpace = RecoverySyncSpaceKeyRingV1(
                        syncSpaceId = space,
                        activeEpoch = 7,
                        activeKeyBase64Url = encodeCanonicalBase64Url(
                            requireNotNull(aStore.exportContentKeyForPairing(requireNotNull(activeKey).reference)).material.copyRawKeyBytesForPairing(),
                        ),
                        historicalKeys = listOf(
                            RecoveryHistoricalKeyV1(6, encodeCanonicalBase64Url(ByteArray(32) { (it + 77).toByte() })),
                        ),
                    ),
                ),
            )
            registerRecoveryProof(client, bootstrapped.deviceCredential, account, secret)
            saveRecoveryEnvelope(client, bootstrapped.deviceCredential, envelope)
            val remoteEnvelope = uploadHistoricalEvent(client, bootstrapped.deviceCredential, space, aStore, requireNotNull(activeKey).reference)
            assertServerTablesExclude(
                dataSource,
                "Recovered encrypted history",
                secret.value,
                bootstrapped.deviceCredential,
                encodeCanonicalBase64Url(
                    requireNotNull(aStore.exportContentKeyForPairing(requireNotNull(activeKey).reference))
                        .material.copyRawKeyBytesForPairing(),
                ),
            )

            val cDatabase = openDesktopDatabase(databaseFile.absolutePath)
            val cStore = DesktopPlatformSecureStore()
            val cReferences = linkedSetOf<SecretReference>()
            try {
                val enrollment = RoomLocalEnrollmentRepository(cDatabase)
                val ring = RoomSyncKeyMetadataRepository(cDatabase)
                val recovered = RecoveryEnrollmentService(
                    transport = RouteRecoveryTransport(client),
                    pendingEnrollment = LocalEnrollmentRequestService(enrollment, cStore, cStore),
                    enrollments = enrollment,
                    credentials = cStore,
                    accountMasterKeys = cStore,
                    keyPackageInstaller = SyncKeyPackageInstaller(cStore, ring),
                    transactions = RoomApplicationTransactionRunner(cDatabase),
                ).recover(account, secret, DeviceId("device-c"), EnrollmentRequestId("recovery-c"))

                val active = assertIs<RecoveryEnrollmentServiceResult.Activated>(recovered).state
                assertEquals(space, active.syncSpaceId)
                assertEquals(7L, requireNotNull(ring.state(space)).activeEncryptionEpoch)
                assertNotNull(ring.decryptionKey(space, 7))
                assertNotNull(ring.decryptionKey(space, 6), "Recovered historical key must remain decrypt-capable.")
                assertNotNull(cStore.load(active.deviceCredentialReference), "Recovery enrollment must stage a usable device credential.")
                cReferences += active.deviceCredentialReference
                cReferences += active.accountMasterKeyReference
                cReferences += requireNotNull(ring.currentEncryptionKey(space)).contentKeyReference
                cReferences += ring.historicalDecryptKeys(space).map { it.contentKeyReference }
                val received = RoomSyncReceiveRepository(cDatabase)
                val journal = RoomMutationJournalRepository(cDatabase)
                val engine = SyncEngine(
                    RoomApplicationTransactionRunner(cDatabase), journal, journal, received,
                    RoomEventRepository(cDatabase), RoomTaskRepository(cDatabase), RoomPlanningProfileRepository(cDatabase),
                    RoomAcademicRepository(cDatabase),
                    dev.agenticscheduler.application.id.RfcUuidV7Generator(
                        dev.agenticscheduler.application.id.EpochMillisecondsClock { 1 },
                        dev.agenticscheduler.application.id.RandomBytes { ByteArray(it) { 1 } },
                    ),
                    MutationWallClock { 1 },
                )
                val keys = SecureSyncPayloadKeyProvider(ring, cStore)
                val codec = AuthenticatedSyncEnvelopeCodec(keys, SecureCurrentEncryptionKeyProvider(ring, cStore))
                val fetched = fetchEnvelope(client, cStore.load(active.deviceCredentialReference)!!.value, space)
                assertEquals(remoteEnvelope, fetched.envelope)
                val outcome = EncryptedSyncReceiveGateway(codec, engine).receive(
                    SyncWireCodec.encodeEnvelope(fetched.envelope),
                    fetched.serverCursor,
                )
                val handled = assertIs<EncryptedSyncReceiveResult.Handled>(outcome)
                assertIs<SyncReceiveResult.Applied>(handled.result)
                assertEquals("Recovered encrypted history", RoomEventRepository(cDatabase).get(EventId("00000000-0000-7000-8000-000000009999"))?.title)
            } finally {
                for (reference in cReferences) {
                    cStore.delete(reference)
                }
                cDatabase.close()
            }
        } finally {
            if (aCredential != null) aStore.delete(requireNotNull(aCredential))
            if (amk != null) aStore.delete(requireNotNull(amk))
            if (activeKey != null) aStore.delete(requireNotNull(activeKey).reference)
            dataSource.close()
            databaseFile.delete()
        }
    }

    private suspend fun createInvitation(client: HttpClient, account: AccountId, space: SyncSpaceId): InvitationCreateResponse {
        val response = client.post("/v1/admin/invitations") {
            header("X-Sync-Admin-Token", "d8-acceptance-admin")
            contentType(ContentType.Application.Json)
            setBody(json.encodeToString(InvitationCreateRequest.serializer(), InvitationCreateRequest(account.value, space.value)))
        }
        assertEquals(HttpStatusCode.Created, response.status)
        return json.decodeFromString(InvitationCreateResponse.serializer(), response.bodyAsText())
    }

    private suspend fun bootstrap(client: HttpClient, invitation: String, deviceId: String, hpke: String): BootstrapResponse {
        val response = client.post("/v1/bootstrap") {
            contentType(ContentType.Application.Json)
            setBody(json.encodeToString(BootstrapRequest.serializer(), BootstrapRequest(invitation, deviceId, hpke)))
        }
        assertEquals(HttpStatusCode.Created, response.status)
        return json.decodeFromString(BootstrapResponse.serializer(), response.bodyAsText())
    }

    private suspend fun registerRecoveryProof(client: HttpClient, credential: String, account: AccountId, secret: RecoverySecret) {
        val proof = RecoveryRegistrationProof.calculate(secret, account.value, 0)
        val response = client.put("/v1/recovery/proof") {
            header(HttpHeaders.Authorization, "Bearer $credential")
            contentType(ContentType.Application.Json)
            setBody(json.encodeToString(RecoveryProofRegistrationRequest.serializer(), RecoveryProofRegistrationRequest(RecoveryRegistrationProof.hash(proof), 0)))
        }
        assertEquals(HttpStatusCode.OK, response.status)
    }

    private suspend fun saveRecoveryEnvelope(client: HttpClient, credential: String, envelope: dev.agenticscheduler.sync.RecoveryEnvelopeV1) {
        val encoded = encodeCanonicalBase64Url(RecoveryWireCodec.encodeEnvelope(envelope).encodeToByteArray())
        val response = client.put("/v1/recovery/envelope") {
            header(HttpHeaders.Authorization, "Bearer $credential")
            contentType(ContentType.Application.Json)
            setBody(json.encodeToString(OpaqueBlobRequest.serializer(), OpaqueBlobRequest(encoded)))
        }
        assertEquals(HttpStatusCode.OK, response.status)
    }

    private suspend fun uploadHistoricalEvent(
        client: HttpClient,
        credential: String,
        space: SyncSpaceId,
        store: DesktopPlatformSecureStore,
        keyReference: SecretReference,
    ): EncryptedEnvelopeV1 {
        val mutationId = "00000000-0000-7000-8000-000000009998"
        val operation = SyncOperation(
            mutationId = mutationId,
            dvv = DvvSnapshot(emptyList(), DotSnapshot("00000000-0000-7000-8000-000000000001", 1)),
            hlc = HlcSnapshot(1, 0, "00000000-0000-7000-8000-000000000001"),
            origin = MutationOrigin.User,
            orderedMutations = listOf(
                EventPut(
                    before = null,
                    after = EventImage(
                        id = "00000000-0000-7000-8000-000000009999",
                        title = "Recovered encrypted history",
                        time = EventTimeImage.AllDay(AllDayRangeImage("2026-01-01", "2026-01-02")),
                        flexibility = FlexibilityImage.HARD,
                        pinState = PinStateImage.UNPINNED,
                    ),
                ),
            ),
        )
        val aead = requireNotNull(store.contentAead(keyReference))
        val codec = AuthenticatedSyncEnvelopeCodec(
            object : SyncPayloadKeyProvider {
                override suspend fun keyFor(syncSpaceId: SyncSpaceId, keyEpoch: Long) =
                    if (syncSpaceId == space && keyEpoch == 7L) SyncPayloadKeyLookup.Available(aead) else SyncPayloadKeyLookup.Missing
            },
            object : CurrentEncryptionKeyProvider {
                override suspend fun currentEncryptionKey(syncSpaceId: SyncSpaceId) =
                    if (syncSpaceId == space) CurrentEncryptionKeyLookup.Available(7, aead) else CurrentEncryptionKeyLookup.Missing
            },
        )
        val encrypted = assertIs<EncryptSyncPayloadResult.Encrypted>(
            codec.encrypt(SyncEnvelopeBinding(space, mutationId, DeviceId("device-a"), 7), SyncPayloadV1(operation = operation)),
        ).envelope
        val response = client.post("/v1/sync/spaces/${space.value}/envelopes") {
            header(HttpHeaders.Authorization, "Bearer $credential")
            contentType(ContentType.Application.Json)
            setBody(json.encodeToString(EncryptedEnvelopeV1.serializer(), encrypted))
        }
        assertEquals(HttpStatusCode.Created, response.status)
        return encrypted
    }

    private suspend fun fetchEnvelope(client: HttpClient, credential: String, space: SyncSpaceId): dev.agenticscheduler.server.sync.StoredEnvelopeResponse {
        val response = client.get("/v1/sync/spaces/${space.value}/envelopes?after=0&limit=10") {
            header(HttpHeaders.Authorization, "Bearer $credential")
        }
        assertEquals(HttpStatusCode.OK, response.status)
        return json.decodeFromString(
            kotlinx.serialization.builtins.ListSerializer(dev.agenticscheduler.server.sync.StoredEnvelopeResponse.serializer()),
            response.bodyAsText(),
        ).single()
    }

    private class RouteRecoveryTransport(private val client: HttpClient) : RecoveryEnrollmentTransport {
        override suspend fun recoveryBootstrap(accountId: String): ClientRecoveryBootstrapResponse {
            val response = client.post("/v1/recovery/bootstrap") {
                contentType(ContentType.Application.Json)
                setBody(json.encodeToString(ClientRecoveryBootstrapRequest.serializer(), ClientRecoveryBootstrapRequest(accountId)))
            }
            check(response.status == HttpStatusCode.OK)
            return json.decodeFromString(ClientRecoveryBootstrapResponse.serializer(), response.bodyAsText())
        }

        override suspend fun enrollWithRecovery(request: ClientRecoveryEnrollmentRequest): ClientRecoveryEnrollmentCreated {
            val response = client.post("/v1/recovery/enroll") {
                contentType(ContentType.Application.Json)
                setBody(json.encodeToString(ClientRecoveryEnrollmentRequest.serializer(), request))
            }
            check(response.status == HttpStatusCode.Created)
            return json.decodeFromString(ClientRecoveryEnrollmentCreated.serializer(), response.bodyAsText())
        }
    }

    /**
     * D8's server is an opaque relay. This scans every public server table's
     * JSON projection after a real recovery/upload flow, so accidentally
     * persisting a known title or any client secret fails the acceptance test.
     */
    private fun assertServerTablesExclude(dataSource: HikariDataSource, vararg forbidden: String) {
        dataSource.connection.use { connection ->
            val tables = connection.prepareStatement(
                "SELECT table_name FROM information_schema.tables WHERE table_schema = 'public' AND table_type = 'BASE TABLE'",
            ).use { statement ->
                statement.executeQuery().use { rows ->
                    buildList { while (rows.next()) add(rows.getString(1)) }
                }
            }
            tables.forEach { table ->
                val quoted = "\"" + table.replace("\"", "\"\"") + "\""
                connection.createStatement().use { statement ->
                    statement.executeQuery("SELECT to_jsonb(row)::text FROM $quoted AS row").use { rows ->
                        while (rows.next()) {
                            val persisted = rows.getString(1)
                            forbidden.forEach { value ->
                                kotlin.test.assertFalse(
                                    persisted.contains(value),
                                    "Server table $table must not retain client plaintext or secret material.",
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    private companion object {
        val json = Json { encodeDefaults = true; ignoreUnknownKeys = true }
    }
}
