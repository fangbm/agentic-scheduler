package dev.agenticscheduler.application.history

import dev.agenticscheduler.application.id.UuidV7Generator
import dev.agenticscheduler.application.persistence.ApplicationTransactionRunner
import dev.agenticscheduler.application.persistence.CommittedMutation
import dev.agenticscheduler.application.persistence.HistoryRepository
import dev.agenticscheduler.application.persistence.LocalReplicaCausalState
import dev.agenticscheduler.application.persistence.MutationJournalRepository
import dev.agenticscheduler.application.persistence.TaskRepository
import dev.agenticscheduler.domain.id.FocusBlockId
import dev.agenticscheduler.sync.CausalRelation
import dev.agenticscheduler.sync.DvvSnapshot
import dev.agenticscheduler.sync.FocusBlockDelete
import dev.agenticscheduler.sync.FocusBlockPut
import dev.agenticscheduler.sync.FocusBlockPutAgainstTombstone
import dev.agenticscheduler.sync.FocusBlockTombstoneCausality
import dev.agenticscheduler.sync.HybridLogicalClock
import dev.agenticscheduler.sync.ReplicaId
import dev.agenticscheduler.sync.SyncOperation
import dev.agenticscheduler.sync.toDottedVersionVector
import dev.agenticscheduler.sync.toTimestamp
import dev.agenticscheduler.sync.operationKind

sealed interface FocusBlockReplayResult {
    data object Applied : FocusBlockReplayResult
    data object Duplicate : FocusBlockReplayResult
    data object SuppressedByTombstone : FocusBlockReplayResult
    data class ConcurrentConflict(val focusBlockIds: List<String>) : FocusBlockReplayResult
    data class Unsupported(val operationKinds: List<String>) : FocusBlockReplayResult
}

/**
 * Narrow D7 replay seam for FocusBlock operations. It persists an accepted operation locally,
 * but never chooses a winner for concurrent facts; D8 owns semantic merge beyond this guard.
 */
class FocusBlockOperationReplayer(
    private val transactions: ApplicationTransactionRunner,
    private val journal: MutationJournalRepository,
    private val history: HistoryRepository,
    private val tasks: TaskRepository,
    private val ids: UuidV7Generator,
    private val wallClock: MutationWallClock,
) {
    suspend fun replay(operation: SyncOperation): FocusBlockReplayResult = transactions.inWriteTransaction {
        if (history.mutation(operation.mutationId) != null) return@inWriteTransaction FocusBlockReplayResult.Duplicate
        val unsupported = operation.orderedMutations.filter { it !is FocusBlockPut && it !is FocusBlockDelete }.map { it.operationKind() }
        if (unsupported.isNotEmpty()) return@inWriteTransaction FocusBlockReplayResult.Unsupported(unsupported)

        val actions = mutableListOf<ReplayAction>()
        val conflicts = mutableListOf<String>()
        var suppressed = false
        operation.orderedMutations.forEach { mutation -> when (mutation) {
            is FocusBlockPut -> {
                when (val decision = history.focusBlockTombstone(mutation.entityId)?.let { FocusBlockTombstoneCausality.decide(operation.dvv, it.dvv) }) {
                    null, FocusBlockPutAgainstTombstone.APPLY -> actions += ReplayAction.Put(mutation)
                    FocusBlockPutAgainstTombstone.SUPPRESS_CAUSALLY_OLDER -> suppressed = true
                    FocusBlockPutAgainstTombstone.CONCURRENT_CONFLICT -> conflicts += mutation.entityId
                }
            }
            is FocusBlockDelete -> {
                val tombstone = history.focusBlockTombstone(mutation.entityId)
                when (val relation = tombstone?.dvv?.let { operation.dvv.toDottedVersionVector().relationTo(it.toDottedVersionVector()) }) {
                    null, CausalRelation.AFTER -> actions += ReplayAction.Delete(mutation)
                    CausalRelation.BEFORE, CausalRelation.EQUAL -> suppressed = true
                    CausalRelation.CONCURRENT -> conflicts += mutation.entityId
                }
            }
            else -> Unit
        } }
        if (conflicts.isNotEmpty()) return@inWriteTransaction FocusBlockReplayResult.ConcurrentConflict(conflicts.distinct().sorted())

        actions.forEach { action -> when (action) {
            is ReplayAction.Put -> tasks.upsertFocusBlock(action.mutation.after.toDomain())
            is ReplayAction.Delete -> tasks.deleteFocusBlock(FocusBlockId(action.mutation.entityId))
        } }
        journal.appendCommittedMutation(CommittedMutation(operation, wallClock.nowEpochMillis()))
        journal.advanceFocusBlockTombstones(operation, actions.filterIsInstance<ReplayAction.Delete>().map(ReplayAction.Delete::mutation))
        val prior = journal.localReplicaState()
        val replicaId = prior?.replicaId ?: ReplicaId(ids.next())
        val mergedContext = mergeContexts(prior?.observedContext.orEmpty(), operation.dvv)
        val mergedHlc = HybridLogicalClock.tickReceive(prior?.lastHlc, operation.hlc.toTimestamp(), wallClock.nowEpochMillis(), replicaId)
        journal.saveLocalReplicaState(LocalReplicaCausalState(replicaId, prior?.lastCounter ?: 0L, mergedContext, mergedHlc))
        if (actions.isEmpty() && suppressed) FocusBlockReplayResult.SuppressedByTombstone else FocusBlockReplayResult.Applied
    }

    private fun mergeContexts(current: Map<ReplicaId, Long>, incoming: DvvSnapshot): Map<ReplicaId, Long> = buildMap {
        putAll(current)
        incoming.toDottedVersionVector().observedContext().forEach { (replica, counter) -> put(replica, maxOf(get(replica) ?: -1L, counter)) }
    }
}

private sealed interface ReplayAction {
    data class Put(val mutation: FocusBlockPut) : ReplayAction
    data class Delete(val mutation: FocusBlockDelete) : ReplayAction
}
