package dev.agenticscheduler.database

import androidx.room3.testing.MigrationTestHelper
import androidx.room3.useReaderConnection
import dev.agenticscheduler.application.history.*
import dev.agenticscheduler.application.id.*
import dev.agenticscheduler.application.persistence.*
import dev.agenticscheduler.application.sync.*
import dev.agenticscheduler.database.repository.*
import dev.agenticscheduler.domain.id.EventId
import dev.agenticscheduler.sync.*
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import java.nio.file.Files
import java.nio.file.Path
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import kotlin.test.*

/** Real Room + D7 receive engine + frozen V3 persistence + desktop Tink; transport is an opaque test relay. */
class AgentHistoryTransportIntegrationTest {
    @get:Rule val migrations = MigrationTestHelper(Path.of("schemas"), Files.createTempFile("agent-transport-migration-", ".db"),
        BundledSQLiteDriver(), AgenticSchedulerDatabase::class, { AgenticSchedulerDatabaseConstructor.initialize() })

    @Test fun `held V2 closure does not block independent authenticated V1 or consented V3 and later drains exactly once`() = runBlocking<Unit> {
        val f = Fixture()
        try {
            f.start()
            val held = business(1, 0, agent = true)
            val dependent = business(2, 1)
            f.localBusiness(held); f.localBusiness(dependent)
            val remote = business(900, 0, replica = 901)
            f.relay.remote += RemoteSyncEnvelope(10, f.encryptBusiness(remote))
            f.consent(true)
            f.author(10, ThreadCreated(thread(100), "independent", 0))
            f.turn(30, thread(100))
            val first = f.worker().run(space)
            assertEquals(setOf(id(1), id(2)), first.heldBusinessOperationIds.toSet())
            assertNull(first.stoppedOnFailure)
            assertEquals(1, first.applied)
            assertEquals("remote-900", f.events.get(EventId(id(900 + 10000)))?.title)
            assertEquals(3, first.agentOutbound.uploaded)
            assertFalse(f.relay.attempts.any { it.mutationId == id(1) || it.mutationId == id(2) })
            assertNull(f.businessOut.envelope(space, id(1)))
            assertNull(f.businessOut.envelope(space, id(2)))
            f.gate = true
            val second = f.worker().run(space)
            assertEquals(2, second.uploaded)
            assertTrue(second.heldBusinessOperationIds.isEmpty())
            assertEquals(listOf(id(1), id(2)), f.relay.attempts.map { it.mutationId }.filter { it == id(1) || it == id(2) })
            assertEquals(0, f.worker().run(space).uploaded)
            assertEquals(2, f.relay.attempts.count { it.mutationId == id(1) || it.mutationId == id(2) })
            assertEquals(3L, f.agent.localReplica(space)!!.nextCounter)
            assertFalse(f.receive.handledDots(space).any { it.replicaId.value == agentReplica.value })
        } finally { f.close() }
    }

    @Test fun `dependent finalized turn is wholly held then durably preflighted and retried unchanged across restart`() = runBlocking<Unit> {
        val path = Files.createTempDirectory("agent-transport-restart-").resolve("history.db").toString()
        val keys = Keys()
        val relay = Relay()
        var f = Fixture(path, keys, relay)
        try {
            f.start(); f.consent(true)
            f.localBusiness(business(1, 0, agent = true))
            f.author(10, ThreadCreated(thread(100), null, 0))
            f.turn(30, thread(100), MutationId(id(1)))
            val held = f.worker().run(space)
            assertEquals(setOf(id(30), id(31), id(32)), held.agentOutbound.heldOperationIds.toSet())
            assertEquals(setOf(id(10)), relay.attempts.map { it.mutationId }.toSet())
            assertTrue(f.state.outboundRecords(space).filter { it.operation.operationId.value != id(10) }.all { it.state == AgentOutboundTransportState.HELD && it.envelope == null })
            f.close(); f = Fixture(path, keys, relay); f.start()
            assertEquals(3, f.state.outboundRecords(space).count { it.state == AgentOutboundTransportState.HELD })
            f.gate = true
            relay.failId = id(30)
            val failed = f.worker().run(space)
            assertEquals(1, failed.uploaded)
            assertIs<SyncUploadResult.RetryableFailure>(failed.agentOutbound.failure)
            val ready = f.state.outboundRecords(space).filter { it.operation.operationId.value in setOf(id(30), id(31), id(32)) }
            assertTrue(ready.all { it.state == AgentOutboundTransportState.READY && it.envelope != null })
            val exact = ready.associate { it.operation.operationId.value to it.envelope }
            val encryptions = keys.encryptions
            f.close(); f = Fixture(path, keys, relay); f.start(); f.gate = true; relay.failId = null
            assertEquals(3, f.worker().run(space).agentOutbound.uploaded)
            assertEquals(encryptions, keys.encryptions)
            assertEquals(exact, f.state.outboundRecords(space).filter { it.operation.operationId.value in exact }.associate { it.operation.operationId.value to it.envelope })
            assertEquals(relay.attempts.first { it.mutationId == id(30) }, relay.attempts.last { it.mutationId == id(30) })
            assertTrue(f.state.outboundRecords(space).all { it.state == AgentOutboundTransportState.UPLOADED })
            assertEquals(0, f.worker().run(space).agentOutbound.uploaded)
            f.close(); f = Fixture(path, keys, relay); f.start()
            assertTrue(f.state.outboundRecords(space).all { it.state == AgentOutboundTransportState.UPLOADED })
            assertEquals(0, f.worker().run(space).agentOutbound.uploaded)
        } finally { f.close() }
    }

    @Test fun `consent defaults off is space scoped and blocks even retained ciphertext retries`() = runBlocking<Unit> {
        val f = Fixture()
        try {
            f.start(); f.author(10, ThreadCreated(thread(100), null, 0)); f.turn(30, thread(100))
            assertFalse(f.state.conversationConsent(space))
            assertTrue(f.worker().run(space).agentOutbound.consentOff)
            assertTrue(f.relay.attempts.isEmpty()); assertEquals(0, f.keys.encryptions)
            assertFailsWith<IllegalArgumentException> { f.state.setConversationConsent(space, true, false) }
            f.consent(true); f.relay.failId = id(10)
            assertNotNull(f.worker().run(space).agentOutbound.failure)
            assertNotNull(f.state.outboundRecords(space).first { it.operation.operationId.value == id(10) }.envelope)
            f.consent(false)
            val attempts = f.relay.attempts.size
            f.worker().run(space)
            assertEquals(attempts, f.relay.attempts.size)
            f.state.setConversationConsent(SyncSpaceId("other"), true, true)
            assertFalse(f.state.conversationConsent(space))
        } finally { f.close() }
    }

    @Test fun `unrelated consented turn progresses while earlier finalized turn references held business`() = runBlocking<Unit> {
        val f = Fixture()
        try {
            f.start(); f.consent(true); f.localBusiness(business(1, 0, agent = true))
            f.author(10, ThreadCreated(thread(100), null, 0)); f.turn(30, thread(100), MutationId(id(1)))
            f.author(40, ThreadCreated(thread(200), null, 0)); f.turn(50, thread(200))
            val result = f.worker().run(space)
            assertEquals(setOf(id(30), id(31), id(32)), result.agentOutbound.heldOperationIds.toSet())
            assertEquals(setOf(id(10), id(40), id(50), id(52)), f.relay.attempts.map { it.mutationId }.toSet())
            assertEquals(4, result.agentOutbound.uploaded)
            assertEquals(listOf(id(1)), result.heldBusinessOperationIds)
        } finally { f.close() }
    }

    @Test fun `whole turn preflight rejects reduced relay bounds without publishing a member`() = runBlocking<Unit> {
        val f = Fixture()
        try {
            f.start(); f.consent(true)
            f.author(10, ThreadCreated(thread(100), null, 0))
            assertEquals(1, f.worker().run(space).agentOutbound.uploaded)
            f.turn(30, thread(100), content = "界".repeat(3000))
            val result = f.worker(AgentEnvelopeUploadLimits(1200, 1200000)).run(space)
            assertEquals("AGENT_SYNC_PAYLOAD_TOO_LARGE", assertIs<SyncUploadResult.NonRetryableFailure>(result.agentOutbound.failure).detail)
            assertEquals(listOf(id(10)), f.relay.attempts.map { it.mutationId })
            assertTrue(f.state.outboundRecords(space).filter { it.operation.operationId.value != id(10) }.all { it.envelope == null })
            val bodyLimited = f.worker(AgentEnvelopeUploadLimits(1048576, 1000)).run(space)
            assertEquals("AGENT_SYNC_PAYLOAD_TOO_LARGE", assertIs<SyncUploadResult.NonRetryableFailure>(bodyLimited.agentOutbound.failure).detail)
            assertEquals(listOf(id(10)), f.relay.attempts.map { it.mutationId })
        } finally { f.close() }
    }

    @Test fun `received tombstone suppresses unpublished local erased content without rewriting its causal history`() = runBlocking<Unit> {
        val f = Fixture()
        try {
            f.start(); f.consent(true)
            f.author(10, ThreadCreated(thread(100), "private", 0)); f.turn(30, thread(100))
            val before = f.state.outboundRecords(space).map { it.operation }
            val delete = agentOperation(20, ThreadDeleted(thread(100)))
            f.gateway.receive(SyncWireCodec.encodeEnvelope(f.encryptAgent(delete)), 1)
            assertTrue(f.agent.isThreadTombstoned(space, id(100)))
            val result = f.worker().run(space)
            assertEquals(setOf(id(10), id(30), id(32)), result.agentOutbound.heldOperationIds.toSet())
            assertTrue(f.relay.attempts.isEmpty())
            assertEquals(before, f.state.outboundRecords(space).map { it.operation })
        } finally { f.close() }
    }

    @Test fun `incomplete turn never uploads a member before its manifest`() = runBlocking<Unit> {
        val f = Fixture()
        try {
            f.start(); f.consent(true)
            f.author(10, MessageAppended(AgentMessageSyncId(id(1010)), thread(100), AgentTurnSyncId(id(2030)), AgentMessageRoleV3.USER, "waiting", 0))
            val result = f.worker().run(space)
            assertEquals(listOf(id(10)), result.agentOutbound.heldOperationIds)
            assertTrue(f.relay.attempts.isEmpty())
        } finally { f.close() }
    }

    @Test fun `V3 envelope reuses exact D8 binding AAD epoch and plaintext bound`() = runBlocking<Unit> {
        val f = Fixture()
        try {
            f.start(); f.consent(true)
            val op = f.author(10, ThreadCreated(thread(100), "history", 0))
            assertEquals(1, f.worker().run(space).agentOutbound.uploaded)
            val envelope = f.relay.attempts.single()
            assertEquals(id(10), envelope.mutationId); assertEquals(1, envelope.envelopeVersion); assertEquals(7L, envelope.keyEpoch)
            val decrypted = assertIs<DecryptSyncEnvelopeResult.AuthenticatedPlaintext>(f.codec.decrypt(envelope))
            assertEquals(op, assertIs<AgentPayloadDecodeResult.Supported>(AgentSyncWireCodec.decodePayload(decrypted.payloadJson, op.operationId)).payload.operation)
            assertEquals("agentic-scheduler-sync|v1|personal|${id(10)}|local|7", SyncEnvelopeBinding.from(envelope).authenticatedAssociatedData())
            assertIs<DecryptSyncEnvelopeResult.AuthenticationFailed>(f.codec.decrypt(envelope.copy(senderDeviceId = DeviceId("changed"))))
            assertIs<DecryptSyncEnvelopeResult.AuthenticationFailed>(f.codec.decrypt(envelope.copy(keyEpoch = 8)))
            assertIs<DecryptSyncEnvelopeResult.AuthenticationFailed>(f.codec.decrypt(envelope.copy(mutationId = id(11))))
            f.keys.epoch = 8
            assertIs<EncryptSyncPayloadResult.NonActiveKeyEpoch>(f.codec.encrypt(SyncEnvelopeBinding.from(envelope), SyncPayloadV3(operation = op)))
            val huge = op.copy(agentEvent = MessageAppended(AgentMessageSyncId(id(1010)), thread(100), AgentTurnSyncId(id(2010)), AgentMessageRoleV3.USER, "界".repeat(90000), 0))
            val count = f.keys.encryptions
            assertEquals("AGENT_SYNC_PAYLOAD_TOO_LARGE", assertIs<EncryptSyncPayloadResult.InvalidPayload>(f.codec.encrypt(SyncEnvelopeBinding(space, id(10), DeviceId("local"), 8), SyncPayloadV3(operation = huge))).reason)
            assertEquals(count, f.keys.encryptions)
            assertNull(f.journal.mutation(id(10)))
        } finally { f.close() }
    }

    @Test fun `unknown V3 event still quarantines whole envelope and independent V1 receives`() = runBlocking<Unit> {
        val f = Fixture()
        try {
            f.start(); f.consent(true)
            val payload = AgentSyncWireCodec.encodePayload(SyncPayloadV3(operation = agentOperation(20, ThreadCreated(thread(100), null, 0))))
                .replace("ThreadCreated", "FutureAgentEvent")
            val binding = SyncEnvelopeBinding(space, id(20), DeviceId("remote"), 7)
            val ciphertext = f.keys.key.encryptToBase64Url(payload, binding.authenticatedAssociatedData())
            f.relay.remote += RemoteSyncEnvelope(4, EncryptedEnvelopeV1(syncSpaceId = space, mutationId = id(20), senderDeviceId = DeviceId("remote"), keyEpoch = 7, ciphertextBase64Url = ciphertext))
            f.relay.remote += RemoteSyncEnvelope(5, f.encryptBusiness(business(900, 0, replica = 901)))
            val result = f.worker().run(space)
            assertEquals(2, result.applied)
            assertNotNull(f.receive.quarantine(space, id(20))); assertNull(f.agent.operation(space, id(20)))
            assertEquals(1, f.journal.timeline().size)
            assertNotNull(f.events.get(EventId(id(10900))))
        } finally { f.close() }
    }

    @Test fun `unequal immutable Agent retry retains integrity evidence and independent business receive still progresses`() = runBlocking<Unit> {
        val f = Fixture()
        try {
            f.start(); f.consent(true)
            val original = agentOperation(20, ThreadCreated(thread(100), "original", 0))
            assertIs<EncryptedSyncReceiveResult.AgentHandled>(f.gateway.receive(SyncWireCodec.encodeEnvelope(f.encryptAgent(original)), 1))
            val divergent = original.copy(agentEvent = ThreadCreated(thread(100), "divergent", 0))
            f.relay.remote += RemoteSyncEnvelope(2, f.encryptAgent(divergent))
            f.relay.remote += RemoteSyncEnvelope(3, f.encryptBusiness(business(900, 0, replica = 901)))
            val result = f.worker().run(space)
            assertEquals("AGENT_SYNC_INTEGRITY_FAILURE", result.agentHistoryReceiveFailure)
            assertEquals(2, result.applied); assertNull(result.stoppedOnReceiveFailure)
            assertEquals(original, f.agent.operation(space, id(20)))
            assertEquals(ProtocolQuarantineReason.INVALID_PAYLOAD, f.receive.quarantine(space, id(20))!!.reason)
            assertEquals(3L, f.receive.serverCursor(space))
            assertNotNull(f.events.get(EventId(id(10900))))
            val evidence = f.db.useReaderConnection { connection -> connection.usePrepared("SELECT count(*) FROM agent_sync_conflict WHERE conflict_kind = 'IMMUTABLE_IDENTITY_CONFLICT'") { it.step(); it.getLong(0) } }
            assertEquals(1L, evidence)
        } finally { f.close() }
    }

    @Test fun `consent off quarantine and upgrade backfill keep business facts and Agent cursor independent`() = runBlocking<Unit> {
        val f = Fixture()
        try {
            f.start()
            val agent = agentOperation(20, ThreadCreated(thread(100), "private", 0))
            f.relay.remote += RemoteSyncEnvelope(4, f.encryptAgent(agent))
            val remote = business(900, 0, replica = 901)
            f.relay.remote += RemoteSyncEnvelope(5, f.encryptBusiness(remote))
            assertEquals(2, f.worker().run(space).applied)
            assertNull(f.agent.operation(space, id(20)))
            assertTrue(f.agent.dvvFrontier(space).components.isEmpty())
            assertNotNull(f.receive.quarantine(space, id(20)))
            assertEquals(3L, f.agent.backfillState(space)!!.cursor)
            assertEquals(5L, f.receive.serverCursor(space))
            val before = f.journal.timeline()
            val businessDots = f.receive.handledDots(space)
            f.consent(true)
            assertEquals(AgentSyncBackfillRecoveryState.COMPLETE, f.backfill().run(space))
            assertEquals(agent, f.agent.operation(space, id(20)))
            assertEquals(before, f.journal.timeline())
            assertEquals(businessDots, f.receive.handledDots(space))
            assertEquals(5L, f.receive.serverCursor(space))
            assertEquals(5L, f.agent.backfillState(space)!!.cursor)
            assertTrue(f.state.outboundRecords(space).isEmpty()) // no retrospective export
        } finally { f.close() }
    }

    @Test fun `missing historical quarantine ciphertext reports incomplete even when later ciphertext exists`() = runBlocking<Unit> {
        val f = Fixture()
        try {
            f.start()
            f.receive.quarantine(ProtocolQuarantine(space, id(20), 4, ProtocolQuarantineReason.UNSUPPORTED_PAYLOAD_VERSION, "3"))
            f.receive.saveServerCursor(space, 100)
            f.relay.remote += RemoteSyncEnvelope(5, f.encryptBusiness(business(900, 0, replica = 901)))
            f.consent(true)
            assertEquals(AgentSyncBackfillRecoveryState.INCOMPLETE, f.backfill().run(space))
            assertEquals(3L, f.agent.backfillState(space)!!.cursor)
            assertEquals(100L, f.receive.serverCursor(space))
            assertTrue(f.journal.timeline().isEmpty())
            f.relay.remote += RemoteSyncEnvelope(4, f.encryptAgent(agentOperation(20, ThreadCreated(thread(100), "restored", 0))))
            assertEquals(AgentSyncBackfillRecoveryState.COMPLETE, f.backfill().run(space))
            assertEquals(100L, f.receive.serverCursor(space))
        } finally { f.close() }
    }

    @Test fun `inbound Agent audit never executes business mutation and waits for actual D7 fact before active projection`() = runBlocking<Unit> {
        val f = Fixture()
        try {
            f.start(); f.consent(true)
            val source = Fixture(keys = f.keys)
            val operations = try {
                source.start(); source.author(10, ThreadCreated(thread(100), "history", 0))
                source.turn(30, thread(100), MutationId(id(900)))
                source.state.outboundRecords(space).map { it.operation }
            } finally { source.close() }
            operations.reversed().forEachIndexed { index, op ->
                assertIs<EncryptedSyncReceiveResult.AgentHandled>(f.gateway.receive(SyncWireCodec.encodeEnvelope(f.encryptAgent(op)), index + 1L))
            }
            assertTrue(f.journal.timeline().isEmpty())
            assertNull(f.events.get(EventId(id(10900))))
            assertFalse(f.agent.isTurnActive(space, id(2030)))
            assertTrue(f.agent.pendingDependencies(space, id(31)).any { it.kind == AgentSyncDependencyKind.BUSINESS_MUTATION })
            val remote = business(900, 0, replica = 901)
            assertIs<EncryptedSyncReceiveResult.Handled>(f.gateway.receive(SyncWireCodec.encodeEnvelope(f.encryptBusiness(remote)), 10))
            assertTrue(f.agent.isTurnActive(space, id(2030)))
            assertEquals(1, f.journal.timeline().size)
            assertEquals("remote-900", f.events.get(EventId(id(10900)))?.title)
            operations.forEach { op -> f.gateway.receive(SyncWireCodec.encodeEnvelope(f.encryptAgent(op)), 11) }
            assertEquals(1, f.journal.timeline().size)
            assertTrue(f.agent.pendingDependencies(space, id(31)).isEmpty())
            assertTrue(f.agent.threadHistoryProjection(space, thread(100)).turns.isNotEmpty())
        } finally { f.close() }
    }

    @Test fun `in-flight backfill cannot overwrite an earlier quarantine restart generation`() = runBlocking<Unit> {
        val f = Fixture()
        try {
            f.start(); f.consent(true)
            f.receive.quarantine(ProtocolQuarantine(space, id(20), 20, ProtocolQuarantineReason.UNSUPPORTED_PAYLOAD_VERSION, "3"))
            f.agent.advanceBackfill(space, AgentSyncBackfillState(10, AgentSyncBackfillRecoveryState.RUNNING, 20, 1))
            f.relay.remote += RemoteSyncEnvelope(4, f.encryptAgent(agentOperation(40, ThreadCreated(thread(200), "earlier", 0), AgentReplicaId(id(505)))))
            f.relay.remote += RemoteSyncEnvelope(20, f.encryptAgent(agentOperation(20, ThreadCreated(thread(100), "later", 0))))
            var introduced = false
            f.relay.beforeFetch = { after ->
                if (!introduced && after == 10L) {
                    introduced = true
                    f.receive.quarantine(ProtocolQuarantine(space, id(40), 4, ProtocolQuarantineReason.UNSUPPORTED_PAYLOAD_VERSION, "3"))
                    f.integration.rememberQuarantine(space, 4)
                }
            }
            assertEquals(AgentSyncBackfillRecoveryState.COMPLETE, f.backfill().run(space))
            assertNotNull(f.agent.operation(space, id(40))); assertNotNull(f.agent.operation(space, id(20)))
            assertEquals(4L, f.agent.backfillState(space)!!.earliestQuarantinedCursor)
            assertEquals(20L, f.agent.backfillState(space)!!.cursor)
            assertEquals(0L, f.receive.serverCursor(space))
            assertTrue(f.journal.timeline().isEmpty())
        } finally { f.close() }
    }

    @Test fun `invalid causal resolution stays unhandled without blocking an independent Agent replica`() = runBlocking<Unit> {
        val f = Fixture()
        try {
            f.start(); f.consent(true)
            val badReplica = AgentReplicaId(id(503))
            val validReplica = AgentReplicaId(id(504))
            val invalid = agentOperation(20, ThreadDeleteConflictResolved(thread(100), listOf(MutationId(id(900))), AgentThreadDeleteResolution.KEEP_DELETION, null), badReplica)
            val independent = agentOperation(21, ThreadCreated(thread(200), "independent", 0), validReplica)
            f.relay.remote += RemoteSyncEnvelope(1, f.encryptAgent(invalid))
            f.relay.remote += RemoteSyncEnvelope(2, f.encryptAgent(independent))
            val result = f.worker().run(space)
            assertEquals("AGENT_SYNC_INTEGRITY_FAILURE", result.agentHistoryReceiveFailure)
            assertEquals(2, result.applied)
            assertEquals(2L, f.receive.serverCursor(space))
            assertNull(f.agent.dvvFrontier(space).components[badReplica])
            assertEquals(0L, f.agent.dvvFrontier(space).components[validReplica])
            assertTrue(f.state.pendingInboundOperations(space).any { it.operationId == invalid.operationId })
            assertTrue(f.journal.timeline().isEmpty())
        } finally { f.close() }
    }

    @Test fun `outbound failure and successful inbound remain separately reported`() = runBlocking<Unit> {
        val f = Fixture()
        try {
            f.start(); f.localBusiness(business(1, 0)); f.relay.failId = id(1)
            f.relay.remote += RemoteSyncEnvelope(5, f.encryptBusiness(business(900, 0, replica = 901)))
            val result = f.worker().run(space)
            assertIs<SyncUploadResult.RetryableFailure>(result.stoppedOnFailure)
            assertEquals(1, result.applied); assertNull(result.stoppedOnFetchFailure)
            assertEquals(5L, f.receive.serverCursor(space))
        } finally { f.close() }
    }

    @Test fun `manifest cannot activate a foreign thread member even without an Action`() = runBlocking<Unit> {
        val f = Fixture()
        try {
            f.start(); f.consent(true)
            val turn = AgentTurnSyncId(id(2030)); val message = AgentMessageSyncId(id(1030))
            val msgOp = agentOperation(20, MessageAppended(message, thread(200), turn, AgentMessageRoleV3.USER, "foreign", 0))
            val manifestReplica = AgentReplicaId(id(502))
            val manifest = agentOperation(21, TurnFinalized(turn, thread(100), emptyList(), listOf(MessageMember(message)), AgentTurnOutcome.SUCCEEDED), manifestReplica)
            f.gateway.receive(SyncWireCodec.encodeEnvelope(f.encryptAgent(msgOp)), 1)
            f.gateway.receive(SyncWireCodec.encodeEnvelope(f.encryptAgent(manifest)), 2)
            assertFalse(f.agent.isTurnActive(space, turn.value))
            assertEquals(AgentSyncTurnState.INCOMPLETE, f.agent.turnState(space, turn.value))
            assertTrue(f.agent.threadHistoryProjection(space, thread(100)).turns.isEmpty())
        } finally { f.close() }
    }

    @Test fun `a later acknowledged dot cannot bypass a held earlier local dependency`() = runBlocking<Unit> {
        val f = Fixture()
        try {
            f.start()
            val held = business(1, 0, agent = true); val legacy = business(2, 1); val successor = business(3, 2)
            f.localBusiness(held); f.localBusiness(legacy); f.localBusiness(successor)
            f.businessOut.save(StoredOutboundEnvelope(space, id(2), f.encryptBusiness(legacy), true))
            val result = f.worker().run(space)
            assertEquals(setOf(id(1), id(3)), result.heldBusinessOperationIds.toSet())
            assertTrue(f.relay.attempts.isEmpty())
            assertNull(f.businessOut.envelope(space, id(3)))
        } finally { f.close() }
    }

    @Test fun `fetch failure does not overwrite completed outbound and simultaneous failures remain distinct`() = runBlocking<Unit> {
        val f = Fixture()
        try {
            f.start(); f.localBusiness(business(1, 0)); f.relay.fetchFailure = true
            val success = f.worker().run(space)
            assertEquals(1, success.uploaded); assertNull(success.stoppedOnFailure)
            assertIs<SyncUploadResult.RetryableFailure>(success.stoppedOnFetchFailure)
            f.localBusiness(business(2, 1)); f.relay.failId = id(2)
            val both = f.worker().run(space)
            assertIs<SyncUploadResult.RetryableFailure>(both.stoppedOnFailure)
            assertIs<SyncUploadResult.RetryableFailure>(both.stoppedOnFetchFailure)
            assertTrue(f.businessOut.envelope(space, id(1))!!.uploaded)
            assertFalse(f.businessOut.envelope(space, id(2))!!.uploaded)
        } finally { f.close() }
    }

    @Test fun `v13 to v14 preserves existing immutable Agent records and fresh transport schema parity`() = runBlocking<Unit> {
        val old = migrations.createDatabase(13)
        AgentSchema.create(old); AgentSyncSchema.create(old)
        old.prepare("INSERT INTO agent_sync_space_state VALUES ('personal', '${agentReplica.value}', 1, 1)").use { it.step() }
        val op = agentOperation(20, ThreadCreated(thread(100), "existing", 0), replica = agentReplica)
        old.prepare("INSERT INTO agent_sync_operation_identity VALUES (?, ?, ?, ?, ?, ?, ?, ?)").use {
            it.bindText(1, space.value); it.bindText(2, id(20)); it.bindText(3, agentReplica.value); it.bindLong(4, 0)
            it.bindText(5, "ThreadCreated"); it.bindText(6, "THREAD"); it.bindText(7, id(100)); it.bindText(8, AgentSyncWireCodec.encodePayload(SyncPayloadV3(operation = op))); it.step()
        }
        old.prepare("INSERT INTO agent_sync_outbox VALUES ('personal', '${id(20)}', 'HELD')").use { it.step() }
        old.close()
        val upgraded = migrations.runMigrationsAndValidate(14, listOf(AgentSyncTransportMigration13To14))
        try {
            AgentSyncSchema.validate(upgraded); AgentSyncTransportSchema.validate(upgraded)
            upgraded.prepare("SELECT state FROM agent_sync_outbox").use { assertTrue(it.step()); assertEquals("HELD", it.getText(0)) }
            upgraded.prepare("SELECT count(*) FROM agent_sync_transport_consent").use { it.step(); assertEquals(0L, it.getLong(0)) }
            val fresh = BundledSQLiteDriver().open(":memory:")
            try {
                AgentSyncTransportSchema.create(fresh)
                fun schema(connection: androidx.sqlite.SQLiteConnection): List<String> = connection.prepare("SELECT sql FROM sqlite_master WHERE name IN ('agent_sync_transport_consent','agent_sync_outbound_envelope') ORDER BY name").use { stmt -> buildList { while (stmt.step()) add(stmt.getText(0)) } }
                assertEquals(schema(fresh), schema(upgraded))
            } finally { fresh.close() }
        } finally { upgraded.close() }
    }

    private class Fixture(path: String? = null, val keys: Keys = Keys(), val relay: Relay = Relay()) {
        val db = if (path == null) openInMemoryDesktopDatabase() else openDesktopDatabase(path)
        val transactions = RoomApplicationTransactionRunner(db)
        val journal = RoomMutationJournalRepository(db)
        val receive = RoomSyncReceiveRepository(db)
        val businessOut = RoomSyncOutboundEnvelopeRepository(db)
        val events = RoomEventRepository(db)
        val agent = RoomAgentSyncPersistence(db)
        val state = RoomAgentSyncTransportPersistence(db)
        val ids = RfcUuidV7Generator(EpochMillisecondsClock { 1 }, RandomBytes { ByteArray(it) { 1 } })
        val codec = AuthenticatedSyncEnvelopeCodec(keys, keys)
        val clock = MutationWallClock { 1 }
        val engine = SyncEngine(transactions, journal, journal, receive, events, RoomTaskRepository(db), RoomPlanningProfileRepository(db), RoomAcademicRepository(db), ids, clock)
        val integration = AgentHistoryReceiveIntegration(agent, state, transactions, receive, journal, clock)
        val gateway = EncryptedSyncReceiveGateway(codec, engine, integration)
        var gate = false
        suspend fun start() { agent.provisionLocalReplica(space, agentReplica) }
        suspend fun consent(value: Boolean) { state.setConversationConsent(space, value, value) }
        suspend fun author(n: Int, event: AgentSyncEvent) = agent.enqueueOutbound(space, MutationId(id(n)), AgentHlcSnapshot(n.toLong(), 0, agentReplica), event)
        suspend fun localBusiness(op: SyncOperation) = transactions.inWriteTransaction { journal.appendCommittedMutation(CommittedMutation(op, op.dvv.dot.counter)) }
        suspend fun turn(n: Int, thread: AgentThreadSyncId, dependency: MutationId? = null, content: String = "text") {
            val turn = AgentTurnSyncId(id(n + 2000)); val message = AgentMessageSyncId(id(n + 1000))
            author(n, MessageAppended(message, thread, turn, AgentMessageRoleV3.USER, content, 0))
            val members = mutableListOf<TurnMemberReference>(MessageMember(message))
            if (dependency != null) {
                val action = AgentActionSyncId(id(n + 1001))
                author(n + 1, ActionFinalized(action, turn, thread, message, emptyList(), emptyList(), listOf(dependency), FinalAgentActionStatus.SUCCEEDED))
                members += ActionMember(action)
            }
            author(n + 2, TurnFinalized(turn, thread, emptyList(), members, AgentTurnOutcome.SUCCEEDED))
        }
        suspend fun encryptBusiness(op: SyncOperation): EncryptedEnvelopeV1 {
            val binding = SyncEnvelopeBinding(space, op.mutationId, DeviceId("remote"), keys.epoch)
            val result = if (op.origin is MutationOrigin.Agent) codec.encrypt(binding, SyncPayloadV2(operation = op)) else codec.encrypt(binding, SyncPayloadV1(operation = op))
            return assertIs<EncryptSyncPayloadResult.Encrypted>(result).envelope
        }
        suspend fun encryptAgent(op: AgentSyncOperation) = assertIs<EncryptSyncPayloadResult.Encrypted>(codec.encrypt(SyncEnvelopeBinding(space, op.operationId.value, DeviceId("remote"), keys.epoch), SyncPayloadV3(operation = op))).envelope
        fun backfill() = AgentHistoryBackfillTransport(agent, state, relay, codec, integration, engine, clock)
        fun worker(limits: AgentEnvelopeUploadLimits = AgentEnvelopeUploadLimits(1048576, 1200000)) = SyncTransportWorker(journal, businessOut, receive, codec, keys, DeviceId("local"), relay, gateway,
            AgentOutboundCompatibilityGate { gate }, AgentHistoryOutboundTransport(agent, state, journal, businessOut, receive, codec, keys, DeviceId("local"), relay, limits))
        fun close() = db.close()
    }

    private class Keys : CurrentEncryptionKeyProvider, SyncPayloadKeyProvider {
        val key = TinkSyncPayloadAead.generate()
        var epoch = 7L
        var encryptions = 0
        private val counting = object : SyncPayloadAead {
            override fun encryptToBase64Url(plaintextUtf8: String, associatedDataUtf8: String): String { encryptions++; return key.encryptToBase64Url(plaintextUtf8, associatedDataUtf8) }
            override fun decryptFromBase64Url(ciphertextBase64Url: String, associatedDataUtf8: String) = key.decryptFromBase64Url(ciphertextBase64Url, associatedDataUtf8)
        }
        override suspend fun currentEncryptionKey(syncSpaceId: SyncSpaceId) = CurrentEncryptionKeyLookup.Available(epoch, counting)
        override suspend fun keyFor(syncSpaceId: SyncSpaceId, keyEpoch: Long) = SyncPayloadKeyLookup.Available(counting)
    }

    private class Relay : SyncTransport {
        val attempts = mutableListOf<EncryptedEnvelopeV1>()
        val remote = mutableListOf<RemoteSyncEnvelope>()
        var failId: String? = null
        var fetchFailure = false
        var beforeFetch: (suspend (Long) -> Unit)? = null
        override suspend fun upload(envelope: EncryptedEnvelopeV1): SyncUploadResult { attempts += envelope; return if (envelope.mutationId == failId) SyncUploadResult.RetryableFailure("offline") else SyncUploadResult.Stored(attempts.size.toLong()) }
        override suspend fun fetch(syncSpaceId: SyncSpaceId, afterCursor: Long, limit: Int): List<RemoteSyncEnvelope> {
            if (fetchFailure) throw SyncTransportException("HTTP_503")
            beforeFetch?.invoke(afterCursor)
            return remote.filter { it.serverCursor > afterCursor }.sortedBy { it.serverCursor }.take(limit)
        }
    }

    companion object {
        private val space = SyncSpaceId("personal")
        private val agentReplica = AgentReplicaId(id(500))
        private fun id(n: Int) = "00000000-0000-7000-8000-${n.toString().padStart(12, '0')}"
        private fun thread(n: Int) = AgentThreadSyncId(id(n))
        private fun business(n: Int, counter: Long, agent: Boolean = false, replica: Int = 200) = SyncOperation(id(n),
            DvvSnapshot(if (counter == 0L) emptyList() else listOf(VersionComponent(id(replica), counter - 1)), DotSnapshot(id(replica), counter)), HlcSnapshot(counter, 0, id(replica)),
            if (agent) MutationOrigin.Agent(id(999)) else MutationOrigin.User,
            listOf(EventPut(null, EventImage(id(n + 10000), "remote-$n", EventTimeImage.AllDay(AllDayRangeImage("2026-01-01", "2026-01-02")), FlexibilityImage.HARD, PinStateImage.UNPINNED))))
        private fun agentOperation(n: Int, event: AgentSyncEvent, replica: AgentReplicaId = AgentReplicaId(id(501))) = AgentSyncOperation(MutationId(id(n)), AgentDvvSnapshot(emptyList(), AgentDot(replica, 0)), AgentHlcSnapshot(0, 0, replica), event)
    }
}
