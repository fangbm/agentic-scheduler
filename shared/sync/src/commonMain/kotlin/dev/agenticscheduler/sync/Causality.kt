package dev.agenticscheduler.sync

import kotlinx.collections.immutable.ImmutableMap
import kotlinx.collections.immutable.toImmutableMap
import kotlinx.serialization.Serializable

private val uuidV7Pattern = Regex("^[0-9a-f]{8}-[0-9a-f]{4}-7[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$")

/** Identity of one installed logical replica; restoring without causal state creates a new one. */
@JvmInline
value class ReplicaId(val value: String) {
    init { require(uuidV7Pattern.matches(value)) { "ReplicaId must be lowercase RFC UUIDv7 text." } }
}

/** One immutable, application-owned logical transaction. */
@JvmInline
@Serializable
value class MutationId(val value: String) {
    init { require(uuidV7Pattern.matches(value)) { "MutationId must be lowercase RFC UUIDv7 text." } }
}

data class Dot(val replicaId: ReplicaId, val counter: Long) {
    init { require(counter >= 0) { "A causal counter must be non-negative." } }
}

enum class CausalRelation { BEFORE, AFTER, EQUAL, CONCURRENT }

/** D7 does not choose a concurrent winner; it only classifies the causal fact for D8's semantic merge. */
enum class FocusBlockPutAgainstTombstone { APPLY, SUPPRESS_CAUSALLY_OLDER, CONCURRENT_CONFLICT }

/**
 * A dotted version vector carries its observed context plus the operation's own
 * dot. The derived version vector is used only for causal comparison; HLC is
 * intentionally excluded from this decision.
 */
class DottedVersionVector(
    context: Map<ReplicaId, Long>,
    val dot: Dot,
) {
    val context: ImmutableMap<ReplicaId, Long> = context
        .also { values -> require(values.values.all { it >= 0 }) { "Causal context counters must be non-negative." } }
        .toSortedMap(compareBy(ReplicaId::value))
        .toImmutableMap()

    fun relationTo(other: DottedVersionVector): CausalRelation {
        val left = completeVector()
        val right = other.completeVector()
        val replicas = (left.keys + right.keys).sortedBy(ReplicaId::value)
        var leftGreater = false
        var rightGreater = false
        replicas.forEach { replica ->
            val comparison = (left[replica] ?: -1L).compareTo(right[replica] ?: -1L)
            if (comparison > 0) leftGreater = true
            if (comparison < 0) rightGreater = true
        }
        return when {
            !leftGreater && !rightGreater -> CausalRelation.EQUAL
            leftGreater && !rightGreater -> CausalRelation.AFTER
            !leftGreater && rightGreater -> CausalRelation.BEFORE
            else -> CausalRelation.CONCURRENT
        }
    }

    /** Context observed by a future local operation includes this operation's dot. */
    fun observedContext(): ImmutableMap<ReplicaId, Long> = completeVector().toImmutableMap()

    private fun completeVector(): Map<ReplicaId, Long> = buildMap {
        putAll(context)
        put(dot.replicaId, maxOf(get(dot.replicaId) ?: -1L, dot.counter))
    }

    override fun equals(other: Any?): Boolean =
        other is DottedVersionVector && context == other.context && dot == other.dot

    override fun hashCode(): Int = 31 * context.hashCode() + dot.hashCode()

    override fun toString(): String = "DottedVersionVector(context=$context, dot=$dot)"
}

data class HlcTimestamp(
    val physicalMillis: Long,
    val logical: Long,
    val replicaId: ReplicaId,
) : Comparable<HlcTimestamp> {
    init { require(logical >= 0) { "HLC logical counter must be non-negative." } }

    override fun compareTo(other: HlcTimestamp): Int = compareValuesBy(this, other, HlcTimestamp::physicalMillis, HlcTimestamp::logical, { it.replicaId.value })
}

fun DvvSnapshot.toDottedVersionVector(): DottedVersionVector = DottedVersionVector(
    context = context.associate { ReplicaId(it.replicaId) to it.counter },
    dot = Dot(ReplicaId(dot.replicaId), dot.counter),
)

/** HST-003: only a causally later Put may follow a FocusBlock tombstone; concurrent input stays unresolved. */
object FocusBlockTombstoneCausality {
    fun decide(put: DvvSnapshot, tombstone: DvvSnapshot): FocusBlockPutAgainstTombstone = when (put.toDottedVersionVector().relationTo(tombstone.toDottedVersionVector())) {
        CausalRelation.BEFORE, CausalRelation.EQUAL -> FocusBlockPutAgainstTombstone.SUPPRESS_CAUSALLY_OLDER
        CausalRelation.CONCURRENT -> FocusBlockPutAgainstTombstone.CONCURRENT_CONFLICT
        CausalRelation.AFTER -> FocusBlockPutAgainstTombstone.APPLY
    }
}

/** Pure HST-007 tick functions; persistence and wall-clock acquisition stay outside :shared:sync. */
object HybridLogicalClock {
    fun tickLocal(last: HlcTimestamp?, wallNowMillis: Long, replicaId: ReplicaId): HlcTimestamp {
        val physical = maxOf(last?.physicalMillis ?: Long.MIN_VALUE, wallNowMillis)
        val logical = if (last == null || physical > last.physicalMillis) 0 else checkedIncrement(last.logical)
        return HlcTimestamp(physical, logical, replicaId)
    }

    fun tickReceive(last: HlcTimestamp?, remote: HlcTimestamp, wallNowMillis: Long, replicaId: ReplicaId): HlcTimestamp {
        val lastPhysical = last?.physicalMillis ?: Long.MIN_VALUE
        val physical = maxOf(lastPhysical, remote.physicalMillis, wallNowMillis)
        val logical = when {
            last != null && physical == last.physicalMillis && physical == remote.physicalMillis -> checkedIncrement(maxOf(last.logical, remote.logical))
            last != null && physical == last.physicalMillis -> checkedIncrement(last.logical)
            physical == remote.physicalMillis -> checkedIncrement(remote.logical)
            else -> 0
        }
        return HlcTimestamp(physical, logical, replicaId)
    }

    private fun checkedIncrement(value: Long): Long = check(value < Long.MAX_VALUE) { "HLC logical counter overflow." }.let { value + 1 }
}
