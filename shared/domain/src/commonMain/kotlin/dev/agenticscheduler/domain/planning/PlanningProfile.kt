package dev.agenticscheduler.domain.planning

import dev.agenticscheduler.domain.id.PlanningProfileId
import kotlin.time.Duration
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.toImmutableList
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone

data class PlanningProfile(
    val id: PlanningProfileId,
    val name: String,
    val configuration: PlanningProfileConfiguration,
) {
    init {
        require(name.isNotBlank()) { "A PlanningProfile name must not be blank." }
    }
}

sealed interface PlanningProfileConfiguration {
    data object Unconfigured : PlanningProfileConfiguration

    class Configured(
        val timeZone: TimeZone,
        weeklyAvailability: ImmutableList<WeeklyAvailabilityWindow>,
        val minimumFocusBlock: Duration,
        val preferredFocusBlock: Duration,
        val maximumFocusBlock: Duration,
        val allDayEventPolicy: AllDayEventPolicy,
    ) : PlanningProfileConfiguration {
        /** Owned canonical snapshot; callers cannot mutate validated Domain state later. */
        val weeklyAvailability: ImmutableList<WeeklyAvailabilityWindow> = weeklyAvailability
            .sortedWith(compareBy<WeeklyAvailabilityWindow>({ it.dayOfWeek.ordinal }, { it.start }, { it.endExclusive }))
            .toImmutableList()

        init {
            require(minimumFocusBlock.isFinite() && preferredFocusBlock.isFinite() && maximumFocusBlock.isFinite()) {
                "Focus block durations must be finite."
            }
            require(minimumFocusBlock > Duration.ZERO && minimumFocusBlock <= preferredFocusBlock && preferredFocusBlock <= maximumFocusBlock) {
                "Focus block durations must satisfy 0 < minimum <= preferred <= maximum."
            }
            this.weeklyAvailability.zipWithNext().forEach { (first, second) ->
                require(first.dayOfWeek != second.dayOfWeek || first.endExclusive <= second.start) {
                    "Availability windows on the same weekday must not overlap."
                }
            }
        }

        override fun equals(other: Any?): Boolean = other is Configured &&
            timeZone == other.timeZone && weeklyAvailability == other.weeklyAvailability &&
            minimumFocusBlock == other.minimumFocusBlock && preferredFocusBlock == other.preferredFocusBlock &&
            maximumFocusBlock == other.maximumFocusBlock && allDayEventPolicy == other.allDayEventPolicy

        override fun hashCode(): Int = listOf(timeZone, weeklyAvailability, minimumFocusBlock, preferredFocusBlock, maximumFocusBlock, allDayEventPolicy).hashCode()
        override fun toString(): String = "Configured(timeZone=$timeZone, weeklyAvailability=$weeklyAvailability, minimumFocusBlock=$minimumFocusBlock, preferredFocusBlock=$preferredFocusBlock, maximumFocusBlock=$maximumFocusBlock, allDayEventPolicy=$allDayEventPolicy)"
    }
}

data class WeeklyAvailabilityWindow(
    val dayOfWeek: DayOfWeek,
    val start: LocalTime,
    val endExclusive: LocalTime,
) {
    init {
        require(start < endExclusive) { "Availability window start must be before its end." }
    }
}

enum class AllDayEventPolicy {
    NON_BLOCKING,
    BLOCK_WHOLE_LOCAL_DAY,
}
