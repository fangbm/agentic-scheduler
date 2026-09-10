package dev.agenticscheduler.domain.academic

import dev.agenticscheduler.domain.id.CourseId
import dev.agenticscheduler.domain.id.CourseScheduleRuleId
import dev.agenticscheduler.domain.id.PeriodTemplateId
import kotlin.collections.Collection
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalTime

class TeachingWeekSet private constructor(
    val weeks: List<AcademicWeekNumber>,
) {
    companion object {
        fun of(weeks: Collection<AcademicWeekNumber>): TeachingWeekSet {
            require(weeks.isNotEmpty()) { "Teaching weeks must not be empty." }
            return TeachingWeekSet(weeks.distinct().sortedBy(AcademicWeekNumber::value))
        }
    }

    override fun equals(other: Any?): Boolean = other is TeachingWeekSet && weeks == other.weeks

    override fun hashCode(): Int = weeks.hashCode()

    override fun toString(): String = "TeachingWeekSet(weeks=$weeks)"
}

sealed interface CourseTimeSpec {
    data class ClockTime(
        val start: LocalTime,
        val endExclusive: LocalTime,
    ) : CourseTimeSpec {
        init {
            require(start < endExclusive) { "Clock time must have a positive local-time range." }
        }
    }

    data class PeriodBased(
        val periodTemplateId: PeriodTemplateId,
        val startPeriod: AcademicPeriodNumber,
        val endPeriodInclusive: AcademicPeriodNumber,
    ) : CourseTimeSpec {
        init {
            require(startPeriod.value <= endPeriodInclusive.value) {
                "The start period must not be after the end period."
            }
        }
    }
}

data class CourseScheduleRule(
    val id: CourseScheduleRuleId,
    val courseId: CourseId,
    val dayOfWeek: DayOfWeek,
    val teachingWeeks: TeachingWeekSet,
    val time: CourseTimeSpec,
    val room: String?,
) {
    init {
        require(room == null || room.isNotBlank()) { "A room must be null or non-blank." }
    }
}

data class CourseOccurrenceKey(
    val scheduleRuleId: CourseScheduleRuleId,
    val academicWeekNumber: AcademicWeekNumber,
)

sealed interface RoomOverride {
    data object Unchanged : RoomOverride

    data class Set(val value: String) : RoomOverride {
        init {
            require(value.isNotBlank()) { "An overridden room must not be blank." }
        }
    }

    data object Clear : RoomOverride
}

enum class CourseOccurrenceDisposition {
    ACTIVE,
    CANCELLED,
}
