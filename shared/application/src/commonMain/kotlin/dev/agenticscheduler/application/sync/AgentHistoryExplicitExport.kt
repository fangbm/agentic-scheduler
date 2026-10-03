package dev.agenticscheduler.application.sync

import dev.agenticscheduler.application.id.EpochMillisecondsClock
import dev.agenticscheduler.application.id.UuidV7Generator
import dev.agenticscheduler.sync.AgentHlcSnapshot
import dev.agenticscheduler.sync.AgentReplicaId
import dev.agenticscheduler.sync.AgentSyncEvent
import dev.agenticscheduler.sync.AgentThreadSyncId
import dev.agenticscheduler.sync.SyncSpaceId

data class AgentHistoryExportMember(val sourceKind: String, val sourceId: String, val event: AgentSyncEvent, val finalized: Boolean)
data class AgentHistoryExportTurn(
    val turnId: String,
    val parentTurnIds: List<String>,
    val ancestryVerified: Boolean,
    val finalized: Boolean,
    val members: List<AgentHistoryExportMember>,
    val manifest: AgentSyncEvent?,
    val wireValid: Boolean = true,
)
data class AgentHistoryExportThread(
    val threadId: AgentThreadSyncId,
    val tracked: Boolean,
    val deleted: Boolean,
    val creation: AgentSyncEvent?,
    val turns: List<AgentHistoryExportTurn>,
)
data class AgentHistoryExportSnapshot(val threads: List<AgentHistoryExportThread>, val legacyUnverifiedThreadCount: Int)

/** Implemented by local persistence from v15 provenance and immutable D9-01 row snapshots. */
fun interface AgentHistoryExportSource { suspend fun snapshot(): AgentHistoryExportSnapshot }

data class AgentHistoryExportAvailability(
    val eligibleTurns: Int,
    val skippedLegacyThreads: Int,
    val skippedUnverifiedTurns: Int,
    val alreadyMappedFacts: Int,
)
data class AgentHistoryExportResult(
    val newlyQueuedFacts: Int,
    val previouslyQueuedFacts: Int,
    val skippedLegacyThreads: Int,
    val skippedUnverifiedTurns: Int,
)

/** Explicit user-action service. It is not provided to Agent Tools, Provider runs, or background jobs. */
class AgentHistoryExplicitExport(
    private val enrollments: LocalEnrollmentRepository,
    private val transportState: AgentSyncTransportPersistence,
    private val sync: AgentSyncPersistence,
    private val mapping: AgentSyncHistoricalExportPersistence,
    private val source: AgentHistoryExportSource,
    private val ids: UuidV7Generator,
    private val clock: EpochMillisecondsClock,
) {
    suspend fun availability(space: SyncSpaceId): AgentHistoryExportAvailability {
        requireActive(space)
        val snapshot = source.snapshot()
        val exportable = snapshot.threads.filter { it.tracked && !it.deleted }.flatMap { thread ->
            thread.turns.filter { eligible(thread, it) }
        }
        val eligibleTurns = exportable.size
        val mappingCount = mapping.historicalExportMappings(space).size
        return AgentHistoryExportAvailability(eligibleTurns, snapshot.legacyUnverifiedThreadCount,
            snapshot.threads.filter { it.tracked && !it.deleted }.sumOf { t -> t.turns.count { !eligible(t, it) } }, mappingCount)
    }

    /** Called only by the visible owner action. It persists facts/outbox records and performs no I/O. */
    suspend fun exportFromUser(space: SyncSpaceId): AgentHistoryExportResult {
        requireActive(space)
        check(transportState.conversationConsent(space)) { "AGENT_CONVERSATION_CONSENT_REQUIRED" }
        val local = sync.localReplica(space) ?: error("Provision the Agent replica before explicit history export.")
        val snapshot = source.snapshot()
        val existing = mapping.historicalExportMappings(space).associateBy { it.sourceKind to it.sourceId }
        val candidates = mutableListOf<AgentHistoryExportMember>()
        val eligibleTurns = mutableListOf<Pair<AgentHistoryExportThread, AgentHistoryExportTurn>>()
        var skipped = 0
        for (thread in snapshot.threads.filter { it.tracked && !it.deleted }.sortedBy { it.threadId.value }) {
            thread.turns.forEach { turn ->
                if (eligible(thread, turn)) eligibleTurns += thread to turn else skipped++
            }
        }
        for ((thread, turn) in eligibleTurns) {
            val creation = thread.creation ?: continue
            candidates += AgentHistoryExportMember("THREAD_CREATED", thread.threadId.value, creation, true)
            candidates += turn.members
            candidates += AgentHistoryExportMember("TURN_FINALIZED", turn.turnId, requireNotNull(turn.manifest), true)
        }
        var newlyQueued = 0
        var alreadyQueued = 0
        for (fact in candidates.distinctBy { it.sourceKind to it.sourceId }) {
            if (!fact.finalized || fact.event is dev.agenticscheduler.sync.ThreadDeleted || fact.event is dev.agenticscheduler.sync.ThreadDeleteConflictResolved)
                continue
            val prepared = mapping.prepareHistoricalExport(space, fact.sourceKind, fact.sourceId, dev.agenticscheduler.sync.MutationId(ids.next()),
                AgentHlcSnapshot(clock.nowMilliseconds(), 0, local.replicaId), fact.event)
            if (prepared.createdMapping) newlyQueued++ else alreadyQueued++
        }
        return AgentHistoryExportResult(newlyQueued, alreadyQueued, snapshot.legacyUnverifiedThreadCount, skipped)
    }

    private suspend fun requireActive(space: SyncSpaceId) {
        check(enrollments.states().filterIsInstance<LocalEnrollmentState.Active>().any { it.syncSpaceId == space }) {
            "NO_ACTIVE_ENROLLMENT"
        }
    }

    private fun eligible(thread: AgentHistoryExportThread, turn: AgentHistoryExportTurn): Boolean =
        thread.tracked && !thread.deleted && thread.creation != null && turn.ancestryVerified && turn.finalized &&
            turn.members.isNotEmpty() && turn.members.all { it.finalized } && turn.wireValid && turn.manifest is dev.agenticscheduler.sync.TurnFinalized &&
            turn.manifest.threadId == thread.threadId && turn.manifest.turnId.value == turn.turnId &&
            turn.manifest.parentTurnIds.map { it.value } == turn.parentTurnIds &&
            dev.agenticscheduler.application.sync.agentHistoryTurnLinkError(turn.manifest, turn.members.map { it.event }) == null
}
