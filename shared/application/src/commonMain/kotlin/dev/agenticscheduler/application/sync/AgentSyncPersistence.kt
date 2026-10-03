package dev.agenticscheduler.application.sync

import dev.agenticscheduler.sync.AgentReplicaId
import dev.agenticscheduler.sync.AgentHlcSnapshot
import dev.agenticscheduler.sync.AgentSyncEvent
import dev.agenticscheduler.sync.AgentSyncOperation
import dev.agenticscheduler.sync.AgentThreadHistoryProjection
import dev.agenticscheduler.sync.AgentThreadSyncId
import dev.agenticscheduler.sync.ThreadDeleteConflictResolved
import dev.agenticscheduler.sync.MutationId
import dev.agenticscheduler.sync.SyncPayloadV3
import dev.agenticscheduler.sync.SyncSpaceId

/** Persistence outcomes for immutable V3 facts. A conflicting identity is never overwritten. */
sealed interface AgentSyncPersistResult {
    data object Inserted : AgentSyncPersistResult
    data object Duplicate : AgentSyncPersistResult
}

enum class AgentSyncDirection { INBOUND, OUTBOUND }
/** Typed persistence integrity failure, caught by transport without depending on Room. */
open class AgentSyncIntegrityFailure(message: String) : IllegalStateException(message)
enum class AgentSyncDependencyKind { AGENT_DOT, PARENT_RECORD, BUSINESS_MUTATION, TURN_MEMBER, PARENT_TURN }
enum class AgentSyncTurnState { INCOMPLETE, COMPLETE_VERIFIED, ACTIVE, TOMBSTONED }
enum class AgentSyncAuditParentState { PARENT_PENDING, PARENT_VERIFIED, PARENT_REMOVED_BY_TOMBSTONE }
enum class AgentSyncBackfillRecoveryState { IDLE, REQUIRED, RUNNING, INCOMPLETE, COMPLETE }

data class AgentSyncPendingDependency(
    val operationId: String,
    val kind: AgentSyncDependencyKind,
    val key: String,
)

data class AgentSyncFrontierState(val components: Map<AgentReplicaId, Long>)

/** Persisted counter is the next local Agent dot to allocate. */
data class AgentSyncLocalClock(val replicaId: AgentReplicaId, val nextCounter: Long)

data class AgentSyncBackfillState(
    val cursor: Long,
    val recoveryState: AgentSyncBackfillRecoveryState,
    val earliestQuarantinedCursor: Long?,
    val updatedAtEpochMillis: Long,
)

/** A caller-supplied fresh immutable event in a user-approved replacement-thread batch. */
data class AgentSyncOutboundEventDraft(
    val operationId: MutationId,
    val hlc: AgentHlcSnapshot,
    val event: AgentSyncEvent,
)

/** The exact user-selected resolution and optional fresh text-only replacement history. */
data class AgentSyncExplicitDeleteResolution(
    val operationId: MutationId,
    val hlc: AgentHlcSnapshot,
    val event: ThreadDeleteConflictResolved,
    val replacementEvents: List<AgentSyncOutboundEventDraft> = emptyList(),
)

/** Local persistence port for D9-02. It never performs network I/O or D7 business writes. */
interface AgentSyncPersistence {
    suspend fun enqueueOutbound(syncSpaceId: SyncSpaceId, operationId: MutationId, hlc: AgentHlcSnapshot, event: AgentSyncEvent): AgentSyncOperation
    suspend fun acceptInbound(syncSpaceId: SyncSpaceId, payload: SyncPayloadV3): AgentSyncPersistResult
    suspend fun operation(syncSpaceId: SyncSpaceId, operationId: String): AgentSyncOperation?
    suspend fun direction(syncSpaceId: SyncSpaceId, operationId: String): Set<AgentSyncDirection>

    suspend fun provisionLocalReplica(syncSpaceId: SyncSpaceId, replicaId: AgentReplicaId): AgentSyncLocalClock
    suspend fun localReplica(syncSpaceId: SyncSpaceId): AgentSyncLocalClock?
    suspend fun dvvFrontier(syncSpaceId: SyncSpaceId): AgentSyncFrontierState
    suspend fun markHandled(syncSpaceId: SyncSpaceId, operationId: String)
    suspend fun eligibleInboundOperations(syncSpaceId: SyncSpaceId): List<AgentSyncOperation>

    suspend fun addPendingDependency(syncSpaceId: SyncSpaceId, dependency: AgentSyncPendingDependency)
    suspend fun resolvePendingDependency(syncSpaceId: SyncSpaceId, dependency: AgentSyncPendingDependency)
    suspend fun pendingDependencies(syncSpaceId: SyncSpaceId, operationId: String): List<AgentSyncPendingDependency>

    suspend fun markTurnMemberPresent(syncSpaceId: SyncSpaceId, turnId: String, memberKind: String, memberId: String)
    suspend fun turnState(syncSpaceId: SyncSpaceId, turnId: String): AgentSyncTurnState?
    suspend fun markTurnActive(syncSpaceId: SyncSpaceId, turnId: String)
    suspend fun isTurnActive(syncSpaceId: SyncSpaceId, turnId: String): Boolean

    suspend fun isThreadTombstoned(syncSpaceId: SyncSpaceId, threadId: String): Boolean
    suspend fun threadHistoryProjection(syncSpaceId: SyncSpaceId, threadId: AgentThreadSyncId): AgentThreadHistoryProjection
    /** Includes handled members whose manifest has not arrived; active projection alone cannot detect them. */
    suspend fun hasIncompleteInboundHistory(syncSpaceId: SyncSpaceId, threadId: AgentThreadSyncId): Boolean
    suspend fun setAuditParentState(syncSpaceId: SyncSpaceId, actionId: String, parentKind: String, parentId: String, state: AgentSyncAuditParentState, threadId: String? = null)
    suspend fun auditParentState(syncSpaceId: SyncSpaceId, actionId: String, parentKind: String, parentId: String): AgentSyncAuditParentState?

    suspend fun backfillState(syncSpaceId: SyncSpaceId): AgentSyncBackfillState?
    suspend fun advanceBackfill(syncSpaceId: SyncSpaceId, value: AgentSyncBackfillState)
}

data class AgentSyncHistoricalExportMapping(
    val syncSpaceId: SyncSpaceId,
    val sourceKind: String,
    val sourceId: String,
    val operationId: MutationId,
    val hlc: AgentHlcSnapshot,
    val event: AgentSyncEvent,
)

data class AgentSyncHistoricalExportPreparation(val operation: AgentSyncOperation, val createdMapping: Boolean)

/** Explicit-owner-only local capability. It prepares mapping and Agent outbox atomically; it never uploads. */
interface AgentSyncHistoricalExportPersistence {
    suspend fun historicalExportMappings(syncSpaceId: SyncSpaceId): List<AgentSyncHistoricalExportMapping>
    suspend fun prepareHistoricalExport(
        syncSpaceId: SyncSpaceId,
        sourceKind: String,
        sourceId: String,
        candidateOperationId: MutationId,
        hlc: AgentHlcSnapshot,
        event: AgentSyncEvent,
    ): AgentSyncHistoricalExportPreparation
}

/**
 * Deliberately separate from the generic Agent outbox port. UI/user-action code may inject this
 * capability; Agent Tools and background schedulers receive only AgentSyncPersistence.
 */
interface AgentSyncExplicitUserResolutionPersistence {
    suspend fun commitExplicitUserDeleteResolution(
        syncSpaceId: SyncSpaceId,
        resolution: AgentSyncExplicitDeleteResolution,
    ): List<AgentSyncOperation>
}
