package dev.agenticscheduler.application.history

import dev.agenticscheduler.application.id.EpochMillisecondsClock
import dev.agenticscheduler.application.id.RandomBytes
import dev.agenticscheduler.application.id.RfcUuidV7Generator
import dev.agenticscheduler.application.persistence.ApplicationTransactionRunner
import dev.agenticscheduler.application.persistence.CommittedMutation
import dev.agenticscheduler.application.persistence.EventRepository
import dev.agenticscheduler.application.persistence.FocusBlockTombstone
import dev.agenticscheduler.application.persistence.HistoryChange
import dev.agenticscheduler.application.persistence.HistoryRepository
import dev.agenticscheduler.application.persistence.LocalReplicaCausalState
import dev.agenticscheduler.application.persistence.MutationJournalRepository
import dev.agenticscheduler.application.persistence.PlanningProfileRepository
import dev.agenticscheduler.application.persistence.TaskRepository
import dev.agenticscheduler.domain.event.Event
import dev.agenticscheduler.domain.id.EventId
import dev.agenticscheduler.domain.id.FocusBlockId
import dev.agenticscheduler.domain.id.PlanningProfileId
import dev.agenticscheduler.domain.id.TaskDependencyId
import dev.agenticscheduler.domain.id.TaskId
import dev.agenticscheduler.domain.id.WorkLogId
import dev.agenticscheduler.domain.planning.PlanningProfile
import dev.agenticscheduler.domain.task.FocusBlock
import dev.agenticscheduler.domain.task.Task
import dev.agenticscheduler.domain.task.TaskDependency
import dev.agenticscheduler.domain.task.WorkLog
import dev.agenticscheduler.sync.DotSnapshot
import dev.agenticscheduler.sync.DvvSnapshot
import dev.agenticscheduler.sync.EntityKind
import dev.agenticscheduler.sync.EventPut
import dev.agenticscheduler.sync.FocusBlockImage
import dev.agenticscheduler.sync.FocusBlockPut
import dev.agenticscheduler.sync.FocusBlockPutAgainstTombstone
import dev.agenticscheduler.sync.HlcSnapshot
import dev.agenticscheduler.sync.MutationOrigin
import dev.agenticscheduler.sync.SyncOperation
import dev.agenticscheduler.sync.EntityMutation
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class UndoServiceTest {
    @Test fun `event update undo is a new compensating mutation`() = kotlinx.coroutines.runBlocking {
        val id = EventId("00000000-0000-7000-8000-000000000010")
        val before = event(id, "before")
        val after = event(id, "after")
        val events = MemoryEvents(after)
        val history = MemoryHistory("00000000-0000-7000-8000-000000000020", EventPut(before.toSemanticImage(), after.toSemanticImage()))
        val journal = MemoryJournalForUndo()
        val result = UndoService(coordinator(journal), history, events, MemoryTasksForUndo(), MemoryProfiles()).undo(history.id)

        assertIs<UndoResult.Applied>(result)
        assertEquals(before, events.value)
        val inverse = assertIs<EventPut>(journal.mutations.single().operation.orderedMutations.single())
        assertEquals(after.toSemanticImage(), inverse.before)
        assertEquals(before.toSemanticImage(), inverse.after)
    }

    @Test fun `diverged event update is an undo conflict without a new mutation`() = kotlinx.coroutines.runBlocking {
        val id = EventId("00000000-0000-7000-8000-000000000010")
        val before = event(id, "before"); val after = event(id, "after")
        val events = MemoryEvents(event(id, "newer"))
        val history = MemoryHistory("00000000-0000-7000-8000-000000000020", EventPut(before.toSemanticImage(), after.toSemanticImage()))
        val journal = MemoryJournalForUndo()
        val result = UndoService(coordinator(journal), history, events, MemoryTasksForUndo(), MemoryProfiles()).undo(history.id)

        assertEquals(UndoResult.Conflict(listOf(id.value)), result)
        assertEquals(emptyList(), journal.mutations)
    }

    @Test fun `replay journals an older focus put without resurrecting its tombstone`() = kotlinx.coroutines.runBlocking {
        val replica = "00000000-0000-7000-8000-000000000001"
        val put = FocusBlockPut(null, FocusBlockImage("00000000-0000-7000-8000-000000000030", "00000000-0000-7000-8000-000000000031", "2026-01-01T10:00:00Z", "2026-01-01T11:00:00Z", "UTC", "SOFT", "UNPINNED"))
        val operation = SyncOperation("00000000-0000-7000-8000-000000000032", DvvSnapshot(emptyList(), DotSnapshot(replica, 1)), HlcSnapshot(1, 0, replica), MutationOrigin.User, listOf(put))
        val tombstone = FocusBlockTombstone(put.entityId, dev.agenticscheduler.sync.MutationId("00000000-0000-7000-8000-000000000033"), DvvSnapshot(listOf(dev.agenticscheduler.sync.VersionComponent(replica, 1)), DotSnapshot(replica, 2)))
        val history = MemoryHistory("00000000-0000-7000-8000-000000000034", put, tombstone = tombstone, present = false)
        val journal = MemoryJournalForUndo(); val tasks = MemoryTasksForUndo()
        val result = FocusBlockOperationReplayer(IdentityTransactionsForUndo, journal, history, tasks, RfcUuidV7Generator(EpochMillisecondsClock { 1 }, RandomBytes { ByteArray(it) { 1 } }), MutationWallClock { 1 }).replay(operation)

        assertEquals(FocusBlockReplayResult.SuppressedByTombstone, result)
        assertEquals(null, tasks.focus)
        assertEquals(listOf(operation), journal.mutations.map(CommittedMutation::operation))
    }

    private fun event(id: EventId, title: String) = Event(id, title, dev.agenticscheduler.domain.time.AllDayRange(kotlinx.datetime.LocalDate(2026, 1, 1), kotlinx.datetime.LocalDate(2026, 1, 2)), dev.agenticscheduler.domain.planning.Flexibility.HARD, dev.agenticscheduler.domain.planning.PinState.UNPINNED)
    private fun coordinator(journal: MemoryJournalForUndo) = MutationCoordinator(IdentityTransactionsForUndo, journal, RfcUuidV7Generator(EpochMillisecondsClock { 1 }, RandomBytes { ByteArray(it) { 1 } }), MutationWallClock { 1 })
}

private object IdentityTransactionsForUndo : ApplicationTransactionRunner { override suspend fun <T> inWriteTransaction(block: suspend () -> T): T = block() }
private class MemoryJournalForUndo : MutationJournalRepository {
    var state: LocalReplicaCausalState? = null; val mutations = mutableListOf<CommittedMutation>()
    override suspend fun localReplicaState() = state
    override suspend fun saveLocalReplicaState(state: LocalReplicaCausalState) { this.state = state }
    override suspend fun appendCommittedMutation(mutation: CommittedMutation) { mutations += mutation }
    override suspend fun advanceFocusBlockTombstones(operation: SyncOperation, acceptedDeletes: List<dev.agenticscheduler.sync.FocusBlockDelete>) = Unit
}
private class MemoryHistory(val id: String, mutation: EntityMutation, private val tombstone: FocusBlockTombstone? = null, private val present: Boolean = true) : HistoryRepository {
    private val value = CommittedMutation(SyncOperation(id, DvvSnapshot(emptyList(), DotSnapshot("00000000-0000-7000-8000-000000000001", 0)), HlcSnapshot(1, 0, "00000000-0000-7000-8000-000000000001"), MutationOrigin.User, listOf(mutation)), 1)
    override suspend fun timeline() = listOf(value); override suspend fun mutation(mutationId: String) = value.takeIf { present && it.operation.mutationId == mutationId }
    override suspend fun entityChanges(entityKind: EntityKind, entityId: String) = emptyList<HistoryChange>(); override suspend fun diff(mutationId: String) = emptyList<HistoryChange>()
    override suspend fun focusBlockTombstone(focusBlockId: String): FocusBlockTombstone? = tombstone?.takeIf { it.focusBlockId == focusBlockId }
}
private class MemoryEvents(initial: Event) : EventRepository {
    var value = initial; override fun observeAll(): Flow<kotlinx.collections.immutable.ImmutableList<Event>> = flowOf(listOf(value).toImmutableList())
    override suspend fun get(id: EventId) = value.takeIf { it.id == id }; override suspend fun upsert(event: Event) { value = event }
}
private class MemoryTasksForUndo : TaskRepository {
    var focus: FocusBlock? = null
    override fun observeTasks() = flowOf(emptyList<Task>().toImmutableList()); override suspend fun getTask(id: TaskId): Task? = null; override suspend fun upsertTask(task: Task) = Unit
    override fun observeFocusBlocks() = flowOf(listOfNotNull(focus).toImmutableList()); override suspend fun getFocusBlock(id: FocusBlockId): FocusBlock? = focus?.takeIf { it.id == id }; override suspend fun upsertFocusBlock(focusBlock: FocusBlock) { focus = focusBlock }; override suspend fun deleteFocusBlock(id: FocusBlockId) { if (focus?.id == id) focus = null }
    override fun observeWorkLogs() = flowOf(emptyList<WorkLog>().toImmutableList()); override suspend fun getWorkLog(id: WorkLogId): WorkLog? = null; override suspend fun upsertWorkLog(workLog: WorkLog) = Unit
    override fun observeDependencies() = flowOf(emptyList<TaskDependency>().toImmutableList()); override suspend fun getDependency(id: TaskDependencyId): TaskDependency? = null; override suspend fun upsertDependency(dependency: TaskDependency) = Unit
}
private class MemoryProfiles : PlanningProfileRepository {
    override fun observeAll() = flowOf(emptyList<PlanningProfile>().toImmutableList()); override suspend fun get(id: PlanningProfileId): PlanningProfile? = null; override suspend fun upsert(profile: PlanningProfile) = Unit
}
