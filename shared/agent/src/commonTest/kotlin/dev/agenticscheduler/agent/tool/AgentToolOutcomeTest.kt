package dev.agenticscheduler.agent.tool

import dev.agenticscheduler.agent.permission.AgentToolCapability
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class AgentToolOutcomeTest {
    @Test
    fun `outcomes keep expected execution states structured and redacted`() {
        assertEquals(
            AgentToolOutcome.PermissionDenied(AgentToolCapability.SCHEDULE_APPLY),
            AgentToolOutcome.PermissionDenied(AgentToolCapability.SCHEDULE_APPLY),
        )
        assertEquals(
            AgentToolOutcome.InfrastructureFailure("provider_unavailable"),
            AgentToolOutcome.InfrastructureFailure("provider_unavailable"),
        )
        assertFailsWith<IllegalArgumentException> {
            AgentToolOutcome.InvalidInput(emptyList())
        }
        assertFailsWith<IllegalArgumentException> {
            AgentToolOutcome.InfrastructureFailure(" ")
        }
    }
}
