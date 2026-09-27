package dev.agenticscheduler.agent.tool

import dev.agenticscheduler.agent.permission.AgentPermissionMode
import dev.agenticscheduler.agent.permission.AgentPermissionPolicy
import dev.agenticscheduler.agent.permission.AgentToolCapability
import dev.agenticscheduler.application.history.HistoryQueryService
import dev.agenticscheduler.application.history.MutationExecution
import dev.agenticscheduler.application.history.UndoCapability
import dev.agenticscheduler.application.history.UndoResult
import dev.agenticscheduler.application.persistence.HistoryChange
import dev.agenticscheduler.sync.EntityKind
import dev.agenticscheduler.sync.HlcTimestamp
import dev.agenticscheduler.sync.MutationId
import dev.agenticscheduler.sync.ReplicaId
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class HistoryUndoToolTest {
    private val mutationId = "00000000-0000-7000-8000-000000000031"
    private val committedId = MutationId("00000000-0000-7000-8000-000000000032")
    private val defaultPolicy = AgentPermissionPolicy.default()
    private val directPolicy = defaultPolicy.withMode(AgentToolCapability.UNDO, AgentPermissionMode.ALLOW_DIRECT)
    private val deniedPolicy = defaultPolicy.withMode(AgentToolCapability.UNDO, AgentPermissionMode.DENY)
    private val args = """{"mutationId":"$mutationId"}"""

    @Test
    fun `valid request prepares confirmation by default and confirmed commit returns MutationId`() = runBlocking {
        val app = FakeUndoApplication()
        val tool = HistoryUndoTool(app)

        val prepared = assertIs<AgentToolOutcome.ConfirmationRequired<HistoryUndoPreview>>(tool.prepare(args, defaultPolicy))
        assertEquals(mutationId, prepared.preview.originalMutationId)
        assertEquals(AgentToolCapability.UNDO, tool.metadata.capability)
        assertEquals(AgentToolAccess.WRITE, tool.metadata.access)

        val notConfirmed = tool.commit(args, prepared.preview, userConfirmed = false, defaultPolicy)
        assertEquals(AgentToolOutcome.PermissionDenied(AgentToolCapability.UNDO), notConfirmed)
        assertEquals(0, app.undoCalls)

        var observed: MutationExecution<UndoResult>? = null
        val committed = assertIs<AgentToolOutcome.Success<CommittedHistoryUndo>>(
            tool.commit(args, prepared.preview, userConfirmed = true, defaultPolicy) { observed = it },
        )
        assertEquals(CommittedHistoryUndo(mutationId, committedId), committed.payload)
        assertEquals(committedId, observed?.mutationId)
        assertEquals(1, app.undoCalls)
    }

    @Test
    fun `input validation happens before application access`() = runBlocking {
        val app = FakeUndoApplication()
        val tool = HistoryUndoTool(app)
        assertIs<AgentToolOutcome.InvalidInput>(tool.prepare("{}", defaultPolicy))
        assertIs<AgentToolOutcome.InvalidInput>(tool.prepare("""{"mutationId":"bad"}""", defaultPolicy))
        assertIs<AgentToolOutcome.InvalidInput>(tool.prepare("""{"mutationId":"$mutationId","extra":true}""", defaultPolicy))
        assertEquals(0, app.canUndoCalls)
    }

    @Test
    fun `not found unsupported and denied remain structured and never undo`() = runBlocking {
        val app = FakeUndoApplication().apply { capability = UndoCapability.NotFound }
        val tool = HistoryUndoTool(app)
        assertEquals(AgentToolOutcome.NotFound, tool.prepare(args, defaultPolicy))

        app.capability = UndoCapability.Unsupported("Task creation has no D7 delete contract.")
        assertEquals(AgentToolOutcome.Unsupported("TASK_CREATE_UNSUPPORTED"), tool.prepare(args, defaultPolicy))

        app.capability = UndoCapability.Available
        assertEquals(AgentToolOutcome.PermissionDenied(AgentToolCapability.UNDO), tool.prepare(args, deniedPolicy))
        assertEquals(0, app.undoCalls)
    }

    @Test
    fun `direct permission allows execution but changed confirmation preview is stale`() = runBlocking {
        val app = FakeUndoApplication()
        val tool = HistoryUndoTool(app)
        val preview = assertIs<AgentToolOutcome.Success<HistoryUndoPreview>>(tool.prepare(args, directPolicy)).payload
        val changedPreview = preview.copy(changes = listOf(
            HistoryUndoPreviewChange("TASK", "task-id", "TaskPut", null, "{}"),
        ))
        assertEquals(AgentToolOutcome.Stale, tool.commit(args, changedPreview, userConfirmed = false, directPolicy))
        assertEquals(0, app.undoCalls)

        val result = assertIs<AgentToolOutcome.Success<CommittedHistoryUndo>>(
            tool.commit(args, preview, userConfirmed = false, directPolicy),
        )
        assertEquals(committedId, result.payload.mutationId)
        assertEquals(1, app.undoCalls)
    }

    @Test
    fun `commit surfaces conflict missing history and redacted infrastructure failure`() = runBlocking {
        val app = FakeUndoApplication()
        val tool = HistoryUndoTool(app)
        val preview = assertIs<AgentToolOutcome.ConfirmationRequired<HistoryUndoPreview>>(tool.prepare(args, defaultPolicy)).preview

        app.undoResult = UndoResult.Conflict(listOf("task-1"))
        assertEquals(AgentToolOutcome.Conflict(listOf("task-1")), tool.commit(args, preview, true, defaultPolicy))
        app.undoResult = UndoResult.NotFound
        assertEquals(AgentToolOutcome.NotFound, tool.commit(args, preview, true, defaultPolicy))

        app.throwOnUndo = true
        assertEquals(AgentToolOutcome.InfrastructureFailure("UNDO_TRANSACTION"), tool.commit(args, preview, true, defaultPolicy))
    }

    @Test
    fun `preview failures are redacted`() = runBlocking {
        val app = FakeUndoApplication().apply { throwOnDiff = true }
        val tool = HistoryUndoTool(app)
        assertEquals(AgentToolOutcome.InfrastructureFailure("UNDO_PREVIEW"), tool.prepare(args, defaultPolicy))
    }

    private class FakeUndoApplication(
        private val committedId: MutationId = MutationId("00000000-0000-7000-8000-000000000032"),
    ) : HistoryUndoApplication {
        var capability: UndoCapability = UndoCapability.Available
        var undoResult: UndoResult = UndoResult.Applied(committedId)
        var canUndoCalls = 0
        var undoCalls = 0
        var throwOnDiff = false
        var throwOnUndo = false

        override suspend fun canUndo(mutationId: String): UndoCapability {
            canUndoCalls++
            return capability
        }

        override suspend fun diff(mutationId: String): List<HistoryChange> {
            if (throwOnDiff) error("sensitive exception detail must not escape")
            return listOf(HistoryChange(
                mutationId = mutationId,
                ordinal = 0,
                entityKind = EntityKind.TASK,
                entityId = "00000000-0000-7000-8000-000000000041",
                operationKind = "TaskPut",
                beforeImageJson = "{\"title\":\"before\"}",
                afterImageJson = "{\"title\":\"after\"}",
                hlc = HlcTimestamp(1, 0, ReplicaId("00000000-0000-7000-8000-000000000042")),
            ))
        }

        override suspend fun undo(
            mutationId: String,
            onCommitted: suspend (MutationExecution<UndoResult>) -> Unit,
        ): UndoResult {
            undoCalls++
            if (throwOnUndo) error("secret exception detail")
            if (undoResult is UndoResult.Applied) {
                onCommitted(MutationExecution(undoResult, committedId))
                return UndoResult.Applied(committedId)
            }
            return undoResult
        }
    }
}
