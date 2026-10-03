package dev.agenticscheduler.application.sync

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import androidx.room3.useReaderConnection
import dev.agenticscheduler.application.history.MutationWallClock
import dev.agenticscheduler.application.history.SyncEngine
import dev.agenticscheduler.application.id.EpochMillisecondsClock
import dev.agenticscheduler.application.id.productionUuidV7Generator
import dev.agenticscheduler.application.persistence.CommittedMutation
import dev.agenticscheduler.database.openDesktopDatabase
import dev.agenticscheduler.database.repository.*
import dev.agenticscheduler.agent.history.*
import dev.agenticscheduler.server.sync.*
import dev.agenticscheduler.sync.*
import io.ktor.client.HttpClient
import io.ktor.client.request.*
import io.ktor.client.statement.bodyAsText
import io.ktor.http.*
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.*
import org.junit.Assume.assumeTrue
import java.io.File
import java.util.UUID
import kotlin.test.*

/** New enrolled-server evidence. No memory relay and no plaintext server Agent state. */
class D9AgentHistoryPostgresE2ETest {
    @Test fun `old client quarantines V3 then applies V1 and upgraded backfill is exactly once`() = scenario {
        a.consent(true)
        a.author(1, ThreadCreated(thread(100), TITLE, 1))
        a.turn(10, thread(100), MESSAGE)
        assertEquals(3, a.worker().run(space).agentOutbound.uploaded)
        a.business(business(50))
        assertEquals(1, a.worker().run(space).uploaded)
        val old = b.worker(v3 = false).run(space)
        assertNull(old.stoppedOnReceiveFailure)
        assertNotNull(b.events.get(dev.agenticscheduler.domain.id.EventId(id(10050))))
        assertEquals(emptyMap(), b.agent.dvvFrontier(space).components)
        val beforeBusiness = b.journal.timeline()
        val cursor = b.receive.serverCursor(space)
        assertEquals(1L, b.state.earliestUnrecoveredV3Quarantine(space))
        b.reopen()
        b.consent(true)
        assertEquals(AgentSyncBackfillRecoveryState.COMPLETE, b.backfill().run(space))
        assertEquals(cursor, b.receive.serverCursor(space))
        assertEquals(beforeBusiness, b.journal.timeline())
        assertEquals(1, b.agent.threadHistoryProjection(space, thread(100)).turns.size)
        assertEquals(AgentSyncBackfillRecoveryState.COMPLETE, b.backfill().run(space))
        assertEquals(beforeBusiness, b.journal.timeline())
        assertEquals(1, b.agent.threadHistoryProjection(space, thread(100)).turns.size)
        assertTrue(b.receive.handledDots(space).none { it.replicaId.value == a.replica.value })
        assertOpaque()
    }

    @Test fun `consent controls are explicit enrolled and do not export local legacy history`() = scenario {
        a.seedLocalOnlyCanaries()
        val legacy = a.local.messages(AgentThreadId(id(5000)))
        val controls = AgentConversationSyncSettings(a.enrollment, a.state, a.state, a.agent)
        assertFalse(controls.settings().single().consent)
        assertFailsWith<IllegalArgumentException> { controls.setFromUser(space, true, false) }
        assertFalse(a.state.conversationConsent(space))
        controls.setFromUser(space, true, true)
        assertTrue(controls.settings().single().consent)
        assertEquals(emptyList(), a.state.outboundRecords(space))
        assertEquals(legacy, a.local.messages(AgentThreadId(id(5000))))
        controls.setFromUser(space, false, false)
        assertFailsWith<IllegalStateException> { controls.setFromUser(SyncSpaceId("unenrolled"), true, true) }
        a.reopen()
        assertFalse(a.state.conversationConsent(space))
        a.author(1, ThreadCreated(thread(100), TITLE, 1))
        assertEquals(0, a.worker().run(space).agentOutbound.uploaded)
        assertTrue(a.worker().run(space).agentOutbound.consentOff)
        assertEquals(emptyList(), a.transport.fetch(space, 0, 100))
        assertEquals(legacy, a.local.messages(AgentThreadId(id(5000))))
    }

    @Test fun `explicitly exported tracked history survives client restart and converges through PostgreSQL relay`() = scenario {
        a.consent(true); b.consent(true)
        val threadId = AgentThreadId(id(1500))
        val turnId = id(1502)
        a.local.saveThread(AgentThread(threadId, TITLE, 1))
        a.local.beginLocalHistoryTurn(threadId, turnId)
        a.local.appendMessage(AgentMessage(AgentMessageId(id(1501)), threadId, 0, AgentMessageRole.USER, MESSAGE, 2))
        a.local.appendMessage(AgentMessage(AgentMessageId(id(1503)), threadId, 1, AgentMessageRole.ASSISTANT, EXPORTED_ASSISTANT, 3))
        assertTrue(a.local.finalizeLocalHistoryTurn(threadId, turnId, AgentLocalTurnOutcome.SUCCEEDED))
        val exporter = AgentHistoryExplicitExport(a.enrollment, a.state, a.agent, a.agent,
            RoomAgentHistoryExportSource(a.db, a.local), productionUuidV7Generator(), EpochMillisecondsClock { 10 })
        assertEquals(1, exporter.availability(space).eligibleTurns)
        assertEquals(4, exporter.exportFromUser(space).newlyQueuedFacts)
        val prepared = a.agent.historicalExportMappings(space)
        assertEquals(4, prepared.size)
        a.reopen()
        assertEquals(4, a.worker().run(space).agentOutbound.uploaded)
        b.worker().run(space)
        assertEquals(1, b.agent.threadHistoryProjection(space, AgentThreadSyncId(threadId.value)).turns.size)
        assertEquals(2, b.agent.threadHistoryProjection(space, AgentThreadSyncId(threadId.value)).turns.single().members.size)
        val idsBeforeRepeat = a.agent.historicalExportMappings(space).map { it.operationId }
        val retryExporter = AgentHistoryExplicitExport(a.enrollment, a.state, a.agent, a.agent,
            RoomAgentHistoryExportSource(a.db, a.local), productionUuidV7Generator(), EpochMillisecondsClock { 99 })
        assertEquals(4, retryExporter.exportFromUser(space).previouslyQueuedFacts)
        assertEquals(idsBeforeRepeat, a.agent.historicalExportMappings(space).map { it.operationId })
        assertEquals(0, a.worker().run(space).agentOutbound.uploaded)
        assertEquals(1, b.agent.threadHistoryProjection(space, AgentThreadSyncId(threadId.value)).turns.size)
        assertOpaque()
    }

    @Test fun `shuffled sealed Tool turn stays invisible across restart and duplicates until complete`() = scenario {
        a.consent(true); b.consent(true)
        a.seedLocalOnlyCanaries()
        val t = thread(100); val turn = AgentTurnSyncId(id(2010)); val message = AgentMessageSyncId(id(1010))
        val call = AgentToolCallSyncId(id(1011)); val result = AgentToolResultSyncId(id(1012)); val action = AgentActionSyncId(id(1013))
        a.author(1, ThreadCreated(t, TITLE, 1))
        a.author(10, MessageAppended(message, t, turn, AgentMessageRoleV3.ASSISTANT, MESSAGE, 1))
        a.author(11, ToolCallFinalized(call, t, turn, message, "history.timeline", buildJsonObject { put("input", INPUT) }, FinalAgentToolCallStatus.COMPLETED))
        a.author(12, ToolResultAppended(result, call, t, turn, AgentToolResultStatusV3.SUCCESS, JsonPrimitive(RESULT), emptyList()))
        a.author(13, ActionFinalized(action, turn, t, message, listOf(call), listOf(result), emptyList(), FinalAgentActionStatus.SUCCEEDED))
        a.author(14, TurnFinalized(turn, t, emptyList(), listOf(MessageMember(message), ToolCallMember(call), ToolResultMember(result), ActionMember(action)), AgentTurnOutcome.SUCCEEDED))
        a.publish(1)
        for (index in listOf(14, 12, 10, 13)) {
            a.publish(index)
            b.worker().run(space)
            assertFalse(b.agent.isTurnActive(space, turn.value))
            assertEquals(emptyList(), b.agent.threadHistoryProjection(space, t).turns)
            assertEquals(AgentHistoryContinuationRead.Blocked(AgentHistoryReadBlock.INCOMPLETE), b.reader.read(space, t))
            b.reopen()
        }
        a.publish(11)
        b.worker().run(space)
        assertTrue(b.agent.isTurnActive(space, turn.value))
        assertEquals(4, b.agent.threadHistoryProjection(space, t).turns.single().members.size)
        assertIs<AgentHistoryContinuationRead.Ready>(b.reader.read(space, t))
        a.publish(11)
        b.reopen(); b.worker().run(space)
        assertEquals(1, b.agent.threadHistoryProjection(space, t).turns.size)
        assertEquals(emptyList(), b.journal.timeline())
        assertOpaque()
    }

    @Test fun `concurrent root and same-parent siblings retain both branches without semantic winner`() = scenario {
        a.consent(true); b.consent(true)
        for ((offset, useParent) in listOf(100 to false, 200 to true)) {
            val t = thread(offset)
            a.author(offset + 1, ThreadCreated(t, TITLE, 1))
            a.worker().run(space); b.worker().run(space)
            val parents = if (useParent) {
                a.turn(offset + 10, t, "parent")
                a.worker().run(space); b.worker().run(space)
                listOf(AgentTurnSyncId(id(offset + 2010)))
            } else emptyList()
            a.turn(offset + 20, t, "left", parents)
            b.turn(offset + 30, t, "right", parents)
            a.worker().run(space); b.worker().run(space); a.worker().run(space)
            for (client in listOf(a, b)) {
                val projection = client.agent.threadHistoryProjection(space, t)
                assertEquals(if (useParent) 3 else 2, projection.turns.size)
                assertTrue(projection.conflicts.any { it.kind == AgentSemanticConflictKind.CONCURRENT_TURN_FORK && it.state == AgentSemanticConflictState.OPEN })
                assertFalse(projection.providerContinuationAllowed)
                assertEquals(listOf("D9_02_03_CONCURRENT_TURN_FORK_OPEN"), client.durableConflictKinds(t))
                assertEquals(AgentHistoryContinuationRead.Blocked(AgentHistoryReadBlock.SEMANTIC_CONFLICT), client.reader.read(space, t))
                client.reopen()
                assertEquals(projection, client.agent.threadHistoryProjection(space, t))
                assertEquals(listOf("D9_02_03_CONCURRENT_TURN_FORK_OPEN"), client.durableConflictKinds(t))
            }
        }
    }

    @Test fun `ambiguous successful relay upload retries the exact durable ciphertext after restart`() = scenario {
        a.consent(true); b.consent(true)
        a.author(1, ThreadCreated(thread(100), TITLE, 1)); a.turn(10, thread(100), MESSAGE)
        val route = a.transport
        val attempts = mutableListOf<EncryptedEnvelopeV1>()
        a.transportOverride = object : SyncTransport by route {
            override suspend fun upload(envelope: EncryptedEnvelopeV1): SyncUploadResult {
                attempts += envelope
                assertIs<SyncUploadResult.Stored>(route.upload(envelope))
                return SyncUploadResult.RetryableFailure("ACK_LOST_AFTER_SERVER_COMMIT")
            }
        }
        assertIs<SyncUploadResult.RetryableFailure>(a.worker().run(space).agentOutbound.failure)
        val retained = a.state.outboundRecords(space).single { it.operation.operationId.value == attempts.single().mutationId }.envelope
        a.reopen(); a.transportOverride = null
        assertEquals(3, a.worker().run(space).agentOutbound.uploaded)
        assertEquals(attempts.single(), retained)
        assertEquals(retained, a.transport.fetch(space, 0, 100).first().envelope)
        b.worker().run(space)
        assertEquals(1, b.agent.threadHistoryProjection(space, thread(100)).turns.size)
        assertEquals(a.agent.dvvFrontier(space), b.agent.dvvFrontier(space))
        assertEquals(emptyList(), b.receive.handledDots(space))
        assertEquals(0, a.worker().run(space).agentOutbound.uploaded)
    }

    @Test fun `historical epoch remains decryptable after rotation without replaying business`() = scenario {
        a.consent(true)
        a.author(1, ThreadCreated(thread(100), TITLE, 1)); a.turn(10, thread(100), MESSAGE)
        a.worker().run(space); b.worker(v3 = false).run(space)
        a.install(8, KEY8); b.install(8, KEY8); b.reopen(); b.consent(true)
        assertEquals(AgentSyncBackfillRecoveryState.COMPLETE, b.backfill().run(space))
        assertEquals(8L, b.ring.state(space)!!.activeEncryptionEpoch)
        assertEquals(1, b.agent.threadHistoryProjection(space, thread(100)).turns.size)
        assertEquals(emptyList(), b.journal.timeline())
        assertOpaque()
    }

    @Test fun `missing historical key is durably incomplete and never advances business cursor`() = scenario {
        a.consent(true); a.author(1, ThreadCreated(thread(100), TITLE, 1)); a.worker().run(space)
        b.worker(v3 = false).run(space)
        val forward = b.receive.serverCursor(space)
        b.store.delete(b.keyReferences.getValue(7))
        b.references.remove(b.keyReferences.getValue(7)) // Linux secret-tool clear does not accept double deletion.
        b.install(8, KEY8); b.consent(true)
        assertEquals(AgentSyncBackfillRecoveryState.INCOMPLETE, b.backfill().run(space))
        b.reopen()
        assertEquals(AgentSyncBackfillRecoveryState.INCOMPLETE, b.agent.backfillState(space)!!.recoveryState)
        assertEquals(forward, b.receive.serverCursor(space))
        assertEquals(emptyList(), b.journal.timeline())
        assertEquals(emptyMap(), b.agent.dvvFrontier(space).components)
    }

    @Test fun `missing retained ciphertext and earlier retention gap remain incomplete`() = scenario {
        a.consent(true); a.author(1, ThreadCreated(thread(100), TITLE, 1)); a.worker().run(space)
        b.worker(v3 = false).run(space)
        source.connection.use { connection -> connection.prepareStatement("DELETE FROM encrypted_operation_envelope WHERE sync_space_id = ? AND mutation_id = ?").use {
            it.setString(1, space.value); it.setString(2, id(1)); assertEquals(1, it.executeUpdate())
        } }
        b.consent(true)
        assertEquals(AgentSyncBackfillRecoveryState.INCOMPLETE, b.backfill().run(space))
        a.business(business(50)); a.worker().run(space)
        assertEquals(AgentSyncBackfillRecoveryState.INCOMPLETE, b.backfill().run(space))
        b.reopen()
        assertEquals(0L, b.agent.backfillState(space)!!.cursor)
        assertEquals(1L, b.receive.serverCursor(space))
        assertEquals(emptyList(), b.journal.timeline())
    }

    @Test fun `real relay holds V2 closure and dependent whole turn while unrelated traffic progresses`() = scenario {
        a.consent(true); b.consent(true)
        a.business(business(50, origin = MutationOrigin.Agent(id(5050))))
        a.business(business(51, counter = 1))
        b.business(business(60, replica = 951)); b.worker().run(space)
        a.author(1, ThreadCreated(thread(100), TITLE, 1))
        a.turn(10, thread(100), "independent")
        a.turn(20, thread(100), "dependent", dependency = MutationId(id(50)))
        val first = a.worker().run(space)
        assertEquals(setOf(id(50), id(51)), first.heldBusinessOperationIds.toSet())
        assertNull(first.stoppedOnFailure)
        assertEquals(3, first.agentOutbound.uploaded)
        assertEquals(setOf(id(20), id(21), id(22)), first.agentOutbound.heldOperationIds.toSet())
        assertNotNull(a.events.get(dev.agenticscheduler.domain.id.EventId(id(10060))))
        assertTrue(a.transport.fetch(space, 0, 100).none { it.envelope.mutationId in setOf(id(20), id(21), id(22), id(50), id(51)) })
        a.reopen(); a.v2Gate = true
        val released = a.worker().run(space)
        assertEquals(2, released.uploaded); assertEquals(3, released.agentOutbound.uploaded)
        assertEquals(emptyList(), released.heldBusinessOperationIds)
        assertEquals(emptyList(), released.agentOutbound.heldOperationIds)
        b.worker().run(space)
        assertTrue(b.agent.isTurnActive(space, id(2020)))
        assertEquals(3, b.journal.timeline().size) // own fact plus exactly two received business facts
        assertEquals(0, a.worker().run(space).uploaded)
        assertEquals(0, a.worker().run(space).agentOutbound.uploaded)
        assertOpaque()
    }

    @Test fun `delete append and concurrent explicit resolutions converge without resurrecting source`() = scenario {
        a.consent(true); b.consent(true)
        val t = thread(100)
        a.author(1, ThreadCreated(t, TITLE, 1)); a.worker().run(space); b.worker().run(space)
        a.author(2, ThreadDeleted(t))
        b.turn(10, t, MESSAGE)
        a.worker().run(space); b.worker().run(space); a.worker().run(space)
        val component = a.agent.threadHistoryProjection(space, t).conflicts.single { it.kind == AgentSemanticConflictKind.THREAD_DELETE_APPEND }
        assertEquals(component, b.agent.threadHistoryProjection(space, t).conflicts.single { it.kind == AgentSemanticConflictKind.THREAD_DELETE_APPEND })
        assertTrue(component.participantOperationIds.size >= 3)
        a.resolve(30, t, component.participantOperationIds)
        val replacement = thread(300)
        val copiedTurn = AgentTurnSyncId(id(301)); val copiedMessage = AgentMessageSyncId(id(302))
        b.resolve(40, t, component.participantOperationIds, replacement, listOf(
            b.draft(41, ThreadCreated(replacement, "selected copy", 1)),
            b.draft(42, MessageAppended(copiedMessage, replacement, copiedTurn, AgentMessageRoleV3.USER, MESSAGE, 1)),
            b.draft(43, TurnFinalized(copiedTurn, replacement, emptyList(), listOf(MessageMember(copiedMessage)), AgentTurnOutcome.SUCCEEDED)),
        ))
        a.worker().run(space); b.worker().run(space); a.worker().run(space)
        for (replica in listOf(a, b)) {
            val p = replica.agent.threadHistoryProjection(space, t)
            assertTrue(p.tombstoned); assertTrue(p.turns.isEmpty())
            assertTrue(p.conflicts.any { it.kind == AgentSemanticConflictKind.DELETE_RESOLUTION && it.state == AgentSemanticConflictState.OPEN })
            assertEquals(1, replica.agent.threadHistoryProjection(space, replacement).turns.size)
            assertEquals(emptyList(), replica.journal.timeline())
            assertEquals(emptyList(), replica.local.toolCalls(AgentThreadId(replacement.value)))
            replica.reopen()
            assertEquals(p, replica.agent.threadHistoryProjection(space, t))
        }
        val expanded = (component.participantOperationIds + listOf(MutationId(id(30)), MutationId(id(40)))).sortedBy { it.value }
        a.resolve(50, t, expanded)
        a.worker().run(space); b.worker().run(space); a.worker().run(space)
        assertEquals(a.agent.threadHistoryProjection(space, t), b.agent.threadHistoryProjection(space, t))
        assertTrue(a.agent.threadHistoryProjection(space, t).conflicts.none { it.state == AgentSemanticConflictState.OPEN })
        assertEquals(AgentHistoryContinuationRead.Blocked(AgentHistoryReadBlock.TOMBSTONED), b.reader.read(space, t))
        assertOpaque()
    }

    @Test fun `handled message before absent manifest cannot be consumed by continuation reader`() = scenario {
        a.consent(true); b.consent(true)
        a.author(1, ThreadCreated(thread(100), TITLE, 1)); a.turn(10, thread(100), MESSAGE)
        a.publish(1); a.publish(10); b.worker().run(space)
        assertEquals(AgentHistoryContinuationRead.Blocked(AgentHistoryReadBlock.INCOMPLETE), b.reader.read(space, thread(100)))
        b.reopen()
        assertEquals(AgentHistoryContinuationRead.Blocked(AgentHistoryReadBlock.INCOMPLETE), b.reader.read(space, thread(100)))
        a.publish(11); b.worker().run(space)
        assertIs<AgentHistoryContinuationRead.Ready>(b.reader.read(space, thread(100)))
    }

    @Test fun `late deleted audit retains D7 references and removed links while unrelated missing parent stays pending`() = scenario {
        a.consent(true); b.consent(true)
        val deleted = thread(100); val unrelated = thread(200)
        a.author(1, ThreadCreated(deleted, TITLE, 1)); a.worker().run(space); b.worker().run(space)
        a.author(2, ThreadDeleted(deleted)); a.worker().run(space); b.worker().run(space)
        a.business(business(50)); a.worker().run(space); b.worker().run(space)
        val removedParent = AgentMessageSyncId(id(9990))
        val action = ActionFinalized(AgentActionSyncId(id(1010)), null, deleted, removedParent, emptyList(), emptyList(), listOf(MutationId(id(50))), FinalAgentActionStatus.SUCCEEDED)
        a.author(10, action); a.author(11, action.copy(actionId = AgentActionSyncId(id(1011)), threadId = unrelated, sourceMessageId = AgentMessageSyncId(id(9991))))
        a.worker().run(space); b.worker().run(space)
        assertEquals(AgentSyncAuditParentState.PARENT_REMOVED_BY_TOMBSTONE, b.agent.auditParentState(space, id(1010), "MESSAGE", removedParent.value))
        assertEquals(AgentSyncAuditParentState.PARENT_PENDING, b.agent.auditParentState(space, id(1011), "MESSAGE", id(9991)))
        assertEquals(action, b.agent.operation(space, id(10))!!.agentEvent)
        assertTrue(b.state.pendingInboundOperations(space).none { it.operationId.value == id(10) })
        assertTrue(b.state.pendingInboundOperations(space).any { it.operationId.value == id(11) })
        assertEquals(1, b.journal.timeline().size)
        b.reopen()
        assertEquals(AgentSyncAuditParentState.PARENT_REMOVED_BY_TOMBSTONE, b.agent.auditParentState(space, id(1010), "MESSAGE", removedParent.value))
        assertEquals(AgentSyncAuditParentState.PARENT_PENDING, b.agent.auditParentState(space, id(1011), "MESSAGE", id(9991)))
        assertTrue(b.agent.isThreadTombstoned(space, deleted.value))
        assertEquals(emptyList(), b.agent.threadHistoryProjection(space, deleted).turns)
    }

    @Test fun `authenticated adversarial V3 is quarantined without corrupting Agent or business frontier`() = scenario {
        a.consent(true); b.consent(true)
        val original = a.author(1, ThreadCreated(thread(100), TITLE, 1))
        a.publish(1); b.worker().run(space)
        val before = b.agent.dvvFrontier(space)
        val reusedDot = original.copy(operationId = MutationId(id(2)), agentEvent = ThreadCreated(thread(200), "reused-dot", 1))
        a.publishRaw(id(2), AgentSyncWireCodec.encodePayload(SyncPayloadV3(operation = reusedDot)))
        val result = b.worker().run(space)
        assertEquals("AGENT_SYNC_INTEGRITY_FAILURE", result.agentHistoryReceiveFailure)
        assertNotNull(b.receive.quarantine(space, id(2)))
        assertEquals(before, b.agent.dvvFrontier(space))
        val unknown = AgentSyncWireCodec.encodePayload(SyncPayloadV3(operation = original.copy(operationId = MutationId(id(3))))).replace("\"ThreadCreated\"", "\"FutureEvent\"")
        a.publishRaw(id(3), unknown)
        a.publishRaw(id(4), AgentSyncWireCodec.encodePayload(SyncPayloadV3(operation = original))) // authenticated outer/inner mismatch
        b.worker().run(space)
        assertNotNull(b.receive.quarantine(space, id(3))); assertNotNull(b.receive.quarantine(space, id(4)))
        val otherAuthor = AgentReplicaId(id(555))
        val unequalRecord = AgentSyncOperation(MutationId(id(5)), AgentDvvSnapshot(emptyList(), AgentDot(otherAuthor, 0)),
            AgentHlcSnapshot(1, 0, otherAuthor), ThreadCreated(thread(100), "unequal immutable value", 1))
        a.publishRaw(id(5), AgentSyncWireCodec.encodePayload(SyncPayloadV3(operation = unequalRecord)))
        assertEquals("AGENT_SYNC_INTEGRITY_FAILURE", b.worker().run(space).agentHistoryReceiveFailure)
        assertNotNull(b.receive.quarantine(space, id(5)))
        assertNull(b.agent.operation(space, id(5)))
        b.reopen()
        assertNotNull(b.receive.quarantine(space, id(5)))
        assertEquals(before, b.agent.dvvFrontier(space))
        assertEquals(emptyList(), b.receive.handledDots(space)); assertEquals(emptyList(), b.journal.timeline())
        assertEquals(original, b.agent.operation(space, id(1)))
        assertOpaque()
    }

    @Test fun `remote turns never collide with existing local thread ordinal zero`() = scenario {
        a.consent(true); b.consent(true)
        for (replica in listOf(a, b)) {
            val localThread = AgentThreadId(thread(100).value)
            replica.local.saveThread(AgentThread(localThread, "local thread", 0))
            replica.local.appendMessage(AgentMessage(AgentMessageId(id(5000 + replica.n)), localThread, 0, AgentMessageRole.USER, "local ordinal zero", 0))
        }
        a.author(1, ThreadCreated(thread(100), TITLE, 1)); a.turn(10, thread(100), MESSAGE)
        a.worker().run(space); b.worker().run(space)
        for (replica in listOf(a, b)) {
            replica.reopen()
            assertEquals("local ordinal zero", replica.local.messages(AgentThreadId(thread(100).value)).single().content)
            assertEquals(0L, replica.local.messages(AgentThreadId(thread(100).value)).single().ordinal)
            assertEquals(MESSAGE, (replica.agent.threadHistoryProjection(space, thread(100)).turns.single().members.single().agentEvent as MessageAppended).content)
        }
    }

    @Test fun `V3 bounds preflight whole turn and malformed ciphertext never authenticates`() = scenario {
        a.consent(true); b.consent(true)
        a.author(1, ThreadCreated(thread(100), TITLE, 1)); a.turn(10, thread(100), MESSAGE)
        val lower = a.worker(limits = AgentEnvelopeUploadLimits(64, 100)).run(space)
        assertIs<SyncUploadResult.NonRetryableFailure>(lower.agentOutbound.failure)
        assertTrue(a.transport.fetch(space, 0, 100).isEmpty())
        a.worker().run(space); b.worker().run(space)
        val projection = b.agent.threadHistoryProjection(space, thread(100))
        val envelope = a.transport.fetch(space, 0, 100).first().envelope
        val bytes = decodeCanonicalBase64Url(envelope.ciphertextBase64Url, null, "ciphertext")
        bytes[bytes.lastIndex] = (bytes.last().toInt() xor 1).toByte()
        assertIs<DecryptSyncEnvelopeResult.AuthenticationFailed>(b.codec.decrypt(envelope.copy(ciphertextBase64Url = encodeCanonicalBase64Url(bytes))))
        assertIs<DecryptSyncEnvelopeResult.AuthenticationFailed>(b.codec.decrypt(envelope.copy(mutationId = id(999))))
        val anotherKey = TinkSyncPayloadAead.generate()
        val wrong = envelope.copy(ciphertextBase64Url = anotherKey.encryptToBase64Url("{}", SyncEnvelopeBinding.from(envelope).authenticatedAssociatedData()))
        assertIs<DecryptSyncEnvelopeResult.AuthenticationFailed>(b.codec.decrypt(wrong))
        assertEquals(projection, b.agent.threadHistoryProjection(space, thread(100)))
        val huge = AgentSyncOperation(MutationId(id(999)), AgentDvvSnapshot(emptyList(), AgentDot(a.replica, 99)), AgentHlcSnapshot(1, 0, a.replica),
            MessageAppended(AgentMessageSyncId(id(9991)), thread(100), AgentTurnSyncId(id(9992)), AgentMessageRoleV3.USER, "x".repeat(262144), 1))
        assertIs<EncryptSyncPayloadResult.InvalidPayload>(a.codec.encrypt(SyncEnvelopeBinding(space, huge.operationId.value, a.deviceId, 7), SyncPayloadV3(operation = huge)))
        assertEquals(3, a.transport.fetch(space, 0, 100).size)
    }

    @Test fun `concurrent tombstone releases already pending matching audit record dependencies only`() = scenario {
        a.consent(true); b.consent(true)
        val t = thread(100)
        a.author(1, ThreadCreated(t, TITLE, 1)); a.worker().run(space); b.worker().run(space)
        val action = ActionFinalized(AgentActionSyncId(id(1010)), null, t, AgentMessageSyncId(id(9990)), emptyList(), emptyList(), emptyList(), FinalAgentActionStatus.SUCCEEDED)
        b.author(10, action); b.worker().run(space); a.worker().run(space)
        assertTrue(a.agent.pendingDependencies(space, id(10)).any { it.kind == AgentSyncDependencyKind.PARENT_RECORD })
        a.author(2, ThreadDeleted(t)) // Does not observe the unhandled remote audit dot.
        a.worker().run(space); b.worker().run(space); a.worker().run(space)
        for (replica in listOf(a, b)) {
            assertEquals(emptyList(), replica.agent.pendingDependencies(space, id(10)))
            assertTrue(replica.state.pendingInboundOperations(space).none { it.operationId.value == id(10) })
            assertEquals(AgentSyncAuditParentState.PARENT_REMOVED_BY_TOMBSTONE, replica.agent.auditParentState(space, id(1010), "MESSAGE", id(9990)))
            assertTrue(replica.agent.isThreadTombstoned(space, t.value))
            replica.reopen()
            assertEquals(emptyList(), replica.agent.pendingDependencies(space, id(10)))
        }
    }

    @Test fun `concurrent identical KEEP decisions converge and unobserved resolution fails closed`() = scenario {
        a.consent(true); b.consent(true)
        val t = thread(100)
        a.author(1, ThreadCreated(t, TITLE, 1)); a.worker().run(space); b.worker().run(space)
        a.author(2, ThreadDeleted(t)); b.turn(10, t, MESSAGE)
        a.worker().run(space); b.worker().run(space); a.worker().run(space)
        val participants = a.agent.threadHistoryProjection(space, t).conflicts.single { it.kind == AgentSemanticConflictKind.THREAD_DELETE_APPEND }.participantOperationIds
        val attacker = AgentReplicaId(id(555))
        val invalid = AgentSyncOperation(MutationId(id(20)), AgentDvvSnapshot(emptyList(), AgentDot(attacker, 0)), AgentHlcSnapshot(1, 0, attacker),
            ThreadDeleteConflictResolved(t, participants, AgentThreadDeleteResolution.KEEP_DELETION, null))
        a.publishRaw(id(20), AgentSyncWireCodec.encodePayload(SyncPayloadV3(operation = invalid)))
        val rejected = b.worker().run(space)
        assertEquals("AGENT_SYNC_INTEGRITY_FAILURE", rejected.agentHistoryReceiveFailure)
        assertNull(b.agent.dvvFrontier(space).components[attacker])
        assertTrue(b.agent.threadHistoryProjection(space, t).conflicts.any { it.state == AgentSemanticConflictState.OPEN })
        a.resolve(30, t, participants); b.resolve(40, t, participants)
        a.worker().run(space); b.worker().run(space); a.worker().run(space)
        assertEquals(a.agent.threadHistoryProjection(space, t), b.agent.threadHistoryProjection(space, t))
        for (replica in listOf(a, b)) {
            val projection = replica.agent.threadHistoryProjection(space, t)
            assertTrue(projection.tombstoned); assertTrue(projection.turns.isEmpty())
            assertTrue(projection.conflicts.none { it.state == AgentSemanticConflictState.OPEN })
            assertEquals(listOf(MutationId(id(30)), MutationId(id(40))), projection.conflicts.single().resolutionOperationIds)
            replica.reopen(); assertEquals(projection, replica.agent.threadHistoryProjection(space, t))
        }
    }

    private fun scenario(body: suspend Fixture.() -> Unit) {
        val url = System.getenv("SYNC_TEST_DATABASE_URL")
        assumeTrue("Real PostgreSQL required; absence is SKIPPED, never E2E PASS.", url != null)
        testApplication {
            val source = HikariDataSource(HikariConfig().apply {
                jdbcUrl = requireNotNull(url)
                username = System.getenv("SYNC_TEST_DATABASE_USER") ?: "agentic"
                password = System.getenv("SYNC_TEST_DATABASE_PASSWORD") ?: "agentic-test"
                maximumPoolSize = 3
            })
            ServerSchemaMigrator(source).migrate()
            application { syncServerModule(JdbcOpaqueSyncRepository(source), SyncServerConfig(
                requireNotNull(url), "agentic", "acceptance-only", adminToken = ADMIN)) }
            val fixture = Fixture(source, client)
            try { fixture.start(); body(fixture) } finally { fixture.close(); source.close() }
        }
    }

    private class Fixture(val source: HikariDataSource, val client: HttpClient) {
        val account = AccountId("d90205-${UUID.randomUUID()}")
        val space = SyncSpaceId("d90205-${UUID.randomUUID()}")
        lateinit var a: Replica
        lateinit var b: Replica
        suspend fun start() { a = enrolled(700); b = enrolled(701) }
        private suspend fun enrolled(n: Int): Replica {
            val store = DesktopPlatformSecureStore()
            val hpke = store.generatePairingDeviceKey()
            val invitation = client.post("/v1/admin/invitations") {
                header("X-Sync-Admin-Token", ADMIN); contentType(ContentType.Application.Json)
                setBody(json.encodeToString(InvitationCreateRequest.serializer(), InvitationCreateRequest(account.value, space.value)))
            }
            assertEquals(HttpStatusCode.Created, invitation.status)
            val token = json.decodeFromString(InvitationCreateResponse.serializer(), invitation.bodyAsText()).invitationToken
            val response = client.post("/v1/bootstrap") {
                contentType(ContentType.Application.Json)
                setBody(json.encodeToString(BootstrapRequest.serializer(), BootstrapRequest(token, "${account.value}-$n", hpke.publicKey.value)))
            }
            assertEquals(HttpStatusCode.Created, response.status)
            val bootstrap = json.decodeFromString(BootstrapResponse.serializer(), response.bodyAsText())
            val credential = store.store(DeviceCredential(bootstrap.deviceCredential))
            val amk = store.generateAccountMasterKey()
            return Replica(this, n, store, credential, hpke, amk).also { replica ->
                replica.enrollment.saveActive(LocalEnrollmentState.Active(account, replica.deviceId, EnrollmentRequestId("bootstrap-$n"),
                    hpke.publicKey, hpke.privateKeyReference, space, amk, credential))
                replica.install(7, KEY7)
                replica.agent.provisionLocalReplica(space, replica.replica)
            }
        }
        suspend fun assertOpaque() {
            source.connection.use { connection ->
                val tables = connection.createStatement().use { statement -> statement.executeQuery(
                    "SELECT table_name FROM information_schema.tables WHERE table_schema='public' AND table_type='BASE TABLE'").use { rows ->
                    buildList { while (rows.next()) add(rows.getString(1)) }
                } }
                for (table in tables) connection.createStatement().use { statement ->
                    val quoted = "\"${table.replace("\"", "\"\"")}\""
                    statement.executeQuery("SELECT to_jsonb(r)::text FROM $quoted r").use { rows ->
                        while (rows.next()) for (canary in listOf(TITLE, MESSAGE, EXPORTED_ASSISTANT, INPUT, RESULT, PROVIDER_SECRET, SECRET_REF,
                            encodeCanonicalBase64Url(KEY7), encodeCanonicalBase64Url(KEY8)) + listOfNotNull(
                            a.store.load(a.credential)?.value, b.store.load(b.credential)?.value)) {
                            assertFalse(rows.getString(1).contains(canary), "Opaque relay table $table retained a forbidden canary.")
                        }
                    }
                }
            }
        }
        suspend fun close() {
            if (::a.isInitialized) a.close()
            if (::b.isInitialized) b.close()
        }
    }

    private class Replica(val fixture: Fixture, val n: Int, val store: DesktopPlatformSecureStore,
        val credential: SecretReference, val hpke: PersistedPairingDeviceKey, val amk: SecretReference) {
        val file = File.createTempFile("d90205-client-$n-", ".db")
        var db = openDesktopDatabase(file.absolutePath)
        val replica = AgentReplicaId(id(n + 100))
        val deviceId = DeviceId("${fixture.account.value}-$n")
        val references = mutableListOf(credential, hpke.privateKeyReference, amk)
        val keyReferences = mutableMapOf<Long, SecretReference>()
        val space get() = fixture.space
        val transactions get() = RoomApplicationTransactionRunner(db)
        val journal get() = RoomMutationJournalRepository(db)
        val receive get() = RoomSyncReceiveRepository(db)
        val events get() = RoomEventRepository(db)
        val businessOut get() = RoomSyncOutboundEnvelopeRepository(db)
        val agent get() = RoomAgentSyncPersistence(db)
        val state get() = RoomAgentSyncTransportPersistence(db)
        val local get() = RoomAgentStateRepository(db)
        val enrollment get() = RoomLocalEnrollmentRepository(db)
        val ring get() = RoomSyncKeyMetadataRepository(db)
        val keys get() = SecureCurrentEncryptionKeyProvider(ring, store)
        val codec get() = AuthenticatedSyncEnvelopeCodec(SecureSyncPayloadKeyProvider(ring, store), keys)
        val engine get() = SyncEngine(transactions, journal, journal, receive, events, RoomTaskRepository(db),
            RoomPlanningProfileRepository(db), RoomAcademicRepository(db), productionUuidV7Generator(), MutationWallClock { 1 })
        val integration get() = AgentHistoryReceiveIntegration(agent, state, transactions, receive, journal, MutationWallClock { 1 })
        val reader get() = AgentHistoryContinuationReader(agent, state, transactions)
        val transport get() = KtorSyncTransport(fixture.client, "https://localhost", deviceCredential = { store.load(credential) })
        var v2Gate = false
        var transportOverride: SyncTransport? = null
        suspend fun install(epoch: Long, bytes: ByteArray) {
            val imported = store.importContentKey(RawEphemeralKeyMaterial.fromBase64Url(encodeCanonicalBase64Url(bytes)))
            references += imported.reference
            keyReferences[epoch] = imported.reference
            assertTrue(ring.installNewEpoch(space, epoch, imported.reference, imported.identity) !is InstallSyncSpaceKeyEpochResult.IntegrityError)
        }
        suspend fun consent(enabled: Boolean) = state.setConversationConsent(space, enabled, enabled)
        suspend fun seedLocalOnlyCanaries() {
            val credential = store.importSecret(object : PlatformSecretMaterial {
                override fun copyRawSecretBytesForSecureStore() = PROVIDER_SECRET.encodeToByteArray()
            })
            references += credential
            local.saveProviderConfig(ProviderConfig(ProviderConfigId(id(5001)), "https://provider.example", "local-model", 4096, 512, false, true, credential))
            val t = AgentThreadId(id(5000))
            local.saveThread(AgentThread(t, "legacy local", 0))
            local.appendMessage(AgentMessage(AgentMessageId(id(5002)), t, 0, AgentMessageRole.USER, "legacy private", 0))
            local.saveProviderConfig(ProviderConfig(ProviderConfigId(id(5003)), "https://provider.example", "local-model", 4096, 512, false, true, SecretReference(SECRET_REF)))
        }
        suspend fun author(index: Int, event: AgentSyncEvent) = agent.enqueueOutbound(space, MutationId(id(index)), AgentHlcSnapshot(index.toLong(), 0, replica), event)
        suspend fun turn(index: Int, thread: AgentThreadSyncId, content: String, parents: List<AgentTurnSyncId> = emptyList(), dependency: MutationId? = null) {
            val turn = AgentTurnSyncId(id(index + 2000)); val message = AgentMessageSyncId(id(index + 1000))
            author(index, MessageAppended(message, thread, turn, AgentMessageRoleV3.USER, content, 1))
            val members = mutableListOf<TurnMemberReference>(MessageMember(message))
            if (dependency != null) {
                val action = AgentActionSyncId(id(index + 1001))
                author(index + 1, ActionFinalized(action, turn, thread, message, emptyList(), emptyList(), listOf(dependency), FinalAgentActionStatus.SUCCEEDED))
                members += ActionMember(action)
            }
            author(index + if (dependency == null) 1 else 2, TurnFinalized(turn, thread, parents, members, AgentTurnOutcome.SUCCEEDED))
        }
        fun draft(index: Int, event: AgentSyncEvent) = AgentSyncOutboundEventDraft(MutationId(id(index)), AgentHlcSnapshot(index.toLong(), 0, replica), event)
        suspend fun resolve(index: Int, thread: AgentThreadSyncId, participants: List<MutationId>, replacement: AgentThreadSyncId? = null, events: List<AgentSyncOutboundEventDraft> = emptyList()) =
            agent.commitExplicitUserDeleteResolution(space, AgentSyncExplicitDeleteResolution(MutationId(id(index)), AgentHlcSnapshot(index.toLong(), 0, replica),
                ThreadDeleteConflictResolved(thread, participants, if (replacement == null) AgentThreadDeleteResolution.KEEP_DELETION else AgentThreadDeleteResolution.COPY_CONTENT_TO_NEW_THREAD, replacement), events))
        suspend fun business(op: SyncOperation) = transactions.inWriteTransaction { journal.appendCommittedMutation(CommittedMutation(op, 1)) }
        suspend fun durableConflictKinds(thread: AgentThreadSyncId): List<String> = db.useReaderConnection { connection ->
            connection.usePrepared("SELECT conflict_kind FROM agent_sync_conflict WHERE sync_space_id = ? AND entity_kind = 'THREAD' AND entity_id = ? AND conflict_kind GLOB 'D9_02_03_*' ORDER BY conflict_kind") { row ->
                row.bindText(1, space.value); row.bindText(2, thread.value)
                buildList { while (row.step()) add(row.getText(0)) }
            }
        }
        suspend fun publish(index: Int): EncryptedEnvelopeV1 {
            val op = requireNotNull(agent.operation(space, id(index)))
            val epoch = requireNotNull(ring.state(space)).activeEncryptionEpoch
            val retained = state.outboundRecords(space).single { it.operation.operationId == op.operationId }.envelope
            val envelope = retained ?: assertIs<EncryptSyncPayloadResult.Encrypted>(codec.encrypt(SyncEnvelopeBinding(space, op.operationId.value, deviceId, epoch), SyncPayloadV3(operation = op))).envelope.also {
                state.retainEnvelopes(space, listOf(it))
            }
            assertTrue(transport.upload(envelope).let { it is SyncUploadResult.Stored || it is SyncUploadResult.Idempotent })
            return envelope
        }
        suspend fun publishRaw(operationId: String, payload: String): EncryptedEnvelopeV1 {
            val key = assertIs<CurrentEncryptionKeyLookup.Available>(keys.currentEncryptionKey(space))
            val binding = SyncEnvelopeBinding(space, operationId, deviceId, key.keyEpoch)
            val envelope = EncryptedEnvelopeV1(syncSpaceId = space, mutationId = operationId, senderDeviceId = deviceId, keyEpoch = key.keyEpoch,
                ciphertextBase64Url = key.aead.encryptToBase64Url(payload, binding.authenticatedAssociatedData()))
            assertIs<SyncUploadResult.Stored>(transport.upload(envelope))
            return envelope
        }
        fun worker(v3: Boolean = true, limits: AgentEnvelopeUploadLimits = AgentEnvelopeUploadLimits(1048576, 1200000)): SyncTransportWorker {
            val actual = transportOverride ?: transport
            val gateway = EncryptedSyncReceiveGateway(codec, engine, if (v3) integration else null)
            return SyncTransportWorker(journal, businessOut, receive, codec, keys, deviceId, actual, gateway,
                AgentOutboundCompatibilityGate { v2Gate }, if (v3) AgentHistoryOutboundTransport(agent, state, journal, businessOut,
                    receive, codec, keys, deviceId, actual, limits) else null)
        }
        fun backfill() = AgentHistoryBackfillTransport(agent, state, transportOverride ?: transport, codec, integration, engine, MutationWallClock { 1 })
        fun reopen() { db.close(); db = openDesktopDatabase(file.absolutePath) }
        suspend fun close() { db.close(); for (reference in references) store.delete(reference); file.delete() }
    }

    companion object {
        private const val ADMIN = "d90205-disposable-admin"
        private const val TITLE = "D90205-THREAD-TITLE-CANARY"
        private const val MESSAGE = "D90205-MESSAGE-TEXT-CANARY"
        private const val EXPORTED_ASSISTANT = "D90205-EXPORTED-ASSISTANT-CANARY"
        private const val INPUT = "D90205-NORMALIZED-INPUT-CANARY"
        private const val RESULT = "D90205-TOOL-RESULT-CANARY"
        private const val PROVIDER_SECRET = "D90205-PROVIDER-CREDENTIAL-CANARY"
        private const val SECRET_REF = "D90205-SECRET-REFERENCE-CANARY"
        private val KEY7 = ByteArray(32) { (it + 11).toByte() }
        private val KEY8 = ByteArray(32) { (it + 61).toByte() }
        private val json = Json { encodeDefaults = true; ignoreUnknownKeys = true }
        private fun id(n: Int) = "00000000-0000-7000-8000-${n.toString().padStart(12, '0')}"
        private fun thread(n: Int) = AgentThreadSyncId(id(n))
        private fun business(n: Int, replica: Int = 950, counter: Long = 0, origin: MutationOrigin = MutationOrigin.User) = SyncOperation(id(n),
            DvvSnapshot(if (counter == 0L) emptyList() else listOf(VersionComponent(id(replica), counter - 1)), DotSnapshot(id(replica), counter)),
            HlcSnapshot(counter, 0, id(replica)), origin, listOf(EventPut(null, EventImage(id(n + 10000), "business-$n",
                EventTimeImage.AllDay(AllDayRangeImage("2026-01-01", "2026-01-02")), FlexibilityImage.HARD, PinStateImage.UNPINNED))))
    }
}
