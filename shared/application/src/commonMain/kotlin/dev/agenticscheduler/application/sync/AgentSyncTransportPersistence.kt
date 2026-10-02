package dev.agenticscheduler.application.sync

import dev.agenticscheduler.sync.AgentSyncOperation
import dev.agenticscheduler.sync.EncryptedEnvelopeV1
import dev.agenticscheduler.sync.SyncSpaceId

data class AgentOutboundTransportRecord(
    val operation: AgentSyncOperation,
    val state: AgentOutboundTransportState,
    val envelope: EncryptedEnvelopeV1?,
)

enum class AgentOutboundTransportState { READY, HELD, UPLOADED }

/** Transport-only state, separate from the D7 journal and its ciphertext. */
interface AgentSyncTransportPersistence {
    suspend fun conversationConsent(syncSpaceId: SyncSpaceId): Boolean
    suspend fun outboundRecords(syncSpaceId: SyncSpaceId): List<AgentOutboundTransportRecord>
    suspend fun retainEnvelopes(syncSpaceId: SyncSpaceId, envelopes: List<EncryptedEnvelopeV1>): List<EncryptedEnvelopeV1>
    suspend fun setHeld(syncSpaceId: SyncSpaceId, operationIds: List<String>, held: Boolean)
    suspend fun markUploaded(syncSpaceId: SyncSpaceId, operationId: String)
    suspend fun pendingInboundOperations(syncSpaceId: SyncSpaceId): List<AgentSyncOperation>
    suspend fun completedInboundTurnIds(syncSpaceId: SyncSpaceId): List<String>
    suspend fun earliestUnrecoveredV3Quarantine(syncSpaceId: SyncSpaceId): Long?
}

/** Inject only into explicit local user actions, never Agent Tools/background tasks. */
interface AgentSyncUserConsentPersistence {
    suspend fun setConversationConsent(syncSpaceId: SyncSpaceId, enabled: Boolean, allActiveDevicesV3Acknowledged: Boolean)
}
