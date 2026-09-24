package dev.agenticscheduler.agent.permission

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class AgentPermissionPolicyTest {
    private val engine = AgentPermissionEngine()

    @Test
    fun `frozen default permits only reads and previews directly`() {
        val policy = AgentPermissionPolicy.default()

        assertEquals(
            AgentPermissionDecision.AllowDirect,
            engine.evaluate(policy, AgentToolCapability.READ),
        )
        assertEquals(
            AgentPermissionDecision.AllowDirect,
            engine.evaluate(policy, AgentToolCapability.PLAN_PREVIEW),
        )
        listOf(
            AgentToolCapability.LOW_RISK_CREATE,
            AgentToolCapability.SOURCE_FACT_UPDATE,
            AgentToolCapability.PLANNING_PROFILE_CHANGE,
            AgentToolCapability.SCHEDULE_APPLY,
            AgentToolCapability.UNDO,
        ).forEach { capability ->
            assertEquals(
                AgentPermissionDecision.RequireConfirmation,
                engine.evaluate(policy, capability),
                "Expected $capability to require confirmation by default.",
            )
        }
    }

    @Test
    fun `bulk destructive and external capability classes stay deny only`() {
        val policy = AgentPermissionPolicy.default()

        listOf(
            AgentToolCapability.BULK_CHANGE,
            AgentToolCapability.DESTRUCTIVE,
            AgentToolCapability.EXTERNAL_SIDE_EFFECT,
        ).forEach { capability ->
            assertEquals(AgentPermissionDecision.Deny, engine.evaluate(policy, capability))
            assertFailsWith<IllegalArgumentException> {
                policy.withMode(capability, AgentPermissionMode.ALLOW_DIRECT)
            }
        }
    }

    @Test
    fun `user may explicitly relax supported write capability`() {
        val policy = AgentPermissionPolicy.default().withMode(
            AgentToolCapability.LOW_RISK_CREATE,
            AgentPermissionMode.ALLOW_DIRECT,
        )

        assertEquals(
            AgentPermissionDecision.AllowDirect,
            engine.evaluate(policy, AgentToolCapability.LOW_RISK_CREATE),
        )
    }

    @Test
    fun `reconstructed policy remains complete and validates deny only capabilities`() {
        val defaults = AgentToolCapability.entries.associateWith {
            AgentPermissionPolicy.default().modeFor(it)
        }

        assertEquals(
            AgentPermissionDecision.RequireConfirmation,
            engine.evaluate(
                AgentPermissionPolicy.fromModes(defaults),
                AgentToolCapability.SCHEDULE_APPLY,
            ),
        )
        assertFailsWith<IllegalArgumentException> {
            AgentPermissionPolicy.fromModes(defaults - AgentToolCapability.READ)
        }
    }
}
