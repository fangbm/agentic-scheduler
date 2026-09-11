package dev.agenticscheduler.domain.academic

import dev.agenticscheduler.domain.id.AcademicYearId
import dev.agenticscheduler.domain.id.SemesterId
import kotlin.time.Instant
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.plus
import kotlinx.datetime.toLocalDateTime

data class AcademicYear(
    val id: AcademicYearId,
    val name: String,
    val startDate: LocalDate,
    val endDateExclusive: LocalDate,
) {
    init {
        require(name.isNotBlank()) { "An academic year name must not be blank." }
        require(startDate < endDateExclusive) { "An academic year must have a positive date range." }
    }
}

@JvmInline
value class AcademicWeekNumber(val value: Int) {
    init {
        require(value >= 1) { "An academic week number must be positive." }
    }
}

data class AcademicWeek(
    val number: AcademicWeekNumber,
    val startDate: LocalDate,
    val endDateExclusive: LocalDate,
) {
    init {
        require(endDateExclusive == startDate.plus(7, DateTimeUnit.DAY)) {
            "An academic week must span exactly seven calendar days."
        }
    }
}

data class Semester(
    val id: SemesterId,
    val academicYearId: AcademicYearId,
    val name: String,
    val startDate: LocalDate,
    val endDateExclusive: LocalDate,
    val timeZone: TimeZone,
    val academicWeeks: kotlinx.collections.immutable.ImmutableList<AcademicWeek>,
) {
    init {
        require(name.isNotBlank()) { "A semester name must not be blank." }
        require(startDate < endDateExclusive) { "A semester must have a positive date range." }
        require(academicWeeks.isNotEmpty()) { "A semester must define academic weeks." }
        require(academicWeeks.first().number.value == 1) { "Academic weeks must begin at week 1." }

        academicWeeks.forEachIndexed { index, week ->
            require(week.number.value == index + 1) {
                "Academic week numbers must be consecutive and already sorted."
            }
            require(week.startDate >= startDate && week.endDateExclusive <= endDateExclusive) {
                "Every academic week must be fully contained in its semester."
            }
            if (index > 0) {
                require(academicWeeks[index - 1].endDateExclusive <= week.startDate) {
                    "Academic week ranges must not overlap."
                }
            }
        }
    }

}

enum class SemesterAcademicYearValidationResult {
    VALID,
    ACADEMIC_YEAR_ID_MISMATCH,
    OUTSIDE_ACADEMIC_YEAR,
}

fun validateSemesterAgainstAcademicYear(
    academicYear: AcademicYear,
    semester: Semester,
): SemesterAcademicYearValidationResult = when {
    semester.academicYearId != academicYear.id -> SemesterAcademicYearValidationResult.ACADEMIC_YEAR_ID_MISMATCH
    semester.startDate < academicYear.startDate || semester.endDateExclusive > academicYear.endDateExclusive ->
        SemesterAcademicYearValidationResult.OUTSIDE_ACADEMIC_YEAR
    else -> SemesterAcademicYearValidationResult.VALID
}

internal fun Semester.containsLocalDate(date: LocalDate): Boolean =
    date >= startDate && date < endDateExclusive

internal fun Semester.containsExactRange(start: Instant, endExclusive: Instant): Boolean {
    val localStart = start.toLocalDateTime(timeZone)
    val localEnd = endExclusive.toLocalDateTime(timeZone)
    return localStart >= LocalDateTime(startDate, LocalTime(0, 0)) &&
        localEnd <= LocalDateTime(endDateExclusive, LocalTime(0, 0))
}
