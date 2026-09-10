package dev.agenticscheduler.domain.id

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class EntityIdsTest {
    @Test
    fun `every typed id accepts lowercase UUIDv7`() {
        assertEquals("018f6e68-7d0c-7000-8000-000000000001", EventId("018f6e68-7d0c-7000-8000-000000000001").value)
        assertEquals("018f6e68-7d0c-7000-8000-000000000002", TaskId("018f6e68-7d0c-7000-8000-000000000002").value)
        assertEquals("018f6e68-7d0c-7000-8000-000000000003", FocusBlockId("018f6e68-7d0c-7000-8000-000000000003").value)
        assertEquals("018f6e68-7d0c-7000-8000-000000000004", WorkLogId("018f6e68-7d0c-7000-8000-000000000004").value)
        assertEquals("018f6e68-7d0c-7000-8000-000000000005", TaskDependencyId("018f6e68-7d0c-7000-8000-000000000005").value)
        assertEquals("018f6e68-7d0c-7000-8000-000000000006", PlanningProfileId("018f6e68-7d0c-7000-8000-000000000006").value)
    }

    @Test
    fun `IDs reject noncanonical values`() {
        assertFailsWith<IllegalArgumentException> { TaskId("not-a-uuid") }
        assertFailsWith<IllegalArgumentException> { TaskId("018f6e68-7d0c-4000-8000-000000000002") }
        assertFailsWith<IllegalArgumentException> { TaskId("018F6E68-7D0C-7000-8000-000000000002") }
        assertFailsWith<IllegalArgumentException> { TaskId("018f6e68-7d0c-7000-c000-000000000002") }
    }

    @Test
    fun `typed IDs remain distinct API types`() {
        val eventId = EventId("018f6e68-7d0c-7000-8000-000000000001")
        assertEquals(eventId, acceptsEventId(eventId))
    }

    private fun acceptsEventId(id: EventId): EventId = id
}
