package dev.agenticscheduler.sync

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Canonical scalar range images shared by all typed source-fact images. */
@Serializable data class ZonedTimeRangeImage(val start: String, val endExclusive: String, val timeZone: String)
@Serializable data class AllDayRangeImage(val startDate: String, val endDateExclusive: String)
@Serializable data class FloatingTimeRangeImage(val start: String, val endExclusive: String)

@Serializable
sealed interface EventTimeImage {
    @Serializable @SerialName("ZONED") data class Zoned(val time: ZonedTimeRangeImage) : EventTimeImage
    @Serializable @SerialName("ALL_DAY") data class AllDay(val dates: AllDayRangeImage) : EventTimeImage
    @Serializable @SerialName("FLOATING") data class Floating(val time: FloatingTimeRangeImage) : EventTimeImage
}

@Serializable enum class FlexibilityImage { HARD, FLEXIBLE, SOFT }
@Serializable enum class PinStateImage { PINNED, UNPINNED }
@Serializable enum class TaskStatusImage { OPEN, IN_PROGRESS, COMPLETED, CANCELLED }
@Serializable enum class TaskPriorityImage { LOW, NORMAL, HIGH }
@Serializable enum class DeadlinePolicyImage { NORMAL, HARD }
@Serializable enum class OverflowPolicyImage { NEVER, ASK, ALLOW }
@Serializable enum class AllDayEventPolicyImage { NON_BLOCKING, BLOCK_WHOLE_LOCAL_DAY }
@Serializable enum class DayOfWeekImage { MONDAY, TUESDAY, WEDNESDAY, THURSDAY, FRIDAY, SATURDAY, SUNDAY }

@Serializable
sealed interface DeadlineImage {
    @Serializable @SerialName("EXACT") data class Exact(val at: String, val timeZone: String) : DeadlineImage
    @Serializable @SerialName("DATE_ONLY") data class DateOnly(val date: String) : DeadlineImage
}

@Serializable data class TaskEffortImage(val estimated: String?, val completed: String, val remaining: String?)
@Serializable data class TaskDeadlineImage(val deadline: DeadlineImage, val policy: DeadlinePolicyImage, val overflowPolicy: OverflowPolicyImage)
@Serializable data class CanonicalAvailabilityWindowImage(val dayOfWeek: DayOfWeekImage, val start: String, val endExclusive: String)

@Serializable
sealed interface PlanningProfileConfigurationImage {
    @Serializable @SerialName("UNCONFIGURED") data object Unconfigured : PlanningProfileConfigurationImage
    @Serializable @SerialName("CONFIGURED") data class Configured(
        val timeZone: String,
        val weeklyAvailability: List<CanonicalAvailabilityWindowImage>,
        val minimumFocusDuration: String,
        val preferredFocusDuration: String,
        val maximumFocusDuration: String,
        val allDayEventPolicy: AllDayEventPolicyImage,
    ) : PlanningProfileConfigurationImage {
        init {
            require(weeklyAvailability == weeklyAvailability.sortedWith(compareBy<CanonicalAvailabilityWindowImage>({ it.dayOfWeek.ordinal }, { it.start }, { it.endExclusive }))) {
                "Planning profile availability must be canonical."
            }
        }
    }
}

@Serializable data class WorkLogImage(val id: String, val taskId: String, val time: ZonedTimeRangeImage)
@Serializable data class TaskDependencyImage(val id: String, val prerequisiteTaskId: String, val dependentTaskId: String)
@Serializable data class AcademicYearImage(val id: String, val name: String, val startDate: String, val endDateExclusive: String)
@Serializable data class AcademicWeekImage(val number: Int, val startDate: String, val endDateExclusive: String)

@Serializable
data class SemesterImage(
    val id: String,
    val academicYearId: String,
    val name: String,
    val startDate: String,
    val endDateExclusive: String,
    val timeZone: String,
    val academicWeeks: List<AcademicWeekImage>,
) {
    init { require(academicWeeks == academicWeeks.sortedBy(AcademicWeekImage::number)) { "Academic weeks must be canonical." } }
}

@Serializable data class CourseImage(val id: String, val semesterId: String, val name: String, val code: String?)
@Serializable data class AcademicPeriodImage(val number: Int, val start: String, val endExclusive: String)

@Serializable
data class PeriodTemplateImage(val id: String, val name: String, val periods: List<AcademicPeriodImage>) {
    init { require(periods == periods.sortedBy(AcademicPeriodImage::number)) { "Academic periods must be canonical." } }
}

@Serializable enum class AcademicHolidayTeachingEffectImage { NO_EFFECT, SUSPEND_TEACHING }
@Serializable data class AcademicHolidayImage(val id: String, val semesterId: String, val name: String, val dates: AllDayRangeImage, val teachingEffect: AcademicHolidayTeachingEffectImage)

@Serializable
sealed interface CourseTimeSpecImage {
    @Serializable @SerialName("CLOCK_TIME") data class ClockTime(val start: String, val endExclusive: String) : CourseTimeSpecImage
    @Serializable @SerialName("PERIOD_BASED") data class PeriodBased(val periodTemplateId: String, val startPeriod: Int, val endPeriodInclusive: Int) : CourseTimeSpecImage
}

@Serializable
data class CourseScheduleRuleImage(
    val id: String,
    val courseId: String,
    val dayOfWeek: DayOfWeekImage,
    val teachingWeeks: List<Int>,
    val time: CourseTimeSpecImage,
    val room: String?,
) {
    init { require(teachingWeeks == teachingWeeks.distinct().sorted()) { "Teaching weeks must be canonical." } }
}

@Serializable data class CourseOccurrenceKeyImage(val scheduleRuleId: String, val academicWeekNumber: Int)
@Serializable enum class CourseOccurrenceDispositionImage { ACTIVE, CANCELLED }

@Serializable
sealed interface RoomOverrideImage {
    @Serializable @SerialName("UNCHANGED") data object Unchanged : RoomOverrideImage
    @Serializable @SerialName("CLEAR") data object Clear : RoomOverrideImage
    @Serializable @SerialName("SET") data class Set(val value: String) : RoomOverrideImage
}

@Serializable
data class CourseOccurrenceExceptionImage(
    val id: String,
    val occurrence: CourseOccurrenceKeyImage,
    val disposition: CourseOccurrenceDispositionImage,
    val timeOverride: ZonedTimeRangeImage?,
    val roomOverride: RoomOverrideImage,
)

@Serializable
sealed interface ExamScheduleImage {
    @Serializable @SerialName("UNSCHEDULED") data object Unscheduled : ExamScheduleImage
    @Serializable @SerialName("DATE_ONLY") data class DateOnly(val date: String) : ExamScheduleImage
    @Serializable @SerialName("EXACT") data class Exact(val time: ZonedTimeRangeImage) : ExamScheduleImage
}

@Serializable data class ExamImage(val id: String, val semesterId: String, val courseId: String?, val title: String, val schedule: ExamScheduleImage)
