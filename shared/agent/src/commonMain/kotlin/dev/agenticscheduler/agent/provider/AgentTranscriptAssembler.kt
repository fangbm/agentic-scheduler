package dev.agenticscheduler.agent.provider

import dev.agenticscheduler.agent.history.AgentMessageRole
import dev.agenticscheduler.agent.history.AgentStateRepository
import dev.agenticscheduler.agent.history.AgentThreadId

sealed interface AgentTranscriptResult {
    data class Ready(val messages: List<ProviderChatMessage>) : AgentTranscriptResult
    /** Never send an orphaned assistant ToolCall or an unmatched ToolResult to a provider. */
    data object UnresolvedToolCall : AgentTranscriptResult
    data object InvalidHistory : AgentTranscriptResult
}

/** Reconstructs provider-independent history with exact assistant/tool-call/result pairing. */
class AgentTranscriptAssembler(private val state: AgentStateRepository) {
    suspend fun assemble(threadId: AgentThreadId): AgentTranscriptResult {
        val messages = state.messages(threadId)
        val calls = state.toolCalls(threadId)
        val results = state.toolResults(threadId)
        val messagesById = messages.associateBy { it.id }
        if (calls.any { messagesById[it.sourceMessageId]?.role != AgentMessageRole.ASSISTANT }) return AgentTranscriptResult.InvalidHistory
        val callsByMessage = calls.groupBy { it.sourceMessageId }
        val resultsByCall = results.associateBy { it.callId }
        if (resultsByCall.size != results.size || results.any { result -> calls.none { it.id == result.callId } }) return AgentTranscriptResult.InvalidHistory
        val output = mutableListOf<ProviderChatMessage>()
        messages.sortedWith(compareBy({ it.ordinal }, { it.id.value })).forEach { message ->
            when (message.role) {
                AgentMessageRole.USER -> output += ProviderChatMessage("user", message.content)
                AgentMessageRole.TOOL -> return AgentTranscriptResult.InvalidHistory
                AgentMessageRole.ASSISTANT -> {
                    val ownedCalls = callsByMessage[message.id].orEmpty().sortedWith(compareBy({ it.ordinal }, { it.id.value }))
                    if (ownedCalls.any { resultsByCall[it.id] == null }) return AgentTranscriptResult.UnresolvedToolCall
                    output += ProviderChatMessage("assistant", message.content.ifEmpty { null }, toolCalls = ownedCalls.map { call ->
                        ProviderToolCall(call.providerCallId ?: call.id.value, function = ProviderFunctionCall(call.name, call.argumentsJson))
                    }.ifEmpty { null })
                    ownedCalls.forEach { call ->
                        output += ProviderChatMessage("tool", resultsByCall.getValue(call.id).resultJson, toolCallId = call.providerCallId ?: call.id.value)
                    }
                }
            }
        }
        return AgentTranscriptResult.Ready(output)
    }
}
