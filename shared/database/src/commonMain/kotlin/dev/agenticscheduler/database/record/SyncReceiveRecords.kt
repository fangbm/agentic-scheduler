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

@Entity(tableName = "pending_sync_receive", primaryKeys = ["sync_space_id", "mutation_id"])
data class PendingSyncReceiveRecord(
    @ColumnInfo(name = "sync_space_id") val syncSpaceId: String,
    @ColumnInfo(name = "mutation_id") val mutationId: String,
    @ColumnInfo(name = "server_cursor") val serverCursor: Long,
    @ColumnInfo(name = "payload_json") val payloadJson: String,
)

@Entity(tableName = "handled_receive_dot", primaryKeys = ["sync_space_id", "replica_id", "counter"])
data class HandledReceiveDotRecord(
    @ColumnInfo(name = "sync_space_id") val syncSpaceId: String,
    @ColumnInfo(name = "replica_id") val replicaId: String,
    val counter: Long,
    @ColumnInfo(name = "mutation_id") val mutationId: String,
)

/** D8-02b stores only the opaque secure-store reference, never key material. */
@Entity(tableName = "sync_space_key_epoch")
data class SyncSpaceKeyEpochRecord(
    @androidx.room3.PrimaryKey @ColumnInfo(name = "sync_space_id") val syncSpaceId: String,
    @ColumnInfo(name = "accepted_key_epoch") val acceptedKeyEpoch: Long,
    @ColumnInfo(name = "content_key_secret_ref") val contentKeySecretRef: String,
)

@Entity(tableName = "sync_space_key_state")
data class SyncSpaceKeyStateRecord(
    @androidx.room3.PrimaryKey @ColumnInfo(name = "sync_space_id") val syncSpaceId: String,
    @ColumnInfo(name = "active_encryption_epoch") val activeEncryptionEpoch: Long,
)

@Entity(tableName = "sync_space_content_key", primaryKeys = ["sync_space_id", "key_epoch"])
data class SyncSpaceContentKeyRecord(
    @ColumnInfo(name = "sync_space_id") val syncSpaceId: String,
    @ColumnInfo(name = "key_epoch") val keyEpoch: Long,
    @ColumnInfo(name = "content_key_secret_ref") val contentKeySecretRef: String,
    @ColumnInfo(name = "key_identity", defaultValue = "''") val keyIdentity: String = "",
    val usage: String,
)
