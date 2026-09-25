package dev.agenticscheduler.database

import dev.agenticscheduler.agent.history.*
import dev.agenticscheduler.agent.provider.*
import dev.agenticscheduler.agent.runtime.*
import dev.agenticscheduler.agent.tool.*
import dev.agenticscheduler.application.editing.TaskEditingService
import dev.agenticscheduler.application.history.AgentOriginWriteGate
import dev.agenticscheduler.application.history.MutationCoordinator
import dev.agenticscheduler.application.history.MutationWallClock
import dev.agenticscheduler.application.history.NoActiveSyncSpaceWritePolicy
import dev.agenticscheduler.application.history.HistoryQueryService
import dev.agenticscheduler.application.id.EpochMillisecondsClock
import dev.agenticscheduler.application.id.RandomBytes
import dev.agenticscheduler.application.id.RfcUuidV7Generator
import dev.agenticscheduler.database.repository.RoomAgentStateRepository
import dev.agenticscheduler.database.repository.RoomApplicationTransactionRunner
import dev.agenticscheduler.database.repository.RoomMutationJournalRepository
import dev.agenticscheduler.database.repository.RoomTaskRepository
import dev.agenticscheduler.domain.id.TaskId
import dev.agenticscheduler.domain.task.Task
import dev.agenticscheduler.domain.task.TaskEffort
import dev.agenticscheduler.domain.task.TaskPriority
import dev.agenticscheduler.domain.task.TaskStatus
import dev.agenticscheduler.sync.MutationOrigin
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.ZERO

class AgentRunIntegrationTest {
    private val json = Json { encodeDefaults = true; explicitNulls = true }

    @Test fun `model proposal local read confirmation Task commit and history share one MutationId`() = runBlocking {
        val database = openInMemoryDesktopDatabase()
        val requests = mutableListOf<HttpRequestData>()
        val existing = Task(TaskId(id(50)), "Existing task", TaskStatus.OPEN, TaskPriority.NORMAL, TaskEffort(null, ZERO, null), null)
        val createInput = TaskCreateToolInput("New task", "HIGH", 45, null, null)
        val replies = mutableListOf(
            reply(ProviderChatMessage("assistant", toolCalls = listOf(ProviderToolCall("probe-1", function = ProviderFunctionCall("d9_capability_probe", "{}"))))),
            reply(ProviderChatMessage("assistant", toolCalls = listOf(ProviderToolCall("get-1", function = ProviderFunctionCall(AgentToolNames.TASK_GET, "{\"taskId\":\"${existing.id.value}\"}"))))),
            reply(ProviderChatMessage("assistant", toolCalls = listOf(ProviderToolCall("create-1", function = ProviderFunctionCall(AgentToolNames.TASK_CREATE, json.encodeToString(createInput)))))),
            reply(ProviderChatMessage("assistant", "Created after confirmation.")),
        )
        val client = HttpClient(MockEngine) { engine { addHandler { request ->
            requests += request
            respond(replies.removeAt(0), headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()))
        } } }
        try {
            var seed = 0
            val ids = RfcUuidV7Generator(EpochMillisecondsClock { 1_700_000_000_000 }, RandomBytes { size -> ByteArray(size) { (seed++).toByte() } })
            val state = RoomAgentStateRepository(database)
            val tasks = RoomTaskRepository(database)
            val history = RoomMutationJournalRepository(database)
            tasks.upsertTask(existing)
            val config = ProviderConfig(ProviderConfigId(id(51)), "https://model.example/v1", "chosen-model", 8192, 2048, false, true, null)
            state.saveProviderConfig(config)
            state.selectProviderConfig(config.id)
            val coordinator = MutationCoordinator(RoomApplicationTransactionRunner(database), history, ids, MutationWallClock { 5 }, AgentOriginWriteGate { true })
            val runtime = AgentRunService(state, OpenAiCompatibleProvider(client, ProviderCredentialResolver { null }),
                TaskGetTool(tasks), TaskCreateTool(TaskEditingService(tasks, ids, coordinator, NoActiveSyncSpaceWritePolicy)), ids, AgentClock { 6 })
            val threadId = runtime.createThread()
            val oldMessage = AgentMessage(AgentMessageId(id(52)), threadId, 0, AgentMessageRole.USER, "Old context", 1)
            state.appendMessage(oldMessage)
            state.appendSummary(ContextSummary(ContextSummaryId(id(53)), threadId, oldMessage.id, oldMessage.id, 1,
                "Existing task is COMPLETED", 2, config.id, config.model))

            val pending = assertIs<AgentRunResult.AwaitingConfirmation>(runtime.run(threadId, "Read my existing task, then create a new one."))
            assertTrue(pending.previewJson.contains("New task"))
            assertEquals(listOf(existing), tasks.observeTasks().first())
            assertTrue(history.timeline().isEmpty())
            assertEquals("Created after confirmation.", assertIs<AgentRunResult.Completed>(runtime.confirm(threadId, pending.callId, true)).assistantText)

            val committed = history.timeline().single()
            val origin = assertIs<MutationOrigin.Agent>(committed.operation.origin)
            val action = requireNotNull(state.action(AgentActionId(origin.agentActionId)))
            val mutationId = action.mutationIds.single()
            assertEquals(committed.operation.mutationId, mutationId.value)
            assertEquals(mutationId.value, history.diff(mutationId.value).single().mutationId)
            assertEquals(mutationId, state.toolResults(threadId).single { it.mutationIds.isNotEmpty() }.mutationIds.single())
            assertEquals(2, tasks.observeTasks().first().size)
            val afterReadRequest = (requests[2].body as TextContent).text
            assertTrue(afterReadRequest.contains("get-1"))
            assertTrue(afterReadRequest.indexOf("potentially stale summary") in 0 until afterReadRequest.lastIndexOf("OPEN"))
            assertTrue((requests[3].body as TextContent).text.contains("create-1"))
        } finally {
            client.close()
            database.close()
        }
    }

    @Test fun `denied and stale model proposals never write`() = runBlocking {
        for (stale in listOf(false, true)) {
            val database = openInMemoryDesktopDatabase()
            val arguments = json.encodeToString(TaskCreateToolInput("No write", "NORMAL", null, null, null))
            val replies = mutableListOf(
                reply(ProviderChatMessage("assistant", toolCalls = listOf(ProviderToolCall("probe-1", function = ProviderFunctionCall("d9_capability_probe", "{}"))))),
                reply(ProviderChatMessage("assistant", toolCalls = listOf(ProviderToolCall("create-1", function = ProviderFunctionCall(AgentToolNames.TASK_CREATE, arguments))))),
                reply(ProviderChatMessage("assistant", "No task was created.")),
            )
            val client = HttpClient(MockEngine) { engine { addHandler {
                respond(replies.removeAt(0), headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()))
            } } }
            try {
                var seed = 0
                val ids = RfcUuidV7Generator(EpochMillisecondsClock { 1_700_000_000_000 }, RandomBytes { size -> ByteArray(size) { (seed++).toByte() } })
                val state = RoomAgentStateRepository(database)
                val tasks = RoomTaskRepository(database)
                val history = RoomMutationJournalRepository(database)
                val config = ProviderConfig(ProviderConfigId(id(60)), "https://model.example/v1", "chosen-model", 8192, 2048, false, true, null)
                state.saveProviderConfig(config)
                state.selectProviderConfig(config.id)
                val coordinator = MutationCoordinator(RoomApplicationTransactionRunner(database), history, ids, MutationWallClock { 5 }, AgentOriginWriteGate { true })
                val runtime = AgentRunService(state, OpenAiCompatibleProvider(client, ProviderCredentialResolver { null }),
                    TaskGetTool(tasks), TaskCreateTool(TaskEditingService(tasks, ids, coordinator, NoActiveSyncSpaceWritePolicy)), ids, AgentClock { 6 })
                val threadId = runtime.createThread()
                val pending = assertIs<AgentRunResult.AwaitingConfirmation>(runtime.run(threadId, "Create a task"))
                if (stale) {
                    val call = state.toolCalls(threadId).single()
                    state.saveToolCall(call.copy(previewJson = "stale"))
                }
                assertIs<AgentRunResult.Completed>(runtime.confirm(threadId, pending.callId, approved = stale))
                assertTrue(tasks.observeTasks().first().isEmpty())
                assertTrue(history.timeline().isEmpty())
                val result = state.toolResults(threadId).single()
                assertEquals(if (stale) AgentToolResultStatus.STALE else AgentToolResultStatus.PERMISSION_DENIED, result.status)
                assertTrue(result.mutationIds.isEmpty())
            } finally {
                client.close()
                database.close()
            }
        }
    }

    @Test fun `prose unknown tools and failed capability probe cannot bypass registry`() = runBlocking {
        val cases = listOf(
            Triple(true, ProviderChatMessage("assistant", "I created it."), true),
            Triple(true, ProviderChatMessage("assistant", toolCalls = listOf(ProviderToolCall("bad-1", function = ProviderFunctionCall("database.execute", "{}")))), false),
            Triple(false, ProviderChatMessage("assistant", toolCalls = listOf(ProviderToolCall("bad-2", function = ProviderFunctionCall(AgentToolNames.TASK_CREATE, "{}")))), false),
        )
        cases.forEach { (probeSupported, modelMessage, prose) ->
            val database = openInMemoryDesktopDatabase()
            val requests = mutableListOf<HttpRequestData>()
            val replies = mutableListOf(
                reply(if (probeSupported) ProviderChatMessage("assistant", toolCalls = listOf(ProviderToolCall("probe-1", function = ProviderFunctionCall("d9_capability_probe", "{}")))) else ProviderChatMessage("assistant", "No tools")),
                reply(modelMessage),
            )
            val client = HttpClient(MockEngine) { engine { addHandler { request ->
                requests += request
                respond(replies.removeAt(0), headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()))
            } } }
            try {
                var seed = 0
                val ids = RfcUuidV7Generator(EpochMillisecondsClock { 1_700_000_000_000 }, RandomBytes { size -> ByteArray(size) { (seed++).toByte() } })
                val state = RoomAgentStateRepository(database)
                val tasks = RoomTaskRepository(database)
                val history = RoomMutationJournalRepository(database)
                val config = ProviderConfig(ProviderConfigId(id(70)), "https://model.example/v1", "chosen-model", 8192, 2048, false, true, null)
                state.saveProviderConfig(config)
                state.selectProviderConfig(config.id)
                val coordinator = MutationCoordinator(RoomApplicationTransactionRunner(database), history, ids, MutationWallClock { 5 }, AgentOriginWriteGate { true })
                val runtime = AgentRunService(state, OpenAiCompatibleProvider(client, ProviderCredentialResolver { null }),
                    TaskGetTool(tasks), TaskCreateTool(TaskEditingService(tasks, ids, coordinator, NoActiveSyncSpaceWritePolicy)), ids, AgentClock { 6 })
                val threadId = runtime.createThread()
                val outcome = runtime.run(threadId, "Create a task")
                if (prose) assertIs<AgentRunResult.Completed>(outcome) else assertEquals(AgentRunResult.Failed("UNREGISTERED_TOOL"), outcome)
                assertTrue(tasks.observeTasks().first().isEmpty())
                assertTrue(history.timeline().isEmpty())
                if (!probeSupported) assertTrue(!(requests.last().body as TextContent).text.contains("\"tools\""))
            } finally {
                client.close()
                database.close()
            }
        }
    }

    @Test fun `task list and history timeline stay read only in the provider registry`() = runBlocking {
        for ((toolName, arguments) in listOf(
            AgentToolNames.TASK_LIST to "{\"status\":null}",
            AgentToolNames.HISTORY_TIMELINE to "{\"limit\":10}",
        )) {
            val database = openInMemoryDesktopDatabase()
            val replies = mutableListOf(
                reply(ProviderChatMessage("assistant", toolCalls = listOf(ProviderToolCall("probe-1", function = ProviderFunctionCall("d9_capability_probe", "{}"))))),
                reply(ProviderChatMessage("assistant", toolCalls = listOf(ProviderToolCall("read-1", function = ProviderFunctionCall(toolName, arguments))))),
                reply(ProviderChatMessage("assistant", "Read complete.")),
            )
            val client = HttpClient(MockEngine) { engine { addHandler {
                respond(replies.removeAt(0), headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()))
            } } }
            try {
                var seed = 0
                val ids = RfcUuidV7Generator(EpochMillisecondsClock { 1_700_000_000_000 }, RandomBytes { size -> ByteArray(size) { (seed++).toByte() } })
                val state = RoomAgentStateRepository(database)
                val tasks = RoomTaskRepository(database)
                val history = RoomMutationJournalRepository(database)
                val config = ProviderConfig(ProviderConfigId(id(80)), "https://model.example/v1", "chosen-model", 8192, 2048, false, true, null)
                state.saveProviderConfig(config)
                state.selectProviderConfig(config.id)
                val coordinator = MutationCoordinator(RoomApplicationTransactionRunner(database), history, ids, MutationWallClock { 5 }, AgentOriginWriteGate { true })
                val runtime = AgentRunService(state, OpenAiCompatibleProvider(client, ProviderCredentialResolver { null }),
                    TaskGetTool(tasks), TaskCreateTool(TaskEditingService(tasks, ids, coordinator, NoActiveSyncSpaceWritePolicy)), ids, AgentClock { 6 },
                    TaskListTool(tasks), HistoryReadTools(HistoryQueryService(history)))
                val threadId = runtime.createThread()
                assertEquals("Read complete.", assertIs<AgentRunResult.Completed>(runtime.run(threadId, "Show me records")).assistantText)
                assertEquals(AgentToolResultStatus.SUCCESS, state.toolResults(threadId).single().status)
                assertTrue(history.timeline().isEmpty())
                assertTrue(tasks.observeTasks().first().isEmpty())
            } finally {
                client.close()
                database.close()
            }
        }
    }

    @Test fun `oversized authoritative read result stops before provider retry and preserves raw history`() = runBlocking {
        val database = openInMemoryDesktopDatabase()
        val task = Task(TaskId(id(90)), "A".repeat(5_000), TaskStatus.OPEN, TaskPriority.NORMAL, TaskEffort(null, ZERO, null), null)
        val replies = mutableListOf(
            reply(ProviderChatMessage("assistant", toolCalls = listOf(ProviderToolCall("probe-1", function = ProviderFunctionCall("d9_capability_probe", "{}"))))),
            reply(ProviderChatMessage("assistant", toolCalls = listOf(ProviderToolCall("get-1", function = ProviderFunctionCall(AgentToolNames.TASK_GET, "{\"taskId\":\"${task.id.value}\"}"))))),
        )
        val client = HttpClient(MockEngine) { engine { addHandler {
            respond(replies.removeAt(0), headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()))
        } } }
        try {
            var seed = 0
            val ids = RfcUuidV7Generator(EpochMillisecondsClock { 1_700_000_000_000 }, RandomBytes { size -> ByteArray(size) { (seed++).toByte() } })
            val state = RoomAgentStateRepository(database)
            val tasks = RoomTaskRepository(database)
            val history = RoomMutationJournalRepository(database)
            tasks.upsertTask(task)
            val config = ProviderConfig(ProviderConfigId(id(91)), "https://model.example/v1", "chosen-model", 4096, 1024, false, true, null)
            state.saveProviderConfig(config)
            state.selectProviderConfig(config.id)
            val coordinator = MutationCoordinator(RoomApplicationTransactionRunner(database), history, ids, MutationWallClock { 5 }, AgentOriginWriteGate { true })
            val runtime = AgentRunService(state, OpenAiCompatibleProvider(client, ProviderCredentialResolver { null }),
                TaskGetTool(tasks), TaskCreateTool(TaskEditingService(tasks, ids, coordinator, NoActiveSyncSpaceWritePolicy)), ids, AgentClock { 6 })
            val threadId = runtime.createThread()
            assertEquals(AgentRunResult.Failed("CONTEXT_TOO_LARGE"), runtime.run(threadId, "Read the task"))
            assertTrue(replies.isEmpty())
            assertEquals(AgentToolResultStatus.SUCCESS, state.toolResults(threadId).single().status)
            assertEquals(2, state.messages(threadId).size)
            assertTrue(history.timeline().isEmpty())
        } finally {
            client.close()
            database.close()
        }
    }

    private fun reply(message: ProviderChatMessage): String = """{"choices":[{"message":${json.encodeToString(message)}}]}"""
    private fun id(number: Int) = "00000000-0000-7000-8000-${number.toString().padStart(12, '0')}"
}
