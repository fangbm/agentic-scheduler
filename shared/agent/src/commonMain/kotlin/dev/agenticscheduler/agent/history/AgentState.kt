package dev.agenticscheduler.agent.history

import dev.agenticscheduler.agent.permission.AgentPermissionMode
import dev.agenticscheduler.agent.permission.AgentPermissionPolicy
import dev.agenticscheduler.application.sync.SecretReference
import dev.agenticscheduler.sync.MutationId
import dev.agenticscheduler.sync.SyncSpaceId
import kotlinx.serialization.Serializable

private fun requireAgentId(value: String) {
    // Reuse the already-frozen UUIDv7 validator; these remain distinct identities.
    MutationId(value)
}

@Serializable @JvmInline value class AgentThreadId(val value: String) { init { requireAgentId(value) } }
@Serializable @JvmInline value class AgentMessageId(val value: String) { init { requireAgentId(value) } }
@Serializable @JvmInline value class AgentToolCallId(val value: String) { init { requireAgentId(value) } }
@Serializable @JvmInline value class AgentToolResultId(val value: String) { init { requireAgentId(value) } }
@Serializable @JvmInline value class AgentActionId(val value: String) { init { requireAgentId(value) } }
@Serializable @JvmInline value class ContextSummaryId(val value: String) { init { requireAgentId(value) } }
@Serializable @JvmInline value class ProviderConfigId(val value: String) { init { requireAgentId(value) } }

@Serializable
data class AgentThread(
    val id: AgentThreadId,
    val title: String?,
    val createdAtEpochMillis: Long,
)

@Serializable enum class AgentMessageRole { USER, ASSISTANT, TOOL }

@Serializable
data class AgentMessage(
    val id: AgentMessageId,
    val threadId: AgentThreadId,
    val ordinal: Long,
    val role: AgentMessageRole,
    val content: String,
    val createdAtEpochMillis: Long,
) { init { require(ordinal >= 0) } }

@Serializable enum class AgentToolCallState { PROPOSED, WAITING_CONFIRMATION, RUNNING, COMPLETED, FAILED, DENIED }

@Serializable
data class AgentToolCall(
    val id: AgentToolCallId,
    val threadId: AgentThreadId,
    val sourceMessageId: AgentMessageId,
    val ordinal: Long,
    val name: String,
    val argumentsJson: String,
    val state: AgentToolCallState,
    val previewJson: String? = null,
) { init { require(ordinal >= 0 && name.isNotBlank() && argumentsJson.isNotBlank()) } }

@Serializable enum class AgentToolResultStatus {
    SUCCESS, INVALID_INPUT, NOT_FOUND, PERMISSION_DENIED, STALE, CONFLICT, INFEASIBLE, INFRASTRUCTURE_FAILURE,
}

@Serializable
data class AgentToolResult(
    val id: AgentToolResultId,
    val threadId: AgentThreadId,
    val callId: AgentToolCallId,
    val ordinal: Long,
    val status: AgentToolResultStatus,
    val resultJson: String,
    val mutationIds: List<MutationId> = emptyList(),
) { init { require(ordinal >= 0 && resultJson.isNotBlank()) } }

@Serializable
data class ContextSummary(
    val id: ContextSummaryId,
    val threadId: AgentThreadId,
    val sourceStartMessageId: AgentMessageId,
    val sourceEndMessageId: AgentMessageId,
    val schemaVersion: Int,
    val text: String,
    val createdAtEpochMillis: Long,
    val providerConfigId: ProviderConfigId?,
    val model: String?,
) { init { require(schemaVersion > 0 && text.isNotBlank()) } }

@Serializable enum class AgentActionStatus { PROPOSED, WAITING_CONFIRMATION, SUCCEEDED, FAILED, DENIED, STALE }

@Serializable
data class AgentAction(
    val id: AgentActionId,
    val threadId: AgentThreadId?,
    val sourceMessageId: AgentMessageId?,
    val providerConfigId: ProviderConfigId?,
    val model: String?,
    val toolCallIds: List<AgentToolCallId>,
    val toolResultIds: List<AgentToolResultId>,
    val permissionDecision: AgentPermissionMode?,
    val confirmationReference: String?,
    val planBranchReference: String?,
    val mutationIds: List<MutationId>,
    val status: AgentActionStatus,
)

/** Non-secret per-device provider configuration; the key lives only behind [credentialReference]. */
data class ProviderConfig(
    val id: ProviderConfigId,
    val baseUrl: String,
    val model: String,
    val maxContextUnits: Long,
    val reservedOutputUnits: Long,
    val streamingSupported: Boolean,
    val toolCallingSupported: Boolean,
    val credentialReference: SecretReference?,
) {
    init {
        require(baseUrl.isNotBlank() && model.isNotBlank())
        require(maxContextUnits > 0 && reservedOutputUnits > 0 && reservedOutputUnits < maxContextUnits)
    }
}

/** D9-01 local-only state. No method exposes permission settings to Agent Tools. */
interface AgentStateRepository {
    suspend fun saveThread(value: AgentThread)
    suspend fun thread(id: AgentThreadId): AgentThread?
    suspend fun threads(): List<AgentThread>
    suspend fun appendMessage(value: AgentMessage)
    suspend fun messages(threadId: AgentThreadId): List<AgentMessage>
    suspend fun saveToolCall(value: AgentToolCall)
    suspend fun toolCalls(threadId: AgentThreadId): List<AgentToolCall>
    suspend fun appendToolResult(value: AgentToolResult)
    suspend fun toolResults(threadId: AgentThreadId): List<AgentToolResult>
    suspend fun appendSummary(value: ContextSummary)
    suspend fun summaries(threadId: AgentThreadId): List<ContextSummary>
    suspend fun saveAction(value: AgentAction)
    suspend fun action(id: AgentActionId): AgentAction?
    suspend fun actions(threadId: AgentThreadId): List<AgentAction>
    /** Explicit local deletion preserves [AgentAction] and D7 ChangeLog audit. */
    suspend fun deleteThread(threadId: AgentThreadId)
    suspend fun permissionPolicy(): AgentPermissionPolicy
    suspend fun savePermissionPolicy(value: AgentPermissionPolicy)
    suspend fun saveProviderConfig(value: ProviderConfig)
    suspend fun providerConfig(id: ProviderConfigId): ProviderConfig?
    suspend fun providerConfigs(): List<ProviderConfig>
    suspend fun selectedProviderConfigId(): ProviderConfigId?
    suspend fun selectProviderConfig(id: ProviderConfigId?)
    /** False unless the user explicitly acknowledges upgraded replicas for this space. */
    suspend fun syncAgentOriginEnabled(syncSpaceId: SyncSpaceId): Boolean
    suspend fun setSyncAgentOriginEnabled(syncSpaceId: SyncSpaceId, enabled: Boolean)
}
