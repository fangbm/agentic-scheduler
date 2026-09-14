package dev.agenticscheduler.sync

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Durable D8 receive outcome for a whole unsupported MutationId. */
@Serializable
enum class ProtocolQuarantineReason { UNSUPPORTED_PAYLOAD_VERSION, UNSUPPORTED_MUTATION, INVALID_PAYLOAD }

@Serializable
data class ProtocolQuarantine(
    val syncSpaceId: SyncSpaceId,
    val mutationId: String,
    val serverCursor: Long,
    val reason: ProtocolQuarantineReason,
    val detail: String?,
) { init { require(serverCursor >= 0) } }

@Serializable
enum class SyncConflictStatus { OPEN, RESOLVED }

/** SYN-015's structured, durable local conflict state. */
@Serializable
data class SyncConflict(
    val conflictId: String,
    val syncSpaceId: SyncSpaceId,
    val entityRefs: List<SyncConflictEntityRef>,
    val participants: List<SyncConflictParticipant>,
    val provisionalMutationId: MutationId,
    val status: SyncConflictStatus,
    val resolutionMutationId: MutationId? = null,
) {
    init {
        require(conflictId.isNotBlank())
        require(entityRefs.isNotEmpty())
        require(participants.size >= 2)
        require(participants.map(SyncConflictParticipant::mutationId).distinct().size == participants.size)
        require((status == SyncConflictStatus.RESOLVED) == (resolutionMutationId != null))
    }
}

@Serializable
data class SyncConflictEntityRef(val entityKind: EntityKind, val entityId: String, val groups: List<String>) {
    init { require(entityId.isNotBlank()); require(groups.isNotEmpty()) }
}

/** Candidate values are canonical typed-image JSON retained only in trusted local storage for explicit resolution UI. */
@Serializable
data class SyncConflictParticipant(
    val mutationId: MutationId,
    val dvv: DvvSnapshot,
    val candidateValuesJson: String,
) { init { require(candidateValuesJson.isNotBlank()) } }

/** Explicit codec for D8 trusted-local state; this is never a server payload codec. */
object SyncReceiveStateCodec {
    private val json = Json { encodeDefaults = true; classDiscriminator = "type" }
    fun encodeConflict(value: SyncConflict): String = json.encodeToString(SyncConflict.serializer(), value)
    fun decodeConflict(encoded: String): SyncConflict = json.decodeFromString(SyncConflict.serializer(), encoded)
}
