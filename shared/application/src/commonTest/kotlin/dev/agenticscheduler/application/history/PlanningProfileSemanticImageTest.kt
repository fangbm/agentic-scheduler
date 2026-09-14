package dev.agenticscheduler.application.history

import dev.agenticscheduler.domain.id.PlanningProfileId
import dev.agenticscheduler.domain.planning.PlanningProfile
import dev.agenticscheduler.domain.planning.PlanningProfileConfiguration
import kotlin.test.Test
import kotlin.test.assertEquals

class PlanningProfileSemanticImageTest {
    @Test
    fun `unconfigured planning profile round trips through typed semantic image`() {
        val profile = PlanningProfile(
            id = PlanningProfileId("00000000-0000-7000-8000-000000000001"),
            name = "Unconfigured",
            configuration = PlanningProfileConfiguration.Unconfigured,
        )

        assertEquals(profile, profile.toSemanticImage().toDomain())
    }
}
