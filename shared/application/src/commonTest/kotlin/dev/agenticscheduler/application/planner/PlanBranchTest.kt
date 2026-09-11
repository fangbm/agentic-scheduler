package dev.agenticscheduler.application.planner

import dev.agenticscheduler.application.id.EpochMillisecondsClock
import dev.agenticscheduler.application.id.RandomBytes
import dev.agenticscheduler.application.id.RfcUuidV7Generator
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
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.collections.immutable.persistentListOf
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.TimeZone
import kotlin.time.Instant

class PlanBranchTest {
    @Test fun `branch preview is isolated and apply materializes only future draft`() = runBlocking {
        val snapshot = snapshot()
        val repository = InMemoryTasks()
        val branch = PlanBranch(PlanBranchId(id(1)), PlanningRequest.FullReplan, snapshot,
            persistentListOf(FocusBlockMutation.Create(FocusBlockDraft(TaskId(id(2)), ZonedTimeRange(Instant.parse("2026-01-02T10:00:00Z"), Instant.parse("2026-01-02T11:00:00Z"), TimeZone.UTC)))), persistentListOf())
        val applier = PlanBranchApplier(IdentityTransactionRunner, repository, generator()) { snapshot.copy(referenceNow = Instant.parse("2026-01-02T09:30:00Z")) }
        assertEquals(0, repository.blocks.size)
        assertEquals(PlanBranchApplyResult.Applied, applier.apply(branch, Instant.parse("2026-01-02T09:30:00Z")))
        assertEquals(1, repository.blocks.size)
    }

    @Test fun `structurally changed current facts refuse stale apply`() = runBlocking {
        val snapshot = snapshot(); val repository = InMemoryTasks()
        val branch = PlanBranch(PlanBranchId(id(3)), PlanningRequest.FullReplan, snapshot, persistentListOf(), persistentListOf())
        val applier = PlanBranchApplier(IdentityTransactionRunner, repository, generator()) { snapshot.copy(profile = PlanningProfile(PlanningProfileId(id(9)), "changed", PlanningProfileConfiguration.Unconfigured)) }
        assertEquals(PlanBranchApplyResult.Stale, applier.apply(branch, Instant.parse("2026-01-02T09:00:00Z")))
    }

    @Test fun `apply rejects a proposal that reaches apply now`() = runBlocking {
        val snapshot = snapshot(); val repository = InMemoryTasks()
        val branch = PlanBranch(PlanBranchId(id(4)), PlanningRequest.FullReplan, snapshot,
            persistentListOf(FocusBlockMutation.Create(FocusBlockDraft(TaskId(id(2)), ZonedTimeRange(Instant.parse("2026-01-02T09:00:00Z"), Instant.parse("2026-01-02T10:00:00Z"), TimeZone.UTC)))), persistentListOf())
        val applier = PlanBranchApplier(IdentityTransactionRunner, repository, generator()) { snapshot }
        assertEquals(PlanBranchApplyResult.Stale, applier.apply(branch, Instant.parse("2026-01-02T09:00:00Z")))
        assertEquals(0, repository.blocks.size)
    }

    @Test fun `transaction rollback leaves no partial branch mutations`() = runBlocking {
        val snapshot = snapshot(); val repository = InMemoryTasks(failOnInsert = 2)
        val branch = PlanBranch(PlanBranchId(id(5)), PlanningRequest.FullReplan, snapshot, persistentListOf(
            FocusBlockMutation.Create(FocusBlockDraft(TaskId(id(2)), ZonedTimeRange(Instant.parse("2026-01-02T10:00:00Z"), Instant.parse("2026-01-02T11:00:00Z"), TimeZone.UTC))),
            FocusBlockMutation.Create(FocusBlockDraft(TaskId(id(2)), ZonedTimeRange(Instant.parse("2026-01-02T11:00:00Z"), Instant.parse("2026-01-02T12:00:00Z"), TimeZone.UTC))),
        ), persistentListOf())
        var failed = false
        try { PlanBranchApplier(RollingBackTransactions(repository), repository, generator()) { snapshot }.apply(branch, Instant.parse("2026-01-02T09:00:00Z")) } catch (_: IllegalStateException) { failed = true }
        assertEquals(true, failed)
        assertEquals(0, repository.blocks.size)
    }

    private fun snapshot() = PlanningSnapshot(Instant.parse("2026-01-02T09:00:00Z"), PlanningHorizon(Instant.parse("2026-01-02T09:00:00Z"), Instant.parse("2026-01-02T12:00:00Z")), PlanningProfile(PlanningProfileId(id(9)), "profile", PlanningProfileConfiguration.Unconfigured), persistentListOf(), persistentListOf(), persistentListOf(), persistentListOf(), persistentListOf(), persistentListOf(), persistentListOf(), persistentListOf())
    private fun generator() = RfcUuidV7Generator(EpochMillisecondsClock { 1 }, RandomBytes { ByteArray(it) { 1 } })
    private fun id(number: Int) = "018f6e68-7d0c-7000-8000-${number.toString().padStart(12, '0')}"
}

private object IdentityTransactionRunner : ApplicationTransactionRunner { override suspend fun <T> inWriteTransaction(block: suspend () -> T): T = block() }
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
