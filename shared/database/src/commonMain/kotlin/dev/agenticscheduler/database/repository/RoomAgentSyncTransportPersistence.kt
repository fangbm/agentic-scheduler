package dev.agenticscheduler.database.repository

import androidx.room3.PooledConnection
import androidx.room3.useReaderConnection
import androidx.room3.withWriteTransaction
import androidx.sqlite.SQLiteStatement
import dev.agenticscheduler.application.sync.*
import dev.agenticscheduler.database.AgenticSchedulerDatabase
import dev.agenticscheduler.sync.*

class RoomAgentSyncTransportPersistence(private val database: AgenticSchedulerDatabase) : AgentSyncTransportPersistence, AgentSyncUserConsentPersistence {
    override suspend fun conversationConsent(syncSpaceId: SyncSpaceId): Boolean = database.useReaderConnection {
        it.transportQuery("SELECT enabled FROM agent_sync_transport_consent WHERE sync_space_id = ?", listOf(syncSpaceId.value)) { row -> row.getLong(0) == 1L }.singleOrNull() ?: false
    }

    override suspend fun setConversationConsent(syncSpaceId: SyncSpaceId, enabled: Boolean, allActiveDevicesV3Acknowledged: Boolean): Unit = database.withWriteTransaction {
        require(!enabled || allActiveDevicesV3Acknowledged) { "Conversation sync requires the owner's acknowledgement that all active devices support V3." }
        transportExecute("INSERT INTO agent_sync_transport_consent VALUES (?, ?, ?) ON CONFLICT(sync_space_id) DO UPDATE SET enabled = excluded.enabled, all_active_devices_v3_acknowledged = excluded.all_active_devices_v3_acknowledged",
            listOf(syncSpaceId.value, if (enabled) 1L else 0L, if (allActiveDevicesV3Acknowledged) 1L else 0L))
        if (!enabled) transportExecute("UPDATE agent_sync_outbox SET state = 'HELD' WHERE sync_space_id = ? AND state != 'UPLOADED'", listOf(syncSpaceId.value))
    }

    override suspend fun outboundRecords(syncSpaceId: SyncSpaceId): List<AgentOutboundTransportRecord> = database.useReaderConnection {
        it.transportQuery("SELECT i.operation_id, i.payload_json, o.state, e.envelope_json FROM agent_sync_operation_identity i JOIN agent_sync_outbox o USING(sync_space_id, operation_id) LEFT JOIN agent_sync_outbound_envelope e USING(sync_space_id, operation_id) WHERE i.sync_space_id = ? ORDER BY i.agent_replica_id, i.agent_counter, i.operation_id", listOf(syncSpaceId.value)) { row ->
            AgentOutboundTransportRecord(decode(row.getText(1), row.getText(0)), AgentOutboundTransportState.valueOf(row.getText(2)),
                if (row.isNull(3)) null else (SyncWireCodec.decodeEnvelope(row.getText(3)) as EnvelopeDecodeResult.Supported).envelope)
        }
    }

    override suspend fun retainEnvelopes(syncSpaceId: SyncSpaceId, envelopes: List<EncryptedEnvelopeV1>): List<EncryptedEnvelopeV1> = database.withWriteTransaction {
        envelopes.map { envelope ->
            require(envelope.syncSpaceId == syncSpaceId)
            check(transportQuery("SELECT 1 FROM agent_sync_outbox WHERE sync_space_id = ? AND operation_id = ?", listOf(syncSpaceId.value, envelope.mutationId)) { it.getLong(0) }.isNotEmpty())
            val old = transportQuery("SELECT envelope_json FROM agent_sync_outbound_envelope WHERE sync_space_id = ? AND operation_id = ?", listOf(syncSpaceId.value, envelope.mutationId)) { it.getText(0) }.singleOrNull()
            if (old != null) (SyncWireCodec.decodeEnvelope(old) as EnvelopeDecodeResult.Supported).envelope
            else {
                transportExecute("INSERT INTO agent_sync_outbound_envelope VALUES (?, ?, ?)", listOf(syncSpaceId.value, envelope.mutationId, SyncWireCodec.encodeEnvelope(envelope)))
                envelope
            }
        }
    }

    override suspend fun setHeld(syncSpaceId: SyncSpaceId, operationIds: List<String>, held: Boolean): Unit = database.withWriteTransaction {
        operationIds.forEach { id -> transportExecute("UPDATE agent_sync_outbox SET state = ? WHERE sync_space_id = ? AND operation_id = ? AND state != 'UPLOADED'", listOf(if (held) "HELD" else "READY", syncSpaceId.value, id)) }
    }

    override suspend fun markUploaded(syncSpaceId: SyncSpaceId, operationId: String): Unit = database.withWriteTransaction {
        check(transportQuery("SELECT 1 FROM agent_sync_outbound_envelope WHERE sync_space_id = ? AND operation_id = ?", listOf(syncSpaceId.value, operationId)) { it.getLong(0) }.isNotEmpty())
        transportExecute("UPDATE agent_sync_outbox SET state = 'UPLOADED' WHERE sync_space_id = ? AND operation_id = ?", listOf(syncSpaceId.value, operationId))
    }

    override suspend fun pendingInboundOperations(syncSpaceId: SyncSpaceId): List<AgentSyncOperation> = database.useReaderConnection {
        it.transportQuery("SELECT i.operation_id, i.payload_json FROM agent_sync_operation_identity i JOIN agent_sync_inbox b USING(sync_space_id, operation_id) WHERE i.sync_space_id = ? AND b.state = 'PENDING' ORDER BY i.agent_replica_id, i.agent_counter", listOf(syncSpaceId.value)) { row -> decode(row.getText(1), row.getText(0)) }
    }

    override suspend fun completedInboundTurnIds(syncSpaceId: SyncSpaceId): List<String> = database.useReaderConnection {
        it.transportQuery("SELECT s.turn_id FROM agent_sync_turn_stage s WHERE s.sync_space_id = ? AND s.state = 'COMPLETE_VERIFIED' AND NOT EXISTS (SELECT 1 FROM agent_sync_active_turn_projection a WHERE a.sync_space_id = s.sync_space_id AND a.turn_id = s.turn_id) ORDER BY s.turn_id", listOf(syncSpaceId.value)) { row -> row.getText(0) }
    }

    override suspend fun earliestUnrecoveredV3Quarantine(syncSpaceId: SyncSpaceId): Long? = database.useReaderConnection {
        it.transportQuery("SELECT MIN(q.server_cursor) FROM protocol_quarantine q LEFT JOIN agent_sync_operation_identity i ON i.sync_space_id = q.sync_space_id AND i.operation_id = q.mutation_id WHERE q.sync_space_id = ? AND q.reason = 'UNSUPPORTED_PAYLOAD_VERSION' AND q.detail = '3' AND i.operation_id IS NULL", listOf(syncSpaceId.value)) { row -> if (row.isNull(0)) null else row.getLong(0) }.single()
    }

    private fun decode(payload: String, id: String): AgentSyncOperation = (AgentSyncWireCodec.decodePayload(payload, MutationId(id)) as AgentPayloadDecodeResult.Supported).payload.operation
}

private suspend fun PooledConnection.transportExecute(sql: String, args: List<Any?>) = usePrepared(sql) { stmt -> stmt.transportBind(args); stmt.step(); Unit }
private suspend fun <T> PooledConnection.transportQuery(sql: String, args: List<Any?>, mapper: (SQLiteStatement) -> T): List<T> = usePrepared(sql) { stmt ->
    stmt.transportBind(args)
    buildList { while (stmt.step()) add(mapper(stmt)) }
}
private fun SQLiteStatement.transportBind(args: List<Any?>) = args.forEachIndexed { index, value -> when (value) {
    null -> bindNull(index + 1)
    is Long -> bindLong(index + 1, value)
    is String -> bindText(index + 1, value)
    else -> error("Unsupported transport SQL argument.")
} }
