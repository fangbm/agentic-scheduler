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
enum class SyncConflictStatus { OPEN, RESOLVED, SUPERSEDED }

/** D8-A03: semantic merge collisions and immutable-identity violations are distinct user-resolvable states. */
@Serializable
enum class SyncConflictKind { SEMANTIC, INTEGRITY }

/** D8-A04's context-only common ancestry; it is never a dotted operation or an HLC-derived winner. */
@Serializable
data class CausalContextSnapshot(val components: List<VersionComponent>) {
    init {
        require(components == components.sortedBy(VersionComponent::replicaId)) { "Causal context must be ReplicaId canonical." }
        require(components.map(VersionComponent::replicaId).distinct().size == components.size) { "Causal context replicas must be unique." }
    }
}

/** SYN-015's structured, durable local conflict state. */
@Serializable
data class SyncConflict(
    val conflictId: String,
    val syncSpaceId: SyncSpaceId,
    val entityRefs: List<SyncConflictEntityRef>,
    val participants: List<SyncConflictParticipant>,
    val provisionalMutationId: MutationId,
    val kind: SyncConflictKind,
    val commonCausalContext: CausalContextSnapshot?,
    val status: SyncConflictStatus,
    val resolutionMutationId: MutationId? = null,
    /** D8-01c component expansion replaces an older OPEN participant set atomically. */
    val supersededByConflictId: String? = null,
) {
    init {
        require(conflictId.isNotBlank())
        require(entityRefs.isNotEmpty())
        require(participants.size >= 2)
        require(participants.map(SyncConflictParticipant::mutationId).distinct().size == participants.size)
        require(provisionalMutationId == participants.minBy { it.mutationId.value }.mutationId) { "Provisional MutationId must be the lexicographically smallest participant." }
        require(commonCausalContext == commonCausalContextOf(participants)) { "Known participant DVVs require their computed common causal context." }
        when (status) {
            SyncConflictStatus.OPEN -> require(resolutionMutationId == null && supersededByConflictId == null)
            SyncConflictStatus.RESOLVED -> require(resolutionMutationId != null && supersededByConflictId == null)
            SyncConflictStatus.SUPERSEDED -> require(resolutionMutationId == null && !supersededByConflictId.isNullOrBlank() && supersededByConflictId != conflictId)
        }
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

/** Component-wise minimum of complete participant DVVs, with absent components omitted. */
fun commonCausalContextOf(participants: List<SyncConflictParticipant>): CausalContextSnapshot {
    require(participants.isNotEmpty())
    val vectors = participants.map { participant -> participant.dvv.toDottedVersionVector().observedContext() }
    val sharedReplicas = vectors.map(Map<ReplicaId, Long>::keys).reduce(Set<ReplicaId>::intersect)
    return CausalContextSnapshot(sharedReplicas.sortedBy(ReplicaId::value).map { replica ->
        VersionComponent(replica.value, vectors.minOf { vector -> requireNotNull(vector[replica]) })
    })
}

/** Explicit codec for D8 trusted-local state; this is never a server payload codec. */
object SyncReceiveStateCodec {
    private val json = Json { encodeDefaults = true; classDiscriminator = "type" }
    fun encodeConflict(value: SyncConflict): String = json.encodeToString(SyncConflict.serializer(), value)
    fun decodeConflict(encoded: String): SyncConflict = json.decodeFromString(SyncConflict.serializer(), encoded)
}
