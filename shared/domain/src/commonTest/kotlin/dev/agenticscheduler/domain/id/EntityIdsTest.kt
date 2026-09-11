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
        assertEquals("018f6e68-7d0c-7000-8000-00000000000f", PlanBranchId("018f6e68-7d0c-7000-8000-00000000000f").value)
        assertEquals("018f6e68-7d0c-7000-8000-000000000007", AcademicYearId("018f6e68-7d0c-7000-8000-000000000007").value)
        assertEquals("018f6e68-7d0c-7000-8000-000000000008", SemesterId("018f6e68-7d0c-7000-8000-000000000008").value)
        assertEquals("018f6e68-7d0c-7000-8000-000000000009", CourseId("018f6e68-7d0c-7000-8000-000000000009").value)
        assertEquals("018f6e68-7d0c-7000-8000-00000000000a", CourseScheduleRuleId("018f6e68-7d0c-7000-8000-00000000000a").value)
        assertEquals("018f6e68-7d0c-7000-8000-00000000000b", CourseOccurrenceExceptionId("018f6e68-7d0c-7000-8000-00000000000b").value)
        assertEquals("018f6e68-7d0c-7000-8000-00000000000c", ExamId("018f6e68-7d0c-7000-8000-00000000000c").value)
        assertEquals("018f6e68-7d0c-7000-8000-00000000000d", AcademicHolidayId("018f6e68-7d0c-7000-8000-00000000000d").value)
        assertEquals("018f6e68-7d0c-7000-8000-00000000000e", PeriodTemplateId("018f6e68-7d0c-7000-8000-00000000000e").value)
    }

    @Test
    fun `IDs reject noncanonical values`() {
        assertFailsWith<IllegalArgumentException> { TaskId("not-a-uuid") }
        assertFailsWith<IllegalArgumentException> { TaskId("018f6e68-7d0c-4000-8000-000000000002") }
        assertFailsWith<IllegalArgumentException> { TaskId("018F6E68-7D0C-7000-8000-000000000002") }
        assertFailsWith<IllegalArgumentException> { TaskId("018f6e68-7d0c-7000-c000-000000000002") }
        assertFailsWith<IllegalArgumentException> { AcademicYearId("018f6e68-7d0c-4000-8000-000000000007") }
        assertFailsWith<IllegalArgumentException> { SemesterId("018F6E68-7D0C-7000-8000-000000000008") }
        assertFailsWith<IllegalArgumentException> { CourseId("018f6e68-7d0c-7000-c000-000000000009") }
        assertFailsWith<IllegalArgumentException> { CourseScheduleRuleId("invalid") }
        assertFailsWith<IllegalArgumentException> { CourseOccurrenceExceptionId("invalid") }
        assertFailsWith<IllegalArgumentException> { ExamId("invalid") }
        assertFailsWith<IllegalArgumentException> { AcademicHolidayId("invalid") }
        assertFailsWith<IllegalArgumentException> { PeriodTemplateId("invalid") }
        assertFailsWith<IllegalArgumentException> { PlanBranchId("invalid") }
    }

    @Test
    fun `every D3 ID family rejects every noncanonical UUID category`() {
        val d3Factories: List<(String) -> Any> = listOf(
            ::AcademicYearId, ::SemesterId, ::CourseId, ::CourseScheduleRuleId,
            ::CourseOccurrenceExceptionId, ::ExamId, ::AcademicHolidayId, ::PeriodTemplateId,
        )
        val invalidValues = listOf(
            "not-a-uuid",
            "018f6e68-7d0c-4000-8000-000000000001",
            "018F6E68-7D0C-7000-8000-000000000001",
            "018f6e68-7d0c-7000-c000-000000000001",
        )
        d3Factories.forEach { factory ->
            invalidValues.forEach { invalid -> assertFailsWith<IllegalArgumentException> { factory(invalid) } }
        }
    }

    @Test
    fun `typed IDs remain distinct API types`() {
        val eventId = EventId("018f6e68-7d0c-7000-8000-000000000001")
        assertEquals(eventId, acceptsEventId(eventId))
    }

    private fun acceptsEventId(id: EventId): EventId = id
}
