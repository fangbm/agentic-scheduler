package dev.agenticscheduler.acceptance

import dev.agenticscheduler.application.history.MutationWallClock
import dev.agenticscheduler.application.history.SyncEngine
import dev.agenticscheduler.application.id.EpochMillisecondsClock
import dev.agenticscheduler.application.id.productionUuidV7Generator
import dev.agenticscheduler.application.sync.*
import dev.agenticscheduler.agent.history.*
import dev.agenticscheduler.database.AgenticSchedulerDatabase
import dev.agenticscheduler.database.repository.*
import dev.agenticscheduler.sync.*
import io.ktor.client.HttpClient
import io.ktor.client.request.*
import io.ktor.client.statement.bodyAsText
import io.ktor.http.*
import kotlinx.serialization.json.*
import java.security.KeyStore
import java.security.cert.CertificateFactory
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

/** Test sources only. Each platform enrolls with real routes and retains its own protected secrets. */
class EnrolledPlatformReplica(
    private val client: HttpClient,
    private val baseUrl: String,
    private val account: AccountId,
    val space: SyncSpaceId,
    private val platform: String,
    private val store: PlatformD8SecureStore,
    private val open: () -> AgenticSchedulerDatabase,
) {
    private var database = open()
    val history get() = RoomAgentSyncPersistence(database)
    val state get() = RoomAgentSyncTransportPersistence(database)
    private val enrollment get() = RoomLocalEnrollmentRepository(database)
    private val ring get() = RoomSyncKeyMetadataRepository(database)
    private val journal get() = RoomMutationJournalRepository(database)
    private val receive get() = RoomSyncReceiveRepository(database)
    private val transactions get() = RoomApplicationTransactionRunner(database)
    private val clock = MutationWallClock { 1 }
    private val replica = AgentReplicaId(id(if (platform == "desktop") 8000 else 8001))
    private val device = DeviceId("${account.value}-$platform")
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private lateinit var active: LocalEnrollmentState.Active
    private val keys get() = SecureCurrentEncryptionKeyProvider(ring, store)
    private val codec get() = AuthenticatedSyncEnvelopeCodec(SecureSyncPayloadKeyProvider(ring, store), keys)
    val transport get() = KtorSyncTransport(client, baseUrl, { store.load(active.deviceCredentialReference) })
    val reader get() = AgentHistoryContinuationReader(history, state, transactions)

    suspend fun enrollOrRestore() {
        val old = enrollment.state(account)
        if (old is LocalEnrollmentState.Active) { active = old; check(old.deviceId == device); return }
        val hpke = store.generatePairingDeviceKey()
        val invitation = client.post("$baseUrl/v1/admin/invitations") {
            header("X-Sync-Admin-Token", "d90205-disposable-admin"); contentType(ContentType.Application.Json)
            setBody(buildJsonObject { put("accountId", account.value); put("syncSpaceId", space.value) }.toString())
        }
        check(invitation.status == HttpStatusCode.Created)
        val token = json.parseToJsonElement(invitation.bodyAsText()).jsonObject.getValue("invitationToken").jsonPrimitive.content
        val bootstrap = client.post("$baseUrl/v1/bootstrap") {
            contentType(ContentType.Application.Json)
            setBody(buildJsonObject { put("invitationToken", token); put("deviceId", device.value); put("hpkePublicKeyBase64Url", hpke.publicKey.value) }.toString())
        }
        check(bootstrap.status == HttpStatusCode.Created) { "Bootstrap status ${bootstrap.status}" }
        val credential = json.parseToJsonElement(bootstrap.bodyAsText()).jsonObject.getValue("deviceCredential").jsonPrimitive.content
        active = LocalEnrollmentState.Active(account, device, EnrollmentRequestId("d90205-$platform"), hpke.publicKey,
            hpke.privateKeyReference, space, store.generateAccountMasterKey(), store.store(DeviceCredential(credential)))
        enrollment.saveActive(active)
        // Fixture key distribution only: platform protection/keyring/AEAD are real, pairing is separate D8 evidence.
        val imported = store.importContentKey(object : ImportedContentKeyMaterial {
            override fun copyRawSecretBytesForSecureStore() = KEY.copyOf()
        })
        check(ring.installNewEpoch(space, 7, imported.reference, imported.identity) !is InstallSyncSpaceKeyEpochResult.IntegrityError)
        history.provisionLocalReplica(space, replica)
        check(!state.conversationConsent(space))
        AgentConversationSyncSettings(enrollment, state, state, history).setFromUser(space, true, true)
    }

    suspend fun seedDesktopOffline() {
        val local = RoomAgentStateRepository(database)
        val threadId = AgentThreadId(THREAD.value)
        local.saveThread(AgentThread(threadId, TITLE, 1))
        local.beginLocalHistoryTurn(threadId, DESKTOP_TURN.value)
        local.appendMessage(AgentMessage(AgentMessageId(id(6001)), threadId, 0, AgentMessageRole.USER, DESKTOP_TEXT, 2))
        local.appendMessage(AgentMessage(AgentMessageId(id(6003)), threadId, 1, AgentMessageRole.ASSISTANT, DESKTOP_ASSISTANT_TEXT, 3))
        check(local.finalizeLocalHistoryTurn(threadId, DESKTOP_TURN.value, AgentLocalTurnOutcome.SUCCEEDED))
        val exporter = AgentHistoryExplicitExport(enrollment, state, history, history,
            RoomAgentHistoryExportSource(database, local), productionUuidV7Generator(), EpochMillisecondsClock { 10 })
        check(exporter.availability(space).eligibleTurns == 1)
        check(exporter.exportFromUser(space).newlyQueuedFacts == 4)
        loseAckAndPersistRetry()
    }

    suspend fun resumeDesktopAfterServerRestart() {
        val retained = state.outboundRecords(space).mapNotNull { it.envelope }.associateBy { it.mutationId }
        check(retained.isNotEmpty())
        check(worker().run(space).agentOutbound.uploaded == 4)
        transport.fetch(space, 0, 100).forEach { check(retained[it.envelope.mutationId]?.let { value -> value == it.envelope } != false) }
        check(worker().run(space).agentOutbound.uploaded == 0)
    }

    suspend fun androidSeedOffline() {
        check(worker().run(space).stoppedOnReceiveFailure == null)
        check(history.threadHistoryProjection(space, THREAD).turns.single().members.map { it.agentEvent }.count { it is MessageAppended } == 2)
        check(reader.read(space, THREAD) is AgentHistoryContinuationRead.Ready)
        author(7003, MessageAppended(AgentMessageSyncId(id(7001)), THREAD, ANDROID_TURN, AgentMessageRoleV3.ASSISTANT, ANDROID_TEXT, 1))
        author(7004, TurnFinalized(ANDROID_TURN, THREAD, listOf(DESKTOP_TURN), listOf(MessageMember(AgentMessageSyncId(id(7001)))), AgentTurnOutcome.SUCCEEDED))
        loseAckAndPersistRetry()
    }

    suspend fun androidResumeAfterProcessRestart() {
        val retained = state.outboundRecords(space).mapNotNull { it.envelope }.associateBy { it.mutationId }
        check(worker().run(space).agentOutbound.uploaded == 2)
        transport.fetch(space, 0, 100).filter { it.envelope.mutationId in retained }.forEach { check(retained.getValue(it.envelope.mutationId) == it.envelope) }
        assertConverged()
    }

    suspend fun verifyDesktop() { worker().run(space); assertConverged() }
    private suspend fun assertConverged() {
        val projection = history.threadHistoryProjection(space, THREAD)
        check(projection.turns.map { it.manifest.turnId } == listOf(DESKTOP_TURN, ANDROID_TURN))
        check(projection.providerContinuationAllowed)
        check(history.dvvFrontier(space).components == mapOf(AgentReplicaId(id(8000)) to 3L, AgentReplicaId(id(8001)) to 1L))
        check(receive.handledDots(space).isEmpty()); check(journal.timeline().isEmpty())
        check(worker().run(space).agentOutbound.uploaded == 0)
        reopen()
        check(projection == history.threadHistoryProjection(space, THREAD))
    }

    private suspend fun loseAckAndPersistRetry() {
        val route = transport
        var attempted: EncryptedEnvelopeV1? = null
        val lossy = object : SyncTransport by route {
            override suspend fun upload(envelope: EncryptedEnvelopeV1): SyncUploadResult {
                attempted = envelope
                check(route.upload(envelope) is SyncUploadResult.Stored)
                return SyncUploadResult.RetryableFailure("ACCEPTANCE_ACK_LOST_AFTER_COMMIT")
            }
        }
        check(worker(lossy).run(space).agentOutbound.failure is SyncUploadResult.RetryableFailure)
        check(state.outboundRecords(space).single { it.operation.operationId.value == attempted!!.mutationId }.envelope == attempted)
    }

    private suspend fun author(n: Int, event: AgentSyncEvent) = history.enqueueOutbound(space, MutationId(id(n)), AgentHlcSnapshot(n.toLong(), 0, replica), event)
    private fun worker(actual: SyncTransport = transport): SyncTransportWorker {
        val engine = SyncEngine(transactions, journal, journal, receive, RoomEventRepository(database), RoomTaskRepository(database),
            RoomPlanningProfileRepository(database), RoomAcademicRepository(database), productionUuidV7Generator(), clock)
        val integration = AgentHistoryReceiveIntegration(history, state, transactions, receive, journal, clock)
        val businessOut = RoomSyncOutboundEnvelopeRepository(database)
        return SyncTransportWorker(journal, businessOut, receive, codec, keys, device, actual, EncryptedSyncReceiveGateway(codec, engine, integration),
            AgentOutboundCompatibilityGate { false }, AgentHistoryOutboundTransport(history, state, journal, businessOut, receive, codec, keys, device, actual,
                AgentEnvelopeUploadLimits(1048576, 1200000)))
    }
    private fun reopen() { database.close(); database = open() }
    suspend fun destroyFixtureSecrets() {
        val references = ring.historicalDecryptKeys(space).map { it.contentKeyReference } +
            listOf(active.deviceCredentialReference, active.accountMasterKeyReference, active.hpkePrivateKeyReference)
        references.distinct().forEach { store.delete(it) }
    }
    fun close() { database.close() }

    companion object {
        fun id(n: Int) = "00000000-0000-7000-8000-${n.toString().padStart(12, '0')}"
        val THREAD = AgentThreadSyncId(id(6000))
        val DESKTOP_TURN = AgentTurnSyncId(id(6002))
        val ANDROID_TURN = AgentTurnSyncId(id(7002))
        const val TITLE = "D90205-PLATFORM-TITLE-CANARY"
        const val DESKTOP_TEXT = "D90205-DESKTOP-MESSAGE-CANARY"
        const val DESKTOP_ASSISTANT_TEXT = "D90205-DESKTOP-ASSISTANT-CANARY"
        const val ANDROID_TEXT = "D90205-ANDROID-MESSAGE-CANARY"
        val KEY = ByteArray(32) { (it + 11).toByte() }
    }
}

/** Test clients trust ONLY this ephemeral acceptance certificate; no permissive trust manager. */
fun acceptanceTrustManager(pem: String): X509TrustManager {
    val certificate = CertificateFactory.getInstance("X.509").generateCertificate(pem.byteInputStream())
    val keyStore = KeyStore.getInstance(KeyStore.getDefaultType()).apply { load(null); setCertificateEntry("acceptance-only", certificate) }
    return TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply { init(keyStore) }.trustManagers.filterIsInstance<X509TrustManager>().single()
}
