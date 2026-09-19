package dev.agenticscheduler.planner

import kotlin.time.Instant

/**
 * R1 pure half-open interval primitive. Every planner time computation uses this
 * type so that intersection/subtraction semantics are defined exactly once.
 * No system clock, no timezone reads, no mutation of caller collections.
 */
internal data class Interval(val start: Instant, val endExclusive: Instant) {
    init {
        require(start < endExclusive) { "An interval must have a positive duration." }
    }

    fun contains(other: Interval): Boolean = start <= other.start && other.endExclusive <= endExclusive
}

internal fun intersectIntervals(first: Interval, second: Interval): Interval? = when {
    first.start < second.endExclusive && second.start < first.endExclusive ->
        Interval(maxOf(first.start, second.start), minOf(first.endExclusive, second.endExclusive))
    else -> null
}

internal fun clipInterval(interval: Interval, bounds: Interval): Interval? =
    intersectIntervals(interval, bounds)

/** Subtracts every cut from source. Output is canonically sorted and never coalesced. */
internal fun subtractIntervals(source: Collection<Interval>, cuts: Collection<Interval>): List<Interval> {
    var current = source.sortedBy { it.start }
    cuts.sortedBy { it.start }.forEach { cut ->
        current = current.flatMap { range ->
            when {
                cut.endExclusive <= range.start || cut.start >= range.endExclusive -> listOf(range)
                cut.start <= range.start && cut.endExclusive >= range.endExclusive -> emptyList()
                cut.start <= range.start -> listOf(Interval(cut.endExclusive, range.endExclusive))
                cut.endExclusive >= range.endExclusive -> listOf(Interval(range.start, cut.start))
                else -> listOf(Interval(range.start, cut.start), Interval(cut.endExclusive, range.endExclusive))
            }
        }
    }
    return current.sortedBy { it.start }
}

/**
 * Unions touching or overlapping intervals into one canonical sorted list.
 * Availability is a set of time, so adjacent windows form one legal region.
 */
internal fun coalesceIntervals(intervals: Collection<Interval>): List<Interval> {
    val sorted = intervals.sortedBy { it.start }
    val result = mutableListOf<Interval>()
    sorted.forEach { interval ->
        val last = result.lastOrNull()
        if (last != null && interval.start <= last.endExclusive) {
            if (interval.endExclusive > last.endExclusive) {
                result[result.size - 1] = Interval(last.start, interval.endExclusive)
            }
        } else {
            result += interval
        }
    }
    return result
}
