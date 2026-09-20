package dev.agenticscheduler.application.history

import dev.agenticscheduler.application.persistence.SyncReceiveRepository
import dev.agenticscheduler.sync.SyncConflict
import dev.agenticscheduler.sync.SyncConflictStatus
import dev.agenticscheduler.sync.SyncSpaceId

/** D8's typed read surface for visible, durable conflict state. */
class SyncConflictQueryService(private val receiveState: SyncReceiveRepository) {
    suspend fun listOpen(syncSpaceId: SyncSpaceId): List<SyncConflict> =
        receiveState.conflicts(syncSpaceId).filter { it.status == SyncConflictStatus.OPEN }

    suspend fun get(conflictId: String): SyncConflict? = receiveState.conflict(conflictId)
}
