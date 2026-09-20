package dev.agenticscheduler.database.record

import androidx.room3.ColumnInfo
import androidx.room3.Entity
import androidx.room3.Index

@Entity(tableName = "mutation_record")
data class MutationRecord(
    @androidx.room3.PrimaryKey @ColumnInfo(name = "mutation_id") val mutationId: String,
    val origin: String,
    @ColumnInfo(name = "dvv_json") val dvvJson: String,
    @ColumnInfo(name = "hlc_physical_millis") val hlcPhysicalMillis: Long,
    @ColumnInfo(name = "hlc_logical") val hlcLogical: Long,
    @ColumnInfo(name = "hlc_replica_id") val hlcReplicaId: String,
    @ColumnInfo(name = "committed_at_epoch_millis") val committedAtEpochMillis: Long,
)

@Entity(tableName = "change_log_entry", primaryKeys = ["mutation_id", "ordinal"], indices = [Index("entity_kind", "entity_id"), Index("entry_id", unique = true)])
data class ChangeLogEntryRecord(
    @ColumnInfo(name = "entry_id") val entryId: String,
    @ColumnInfo(name = "mutation_id") val mutationId: String,
    val ordinal: Int,
    @ColumnInfo(name = "entity_kind") val entityKind: String,
    @ColumnInfo(name = "entity_id") val entityId: String,
    @ColumnInfo(name = "operation_kind") val operationKind: String,
    @ColumnInfo(name = "before_image_json") val beforeImageJson: String?,
    @ColumnInfo(name = "after_image_json") val afterImageJson: String?,
)

@Entity(tableName = "sync_operation_journal")
data class SyncOperationJournalRecord(
    @androidx.room3.PrimaryKey @ColumnInfo(name = "mutation_id") val mutationId: String,
    @ColumnInfo(name = "codec_version") val codecVersion: Int,
    @ColumnInfo(name = "operation_json") val operationJson: String,
    /** D8 transport metadata; ciphertext is retained separately from operation_json. */
    @ColumnInfo(name = "outbound_sync_space_id") val outboundSyncSpaceId: String? = null,
    @ColumnInfo(name = "outbound_sender_device_id") val outboundSenderDeviceId: String? = null,
    @ColumnInfo(name = "outbound_key_epoch") val outboundKeyEpoch: Long? = null,
    @ColumnInfo(name = "outbound_ciphertext_base64url") val outboundCiphertextBase64Url: String? = null,
    @ColumnInfo(name = "outbound_uploaded", defaultValue = "0") val outboundUploaded: Boolean = false,
)

@Entity(tableName = "replica_causal_state")
data class ReplicaCausalStateRecord(
    @androidx.room3.PrimaryKey @ColumnInfo(name = "state_key") val stateKey: String = LOCAL_STATE_KEY,
    @ColumnInfo(name = "replica_id") val replicaId: String,
    @ColumnInfo(name = "last_counter") val lastCounter: Long,
    @ColumnInfo(name = "observed_context_json") val observedContextJson: String,
    @ColumnInfo(name = "last_hlc_physical_millis") val lastHlcPhysicalMillis: Long,
    @ColumnInfo(name = "last_hlc_logical") val lastHlcLogical: Long,
    @ColumnInfo(name = "last_hlc_replica_id") val lastHlcReplicaId: String,
) {
    companion object { const val LOCAL_STATE_KEY = "LOCAL" }
}

@Entity(tableName = "focus_block_tombstone", indices = [Index("deletion_mutation_id")])
data class FocusBlockTombstoneRecord(
    @androidx.room3.PrimaryKey @ColumnInfo(name = "focus_block_id") val focusBlockId: String,
    @ColumnInfo(name = "deletion_mutation_id") val deletionMutationId: String,
    @ColumnInfo(name = "dvv_json") val dvvJson: String,
    @ColumnInfo(name = "hlc_physical_millis") val hlcPhysicalMillis: Long,
    @ColumnInfo(name = "hlc_logical") val hlcLogical: Long,
    @ColumnInfo(name = "hlc_replica_id") val hlcReplicaId: String,
)
