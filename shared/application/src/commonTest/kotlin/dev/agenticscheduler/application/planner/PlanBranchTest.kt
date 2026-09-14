package dev.agenticscheduler.application.planner

import dev.agenticscheduler.application.id.EpochMillisecondsClock
import dev.agenticscheduler.application.id.RandomBytes
import dev.agenticscheduler.application.id.RfcUuidV7Generator
import dev.agenticscheduler.application.history.MutationCoordinator
import dev.agenticscheduler.application.history.MutationWallClock
import dev.agenticscheduler.application.persistence.CommittedMutation
import dev.agenticscheduler.application.persistence.LocalReplicaCausalState
import dev.agenticscheduler.application.persistence.MutationJournalRepository
import dev.agenticscheduler.application.persistence.ApplicationTransactionRunner
import dev.agenticscheduler.application.persistence.TaskRepository
import dev.agenticscheduler.domain.id.FocusBlockId
import dev.agenticscheduler.domain.id.PlanBranchId
import dev.agenticscheduler.domain.id.PlanningProfileId
import dev.agenticscheduler.domain.id.TaskDependencyId
import dev.agenticscheduler.domain.id.TaskId
import dev.agenticscheduler.domain.id.WorkLogId
import dev.agenticscheduler.domain.planning.PlanningProfile
import dev.agenticscheduler.domain.planning.PlanningProfileConfiguration
import dev.agenticscheduler.domain.task.FocusBlock
import dev.agenticscheduler.domain.task.Task
import dev.agenticscheduler.domain.task.TaskDependency
import dev.agenticscheduler.domain.task.WorkLog
import dev.agenticscheduler.domain.time.ZonedTimeRange
import dev.agenticscheduler.planner.FocusBlockDraft
import dev.agenticscheduler.planner.FocusBlockMutation
import dev.agenticscheduler.planner.PlanningHorizon
import dev.agenticscheduler.planner.PlanningSnapshot
import dev.agenticscheduler.planner.PlannerExplanation
import dev.agenticscheduler.planner.PlannerIssue
import dev.agenticscheduler.planner.PlannerResult
import dev.agenticscheduler.planner.PlacementCriterion
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.TimeZone
import kotlin.time.Instant

class PlanBranchTest {
    @Test fun `branch retains decision evidence separately from planner issues`() {
        val snapshot = snapshot()
        val mutation = FocusBlockMutation.Create(FocusBlockDraft(TaskId(id(2)), ZonedTimeRange(Instant.parse("2026-01-02T10:00:00Z"), Instant.parse("2026-01-02T11:00:00Z"), TimeZone.UTC)))
        val explanation = PlannerExplanation(mutation.taskId, mutation, persistentListOf(PlacementCriterion.EARLIER_START))
        val issue = PlannerIssue.UnscheduledEffort(mutation.taskId, kotlin.time.Duration.ZERO)
        val branch = PlanBranchFactory(generator()).fromApplicableResult(
            PlanningRequest.FullReplan, snapshot,
            PlannerResult.Success(persistentListOf(mutation), persistentListOf(issue), persistentListOf(explanation)),
        )
        assertEquals(persistentListOf(issue), branch.issues)
        assertEquals(persistentListOf(explanation), branch.explanations)
    }

    @Test fun `branch preview is isolated and apply materializes only future draft`() = runBlocking {
        val snapshot = snapshot()
        val repository = InMemoryTasks()
        val branch = PlanBranch(PlanBranchId(id(1)), PlanningRequest.FullReplan, snapshot,
            persistentListOf(FocusBlockMutation.Create(FocusBlockDraft(TaskId(id(2)), ZonedTimeRange(Instant.parse("2026-01-02T10:00:00Z"), Instant.parse("2026-01-02T11:00:00Z"), TimeZone.UTC)))), persistentListOf(), persistentListOf())
        val applier = PlanBranchApplier(IdentityTransactionRunner, repository, generator()) { snapshot.copy(referenceNow = Instant.parse("2026-01-02T09:30:00Z")) }
        assertEquals(0, repository.blocks.size)
        assertEquals(PlanBranchStatus.APPLIED, assertIs<PlanBranchApplyResult.Applied>(applier.apply(branch, Instant.parse("2026-01-02T09:30:00Z"))).branch.status)
        assertEquals(1, repository.blocks.size)
    }

    @Test fun `structurally changed current facts refuse stale apply`() = runBlocking {
        val snapshot = snapshot(); val repository = InMemoryTasks()
        val branch = PlanBranch(PlanBranchId(id(3)), PlanningRequest.FullReplan, snapshot, persistentListOf(), persistentListOf(), persistentListOf())
        val applier = PlanBranchApplier(IdentityTransactionRunner, repository, generator()) { snapshot.copy(profile = PlanningProfile(PlanningProfileId(id(9)), "changed", PlanningProfileConfiguration.Unconfigured)) }
        assertEquals(PlanBranchStatus.STALE, assertIs<PlanBranchApplyResult.Stale>(applier.apply(branch, Instant.parse("2026-01-02T09:00:00Z"))).branch.status)
    }

    @Test fun `unchanged no mutation branch applies without allocating a mutation`() = runBlocking {
        val snapshot = snapshot(); val repository = InMemoryTasks()
        val branch = PlanBranch(PlanBranchId(id(30)), PlanningRequest.FullReplan, snapshot, persistentListOf(), persistentListOf(), persistentListOf())
        val coordinator = MutationCoordinator(IdentityTransactionRunner, EmptyJournal, generator(), MutationWallClock { 1 })
        val applier = PlanBranchApplier(IdentityTransactionRunner, repository, generator(), coordinator) { snapshot }
        assertEquals(PlanBranchStatus.APPLIED, assertIs<PlanBranchApplyResult.Applied>(applier.apply(branch, Instant.parse("2026-01-02T09:00:00Z"))).branch.status)
        assertEquals(0, EmptyJournal.appended)
    }

    @Test fun `unresolvable current source facts refuse stale apply`() = runBlocking {
        val snapshot = snapshot(); val repository = InMemoryTasks()
        val branch = PlanBranch(PlanBranchId(id(6)), PlanningRequest.FullReplan, snapshot, persistentListOf(), persistentListOf(), persistentListOf())
        val applier = PlanBranchApplier(IdentityTransactionRunner, repository, generator()) { null }
        assertEquals(PlanBranchStatus.STALE, assertIs<PlanBranchApplyResult.Stale>(applier.apply(branch, Instant.parse("2026-01-02T09:00:00Z"))).branch.status)
    }

    @Test fun `apply rejects a proposal that reaches apply now`() = runBlocking {
        val snapshot = snapshot(); val repository = InMemoryTasks()
        val branch = PlanBranch(PlanBranchId(id(4)), PlanningRequest.FullReplan, snapshot,
            persistentListOf(FocusBlockMutation.Create(FocusBlockDraft(TaskId(id(2)), ZonedTimeRange(Instant.parse("2026-01-02T09:00:00Z"), Instant.parse("2026-01-02T10:00:00Z"), TimeZone.UTC)))), persistentListOf(), persistentListOf())
        val applier = PlanBranchApplier(IdentityTransactionRunner, repository, generator()) { snapshot }
        assertEquals(PlanBranchStatus.STALE, assertIs<PlanBranchApplyResult.Stale>(applier.apply(branch, Instant.parse("2026-01-02T09:00:00Z"))).branch.status)
        assertEquals(0, repository.blocks.size)
    }

    @Test fun `apply refuses to move or delete a target that has already started`() = runBlocking {
        val started = FocusBlock(
            FocusBlockId(id(8)), TaskId(id(2)),
            ZonedTimeRange(Instant.parse("2026-01-02T10:00:00Z"), Instant.parse("2026-01-02T11:00:00Z"), TimeZone.UTC),
            dev.agenticscheduler.domain.planning.Flexibility.SOFT,
            dev.agenticscheduler.domain.planning.PinState.UNPINNED,
        )
        val snapshot = snapshot(started)
        val repository = InMemoryTasks().also { it.blocks[started.id] = started }
        val branch = PlanBranch(
            PlanBranchId(id(7)), PlanningRequest.FullReplan, snapshot,
            persistentListOf(FocusBlockMutation.Delete(started.id, started.taskId)), persistentListOf(), persistentListOf(),
        )
        val applier = PlanBranchApplier(IdentityTransactionRunner, repository, generator()) { snapshot }
        assertEquals(
            PlanBranchStatus.STALE,
            assertIs<PlanBranchApplyResult.Stale>(applier.apply(branch, Instant.parse("2026-01-02T10:05:00Z"))).branch.status,
        )
        assertEquals(started, repository.blocks[started.id])
    }

    @Test fun `transaction rollback leaves no partial branch mutations`() = runBlocking {
        val snapshot = snapshot(); val repository = InMemoryTasks(failOnInsert = 2)
        val branch = PlanBranch(PlanBranchId(id(5)), PlanningRequest.FullReplan, snapshot, persistentListOf(
            FocusBlockMutation.Create(FocusBlockDraft(TaskId(id(2)), ZonedTimeRange(Instant.parse("2026-01-02T10:00:00Z"), Instant.parse("2026-01-02T11:00:00Z"), TimeZone.UTC))),
            FocusBlockMutation.Create(FocusBlockDraft(TaskId(id(2)), ZonedTimeRange(Instant.parse("2026-01-02T11:00:00Z"), Instant.parse("2026-01-02T12:00:00Z"), TimeZone.UTC))),
        ), persistentListOf(), persistentListOf())
        var failed = false
        try { PlanBranchApplier(RollingBackTransactions(repository), repository, generator()) { snapshot }.apply(branch, Instant.parse("2026-01-02T09:00:00Z")) } catch (_: IllegalStateException) { failed = true }
        assertEquals(true, failed)
        assertEquals(0, repository.blocks.size)
    }

    private fun snapshot(vararg focusBlocks: FocusBlock) = PlanningSnapshot(Instant.parse("2026-01-02T09:00:00Z"), PlanningHorizon(Instant.parse("2026-01-02T09:00:00Z"), Instant.parse("2026-01-02T12:00:00Z")), PlanningProfile(PlanningProfileId(id(9)), "profile", PlanningProfileConfiguration.Unconfigured), persistentListOf(), persistentListOf(), focusBlocks.toList().toImmutableList(), persistentListOf(), persistentListOf(), persistentListOf(), persistentListOf(), persistentListOf())
    private fun generator() = RfcUuidV7Generator(EpochMillisecondsClock { 1 }, RandomBytes { ByteArray(it) { 1 } })
    private fun id(number: Int) = "018f6e68-7d0c-7000-8000-${number.toString().padStart(12, '0')}"
}

private object IdentityTransactionRunner : ApplicationTransactionRunner { override suspend fun <T> inWriteTransaction(block: suspend () -> T): T = block() }
private object EmptyJournal : MutationJournalRepository {
    var appended = 0
    override suspend fun localReplicaState(): LocalReplicaCausalState? = null
    override suspend fun saveLocalReplicaState(state: LocalReplicaCausalState) = Unit
    override suspend fun appendCommittedMutation(mutation: CommittedMutation) { appended++ }
    override suspend fun advanceFocusBlockTombstones(operation: dev.agenticscheduler.sync.SyncOperation, acceptedDeletes: List<dev.agenticscheduler.sync.FocusBlockDelete>) = Unit
}
private class RollingBackTransactions(private val tasks: InMemoryTasks) : ApplicationTransactionRunner {
    override suspend fun <T> inWriteTransaction(block: suspend () -> T): T { val before = tasks.blocks.toMap(); return try { block() } catch (failure: Throwable) { tasks.blocks.clear(); tasks.blocks.putAll(before); throw failure } }
}
private class InMemoryTasks(private val failOnInsert: Int? = null) : TaskRepository {
    val blocks = mutableMapOf<FocusBlockId, FocusBlock>()
    private var inserts = 0
    override fun observeTasks(): Flow<kotlinx.collections.immutable.ImmutableList<Task>> = emptyFlow(); override suspend fun getTask(id: TaskId): Task? = null; override suspend fun upsertTask(task: Task) = Unit
    override fun observeFocusBlocks(): Flow<kotlinx.collections.immutable.ImmutableList<FocusBlock>> = emptyFlow(); override suspend fun getFocusBlock(id: FocusBlockId) = blocks[id]; override suspend fun upsertFocusBlock(focusBlock: FocusBlock) { inserts++; if (inserts == failOnInsert) error("forced failure"); blocks[focusBlock.id] = focusBlock }; override suspend fun deleteFocusBlock(id: FocusBlockId) { blocks.remove(id) }
    override fun observeWorkLogs(): Flow<kotlinx.collections.immutable.ImmutableList<WorkLog>> = emptyFlow(); override suspend fun getWorkLog(id: WorkLogId): WorkLog? = null; override suspend fun upsertWorkLog(workLog: WorkLog) = Unit
    override fun observeDependencies(): Flow<kotlinx.collections.immutable.ImmutableList<TaskDependency>> = emptyFlow(); override suspend fun getDependency(id: TaskDependencyId): TaskDependency? = null; override suspend fun upsertDependency(dependency: TaskDependency) = Unit
}
