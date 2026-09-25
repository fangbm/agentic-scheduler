package dev.agenticscheduler.agent.tool

import dev.agenticscheduler.application.history.HistoryQueryService
import dev.agenticscheduler.application.persistence.CommittedMutation
import dev.agenticscheduler.application.persistence.FocusBlockTombstone
import dev.agenticscheduler.application.persistence.HistoryChange
import dev.agenticscheduler.application.persistence.HistoryRepository
import dev.agenticscheduler.sync.EntityKind
import dev.agenticscheduler.sync.MutationId
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class HistoryReadToolsTest {
    @Test fun `history reads delegate to D7 without writes and reject bad input`() = runBlocking {
        val repository = EmptyHistory()
        val tools = HistoryReadTools(HistoryQueryService(repository))
        assertEquals(emptyList(), assertIs<AgentToolOutcome.Success<List<CommittedMutation>>>(tools.timeline(HistoryTimelineInput())).payload)
        assertEquals(AgentToolOutcome.NotFound, tools.getMutation(MutationId("00000000-0000-7000-8000-000000000001")))
        assertIs<AgentToolOutcome.InvalidInput>(tools.timeline(HistoryTimelineInput(limit = 201)))
        assertIs<AgentToolOutcome.InvalidInput>(tools.getEntityChanges(HistoryEntityChangesInput(EntityKind.TASK, " ")))
        assertEquals(AgentToolAccess.READ, tools.timelineMetadata.access)
    }

    private class EmptyHistory : HistoryRepository {
        override suspend fun timeline(): List<CommittedMutation> = emptyList()
        override suspend fun mutation(mutationId: String): CommittedMutation? = null
        override suspend fun entityChanges(entityKind: EntityKind, entityId: String): List<HistoryChange> = emptyList()
        override suspend fun diff(mutationId: String): List<HistoryChange> = emptyList()
        override suspend fun focusBlockTombstone(focusBlockId: String): FocusBlockTombstone? = null
    }
}
