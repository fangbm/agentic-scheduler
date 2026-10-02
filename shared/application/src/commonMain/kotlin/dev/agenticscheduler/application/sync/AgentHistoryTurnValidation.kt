package dev.agenticscheduler.application.sync

import dev.agenticscheduler.sync.*

/** Frozen W2 association checks for complete historical turns; never invokes a Tool. */
fun agentHistoryTurnLinkError(manifest: TurnFinalized, members: List<AgentSyncEvent>): String? {
    if (members.any { it.threadIdForTransport() != manifest.threadId || it.turnIdForTransport() != manifest.turnId })
        return "Manifest member belongs to another thread or turn."
    val messages = members.filterIsInstance<MessageAppended>().associateBy { it.messageId }
    val calls = members.filterIsInstance<ToolCallFinalized>().associateBy { it.callId }
    val results = members.filterIsInstance<ToolResultAppended>().associateBy { it.resultId }
    val actions = members.filterIsInstance<ActionFinalized>().associateBy { it.actionId }
    val refs = messages.keys.map { MessageMember(it) } + calls.keys.map { ToolCallMember(it) } +
        results.keys.map { ToolResultMember(it) } + actions.keys.map { ActionMember(it) }
    if (refs.size != members.size || refs.toSet() != manifest.orderedMembers.toSet()) return "Manifest members are incomplete or duplicated."
    if (calls.values.any { it.sourceMessageId !in messages }) return "Tool call source is not a message in this turn."
    if (results.values.any { it.callId !in calls }) return "Tool result call is not a member of this turn."
    return when (val validation = AgentTurnLinkValidator.validate(manifest, actions, messages, calls, results)) {
        AgentTurnLinkValidation.Valid -> null
        else -> validation.toString()
    }
}
