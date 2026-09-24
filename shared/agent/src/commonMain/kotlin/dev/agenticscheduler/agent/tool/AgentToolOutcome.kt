package dev.agenticscheduler.agent.tool

import dev.agenticscheduler.agent.permission.AgentToolCapability

/** A redacted, field-scoped validation issue safe to return to a model or UI. */
data class AgentToolInputIssue(
    val field: String,
    val code: String,
) {
    init {
        require(field.isNotBlank())
        require(code.isNotBlank())
    }
}

/**
 * Structured execution truth for internal D9 Tools.
 *
 * This is not a provider wire schema: only typed Tool implementations create these outcomes.
 */
sealed interface AgentToolOutcome<out T> {
    data class Success<T>(val payload: T) : AgentToolOutcome<T>

    data class InvalidInput(val issues: List<AgentToolInputIssue>) : AgentToolOutcome<Nothing> {
        init { require(issues.isNotEmpty()) }
    }

    data object NotFound : AgentToolOutcome<Nothing>

    data class PermissionDenied(val capability: AgentToolCapability) : AgentToolOutcome<Nothing>

    /** [preview] is Tool-specific typed data, never a blind write authorization. */
    data class ConfirmationRequired<T>(val preview: T) : AgentToolOutcome<T>

    data object Stale : AgentToolOutcome<Nothing>

    data class Conflict(val conflictIds: List<String>) : AgentToolOutcome<Nothing> {
        init { require(conflictIds.isNotEmpty() && conflictIds.all(String::isNotBlank)) }
    }

    data class Infeasible(val reasonCodes: List<String>) : AgentToolOutcome<Nothing> {
        init { require(reasonCodes.isNotEmpty() && reasonCodes.all(String::isNotBlank)) }
    }

    /** Diagnostics retain only a stable redacted code, never an exception or stack trace. */
    data class InfrastructureFailure(val redactedCode: String) : AgentToolOutcome<Nothing> {
        init { require(redactedCode.isNotBlank()) }
    }
}
