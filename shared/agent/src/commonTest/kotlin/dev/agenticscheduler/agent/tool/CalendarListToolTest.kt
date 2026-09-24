package dev.agenticscheduler.agent.tool

import dev.agenticscheduler.agent.permission.AgentToolCapability
import dev.agenticscheduler.application.calendar.CalendarConflict
import dev.agenticscheduler.application.calendar.CalendarItem
import dev.agenticscheduler.application.calendar.CalendarProjectionIssue
import dev.agenticscheduler.application.calendar.CalendarProjectionResult
import dev.agenticscheduler.application.calendar.CalendarQueryService
import dev.agenticscheduler.application.calendar.CalendarViewport
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone

class CalendarListToolTest {
    @Test
    fun `calendar list delegates only the explicit viewport to the query service`() = runBlocking {
        val expected = CalendarProjectionResult(
            items = emptyList<CalendarItem>().toImmutableList(),
            conflicts = emptyList<CalendarConflict>().toImmutableList(),
            issues = emptyList<CalendarProjectionIssue>().toImmutableList(),
        )
        val queries = RecordingCalendarQueries(expected)
        val tool = CalendarListTool(queries)
        val viewport = CalendarViewport(
            startDate = LocalDate(2026, 9, 24),
            endDateExclusive = LocalDate(2026, 9, 25),
            displayTimeZone = TimeZone.of("Asia/Shanghai"),
        )

        assertEquals(AgentToolOutcome.Success(expected), tool.execute(viewport))
        assertEquals(listOf(viewport), queries.observedViewports)
        assertEquals(AgentToolNames.CALENDAR_LIST, tool.metadata.name)
        assertEquals(AgentToolCapability.READ, tool.metadata.capability)
        assertEquals(AgentToolAccess.READ, tool.metadata.access)
    }
}

private class RecordingCalendarQueries(
    private val result: CalendarProjectionResult,
) : CalendarQueryService {
    val observedViewports = mutableListOf<CalendarViewport>()

    override fun observe(viewport: CalendarViewport): Flow<CalendarProjectionResult> {
        observedViewports += viewport
        return flowOf(result)
    }
}
