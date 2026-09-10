package dev.agenticscheduler.domain.planning

import dev.agenticscheduler.domain.id.PlanningProfileId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class PlanningProfileTest {
    @Test
    fun `PlanningProfile accepts nonblank name`() {
        val profile = PlanningProfile(PlanningProfileId("018f6e68-7d0c-7000-8000-000000000006"), "Study")
        assertEquals("Study", profile.name)
    }

    @Test
    fun `PlanningProfile rejects blank name`() {
        assertFailsWith<IllegalArgumentException> {
            PlanningProfile(PlanningProfileId("018f6e68-7d0c-7000-8000-000000000006"), "  ")
        }
    }
}
