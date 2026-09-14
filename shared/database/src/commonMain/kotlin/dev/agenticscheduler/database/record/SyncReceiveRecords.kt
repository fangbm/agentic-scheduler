package dev.agenticscheduler.database.record

import androidx.room3.ColumnInfo
import androidx.room3.Entity

@Entity(tableName = "sync_space_cursor")
data class SyncSpaceCursorRecord(
    @androidx.room3.PrimaryKey @ColumnInfo(name = "sync_space_id") val syncSpaceId: String,
    @ColumnInfo(name = "server_cursor") val serverCursor: Long,
)

@Entity(tableName = "protocol_quarantine", primaryKeys = ["sync_space_id", "mutation_id"])
data class ProtocolQuarantineRecord(
    @ColumnInfo(name = "sync_space_id") val syncSpaceId: String,
    @ColumnInfo(name = "mutation_id") val mutationId: String,
    @ColumnInfo(name = "server_cursor") val serverCursor: Long,
    val reason: String,
    val detail: String?,
)

@Entity(tableName = "sync_conflict")
data class SyncConflictRecord(
    @androidx.room3.PrimaryKey @ColumnInfo(name = "conflict_id") val conflictId: String,
    @ColumnInfo(name = "sync_space_id") val syncSpaceId: String,
    @ColumnInfo(name = "conflict_json") val conflictJson: String,
)
