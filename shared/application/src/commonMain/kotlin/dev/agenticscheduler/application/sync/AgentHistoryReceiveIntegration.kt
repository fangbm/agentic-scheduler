package dev.agenticscheduler.application.sync

import dev.agenticscheduler.application.history.DecryptedPayloadReceipt
import dev.agenticscheduler.application.history.MutationWallClock
import dev.agenticscheduler.application.history.SyncEngine
import dev.agenticscheduler.application.history.SyncReceiveResult
import dev.agenticscheduler.application.persistence.ApplicationTransactionRunner
import dev.agenticscheduler.application.persistence.HistoryRepository
import dev.agenticscheduler.application.persistence.SyncReceiveRepository
import dev.agenticscheduler.sync.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.intOrNull

/** Inner dispatch only, after the unchanged D8 envelope/AAD authentication boundary. */
class AgentHistoryReceiveIntegration(
    private val persistence: AgentSyncPersistence,
    private val state: AgentSyncTransportPersistence,
    private val transactions: ApplicationTransactionRunner,
    private val businessReceive: SyncReceiveRepository,
    private val history: HistoryRepository,
    private val clock: MutationWallClock,
) {
    suspend fun receive(receipt: DecryptedPayloadReceipt, businessEngine: SyncEngine, backfill: Boolean = false): EncryptedSyncReceiveResult {
        if (!state.conversationConsent(receipt.syncSpaceId)) {
            if (backfill) return EncryptedSyncReceiveResult.ProtocolFailure.InvalidEnvelope("Conversation consent is off.")
            // Existing business engine quarantines V3 without storing its plaintext or Agent dots.
            val result = businessEngine.receive(receipt)
            rememberQuarantine(receipt.syncSpaceId, receipt.serverCursor)
            return EncryptedSyncReceiveResult.Handled(result)
        }
        val decoded = try { AgentSyncWireCodec.decodePayload(receipt.payloadJson, MutationId(receipt.mutationId))
        } catch (_: IllegalArgumentException) { AgentPayloadDecodeResult.Invalid("Invalid authenticated operation ID.") }
        if (decoded !is AgentPayloadDecodeResult.Supported) {
            if (backfill) return EncryptedSyncReceiveResult.ProtocolFailure.InvalidEnvelope("Invalid or unsupported Agent history payload.")
            // Unknown V3 event/invalid payload remains whole-ID quarantined by the legacy path.
            val result = businessEngine.receive(receipt)
            rememberQuarantine(receipt.syncSpaceId, receipt.serverCursor)
            return EncryptedSyncReceiveResult.Handled(result)
        }
        return transactions.inWriteTransaction {
            // Recheck consent inside the database write boundary.
            check(state.conversationConsent(receipt.syncSpaceId)) { "Conversation consent was withdrawn." }
            if (history.mutation(receipt.mutationId) != null) return@inWriteTransaction rejectIntegrity(receipt, backfill)
            // Already indexed inbound facts must not acquire fresh dependencies on retransmission.
            val alreadyIndexed = persistence.operation(receipt.syncSpaceId, receipt.mutationId) == decoded.payload.operation &&
                AgentSyncDirection.INBOUND in persistence.direction(receipt.syncSpaceId, receipt.mutationId)
            val outcome = try {
                if (alreadyIndexed) AgentSyncPersistResult.Duplicate else persistence.acceptInbound(receipt.syncSpaceId, decoded.payload)
            } catch (_: AgentSyncIntegrityFailure) {
                return@inWriteTransaction rejectIntegrity(receipt, backfill)
            }
            val drainFailure = drain(receipt.syncSpaceId)
            if (!backfill) businessReceive.saveServerCursor(receipt.syncSpaceId, maxOf(businessReceive.serverCursor(receipt.syncSpaceId), receipt.serverCursor))
            EncryptedSyncReceiveResult.AgentHandled(decoded.payload.operation.operationId, outcome, drainFailure)
        }
    }

    private suspend fun rejectIntegrity(receipt: DecryptedPayloadReceipt, backfill: Boolean): EncryptedSyncReceiveResult {
        if (backfill) return EncryptedSyncReceiveResult.ProtocolFailure.InvalidEnvelope("AGENT_SYNC_INTEGRITY_FAILURE")
        // The adapter retains immutable/integrity evidence; quarantine routing metadata only.
        businessReceive.quarantine(ProtocolQuarantine(receipt.syncSpaceId, receipt.mutationId, receipt.serverCursor,
            ProtocolQuarantineReason.INVALID_PAYLOAD, "AGENT_SYNC_INTEGRITY_FAILURE"))
        businessReceive.saveServerCursor(receipt.syncSpaceId, maxOf(businessReceive.serverCursor(receipt.syncSpaceId), receipt.serverCursor))
        return EncryptedSyncReceiveResult.Handled(SyncReceiveResult.Quarantined(receipt.mutationId, ProtocolQuarantineReason.INVALID_PAYLOAD), "AGENT_SYNC_INTEGRITY_FAILURE")
    }

    suspend fun afterBusinessReceive(space: SyncSpaceId): String? = try {
        if (state.conversationConsent(space)) transactions.inWriteTransaction { drain(space) } else null
    } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled
    } catch (_: Exception) { "AGENT_HISTORY_RECEIVE_FAILURE" }

    private suspend fun drain(space: SyncSpaceId): String? {
        var integrityFailure: String? = null
        do {
            var changed = false
            for (operation in state.pendingInboundOperations(space)) {
                for (dependency in persistence.pendingDependencies(space, operation.operationId.value)) {
                    if (dependency.kind == AgentSyncDependencyKind.BUSINESS_MUTATION) {
                        val fact = history.mutation(dependency.key)
                        // A local D7 fact is required; a received Agent success cannot create one.
                        if (fact != null) persistence.resolvePendingDependency(space, dependency)
                    }
                }
            }
            for (operation in persistence.eligibleInboundOperations(space)) {
                try {
                    persistence.markHandled(space, operation.operationId.value)
                    changed = true
                } catch (_: AgentSyncIntegrityFailure) { integrityFailure = "AGENT_SYNC_INTEGRITY_FAILURE" }
            }
            for (turn in state.completedInboundTurnIds(space)) {
                persistence.markTurnActive(space, turn)
                changed = true
            }
        } while (changed)
        return integrityFailure
    }

    suspend fun rememberQuarantine(space: SyncSpaceId, cursor: Long) = transactions.inWriteTransaction {
        val old = persistence.backfillState(space)
        persistence.advanceBackfill(space, AgentSyncBackfillState(old?.cursor ?: maxOf(0L, cursor - 1),
            AgentSyncBackfillRecoveryState.REQUIRED, cursor, clock.nowEpochMillis()))
    }

    /** A stale fetch must not overwrite a concurrently discovered earlier recovery generation. */
    internal suspend fun advanceRecovery(space: SyncSpaceId, expected: AgentSyncBackfillState, next: AgentSyncBackfillState): Boolean = transactions.inWriteTransaction {
        if (persistence.backfillState(space) != expected) return@inWriteTransaction false
        persistence.advanceBackfill(space, next)
        true
    }
}

internal fun isAgentV3(payloadJson: String): Boolean = try {
    Json.parseToJsonElement(payloadJson).jsonObject["payloadVersion"]?.jsonPrimitive?.intOrNull == 3
} catch (_: Exception) { false }

/** Historical replay owns only the Agent cursor, never replays a business operation. */
class AgentHistoryBackfillTransport(
    private val persistence: AgentSyncPersistence,
    private val state: AgentSyncTransportPersistence,
    private val transport: SyncTransport,
    private val codec: AuthenticatedSyncEnvelopeCodec,
    private val integration: AgentHistoryReceiveIntegration,
    private val businessEngine: SyncEngine,
    private val clock: MutationWallClock,
) {
    suspend fun run(space: SyncSpaceId, limit: Int = 100): AgentSyncBackfillRecoveryState {
        require(limit > 0)
        if (!state.conversationConsent(space)) return persistence.backfillState(space)?.recoveryState ?: AgentSyncBackfillRecoveryState.IDLE
        val earliest = state.earliestUnrecoveredV3Quarantine(space)
        val previous = persistence.backfillState(space)
        if (earliest != null && (previous == null || previous.recoveryState == AgentSyncBackfillRecoveryState.COMPLETE || earliest < (previous.earliestQuarantinedCursor ?: Long.MAX_VALUE))) integration.rememberQuarantine(space, earliest)
        var progress = persistence.backfillState(space) ?: return AgentSyncBackfillRecoveryState.IDLE
        if (progress.recoveryState == AgentSyncBackfillRecoveryState.COMPLETE) return progress.recoveryState
        try {
            recoveryLoop@ while (true) {
                if (!state.conversationConsent(space)) return progress.recoveryState
                progress = persistence.backfillState(space) ?: progress
                val batch = transport.fetch(space, progress.cursor, limit)
                if (batch.isEmpty()) {
                    // If the earliest retained quarantine is absent, do not claim complete recovery.
                    val missing = state.earliestUnrecoveredV3Quarantine(space) != null
                    if (!integration.advanceRecovery(space, progress, progress.copy(recoveryState = if (missing) AgentSyncBackfillRecoveryState.INCOMPLETE else AgentSyncBackfillRecoveryState.COMPLETE, updatedAtEpochMillis = clock.nowEpochMillis()))) continue@recoveryLoop
                    return persistence.backfillState(space)!!.recoveryState
                }
                for (remote in batch) {
                    if (persistence.backfillState(space) != progress) continue@recoveryLoop
                    check(remote.envelope.syncSpaceId == space && remote.serverCursor > progress.cursor) { "Historical routing/cursor mismatch." }
                    val unrecovered = state.earliestUnrecoveredV3Quarantine(space)
                    check(unrecovered == null || unrecovered >= remote.serverCursor) { "Known historical ciphertext is missing before this cursor." }
                    val decrypted = codec.decrypt(remote.envelope)
                    if (decrypted !is DecryptSyncEnvelopeResult.AuthenticatedPlaintext) error("History ciphertext/key unavailable or authentication failed.")
                    if (isAgentV3(decrypted.payloadJson)) {
                        val result = integration.receive(DecryptedPayloadReceipt(space, remote.envelope.mutationId, remote.serverCursor, decrypted.payloadJson), businessEngine, backfill = true)
                        check(result is EncryptedSyncReceiveResult.AgentHandled && result.agentHistoryFailure == null) { "History is unsupported, invalid or consent was withdrawn." }
                    }
                    val next = progress.copy(cursor = remote.serverCursor, recoveryState = AgentSyncBackfillRecoveryState.RUNNING, updatedAtEpochMillis = clock.nowEpochMillis())
                    if (!integration.advanceRecovery(space, progress, next)) continue@recoveryLoop
                    progress = next
                }
            }
        } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled
        } catch (_: Exception) {
            val current = persistence.backfillState(space) ?: progress
            integration.advanceRecovery(space, current, current.copy(recoveryState = AgentSyncBackfillRecoveryState.INCOMPLETE, updatedAtEpochMillis = clock.nowEpochMillis()))
            return AgentSyncBackfillRecoveryState.INCOMPLETE
        }
    }
}
