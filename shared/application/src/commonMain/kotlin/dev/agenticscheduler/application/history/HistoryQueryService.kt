package dev.agenticscheduler.application.history

import dev.agenticscheduler.application.persistence.CommittedMutation
import dev.agenticscheduler.application.persistence.HistoryChange
import dev.agenticscheduler.application.persistence.HistoryRepository
import dev.agenticscheduler.sync.EntityKind

/** Read-only application capability for authoritative structured history. */
class HistoryQueryService(private val history: HistoryRepository) {
    suspend fun timeline(cursor: TimelineCursor? = null, limit: Int = DEFAULT_LIMIT): List<CommittedMutation> {
        require(limit in 1..MAX_LIMIT)
        return history.timeline().asSequence()
            .filter { mutation -> cursor == null || mutation.isAfter(cursor) }
            .take(limit)
            .toList()
    }

    suspend fun getMutation(mutationId: String): CommittedMutation? = history.mutation(mutationId)
    suspend fun getEntityChanges(entityKind: EntityKind, entityId: String, cursor: EntityChangesCursor? = null, limit: Int = DEFAULT_LIMIT): List<HistoryChange> {
        require(limit in 1..MAX_LIMIT)
        return history.entityChanges(entityKind, entityId).asSequence().filter { change -> cursor == null || change.isAfter(cursor) }.take(limit).toList()
    }
    suspend fun getDiff(mutationId: String): List<HistoryChange> = history.diff(mutationId)

    data class TimelineCursor(val physicalMillis: Long, val logical: Long, val replicaId: String, val mutationId: String)
    data class EntityChangesCursor(val physicalMillis: Long, val logical: Long, val replicaId: String, val mutationId: String, val ordinal: Int)

    private fun CommittedMutation.isAfter(cursor: TimelineCursor): Boolean {
        val timestamp = operation.hlc
        val physical = timestamp.physicalMillis.compareTo(cursor.physicalMillis)
        if (physical != 0) return physical > 0
        val logical = timestamp.logical.compareTo(cursor.logical)
        if (logical != 0) return logical > 0
        val replica = timestamp.replicaId.compareTo(cursor.replicaId)
        if (replica != 0) return replica > 0
        return operation.mutationId > cursor.mutationId
    }
    private fun HistoryChange.isAfter(cursor: EntityChangesCursor): Boolean {
        val physical = hlc.physicalMillis.compareTo(cursor.physicalMillis); if (physical != 0) return physical > 0
        val logical = hlc.logical.compareTo(cursor.logical); if (logical != 0) return logical > 0
        val replica = hlc.replicaId.value.compareTo(cursor.replicaId); if (replica != 0) return replica > 0
        val mutation = mutationId.compareTo(cursor.mutationId); if (mutation != 0) return mutation > 0
        return ordinal > cursor.ordinal
    }

    private companion object { const val DEFAULT_LIMIT = 50; const val MAX_LIMIT = 200 }
}
