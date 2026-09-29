package dev.agenticscheduler.agent.tool

import dev.agenticscheduler.agent.permission.AgentToolCapability
import dev.agenticscheduler.application.planner.DogfoodPlannerService
import dev.agenticscheduler.application.planner.PlanBranch
import dev.agenticscheduler.application.planner.PlannerPreview
import dev.agenticscheduler.domain.id.FocusBlockId
import dev.agenticscheduler.domain.id.PlanningProfileId
import dev.agenticscheduler.planner.LocalReflowRequest
import dev.agenticscheduler.planner.PlanningHorizon
import dev.agenticscheduler.planner.PlannerIssue
import dev.agenticscheduler.domain.time.ZonedTimeRange
import kotlin.time.Instant
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.CancellationException
import kotlinx.datetime.TimeZone
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

/** Names are internal D9 Tool contracts; AGT-004 does not promise external schema compatibility. */
object PlannerToolNames {
    const val PREVIEW_FULL_REPLAN = "planner.previewFullReplan"
    const val PREVIEW_LOCAL_REFLOW = "planner.previewLocalReflow"
}

@Serializable
data class PlannerPreviewWindowInput(
    val planningProfileId: String,
    val referenceNow: String,
    val horizonStart: String,
    val horizonEndExclusive: String,
)

@Serializable
data class PlannerZonedRangeInput(
    val start: String,
    val endExclusive: String,
    val timeZone: String,
)

@Serializable
data class PlannerLocalReflowInput(
    val planningProfileId: String,
    val referenceNow: String,
    val horizonStart: String,
    val horizonEndExclusive: String,
    val affectedFocusBlockIds: List<String>,
    val disruptedRanges: List<PlannerZonedRangeInput>,
    val searchWindow: PlannerZonedRangeInput,
)

/** Narrow port keeps Tool tests independent of persistence while production delegates to D6.5. */
interface PlannerPreviewApplication {
    suspend fun fullReplan(
        profileId: PlanningProfileId,
        referenceNow: Instant,
        horizon: PlanningHorizon,
    ): PlannerPreview

    suspend fun localReflow(
        profileId: PlanningProfileId,
        referenceNow: Instant,
        horizon: PlanningHorizon,
        request: LocalReflowRequest,
    ): PlannerPreview
}

class DogfoodPlannerPreviewApplication(
    private val service: DogfoodPlannerService,
) : PlannerPreviewApplication {
    override suspend fun fullReplan(profileId: PlanningProfileId, referenceNow: Instant, horizon: PlanningHorizon) =
        service.fullReplan(profileId, referenceNow, horizon)

    override suspend fun localReflow(
        profileId: PlanningProfileId,
        referenceNow: Instant,
        horizon: PlanningHorizon,
        request: LocalReflowRequest,
    ) = service.localReflow(profileId, referenceNow, horizon, request)
}

/** Explicit inputs only: the Tool never assembles Planner facts or mutates Active State itself. */
class PlannerPreviewFullReplanTool(
    private val planner: PlannerPreviewApplication,
) {
    val metadata = AgentToolMetadata(
        PlannerToolNames.PREVIEW_FULL_REPLAN,
        AgentToolCapability.PLAN_PREVIEW,
        AgentToolAccess.READ,
    )
    private val json = Json { ignoreUnknownKeys = false }

    suspend fun execute(input: PlannerPreviewWindowInput): AgentToolOutcome<PlanBranch> {
        val decoded = decodeWindow(input) ?: return invalid("arguments", "INVALID_PLANNER_WINDOW")
        return invokePlanner {
            planner.fullReplan(decoded.profileId, decoded.referenceNow, decoded.horizon)
        }
    }

    suspend fun execute(argumentsJson: String): AgentToolOutcome<PlanBranch> = try {
        execute(json.decodeFromString(PlannerPreviewWindowInput.serializer(), argumentsJson))
    } catch (_: SerializationException) {
        invalid("arguments", "INVALID_JSON")
    } catch (_: IllegalArgumentException) {
        invalid("arguments", "INVALID_JSON")
    }

    private data class Window(
        val profileId: PlanningProfileId,
        val referenceNow: Instant,
        val horizon: PlanningHorizon,
    )

    private fun decodeWindow(input: PlannerPreviewWindowInput): Window? = try {
        val referenceNow = Instant.parse(input.referenceNow)
        val horizon = PlanningHorizon(Instant.parse(input.horizonStart), Instant.parse(input.horizonEndExclusive))
        Window(PlanningProfileId(input.planningProfileId), referenceNow, horizon)
    } catch (_: IllegalArgumentException) {
        null
    }
}

class PlannerPreviewLocalReflowTool(
    private val planner: PlannerPreviewApplication,
) {
    val metadata = AgentToolMetadata(
        PlannerToolNames.PREVIEW_LOCAL_REFLOW,
        AgentToolCapability.PLAN_PREVIEW,
        AgentToolAccess.READ,
    )
    private val json = Json { ignoreUnknownKeys = false }

    suspend fun execute(input: PlannerLocalReflowInput): AgentToolOutcome<PlanBranch> {
        val decoded = decode(input) ?: return invalid("arguments", "INVALID_LOCAL_REFLOW")
        return invokePlanner {
            planner.localReflow(decoded.profileId, decoded.referenceNow, decoded.horizon, decoded.request)
        }
    }

    suspend fun execute(argumentsJson: String): AgentToolOutcome<PlanBranch> = try {
        execute(json.decodeFromString(PlannerLocalReflowInput.serializer(), argumentsJson))
    } catch (_: SerializationException) {
        invalid("arguments", "INVALID_JSON")
    } catch (_: IllegalArgumentException) {
        invalid("arguments", "INVALID_JSON")
    }

    private data class Decoded(
        val profileId: PlanningProfileId,
        val referenceNow: Instant,
        val horizon: PlanningHorizon,
        val request: LocalReflowRequest,
    )

    private fun decode(input: PlannerLocalReflowInput): Decoded? = try {
        val affected = input.affectedFocusBlockIds.map(::FocusBlockId).toImmutableList()
        val request = LocalReflowRequest(
            affectedFocusBlockIds = affected,
            disruptedRanges = input.disruptedRanges.map(PlannerZonedRangeInput::toDomain).toImmutableList(),
            searchWindow = input.searchWindow.toDomain(),
        )
        Decoded(
            profileId = PlanningProfileId(input.planningProfileId),
            referenceNow = Instant.parse(input.referenceNow),
            horizon = PlanningHorizon(Instant.parse(input.horizonStart), Instant.parse(input.horizonEndExclusive)),
            request = request,
        )
    } catch (_: IllegalArgumentException) {
        null
    }
}

private fun PlannerZonedRangeInput.toDomain() = ZonedTimeRange(
    start = Instant.parse(start),
    endExclusive = Instant.parse(endExclusive),
    timeZone = TimeZone.of(timeZone),
)

private suspend inline fun invokePlanner(crossinline preview: suspend () -> PlannerPreview): AgentToolOutcome<PlanBranch> = try {
    when (val result = preview()) {
        is PlannerPreview.Applicable -> AgentToolOutcome.Success(result.branch)
        is PlannerPreview.Infeasible -> AgentToolOutcome.Infeasible(result.issues.map(::plannerIssueCode))
        is PlannerPreview.InvalidInput -> AgentToolOutcome.InvalidInput(
            result.issues.map { issue -> AgentToolInputIssue("planner", plannerIssueCode(issue)) },
        )
    }
} catch (cancelled: CancellationException) {
    throw cancelled
} catch (_: Exception) {
    AgentToolOutcome.InfrastructureFailure("PLANNER_PREVIEW")
}

private fun plannerIssueCode(issue: PlannerIssue): String = when (issue) {
    PlannerIssue.ProfileUnconfigured -> "PROFILE_UNCONFIGURED"
    is PlannerIssue.InvalidSnapshot -> "INVALID_SNAPSHOT"
    is PlannerIssue.TimeResolutionFailure -> "TIME_RESOLUTION_FAILURE"
    is PlannerIssue.UnknownRemainingEffort -> "UNKNOWN_REMAINING_EFFORT"
    is PlannerIssue.NoLegalAvailability -> "NO_LEGAL_AVAILABILITY"
    is PlannerIssue.DependencyBlocked -> "DEPENDENCY_BLOCKED"
    is PlannerIssue.OverflowApprovalRequired -> "OVERFLOW_APPROVAL_REQUIRED"
    is PlannerIssue.HardDeadlineShortfall -> "HARD_DEADLINE_SHORTFALL"
    is PlannerIssue.UnscheduledEffort -> "UNSCHEDULED_EFFORT"
    is PlannerIssue.OverallocatedPlannedEffort -> "OVERALLOCATED_PLANNED_EFFORT"
    is PlannerIssue.ImmovableConflict -> "IMMOVABLE_CONFLICT"
}

private fun invalid(field: String, code: String) = AgentToolOutcome.InvalidInput(listOf(AgentToolInputIssue(field, code)))
