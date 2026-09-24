package dev.agenticscheduler.application.sync

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import dev.agenticscheduler.application.history.MutationWallClock
import dev.agenticscheduler.application.history.SyncEngine
import dev.agenticscheduler.application.history.SyncReceiveResult
import dev.agenticscheduler.database.openDesktopDatabase
import dev.agenticscheduler.database.repository.RoomAcademicRepository
import dev.agenticscheduler.database.repository.RoomApplicationTransactionRunner
import dev.agenticscheduler.database.repository.RoomEventRepository
import dev.agenticscheduler.database.repository.RoomLocalEnrollmentRepository
import dev.agenticscheduler.database.repository.RoomMutationJournalRepository
import dev.agenticscheduler.database.repository.RoomPlanningProfileRepository
import dev.agenticscheduler.database.repository.RoomSyncKeyMetadataRepository
import dev.agenticscheduler.database.repository.RoomSyncReceiveRepository
import dev.agenticscheduler.database.repository.RoomTaskRepository
import dev.agenticscheduler.domain.id.EventId
import dev.agenticscheduler.server.sync.ActiveDeviceDirectoryEntry
import dev.agenticscheduler.server.sync.AtomicRevocationRequest
import dev.agenticscheduler.server.sync.BootstrapRequest
import dev.agenticscheduler.server.sync.BootstrapResponse
import dev.agenticscheduler.server.sync.InvitationCreateRequest
import dev.agenticscheduler.server.sync.InvitationCreateResponse
import dev.agenticscheduler.server.sync.JdbcOpaqueSyncRepository
import dev.agenticscheduler.server.sync.RecoveryProofRegistrationRequest
import dev.agenticscheduler.server.sync.RotationPackageResponse
import dev.agenticscheduler.server.sync.ServerSchemaMigrator
import dev.agenticscheduler.server.sync.SyncServerConfig
import dev.agenticscheduler.server.sync.syncServerModule
import dev.agenticscheduler.sync.AccountId
import dev.agenticscheduler.sync.AllDayRangeImage
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
import dev.agenticscheduler.sync.SyncOperation
import dev.agenticscheduler.sync.SyncPayloadV1
import dev.agenticscheduler.sync.SyncSpaceId
import dev.agenticscheduler.sync.SyncWireCodec
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
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull

/**
 * SYN-007A/SYN-007B end-to-end acceptance. This deliberately uses production
 * Ktor routes, JDBC/PostgreSQL, Room key metadata, Windows DPAPI and Tink HPKE
 * rather than a fake lifecycle transport.
 */
class D8RevocationRotationPostgresAcceptanceTest {
    @Test
    fun `A revokes B while C installs the exact server rotation package`() = testApplication {
        val jdbcUrl = System.getenv("SYNC_TEST_DATABASE_URL") ?: return@testApplication
        val dataSource = HikariDataSource(HikariConfig().apply {
            this.jdbcUrl = jdbcUrl
            username = System.getenv("SYNC_TEST_DATABASE_USER") ?: "agentic"
            password = System.getenv("SYNC_TEST_DATABASE_PASSWORD") ?: "agentic-test"
            maximumPoolSize = 3
        })
        val aFile = File.createTempFile("agentic-d8-rotate-a-", ".db")
        val cFile = File.createTempFile("agentic-d8-rotate-c-", ".db")
        val repository = JdbcOpaqueSyncRepository(dataSource)
        val aStore = DesktopPlatformSecureStore()
        val bStore = DesktopPlatformSecureStore()
        val cStore = DesktopPlatformSecureStore()
        val aSecrets = linkedSetOf<SecretReference>()
        val bSecrets = linkedSetOf<SecretReference>()
        val cSecrets = linkedSetOf<SecretReference>()
        val aDatabase = openDesktopDatabase(aFile.absolutePath)
        val cDatabase = openDesktopDatabase(cFile.absolutePath)
        try {
            ServerSchemaMigrator(dataSource).migrate()
            application {
                syncServerModule(
                    repository,
                    SyncServerConfig(jdbcUrl, "agentic", "agentic-test", adminToken = "d8-acceptance-admin"),
                )
            }

            val account = AccountId("d8-rotation-account")
            val space = SyncSpaceId("d8-rotation-space")
            val recoverySecret = RecoverySecret(encodeCanonicalBase64Url(ByteArray(32) { (it + 31).toByte() }))

            val aKeyPair = aStore.generatePairingDeviceKey().also { aSecrets += it.privateKeyReference }
            val bKeyPair = bStore.generatePairingDeviceKey().also { bSecrets += it.privateKeyReference }
            val cKeyPair = cStore.generatePairingDeviceKey().also { cSecrets += it.privateKeyReference }
            val aBootstrap = bootstrap(client, createInvitation(client, account, space).invitationToken, "d8-rotate-device-a", aKeyPair.publicKey.value)
            val bBootstrap = bootstrap(client, createInvitation(client, account, space).invitationToken, "d8-rotate-device-b", bKeyPair.publicKey.value)
            val cBootstrap = bootstrap(client, createInvitation(client, account, space).invitationToken, "d8-rotate-device-c", cKeyPair.publicKey.value)
            val aCredential = aStore.store(DeviceCredential(aBootstrap.deviceCredential)).also { aSecrets += it }
            val bCredential = bStore.store(DeviceCredential(bBootstrap.deviceCredential)).also { bSecrets += it }
            val cCredential = cStore.store(DeviceCredential(cBootstrap.deviceCredential)).also { cSecrets += it }

            val aEnrollment = RoomLocalEnrollmentRepository(aDatabase)
            val aRing = RoomSyncKeyMetadataRepository(aDatabase)
            val aAmk = aStore.generateAccountMasterKey().also { aSecrets += it }
            val aOldContent = aStore.generateContentKey().also { aSecrets += it.reference }
            assertIs<InstallSyncSpaceKeyEpochResult.Installed>(
                aRing.installNewEpoch(space, 7, aOldContent.reference, aOldContent.identity),
            )
            val aActive = LocalEnrollmentState.Active(
                account, DeviceId(aBootstrap.deviceId), EnrollmentRequestId("bootstrap-a"),
                aKeyPair.publicKey, aKeyPair.privateKeyReference, space, aAmk, aCredential,
            )
            aEnrollment.saveActive(aActive)

            val cEnrollment = RoomLocalEnrollmentRepository(cDatabase)
            val cRing = RoomSyncKeyMetadataRepository(cDatabase)
            val cAmk = cStore.importAccountMasterKey(requireNotNull(aStore.exportAccountMasterKeyForPairing(aAmk))).also { cSecrets += it }
            val oldContentMaterial = requireNotNull(aStore.exportContentKeyForPairing(aOldContent.reference))
                .material.copyRawKeyBytesForPairing()
            val cOldContent = cStore.importContentKey(RawEphemeralKeyMaterial.fromBase64Url(encodeCanonicalBase64Url(oldContentMaterial)))
                .also { cSecrets += it.reference }
            assertIs<InstallSyncSpaceKeyEpochResult.Installed>(
                cRing.installNewEpoch(space, 7, cOldContent.reference, cOldContent.identity),
            )
            val cActive = LocalEnrollmentState.Active(
                account, DeviceId(cBootstrap.deviceId), EnrollmentRequestId("bootstrap-c"),
                cKeyPair.publicKey, cKeyPair.privateKeyReference, space, cAmk, cCredential,
            )
            cEnrollment.saveActive(cActive)

            registerRecoveryProof(client, aBootstrap.deviceCredential, account, recoverySecret)
            val aOldEnvelope = encryptAndUpload(
                client, aBootstrap.deviceCredential, space, DeviceId(aBootstrap.deviceId), 7,
                requireNotNull(aStore.contentAead(aOldContent.reference)), "00000000-0000-7000-8000-00000000a701", "Epoch seven history",
            )

            val aTransport = RouteRotationTransport(client, aBootstrap.deviceCredential)
            val rotation = RevocationRotationService(
                transport = aTransport,
                enrollments = aEnrollment,
                keyRing = aRing,
                accountMasterKeys = aStore,
                accountMasterKeyGenerator = aStore,
                contentKeyGenerator = aStore,
                exporter = aStore,
                packages = RotationKeyPackageBuilder(aRing, aStore, TinkPairingHpke()),
                keyMaterial = aStore,
                transactions = RoomApplicationTransactionRunner(aDatabase),
            )
            val prepared = assertIs<RevocationRotationResult.Prepared>(
                rotation.prepare(account, DeviceId(bBootstrap.deviceId), "rotation-8", recoverySecret),
            ).value
            assertIs<RevocationRotationResult.Committed>(rotation.submit(prepared))
            assertEquals(8L, requireNotNull(aRing.state(space)).activeEncryptionEpoch)

            val cRecipient = RotationKeyPackageRecipientService(
                enrollments = cEnrollment,
                privateKeys = cStore,
                hpke = TinkPairingHpke(),
                accountMasterKeys = cStore,
                keyRing = cRing,
                keyPackageInstaller = SyncKeyPackageInstaller(cStore, cRing),
                transactions = RoomApplicationTransactionRunner(cDatabase),
            )
            val catchUp = RotationPackageCatchUpService(
                transport = RouteRotationTransport(client, cBootstrap.deviceCredential),
                recipient = cRecipient,
            ).catchUp()
            assertIs<RotationPackageCatchUpResult.Completed>(catchUp)
            assertEquals(8L, requireNotNull(cRing.state(space)).activeEncryptionEpoch)
            assertEquals(8L, requireNotNull(aRing.currentEncryptionKey(space)).keyEpoch)
            assertEquals(7L, requireNotNull(cRing.decryptionKey(space, 7)).keyEpoch)

            val cEngine = syncEngine(cDatabase)
            val cCodec = AuthenticatedSyncEnvelopeCodec(
                SecureSyncPayloadKeyProvider(cRing, cStore),
                SecureCurrentEncryptionKeyProvider(cRing, cStore),
            )
            val historical = assertIs<EncryptedSyncReceiveResult.Handled>(
                EncryptedSyncReceiveGateway(cCodec, cEngine).receive(
                    SyncWireCodec.encodeEnvelope(aOldEnvelope),
                    serverCursor = 1,
                ),
            )
            assertIs<SyncReceiveResult.Applied>(historical.result)
            assertEquals("Epoch seven history", RoomEventRepository(cDatabase).get(EventId("00000000-0000-7000-8000-00000000a701"))?.title)

            val aNewEnvelope = encryptAndUpload(
                client, aBootstrap.deviceCredential, space, DeviceId(aBootstrap.deviceId), 8,
                requireNotNull(aStore.contentAead(requireNotNull(aRing.currentEncryptionKey(space)).contentKeyReference)),
                "00000000-0000-7000-8000-00000000a801", "Epoch eight from A",
            )
            val cNewEnvelope = encryptAndUpload(
                client, cBootstrap.deviceCredential, space, DeviceId(cBootstrap.deviceId), 8,
                requireNotNull(cStore.contentAead(requireNotNull(cRing.currentEncryptionKey(space)).contentKeyReference)),
                "00000000-0000-7000-8000-00000000c801", "Epoch eight from C",
            )
            assertEquals(8L, aNewEnvelope.keyEpoch)
            assertEquals(8L, cNewEnvelope.keyEpoch)

            assertUnauthorized(client.get("/v1/devices/active") { bearer(bBootstrap.deviceCredential) })
            assertUnauthorized(client.get("/v1/sync/spaces/${space.value}/envelopes?after=0&limit=10") { bearer(bBootstrap.deviceCredential) })
            assertUnauthorized(client.post("/v1/sync/spaces/${space.value}/envelopes") {
                bearer(bBootstrap.deviceCredential)
                contentType(ContentType.Application.Json)
                setBody("{}")
            })
        } finally {
            for (reference in aSecrets) aStore.delete(reference)
            for (reference in bSecrets) bStore.delete(reference)
            for (reference in cSecrets) cStore.delete(reference)
            aDatabase.close()
            cDatabase.close()
            aFile.delete()
            cFile.delete()
            dataSource.close()
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
        val response = client.put("/v1/recovery/proof") {
            bearer(credential)
            contentType(ContentType.Application.Json)
            val proof = RecoveryRegistrationProof.calculate(secret, account.value, 0)
            setBody(json.encodeToString(RecoveryProofRegistrationRequest.serializer(), RecoveryProofRegistrationRequest(RecoveryRegistrationProof.hash(proof), 0)))
        }
        assertEquals(HttpStatusCode.OK, response.status)
    }

    private suspend fun encryptAndUpload(
        client: HttpClient,
        credential: String,
        space: SyncSpaceId,
        sender: DeviceId,
        epoch: Long,
        aead: SyncPayloadAead,
        mutationId: String,
        title: String,
    ): EncryptedEnvelopeV1 {
        val replica = if (sender.value.endsWith("a")) "00000000-0000-7000-8000-00000000000a" else "00000000-0000-7000-8000-00000000000c"
        val operation = SyncOperation(
            mutationId = mutationId,
            dvv = DvvSnapshot(emptyList(), DotSnapshot(replica, 1)),
            hlc = HlcSnapshot(1, 0, replica),
            origin = MutationOrigin.User,
            orderedMutations = listOf(
                EventPut(
                    before = null,
                    after = EventImage(
                        id = mutationId,
                        title = title,
                        time = EventTimeImage.AllDay(AllDayRangeImage("2026-01-01", "2026-01-02")),
                        flexibility = FlexibilityImage.HARD,
                        pinState = PinStateImage.UNPINNED,
                    ),
                ),
            ),
        )
        val envelope = assertIs<EncryptSyncPayloadResult.Encrypted>(
            AuthenticatedSyncEnvelopeCodec(
                object : SyncPayloadKeyProvider {
                    override suspend fun keyFor(syncSpaceId: SyncSpaceId, keyEpoch: Long) =
                        if (syncSpaceId == space && keyEpoch == epoch) SyncPayloadKeyLookup.Available(aead) else SyncPayloadKeyLookup.Missing
                },
                object : CurrentEncryptionKeyProvider {
                    override suspend fun currentEncryptionKey(syncSpaceId: SyncSpaceId) =
                        if (syncSpaceId == space) CurrentEncryptionKeyLookup.Available(epoch, aead) else CurrentEncryptionKeyLookup.Missing
                },
            ).encrypt(SyncEnvelopeBinding(space, mutationId, sender, epoch), SyncPayloadV1(operation = operation)),
        ).envelope
        val response = client.post("/v1/sync/spaces/${space.value}/envelopes") {
            bearer(credential)
            contentType(ContentType.Application.Json)
            setBody(json.encodeToString(EncryptedEnvelopeV1.serializer(), envelope))
        }
        assertEquals(HttpStatusCode.Created, response.status)
        return envelope
    }

    private fun syncEngine(database: dev.agenticscheduler.database.AgenticSchedulerDatabase) = SyncEngine(
        RoomApplicationTransactionRunner(database),
        RoomMutationJournalRepository(database), RoomMutationJournalRepository(database), RoomSyncReceiveRepository(database),
        RoomEventRepository(database), RoomTaskRepository(database), RoomPlanningProfileRepository(database), RoomAcademicRepository(database),
        dev.agenticscheduler.application.id.RfcUuidV7Generator(
            dev.agenticscheduler.application.id.EpochMillisecondsClock { 1 },
            dev.agenticscheduler.application.id.RandomBytes { ByteArray(it) { 1 } },
        ),
        MutationWallClock { 1 },
    )

    private class RouteRotationTransport(
        private val client: HttpClient,
        private val credential: String,
    ) : RevocationRotationTransport, RotationPackageTransport {
        override suspend fun activeDevices(): List<ClientActiveDeviceDirectoryEntry> {
            val response = client.get("/v1/devices/active") { bearer(credential) }
            check(response.status == HttpStatusCode.OK)
            return json.decodeFromString(ListSerializer(ActiveDeviceDirectoryEntry.serializer()), response.bodyAsText())
                .map { ClientActiveDeviceDirectoryEntry(it.deviceId, it.hpkePublicKeyBase64Url) }
        }

        override suspend fun revokeDeviceAndRotate(deviceId: String, request: ClientAtomicRevocationRequest) {
            val response = client.post("/v1/devices/$deviceId/revoke-and-rotate") {
                bearer(credential)
                contentType(ContentType.Application.Json)
                setBody(json.encodeToString(ClientAtomicRevocationRequest.serializer(), request))
            }
            check(response.status == HttpStatusCode.OK)
        }

        override suspend fun rotationPackages(): List<ClientRotationPackageResponse> {
            val response = client.get("/v1/rotations/packages") { bearer(credential) }
            check(response.status == HttpStatusCode.OK)
            return json.decodeFromString(ListSerializer(RotationPackageResponse.serializer()), response.bodyAsText())
                .map { ClientRotationPackageResponse(it.rotationId, it.targetDeviceId, it.packageBase64Url) }
        }
    }

    private companion object {
        val json = Json { encodeDefaults = true; ignoreUnknownKeys = true }

        fun io.ktor.client.request.HttpRequestBuilder.bearer(value: String) {
            header(HttpHeaders.Authorization, "Bearer $value")
        }

        fun assertUnauthorized(response: io.ktor.client.statement.HttpResponse) {
            assertEquals(HttpStatusCode.Unauthorized, response.status)
        }
    }
}
