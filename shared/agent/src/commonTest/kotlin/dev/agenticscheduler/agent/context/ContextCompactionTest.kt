package dev.agenticscheduler.agent.context

import dev.agenticscheduler.agent.history.AgentMessage
import dev.agenticscheduler.agent.history.AgentMessageId
import dev.agenticscheduler.agent.history.AgentMessageRole
import dev.agenticscheduler.agent.history.AgentThreadId
import dev.agenticscheduler.agent.history.ContextSummary
import dev.agenticscheduler.application.id.EpochMillisecondsClock
import dev.agenticscheduler.application.id.UuidV7Generator
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ContextCompactionTest {
    private val thread = AgentThreadId(id(1))
    private val messages = (0 until 18).map { index ->
        AgentMessage(AgentMessageId(id(index + 2)), thread, index.toLong(), AgentMessageRole.USER, "message $index", index.toLong())
    }

    @Test fun `compaction retains recent and unresolved messages`() {
        val selected = assertIs<CompactionSelection.Prefix>(ContextCompactionSelector.select(
            messages, null, setOf(messages[4].id), CompactionPressure(30, 90, 100),
        ))
        assertEquals(messages.take(4), selected.messages)
        assertTrue(selected.messages.none { it.id == messages[4].id })
    }

    @Test fun `summary failure preserves raw history and incremental ranges`() = runBlocking {
        val stored = mutableListOf<ContextSummary>()
        var nextId = 50
        val ids = object : UuidV7Generator { override fun next() = id(nextId++) }
        val service = ContextCompactionService(
            ContextSummaryProvider { _, _ -> "summary" }, stored::add,
            ids, EpochMillisecondsClock { 100 }, 1,
        )
        val first = assertIs<ContextCompactionResult.Saved>(service.compact(
            messages, null, emptySet(), CompactionPressure(30, 90, 100), null, null,
        )).summary
        assertEquals(messages.first().id, first.sourceStartMessageId)
        assertEquals(messages[5].id, first.sourceEndMessageId)
        val larger = messages + (18 until 25).map { index ->
            AgentMessage(AgentMessageId(id(index + 2)), thread, index.toLong(), AgentMessageRole.USER, "message $index", index.toLong())
        }
        val second = assertIs<ContextCompactionResult.Saved>(service.compact(
            larger, first, emptySet(), CompactionPressure(30, 90, 100), null, null,
        )).summary
        assertEquals(first.sourceStartMessageId, second.sourceStartMessageId)
        assertEquals(larger[12].id, second.sourceEndMessageId)
        assertEquals(2, stored.size)

        val failing = ContextCompactionService(
            ContextSummaryProvider { _, _ -> error("offline") }, stored::add,
            ids, EpochMillisecondsClock { 101 }, 1,
        )
        val newest = larger + (25 until 32).map { index ->
            AgentMessage(AgentMessageId(id(index + 2)), thread, index.toLong(), AgentMessageRole.USER, "message $index", index.toLong())
        }
        assertIs<ContextCompactionResult.Failed>(failing.compact(
            newest, second, emptySet(), CompactionPressure(30, 90, 100), null, null,
        ))
        assertEquals(2, stored.size)
        assertEquals(32, newest.size)
    }

    private fun id(number: Int) = "00000000-0000-7000-8000-${number.toString().padStart(12, '0')}"
}
