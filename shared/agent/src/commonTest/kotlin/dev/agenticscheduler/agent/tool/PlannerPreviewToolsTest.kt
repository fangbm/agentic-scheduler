package dev.agenticscheduler.agent.tool

import dev.agenticscheduler.agent.permission.AgentToolCapability
import dev.agenticscheduler.application.planner.PlanBranch
import dev.agenticscheduler.application.planner.PlanBranchStatus
import dev.agenticscheduler.application.planner.PlannerPreview
import dev.agenticscheduler.application.planner.PlanningRequest
import dev.agenticscheduler.domain.id.PlanBranchId
import dev.agenticscheduler.domain.id.PlanningProfileId
import dev.agenticscheduler.domain.planning.AllDayEventPolicy
import dev.agenticscheduler.domain.planning.PlanningProfile
import dev.agenticscheduler.domain.planning.PlanningProfileConfiguration
import dev.agenticscheduler.domain.time.ZonedTimeRange
import dev.agenticscheduler.planner.LocalReflowRequest
import dev.agenticscheduler.planner.PlannerIssue
import dev.agenticscheduler.planner.PlanningHorizon
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlinx.collections.immutable.persistentListOf
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.TimeZone
import kotlin.time.Instant

class PlannerPreviewToolsTest {
    private val profileId = PlanningProfileId(id(1))
    private val referenceNow = Instant.parse("2026-09-27T08:00:00Z")
    private val horizon = PlanningHorizon(referenceNow, Instant.parse("2026-09-28T08:00:00Z"))

    @Test
    fun `full replan maps explicit request to existing application preview without writes`() = runBlocking {
        val expected = applicable(PlanningRequest.FullReplan)
        val app = RecordingPlanner(expected)
        val tool = PlannerPreviewFullReplanTool(app)

        val result = tool.execute(PlannerPreviewWindowInput(
            profileId.value,
            referenceNow.toString(),
            horizon.start.toString(),
            horizon.endExclusive.toString(),
        ))

        assertEquals(AgentToolOutcome.Success(expected.branch), result)
        assertEquals(PlannerCall.FullReplan(profileId, referenceNow, horizon), app.calls.single())
        assertEquals(0, app.writeCalls)
        assertEquals(AgentToolCapability.PLAN_PREVIEW, tool.metadata.capability)
        assertEquals(AgentToolAccess.READ, tool.metadata.access)
        assertEquals(PlannerToolNames.PREVIEW_FULL_REPLAN, tool.metadata.name)
    }

    @Test
    fun `local reflow maps every explicit affected id range and search window`() = runBlocking {
        val affectedId = dev.agenticscheduler.domain.id.FocusBlockId(id(2))
        val search = range("2026-09-27T08:00:00Z", "2026-09-27T12:00:00Z")
        val disrupted = range("2026-09-27T09:00:00Z", "2026-09-27T10:00:00Z")
        val request = LocalReflowRequest(persistentListOf(affectedId), persistentListOf(disrupted), search)
        val expected = applicable(PlanningRequest.LocalReflow(request))
        val app = RecordingPlanner(expected)
        val tool = PlannerPreviewLocalReflowTool(app)

        val result = tool.execute(PlannerLocalReflowInput(
            planningProfileId = profileId.value,
            referenceNow = referenceNow.toString(),
            horizonStart = horizon.start.toString(),
            horizonEndExclusive = horizon.endExclusive.toString(),
            affectedFocusBlockIds = listOf(affectedId.value),
            disruptedRanges = listOf(disrupted.toInput()),
            searchWindow = search.toInput(),
        ))

        assertEquals(AgentToolOutcome.Success(expected.branch), result)
        assertEquals(PlannerCall.LocalReflow(profileId, referenceNow, horizon, request), app.calls.single())
        assertEquals(0, app.writeCalls)
        assertEquals(AgentToolCapability.PLAN_PREVIEW, tool.metadata.capability)
        assertEquals(AgentToolAccess.READ, tool.metadata.access)
        assertEquals(PlannerToolNames.PREVIEW_LOCAL_REFLOW, tool.metadata.name)
    }

    @Test
    fun `invalid request time and invalid local range return structured input failures`() = runBlocking {
        val full = PlannerPreviewFullReplanTool(RecordingPlanner(applicable(PlanningRequest.FullReplan)))
        val invalidWindow = full.execute(PlannerPreviewWindowInput(profileId.value, "not-a-time", horizon.start.toString(), horizon.endExclusive.toString()))
        assertEquals("INVALID_PLANNER_WINDOW", assertIs<AgentToolOutcome.InvalidInput>(invalidWindow).issues.single().code)

        val local = PlannerPreviewLocalReflowTool(RecordingPlanner(applicable(PlanningRequest.FullReplan)))
        val invalidRange = local.execute(PlannerLocalReflowInput(
            profileId.value, referenceNow.toString(), horizon.start.toString(), horizon.endExclusive.toString(),
            affectedFocusBlockIds = listOf(id(2)), disruptedRanges = emptyList(),
            searchWindow = PlannerZonedRangeInput("2026-09-27T11:00:00Z", "2026-09-27T10:00:00Z", "UTC"),
        ))
        assertEquals("INVALID_LOCAL_REFLOW", assertIs<AgentToolOutcome.InvalidInput>(invalidRange).issues.single().code)
    }

    @Test
    fun `invalid and infeasible application previews retain structured categories`() = runBlocking {
        val invalidApp = RecordingPlanner(PlannerPreview.InvalidInput(persistentListOf(PlannerIssue.ProfileUnconfigured)))
        val invalid = PlannerPreviewFullReplanTool(invalidApp).execute(validFullInput())
        assertEquals("PROFILE_UNCONFIGURED", assertIs<AgentToolOutcome.InvalidInput>(invalid).issues.single().code)

        val infeasibleApp = RecordingPlanner(PlannerPreview.Infeasible(persistentListOf(PlannerIssue.ImmovableConflict(dev.agenticscheduler.domain.id.FocusBlockId(id(3))))))
        val infeasible = PlannerPreviewLocalReflowTool(infeasibleApp).execute(validLocalInput())
        assertEquals(listOf("IMMOVABLE_CONFLICT"), assertIs<AgentToolOutcome.Infeasible>(infeasible).reasonCodes)
    }

    @Test
    fun `malformed json and application failure remain structured and redacted`() = runBlocking {
        val malformed = PlannerPreviewFullReplanTool(RecordingPlanner(applicable(PlanningRequest.FullReplan))).execute("{bad json")
        assertEquals("INVALID_JSON", assertIs<AgentToolOutcome.InvalidInput>(malformed).issues.single().code)

        val failedApp = RecordingPlanner(applicable(PlanningRequest.FullReplan), failure = IllegalStateException("contains secret data"))
        val failed = PlannerPreviewFullReplanTool(failedApp).execute(validFullInput())
        assertEquals("PLANNER_PREVIEW", assertIs<AgentToolOutcome.InfrastructureFailure>(failed).redactedCode)
    }

    @Test
    fun `cancellation propagates instead of becoming infrastructure failure`() = runBlocking {
        val app = RecordingPlanner(applicable(PlanningRequest.FullReplan), failure = CancellationException("cancel"))
        try {
            PlannerPreviewFullReplanTool(app).execute(validFullInput())
            error("Expected cancellation")
        } catch (_: CancellationException) {
            assertEquals(1, app.calls.size)
        }
    }

    private fun validFullInput() = PlannerPreviewWindowInput(profileId.value, referenceNow.toString(), horizon.start.toString(), horizon.endExclusive.toString())
    private fun validLocalInput() = PlannerLocalReflowInput(
        profileId.value, referenceNow.toString(), horizon.start.toString(), horizon.endExclusive.toString(),
        emptyList(), emptyList(), range("2026-09-27T09:00:00Z", "2026-09-27T10:00:00Z").toInput(),
    )

    private fun applicable(request: PlanningRequest) = PlannerPreview.Applicable(PlanBranch(
        id = PlanBranchId(id(4)),
        originalRequest = request,
        baseFacts = dev.agenticscheduler.planner.PlanningSnapshot(
            referenceNow = referenceNow,
            horizon = horizon,
            profile = PlanningProfile(profileId, "Study", PlanningProfileConfiguration.Unconfigured),
            tasks = persistentListOf(), dependencies = persistentListOf(), focusBlocks = persistentListOf(),
            events = persistentListOf(), courseSessions = persistentListOf(), exams = persistentListOf(),
            constraints = persistentListOf(), askOverflowAuthorizedTaskIds = persistentListOf(),
        ),
        mutations = persistentListOf(), issues = persistentListOf(), explanations = persistentListOf(),
        status = PlanBranchStatus.DRAFT,
    ))

    private fun range(start: String, end: String) = ZonedTimeRange(Instant.parse(start), Instant.parse(end), TimeZone.UTC)
    private fun ZonedTimeRange.toInput() = PlannerZonedRangeInput(start.toString(), endExclusive.toString(), timeZone.id)
    private fun id(n: Int) = "018f6e68-7d0c-7000-8000-${n.toString().padStart(12, '0')}"
}

private sealed interface PlannerCall {
    data class FullReplan(val profileId: PlanningProfileId, val now: Instant, val horizon: PlanningHorizon) : PlannerCall
    data class LocalReflow(val profileId: PlanningProfileId, val now: Instant, val horizon: PlanningHorizon, val request: LocalReflowRequest) : PlannerCall
}

private class RecordingPlanner(
    private val result: PlannerPreview,
    private val failure: Exception? = null,
) : PlannerPreviewApplication {
    val calls = mutableListOf<PlannerCall>()
    var writeCalls = 0

    override suspend fun fullReplan(profileId: PlanningProfileId, referenceNow: Instant, horizon: PlanningHorizon): PlannerPreview {
        calls += PlannerCall.FullReplan(profileId, referenceNow, horizon)
        failure?.let { throw it }
        return result
    }

    override suspend fun localReflow(
        profileId: PlanningProfileId,
        referenceNow: Instant,
        horizon: PlanningHorizon,
        request: LocalReflowRequest,
    ): PlannerPreview {
        calls += PlannerCall.LocalReflow(profileId, referenceNow, horizon, request)
        failure?.let { throw it }
        return result
    }
}
