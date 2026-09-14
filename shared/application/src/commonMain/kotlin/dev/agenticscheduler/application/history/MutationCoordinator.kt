package dev.agenticscheduler.application.history

import dev.agenticscheduler.application.id.UuidV7Generator
import dev.agenticscheduler.application.persistence.ApplicationTransactionRunner
import dev.agenticscheduler.application.persistence.CommittedMutation
import dev.agenticscheduler.application.persistence.LocalReplicaCausalState
import dev.agenticscheduler.application.persistence.MutationJournalRepository
import dev.agenticscheduler.sync.DottedVersionVector
import dev.agenticscheduler.sync.Dot
import dev.agenticscheduler.sync.EntityMutation
import dev.agenticscheduler.sync.HybridLogicalClock
import dev.agenticscheduler.sync.MutationId
import dev.agenticscheduler.sync.MutationOrigin
import dev.agenticscheduler.sync.ReplicaId
import dev.agenticscheduler.sync.SyncOperation
import dev.agenticscheduler.sync.toSnapshot

fun interface MutationWallClock { fun nowEpochMillis(): Long }

data class MutationExecution<T>(val value: T, val mutationId: MutationId)

/**
 * HST-001's only application seam for supported local source-fact writes.
 * The active-state work in [block], operation append, and local causal state
 * are deliberately enclosed by one [ApplicationTransactionRunner] transaction.
 */
class MutationCoordinator(
    private val transactions: ApplicationTransactionRunner,
    private val journal: MutationJournalRepository,
    private val ids: UuidV7Generator,
    private val wallClock: MutationWallClock,
) {
    suspend fun <T> execute(origin: MutationOrigin, block: suspend MutationScope.() -> T): MutationExecution<T> =
        checkNotNull(executeIfAny(origin, block)) { "A committed MutationId must own at least one typed entity mutation." }

    /** Allows a validated no-op outcome (for example stale Planner Apply) without allocating causal state. */
    suspend fun <T> executeIfAny(origin: MutationOrigin, block: suspend MutationScope.() -> T): MutationExecution<T>? =
        transactions.inWriteTransaction {
            val scope = MutationScope()
            val value = scope.block()
            val mutations = scope.orderedMutations()
            if (mutations.isEmpty()) return@inWriteTransaction null

            val now = wallClock.nowEpochMillis()
            val prior = journal.localReplicaState()
            val replicaId = prior?.replicaId ?: ReplicaId(ids.next())
            val nextCounter = prior?.lastCounter?.let { checkedIncrement(it) } ?: 0L
            val dvv = DottedVersionVector(prior?.observedContext.orEmpty(), Dot(replicaId, nextCounter))
            val hlc = HybridLogicalClock.tickLocal(prior?.lastHlc, now, replicaId)
            val mutationId = MutationId(ids.next())
            val operation = SyncOperation(mutationId.value, dvv.toSnapshot(), hlc.toSnapshot(), origin, mutations)

            journal.appendCommittedMutation(CommittedMutation(operation, now))
            journal.saveLocalReplicaState(LocalReplicaCausalState(replicaId, nextCounter, dvv.observedContext(), hlc))
            MutationExecution(value, mutationId)
        }

    private fun checkedIncrement(value: Long): Long = check(value < Long.MAX_VALUE) { "Causal counter overflow." }.let { value + 1 }
}

class MutationScope internal constructor() {
    private val mutations = mutableListOf<EntityMutation>()
    fun record(mutation: EntityMutation) { mutations += mutation }
    internal fun orderedMutations(): List<EntityMutation> = mutations.toList()
}
