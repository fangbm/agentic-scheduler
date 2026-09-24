package dev.agenticscheduler.agent.permission

/**
 * The local, user-owned authority for one class of Agent Tool.
 *
 * This model deliberately has no sync or provider-facing representation. The Agent itself
 * receives only an evaluation result; it never receives a mutable policy surface.
 */
enum class AgentToolCapability {
    READ,
    PLAN_PREVIEW,
    LOW_RISK_CREATE,
    SOURCE_FACT_UPDATE,
    PLANNING_PROFILE_CHANGE,
    SCHEDULE_APPLY,
    UNDO,
    BULK_CHANGE,
    DESTRUCTIVE,
    EXTERNAL_SIDE_EFFECT,
}

enum class AgentPermissionMode {
    ALLOW_DIRECT,
    REQUIRE_CONFIRMATION,
    DENY,
}

sealed interface AgentPermissionDecision {
    data object AllowDirect : AgentPermissionDecision

    data object RequireConfirmation : AgentPermissionDecision

    data object Deny : AgentPermissionDecision
}

/**
 * A complete local permission policy.
 *
 * D9 v1 has no bulk, destructive, or external-side-effect Tool. Those capability classes are
 * intentionally deny-only until a later task defines a concrete, reviewed Tool for them.
 */
class AgentPermissionPolicy private constructor(
    private val modes: Map<AgentToolCapability, AgentPermissionMode>,
) {
    init {
        require(modes.keys == AgentToolCapability.entries.toSet()) {
            "Agent permission policy must define every capability."
        }
        denyOnlyCapabilities.forEach { capability ->
            require(modes.getValue(capability) == AgentPermissionMode.DENY) {
                "$capability is deny-only in D9 v1."
            }
        }
    }

    fun modeFor(capability: AgentToolCapability): AgentPermissionMode = modes.getValue(capability)

    fun withMode(
        capability: AgentToolCapability,
        mode: AgentPermissionMode,
    ): AgentPermissionPolicy = AgentPermissionPolicy(modes + (capability to mode))

    companion object {
        private val denyOnlyCapabilities = setOf(
            AgentToolCapability.BULK_CHANGE,
            AgentToolCapability.DESTRUCTIVE,
            AgentToolCapability.EXTERNAL_SIDE_EFFECT,
        )

        fun default(): AgentPermissionPolicy = AgentPermissionPolicy(
            modes = mapOf(
                AgentToolCapability.READ to AgentPermissionMode.ALLOW_DIRECT,
                AgentToolCapability.PLAN_PREVIEW to AgentPermissionMode.ALLOW_DIRECT,
                AgentToolCapability.LOW_RISK_CREATE to AgentPermissionMode.REQUIRE_CONFIRMATION,
                AgentToolCapability.SOURCE_FACT_UPDATE to AgentPermissionMode.REQUIRE_CONFIRMATION,
                AgentToolCapability.PLANNING_PROFILE_CHANGE to AgentPermissionMode.REQUIRE_CONFIRMATION,
                AgentToolCapability.SCHEDULE_APPLY to AgentPermissionMode.REQUIRE_CONFIRMATION,
                AgentToolCapability.UNDO to AgentPermissionMode.REQUIRE_CONFIRMATION,
                AgentToolCapability.BULK_CHANGE to AgentPermissionMode.DENY,
                AgentToolCapability.DESTRUCTIVE to AgentPermissionMode.DENY,
                AgentToolCapability.EXTERNAL_SIDE_EFFECT to AgentPermissionMode.DENY,
            ),
        )

        /**
         * Reconstructs a complete local policy after the application-owned persistence layer
         * has loaded it. This does not create a Tool for reading or changing policy.
         */
        fun fromModes(modes: Map<AgentToolCapability, AgentPermissionMode>): AgentPermissionPolicy =
            AgentPermissionPolicy(modes.toMap())
    }
}

/** The only permission interpretation that Tool execution may use. */
class AgentPermissionEngine {
    fun evaluate(
        policy: AgentPermissionPolicy,
        capability: AgentToolCapability,
    ): AgentPermissionDecision = when (policy.modeFor(capability)) {
        AgentPermissionMode.ALLOW_DIRECT -> AgentPermissionDecision.AllowDirect
        AgentPermissionMode.REQUIRE_CONFIRMATION -> AgentPermissionDecision.RequireConfirmation
        AgentPermissionMode.DENY -> AgentPermissionDecision.Deny
    }
}
