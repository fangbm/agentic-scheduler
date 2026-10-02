package dev.agenticscheduler.application.sync

import dev.agenticscheduler.application.persistence.ApplicationTransactionRunner
import dev.agenticscheduler.sync.AgentThreadHistoryProjection
import dev.agenticscheduler.sync.AgentThreadSyncId
import dev.agenticscheduler.sync.SyncSpaceId

enum class AgentHistoryReadBlock { CONSENT_OFF, INCOMPLETE, TOMBSTONED, SEMANTIC_CONFLICT }

sealed interface AgentHistoryContinuationRead {
    data class Ready(val projection: AgentThreadHistoryProjection) : AgentHistoryContinuationRead
    data class Blocked(val reason: AgentHistoryReadBlock) : AgentHistoryContinuationRead
}

/**
 * Acceptance composition's sealed-history read boundary. It grants no Tool execution capability
 * and is not injected into production Provider runs while OD-012 remains open.
 */
class AgentHistoryContinuationReader(
    private val history: AgentSyncPersistence,
    private val state: AgentSyncTransportPersistence,
    private val transactions: ApplicationTransactionRunner,
) {
    suspend fun read(space: SyncSpaceId, thread: AgentThreadSyncId): AgentHistoryContinuationRead = transactions.inWriteTransaction {
        if (!state.conversationConsent(space)) return@inWriteTransaction AgentHistoryContinuationRead.Blocked(AgentHistoryReadBlock.CONSENT_OFF)
        val projection = history.threadHistoryProjection(space, thread)
        if (projection.tombstoned) return@inWriteTransaction AgentHistoryContinuationRead.Blocked(AgentHistoryReadBlock.TOMBSTONED)
        if (!projection.providerContinuationAllowed) return@inWriteTransaction AgentHistoryContinuationRead.Blocked(AgentHistoryReadBlock.SEMANTIC_CONFLICT)
        if (history.hasIncompleteInboundHistory(space, thread) ||
            state.pendingInboundOperations(space).any { it.agentEvent.threadIdForTransport() == thread } ||
            state.outboundRecords(space).any { record -> record.operation.agentEvent.threadIdForTransport() == thread &&
                record.operation.agentEvent.turnIdForTransport()?.let { !history.isTurnActive(space, it.value) } == true }) {
            return@inWriteTransaction AgentHistoryContinuationRead.Blocked(AgentHistoryReadBlock.INCOMPLETE)
        }
        AgentHistoryContinuationRead.Ready(projection)
    }
}
