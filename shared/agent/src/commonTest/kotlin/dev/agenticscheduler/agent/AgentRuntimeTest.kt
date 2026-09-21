package dev.agenticscheduler.agent

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AgentRuntimeTest {
    @Test
    fun `run loop executes known tools and stops at final response`() = runBlocking {
        var calls = 0
        val model = object : AgentModelClient {
            override suspend fun turn(request: AgentTurnRequestV1, tools: List<AgentToolDefinitionV1>): AgentTurnResponseV1 =
                if (request.toolResults.isEmpty()) AgentTurnResponseV1(request.runId, toolCalls = listOf(AgentToolCallV1("call-1", "task.list", "{}")))
                else AgentTurnResponseV1(request.runId, assistantText = "done")
        }
        val result = AgentRunLoop(model, GeneralSchedulerSkillV1.tools, AgentToolExecutor {
            calls++
            AgentToolResultV1(it.id, it.name, "{\"ok\":true}")
        }).run(AgentTurnRequestV1("run-1", listOf(AgentMessageV1("user", "show tasks"))))
        assertEquals("done", result.response.assistantText)
        assertEquals(1, calls)
        assertTrue(result.pendingConfirmations.isEmpty())
        assertEquals(2, result.steps)
    }

    @Test
    fun `unknown model tool is surfaced as an error result`() = runBlocking {
        val result = AgentRunLoop(
            object : AgentModelClient {
                override suspend fun turn(request: AgentTurnRequestV1, tools: List<AgentToolDefinitionV1>) =
                    if (request.toolResults.isEmpty()) AgentTurnResponseV1(request.runId, toolCalls = listOf(AgentToolCallV1("call-1", "sql.execute", "{}")))
                    else AgentTurnResponseV1(request.runId, assistantText = "blocked")
            },
            GeneralSchedulerSkillV1.tools,
            AgentToolExecutor { error("must not execute unknown tool") },
        ).run(AgentTurnRequestV1("run-1", listOf(AgentMessageV1("user", "do it"))))
        assertTrue(result.toolResults.single().isError)
        assertTrue(result.pendingConfirmations.isEmpty())
        assertEquals("blocked", result.response.assistantText)
    }

    @Test
    fun `write tool is held for confirmation`() = runBlocking {
        var executed = false
        val result = AgentRunLoop(
            object : AgentModelClient {
                override suspend fun turn(request: AgentTurnRequestV1, tools: List<AgentToolDefinitionV1>) =
                    if (request.toolResults.isEmpty()) AgentTurnResponseV1(request.runId, toolCalls = listOf(AgentToolCallV1("call-1", "task.create", "{}")))
                    else AgentTurnResponseV1(request.runId, assistantText = "waiting")
            },
            GeneralSchedulerSkillV1.tools,
            AgentToolExecutor { executed = true; AgentToolResultV1(it.id, it.name, "{}") },
        ).run(AgentTurnRequestV1("run-1", listOf(AgentMessageV1("user", "create a task"))))
        assertTrue(result.pendingConfirmations.single().name == "task.create")
        assertTrue(!executed)
    }
}
