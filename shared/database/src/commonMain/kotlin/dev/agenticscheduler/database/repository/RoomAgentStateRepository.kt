package dev.agenticscheduler.database.repository

import androidx.room3.PooledConnection
import androidx.room3.useReaderConnection
import androidx.room3.useWriterConnection
import androidx.room3.withWriteTransaction
import androidx.sqlite.SQLiteStatement
import dev.agenticscheduler.agent.history.*
import dev.agenticscheduler.agent.permission.AgentPermissionMode
import dev.agenticscheduler.agent.permission.AgentPermissionPolicy
import dev.agenticscheduler.agent.permission.AgentToolCapability
import dev.agenticscheduler.application.sync.SecretReference
import dev.agenticscheduler.database.AgenticSchedulerDatabase
import dev.agenticscheduler.sync.SyncSpaceId
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Uses Room's own connection/transaction for D9's explicitly migrated tables. */
class RoomAgentStateRepository(private val database: AgenticSchedulerDatabase) : AgentStateRepository {
    private val json = Json { encodeDefaults = true }

    override suspend fun saveThread(value: AgentThread) = database.withWriteTransaction {
        val existed = query("SELECT 1 FROM agent_thread WHERE thread_id = ?", listOf(value.id.value)) { it.getLong(0) }.isNotEmpty()
        execute(
            "INSERT INTO agent_thread(thread_id, created_at_epoch_millis, payload_json) VALUES (?, ?, ?) " +
                "ON CONFLICT(thread_id) DO UPDATE SET payload_json = excluded.payload_json",
            listOf(value.id.value, value.createdAtEpochMillis, json.encodeToString(AgentThread.serializer(), value)),
        )
        val provenance = query("SELECT state FROM agent_local_thread_provenance WHERE thread_id = ?", listOf(value.id.value)) { it.getText(0) }.firstOrNull()
        if (provenance == null) {
            val state = if (existed) "LEGACY_UNVERIFIED" else "TRACKED"
            execute(
                "INSERT INTO agent_local_thread_provenance(thread_id, state, creation_title, creation_at_epoch_millis) VALUES (?, ?, ?, ?)",
                listOf(value.id.value, state, if (state == "TRACKED") value.title else null, if (state == "TRACKED") value.createdAtEpochMillis else null),
            )
        }
    }

    override suspend fun thread(id: AgentThreadId): AgentThread? = read(
        "SELECT payload_json FROM agent_thread WHERE thread_id = ?", listOf(id.value),
    ) { json.decodeFromString(AgentThread.serializer(), it.getText(0)) }.firstOrNull()

    override suspend fun threads(): List<AgentThread> = read(
        "SELECT payload_json FROM agent_thread ORDER BY created_at_epoch_millis DESC, thread_id ASC",
    ) { json.decodeFromString(AgentThread.serializer(), it.getText(0)) }

    override suspend fun appendMessage(value: AgentMessage) = database.withWriteTransaction {
        val snapshot = json.encodeToString(AgentMessage.serializer(), value)
        execute("INSERT INTO agent_message(message_id, thread_id, ordinal, payload_json) VALUES (?, ?, ?, ?)",
            listOf(value.id.value, value.threadId.value, value.ordinal, snapshot))
        recordLocalHistoryMember(value.threadId, "MESSAGE", value.id.value, true, snapshot)
    }

    override suspend fun messages(threadId: AgentThreadId): List<AgentMessage> = read(
        "SELECT payload_json FROM agent_message WHERE thread_id = ? ORDER BY ordinal ASC, message_id ASC", listOf(threadId.value),
    ) { json.decodeFromString(AgentMessage.serializer(), it.getText(0)) }

    override suspend fun saveToolCall(value: AgentToolCall) = database.withWriteTransaction {
        val old = query("SELECT payload_json FROM agent_tool_call WHERE call_id = ?", listOf(value.id.value)) {
            json.decodeFromString(AgentToolCall.serializer(), it.getText(0))
        }.firstOrNull()
        check(old == null || old.copy(state = value.state, previewJson = value.previewJson) == value) {
            "An AgentToolCall cannot change its identity or proposed input."
        }
        execute(
            "INSERT INTO agent_tool_call(call_id, thread_id, ordinal, payload_json) VALUES (?, ?, ?, ?) " +
                "ON CONFLICT(call_id) DO UPDATE SET payload_json = excluded.payload_json",
            listOf(value.id.value, value.threadId.value, value.ordinal, json.encodeToString(AgentToolCall.serializer(), value)),
        )
        val isFinal = value.state !in setOf(AgentToolCallState.PROPOSED, AgentToolCallState.WAITING_CONFIRMATION, AgentToolCallState.RUNNING)
        recordLocalHistoryMember(value.threadId, "TOOL_CALL", value.id.value, isFinal,
            if (isFinal) json.encodeToString(AgentToolCall.serializer(), value) else null)
    }

    override suspend fun toolCalls(threadId: AgentThreadId): List<AgentToolCall> = read(
        "SELECT payload_json FROM agent_tool_call WHERE thread_id = ? ORDER BY ordinal ASC, call_id ASC", listOf(threadId.value),
    ) { json.decodeFromString(AgentToolCall.serializer(), it.getText(0)) }

    override suspend fun appendToolResult(value: AgentToolResult) = database.withWriteTransaction {
        val snapshot = json.encodeToString(AgentToolResult.serializer(), value)
        execute("INSERT INTO agent_tool_result(result_id, thread_id, call_id, ordinal, payload_json) VALUES (?, ?, ?, ?, ?)",
            listOf(value.id.value, value.threadId.value, value.callId.value, value.ordinal, snapshot))
        recordLocalHistoryMember(value.threadId, "TOOL_RESULT", value.id.value, true, snapshot)
    }

    override suspend fun toolResults(threadId: AgentThreadId): List<AgentToolResult> = read(
        "SELECT payload_json FROM agent_tool_result WHERE thread_id = ? ORDER BY ordinal ASC, result_id ASC", listOf(threadId.value),
    ) { json.decodeFromString(AgentToolResult.serializer(), it.getText(0)) }

    override suspend fun appendSummary(value: ContextSummary) = write(
        "INSERT INTO context_summary(summary_id, thread_id, created_at_epoch_millis, payload_json) VALUES (?, ?, ?, ?)",
        listOf(value.id.value, value.threadId.value, value.createdAtEpochMillis, json.encodeToString(ContextSummary.serializer(), value)),
    )

    override suspend fun summaries(threadId: AgentThreadId): List<ContextSummary> = read(
        "SELECT payload_json FROM context_summary WHERE thread_id = ? ORDER BY created_at_epoch_millis ASC, summary_id ASC", listOf(threadId.value),
    ) { json.decodeFromString(ContextSummary.serializer(), it.getText(0)) }

    override suspend fun saveAction(value: AgentAction) = write(
        "INSERT INTO agent_action(action_id, thread_id, payload_json) VALUES (?, ?, ?) " +
            "ON CONFLICT(action_id) DO UPDATE SET payload_json = excluded.payload_json",
        listOf(value.id.value, value.threadId?.value, json.encodeToString(AgentAction.serializer(), value)),
    ).also {
        val threadId = value.threadId ?: return@also
        val finalized = value.status !in setOf(AgentActionStatus.PROPOSED, AgentActionStatus.WAITING_CONFIRMATION)
        if (finalized) database.withWriteTransaction {
            recordLocalHistoryMember(threadId, "ACTION", value.id.value, true, json.encodeToString(AgentAction.serializer(), value))
        } else database.withWriteTransaction {
            recordLocalHistoryMember(threadId, "ACTION", value.id.value, false, null)
        }
    }

    override suspend fun action(id: AgentActionId): AgentAction? = read(
        "SELECT payload_json FROM agent_action WHERE action_id = ?", listOf(id.value),
    ) { json.decodeFromString(AgentAction.serializer(), it.getText(0)) }.firstOrNull()

    override suspend fun actions(threadId: AgentThreadId): List<AgentAction> = read(
        "SELECT payload_json FROM agent_action WHERE thread_id = ? ORDER BY action_id ASC", listOf(threadId.value),
    ) { json.decodeFromString(AgentAction.serializer(), it.getText(0)) }

    override suspend fun deleteThread(threadId: AgentThreadId) = database.withWriteTransaction {
        execute("UPDATE agent_local_thread_provenance SET state = 'DELETED' WHERE thread_id = ?", listOf(threadId.value))
        listOf("agent_tool_result", "agent_tool_call", "agent_message", "context_summary").forEach { table ->
            execute("DELETE FROM $table WHERE thread_id = ?", listOf(threadId.value))
        }
        execute("DELETE FROM agent_thread WHERE thread_id = ?", listOf(threadId.value))
    }

    override suspend fun permissionPolicy(): AgentPermissionPolicy {
        val rows = read(
            "SELECT capability, mode FROM agent_permission_policy WHERE capability NOT LIKE 'SYNC_AGENT_ORIGIN:%' ORDER BY capability ASC",
        ) { it.getText(0) to it.getText(1) }
        return if (rows.isEmpty()) AgentPermissionPolicy.default() else AgentPermissionPolicy.fromModes(
            rows.associate { (capability, mode) -> AgentToolCapability.valueOf(capability) to AgentPermissionMode.valueOf(mode) },
        )
    }

    override suspend fun savePermissionPolicy(value: AgentPermissionPolicy) = database.withWriteTransaction {
        execute("DELETE FROM agent_permission_policy WHERE capability NOT LIKE 'SYNC_AGENT_ORIGIN:%'")
        AgentToolCapability.entries.forEach { capability ->
            execute("INSERT INTO agent_permission_policy(capability, mode) VALUES (?, ?)", listOf(capability.name, value.modeFor(capability).name))
        }
    }

    override suspend fun saveProviderConfig(value: ProviderConfig) = database.withWriteTransaction {
        val selected = query("SELECT selected FROM provider_config WHERE config_id = ?", listOf(value.id.value)) { it.getLong(0) }.firstOrNull() ?: 0L
        val payload = ProviderConfigPayload(
            value.baseUrl, value.model, value.maxContextUnits, value.reservedOutputUnits,
            value.streamingSupported, value.toolCallingSupported,
        )
        execute(
            "INSERT INTO provider_config(config_id, selected, credential_secret_ref, payload_json) VALUES (?, ?, ?, ?) " +
                "ON CONFLICT(config_id) DO UPDATE SET credential_secret_ref = excluded.credential_secret_ref, payload_json = excluded.payload_json",
            listOf(value.id.value, selected, value.credentialReference?.value, json.encodeToString(ProviderConfigPayload.serializer(), payload)),
        )
    }

    override suspend fun providerConfig(id: ProviderConfigId): ProviderConfig? = read(
        "SELECT credential_secret_ref, payload_json FROM provider_config WHERE config_id = ?", listOf(id.value),
    ) { row -> decodeProvider(id, if (row.isNull(0)) null else row.getText(0), row.getText(1)) }.firstOrNull()

    override suspend fun providerConfigs(): List<ProviderConfig> = read(
        "SELECT config_id, credential_secret_ref, payload_json FROM provider_config ORDER BY config_id ASC",
    ) { row -> decodeProvider(ProviderConfigId(row.getText(0)), if (row.isNull(1)) null else row.getText(1), row.getText(2)) }

    override suspend fun selectedProviderConfigId(): ProviderConfigId? = read(
        "SELECT config_id FROM provider_config WHERE selected = 1 ORDER BY config_id ASC LIMIT 1",
    ) { ProviderConfigId(it.getText(0)) }.firstOrNull()

    override suspend fun selectProviderConfig(id: ProviderConfigId?) = database.withWriteTransaction {
        if (id != null) require(query("SELECT 1 FROM provider_config WHERE config_id = ?", listOf(id.value)) { it.getLong(0) }.isNotEmpty()) {
            "Selected provider config does not exist."
        }
        execute("UPDATE provider_config SET selected = 0 WHERE selected = 1")
        if (id != null) execute("UPDATE provider_config SET selected = 1 WHERE config_id = ?", listOf(id.value))
    }

    override suspend fun syncAgentOriginEnabled(syncSpaceId: SyncSpaceId): Boolean = read(
        "SELECT mode FROM agent_permission_policy WHERE capability = ?", listOf("SYNC_AGENT_ORIGIN:${syncSpaceId.value}"),
    ) { it.getText(0) }.firstOrNull() == AgentPermissionMode.ALLOW_DIRECT.name

    override suspend fun setSyncAgentOriginEnabled(syncSpaceId: SyncSpaceId, enabled: Boolean) = write(
        "INSERT INTO agent_permission_policy(capability, mode) VALUES (?, ?) " +
            "ON CONFLICT(capability) DO UPDATE SET mode = excluded.mode",
        listOf("SYNC_AGENT_ORIGIN:${syncSpaceId.value}", if (enabled) AgentPermissionMode.ALLOW_DIRECT.name else AgentPermissionMode.DENY.name),
    )

    override suspend fun beginLocalHistoryTurn(threadId: AgentThreadId, turnId: String) = database.withWriteTransaction {
        val threadState = query("SELECT state FROM agent_local_thread_provenance WHERE thread_id = ?", listOf(threadId.value)) { it.getText(0) }.singleOrNull()
            ?: return@withWriteTransaction
        check(threadState != "DELETED") { "Deleted Agent history cannot start another turn." }
        val previous = query(
            "SELECT turn_id, ancestry_verified, lifecycle FROM agent_local_turn_provenance WHERE thread_id = ? ORDER BY start_order DESC LIMIT 1",
            listOf(threadId.value),
        ) { Triple(it.getText(0), it.getLong(1) == 1L, it.getText(2)) }.singleOrNull()
        val parentIds = previous?.let { listOf(it.first) }.orEmpty()
        val ancestryVerified = threadState == "TRACKED" && (previous == null || previous.second && previous.third == "FINALIZED")
        execute(
            "INSERT INTO agent_local_turn_provenance(thread_id, turn_id, parent_turn_ids_json, ancestry_verified, lifecycle, outcome) VALUES (?, ?, ?, ?, 'RUNNING', NULL)",
            listOf(threadId.value, turnId, json.encodeToString(parentIds), if (ancestryVerified) 1 else 0),
        )
    }

    override suspend fun activeLocalHistoryTurn(threadId: AgentThreadId): String? = read(
        "SELECT turn_id FROM agent_local_turn_provenance WHERE thread_id = ? AND lifecycle IN ('RUNNING','AWAITING_CONFIRMATION') ORDER BY start_order DESC LIMIT 1",
        listOf(threadId.value),
    ) { it.getText(0) }.firstOrNull()

    override suspend fun setLocalHistoryTurnAwaitingConfirmation(threadId: AgentThreadId, turnId: String) = write(
        "UPDATE agent_local_turn_provenance SET lifecycle = 'AWAITING_CONFIRMATION' WHERE thread_id = ? AND turn_id = ? AND lifecycle = 'RUNNING'",
        listOf(threadId.value, turnId),
    )

    override suspend fun finalizeLocalHistoryTurn(threadId: AgentThreadId, turnId: String, outcome: AgentLocalTurnOutcome): Boolean = database.withWriteTransaction {
        val pending = query(
            "SELECT 1 FROM agent_local_turn_member WHERE thread_id = ? AND turn_id = ? AND finalized = 0 LIMIT 1",
            listOf(threadId.value, turnId),
        ) { it.getLong(0) }.isNotEmpty()
        if (pending) return@withWriteTransaction false
        execute(
            "UPDATE agent_local_turn_provenance SET lifecycle = 'FINALIZED', outcome = ? WHERE thread_id = ? AND turn_id = ? AND lifecycle IN ('RUNNING','AWAITING_CONFIRMATION')",
            listOf(outcome.name, threadId.value, turnId),
        )
        query("SELECT lifecycle FROM agent_local_turn_provenance WHERE thread_id = ? AND turn_id = ?", listOf(threadId.value, turnId)) { it.getText(0) }.singleOrNull() == "FINALIZED"
    }

    override suspend fun localThreadProvenance(threadId: AgentThreadId): AgentLocalThreadProvenance? = read(
        "SELECT state, creation_title, creation_at_epoch_millis FROM agent_local_thread_provenance WHERE thread_id = ?", listOf(threadId.value),
    ) { AgentLocalThreadProvenance(threadId, AgentLocalThreadProvenanceState.valueOf(it.getText(0)), if (it.isNull(1)) null else it.getText(1), if (it.isNull(2)) null else it.getLong(2)) }.firstOrNull()

    override suspend fun localHistoryTurns(threadId: AgentThreadId): List<AgentLocalTurnProvenance> = database.useReaderConnection { connection ->
        val rows = connection.query("SELECT turn_id, parent_turn_ids_json, ancestry_verified, lifecycle, outcome FROM agent_local_turn_provenance WHERE thread_id = ? ORDER BY start_order", listOf(threadId.value)) { row ->
            TurnRow(row.getText(0), row.getText(1), row.getLong(2) == 1L, row.getText(3), if (row.isNull(4)) null else row.getText(4))
        }
        rows.map { row ->
            val members = connection.query(
                "SELECT member_order, member_kind, member_id, snapshot_json, finalized FROM agent_local_turn_member WHERE thread_id = ? AND turn_id = ? ORDER BY member_order",
                listOf(threadId.value, row.turnId),
            ) { item -> AgentLocalHistoryMember(item.getLong(0).toInt(), AgentLocalHistoryMemberKind.valueOf(item.getText(1)), item.getText(2), if (item.isNull(3)) null else item.getText(3), item.getLong(4) == 1L) }
            AgentLocalTurnProvenance(threadId, row.turnId, json.decodeFromString<List<String>>(row.parentsJson), row.ancestryVerified,
                AgentLocalTurnLifecycle.valueOf(row.lifecycle), row.outcome?.let(AgentLocalTurnOutcome::valueOf), members)
        }
    }

    private data class TurnRow(val turnId: String, val parentsJson: String, val ancestryVerified: Boolean, val lifecycle: String, val outcome: String?)

    private suspend fun PooledConnection.recordLocalHistoryMember(threadId: AgentThreadId, kind: String, id: String, finalized: Boolean, snapshot: String?) {
        val isTracked = query("SELECT 1 FROM agent_local_thread_provenance WHERE thread_id = ? AND state = 'TRACKED'", listOf(threadId.value)) { it.getLong(0) }.isNotEmpty()
        if (!isTracked) return
        val turn = query("SELECT turn_id FROM agent_local_turn_provenance WHERE thread_id = ? AND lifecycle IN ('RUNNING','AWAITING_CONFIRMATION') ORDER BY start_order DESC LIMIT 1", listOf(threadId.value)) { it.getText(0) }.singleOrNull() ?: return
        val existing = query("SELECT finalized, snapshot_json FROM agent_local_turn_member WHERE thread_id = ? AND turn_id = ? AND member_kind = ? AND member_id = ?", listOf(threadId.value, turn, kind, id)) { (it.getLong(0) == 1L) to (if (it.isNull(1)) null else it.getText(1)) }.singleOrNull()
        if (existing?.first == true && finalized && existing.second != snapshot) error("A finalized local history member is immutable.")
        val order = query("SELECT coalesce(max(member_order), -1) + 1 FROM agent_local_turn_member WHERE thread_id = ? AND turn_id = ?", listOf(threadId.value, turn)) { it.getLong(0) }.single()
        execute(
            "INSERT INTO agent_local_turn_member(thread_id, turn_id, member_order, member_kind, member_id, finalized, snapshot_json) VALUES (?, ?, ?, ?, ?, ?, ?) " +
                "ON CONFLICT(thread_id, turn_id, member_kind, member_id) DO UPDATE SET finalized = max(agent_local_turn_member.finalized, excluded.finalized), snapshot_json = coalesce(agent_local_turn_member.snapshot_json, excluded.snapshot_json)",
            listOf(threadId.value, turn, order, kind, id, if (finalized) 1 else 0, snapshot),
        )
    }

    private fun decodeProvider(id: ProviderConfigId, credentialReference: String?, payloadJson: String): ProviderConfig {
        val payload = json.decodeFromString(ProviderConfigPayload.serializer(), payloadJson)
        return ProviderConfig(
            id, payload.baseUrl, payload.model, payload.maxContextUnits, payload.reservedOutputUnits,
            payload.streamingSupported, payload.toolCallingSupported, credentialReference?.let(::SecretReference),
        )
    }

    private suspend fun <T> read(
        sql: String,
        args: List<Any?> = emptyList(),
        decode: (SQLiteStatement) -> T,
    ): List<T> = database.useReaderConnection { it.query(sql, args, decode) }

    private suspend fun write(sql: String, args: List<Any?> = emptyList()) = database.useWriterConnection {
        it.execute(sql, args)
    }
}

@Serializable
private data class ProviderConfigPayload(
    val baseUrl: String,
    val model: String,
    val maxContextUnits: Long,
    val reservedOutputUnits: Long,
    val streamingSupported: Boolean,
    val toolCallingSupported: Boolean,
)

private suspend fun PooledConnection.execute(sql: String, args: List<Any?> = emptyList()) = usePrepared(sql) { statement ->
    statement.bind(args)
    statement.step()
    Unit
}

private suspend fun <T> PooledConnection.query(sql: String, args: List<Any?>, decode: (SQLiteStatement) -> T): List<T> = usePrepared(sql) { statement ->
    statement.bind(args)
    buildList { while (statement.step()) add(decode(statement)) }
}

private fun SQLiteStatement.bind(args: List<Any?>) {
    args.forEachIndexed { index, value -> when (value) {
        null -> bindNull(index + 1)
        is String -> bindText(index + 1, value)
        is Long -> bindLong(index + 1, value)
        is Int -> bindLong(index + 1, value.toLong())
        else -> error("Unsupported Agent SQL binding type.")
    } }
}
