package dev.agenticscheduler.agent.tool

import dev.agenticscheduler.agent.history.AgentActionId
import dev.agenticscheduler.agent.permission.AgentPermissionDecision
import dev.agenticscheduler.agent.permission.AgentPermissionEngine
import dev.agenticscheduler.agent.permission.AgentPermissionPolicy
import dev.agenticscheduler.agent.permission.AgentToolCapability
import dev.agenticscheduler.application.history.AgentOriginWriteNotAllowed
import dev.agenticscheduler.application.history.MutationExecution
import dev.agenticscheduler.application.planner.DogfoodPlannerService
import dev.agenticscheduler.application.planner.PlanBranch
import dev.agenticscheduler.application.planner.PlanBranchApplyResult
import dev.agenticscheduler.application.planner.PlanBranchStatus
import dev.agenticscheduler.sync.MutationId
import dev.agenticscheduler.sync.MutationOrigin
import kotlin.time.Instant
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

const val PLANNER_APPLY_BRANCH_TOOL_NAME = "planner.applyBranch"

@Serializable
data class PlannerApplyBranchToolInput(val planBranchId: String, val applyNow: String)

data class PlannerApplyBranchPreview(val branch: PlanBranch)
data class CommittedPlannerApply(val branch: PlanBranch, val mutationId: MutationId?)

interface PlannerBranchApplyApplication {
    suspend fun apply(
        branch: PlanBranch,
        applyNow: Instant,
        origin: MutationOrigin,
        onCommitted: suspend (MutationExecution<PlanBranchApplyResult>) -> Unit,
    ): PlanBranchApplyResult
}

class DogfoodPlannerBranchApplyApplication(private val service: DogfoodPlannerService) : PlannerBranchApplyApplication {
    override suspend fun apply(
        branch: PlanBranch,
        applyNow: Instant,
        origin: MutationOrigin,
        onCommitted: suspend (MutationExecution<PlanBranchApplyResult>) -> Unit,
    ) = service.apply(branch, applyNow, origin, onCommitted)
}

/** Applies a supplied session-local branch only after confirmation and D6's transaction-time stale check. */
class PlannerApplyBranchTool(
    private val planner: PlannerBranchApplyApplication,
    private val permissions: AgentPermissionEngine = AgentPermissionEngine(),
) {
    constructor(planner: DogfoodPlannerService, permissions: AgentPermissionEngine = AgentPermissionEngine()) :
        this(DogfoodPlannerBranchApplyApplication(planner), permissions)
    val metadata = AgentToolMetadata(PLANNER_APPLY_BRANCH_TOOL_NAME, AgentToolCapability.SCHEDULE_APPLY, AgentToolAccess.WRITE)
    private val json = Json { ignoreUnknownKeys = false }

    fun prepare(input: PlannerApplyBranchToolInput, branch: PlanBranch, policy: AgentPermissionPolicy): AgentToolOutcome<PlannerApplyBranchPreview> {
        if (input.planBranchId != branch.id.value || branch.status != PlanBranchStatus.DRAFT) return AgentToolOutcome.Stale
        if (runCatching { Instant.parse(input.applyNow) }.isFailure) return invalid("applyNow", "INVALID_INSTANT")
        val preview = PlannerApplyBranchPreview(branch)
        return when (permissions.evaluate(policy, metadata.capability)) {
            AgentPermissionDecision.AllowDirect -> AgentToolOutcome.Success(preview)
            AgentPermissionDecision.RequireConfirmation -> AgentToolOutcome.ConfirmationRequired(preview)
            AgentPermissionDecision.Deny -> AgentToolOutcome.PermissionDenied(metadata.capability)
        }
    }

    fun prepare(argumentsJson: String, branch: PlanBranch, policy: AgentPermissionPolicy): AgentToolOutcome<PlannerApplyBranchPreview> =
        when (val decoded = decode(argumentsJson)) {
            is Decoded.Valid -> prepare(decoded.input, branch, policy)
            is Decoded.Invalid -> decoded.outcome
        }

    suspend fun commit(
        argumentsJson: String,
        branch: PlanBranch,
        shownPreview: PlannerApplyBranchPreview,
        userConfirmed: Boolean,
        policy: AgentPermissionPolicy,
        agentActionId: AgentActionId,
        onCommitted: suspend (MutationExecution<PlanBranchApplyResult>) -> Unit = {},
    ): AgentToolOutcome<CommittedPlannerApply> {
        val prepared = prepare(argumentsJson, branch, policy)
        val current = when (prepared) {
            is AgentToolOutcome.Success -> prepared.payload
            is AgentToolOutcome.ConfirmationRequired -> prepared.preview
            is AgentToolOutcome.InvalidInput -> return prepared
            is AgentToolOutcome.PermissionDenied -> return prepared
            AgentToolOutcome.Stale -> return AgentToolOutcome.Stale
            else -> return AgentToolOutcome.InfrastructureFailure("UNEXPECTED_PLAN_BRANCH_PREVIEW")
        }
        if (current != shownPreview) return AgentToolOutcome.Stale
        if (prepared is AgentToolOutcome.ConfirmationRequired && !userConfirmed) return AgentToolOutcome.PermissionDenied(metadata.capability)
        val request = (decode(argumentsJson) as Decoded.Valid).input
        var mutationId: MutationId? = null
        return try {
            when (val result = planner.apply(
                branch,
                Instant.parse(request.applyNow),
                MutationOrigin.Agent(agentActionId.value),
                onCommitted = { committed ->
                    mutationId = committed.mutationId
                    onCommitted(committed)
                },
            )) {
                is PlanBranchApplyResult.Applied -> AgentToolOutcome.Success(CommittedPlannerApply(result.branch, mutationId))
                is PlanBranchApplyResult.Stale -> AgentToolOutcome.Stale
                is PlanBranchApplyResult.BlockedBySyncConflict -> AgentToolOutcome.Conflict(result.blocks.map { it.conflictId })
            }
        } catch (_: AgentOriginWriteNotAllowed) {
            AgentToolOutcome.PermissionDenied(metadata.capability)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            AgentToolOutcome.InfrastructureFailure("PLANNER_APPLY_BRANCH_TRANSACTION")
        }
    }

    private fun decode(argumentsJson: String): Decoded {
        val input = try { json.decodeFromString(PlannerApplyBranchToolInput.serializer(), argumentsJson) }
        catch (_: SerializationException) { return Decoded.Invalid(invalid("arguments", "INVALID_JSON")) }
        catch (_: IllegalArgumentException) { return Decoded.Invalid(invalid("arguments", "INVALID_JSON")) }
        return try {
            dev.agenticscheduler.domain.id.PlanBranchId(input.planBranchId)
            Instant.parse(input.applyNow)
            Decoded.Valid(input)
        } catch (_: IllegalArgumentException) {
            Decoded.Invalid(invalid("arguments", "INVALID_VALUE"))
        }
    }

    private sealed interface Decoded {
        data class Valid(val input: PlannerApplyBranchToolInput) : Decoded
        data class Invalid(val outcome: AgentToolOutcome.InvalidInput) : Decoded
    }
}

private fun invalid(field: String, code: String) = AgentToolOutcome.InvalidInput(listOf(AgentToolInputIssue(field, code)))
