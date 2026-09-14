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
    suspend fun getEntityChanges(entityKind: EntityKind, entityId: String): List<HistoryChange> = history.entityChanges(entityKind, entityId)
    suspend fun getDiff(mutationId: String): List<HistoryChange> = history.diff(mutationId)

    data class TimelineCursor(val physicalMillis: Long, val logical: Long, val mutationId: String)

    private fun CommittedMutation.isAfter(cursor: TimelineCursor): Boolean {
        val timestamp = operation.hlc
        val physical = timestamp.physicalMillis.compareTo(cursor.physicalMillis)
        if (physical != 0) return physical > 0
        val logical = timestamp.logical.compareTo(cursor.logical)
        if (logical != 0) return logical > 0
        return operation.mutationId > cursor.mutationId
    }

    private companion object { const val DEFAULT_LIMIT = 50; const val MAX_LIMIT = 200 }
}
