package dev.agenticscheduler.database.repository

import androidx.room3.useReaderConnection
import androidx.room3.PooledConnection
import androidx.sqlite.SQLiteStatement
import dev.agenticscheduler.agent.history.*
import dev.agenticscheduler.application.sync.*
import dev.agenticscheduler.database.AgenticSchedulerDatabase
import dev.agenticscheduler.sync.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/** Reads only v15 prospective provenance; v14 rows are counted and never heuristically exported. */
class RoomAgentHistoryExportSource(
    private val database: AgenticSchedulerDatabase,
    private val state: AgentStateRepository = RoomAgentStateRepository(database),
) : AgentHistoryExportSource {
    private val json = Json { ignoreUnknownKeys = false }

    override suspend fun snapshot(): AgentHistoryExportSnapshot {
        val provenance = database.useReaderConnection { connection ->
            connection.query("SELECT thread_id, state, creation_title, creation_at_epoch_millis FROM agent_local_thread_provenance ORDER BY thread_id") { row ->
                ThreadRow(row.getText(0), row.getText(1), if (row.isNull(2)) null else row.getText(2), if (row.isNull(3)) null else row.getLong(3))
            }
        }
        val legacy = provenance.count { it.state == AgentLocalThreadProvenanceState.LEGACY_UNVERIFIED.name }
        val threads = mutableListOf<AgentHistoryExportThread>()
        for (row in provenance.filter { it.state == AgentLocalThreadProvenanceState.TRACKED.name || it.state == AgentLocalThreadProvenanceState.DELETED.name }) {
            val threadId = AgentThreadId(row.id)
            val deleted = row.state == AgentLocalThreadProvenanceState.DELETED.name
            val turns = if (deleted) emptyList() else state.localHistoryTurns(threadId).map(::convertTurn)
            val creation = if (deleted) null else row.createdAt?.let { ThreadCreated(AgentThreadSyncId(row.id), row.title, it) }
            threads += AgentHistoryExportThread(AgentThreadSyncId(row.id), tracked = !deleted, deleted = deleted, creation = creation, turns = turns)
        }
        return AgentHistoryExportSnapshot(threads, legacy)
    }

    private fun convertTurn(turn: AgentLocalTurnProvenance): AgentHistoryExportTurn {
        var valid = true
        val converted = turn.members.mapNotNull { member ->
            val event = runCatching { convertMember(turn, member) }.getOrElse { valid = false; null }
            event?.let { AgentHistoryExportMember(member.kind.name, member.id, it, member.finalized) }
        }
        val manifest = if (valid && turn.lifecycle == AgentLocalTurnLifecycle.FINALIZED && turn.outcome != null) runCatching {
            val refs = converted.map { it.event.memberReference() ?: error("Non-member event in turn provenance") }
            TurnFinalized(
                AgentTurnSyncId(turn.turnId), AgentThreadSyncId(turn.threadId.value), turn.parentTurnIds.map(::AgentTurnSyncId), refs,
                if (turn.outcome == AgentLocalTurnOutcome.FAILED) AgentTurnOutcome.FAILED else AgentTurnOutcome.SUCCEEDED,
            )
        }.getOrNull() else null
        if (manifest == null || agentHistoryTurnLinkError(manifest, converted.map { it.event }) != null) valid = false
        val finalized = valid && manifest != null
        return AgentHistoryExportTurn(turn.turnId, turn.parentTurnIds, turn.ancestryVerified, finalized, converted, manifest, wireValid = valid)
    }

    private fun convertMember(turn: AgentLocalTurnProvenance, member: AgentLocalHistoryMember): AgentSyncEvent {
        val snapshot = member.snapshotJson
        require(member.finalized && snapshot != null) { "Incomplete provenance member." }
        val threadId = AgentThreadSyncId(turn.threadId.value)
        val turnId = AgentTurnSyncId(turn.turnId)
        return when (member.kind) {
            AgentLocalHistoryMemberKind.MESSAGE -> json.decodeFromString(AgentMessage.serializer(), snapshot).let { value ->
                require(value.threadId == turn.threadId)
                MessageAppended(AgentMessageSyncId(value.id.value), threadId, turnId, AgentMessageRoleV3.valueOf(value.role.name), value.content, value.createdAtEpochMillis)
            }
            AgentLocalHistoryMemberKind.TOOL_CALL -> json.decodeFromString(AgentToolCall.serializer(), snapshot).let { value ->
                require(value.threadId == turn.threadId)
                val inputs = json.parseToJsonElement(value.argumentsJson) as? JsonObject ?: error("Tool inputs must be a JSON object.")
                val status = when (value.state) {
                    AgentToolCallState.COMPLETED -> FinalAgentToolCallStatus.COMPLETED
                    AgentToolCallState.FAILED -> FinalAgentToolCallStatus.FAILED
                    AgentToolCallState.DENIED -> FinalAgentToolCallStatus.DENIED
                    else -> error("A nonterminal ToolCall cannot be exported.")
                }
                ToolCallFinalized(AgentToolCallSyncId(value.id.value), threadId, turnId, AgentMessageSyncId(value.sourceMessageId.value), value.name, inputs, status)
            }
            AgentLocalHistoryMemberKind.TOOL_RESULT -> json.decodeFromString(AgentToolResult.serializer(), snapshot).let { value ->
                require(value.threadId == turn.threadId)
                ToolResultAppended(AgentToolResultSyncId(value.id.value), AgentToolCallSyncId(value.callId.value), threadId, turnId,
                    AgentToolResultStatusV3.valueOf(value.status.name), json.parseToJsonElement(value.resultJson), value.mutationIds)
            }
            AgentLocalHistoryMemberKind.ACTION -> json.decodeFromString(AgentAction.serializer(), snapshot).let { value ->
                require(value.threadId?.value == turn.threadId.value)
                val status = when (value.status) {
                    AgentActionStatus.SUCCEEDED -> FinalAgentActionStatus.SUCCEEDED
                    AgentActionStatus.FAILED -> FinalAgentActionStatus.FAILED
                    AgentActionStatus.DENIED -> FinalAgentActionStatus.DENIED
                    AgentActionStatus.STALE -> FinalAgentActionStatus.STALE
                    else -> error("A nonterminal Action cannot be exported.")
                }
                ActionFinalized(AgentActionSyncId(value.id.value), turnId, threadId, value.sourceMessageId?.let { AgentMessageSyncId(it.value) },
                    value.toolCallIds.map { AgentToolCallSyncId(it.value) }, value.toolResultIds.map { AgentToolResultSyncId(it.value) }, value.mutationIds, status)
            }
        }
    }

    private data class ThreadRow(val id: String, val state: String, val title: String?, val createdAt: Long?)
}

private fun AgentSyncEvent.memberReference(): TurnMemberReference? = when (this) {
    is MessageAppended -> MessageMember(messageId)
    is ToolCallFinalized -> ToolCallMember(callId)
    is ToolResultAppended -> ToolResultMember(resultId)
    is ActionFinalized -> ActionMember(actionId)
    else -> null
}

private suspend fun <T> PooledConnection.query(sql: String, decode: (SQLiteStatement) -> T): List<T> = usePrepared(sql) { statement ->
    buildList { while (statement.step()) add(decode(statement)) }
}
