package dev.agenticscheduler.application.history

import dev.agenticscheduler.application.id.EpochMillisecondsClock
import dev.agenticscheduler.application.id.RandomBytes
import dev.agenticscheduler.application.id.RfcUuidV7Generator
import dev.agenticscheduler.application.persistence.ApplicationTransactionRunner
import dev.agenticscheduler.application.persistence.CommittedMutation
import dev.agenticscheduler.application.persistence.LocalReplicaCausalState
import dev.agenticscheduler.application.persistence.MutationJournalRepository
import dev.agenticscheduler.sync.EventImage
import dev.agenticscheduler.sync.EventPut
import dev.agenticscheduler.sync.MutationOrigin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class MutationCoordinatorTest {
    @Test fun `successful local mutations allocate one durable sequential dot each`() = kotlinx.coroutines.runBlocking {
        val journal = MemoryJournal()
        val coordinator = coordinator(journal)
        coordinator.execute(MutationOrigin.User) { record(put("1")); "first" }
        coordinator.execute(MutationOrigin.User) { record(put("2")); "second" }

        assertEquals(listOf(0L, 1L), journal.operations.map { it.operation.dvv.dot.counter })
        assertEquals(1L, journal.state?.lastCounter)
        assertEquals(listOf(0L, 1L), journal.operations.map { it.operation.hlc.logical })
    }

    @Test fun `journal failure publishes neither dot nor causal state`() = kotlinx.coroutines.runBlocking {
        val journal = MemoryJournal(failAppend = true)
        assertFailsWith<IllegalStateException> { coordinator(journal).execute(MutationOrigin.User) { record(put("1")); Unit } }
        assertEquals(null, journal.state)
        assertEquals(emptyList(), journal.operations)
    }

    private fun coordinator(journal: MemoryJournal) = MutationCoordinator(
        transactions = IdentityTransactions, journal = journal,
        ids = RfcUuidV7Generator(EpochMillisecondsClock { 1 }, RandomBytes { size -> ByteArray(size) { nextByte++.toByte() } }),
        wallClock = MutationWallClock { 100 },
    )

    private fun put(id: String) = EventPut(null, EventImage("00000000-0000-7000-8000-00000000000$id", "event", "HARD", "UNPINNED", "ALL_DAY", "2026-01-01", "2026-01-02"))
}

private var nextByte = 0
private object IdentityTransactions : ApplicationTransactionRunner { override suspend fun <T> inWriteTransaction(block: suspend () -> T): T = block() }

private class MemoryJournal(private val failAppend: Boolean = false) : MutationJournalRepository {
    var state: LocalReplicaCausalState? = null
    val operations = mutableListOf<CommittedMutation>()
    override suspend fun localReplicaState() = state
    override suspend fun saveLocalReplicaState(state: LocalReplicaCausalState) { this.state = state }
    override suspend fun appendCommittedMutation(mutation: CommittedMutation) {
        if (failAppend) error("forced journal failure")
        operations += mutation
    }
}
