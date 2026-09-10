package dev.agenticscheduler.domain.time

import kotlin.time.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone

sealed interface TimePlacement

data class ZonedTimeRange(
    val start: Instant,
    val endExclusive: Instant,
    val timeZone: TimeZone,
) : TimePlacement {
    init {
        require(start < endExclusive) { "A zoned time range must have a positive duration." }
    }

    fun overlaps(other: ZonedTimeRange): Boolean =
        start < other.endExclusive && other.start < endExclusive
}

data class AllDayRange(
    val startDate: LocalDate,
    val endDateExclusive: LocalDate,
) : TimePlacement {
    init {
        require(startDate < endDateExclusive) { "An all-day range must have a positive duration." }
    }

    fun overlaps(other: AllDayRange): Boolean =
        startDate < other.endDateExclusive && other.startDate < endDateExclusive
}

data class FloatingTimeRange(
    val start: LocalDateTime,
    val endExclusive: LocalDateTime,
) : TimePlacement {
    init {
        require(start < endExclusive) { "A floating time range must have a positive duration." }
    }

    fun overlaps(other: FloatingTimeRange): Boolean =
        start < other.endExclusive && other.start < endExclusive
}
