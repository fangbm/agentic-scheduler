package dev.agenticscheduler.planner

import dev.agenticscheduler.domain.planning.PlanningProfileConfiguration
import dev.agenticscheduler.domain.task.Task
import kotlin.math.abs
import kotlin.time.Duration
import kotlin.time.Instant
import kotlinx.datetime.TimeZone

/**
 * R4 finite structural candidate generation (D6R-007). Candidate starts are
 * drawn only from structural critical points inside a legal free interval;
 * durations enumerate every legal PLN-013 value. No epsilon, no minute grid,
 * no random sampling. Illegal or zero-length ranges are never produced.
 */
internal object CandidateGeneration {

    /**
     * PLN-013 chunk duration contract, ranked exactly as frozen:
     * 1. leaves remainder 0 or >= minimum
     * 2. absolute distance from preferredFocusBlock
     * 3. longer duration
     */
    fun durationCandidates(
        remaining: Duration,
        capacity: Duration,
        config: PlanningProfileConfiguration.Configured,
    ): List<Duration> {
        val upper = minOf(remaining, capacity, config.maximumFocusBlock)
        if (remaining < config.minimumFocusBlock) {
            return if (upper == remaining) listOf(remaining) else emptyList()
        }
        if (upper < config.minimumFocusBlock) return emptyList()
        return listOf(
            upper,
            minOf(config.preferredFocusBlock, upper),
            remaining,
            remaining - config.minimumFocusBlock,
            config.minimumFocusBlock,
        )
            .distinct()
            .filter { it >= config.minimumFocusBlock && it <= upper }
            .filter { remaining - it == Duration.ZERO || remaining - it >= config.minimumFocusBlock }
            .sortedWith(
                compareBy<Duration>(
                    { if (remaining - it == Duration.ZERO || remaining - it >= config.minimumFocusBlock) 0 else 1 },
                    { abs((it - config.preferredFocusBlock).inWholeMilliseconds) },
                    { -it.inWholeMilliseconds },
                ),
            )
    }

    /**
     * Structural start points for one free interval: interval start, the
     * dependency boundary, the deadline boundary, and boundaries of placements
     * already accepted inside this task's tentative delta. The clamped original
     * start is duration-dependent and therefore enumerated per duration via
     * [clampedOriginalStarts]. Equivalent instants are deduplicated.
     */
    fun structuralStarts(inputs: StructureInputs): List<Instant> {
        val candidates = mutableListOf(inputs.interval.start, inputs.after)
        inputs.cutoff?.let(candidates::add)
        candidates += inputs.tentativeBoundaries
        return candidates
            .filter { it >= inputs.interval.start && it < inputs.interval.endExclusive && it >= inputs.after }
            .distinct()
            .sorted()
    }

    /**
     * PLN-013 durations for one structural start, computed from that start's own
     * segment capacity (U = min(R, C, max) changes as the start moves later).
     * A fixed duration (FLEXIBLE move) bypasses enumeration.
     */
    fun durationsForSegment(
        demand: Duration,
        segmentCapacity: Duration,
        fixedDuration: Duration?,
        config: PlanningProfileConfiguration.Configured,
    ): List<Duration> = fixedDuration?.let { listOf(it) }
        ?: durationCandidates(demand, segmentCapacity, config)

    /**
     * Clamped original starts for one placement duration: the original start
     * clamped into the free interval so the range still fits, and — for
     * deadline-bound work — clamped to the latest start that still finishes by
     * the cutoff (D6R-007).
     */
    data class StructureInputs(
        val interval: Interval,
        val after: Instant,
        val cutoff: Instant?,
        val originalStart: Instant?,
        val tentativeBoundaries: List<Instant>,
    )

    fun clampedOriginalStarts(
        interval: Interval,
        cutoff: Instant?,
        originalStart: Instant?,
        duration: Duration,
    ): List<Instant> {
        val original = originalStart ?: return emptyList()
        val latestFit = interval.endExclusive - duration
        if (latestFit <= interval.start) return emptyList()
        val starts = mutableListOf(original.coerceIn(interval.start, latestFit))
        cutoff?.let { deadline ->
            val latestLegal = deadline - duration
            if (latestLegal >= interval.start && latestLegal <= latestFit) {
                starts += original.coerceIn(interval.start, latestLegal)
            }
        }
        return starts.filter { it >= interval.start && it < interval.endExclusive }.distinct()
    }
}

/**
 * R5 PLN-015 candidate comparison (D6R-008). Each field has explicit stable
 * semantics; the comparison order itself is frozen and never re-weighted.
 */
internal data class PlacementCandidate(
    val range: Interval,
    val taskId: dev.agenticscheduler.domain.id.TaskId,
    /** Existing reservation replaced by this candidate, when any. */
    val sourceReservation: Reservation?,
    /** Replacement reservations required by this placement (displacements), in canonical order. */
    val displacements: List<Displacement>,
    val overflowLegalityRank: Int,
    val overflowLatenessMillis: Long,
    /** 0 = exact original placement, 1 = no displacement required, 2 = displacement required. */
    val preserveRank: Int,
    val movementMillis: Long,
    val contextSwitchDelta: Int,
    val durationRank: Int,
) {
    fun identityText(): String = sourceReservation?.identityText()
        ?: displacements.firstOrNull()?.displaced?.identityText()
        ?: "proposal@${range.start}"
}

/** One displaced lower-authority reservation and its proven in-transaction replacement (null = authorized SOFT removal). */
internal data class Displacement(
    val displaced: Reservation,
    val replacement: Reservation?,
)

internal data class DecisionTrace(
    val winner: PlacementCandidate,
    val runnerUp: PlacementCandidate?,
    val firstDifferingCriterion: PlacementCriterion?,
    /**
     * Explanation derived from the actual comparisons: every criterion at which
     * the winner beat at least one alternative after all earlier criteria tied,
     * in frozen PLN-015 order. CANONICAL_IDENTITY always closes the list.
     */
    val decisiveCriteria: List<PlacementCriterion>,
) {
    fun criteriaForWinner(): List<PlacementCriterion> = decisiveCriteria
}

internal object CandidateComparison {

    private fun criterionOf(index: Int): PlacementCriterion = when (index) {
        0 -> PlacementCriterion.DEADLINE_LEGALITY
        1 -> PlacementCriterion.LATENESS
        2 -> PlacementCriterion.PRESERVED_EXISTING_PLACEMENT
        3 -> PlacementCriterion.MINIMAL_MOVEMENT
        4 -> PlacementCriterion.MINIMAL_CONTEXT_SWITCHES
        5 -> PlacementCriterion.CHUNK_DURATION
        6 -> PlacementCriterion.EARLIER_START
        7 -> PlacementCriterion.EARLIER_END
        else -> PlacementCriterion.CANONICAL_IDENTITY
    }

    private val keys: List<(PlacementCandidate) -> Comparable<*>> = listOf(
        { it.overflowLegalityRank },
        { it.overflowLatenessMillis },
        { it.preserveRank },
        { it.movementMillis },
        { it.contextSwitchDelta },
        { it.durationRank },
        { it.range.start },
        { it.range.endExclusive },
        { it.identityText() },
    )

    private fun firstDiffering(first: PlacementCandidate, second: PlacementCandidate): PlacementCriterion {
        val index = keys.indexOfFirst { key -> key(first) != key(second) }
        return if (index >= 0) criterionOf(index) else PlacementCriterion.CANONICAL_IDENTITY
    }

    /** Exact PLN-015 lexicographic order (D6R-008). */
    fun compare(first: PlacementCandidate, second: PlacementCandidate): Int {
        compareValuesBy(first, second, { it.overflowLegalityRank }, { it.overflowLatenessMillis }, { it.preserveRank })
            .let { if (it != 0) return it }
        compareValuesBy(first, second, { it.movementMillis }, { it.contextSwitchDelta }, { it.durationRank })
            .let { if (it != 0) return it }
        compareValuesBy(first, second, { it.range.start }, { it.range.endExclusive }, { it.identityText() })
            .let { if (it != 0) return it }
        return 0
    }

    fun decide(candidates: List<PlacementCandidate>): DecisionTrace? {
        if (candidates.isEmpty()) return null
        val ordered = candidates.sortedWith(::compare)
        val winner = ordered.first()
        val runnerUp = ordered.getOrNull(1)
        val decisive = ordered.drop(1)
            .map { firstDiffering(winner, it) }
            .distinct()
            .sortedBy { it.ordinal }
            .toMutableList()
        if (decisive.isEmpty() || decisive.last() != PlacementCriterion.CANONICAL_IDENTITY) {
            decisive += PlacementCriterion.CANONICAL_IDENTITY
        }
        return DecisionTrace(winner, runnerUp, runnerUp?.let { firstDiffering(winner, it) }, decisive.toList())
    }
}

/**
 * Shared legality filters for candidates and relocations (PLN-010 / PLN-012 /
 * PLN-009). Pure functions over the task and the tentative interval view.
 */
internal object PlacementLegality {

    /** PLN-010: HARD never overflows; NORMAL overflows only when authorized for this run. */
    fun isDeadlineLegal(cutoff: Instant?, overflowAuthorized: Boolean, range: Interval): Boolean = when {
        cutoff == null -> true
        range.endExclusive <= cutoff -> true
        else -> overflowAuthorized
    }
}
