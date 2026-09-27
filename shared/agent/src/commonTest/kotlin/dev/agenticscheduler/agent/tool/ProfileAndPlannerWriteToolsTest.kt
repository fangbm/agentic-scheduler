package dev.agenticscheduler.agent.tool

import dev.agenticscheduler.agent.permission.AgentPermissionPolicy
import dev.agenticscheduler.application.history.MutationCoordinator
import dev.agenticscheduler.application.history.MutationExecution
import dev.agenticscheduler.application.history.MutationWallClock
import dev.agenticscheduler.application.history.NoActiveSyncSpaceWritePolicy
import dev.agenticscheduler.application.id.UuidV7Generator
import dev.agenticscheduler.application.persistence.ApplicationTransactionRunner
import dev.agenticscheduler.application.persistence.CommittedMutation
import dev.agenticscheduler.application.persistence.LocalReplicaCausalState
import dev.agenticscheduler.application.persistence.MutationJournalRepository
import dev.agenticscheduler.application.planner.PlanBranch
import dev.agenticscheduler.application.planner.PlanBranchApplyResult
import dev.agenticscheduler.application.planner.PlanBranchStatus
import dev.agenticscheduler.application.planner.PlanningProfileSettingsService
import dev.agenticscheduler.application.history.AgentOriginWriteGate
import dev.agenticscheduler.domain.id.PlanBranchId
import dev.agenticscheduler.domain.id.PlanningProfileId
import dev.agenticscheduler.domain.planning.PlanningProfile
import dev.agenticscheduler.domain.planning.PlanningProfileConfiguration
import dev.agenticscheduler.planner.PlanningHorizon
import dev.agenticscheduler.sync.MutationId
import dev.agenticscheduler.sync.MutationOrigin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlinx.collections.immutable.persistentListOf
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.TimeZone
import kotlin.time.Instant

class ProfileAndPlannerWriteToolsTest {
    @Test
    fun `planning profile update defaults to a typed confirmation preview`() = runBlocking {
        val profile = PlanningProfile(profileId, "Study", PlanningProfileConfiguration.Unconfigured)
        val tool = PlanningProfileUpdateTool(settings(MemoryProfiles(profile)))

        val result = tool.prepare(
            """{"planningProfileId":"${profile.id.value}","name":"Study 2","configuration":"UNCONFIGURED","timeZone":null,"weeklyAvailability":null,"minimumFocusBlockMinutes":null,"preferredFocusBlockMinutes":null,"maximumFocusBlockMinutes":null,"allDayEventPolicy":null}""",
            AgentPermissionPolicy.default(),
        )

        val preview = assertIs<AgentToolOutcome.ConfirmationRequired<PlanningProfileUpdateWritePreview>>(result).preview
        assertEquals(profile, preview.before)
        assertEquals("Study 2", preview.after.name)
        assertEquals("planningProfile.update", tool.metadata.name)
    }

    @Test
    fun `planning profile update rejects unknown fields and incomplete configured state`() = runBlocking {
        val tool = PlanningProfileUpdateTool(settings(MemoryProfiles(null)))
        val extra = tool.prepare(
            """{"planningProfileId":"${profileId.value}","name":"Study","configuration":"UNCONFIGURED","unreviewed":true}""",
            AgentPermissionPolicy.default(),
        )
        assertEquals("INVALID_JSON", assertIs<AgentToolOutcome.InvalidInput>(extra).issues.single().code)

        val incomplete = tool.prepare(
            """{"planningProfileId":"${profileId.value}","name":"Study","configuration":"CONFIGURED","timeZone":"UTC","weeklyAvailability":[],"minimumFocusBlockMinutes":25,"preferredFocusBlockMinutes":null,"maximumFocusBlockMinutes":90,"allDayEventPolicy":"NON_BLOCKING"}""",
            AgentPermissionPolicy.default(),
        )
        assertEquals("preferredFocusBlockMinutes", assertIs<AgentToolOutcome.InvalidInput>(incomplete).issues.single().field)
    }

    @Test
    fun `planning profile update returns committed mutation and forwards atomic callback`() = runBlocking {
        val original = PlanningProfile(profileId, "Study", PlanningProfileConfiguration.Unconfigured)
        val repository = MemoryProfiles(original)
        val tool = PlanningProfileUpdateTool(settings(repository))
        val arguments = """{"planningProfileId":"${profileId.value}","name":"Study 2","configuration":"UNCONFIGURED","timeZone":null,"weeklyAvailability":null,"minimumFocusBlockMinutes":null,"preferredFocusBlockMinutes":null,"maximumFocusBlockMinutes":null,"allDayEventPolicy":null}"""
        val preview = assertIs<AgentToolOutcome.ConfirmationRequired<PlanningProfileUpdateWritePreview>>(
            tool.prepare(arguments, AgentPermissionPolicy.default()),
        ).preview
        var callbackMutationId: MutationId? = null

        val result = tool.commit(
            arguments, preview, true, AgentPermissionPolicy.default(),
            dev.agenticscheduler.agent.history.AgentActionId("018f6e68-7d0c-7000-8000-000000000002"),
        ) { callbackMutationId = it.mutationId }

        val committed = assertIs<AgentToolOutcome.Success<CommittedPlanningProfileUpdate>>(result).payload
        assertEquals("Study 2", repository.get(profileId)?.name)
        assertEquals(committed.mutationId, callbackMutationId)
    }

    @Test
    fun `planning profile update cannot create a missing profile`() = runBlocking {
        val tool = PlanningProfileUpdateTool(settings(MemoryProfiles(null)))
        val result = tool.prepare(
            """{"planningProfileId":"${profileId.value}","name":"Study","configuration":"UNCONFIGURED","timeZone":null,"weeklyAvailability":null,"minimumFocusBlockMinutes":null,"preferredFocusBlockMinutes":null,"maximumFocusBlockMinutes":null,"allDayEventPolicy":null}""",
            AgentPermissionPolicy.default(),
        )
        assertEquals(AgentToolOutcome.NotFound, result)
    }

    @Test
    fun `planner branch apply defaults to confirmation and rejects a stale branch`() {
        val branch = branch()
        val tool = PlannerApplyBranchTool(planner = UnusedPlannerApply)
        val input = PlannerApplyBranchToolInput(branch.id.value, "2026-09-27T08:00:00Z")
        assertIs<AgentToolOutcome.ConfirmationRequired<PlannerApplyBranchPreview>>(
            tool.prepare(input, branch, AgentPermissionPolicy.default()),
        )

        val stale = tool.prepare(input.copy(planBranchId = "018f6e68-7d0c-7000-8000-000000000099"), branch, AgentPermissionPolicy.default())
        assertEquals(AgentToolOutcome.Stale, stale)
        assertEquals("planner.applyBranch", tool.metadata.name)
    }

    @Test
    fun `planner branch apply forwards Agent origin and transaction callback`() = runBlocking {
        val branch = branch()
        val planner = RecordingPlannerApply()
        val tool = PlannerApplyBranchTool(planner)
        val arguments = """{"planBranchId":"${branch.id.value}","applyNow":"2026-09-27T08:00:00Z"}"""
        val preview = assertIs<AgentToolOutcome.ConfirmationRequired<PlannerApplyBranchPreview>>(
            tool.prepare(arguments, branch, AgentPermissionPolicy.default()),
        ).preview
        var callbackMutationId: MutationId? = null
        val actionId = dev.agenticscheduler.agent.history.AgentActionId("018f6e68-7d0c-7000-8000-000000000003")

        val result = tool.commit(arguments, branch, preview, true, AgentPermissionPolicy.default(), actionId) {
            callbackMutationId = it.mutationId
        }

        val committed = assertIs<AgentToolOutcome.Success<CommittedPlannerApply>>(result).payload
        assertEquals(MutationOrigin.Agent(actionId.value), planner.origin)
        assertEquals(planner.mutationId, committed.mutationId)
        assertEquals(committed.mutationId, callbackMutationId)
    }

    private fun settings(profiles: MemoryProfiles) = PlanningProfileSettingsService(
        profiles,
        FixedUuidGenerator,
        MutationCoordinator(
            IdentityTransactions, EmptyJournal, FixedUuidGenerator, MutationWallClock { 1 }, AgentOriginWriteGate { true },
        ),
        NoActiveSyncSpaceWritePolicy,
    )

    private fun branch(status: PlanBranchStatus = PlanBranchStatus.DRAFT): PlanBranch {
        val now = Instant.parse("2026-09-27T08:00:00Z")
        val horizon = PlanningHorizon(now, Instant.parse("2026-09-28T08:00:00Z"))
        return PlanBranch(
            PlanBranchId("018f6e68-7d0c-7000-8000-000000000004"),
            dev.agenticscheduler.application.planner.PlanningRequest.FullReplan,
            dev.agenticscheduler.planner.PlanningSnapshot(
                referenceNow = now,
                horizon = horizon,
                profile = PlanningProfile(profileId, "Study", PlanningProfileConfiguration.Unconfigured),
                tasks = persistentListOf(),
                dependencies = persistentListOf(),
                focusBlocks = persistentListOf(),
                events = persistentListOf(),
                courseSessions = persistentListOf(),
                exams = persistentListOf(),
                constraints = persistentListOf(),
                askOverflowAuthorizedTaskIds = persistentListOf(),
            ),
            persistentListOf(), persistentListOf(), persistentListOf(), status,
        )
    }

    private class MemoryProfiles(private var profile: PlanningProfile?) : dev.agenticscheduler.application.persistence.PlanningProfileRepository {
        override fun observeAll(): Flow<kotlinx.collections.immutable.ImmutableList<PlanningProfile>> = flowOf(persistentListOf<PlanningProfile>().let { current ->
            profile?.let { current.add(it) } ?: current
        })
        override suspend fun get(id: PlanningProfileId) = profile?.takeIf { it.id == id }
        override suspend fun upsert(profile: PlanningProfile) { this.profile = profile }
    }

    private object IdentityTransactions : ApplicationTransactionRunner {
        override suspend fun <T> inWriteTransaction(block: suspend () -> T): T = block()
    }

    private object EmptyJournal : MutationJournalRepository {
        override suspend fun localReplicaState(): LocalReplicaCausalState? = null
        override suspend fun saveLocalReplicaState(state: LocalReplicaCausalState) = Unit
        override suspend fun appendCommittedMutation(mutation: CommittedMutation) = Unit
        override suspend fun advanceFocusBlockTombstones(operation: dev.agenticscheduler.sync.SyncOperation, acceptedDeletes: List<dev.agenticscheduler.sync.FocusBlockDelete>) = Unit
    }

    private companion object {
        val profileId = PlanningProfileId("018f6e68-7d0c-7000-8000-000000000001")
    }
}

private object FixedUuidGenerator : UuidV7Generator {
    private var next = 10
    override fun next(): String = "018f6e68-7d0c-7000-8000-${(next++).toString().padStart(12, '0')}"
}

private object UnusedPlannerApply : PlannerBranchApplyApplication {
    override suspend fun apply(
        branch: PlanBranch,
        applyNow: Instant,
        origin: dev.agenticscheduler.sync.MutationOrigin,
        onCommitted: suspend (dev.agenticscheduler.application.history.MutationExecution<PlanBranchApplyResult>) -> Unit,
    ): PlanBranchApplyResult = error("prepare must not apply a branch")
}

private class RecordingPlannerApply : PlannerBranchApplyApplication {
    val mutationId = MutationId("018f6e68-7d0c-7000-8000-000000000010")
    var origin: MutationOrigin? = null
    override suspend fun apply(
        branch: PlanBranch,
        applyNow: Instant,
        origin: MutationOrigin,
        onCommitted: suspend (MutationExecution<PlanBranchApplyResult>) -> Unit,
    ): PlanBranchApplyResult {
        this.origin = origin
        val result = PlanBranchApplyResult.Applied(branch.copy(status = PlanBranchStatus.APPLIED))
        onCommitted(MutationExecution(result, mutationId))
        return result
    }
}
