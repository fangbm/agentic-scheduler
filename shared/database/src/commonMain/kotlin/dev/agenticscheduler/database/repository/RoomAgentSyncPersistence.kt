package dev.agenticscheduler.database.repository

import androidx.room3.PooledConnection
import androidx.room3.useReaderConnection
import androidx.room3.withWriteTransaction
import androidx.sqlite.SQLiteStatement
import dev.agenticscheduler.application.sync.AgentSyncAuditParentState
import dev.agenticscheduler.application.sync.AgentSyncBackfillRecoveryState
import dev.agenticscheduler.application.sync.AgentSyncBackfillState
import dev.agenticscheduler.application.sync.AgentSyncDependencyKind
import dev.agenticscheduler.application.sync.AgentSyncDirection
import dev.agenticscheduler.application.sync.AgentSyncExplicitDeleteResolution
import dev.agenticscheduler.application.sync.AgentSyncExplicitUserResolutionPersistence
import dev.agenticscheduler.application.sync.AgentSyncFrontierState
import dev.agenticscheduler.application.sync.AgentSyncLocalClock
import dev.agenticscheduler.application.sync.AgentSyncOutboundEventDraft
import dev.agenticscheduler.application.sync.AgentSyncPendingDependency
import dev.agenticscheduler.application.sync.AgentSyncPersistResult
import dev.agenticscheduler.application.sync.AgentSyncPersistence
import dev.agenticscheduler.application.sync.AgentSyncTurnState
import dev.agenticscheduler.database.AgenticSchedulerDatabase
import dev.agenticscheduler.sync.*
import kotlinx.serialization.SerializationException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.add

class AgentSyncIntegrityConflictException(message: String) : IllegalStateException(message)

/** Room adapter for isolated D9-02 local state; it has no transport or receive-dispatch hooks. */
class RoomAgentSyncPersistence(private val database: AgenticSchedulerDatabase) : AgentSyncPersistence, AgentSyncExplicitUserResolutionPersistence {
    private val json = Json { encodeDefaults = true }
    override suspend fun enqueueOutbound(
        syncSpaceId: SyncSpaceId,
        operationId: MutationId,
        hlc: AgentHlcSnapshot,
        event: AgentSyncEvent,
    ): AgentSyncOperation {
        require(event !is ThreadDeleteConflictResolved) {
            "ThreadDeleteConflictResolved may only be authored through the explicit local-user resolution capability."
        }
        val outcome = database.withWriteTransaction {
            val threadId = event.threadIdForConflictCheck()
            if (threadId != null && event.isContentEvent() && query(
                    "SELECT 1 FROM agent_sync_thread_tombstone WHERE sync_space_id = ? AND thread_id = ? LIMIT 1",
                    listOf(syncSpaceId.value, threadId),
                ) { it.getLong(0) }.isNotEmpty()) {
                return@withWriteTransaction LocalEnqueueOutcome.Conflict("A tombstoned Agent thread cannot receive new active content.")
            }
            enqueueOutboundInTransaction(syncSpaceId, operationId, hlc, event, allowResolution = false)
        }
        return when (outcome) {
            is LocalEnqueueOutcome.Success -> outcome.operation
            is LocalEnqueueOutcome.Conflict -> throw AgentSyncIntegrityConflictException(outcome.message)
        }
    }

    override suspend fun acceptInbound(syncSpaceId: SyncSpaceId, payload: SyncPayloadV3): AgentSyncPersistResult {
        payload.operation.requireSequentialAuthorContext()
        check(localReplica(syncSpaceId) != null) { "Provision an Agent replica for the SyncSpace before accepting V3 Agent history." }
        return persist(syncSpaceId, payload, AgentSyncDirection.INBOUND)
    }

    private suspend fun persist(space: SyncSpaceId, payload: SyncPayloadV3, direction: AgentSyncDirection): AgentSyncPersistResult {
        val outcome = database.withWriteTransaction {
            val operation = payload.operation
            val encoded = AgentSyncWireCodec.encodePayload(payload)
            val record = payload.operation.agentEvent.immutableRecordIdentity()
            val existing = query(
                "SELECT operation_id, agent_replica_id, agent_counter, immutable_record_kind, immutable_record_id, payload_json " +
                    "FROM agent_sync_operation_identity WHERE sync_space_id = ? AND (operation_id = ? OR (agent_replica_id = ? AND agent_counter = ?) OR (? IS NOT NULL AND immutable_record_kind = ? AND immutable_record_id = ?))",
                listOf(space.value, operation.operationId.value, operation.agentDvv.dot.replicaId.value, operation.agentDvv.dot.counter,
                    record?.first, record?.first, record?.second),
            ) { row -> IdentityRow(row.getText(0), row.getText(1), row.getLong(2), if (row.isNull(3)) null else row.getText(3), if (row.isNull(4)) null else row.getText(4), row.getText(5)) }
            val duplicate = existing.firstOrNull()
            if (duplicate != null) {
                val sameTypedOperation = decodeOperation(duplicate.payloadJson, duplicate.operationId) == operation
                val sameDot = duplicate.replicaId == operation.agentDvv.dot.replicaId.value && duplicate.counter == operation.agentDvv.dot.counter
                val sameRecord = record != null && duplicate.recordKind == record.first && duplicate.recordId == record.second
                if (!sameTypedOperation || duplicate.operationId != operation.operationId.value || (!sameDot && !sameRecord)) {
                    recordIntegrityConflict(space, operation, record, duplicate.operationId, encoded)
                    return@withWriteTransaction PersistOutcome.Conflict("Agent sync operation ID, immutable record ID, or Agent dot is already bound to a different typed operation.")
                }
                addDirection(space, operation.operationId.value, direction)
                persistEventIndexes(space, operation, direction)
                return@withWriteTransaction PersistOutcome.Success(AgentSyncPersistResult.Duplicate)
            }
            execute(
                "INSERT INTO agent_sync_operation_identity(sync_space_id, operation_id, agent_replica_id, agent_counter, event_type, immutable_record_kind, immutable_record_id, payload_json) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                listOf(space.value, operation.operationId.value, operation.agentDvv.dot.replicaId.value, operation.agentDvv.dot.counter,
                    operation.agentEvent::class.simpleName ?: "AgentSyncEvent", record?.first, record?.second, encoded),
            )
            addDirection(space, operation.operationId.value, direction)
            persistEventIndexes(space, operation, direction)
            PersistOutcome.Success(AgentSyncPersistResult.Inserted)
        }
        return when (outcome) {
            is PersistOutcome.Success -> outcome.result
            is PersistOutcome.Conflict -> throw AgentSyncIntegrityConflictException(outcome.message)
        }
    }

    override suspend fun operation(syncSpaceId: SyncSpaceId, operationId: String): AgentSyncOperation? = database.useReaderConnection { connection ->
        connection.query("SELECT operation_id, payload_json FROM agent_sync_operation_identity WHERE sync_space_id = ? AND operation_id = ?", listOf(syncSpaceId.value, operationId)) {
            decodePayload(it.getText(1), it.getText(0)).operation
        }.firstOrNull()
    }

    override suspend fun direction(syncSpaceId: SyncSpaceId, operationId: String): Set<AgentSyncDirection> = database.useReaderConnection { connection ->
        buildSet {
            if (connection.query("SELECT 1 FROM agent_sync_inbox WHERE sync_space_id = ? AND operation_id = ?", listOf(syncSpaceId.value, operationId)) { it.getLong(0) }.isNotEmpty()) add(AgentSyncDirection.INBOUND)
            if (connection.query("SELECT 1 FROM agent_sync_outbox WHERE sync_space_id = ? AND operation_id = ?", listOf(syncSpaceId.value, operationId)) { it.getLong(0) }.isNotEmpty()) add(AgentSyncDirection.OUTBOUND)
        }
    }

    override suspend fun provisionLocalReplica(syncSpaceId: SyncSpaceId, replicaId: AgentReplicaId): AgentSyncLocalClock = database.withWriteTransaction {
        val old = query("SELECT local_replica_id, local_counter FROM agent_sync_space_state WHERE sync_space_id = ?", listOf(syncSpaceId.value)) { it.getText(0) to it.getLong(1) }.firstOrNull()
        require(old == null || old.first == replicaId.value) { "Agent replica identity is immutable per SyncSpace; restore without its causal state using a new AgentReplicaId." }
        if (old == null) {
            execute("INSERT INTO agent_sync_space_state(sync_space_id, local_replica_id, local_counter) VALUES (?, ?, 0)", listOf(syncSpaceId.value, replicaId.value))
            AgentSyncLocalClock(replicaId, 0L)
        } else {
            AgentSyncLocalClock(replicaId, old.second)
        }
    }

    override suspend fun localReplica(syncSpaceId: SyncSpaceId): AgentSyncLocalClock? = database.useReaderConnection { connection ->
        connection.query("SELECT local_replica_id, local_counter FROM agent_sync_space_state WHERE sync_space_id = ?", listOf(syncSpaceId.value)) { AgentSyncLocalClock(AgentReplicaId(it.getText(0)), it.getLong(1)) }.firstOrNull()
    }

    override suspend fun dvvFrontier(syncSpaceId: SyncSpaceId): AgentSyncFrontierState = database.useReaderConnection { connection ->
        AgentSyncFrontierState(connection.frontierInTransaction(syncSpaceId.value))
    }

    override suspend fun markHandled(syncSpaceId: SyncSpaceId, operationId: String): Unit = database.withWriteTransaction {
        val row = query("SELECT agent_replica_id, agent_counter FROM agent_sync_operation_identity WHERE sync_space_id = ? AND operation_id = ?", listOf(syncSpaceId.value, operationId)) { it.getText(0) to it.getLong(1) }.singleOrNull()
            ?: error("Cannot mark an unknown Agent operation handled.")
        val handledOperation = query(
            "SELECT payload_json FROM agent_sync_operation_identity WHERE sync_space_id = ? AND operation_id = ?",
            listOf(syncSpaceId.value, operationId),
        ) { decodePayload(it.getText(0), operationId).operation }.single()
        check(query("SELECT 1 FROM agent_sync_inbox WHERE sync_space_id = ? AND operation_id = ?", listOf(syncSpaceId.value, operationId)) { it.getLong(0) }.isNotEmpty()) { "Only inbound Agent operations can be marked handled." }
        check(query("SELECT 1 FROM agent_sync_pending_dependency WHERE sync_space_id = ? AND operation_id = ? LIMIT 1", listOf(syncSpaceId.value, operationId)) { it.getLong(0) }.isEmpty()) { "Agent operations with unresolved causal or parent dependencies cannot be marked handled." }
        val resolutionEvent = handledOperation.agentEvent as? ThreadDeleteConflictResolved
        if (resolutionEvent != null) {
            val prior = threadOperationsInTransaction(syncSpaceId.value, resolutionEvent.threadId)
                .filterNot { it.operationId == handledOperation.operationId }
            val validation = AgentSyncMergeProjection.validateDeleteResolution(handledOperation, prior)
            if (validation !is AgentDeleteResolutionValidation.Valid) {
                val reason = (validation as AgentDeleteResolutionValidation.Invalid).reason
                throw AgentSyncIntegrityConflictException("Invalid Agent thread-delete resolution: $reason")
            }
            validateFreshReplacementThreadId(syncSpaceId.value, handledOperation)
        }
        validateReplacementThreadEvent(syncSpaceId.value, handledOperation)
        execute("INSERT INTO agent_sync_handled_dot(sync_space_id, agent_replica_id, counter, operation_id) VALUES (?, ?, ?, ?) ON CONFLICT(sync_space_id, operation_id) DO NOTHING", listOf(syncSpaceId.value, row.first, row.second, operationId))
        val binding = query("SELECT operation_id FROM agent_sync_handled_dot WHERE sync_space_id = ? AND agent_replica_id = ? AND counter = ?", listOf(syncSpaceId.value, row.first, row.second)) { it.getText(0) }.single()
        if (binding != operationId) throw AgentSyncIntegrityConflictException("An Agent handled dot is already bound to another operation ID.")
        execute("UPDATE agent_sync_inbox SET state = 'HANDLED' WHERE sync_space_id = ? AND operation_id = ?", listOf(syncSpaceId.value, operationId))
        if (handledOperation.agentEvent is ThreadDeleted) {
            materializeThreadDelete(syncSpaceId.value, handledOperation)
        }
        val identity = query("SELECT immutable_record_kind, immutable_record_id FROM agent_sync_operation_identity WHERE sync_space_id = ? AND operation_id = ?", listOf(syncSpaceId.value, operationId)) { (if (it.isNull(0)) null else it.getText(0)) to (if (it.isNull(1)) null else it.getText(1)) }.single()
        if (identity.first != null && identity.second != null) {
            execute("UPDATE agent_sync_turn_member SET present = 1 WHERE sync_space_id = ? AND member_kind = ? AND member_id = ?", listOf(syncSpaceId.value, identity.first, identity.second))
            query("SELECT turn_id FROM agent_sync_turn_member WHERE sync_space_id = ? AND member_kind = ? AND member_id = ?", listOf(syncSpaceId.value, identity.first, identity.second)) { it.getText(0) }.forEach { recomputeTurnState(syncSpaceId.value, it) }
            val waiting = query("SELECT operation_id, dependency_kind, dependency_key FROM agent_sync_pending_dependency WHERE sync_space_id = ? AND dependency_kind IN ('PARENT_RECORD','TURN_MEMBER') AND dependency_key = ?", listOf(syncSpaceId.value, identity.second)) { Triple(it.getText(0), it.getText(1), it.getText(2)) }
            waiting.forEach { (waitingOperation, kind, key) ->
                execute("DELETE FROM agent_sync_pending_dependency WHERE sync_space_id = ? AND operation_id = ? AND dependency_kind = ? AND dependency_key = ?", listOf(syncSpaceId.value, waitingOperation, kind, key))
                query("SELECT turn_id FROM agent_sync_turn_stage WHERE sync_space_id = ? AND manifest_operation_id = ?", listOf(syncSpaceId.value, waitingOperation)) { it.getText(0) }.forEach { recomputeTurnState(syncSpaceId.value, it) }
            }
            markAuditParentsForHandledRecord(syncSpaceId.value, identity.first!!, identity.second!!)
        }
        advanceAgentFrontier(syncSpaceId.value, AgentReplicaId(row.first))
        recomputeTurnsForOperation(syncSpaceId.value, operationId)
        handledOperation.agentEvent.threadIdForAudit()?.let { refreshSemanticConflictRecords(syncSpaceId.value, AgentThreadSyncId(it)) }
        Unit
    }

    override suspend fun eligibleInboundOperations(syncSpaceId: SyncSpaceId): List<AgentSyncOperation> = database.useReaderConnection { connection ->
        connection.query(
            "SELECT i.operation_id, i.payload_json FROM agent_sync_operation_identity i " +
                "JOIN agent_sync_inbox b USING(sync_space_id, operation_id) " +
                "WHERE i.sync_space_id = ? AND b.state = 'PENDING' " +
                "AND NOT EXISTS (SELECT 1 FROM agent_sync_pending_dependency d WHERE d.sync_space_id = i.sync_space_id AND d.operation_id = i.operation_id) " +
                "ORDER BY i.agent_replica_id, i.agent_counter, i.operation_id",
            listOf(syncSpaceId.value),
        ) { decodePayload(it.getText(1), it.getText(0)).operation }
    }

    override suspend fun addPendingDependency(syncSpaceId: SyncSpaceId, dependency: AgentSyncPendingDependency) = database.withWriteTransaction {
        check(query("SELECT 1 FROM agent_sync_handled_dot WHERE sync_space_id = ? AND operation_id = ?", listOf(syncSpaceId.value, dependency.operationId)) { it.getLong(0) }.isEmpty()) { "Handled immutable Agent operations cannot gain new dependencies." }
        if (dependency.kind == AgentSyncDependencyKind.AGENT_DOT) {
            val (replicaId, counter) = dependency.parseAgentDotKey()
            val frontier = query("SELECT counter FROM agent_sync_dvv_frontier WHERE sync_space_id = ? AND agent_replica_id = ?", listOf(syncSpaceId.value, replicaId.value)) { it.getLong(0) }.firstOrNull() ?: -1L
            if (frontier >= counter) return@withWriteTransaction
        }
        execute("INSERT INTO agent_sync_pending_dependency(sync_space_id, operation_id, dependency_kind, dependency_key) VALUES (?, ?, ?, ?) ON CONFLICT DO NOTHING", listOf(syncSpaceId.value, dependency.operationId, dependency.kind.name, dependency.key))
        execute("UPDATE agent_sync_outbox SET state = 'HELD' WHERE sync_space_id = ? AND operation_id = ?", listOf(syncSpaceId.value, dependency.operationId))
        val turns = query("SELECT turn_id FROM agent_sync_turn_stage WHERE sync_space_id = ? AND manifest_operation_id = ? UNION SELECT turn_id FROM agent_sync_turn_member WHERE sync_space_id = ? AND member_id IN (SELECT immutable_record_id FROM agent_sync_operation_identity WHERE sync_space_id = ? AND operation_id = ?)", listOf(syncSpaceId.value, dependency.operationId, syncSpaceId.value, syncSpaceId.value, dependency.operationId)) { it.getText(0) }
        turns.forEach { recomputeTurnState(syncSpaceId.value, it) }
    }

    override suspend fun resolvePendingDependency(syncSpaceId: SyncSpaceId, dependency: AgentSyncPendingDependency) = database.withWriteTransaction {
        if (dependency.kind == AgentSyncDependencyKind.AGENT_DOT) {
            val (replicaId, counter) = dependency.parseAgentDotKey()
            val frontier = query("SELECT counter FROM agent_sync_dvv_frontier WHERE sync_space_id = ? AND agent_replica_id = ?", listOf(syncSpaceId.value, replicaId.value)) { it.getLong(0) }.firstOrNull() ?: -1L
            check(frontier >= counter) { "An Agent-dot dependency can only be released by its contiguous handled frontier." }
        }
        execute("DELETE FROM agent_sync_pending_dependency WHERE sync_space_id = ? AND operation_id = ? AND dependency_kind = ? AND dependency_key = ?", listOf(syncSpaceId.value, dependency.operationId, dependency.kind.name, dependency.key))
        execute("UPDATE agent_sync_outbox SET state = 'READY' WHERE sync_space_id = ? AND operation_id = ? AND state = 'HELD' AND NOT EXISTS (SELECT 1 FROM agent_sync_pending_dependency WHERE sync_space_id = ? AND operation_id = ?)", listOf(syncSpaceId.value, dependency.operationId, syncSpaceId.value, dependency.operationId))
        recomputeTurnsForOperation(syncSpaceId.value, dependency.operationId)
    }

    override suspend fun pendingDependencies(syncSpaceId: SyncSpaceId, operationId: String): List<AgentSyncPendingDependency> = database.useReaderConnection { connection ->
        connection.query("SELECT operation_id, dependency_kind, dependency_key FROM agent_sync_pending_dependency WHERE sync_space_id = ? AND operation_id = ? ORDER BY dependency_kind, dependency_key", listOf(syncSpaceId.value, operationId)) {
            AgentSyncPendingDependency(it.getText(0), AgentSyncDependencyKind.valueOf(it.getText(1)), it.getText(2))
        }
    }

    override suspend fun markTurnMemberPresent(syncSpaceId: SyncSpaceId, turnId: String, memberKind: String, memberId: String) = database.withWriteTransaction {
        check(query("SELECT 1 FROM agent_sync_operation_identity i JOIN agent_sync_inbox b USING(sync_space_id, operation_id) JOIN agent_sync_handled_dot h USING(sync_space_id, operation_id) WHERE i.sync_space_id = ? AND i.immutable_record_kind = ? AND i.immutable_record_id = ? LIMIT 1", listOf(syncSpaceId.value, memberKind, memberId)) { it.getLong(0) }.isNotEmpty()) { "Only handled inbound immutable records can satisfy a staged manifest member." }
        execute("UPDATE agent_sync_turn_member SET present = 1 WHERE sync_space_id = ? AND turn_id = ? AND member_kind = ? AND member_id = ?", listOf(syncSpaceId.value, turnId, memberKind, memberId))
        check(query("SELECT changes()") { it.getLong(0) }.single() == 1L) { "The record is not a member of the staged TurnFinalized manifest." }
        recomputeTurnState(syncSpaceId.value, turnId)
    }

    override suspend fun turnState(syncSpaceId: SyncSpaceId, turnId: String): AgentSyncTurnState? = database.useReaderConnection { connection ->
        connection.query("SELECT state FROM agent_sync_turn_stage WHERE sync_space_id = ? AND turn_id = ?", listOf(syncSpaceId.value, turnId)) { AgentSyncTurnState.valueOf(it.getText(0)) }.firstOrNull()
    }

    override suspend fun markTurnActive(syncSpaceId: SyncSpaceId, turnId: String) = database.withWriteTransaction {
        val stage = query("SELECT thread_id, manifest_operation_id, state FROM agent_sync_turn_stage WHERE sync_space_id = ? AND turn_id = ?", listOf(syncSpaceId.value, turnId)) { Triple(it.getText(0), it.getText(1), AgentSyncTurnState.valueOf(it.getText(2))) }.singleOrNull()
            ?: error("Unknown staged Agent turn.")
        check(stage.third != AgentSyncTurnState.TOMBSTONED) { "A tombstoned thread cannot be restored by an older event." }
        require(stage.third == AgentSyncTurnState.COMPLETE_VERIFIED) { "An incomplete turn cannot be made active." }
        check(query("SELECT 1 FROM agent_sync_thread_tombstone WHERE sync_space_id = ? AND thread_id = ? LIMIT 1", listOf(syncSpaceId.value, stage.first)) { it.getLong(0) }.isEmpty()) { "A tombstoned thread cannot be restored by an older event." }
        val unresolved = query("SELECT 1 FROM agent_sync_pending_dependency WHERE sync_space_id = ? AND operation_id IN (SELECT operation_id FROM agent_sync_operation_identity WHERE sync_space_id = ? AND immutable_record_id IN (SELECT member_id FROM agent_sync_turn_member WHERE sync_space_id = ? AND turn_id = ?)) LIMIT 1", listOf(syncSpaceId.value, syncSpaceId.value, syncSpaceId.value, turnId)) { it.getLong(0) }
        require(unresolved.isEmpty()) { "A turn with unresolved causal or record dependencies cannot become active." }
        execute("INSERT INTO agent_sync_active_turn_projection(sync_space_id, turn_id, thread_id) VALUES (?, ?, ?) ON CONFLICT(sync_space_id, turn_id) DO NOTHING", listOf(syncSpaceId.value, turnId, stage.first))
        val waiting = query("SELECT operation_id, dependency_key FROM agent_sync_pending_dependency WHERE sync_space_id = ? AND dependency_kind = 'PARENT_TURN' AND dependency_key = ?", listOf(syncSpaceId.value, turnId)) { it.getText(0) to it.getText(1) }
        waiting.forEach { (operation, key) ->
            execute("DELETE FROM agent_sync_pending_dependency WHERE sync_space_id = ? AND operation_id = ? AND dependency_kind = 'PARENT_TURN' AND dependency_key = ?", listOf(syncSpaceId.value, operation, key))
            query("SELECT turn_id FROM agent_sync_turn_stage WHERE sync_space_id = ? AND manifest_operation_id = ?", listOf(syncSpaceId.value, operation)) { it.getText(0) }.forEach { recomputeTurnState(syncSpaceId.value, it) }
        }
    }

    override suspend fun isTurnActive(syncSpaceId: SyncSpaceId, turnId: String): Boolean = database.useReaderConnection { connection ->
        connection.query("SELECT 1 FROM agent_sync_active_turn_projection WHERE sync_space_id = ? AND turn_id = ?", listOf(syncSpaceId.value, turnId)) { it.getLong(0) }.isNotEmpty()
    }

    override suspend fun isThreadTombstoned(syncSpaceId: SyncSpaceId, threadId: String): Boolean = database.useReaderConnection { connection ->
        connection.query("SELECT 1 FROM agent_sync_thread_tombstone WHERE sync_space_id = ? AND thread_id = ? LIMIT 1", listOf(syncSpaceId.value, threadId)) { it.getLong(0) }.isNotEmpty()
    }

    override suspend fun threadHistoryProjection(syncSpaceId: SyncSpaceId, threadId: AgentThreadSyncId): AgentThreadHistoryProjection =
        database.useReaderConnection { connection ->
            val operations = connection.threadOperationsInTransaction(syncSpaceId.value, threadId)
            val activeTurns = connection.query(
                "SELECT turn_id FROM agent_sync_active_turn_projection WHERE sync_space_id = ? AND thread_id = ? ORDER BY turn_id",
                listOf(syncSpaceId.value, threadId.value),
            ) { AgentTurnSyncId(it.getText(0)) }.toSet()
            val tombstoned = connection.query(
                "SELECT 1 FROM agent_sync_thread_tombstone WHERE sync_space_id = ? AND thread_id = ? LIMIT 1",
                listOf(syncSpaceId.value, threadId.value),
            ) { it.getLong(0) }.isNotEmpty()
            AgentSyncMergeProjection.project(threadId, operations, activeTurns, tombstoned)
        }

    override suspend fun commitExplicitUserDeleteResolution(
        syncSpaceId: SyncSpaceId,
        resolution: AgentSyncExplicitDeleteResolution,
    ): List<AgentSyncOperation> = database.withWriteTransaction {
        val event = resolution.event
        require(resolution.operationId !in event.participantOperationIds) { "A resolution operation cannot be one of its own participants." }
        val allDrafts = listOf(AgentSyncOutboundEventDraft(resolution.operationId, resolution.hlc, event)) + resolution.replacementEvents
        require(allDrafts.map { it.operationId }.distinct().size == allDrafts.size) { "Resolution batch operation IDs must be unique." }
        val retryRows = allDrafts.map { draft ->
            query(
                "SELECT operation_id, payload_json FROM agent_sync_operation_identity WHERE sync_space_id = ? AND operation_id = ?",
                listOf(syncSpaceId.value, draft.operationId.value),
            ) { decodePayload(it.getText(1), it.getText(0)).operation }.singleOrNull()
        }
        if (retryRows.any { it != null }) {
            require(retryRows.all { it != null }) { "A resolution batch cannot be partially retried." }
            val existing = retryRows.filterNotNull()
            require(existing.map { it.agentEvent } == allDrafts.map { it.event } && existing.map { it.hlc } == allDrafts.map { it.hlc }) {
                "A retried explicit user resolution batch has different immutable content."
            }
            require(allDrafts.all { AgentSyncDirection.OUTBOUND in directionInTransaction(syncSpaceId.value, it.operationId.value) }) {
                "An existing inbound event cannot be reused as a local explicit user resolution."
            }
            return@withWriteTransaction existing
        }

        val local = query("SELECT local_replica_id, local_counter FROM agent_sync_space_state WHERE sync_space_id = ?", listOf(syncSpaceId.value)) {
            AgentSyncLocalClock(AgentReplicaId(it.getText(0)), it.getLong(1))
        }.singleOrNull() ?: error("Provision an Agent replica before resolving a conflict.")
        require(resolution.hlc.replicaId == local.replicaId) { "Explicit user resolution must use this SyncSpace's local Agent replica." }
        val prior = threadOperationsInTransaction(syncSpaceId.value, event.threadId)
        val preview = nextLocalOperation(syncSpaceId, resolution.operationId, resolution.hlc, event)
        val validation = AgentSyncMergeProjection.validateDeleteResolution(preview, prior)
        if (validation !is AgentDeleteResolutionValidation.Valid) {
            throw AgentSyncIntegrityConflictException("Explicit user resolution is stale or causally incomplete: ${(validation as AgentDeleteResolutionValidation.Invalid).reason}")
        }
        check(query("SELECT 1 FROM agent_sync_thread_tombstone WHERE sync_space_id = ? AND thread_id = ? LIMIT 1", listOf(syncSpaceId.value, event.threadId.value)) { it.getLong(0) }.isNotEmpty()) {
            "Delete conflict resolution cannot be authored before the original thread is tombstoned."
        }
        validateFreshReplacementThreadId(syncSpaceId.value, preview)
        validateReplacementBatch(syncSpaceId.value, event, resolution.replacementEvents)

        val inserted = mutableListOf<AgentSyncOperation>()
        for (draft in allDrafts) {
            val outcome = enqueueOutboundInTransaction(syncSpaceId, draft.operationId, draft.hlc, draft.event, allowResolution = draft.event is ThreadDeleteConflictResolved)
            val operation = when (outcome) {
                is LocalEnqueueOutcome.Success -> outcome.operation
                is LocalEnqueueOutcome.Conflict -> throw AgentSyncIntegrityConflictException(outcome.message)
            }
            inserted += operation
        }
        refreshSemanticConflictRecords(syncSpaceId.value, event.threadId)
        event.replacementThreadId?.let { refreshSemanticConflictRecords(syncSpaceId.value, it) }
        inserted
    }

    private suspend fun PooledConnection.enqueueOutboundInTransaction(
        syncSpaceId: SyncSpaceId,
        operationId: MutationId,
        hlc: AgentHlcSnapshot,
        event: AgentSyncEvent,
        allowResolution: Boolean,
    ): LocalEnqueueOutcome {
        if (event is ThreadDeleteConflictResolved && !allowResolution) {
            return LocalEnqueueOutcome.Conflict("ThreadDeleteConflictResolved requires the explicit local-user capability.")
        }
        val spaceState = query(
            "SELECT local_replica_id, local_counter FROM agent_sync_space_state WHERE sync_space_id = ?",
            listOf(syncSpaceId.value),
        ) { AgentSyncLocalClock(AgentReplicaId(it.getText(0)), it.getLong(1)) }.singleOrNull()
            ?: error("Provision an Agent replica before allocating a local operation.")
        require(hlc.replicaId == spaceState.replicaId) { "Local Agent HLC must use the provisioned AgentReplicaId." }

        val existing = query(
            "SELECT operation_id, payload_json FROM agent_sync_operation_identity WHERE sync_space_id = ? AND operation_id = ?",
            listOf(syncSpaceId.value, operationId.value),
        ) { decodePayload(it.getText(1), it.getText(0)).operation }.singleOrNull()
        if (existing != null) {
            if (existing.agentEvent != event || existing.hlc != hlc || AgentSyncDirection.OUTBOUND !in directionInTransaction(syncSpaceId.value, operationId.value)) {
                val candidate = existing.copy(agentEvent = event, hlc = hlc)
                recordIntegrityConflict(syncSpaceId, candidate, candidate.agentEvent.immutableRecordIdentity(), existing.operationId.value,
                    AgentSyncWireCodec.encodePayload(SyncPayloadV3(operation = candidate)))
                return LocalEnqueueOutcome.Conflict("A retried outbound Agent operation ID is already bound to different immutable content or metadata.")
            }
            return LocalEnqueueOutcome.Success(existing)
        }

        val localThread = event.threadIdForConflictCheck()
        if (localThread != null && event.isContentEvent() && query(
                "SELECT 1 FROM agent_sync_thread_tombstone WHERE sync_space_id = ? AND thread_id = ? LIMIT 1",
                listOf(syncSpaceId.value, localThread),
            ) { it.getLong(0) }.isNotEmpty()) {
            return LocalEnqueueOutcome.Conflict("A tombstoned Agent thread cannot receive new active content.")
        }

        val next = spaceState.nextCounter
        check(next < Long.MAX_VALUE) { "Agent counter exhausted; refusing counter wrap or reuse of this AgentReplicaId." }
        val frontier = frontierInTransaction(syncSpaceId.value).toMutableMap()
        val expectedLocalFrontier = if (next == 0L) null else next - 1L
        check(frontier[spaceState.replicaId] == expectedLocalFrontier) {
            "Persisted local Agent counter and contiguous frontier are inconsistent; do not reuse this AgentReplicaId."
        }
        val dot = AgentDot(spaceState.replicaId, next)
        if (next > 0L) frontier[spaceState.replicaId] = next - 1L
        val operation = AgentSyncOperation(
            operationId,
            AgentDvvSnapshot(frontier.entries.sortedBy { it.key.value }.map { AgentVersionComponent(it.key, it.value) }, dot),
            hlc,
            event,
        )
        val encoded = AgentSyncWireCodec.encodePayload(SyncPayloadV3(operation = operation))
        val record = event.immutableRecordIdentity()
        val conflicting = query(
            "SELECT operation_id FROM agent_sync_operation_identity WHERE sync_space_id = ? AND (" +
                "(agent_replica_id = ? AND agent_counter = ?) OR " +
                "(? IS NOT NULL AND immutable_record_kind = ? AND immutable_record_id = ?))",
            listOf(syncSpaceId.value, dot.replicaId.value, dot.counter, record?.first, record?.first, record?.second),
        ) { it.getText(0) }.firstOrNull()
        if (conflicting != null) {
            recordIntegrityConflict(syncSpaceId, operation, record, conflicting, encoded)
            return LocalEnqueueOutcome.Conflict("Agent dot or immutable record identity is already bound to another operation.")
        }
        execute(
            "INSERT INTO agent_sync_operation_identity(sync_space_id, operation_id, agent_replica_id, agent_counter, event_type, immutable_record_kind, immutable_record_id, payload_json) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
            listOf(syncSpaceId.value, operationId.value, dot.replicaId.value, dot.counter,
                event::class.simpleName ?: "AgentSyncEvent", record?.first, record?.second, encoded),
        )
        addDirection(syncSpaceId, operationId.value, AgentSyncDirection.OUTBOUND)
        execute("UPDATE agent_sync_space_state SET local_counter = ? WHERE sync_space_id = ?", listOf(next + 1L, syncSpaceId.value))
        advanceAgentFrontier(syncSpaceId.value, dot.replicaId)
        persistEventIndexes(syncSpaceId, operation, AgentSyncDirection.OUTBOUND)
        operation.agentEvent.threadIdForAudit()?.let { refreshSemanticConflictRecords(syncSpaceId.value, AgentThreadSyncId(it)) }
        return LocalEnqueueOutcome.Success(operation)
    }

    private suspend fun PooledConnection.nextLocalOperation(
        syncSpaceId: SyncSpaceId,
        operationId: MutationId,
        hlc: AgentHlcSnapshot,
        event: AgentSyncEvent,
    ): AgentSyncOperation {
        val clock = query("SELECT local_replica_id, local_counter FROM agent_sync_space_state WHERE sync_space_id = ?", listOf(syncSpaceId.value)) {
            AgentSyncLocalClock(AgentReplicaId(it.getText(0)), it.getLong(1))
        }.singleOrNull() ?: error("Provision an Agent replica before resolving a conflict.")
        require(hlc.replicaId == clock.replicaId)
        check(clock.nextCounter < Long.MAX_VALUE)
        val frontier = frontierInTransaction(syncSpaceId.value).toMutableMap()
        if (clock.nextCounter > 0L) frontier[clock.replicaId] = clock.nextCounter - 1L
        return AgentSyncOperation(
            operationId,
            AgentDvvSnapshot(frontier.entries.sortedBy { it.key.value }.map { AgentVersionComponent(it.key, it.value) }, AgentDot(clock.replicaId, clock.nextCounter)),
            hlc,
            event,
        )
    }

    private suspend fun PooledConnection.validateReplacementBatch(
        space: String,
        resolution: ThreadDeleteConflictResolved,
        drafts: List<AgentSyncOutboundEventDraft>,
    ) {
        if (resolution.resolution == AgentThreadDeleteResolution.KEEP_DELETION) {
            require(drafts.isEmpty()) { "KEEP_DELETION cannot include replacement-thread events." }
            return
        }
        val replacement = requireNotNull(resolution.replacementThreadId)
        require(drafts.isNotEmpty()) { "COPY_CONTENT_TO_NEW_THREAD needs a fresh ThreadCreated and selected text turns." }
        val events = drafts.map { it.event }
        val creates = events.filterIsInstance<ThreadCreated>()
        require(creates.size == 1 && creates.single().threadId == replacement && events.first() == creates.single()) {
            "A copied thread batch must begin with exactly one ThreadCreated for its replacement ID."
        }
        require(events.all { event -> event is ThreadCreated || event is MessageAppended || event is TurnFinalized }) {
            "Copied history may contain only a new thread, text messages, and sealed text turns; Tool and audit events are not copied."
        }
        val messages = events.filterIsInstance<MessageAppended>()
        require(messages.all { it.threadId == replacement && it.role in setOf(AgentMessageRoleV3.USER, AgentMessageRoleV3.ASSISTANT) }) {
            "Copied content must be fresh USER/ASSISTANT text messages in the replacement thread."
        }
        val turns = events.filterIsInstance<TurnFinalized>()
        val turnIds = turns.map { it.turnId }.toSet()
        require(turnIds.size == turns.size && turns.all { it.threadId == replacement && it.parentTurnIds.all(turnIds::contains) }) {
            "Copied turns need fresh identities and may parent only other copied turns."
        }
        val copiedMessages = messages.associateBy { it.messageId }
        require(turns.all { turn ->
            turn.orderedMembers.all { it is MessageMember } &&
                turn.orderedMembers.map { (it as MessageMember).id }.toSet() == copiedMessages.values.filter { it.turnId == turn.turnId }.map { it.messageId }.toSet() &&
                copiedMessages.values.filter { it.turnId == turn.turnId }.all { it.threadId == replacement }
        } && messages.all { it.turnId in turnIds }) {
            "Every copied text message must belong to exactly one fresh message-only finalized turn."
        }
        val recordIds = events.mapNotNull { it.immutableRecordIdentity()?.second }
        require(recordIds.distinct().size == recordIds.size)
        val allIds = drafts.map { it.operationId.value } + recordIds + resolution.participantOperationIds.map { it.value }
        require(allIds.distinct().size == allIds.size) { "Copied operation and entity IDs must all be fresh and distinct from conflict participants." }
        for (id in drafts.map { it.operationId.value } + recordIds) {
            val reused = query(
                "SELECT 1 FROM agent_sync_operation_identity WHERE sync_space_id = ? AND (operation_id = ? OR immutable_record_id = ?) LIMIT 1",
                listOf(space, id, id),
            ) { it.getLong(0) }.isNotEmpty()
            require(!reused) { "COPY_CONTENT_TO_NEW_THREAD requires fresh operation and record IDs." }
        }
    }

    private suspend fun PooledConnection.threadOperationsInTransaction(space: String, threadId: AgentThreadSyncId): List<AgentSyncOperation> =
        allHandledOrOutboundOperations(space).filter { it.agentEvent.threadIdForAudit() == threadId.value }

    private suspend fun PooledConnection.allHandledOrOutboundOperations(space: String): List<AgentSyncOperation> = query(
        "SELECT i.operation_id, i.payload_json FROM agent_sync_operation_identity i WHERE i.sync_space_id = ? AND (" +
            "EXISTS (SELECT 1 FROM agent_sync_inbox b WHERE b.sync_space_id = i.sync_space_id AND b.operation_id = i.operation_id AND b.state = 'HANDLED') OR " +
            "EXISTS (SELECT 1 FROM agent_sync_outbox o WHERE o.sync_space_id = i.sync_space_id AND o.operation_id = i.operation_id)) " +
            "ORDER BY i.agent_replica_id, i.agent_counter, i.operation_id",
        listOf(space),
    ) { decodePayload(it.getText(1), it.getText(0)).operation }

    private suspend fun PooledConnection.validateFreshReplacementThreadId(space: String, operation: AgentSyncOperation) {
        val event = operation.agentEvent as ThreadDeleteConflictResolved
        if (event.resolution != AgentThreadDeleteResolution.COPY_CONTENT_TO_NEW_THREAD) return
        val replacement = requireNotNull(event.replacementThreadId)
        val allOperations = query(
            "SELECT i.operation_id, i.payload_json FROM agent_sync_operation_identity i WHERE i.sync_space_id = ?",
            listOf(space),
        ) { decodePayload(it.getText(1), it.getText(0)).operation }
        val existing = allOperations.filter { it.agentEvent.threadIdForAudit() == replacement.value }
        val equivalentAcceptedResolutions = allOperations.filter { candidate ->
            val prior = candidate.agentEvent as? ThreadDeleteConflictResolved ?: return@filter false
            val sameDecision = prior.threadId == event.threadId && prior.resolution == event.resolution &&
                prior.replacementThreadId == event.replacementThreadId &&
                prior.participantOperationIds == event.participantOperationIds
            if (!sameDecision) return@filter false
            AgentSyncDirection.OUTBOUND in directionInTransaction(space, candidate.operationId.value) ||
                query(
                    "SELECT 1 FROM agent_sync_inbox WHERE sync_space_id = ? AND operation_id = ? AND state = 'HANDLED' LIMIT 1",
                    listOf(space, candidate.operationId.value),
                ) { it.getLong(0) }.isNotEmpty()
        }
        check(existing.all { candidate ->
            AgentCausality.observes(candidate, operation) || equivalentAcceptedResolutions.any { accepted -> AgentCausality.observes(candidate, accepted) }
        }) {
            "Replacement thread ID already has unrelated history; COPY must use a fresh thread identity."
        }
    }

    private suspend fun PooledConnection.validateReplacementThreadEvent(space: String, operation: AgentSyncOperation) {
        val threadId = operation.agentEvent.threadIdForAudit() ?: return
        val resolutions = query(
            "SELECT i.operation_id, i.payload_json FROM agent_sync_operation_identity i WHERE i.sync_space_id = ?",
            listOf(space),
        ) { decodePayload(it.getText(1), it.getText(0)).operation }.filter { candidate ->
            val event = candidate.agentEvent as? ThreadDeleteConflictResolved
            event?.resolution == AgentThreadDeleteResolution.COPY_CONTENT_TO_NEW_THREAD && event.replacementThreadId?.value == threadId
        }
        if (resolutions.isEmpty()) return
        val acceptedResolution = resolutions.any { resolution ->
            val handled = query(
                "SELECT 1 FROM agent_sync_outbox WHERE sync_space_id = ? AND operation_id = ? UNION ALL " +
                    "SELECT 1 FROM agent_sync_inbox WHERE sync_space_id = ? AND operation_id = ? AND state = 'HANDLED' LIMIT 1",
                listOf(space, resolution.operationId.value, space, resolution.operationId.value),
            ) { it.getLong(0) }.isNotEmpty()
            handled && AgentCausality.observes(operation, resolution)
        }
        if (operation.agentEvent is ThreadCreated || operation.agentEvent is MessageAppended || operation.agentEvent is TurnFinalized) {
            check(acceptedResolution) { "Replacement thread history is inactive until its COPY resolution is causally accepted." }
        }
    }

    private suspend fun PooledConnection.refreshSemanticConflictRecords(space: String, threadId: AgentThreadSyncId) {
        val operations = threadOperationsInTransaction(space, threadId)
        val activeTurns = query(
            "SELECT turn_id FROM agent_sync_active_turn_projection WHERE sync_space_id = ? AND thread_id = ?",
            listOf(space, threadId.value),
        ) { AgentTurnSyncId(it.getText(0)) }.toSet()
        val tombstoned = query("SELECT 1 FROM agent_sync_thread_tombstone WHERE sync_space_id = ? AND thread_id = ? LIMIT 1", listOf(space, threadId.value)) { it.getLong(0) }.isNotEmpty()
        val conflicts = AgentSyncMergeProjection.project(threadId, operations, activeTurns, tombstoned).conflicts
        for (conflict in conflicts) {
            val participantIds = conflict.participantOperationIds
            val firstId = participantIds.firstOrNull()?.value ?: continue
            val lastId = participantIds.lastOrNull()?.value
            val candidate = query(
                "SELECT payload_json FROM agent_sync_operation_identity WHERE sync_space_id = ? AND operation_id = ?",
                listOf(space, firstId),
            ) { it.getText(0) }.singleOrNull() ?: continue
            val metadata = buildJsonObject {
                put("state", conflict.state.name)
                putJsonArray("participantOperationIds") { participantIds.forEach { add(it.value) } }
                putJsonArray("resolutionOperationIds") { conflict.resolutionOperationIds.forEach { add(it.value) } }
            }
            val storageKey = "${conflict.localConflictKey}|${conflict.kind.name}"
            execute(
                "INSERT INTO agent_sync_conflict(conflict_id, sync_space_id, entity_kind, entity_id, conflict_kind, local_operation_id, remote_operation_id, candidate_payload_json, metadata_json) " +
                    "VALUES (?, ?, 'THREAD', ?, ?, ?, ?, ?, ?) ON CONFLICT(conflict_id) DO UPDATE SET conflict_kind = excluded.conflict_kind, " +
                    "local_operation_id = excluded.local_operation_id, remote_operation_id = excluded.remote_operation_id, candidate_payload_json = excluded.candidate_payload_json, metadata_json = excluded.metadata_json",
                listOf(storageKey, space, threadId.value, "D9_02_03_${conflict.kind.name}_${conflict.state.name}", firstId, lastId, candidate, json.encodeToString(kotlinx.serialization.json.JsonObject.serializer(), metadata)),
            )
        }
    }

    override suspend fun setAuditParentState(syncSpaceId: SyncSpaceId, actionId: String, parentKind: String, parentId: String, state: AgentSyncAuditParentState, threadId: String?) = database.withWriteTransaction {
        execute("INSERT INTO agent_sync_audit_parent_link(sync_space_id, action_id, thread_id, parent_kind, parent_id, state) VALUES (?, ?, ?, ?, ?, ?) ON CONFLICT(sync_space_id, action_id, parent_kind, parent_id) DO UPDATE SET thread_id = excluded.thread_id, state = excluded.state", listOf(syncSpaceId.value, actionId, threadId, parentKind, parentId, state.name))
    }

    override suspend fun auditParentState(syncSpaceId: SyncSpaceId, actionId: String, parentKind: String, parentId: String): AgentSyncAuditParentState? = database.useReaderConnection { connection ->
        connection.query("SELECT state FROM agent_sync_audit_parent_link WHERE sync_space_id = ? AND action_id = ? AND parent_kind = ? AND parent_id = ?", listOf(syncSpaceId.value, actionId, parentKind, parentId)) { AgentSyncAuditParentState.valueOf(it.getText(0)) }.firstOrNull()
    }

    override suspend fun backfillState(syncSpaceId: SyncSpaceId): AgentSyncBackfillState? = database.useReaderConnection { connection ->
        connection.query("SELECT cursor, recovery_state, earliest_quarantined_cursor, updated_at_epoch_millis FROM agent_sync_backfill_state WHERE sync_space_id = ?", listOf(syncSpaceId.value)) {
            AgentSyncBackfillState(it.getLong(0), AgentSyncBackfillRecoveryState.valueOf(it.getText(1)), if (it.isNull(2)) null else it.getLong(2), it.getLong(3))
        }.firstOrNull()
    }

    override suspend fun advanceBackfill(syncSpaceId: SyncSpaceId, value: AgentSyncBackfillState) = database.withWriteTransaction {
        val incomingEarliest = value.earliestQuarantinedCursor
        require(value.cursor >= 0 && (incomingEarliest == null || incomingEarliest >= 0))
        val old = query(
            "SELECT cursor, recovery_state, earliest_quarantined_cursor FROM agent_sync_backfill_state WHERE sync_space_id = ?",
            listOf(syncSpaceId.value),
        ) { Triple(it.getLong(0), AgentSyncBackfillRecoveryState.valueOf(it.getText(1)), if (it.isNull(2)) null else it.getLong(2)) }.singleOrNull()
        val previousCursor = old?.first
        val previousGenerationOpen = old == null || old.second != AgentSyncBackfillRecoveryState.COMPLETE
        val previousEarliest = old?.third
        val earlierQuarantineDiscovered = incomingEarliest != null &&
            (previousEarliest == null || incomingEarliest < previousEarliest)

        // A recovery generation retains the minimum quarantine cursor. Discovering a
        // lower cursor explicitly restarts from its predecessor, even though this is
        // the one permitted transition that may move the progress cursor backwards.
        val earliestQuarantinedCursor = when {
            old == null || !previousGenerationOpen -> incomingEarliest
            previousEarliest == null -> incomingEarliest
            incomingEarliest == null -> previousEarliest
            else -> minOf(previousEarliest, incomingEarliest)
        }
        val restartCursor = earliestQuarantinedCursor?.let { maxOf(0L, it - 1L) }
        val nextCursor = if (earlierQuarantineDiscovered && restartCursor != null) {
            minOf(value.cursor, previousCursor ?: value.cursor, restartCursor)
        } else {
            require(previousCursor == null || value.cursor >= previousCursor) { "Agent V3 backfill cursor cannot move backwards without discovering an earlier quarantine." }
            value.cursor
        }
        val nextRecoveryState = if (earlierQuarantineDiscovered) AgentSyncBackfillRecoveryState.RUNNING else value.recoveryState
        execute(
            "INSERT INTO agent_sync_backfill_state(sync_space_id, cursor, recovery_state, earliest_quarantined_cursor, updated_at_epoch_millis) VALUES (?, ?, ?, ?, ?) " +
                "ON CONFLICT(sync_space_id) DO UPDATE SET cursor = excluded.cursor, recovery_state = excluded.recovery_state, earliest_quarantined_cursor = excluded.earliest_quarantined_cursor, updated_at_epoch_millis = excluded.updated_at_epoch_millis",
            listOf(syncSpaceId.value, nextCursor, nextRecoveryState.name, earliestQuarantinedCursor, value.updatedAtEpochMillis),
        )
    }

    private suspend fun PooledConnection.frontierInTransaction(space: String): Map<AgentReplicaId, Long> =
        query("SELECT agent_replica_id, counter FROM agent_sync_dvv_frontier WHERE sync_space_id = ? ORDER BY agent_replica_id", listOf(space)) {
            AgentReplicaId(it.getText(0)) to it.getLong(1)
        }.toMap()

    private suspend fun PooledConnection.directionInTransaction(space: String, operationId: String): Set<AgentSyncDirection> = buildSet {
        if (query("SELECT 1 FROM agent_sync_inbox WHERE sync_space_id = ? AND operation_id = ?", listOf(space, operationId)) { it.getLong(0) }.isNotEmpty()) add(AgentSyncDirection.INBOUND)
        if (query("SELECT 1 FROM agent_sync_outbox WHERE sync_space_id = ? AND operation_id = ?", listOf(space, operationId)) { it.getLong(0) }.isNotEmpty()) add(AgentSyncDirection.OUTBOUND)
    }

    /** Advance only through durable local outbox dots or handled inbound dots; absence means -1. */
    private suspend fun PooledConnection.advanceAgentFrontier(space: String, replicaId: AgentReplicaId) {
        var frontier = query("SELECT counter FROM agent_sync_dvv_frontier WHERE sync_space_id = ? AND agent_replica_id = ?", listOf(space, replicaId.value)) { it.getLong(0) }.firstOrNull() ?: -1L
        while (frontier < Long.MAX_VALUE) {
            val next = frontier + 1L
            val durable = query(
                "SELECT 1 FROM agent_sync_handled_dot h WHERE h.sync_space_id = ? AND h.agent_replica_id = ? AND h.counter = ? " +
                    "UNION ALL SELECT 1 FROM agent_sync_operation_identity i JOIN agent_sync_outbox o USING(sync_space_id, operation_id) " +
                    "WHERE i.sync_space_id = ? AND i.agent_replica_id = ? AND i.agent_counter = ? LIMIT 1",
                listOf(space, replicaId.value, next, space, replicaId.value, next),
            ) { it.getLong(0) }.isNotEmpty()
            if (!durable) break
            frontier = next
        }
        if (frontier < 0L) return
        execute(
            "INSERT INTO agent_sync_dvv_frontier(sync_space_id, agent_replica_id, counter) VALUES (?, ?, ?) " +
                "ON CONFLICT(sync_space_id, agent_replica_id) DO UPDATE SET counter = excluded.counter",
            listOf(space, replicaId.value, frontier),
        )
        resolveCoveredAgentDotDependencies(space, replicaId, frontier)
    }

    /** Release every context component covered by the newly persisted contiguous frontier. */
    private suspend fun PooledConnection.resolveCoveredAgentDotDependencies(space: String, replicaId: AgentReplicaId, frontier: Long) {
        val covered = query(
            "SELECT operation_id, dependency_key FROM agent_sync_pending_dependency WHERE sync_space_id = ? AND dependency_kind = 'AGENT_DOT'",
            listOf(space),
        ) { it.getText(0) to it.getText(1) }.filter { (_, key) ->
            val dependency = key.parseAgentDotKeyOrNull()
            dependency != null && dependency.first == replicaId && dependency.second <= frontier
        }
        covered.forEach { (operationId, key) ->
            execute("DELETE FROM agent_sync_pending_dependency WHERE sync_space_id = ? AND operation_id = ? AND dependency_kind = 'AGENT_DOT' AND dependency_key = ?", listOf(space, operationId, key))
            execute("UPDATE agent_sync_outbox SET state = 'READY' WHERE sync_space_id = ? AND operation_id = ? AND state = 'HELD' AND NOT EXISTS (SELECT 1 FROM agent_sync_pending_dependency WHERE sync_space_id = ? AND operation_id = ?)", listOf(space, operationId, space, operationId))
            recomputeTurnsForOperation(space, operationId)
        }
    }

    private suspend fun PooledConnection.recomputeTurnsForOperation(space: String, operationId: String) {
        val turns = query(
            "SELECT turn_id FROM agent_sync_turn_stage WHERE sync_space_id = ? AND manifest_operation_id = ? " +
                "UNION SELECT m.turn_id FROM agent_sync_turn_member m JOIN agent_sync_operation_identity i " +
                "ON i.sync_space_id = m.sync_space_id AND i.immutable_record_kind = m.member_kind AND i.immutable_record_id = m.member_id " +
                "WHERE i.sync_space_id = ? AND i.operation_id = ?",
            listOf(space, operationId, space, operationId),
        ) { it.getText(0) }
        turns.forEach { recomputeTurnState(space, it) }
    }

    private suspend fun PooledConnection.persistEventIndexes(space: SyncSpaceId, operation: AgentSyncOperation, direction: AgentSyncDirection) {
        val event = operation.agentEvent
        operation.agentDvv.context.forEach { component ->
            val frontier = query("SELECT counter FROM agent_sync_dvv_frontier WHERE sync_space_id = ? AND agent_replica_id = ?", listOf(space.value, component.replicaId.value)) { it.getLong(0) }.firstOrNull() ?: -1L
            if (frontier < component.counter) addDependency(space.value, operation.operationId.value, AgentSyncDependencyKind.AGENT_DOT.name, "${component.replicaId.value}:${component.counter}")
        }
        event.businessMutationIdsForPersistence().forEach { addDependency(space.value, operation.operationId.value, AgentSyncDependencyKind.BUSINESS_MUTATION.name, it) }
        if (event is TurnFinalized) event.parentTurnIds.forEach { addDependency(space.value, operation.operationId.value, AgentSyncDependencyKind.PARENT_TURN.name, it.value) }
        if (direction == AgentSyncDirection.OUTBOUND) {
            execute("UPDATE agent_sync_outbox SET state = 'HELD' WHERE sync_space_id = ? AND operation_id = ? AND EXISTS (SELECT 1 FROM agent_sync_pending_dependency WHERE sync_space_id = ? AND operation_id = ?)", listOf(space.value, operation.operationId.value, space.value, operation.operationId.value))
            return
        }
        if (direction == AgentSyncDirection.INBOUND) {
            val threadId = event.threadIdForConflictCheck()
            if (threadId != null && event.isContentEvent()) {
                val tombstones = query("SELECT operation_id, dvv_json FROM agent_sync_thread_tombstone WHERE sync_space_id = ? AND thread_id = ?", listOf(space.value, threadId)) { it.getText(0) to it.getText(1) }
                tombstones.forEach { (deleteOperationId, deleteDvvJson) ->
                    val deleteDvv = json.decodeFromString(AgentDvvSnapshot.serializer(), deleteDvvJson)
                    val precedesEvent = deleteDvv.dot.replicaId == operation.agentDvv.dot.replicaId && deleteDvv.dot.counter > operation.agentDvv.dot.counter ||
                        deleteDvv.context.singleOrNull { it.replicaId == operation.agentDvv.dot.replicaId }?.counter?.let { it >= operation.agentDvv.dot.counter } == true
                    if (!precedesEvent) recordTombstoneAppendConflict(space.value, threadId, deleteOperationId, operation)
                }
            }
        }
        when (event) {
            // Inbound deletes remain immutable staged facts until markHandled proves
            // their causal dependencies. Authoritative tombstone materialization is
            // performed in that same handling transaction.
            is ThreadDeleted -> Unit
            is TurnFinalized -> {
                execute("INSERT INTO agent_sync_turn_stage(sync_space_id, turn_id, thread_id, manifest_operation_id, outcome, state) VALUES (?, ?, ?, ?, ?, 'INCOMPLETE') ON CONFLICT(sync_space_id, turn_id) DO NOTHING", listOf(space.value, event.turnId.value, event.threadId.value, operation.operationId.value, event.outcome.name))
                event.orderedMembers.forEachIndexed { index, member ->
                    val identity = member.identity()
                    execute("INSERT INTO agent_sync_turn_member(sync_space_id, turn_id, member_ordinal, member_kind, member_id, present) VALUES (?, ?, ?, ?, ?, 0)", listOf(space.value, event.turnId.value, index.toLong(), identity.first, identity.second))
                    val exists = query("SELECT 1 FROM agent_sync_operation_identity i JOIN agent_sync_inbox b USING(sync_space_id, operation_id) JOIN agent_sync_handled_dot h USING(sync_space_id, operation_id) WHERE i.sync_space_id = ? AND i.immutable_record_kind = ? AND i.immutable_record_id = ?", listOf(space.value, identity.first, identity.second)) { it.getLong(0) }.isNotEmpty()
                    if (exists) execute("UPDATE agent_sync_turn_member SET present = 1 WHERE sync_space_id = ? AND turn_id = ? AND member_ordinal = ?", listOf(space.value, event.turnId.value, index.toLong()))
                }
                recomputeTurnState(space.value, event.turnId.value)
            }
            is ActionFinalized -> {
                val threadId = event.threadId
                val parentState = if (threadId != null && query("SELECT 1 FROM agent_sync_thread_tombstone WHERE sync_space_id = ? AND thread_id = ? LIMIT 1", listOf(space.value, threadId.value)) { it.getLong(0) }.isNotEmpty()) AgentSyncAuditParentState.PARENT_REMOVED_BY_TOMBSTONE.name else AgentSyncAuditParentState.PARENT_PENDING.name
                val parents = buildList {
                    event.turnId?.let { add("TURN" to it.value) }
                    event.sourceMessageId?.let { add("MESSAGE" to it.value) }
                    event.toolCallIds.forEach { add("TOOL_CALL" to it.value) }
                    event.toolResultIds.forEach { add("TOOL_RESULT" to it.value) }
                }
                parents.forEach { (kind, id) ->
                    execute("INSERT INTO agent_sync_audit_parent_link(sync_space_id, action_id, thread_id, parent_kind, parent_id, state) VALUES (?, ?, ?, ?, ?, ?) ON CONFLICT(sync_space_id, action_id, parent_kind, parent_id) DO NOTHING", listOf(space.value, event.actionId.value, threadId?.value, kind, id, parentState))
                    if (parentState == AgentSyncAuditParentState.PARENT_PENDING.name) markAuditParentsForHandledRecord(space.value, kind, id)
                }
                buildList {
                    event.sourceMessageId?.let { add(it.value) }
                    event.toolCallIds.forEach { add(it.value) }
                    event.toolResultIds.forEach { add(it.value) }
                }.distinct().forEach { parentId ->
                    val resolved = query("SELECT 1 FROM agent_sync_operation_identity i JOIN agent_sync_inbox b USING(sync_space_id, operation_id) JOIN agent_sync_handled_dot h USING(sync_space_id, operation_id) WHERE i.sync_space_id = ? AND i.immutable_record_id = ? LIMIT 1", listOf(space.value, parentId)) { it.getLong(0) }.isNotEmpty()
                    if (!resolved) addDependency(space.value, operation.operationId.value, AgentSyncDependencyKind.PARENT_RECORD.name, parentId)
                }
            }
            is ToolResultAppended -> Unit
            else -> {
                val identity = event.immutableRecordIdentity()
                if (identity != null) {
                    val handled = query("SELECT 1 FROM agent_sync_operation_identity i JOIN agent_sync_inbox b USING(sync_space_id, operation_id) JOIN agent_sync_handled_dot h USING(sync_space_id, operation_id) WHERE i.sync_space_id = ? AND i.immutable_record_kind = ? AND i.immutable_record_id = ?", listOf(space.value, identity.first, identity.second)) { it.getLong(0) }.isNotEmpty()
                    if (handled) execute("UPDATE agent_sync_turn_member SET present = 1 WHERE sync_space_id = ? AND member_kind = ? AND member_id = ?", listOf(space.value, identity.first, identity.second))
                    query("SELECT turn_id FROM agent_sync_turn_member WHERE sync_space_id = ? AND member_kind = ? AND member_id = ?", listOf(space.value, identity.first, identity.second)) { it.getText(0) }.forEach { recomputeTurnState(space.value, it) }
                }
            }
        }
    }

    private suspend fun PooledConnection.recomputeTurnState(space: String, turnId: String) {
        val stage = query("SELECT manifest_operation_id, thread_id, state FROM agent_sync_turn_stage WHERE sync_space_id = ? AND turn_id = ?", listOf(space, turnId)) { Triple(it.getText(0), it.getText(1), it.getText(2)) }.singleOrNull() ?: return
        val op = stage.first
        if (stage.third == AgentSyncTurnState.TOMBSTONED.name) return
        if (query("SELECT 1 FROM agent_sync_thread_tombstone WHERE sync_space_id = ? AND thread_id = ? LIMIT 1", listOf(space, stage.second)) { it.getLong(0) }.isNotEmpty()) {
            execute("DELETE FROM agent_sync_active_turn_projection WHERE sync_space_id = ? AND turn_id = ?", listOf(space, turnId))
            execute("UPDATE agent_sync_turn_stage SET state = 'TOMBSTONED' WHERE sync_space_id = ? AND turn_id = ?", listOf(space, turnId))
            return
        }
        val manifestHandled = query(
            "SELECT 1 FROM agent_sync_inbox b JOIN agent_sync_handled_dot h USING(sync_space_id, operation_id) " +
                "WHERE b.sync_space_id = ? AND b.operation_id = ? AND b.state = 'HANDLED' LIMIT 1",
            listOf(space, op),
        ) { it.getLong(0) }.isNotEmpty()
        val incomplete = !manifestHandled || query("SELECT count(*) FROM agent_sync_turn_member WHERE sync_space_id = ? AND turn_id = ? AND present = 0", listOf(space, turnId)) { it.getLong(0) }.single() > 0
        val dependencies = query("SELECT count(*) FROM agent_sync_pending_dependency WHERE sync_space_id = ? AND operation_id IN (SELECT operation_id FROM agent_sync_operation_identity WHERE sync_space_id = ? AND (operation_id = ? OR immutable_record_id IN (SELECT member_id FROM agent_sync_turn_member WHERE sync_space_id = ? AND turn_id = ?)))", listOf(space, space, op, space, turnId)) { it.getLong(0) }.single() > 0
        val parents = query("SELECT count(*) FROM agent_sync_pending_dependency WHERE sync_space_id = ? AND operation_id = ? AND dependency_kind = 'PARENT_TURN'", listOf(space, op)) { it.getLong(0) }.single() > 0
        val linkError = if (incomplete || dependencies || parents) null else turnLinkError(space, turnId)
        if (linkError != null) recordTurnLinkConflict(space, turnId, op, linkError)
        val next = if (incomplete || dependencies || parents || linkError != null) "INCOMPLETE" else "COMPLETE_VERIFIED"
        execute("UPDATE agent_sync_turn_stage SET state = ? WHERE sync_space_id = ? AND turn_id = ?", listOf(next, space, turnId))
    }

    /** Commit the authoritative delete only after its inbound operation is causally handled. */
    private suspend fun PooledConnection.materializeThreadDelete(space: String, operation: AgentSyncOperation) {
        val event = operation.agentEvent as? ThreadDeleted ?: error("Expected a handled ThreadDeleted operation.")
        execute(
            "INSERT INTO agent_sync_thread_tombstone(sync_space_id, thread_id, operation_id, agent_replica_id, counter, dvv_json) VALUES (?, ?, ?, ?, ?, ?) ON CONFLICT(sync_space_id, operation_id) DO NOTHING",
            listOf(space, event.threadId.value, operation.operationId.value, operation.agentDvv.dot.replicaId.value, operation.agentDvv.dot.counter, encodeDvv(operation.agentDvv)),
        )
        execute("DELETE FROM agent_sync_active_turn_projection WHERE sync_space_id = ? AND thread_id = ?", listOf(space, event.threadId.value))
        execute("UPDATE agent_sync_turn_stage SET state = 'TOMBSTONED' WHERE sync_space_id = ? AND thread_id = ?", listOf(space, event.threadId.value))
        execute(
            "UPDATE agent_sync_audit_parent_link SET state = 'PARENT_REMOVED_BY_TOMBSTONE' WHERE sync_space_id = ? AND thread_id = ? AND state != 'PARENT_REMOVED_BY_TOMBSTONE'",
            listOf(space, event.threadId.value),
        )
    }

    private suspend fun PooledConnection.turnLinkError(space: String, turnId: String): String? {
        val manifestRow = query("SELECT s.manifest_operation_id, i.payload_json FROM agent_sync_turn_stage s JOIN agent_sync_operation_identity i ON i.sync_space_id = s.sync_space_id AND i.operation_id = s.manifest_operation_id WHERE s.sync_space_id = ? AND s.turn_id = ?", listOf(space, turnId)) { it.getText(0) to it.getText(1) }.singleOrNull() ?: return "Missing TurnFinalized manifest operation."
        val manifest = decodePayload(manifestRow.second, manifestRow.first).operation.agentEvent as? TurnFinalized ?: return "Manifest operation does not contain TurnFinalized."
        val events = query("SELECT m.member_kind, i.payload_json, i.operation_id FROM agent_sync_turn_member m JOIN agent_sync_operation_identity i ON i.sync_space_id = m.sync_space_id AND i.immutable_record_kind = m.member_kind AND i.immutable_record_id = m.member_id JOIN agent_sync_inbox b ON b.sync_space_id = i.sync_space_id AND b.operation_id = i.operation_id JOIN agent_sync_handled_dot h ON h.sync_space_id = i.sync_space_id AND h.operation_id = i.operation_id WHERE m.sync_space_id = ? AND m.turn_id = ?", listOf(space, turnId)) { Triple(it.getText(0), it.getText(1), it.getText(2)) }
        val operations = events.map { decodePayload(it.second, it.third).operation.agentEvent }
        val actions = operations.filterIsInstance<ActionFinalized>().associateBy { it.actionId }
        val messages = operations.filterIsInstance<MessageAppended>().associateBy { it.messageId }
        val calls = operations.filterIsInstance<ToolCallFinalized>().associateBy { it.callId }
        val results = operations.filterIsInstance<ToolResultAppended>().associateBy { it.resultId }
        return when (val validation = AgentTurnLinkValidator.validate(manifest, actions, messages, calls, results)) {
            AgentTurnLinkValidation.Valid -> null
            else -> validation.toString()
        }
    }

    private suspend fun PooledConnection.recordTurnLinkConflict(space: String, turnId: String, operationId: String, reason: String) {
        val candidate = query("SELECT payload_json FROM agent_sync_operation_identity WHERE sync_space_id = ? AND operation_id = ?", listOf(space, operationId)) { it.getText(0) }.singleOrNull() ?: return
        val escaped = reason.replace("\\", "\\\\").replace("\"", "\\\"")
        execute("INSERT INTO agent_sync_conflict(conflict_id, sync_space_id, entity_kind, entity_id, conflict_kind, local_operation_id, remote_operation_id, candidate_payload_json, metadata_json) VALUES (?, ?, 'TURN', ?, 'TURN_LINK_INTEGRITY_CONFLICT', NULL, ?, ?, ?) ON CONFLICT(conflict_id) DO NOTHING", listOf("$space:$turnId:turn-link", space, turnId, operationId, candidate, "{\"reason\":\"$escaped\"}"))
    }

    private suspend fun PooledConnection.markAuditParentsForHandledRecord(space: String, kind: String, id: String) {
        val candidates = query("SELECT i.operation_id, i.payload_json FROM agent_sync_operation_identity i JOIN agent_sync_inbox b USING(sync_space_id, operation_id) JOIN agent_sync_handled_dot h USING(sync_space_id, operation_id) WHERE i.sync_space_id = ? AND i.immutable_record_kind = ? AND i.immutable_record_id = ?", listOf(space, kind, id)) { it.getText(0) to it.getText(1) }
        if (candidates.isEmpty()) return
        val parentThread = candidates.asSequence().mapNotNull { (operationId, payload) ->
            decodePayload(payload, operationId).operation.agentEvent.threadIdForAudit()
        }.firstOrNull() ?: return
        val links = query("SELECT action_id, parent_kind, parent_id FROM agent_sync_audit_parent_link WHERE sync_space_id = ? AND thread_id = ? AND parent_kind = ? AND parent_id = ? AND state = 'PARENT_PENDING'", listOf(space, parentThread, kind, id)) { Triple(it.getText(0), it.getText(1), it.getText(2)) }
        if (links.isEmpty()) return
        val state = if (query("SELECT 1 FROM agent_sync_thread_tombstone WHERE sync_space_id = ? AND thread_id = ? LIMIT 1", listOf(space, parentThread)) { it.getLong(0) }.isNotEmpty()) AgentSyncAuditParentState.PARENT_REMOVED_BY_TOMBSTONE.name else AgentSyncAuditParentState.PARENT_VERIFIED.name
        links.forEach { (actionId, parentKind, parentId) ->
            execute("UPDATE agent_sync_audit_parent_link SET state = ? WHERE sync_space_id = ? AND action_id = ? AND parent_kind = ? AND parent_id = ? AND state = 'PARENT_PENDING'", listOf(state, space, actionId, parentKind, parentId))
        }
    }

    private suspend fun PooledConnection.addDependency(space: String, operationId: String, kind: String, key: String) {
        execute("INSERT INTO agent_sync_pending_dependency(sync_space_id, operation_id, dependency_kind, dependency_key) VALUES (?, ?, ?, ?) ON CONFLICT DO NOTHING", listOf(space, operationId, kind, key))
    }

    private suspend fun PooledConnection.addDirection(space: SyncSpaceId, operationId: String, direction: AgentSyncDirection) {
        when (direction) {
            AgentSyncDirection.INBOUND -> execute("INSERT INTO agent_sync_inbox(sync_space_id, operation_id, state) VALUES (?, ?, 'PENDING') ON CONFLICT DO NOTHING", listOf(space.value, operationId))
            AgentSyncDirection.OUTBOUND -> execute("INSERT INTO agent_sync_outbox(sync_space_id, operation_id, state) VALUES (?, ?, 'READY') ON CONFLICT DO NOTHING", listOf(space.value, operationId))
        }
    }

    private suspend fun PooledConnection.recordIntegrityConflict(space: SyncSpaceId, operation: AgentSyncOperation, record: Pair<String, String>?, incumbent: String, encoded: String) {
        val ref = record ?: operation.agentEvent.immutableRecordIdentity() ?: ("THREAD" to operation.operationId.value)
        execute("INSERT INTO agent_sync_conflict(conflict_id, sync_space_id, entity_kind, entity_id, conflict_kind, local_operation_id, remote_operation_id, candidate_payload_json, metadata_json) VALUES (?, ?, ?, ?, 'IMMUTABLE_IDENTITY_CONFLICT', ?, ?, ?, ?) ON CONFLICT(conflict_id) DO NOTHING", listOf("${space.value}:${operation.operationId.value}:${operation.agentDvv.dot.counter}", space.value, ref.first, ref.second, incumbent, operation.operationId.value, encoded, "{\"payloadBytes\":${encoded.encodeToByteArray().size}}"))
    }

    private fun decodeOperation(encoded: String, operationId: String): AgentSyncOperation = decodePayload(encoded, operationId).operation
    private fun decodePayload(encoded: String, operationId: String): SyncPayloadV3 = when (val decoded = AgentSyncWireCodec.decodePayload(encoded, MutationId(operationId))) {
        is AgentPayloadDecodeResult.Supported -> decoded.payload
        else -> throw SerializationException("Persisted typed V3 operation failed codec validation: $decoded")
    }

    private fun encodeDvv(value: AgentDvvSnapshot): String = json.encodeToString(AgentDvvSnapshot.serializer(), value)

    private suspend fun PooledConnection.recordTombstoneAppendConflict(space: String, threadId: String, tombstoneOperationId: String, operation: AgentSyncOperation) {
        val candidate = AgentSyncWireCodec.encodePayload(SyncPayloadV3(operation = operation))
        execute("INSERT INTO agent_sync_conflict(conflict_id, sync_space_id, entity_kind, entity_id, conflict_kind, local_operation_id, remote_operation_id, candidate_payload_json, metadata_json) VALUES (?, ?, 'THREAD', ?, 'THREAD_DELETE_APPEND_CONFLICT', ?, ?, ?, '{\"reason\":\"append is not causally before tombstone\"}') ON CONFLICT(conflict_id) DO NOTHING", listOf("$space:$threadId:delete-append:${operation.operationId.value}", space, threadId, tombstoneOperationId, operation.operationId.value, candidate))
    }

    private data class IdentityRow(val operationId: String, val replicaId: String, val counter: Long, val recordKind: String?, val recordId: String?, val payloadJson: String)
    private sealed interface LocalEnqueueOutcome {
        data class Success(val operation: AgentSyncOperation) : LocalEnqueueOutcome
        data class Conflict(val message: String) : LocalEnqueueOutcome
    }
    private sealed interface PersistOutcome {
        data class Success(val result: AgentSyncPersistResult) : PersistOutcome
        data class Conflict(val message: String) : PersistOutcome
    }
}

private fun AgentSyncEvent.immutableRecordIdentity(): Pair<String, String>? = when (this) {
    is ThreadCreated -> "THREAD" to threadId.value
    is MessageAppended -> "MESSAGE" to messageId.value
    is ToolCallFinalized -> "TOOL_CALL" to callId.value
    is ToolResultAppended -> "TOOL_RESULT" to resultId.value
    is ActionFinalized -> "ACTION" to actionId.value
    is TurnFinalized -> "TURN" to turnId.value
    is ThreadTitleSet, is ThreadDeleted, is ThreadDeleteConflictResolved -> null
}

private fun AgentSyncEvent.threadIdForConflictCheck(): String? = when (this) {
    is ThreadCreated -> threadId.value
    is ThreadTitleSet -> threadId.value
    is MessageAppended -> threadId.value
    is ToolCallFinalized -> threadId.value
    is ToolResultAppended -> threadId.value
    is ActionFinalized -> null
    is TurnFinalized -> threadId.value
    is ThreadDeleted -> null
    is ThreadDeleteConflictResolved -> threadId.value
}

private fun AgentSyncEvent.threadIdForAudit(): String? = when (this) {
    is ThreadCreated -> threadId.value
    is ThreadTitleSet -> threadId.value
    is MessageAppended -> threadId.value
    is ToolCallFinalized -> threadId.value
    is ToolResultAppended -> threadId.value
    is ActionFinalized -> threadId?.value
    is TurnFinalized -> threadId.value
    is ThreadDeleted -> threadId.value
    is ThreadDeleteConflictResolved -> threadId.value
}

private fun AgentSyncEvent.isContentEvent(): Boolean = when (this) {
    is ThreadCreated, is ThreadTitleSet, is MessageAppended, is ToolCallFinalized, is ToolResultAppended, is TurnFinalized -> true
    is ActionFinalized, is ThreadDeleted, is ThreadDeleteConflictResolved -> false
}

private fun AgentSyncEvent.businessMutationIdsForPersistence(): List<String> = when (this) {
    is ToolResultAppended -> businessMutationIds.map { it.value }
    is ActionFinalized -> businessMutationIds.map { it.value }
    else -> emptyList()
}

private fun AgentSyncOperation.requireSequentialAuthorContext() {
    val dot = agentDvv.dot
    if (dot.counter == 0L) return
    val authorPrevious = agentDvv.context.singleOrNull { it.replicaId == dot.replicaId }?.counter
    require(authorPrevious == dot.counter - 1L) {
        "An Agent dot above zero must causally observe its author's immediately preceding counter."
    }
}

private fun AgentSyncPendingDependency.parseAgentDotKey(): Pair<AgentReplicaId, Long> =
    key.parseAgentDotKeyOrNull() ?: throw IllegalArgumentException("Agent-dot dependency key must be '<AgentReplicaId>:<counter>'.")

private fun String.parseAgentDotKeyOrNull(): Pair<AgentReplicaId, Long>? {
    val separator = lastIndexOf(':')
    if (separator <= 0 || separator == lastIndex) return null
    val replica = runCatching { AgentReplicaId(substring(0, separator)) }.getOrNull() ?: return null
    val counter = substring(separator + 1).toLongOrNull()?.takeIf { it >= 0L } ?: return null
    return replica to counter
}

private fun TurnMemberReference.identity(): Pair<String, String> = when (this) {
    is MessageMember -> "MESSAGE" to id.value
    is ToolCallMember -> "TOOL_CALL" to id.value
    is ToolResultMember -> "TOOL_RESULT" to id.value
    is ActionMember -> "ACTION" to id.value
}

private suspend fun PooledConnection.execute(sql: String, args: List<Any?> = emptyList()) = usePrepared(sql) { statement ->
    statement.bind(args)
    statement.step()
    Unit
}

private suspend fun <T> PooledConnection.query(sql: String, args: List<Any?> = emptyList(), decode: (SQLiteStatement) -> T): List<T> = usePrepared(sql) { statement ->
    statement.bind(args)
    buildList { while (statement.step()) add(decode(statement)) }
}

private fun SQLiteStatement.bind(args: List<Any?>) {
    args.forEachIndexed { index, value -> when (value) {
        null -> bindNull(index + 1)
        is String -> bindText(index + 1, value)
        is Long -> bindLong(index + 1, value)
        is Int -> bindLong(index + 1, value.toLong())
        else -> error("Unsupported Agent sync SQL binding type.")
    } }
}
