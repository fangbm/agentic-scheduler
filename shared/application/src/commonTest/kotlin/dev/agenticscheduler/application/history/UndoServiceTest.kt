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
import dev.agenticscheduler.domain.planning.PlanningProfileConfiguration
import dev.agenticscheduler.domain.task.FocusBlock
import dev.agenticscheduler.domain.task.Task
import dev.agenticscheduler.domain.task.TaskEffort
import dev.agenticscheduler.domain.task.TaskPriority
import dev.agenticscheduler.domain.task.TaskStatus
import dev.agenticscheduler.domain.task.TaskDependency
import dev.agenticscheduler.domain.task.WorkLog
import dev.agenticscheduler.sync.DotSnapshot
import dev.agenticscheduler.sync.DvvSnapshot
import dev.agenticscheduler.sync.EntityKind
import dev.agenticscheduler.sync.EventPut
import dev.agenticscheduler.sync.FocusBlockImage
import dev.agenticscheduler.sync.FocusBlockPut
import dev.agenticscheduler.sync.FocusBlockPutAgainstTombstone
import dev.agenticscheduler.sync.FocusBlockDelete
import dev.agenticscheduler.sync.HlcSnapshot
import dev.agenticscheduler.sync.MutationOrigin
import dev.agenticscheduler.sync.SyncOperation
import dev.agenticscheduler.sync.EntityMutation
import dev.agenticscheduler.sync.PlanningProfilePut
import dev.agenticscheduler.sync.TaskPut
import dev.agenticscheduler.sync.WorkLogAppend
import dev.agenticscheduler.sync.AcademicYearImage
import dev.agenticscheduler.sync.TaskDependencyImage
import dev.agenticscheduler.sync.WorkLogImage
import dev.agenticscheduler.sync.ZonedTimeRangeImage
import dev.agenticscheduler.sync.AcademicYearPut
import dev.agenticscheduler.sync.TaskDependencyPut
import dev.agenticscheduler.sync.operationKind
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

class UndoServiceTest {
    @Test fun `event update undo is a new compensating mutation`() = kotlinx.coroutines.runBlocking {
        val id = EventId("00000000-0000-7000-8000-000000000010")
        val before = event(id, "before")
        val after = event(id, "after")
        val events = MemoryEvents(after)
        val history = MemoryHistory("00000000-0000-7000-8000-000000000020", EventPut(before.toSemanticImage(), after.toSemanticImage()))
        val journal = MemoryJournalForUndo()
        val result = UndoService(coordinator(journal), history, events, MemoryTasksForUndo(), MemoryProfiles(), NoActiveSyncSpaceWritePolicy).undo(history.id)

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
        val result = UndoService(coordinator(journal), history, events, MemoryTasksForUndo(), MemoryProfiles(), NoActiveSyncSpaceWritePolicy).undo(history.id)

        assertEquals(UndoResult.Conflict(listOf(id.value)), result)
        assertEquals(emptyList(), journal.mutations)
    }

    @Test fun `undo cannot write an open conflicted semantic group`() = kotlinx.coroutines.runBlocking {
        val id = EventId("00000000-0000-7000-8000-000000000010")
        val before = event(id, "before"); val after = event(id, "after")
        val events = MemoryEvents(after)
        val history = MemoryHistory("00000000-0000-7000-8000-000000000020", EventPut(before.toSemanticImage(), after.toSemanticImage()))
        val journal = MemoryJournalForUndo()
        val block = SyncConflictWriteBlock("conflict-1", EntityKind.EVENT, id.value, listOf("title"))
        val policy = SyncConflictWritePolicy { proposed ->
            val inverse = assertIs<EventPut>(proposed.single())
            assertEquals(after.toSemanticImage(), inverse.before)
            assertEquals(before.toSemanticImage(), inverse.after)
            listOf(block)
        }

        val result = UndoService(coordinator(journal), history, events, MemoryTasksForUndo(), MemoryProfiles(), policy).undo(history.id)

        assertEquals(UndoResult.BlockedBySyncConflict(listOf(block)), result)
        assertEquals(after, events.value)
        assertEquals(emptyList(), journal.mutations)
    }

    @Test fun `task and PlanningProfile updates undo through one compensating mutation each`() = kotlinx.coroutines.runBlocking {
        val taskId = TaskId("00000000-0000-7000-8000-000000000040")
        val taskBefore = task(taskId, "before"); val taskAfter = task(taskId, "after")
        val taskJournal = MemoryJournalForUndo()
        val tasks = MemoryTasksForUndo(taskAfter)
        val taskResult = UndoService(coordinator(taskJournal), MemoryHistory("00000000-0000-7000-8000-000000000041", TaskPut(taskBefore.toSemanticImage(), taskAfter.toSemanticImage())), MemoryEvents(event(EventId("00000000-0000-7000-8000-000000000042"), "unused")), tasks, MemoryProfiles(), NoActiveSyncSpaceWritePolicy).undo("00000000-0000-7000-8000-000000000041")
        assertIs<UndoResult.Applied>(taskResult)
        assertEquals(taskBefore, tasks.task)
        assertIs<TaskPut>(taskJournal.mutations.single().operation.orderedMutations.single())

        val profileId = PlanningProfileId("00000000-0000-7000-8000-000000000043")
        val profileBefore = PlanningProfile(profileId, "before", PlanningProfileConfiguration.Unconfigured)
        val profileAfter = PlanningProfile(profileId, "after", PlanningProfileConfiguration.Unconfigured)
        val profiles = MemoryProfiles(profileAfter); val profileJournal = MemoryJournalForUndo()
        val profileResult = UndoService(coordinator(profileJournal), MemoryHistory("00000000-0000-7000-8000-000000000044", PlanningProfilePut(profileBefore.toSemanticImage(), profileAfter.toSemanticImage())), MemoryEvents(event(EventId("00000000-0000-7000-8000-000000000045"), "unused")), MemoryTasksForUndo(), profiles, NoActiveSyncSpaceWritePolicy).undo("00000000-0000-7000-8000-000000000044")
        assertIs<UndoResult.Applied>(profileResult)
        assertEquals(profileBefore, profiles.value)
        assertIs<PlanningProfilePut>(profileJournal.mutations.single().operation.orderedMutations.single())
        Unit
    }

    @Test fun `FocusBlock create move resize and delete undo with their frozen inverse semantics`() = kotlinx.coroutines.runBlocking {
        val original = focus("00000000-0000-7000-8000-000000000050", "2026-01-01T10:00:00Z", "2026-01-01T11:00:00Z")
        val moved = focus(original.id.value, "2026-01-01T11:00:00Z", "2026-01-01T12:00:00Z")
        val resized = focus(original.id.value, "2026-01-01T11:00:00Z", "2026-01-01T13:00:00Z")

        val createTasks = MemoryTasksForUndo(focus = original)
        val createJournal = MemoryJournalForUndo()
        assertIs<UndoResult.Applied>(UndoService(coordinator(createJournal), MemoryHistory("00000000-0000-7000-8000-000000000051", FocusBlockPut(null, original.toSemanticImage())), unusedEvents(), createTasks, MemoryProfiles(), NoActiveSyncSpaceWritePolicy).undo("00000000-0000-7000-8000-000000000051"))
        assertNull(createTasks.focus)
        assertIs<FocusBlockDelete>(createJournal.mutations.single().operation.orderedMutations.single())

        val moveTasks = MemoryTasksForUndo(focus = moved)
        assertIs<UndoResult.Applied>(UndoService(coordinator(MemoryJournalForUndo()), MemoryHistory("00000000-0000-7000-8000-000000000052", FocusBlockPut(original.toSemanticImage(), moved.toSemanticImage())), unusedEvents(), moveTasks, MemoryProfiles(), NoActiveSyncSpaceWritePolicy).undo("00000000-0000-7000-8000-000000000052"))
        assertEquals(original, moveTasks.focus)

        val resizeTasks = MemoryTasksForUndo(focus = resized)
        assertIs<UndoResult.Applied>(UndoService(coordinator(MemoryJournalForUndo()), MemoryHistory("00000000-0000-7000-8000-000000000053", FocusBlockPut(moved.toSemanticImage(), resized.toSemanticImage())), unusedEvents(), resizeTasks, MemoryProfiles(), NoActiveSyncSpaceWritePolicy).undo("00000000-0000-7000-8000-000000000053"))
        assertEquals(moved, resizeTasks.focus)

        val deleteId = "00000000-0000-7000-8000-000000000054"
        val deleteTasks = MemoryTasksForUndo()
        val deleteHistory = MemoryHistory(deleteId, FocusBlockDelete(original.toSemanticImage()), tombstone = FocusBlockTombstone(original.id.value, dev.agenticscheduler.sync.MutationId(deleteId), DvvSnapshot(emptyList(), DotSnapshot("00000000-0000-7000-8000-000000000001", 1))))
        assertIs<UndoResult.Applied>(UndoService(coordinator(MemoryJournalForUndo()), deleteHistory, unusedEvents(), deleteTasks, MemoryProfiles(), NoActiveSyncSpaceWritePolicy).undo(deleteId))
        assertEquals(original, deleteTasks.focus)
    }

    @Test fun `grouped Planner undo is all or nothing`() = kotlinx.coroutines.runBlocking {
        val first = focus("00000000-0000-7000-8000-000000000060", "2026-01-01T10:00:00Z", "2026-01-01T11:00:00Z")
        val second = focus("00000000-0000-7000-8000-000000000061", "2026-01-01T11:00:00Z", "2026-01-01T12:00:00Z")
        val id = "00000000-0000-7000-8000-000000000062"
        val successTasks = MemoryTasksForUndo(focus = first, extraFocus = second)
        val successJournal = MemoryJournalForUndo()
        val original = MemoryHistory(id, FocusBlockPut(null, first.toSemanticImage()), extraMutations = listOf(FocusBlockPut(null, second.toSemanticImage())))
        assertIs<UndoResult.Applied>(UndoService(coordinator(successJournal), original, unusedEvents(), successTasks, MemoryProfiles(), NoActiveSyncSpaceWritePolicy).undo(id))
        assertNull(successTasks.focus); assertNull(successTasks.extraFocus)
        assertEquals(2, successJournal.mutations.single().operation.orderedMutations.size)

        val diverged = second.copy(flexibility = dev.agenticscheduler.domain.planning.Flexibility.HARD)
        val failedTasks = MemoryTasksForUndo(focus = first, extraFocus = diverged)
        val failedJournal = MemoryJournalForUndo()
        assertEquals(UndoResult.Conflict(listOf(second.id.value)), UndoService(coordinator(failedJournal), original, unusedEvents(), failedTasks, MemoryProfiles(), NoActiveSyncSpaceWritePolicy).undo(id))
        assertEquals(first, failedTasks.focus); assertEquals(diverged, failedTasks.extraFocus); assertEquals(emptyList(), failedJournal.mutations)
    }

    @Test fun `every frozen unsupported Undo category returns a structured result without writing`() = kotlinx.coroutines.runBlocking {
        val event = event(EventId("00000000-0000-7000-8000-000000000070"), "created")
        val task = task(TaskId("00000000-0000-7000-8000-000000000071"), "created")
        val academicBefore = AcademicYearImage("00000000-0000-7000-8000-000000000072", "before", "2026-01-01", "2027-01-01")
        val academicAfter = AcademicYearImage("00000000-0000-7000-8000-000000000073", "after", "2027-01-01", "2028-01-01")
        val dependency = TaskDependencyImage("00000000-0000-7000-8000-000000000074", "00000000-0000-7000-8000-000000000075", "00000000-0000-7000-8000-000000000076")
        val workLog = WorkLogImage("00000000-0000-7000-8000-000000000077", "00000000-0000-7000-8000-000000000071", ZonedTimeRangeImage("2026-01-01T09:00:00Z", "2026-01-01T10:00:00Z", "UTC"))
        val unsupported = listOf<EntityMutation>(
            EventPut(null, event.toSemanticImage()),
            TaskPut(null, task.toSemanticImage()),
            AcademicYearPut(null, academicAfter),
            AcademicYearPut(academicBefore, academicAfter),
            TaskDependencyPut(null, dependency),
            WorkLogAppend(workLog),
        )
        unsupported.forEachIndexed { index, operation ->
            val mutationId = "00000000-0000-7000-8000-0000000001${index.toString().padStart(2, '0')}"
            val journal = MemoryJournalForUndo()
            val result = UndoService(coordinator(journal), MemoryHistory(mutationId, operation), unusedEvents(), MemoryTasksForUndo(), MemoryProfiles(), NoActiveSyncSpaceWritePolicy).undo(mutationId)
            assertIs<UndoResult.Unsupported>(result, operation.operationKind())
            assertEquals(emptyList(), journal.mutations, operation.operationKind())
        }
        Unit
    }

    @Test fun `replay journals an older focus put without resurrecting its tombstone`() = kotlinx.coroutines.runBlocking {
        val replica = "00000000-0000-7000-8000-000000000001"
        val put = FocusBlockPut(null, FocusBlockImage("00000000-0000-7000-8000-000000000030", "00000000-0000-7000-8000-000000000031", ZonedTimeRangeImage("2026-01-01T10:00:00Z", "2026-01-01T11:00:00Z", "UTC"), dev.agenticscheduler.sync.FlexibilityImage.SOFT, dev.agenticscheduler.sync.PinStateImage.UNPINNED))
        val operation = SyncOperation("00000000-0000-7000-8000-000000000032", DvvSnapshot(emptyList(), DotSnapshot(replica, 1)), HlcSnapshot(1, 0, replica), MutationOrigin.User, listOf(put))
        val tombstone = FocusBlockTombstone(put.entityId, dev.agenticscheduler.sync.MutationId("00000000-0000-7000-8000-000000000033"), DvvSnapshot(listOf(dev.agenticscheduler.sync.VersionComponent(replica, 1)), DotSnapshot(replica, 2)))
        val history = MemoryHistory("00000000-0000-7000-8000-000000000034", put, tombstone = tombstone, present = false)
        val journal = MemoryJournalForUndo(); val tasks = MemoryTasksForUndo()
        val result = FocusBlockOperationReplayer(IdentityTransactionsForUndo, journal, history, tasks, RfcUuidV7Generator(EpochMillisecondsClock { 1 }, RandomBytes { ByteArray(it) { 1 } }), MutationWallClock { 1 }).replay(operation)

        assertEquals(FocusBlockReplayResult.SuppressedByTombstone, result)
        assertEquals(null, tasks.focus)
        assertEquals(listOf(operation), journal.mutations.map(CommittedMutation::operation))
    }

    @Test fun `older replayed delete is journaled but never downgrades a newer tombstone`() = kotlinx.coroutines.runBlocking {
        val replica = "00000000-0000-7000-8000-000000000001"
        val image = FocusBlockImage("00000000-0000-7000-8000-000000000080", "00000000-0000-7000-8000-000000000081", ZonedTimeRangeImage("2026-01-01T10:00:00Z", "2026-01-01T11:00:00Z", "UTC"), dev.agenticscheduler.sync.FlexibilityImage.SOFT, dev.agenticscheduler.sync.PinStateImage.UNPINNED)
        val newer = SyncOperation("00000000-0000-7000-8000-000000000082", DvvSnapshot(listOf(dev.agenticscheduler.sync.VersionComponent(replica, 1)), DotSnapshot(replica, 2)), HlcSnapshot(2, 0, replica), MutationOrigin.User, listOf(FocusBlockDelete(image)))
        val journal = MemoryJournalForUndo()
        val history = MemoryHistory("irrelevant", FocusBlockDelete(image), present = false, tombstoneProvider = { journal.tombstones[image.id] })
        val replayer = FocusBlockOperationReplayer(IdentityTransactionsForUndo, journal, history, MemoryTasksForUndo(), RfcUuidV7Generator(EpochMillisecondsClock { 1 }, RandomBytes { ByteArray(it) { 1 } }), MutationWallClock { 1 })
        assertEquals(FocusBlockReplayResult.Applied, replayer.replay(newer))
        val older = SyncOperation("00000000-0000-7000-8000-000000000083", DvvSnapshot(emptyList(), DotSnapshot(replica, 1)), HlcSnapshot(1, 0, replica), MutationOrigin.User, listOf(FocusBlockDelete(image)))
        assertEquals(FocusBlockReplayResult.SuppressedByTombstone, replayer.replay(older))
        assertEquals(newer.mutationId, journal.tombstones.getValue(image.id).deletionMutationId.value)
        assertEquals(listOf(newer, older), journal.mutations.map(CommittedMutation::operation))
    }

    @Test fun `replaying the same operation twice is idempotent for both Active State and journal`() = kotlinx.coroutines.runBlocking {
        val put = FocusBlockPut(null, FocusBlockImage("00000000-0000-7000-8000-000000000090", "00000000-0000-7000-8000-000000000091", ZonedTimeRangeImage("2026-01-01T10:00:00Z", "2026-01-01T11:00:00Z", "UTC"), dev.agenticscheduler.sync.FlexibilityImage.SOFT, dev.agenticscheduler.sync.PinStateImage.UNPINNED))
        val operation = SyncOperation("00000000-0000-7000-8000-000000000092", DvvSnapshot(emptyList(), DotSnapshot("00000000-0000-7000-8000-000000000001", 1)), HlcSnapshot(1, 0, "00000000-0000-7000-8000-000000000001"), MutationOrigin.User, listOf(put))
        val journal = MemoryJournalForUndo()
        val history = MemoryHistory("irrelevant", put, present = false, mutationProvider = { id -> journal.mutations.firstOrNull { it.operation.mutationId == id } })
        val tasks = MemoryTasksForUndo()
        val replayer = FocusBlockOperationReplayer(IdentityTransactionsForUndo, journal, history, tasks, RfcUuidV7Generator(EpochMillisecondsClock { 1 }, RandomBytes { ByteArray(it) { 1 } }), MutationWallClock { 1 })
        assertEquals(FocusBlockReplayResult.Applied, replayer.replay(operation))
        val applied = tasks.focus
        assertEquals(FocusBlockReplayResult.Duplicate, replayer.replay(operation))
        assertEquals(applied, tasks.focus)
        assertEquals(listOf(operation), journal.mutations.map(CommittedMutation::operation))
    }

    private fun event(id: EventId, title: String) = Event(id, title, dev.agenticscheduler.domain.time.AllDayRange(kotlinx.datetime.LocalDate(2026, 1, 1), kotlinx.datetime.LocalDate(2026, 1, 2)), dev.agenticscheduler.domain.planning.Flexibility.HARD, dev.agenticscheduler.domain.planning.PinState.UNPINNED)
    private fun task(id: TaskId, title: String) = Task(id, title, TaskStatus.OPEN, TaskPriority.NORMAL, TaskEffort(null, kotlin.time.Duration.ZERO, null), null)
    private fun focus(id: String, start: String, end: String) = FocusBlock(FocusBlockId(id), TaskId("00000000-0000-7000-8000-000000000099"), dev.agenticscheduler.domain.time.ZonedTimeRange(kotlin.time.Instant.parse(start), kotlin.time.Instant.parse(end), kotlinx.datetime.TimeZone.UTC), dev.agenticscheduler.domain.planning.Flexibility.SOFT, dev.agenticscheduler.domain.planning.PinState.UNPINNED)
    private fun unusedEvents() = MemoryEvents(event(EventId("00000000-0000-7000-8000-000000000098"), "unused"))
    private fun coordinator(journal: MemoryJournalForUndo) = MutationCoordinator(IdentityTransactionsForUndo, journal, RfcUuidV7Generator(EpochMillisecondsClock { 1 }, RandomBytes { ByteArray(it) { 1 } }), MutationWallClock { 1 })
}

private object IdentityTransactionsForUndo : ApplicationTransactionRunner { override suspend fun <T> inWriteTransaction(block: suspend () -> T): T = block() }
private class MemoryJournalForUndo : MutationJournalRepository {
    var state: LocalReplicaCausalState? = null; val mutations = mutableListOf<CommittedMutation>(); val tombstones = mutableMapOf<String, FocusBlockTombstone>()
    override suspend fun localReplicaState() = state
    override suspend fun saveLocalReplicaState(state: LocalReplicaCausalState) { this.state = state }
    override suspend fun appendCommittedMutation(mutation: CommittedMutation) { mutations += mutation }
    override suspend fun advanceFocusBlockTombstones(operation: SyncOperation, acceptedDeletes: List<dev.agenticscheduler.sync.FocusBlockDelete>) { acceptedDeletes.forEach { delete -> tombstones[delete.entityId] = FocusBlockTombstone(delete.entityId, dev.agenticscheduler.sync.MutationId(operation.mutationId), operation.dvv) } }
}
private class MemoryHistory(val id: String, mutation: EntityMutation, private val tombstone: FocusBlockTombstone? = null, private val present: Boolean = true, extraMutations: List<EntityMutation> = emptyList(), private val tombstoneProvider: (() -> FocusBlockTombstone?)? = null, private val mutationProvider: ((String) -> CommittedMutation?)? = null) : HistoryRepository {
    private val value = CommittedMutation(SyncOperation(id, DvvSnapshot(emptyList(), DotSnapshot("00000000-0000-7000-8000-000000000001", 0)), HlcSnapshot(1, 0, "00000000-0000-7000-8000-000000000001"), MutationOrigin.User, listOf(mutation) + extraMutations), 1)
    override suspend fun timeline() = listOf(value); override suspend fun mutation(mutationId: String) = mutationProvider?.invoke(mutationId) ?: value.takeIf { present && it.operation.mutationId == mutationId }
    override suspend fun entityChanges(entityKind: EntityKind, entityId: String) = emptyList<HistoryChange>(); override suspend fun diff(mutationId: String) = emptyList<HistoryChange>()
    override suspend fun focusBlockTombstone(focusBlockId: String): FocusBlockTombstone? = (tombstoneProvider?.invoke() ?: tombstone)?.takeIf { it.focusBlockId == focusBlockId }
}
private class MemoryEvents(initial: Event) : EventRepository {
    var value = initial; override fun observeAll(): Flow<kotlinx.collections.immutable.ImmutableList<Event>> = flowOf(listOf(value).toImmutableList())
    override suspend fun get(id: EventId) = value.takeIf { it.id == id }; override suspend fun upsert(event: Event) { value = event }
}
private class MemoryTasksForUndo(initialTask: Task? = null, focus: FocusBlock? = null, extraFocus: FocusBlock? = null) : TaskRepository {
    var task: Task? = initialTask; var focus: FocusBlock? = focus; var extraFocus: FocusBlock? = extraFocus
    override fun observeTasks() = flowOf(listOfNotNull(task).toImmutableList()); override suspend fun getTask(id: TaskId): Task? = task?.takeIf { it.id == id }; override suspend fun upsertTask(task: Task) { this.task = task }
    override fun observeFocusBlocks() = flowOf(listOfNotNull(focus, extraFocus).toImmutableList()); override suspend fun getFocusBlock(id: FocusBlockId): FocusBlock? = listOfNotNull(focus, extraFocus).firstOrNull { it.id == id }; override suspend fun upsertFocusBlock(focusBlock: FocusBlock) { if (focus?.id == focusBlock.id || extraFocus == null) focus = focusBlock else extraFocus = focusBlock }; override suspend fun deleteFocusBlock(id: FocusBlockId) { if (focus?.id == id) focus = null; if (extraFocus?.id == id) extraFocus = null }
    override fun observeWorkLogs() = flowOf(emptyList<WorkLog>().toImmutableList()); override suspend fun getWorkLog(id: WorkLogId): WorkLog? = null; override suspend fun upsertWorkLog(workLog: WorkLog) = Unit
    override fun observeDependencies() = flowOf(emptyList<TaskDependency>().toImmutableList()); override suspend fun getDependency(id: TaskDependencyId): TaskDependency? = null; override suspend fun upsertDependency(dependency: TaskDependency) = Unit
}
private class MemoryProfiles(initial: PlanningProfile? = null) : PlanningProfileRepository {
    var value: PlanningProfile? = initial
    override fun observeAll() = flowOf(listOfNotNull(value).toImmutableList()); override suspend fun get(id: PlanningProfileId): PlanningProfile? = value?.takeIf { it.id == id }; override suspend fun upsert(profile: PlanningProfile) { value = profile }
}
