package dev.agenticscheduler.agent.context

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ContextAssemblerTest {
    private val meter = object : BudgetMeter {
        override val maxContextUnits = 200L
        override val reservedOutputUnits = 20L
        override fun measure(serialized: String) = serialized.length.toLong()
    }

    @Test fun `mandatory context fails rather than dropping command or tools`() {
        val result = ContextAssembler(meter).assemble(ContextRequest("S".repeat(100), listOf("tool" to "T".repeat(60)), "command", null, emptyList()))
        assertIs<ContextAssemblyResult.ContextTooLarge>(result)
        assertEquals(160, result.maxInputUnits)
    }

    @Test fun `current facts outrank summaries with the same key and tie ordering is stable`() {
        val candidates = listOf(
            ContextCandidate("summary", ContextClass.SUMMARY, "stale", 9, 9, "task:1"),
            ContextCandidate("b", ContextClass.CURRENT_DOMAIN, "current-b", 1, 1, "task:1"),
            ContextCandidate("a", ContextClass.CURRENT_DOMAIN, "current-a", 1, 1, "task:2"),
            ContextCandidate("history", ContextClass.RETRIEVED_HISTORY, "history", 0, 0),
        )
        val request = ContextRequest("system", listOf("z" to "schema-z", "a" to "schema-a"), "command", "anchor", candidates)
        val first = assertIs<ContextAssemblyResult.Ready>(ContextAssembler(meter).assemble(request))
        val second = assertIs<ContextAssemblyResult.Ready>(ContextAssembler(meter).assemble(request.copy(candidates = candidates.reversed())))
        assertEquals(first, second)
        assertEquals(listOf("system", "a", "z", "command", "anchor", "a", "b", "history"), first.parts.map(ContextPart::id))
        assertTrue(first.parts.none { it.id == "summary" })
    }

    @Test fun `unused class capacity flows to later history`() {
        val candidates = listOf(ContextCandidate("history", ContextClass.RETRIEVED_HISTORY, "h".repeat(75), 0, 0))
        val result = assertIs<ContextAssemblyResult.Ready>(ContextAssembler(meter).assemble(ContextRequest("S", emptyList(), "C", null, candidates)))
        assertTrue(result.parts.any { it.id == "history" })
    }
}
