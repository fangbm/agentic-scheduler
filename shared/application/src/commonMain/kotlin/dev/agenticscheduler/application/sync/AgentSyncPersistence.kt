package dev.agenticscheduler.application.sync

import dev.agenticscheduler.sync.AgentReplicaId
import dev.agenticscheduler.sync.AgentSyncOperation
import dev.agenticscheduler.sync.SyncPayloadV3
import dev.agenticscheduler.sync.SyncSpaceId

/** Persistence outcomes for immutable V3 facts. A conflicting identity is never overwritten. */
sealed interface AgentSyncPersistResult {
    data object Inserted : AgentSyncPersistResult
    data object Duplicate : AgentSyncPersistResult
}

enum class AgentSyncDirection { INBOUND, OUTBOUND }
enum class AgentSyncDependencyKind { AGENT_DOT, PARENT_RECORD, BUSINESS_MUTATION, TURN_MEMBER, PARENT_TURN }
enum class AgentSyncTurnState { INCOMPLETE, COMPLETE_VERIFIED, ACTIVE, TOMBSTONED }
enum class AgentSyncAuditParentState { PARENT_PENDING, PARENT_VERIFIED, PARENT_REMOVED_BY_TOMBSTONE }
enum class AgentSyncBackfillRecoveryState { IDLE, REQUIRED, RUNNING, INCOMPLETE, COMPLETE }

data class AgentSyncPendingDependency(
    val operationId: String,
    val kind: AgentSyncDependencyKind,
    val key: String,
)

sealed interface AgentSyncFrontierState {
    data class Available(val components: Map<AgentReplicaId, Long>) : AgentSyncFrontierState
    data object BlockedByDecision : AgentSyncFrontierState
}

data class AgentSyncBackfillState(
    val cursor: Long,
    val recoveryState: AgentSyncBackfillRecoveryState,
    val earliestQuarantinedCursor: Long?,
    val updatedAtEpochMillis: Long,
)

/** Local persistence port for D9-02. It never performs network I/O or D7 business writes. */
interface AgentSyncPersistence {
    suspend fun enqueueOutbound(syncSpaceId: SyncSpaceId, payload: SyncPayloadV3): AgentSyncPersistResult
    suspend fun acceptInbound(syncSpaceId: SyncSpaceId, payload: SyncPayloadV3): AgentSyncPersistResult
    suspend fun operation(syncSpaceId: SyncSpaceId, operationId: String): AgentSyncOperation?
    suspend fun direction(syncSpaceId: SyncSpaceId, operationId: String): Set<AgentSyncDirection>

    suspend fun setLocalReplica(syncSpaceId: SyncSpaceId, replicaId: AgentReplicaId, counter: Long)
    suspend fun localReplica(syncSpaceId: SyncSpaceId): Pair<AgentReplicaId, Long>?
    suspend fun dvvFrontier(syncSpaceId: SyncSpaceId): AgentSyncFrontierState
    suspend fun markHandled(syncSpaceId: SyncSpaceId, operationId: String)

    suspend fun addPendingDependency(syncSpaceId: SyncSpaceId, dependency: AgentSyncPendingDependency)
    suspend fun resolvePendingDependency(syncSpaceId: SyncSpaceId, dependency: AgentSyncPendingDependency)
    suspend fun pendingDependencies(syncSpaceId: SyncSpaceId, operationId: String): List<AgentSyncPendingDependency>

    suspend fun markTurnMemberPresent(syncSpaceId: SyncSpaceId, turnId: String, memberKind: String, memberId: String)
    suspend fun turnState(syncSpaceId: SyncSpaceId, turnId: String): AgentSyncTurnState?
    suspend fun markTurnActive(syncSpaceId: SyncSpaceId, turnId: String)
    suspend fun isTurnActive(syncSpaceId: SyncSpaceId, turnId: String): Boolean

    suspend fun isThreadTombstoned(syncSpaceId: SyncSpaceId, threadId: String): Boolean
    suspend fun setAuditParentState(syncSpaceId: SyncSpaceId, actionId: String, parentKind: String, parentId: String, state: AgentSyncAuditParentState, threadId: String? = null)
    suspend fun auditParentState(syncSpaceId: SyncSpaceId, actionId: String, parentKind: String, parentId: String): AgentSyncAuditParentState?

    suspend fun backfillState(syncSpaceId: SyncSpaceId): AgentSyncBackfillState?
    suspend fun advanceBackfill(syncSpaceId: SyncSpaceId, value: AgentSyncBackfillState)
}
