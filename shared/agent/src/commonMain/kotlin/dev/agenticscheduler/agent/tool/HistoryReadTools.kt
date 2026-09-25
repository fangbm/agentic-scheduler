package dev.agenticscheduler.agent.tool

import dev.agenticscheduler.agent.permission.AgentToolCapability
import dev.agenticscheduler.application.history.HistoryQueryService
import dev.agenticscheduler.application.persistence.CommittedMutation
import dev.agenticscheduler.application.persistence.HistoryChange
import dev.agenticscheduler.sync.EntityKind
import dev.agenticscheduler.sync.MutationId

data class HistoryTimelineInput(val cursor: HistoryQueryService.TimelineCursor? = null, val limit: Int? = null)
data class HistoryEntityChangesInput(
    val entityKind: EntityKind,
    val entityId: String,
    val cursor: HistoryQueryService.EntityChangesCursor? = null,
    val limit: Int? = null,
)

/** Read-only access to authoritative D7 history, never provider conversation memory. */
class HistoryReadTools(private val history: HistoryQueryService) {
    val timelineMetadata = AgentToolMetadata(AgentToolNames.HISTORY_TIMELINE, AgentToolCapability.READ, AgentToolAccess.READ)
    val mutationMetadata = AgentToolMetadata(AgentToolNames.HISTORY_GET_MUTATION, AgentToolCapability.READ, AgentToolAccess.READ)
    val entityChangesMetadata = AgentToolMetadata(AgentToolNames.HISTORY_GET_ENTITY_CHANGES, AgentToolCapability.READ, AgentToolAccess.READ)

    suspend fun timeline(input: HistoryTimelineInput): AgentToolOutcome<List<CommittedMutation>> {
        if (input.limit != null && input.limit !in 1..200) return AgentToolOutcome.InvalidInput(listOf(AgentToolInputIssue("limit", "OUT_OF_RANGE")))
        return AgentToolOutcome.Success(if (input.limit == null) history.timeline(input.cursor) else history.timeline(input.cursor, input.limit))
    }

    suspend fun getMutation(id: MutationId): AgentToolOutcome<CommittedMutation> =
        history.getMutation(id.value)?.let { AgentToolOutcome.Success(it) } ?: AgentToolOutcome.NotFound

    suspend fun getEntityChanges(input: HistoryEntityChangesInput): AgentToolOutcome<List<HistoryChange>> {
        val issues = buildList {
            if (input.entityId.isBlank()) add(AgentToolInputIssue("entityId", "BLANK"))
            if (input.limit != null && input.limit !in 1..200) add(AgentToolInputIssue("limit", "OUT_OF_RANGE"))
        }
        if (issues.isNotEmpty()) return AgentToolOutcome.InvalidInput(issues)
        return AgentToolOutcome.Success(if (input.limit == null) {
            history.getEntityChanges(input.entityKind, input.entityId, input.cursor)
        } else {
            history.getEntityChanges(input.entityKind, input.entityId, input.cursor, input.limit)
        })
    }
}
