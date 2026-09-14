package dev.agenticscheduler.application.history

import dev.agenticscheduler.domain.event.Event
import dev.agenticscheduler.domain.academic.*
import dev.agenticscheduler.domain.id.*
import dev.agenticscheduler.domain.planning.*
import dev.agenticscheduler.domain.task.*
import dev.agenticscheduler.domain.time.AllDayRange
import dev.agenticscheduler.domain.time.FloatingTimeRange
import dev.agenticscheduler.domain.time.ZonedTimeRange
import dev.agenticscheduler.sync.*
import kotlinx.collections.immutable.toImmutableList
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlin.time.Duration
import kotlin.time.Instant

internal fun Event.toSemanticImage() = EventImage(id.value, title, when (val placement = time) {
    is ZonedTimeRange -> EventTimeImage.Zoned(placement.toSemanticImage())
    is AllDayRange -> EventTimeImage.AllDay(AllDayRangeImage(placement.startDate.toString(), placement.endDateExclusive.toString()))
    is FloatingTimeRange -> EventTimeImage.Floating(FloatingTimeRangeImage(placement.start.toString(), placement.endExclusive.toString()))
}, flexibility.toImage(), pinState.toImage())

internal fun Task.toSemanticImage() = TaskImage(id.value, title, status.toImage(), priority.toImage(), TaskEffortImage(effort.estimated?.toIsoString(), effort.completed.toIsoString(), effort.remaining?.toIsoString()), deadline?.let { TaskDeadlineImage(when (val value = it.deadline) {
    is Deadline.Exact -> DeadlineImage.Exact(value.at.toString(), value.timeZone.id)
    is Deadline.DateOnly -> DeadlineImage.DateOnly(value.date.toString())
}, it.policy.toImage(), it.overflowPolicy.toImage()) })

internal fun FocusBlock.toSemanticImage() = FocusBlockImage(id.value, taskId.value, time.toSemanticImage(), flexibility.toImage(), pinState.toImage())

internal fun PlanningProfile.toSemanticImage() = PlanningProfileImage(id.value, name, when (val value = configuration) {
    PlanningProfileConfiguration.Unconfigured -> PlanningProfileConfigurationImage.Unconfigured
    is PlanningProfileConfiguration.Configured -> PlanningProfileConfigurationImage.Configured(value.timeZone.id, value.weeklyAvailability.map { CanonicalAvailabilityWindowImage(it.dayOfWeek.toImage(), it.start.toString(), it.endExclusive.toString()) }, value.minimumFocusBlock.toIsoString(), value.preferredFocusBlock.toIsoString(), value.maximumFocusBlock.toIsoString(), value.allDayEventPolicy.toImage())
})

internal fun EventImage.toDomain() = Event(EventId(id), title, when (val value = time) {
    is EventTimeImage.Zoned -> value.time.toDomain()
    is EventTimeImage.AllDay -> AllDayRange(LocalDate.parse(value.dates.startDate), LocalDate.parse(value.dates.endDateExclusive))
    is EventTimeImage.Floating -> FloatingTimeRange(LocalDateTime.parse(value.time.start), LocalDateTime.parse(value.time.endExclusive))
}, flexibility.toDomain(), pinState.toDomain())

internal fun TaskImage.toDomain() = Task(TaskId(id), title, status.toDomain(), priority.toDomain(), TaskEffort(effort.estimated?.let(Duration::parseIsoString), Duration.parseIsoString(effort.completed), effort.remaining?.let(Duration::parseIsoString)), deadline?.let { value -> TaskDeadline(when (val deadlineValue = value.deadline) {
    is DeadlineImage.Exact -> Deadline.Exact(Instant.parse(deadlineValue.at), TimeZone.of(deadlineValue.timeZone))
    is DeadlineImage.DateOnly -> Deadline.DateOnly(LocalDate.parse(deadlineValue.date))
}, value.policy.toDomain(), value.overflowPolicy.toDomain()) })

internal fun FocusBlockImage.toDomain() = FocusBlock(FocusBlockId(id), TaskId(taskId), time.toDomain(), flexibility.toDomain(), pinState.toDomain())

internal fun PlanningProfileImage.toDomain() = PlanningProfile(PlanningProfileId(id), name, when (val value = configuration) {
    PlanningProfileConfigurationImage.Unconfigured -> PlanningProfileConfiguration.Unconfigured
    is PlanningProfileConfigurationImage.Configured -> PlanningProfileConfiguration.Configured(TimeZone.of(value.timeZone), value.weeklyAvailability.map { WeeklyAvailabilityWindow(it.dayOfWeek.toDomain(), LocalTime.parse(it.start), LocalTime.parse(it.endExclusive)) }.toImmutableList(), Duration.parseIsoString(value.minimumFocusDuration), Duration.parseIsoString(value.preferredFocusDuration), Duration.parseIsoString(value.maximumFocusDuration), value.allDayEventPolicy.toDomain())
})

private fun ZonedTimeRange.toSemanticImage() = ZonedTimeRangeImage(start.toString(), endExclusive.toString(), timeZone.id)
private fun ZonedTimeRangeImage.toDomain() = ZonedTimeRange(Instant.parse(start), Instant.parse(endExclusive), TimeZone.of(timeZone))
private fun Flexibility.toImage() = when (this) { Flexibility.HARD -> FlexibilityImage.HARD; Flexibility.FLEXIBLE -> FlexibilityImage.FLEXIBLE; Flexibility.SOFT -> FlexibilityImage.SOFT }
private fun FlexibilityImage.toDomain() = when (this) { FlexibilityImage.HARD -> Flexibility.HARD; FlexibilityImage.FLEXIBLE -> Flexibility.FLEXIBLE; FlexibilityImage.SOFT -> Flexibility.SOFT }
private fun PinState.toImage() = when (this) { PinState.PINNED -> PinStateImage.PINNED; PinState.UNPINNED -> PinStateImage.UNPINNED }
private fun PinStateImage.toDomain() = when (this) { PinStateImage.PINNED -> PinState.PINNED; PinStateImage.UNPINNED -> PinState.UNPINNED }
private fun TaskStatus.toImage() = when (this) { TaskStatus.OPEN -> TaskStatusImage.OPEN; TaskStatus.IN_PROGRESS -> TaskStatusImage.IN_PROGRESS; TaskStatus.COMPLETED -> TaskStatusImage.COMPLETED; TaskStatus.CANCELLED -> TaskStatusImage.CANCELLED }
private fun TaskStatusImage.toDomain() = when (this) { TaskStatusImage.OPEN -> TaskStatus.OPEN; TaskStatusImage.IN_PROGRESS -> TaskStatus.IN_PROGRESS; TaskStatusImage.COMPLETED -> TaskStatus.COMPLETED; TaskStatusImage.CANCELLED -> TaskStatus.CANCELLED }
private fun TaskPriority.toImage() = when (this) { TaskPriority.LOW -> TaskPriorityImage.LOW; TaskPriority.NORMAL -> TaskPriorityImage.NORMAL; TaskPriority.HIGH -> TaskPriorityImage.HIGH }
private fun TaskPriorityImage.toDomain() = when (this) { TaskPriorityImage.LOW -> TaskPriority.LOW; TaskPriorityImage.NORMAL -> TaskPriority.NORMAL; TaskPriorityImage.HIGH -> TaskPriority.HIGH }
private fun DeadlinePolicy.toImage() = when (this) { DeadlinePolicy.NORMAL -> DeadlinePolicyImage.NORMAL; DeadlinePolicy.HARD -> DeadlinePolicyImage.HARD }
private fun DeadlinePolicyImage.toDomain() = when (this) { DeadlinePolicyImage.NORMAL -> DeadlinePolicy.NORMAL; DeadlinePolicyImage.HARD -> DeadlinePolicy.HARD }
private fun OverflowPolicy.toImage() = when (this) { OverflowPolicy.NEVER -> OverflowPolicyImage.NEVER; OverflowPolicy.ASK -> OverflowPolicyImage.ASK; OverflowPolicy.ALLOW -> OverflowPolicyImage.ALLOW }
private fun OverflowPolicyImage.toDomain() = when (this) { OverflowPolicyImage.NEVER -> OverflowPolicy.NEVER; OverflowPolicyImage.ASK -> OverflowPolicy.ASK; OverflowPolicyImage.ALLOW -> OverflowPolicy.ALLOW }
private fun AllDayEventPolicy.toImage() = when (this) { AllDayEventPolicy.NON_BLOCKING -> AllDayEventPolicyImage.NON_BLOCKING; AllDayEventPolicy.BLOCK_WHOLE_LOCAL_DAY -> AllDayEventPolicyImage.BLOCK_WHOLE_LOCAL_DAY }
private fun AllDayEventPolicyImage.toDomain() = when (this) { AllDayEventPolicyImage.NON_BLOCKING -> AllDayEventPolicy.NON_BLOCKING; AllDayEventPolicyImage.BLOCK_WHOLE_LOCAL_DAY -> AllDayEventPolicy.BLOCK_WHOLE_LOCAL_DAY }
private fun DayOfWeek.toImage() = when (this) { DayOfWeek.MONDAY -> DayOfWeekImage.MONDAY; DayOfWeek.TUESDAY -> DayOfWeekImage.TUESDAY; DayOfWeek.WEDNESDAY -> DayOfWeekImage.WEDNESDAY; DayOfWeek.THURSDAY -> DayOfWeekImage.THURSDAY; DayOfWeek.FRIDAY -> DayOfWeekImage.FRIDAY; DayOfWeek.SATURDAY -> DayOfWeekImage.SATURDAY; DayOfWeek.SUNDAY -> DayOfWeekImage.SUNDAY }
private fun DayOfWeekImage.toDomain() = when (this) { DayOfWeekImage.MONDAY -> DayOfWeek.MONDAY; DayOfWeekImage.TUESDAY -> DayOfWeek.TUESDAY; DayOfWeekImage.WEDNESDAY -> DayOfWeek.WEDNESDAY; DayOfWeekImage.THURSDAY -> DayOfWeek.THURSDAY; DayOfWeekImage.FRIDAY -> DayOfWeek.FRIDAY; DayOfWeekImage.SATURDAY -> DayOfWeek.SATURDAY; DayOfWeekImage.SUNDAY -> DayOfWeek.SUNDAY }

internal fun WorkLog.toSemanticImage() = WorkLogImage(id.value, taskId.value, time.toSemanticImage())
internal fun WorkLogImage.toDomain() = WorkLog(WorkLogId(id), TaskId(taskId), time.toDomain())

internal fun TaskDependency.toSemanticImage() = TaskDependencyImage(id.value, prerequisiteTaskId.value, dependentTaskId.value)
internal fun TaskDependencyImage.toDomain() = TaskDependency(TaskDependencyId(id), TaskId(prerequisiteTaskId), TaskId(dependentTaskId))

internal fun AcademicYear.toSemanticImage() = AcademicYearImage(id.value, name, startDate.toString(), endDateExclusive.toString())
internal fun AcademicYearImage.toDomain() = AcademicYear(AcademicYearId(id), name, LocalDate.parse(startDate), LocalDate.parse(endDateExclusive))

internal fun Semester.toSemanticImage() = SemesterImage(id.value, academicYearId.value, name, startDate.toString(), endDateExclusive.toString(), timeZone.id, academicWeeks.map { AcademicWeekImage(it.number.value, it.startDate.toString(), it.endDateExclusive.toString()) })
internal fun SemesterImage.toDomain() = Semester(SemesterId(id), AcademicYearId(academicYearId), name, LocalDate.parse(startDate), LocalDate.parse(endDateExclusive), TimeZone.of(timeZone), academicWeeks.map { AcademicWeek(AcademicWeekNumber(it.number), LocalDate.parse(it.startDate), LocalDate.parse(it.endDateExclusive)) }.toImmutableList())

internal fun Course.toSemanticImage() = CourseImage(id.value, semesterId.value, name, code)
internal fun CourseImage.toDomain() = Course(CourseId(id), SemesterId(semesterId), name, code)

internal fun PeriodTemplate.toSemanticImage() = PeriodTemplateImage(id.value, name, periods.map { AcademicPeriodImage(it.number.value, it.start.toString(), it.endExclusive.toString()) })
internal fun PeriodTemplateImage.toDomain() = PeriodTemplate(PeriodTemplateId(id), name, periods.map { AcademicPeriod(AcademicPeriodNumber(it.number), LocalTime.parse(it.start), LocalTime.parse(it.endExclusive)) }.toImmutableList())

internal fun AcademicHoliday.toSemanticImage() = AcademicHolidayImage(id.value, semesterId.value, name, AllDayRangeImage(dates.startDate.toString(), dates.endDateExclusive.toString()), when (teachingEffect) { AcademicHolidayTeachingEffect.NO_EFFECT -> AcademicHolidayTeachingEffectImage.NO_EFFECT; AcademicHolidayTeachingEffect.SUSPEND_TEACHING -> AcademicHolidayTeachingEffectImage.SUSPEND_TEACHING })
internal fun AcademicHolidayImage.toDomain() = AcademicHoliday(AcademicHolidayId(id), SemesterId(semesterId), name, AllDayRange(LocalDate.parse(dates.startDate), LocalDate.parse(dates.endDateExclusive)), when (teachingEffect) { AcademicHolidayTeachingEffectImage.NO_EFFECT -> AcademicHolidayTeachingEffect.NO_EFFECT; AcademicHolidayTeachingEffectImage.SUSPEND_TEACHING -> AcademicHolidayTeachingEffect.SUSPEND_TEACHING })

internal fun CourseScheduleRule.toSemanticImage() = CourseScheduleRuleImage(id.value, courseId.value, dayOfWeek.toImage(), teachingWeeks.weeks.map(AcademicWeekNumber::value), when (val value = time) {
    is CourseTimeSpec.ClockTime -> CourseTimeSpecImage.ClockTime(value.start.toString(), value.endExclusive.toString())
    is CourseTimeSpec.PeriodBased -> CourseTimeSpecImage.PeriodBased(value.periodTemplateId.value, value.startPeriod.value, value.endPeriodInclusive.value)
}, room)
internal fun CourseScheduleRuleImage.toDomain() = CourseScheduleRule(CourseScheduleRuleId(id), CourseId(courseId), dayOfWeek.toDomain(), TeachingWeekSet.of(teachingWeeks.map(::AcademicWeekNumber)), when (val value = time) {
    is CourseTimeSpecImage.ClockTime -> CourseTimeSpec.ClockTime(LocalTime.parse(value.start), LocalTime.parse(value.endExclusive))
    is CourseTimeSpecImage.PeriodBased -> CourseTimeSpec.PeriodBased(PeriodTemplateId(value.periodTemplateId), AcademicPeriodNumber(value.startPeriod), AcademicPeriodNumber(value.endPeriodInclusive))
}, room)

internal fun CourseOccurrenceException.toSemanticImage() = CourseOccurrenceExceptionImage(id.value, CourseOccurrenceKeyImage(occurrenceKey.scheduleRuleId.value, occurrenceKey.academicWeekNumber.value), when (disposition) { CourseOccurrenceDisposition.ACTIVE -> CourseOccurrenceDispositionImage.ACTIVE; CourseOccurrenceDisposition.CANCELLED -> CourseOccurrenceDispositionImage.CANCELLED }, timeOverride?.toSemanticImage(), when (val value = roomOverride) {
    RoomOverride.Unchanged -> RoomOverrideImage.Unchanged
    RoomOverride.Clear -> RoomOverrideImage.Clear
    is RoomOverride.Set -> RoomOverrideImage.Set(value.value)
})
internal fun CourseOccurrenceExceptionImage.toDomain() = CourseOccurrenceException(CourseOccurrenceExceptionId(id), CourseOccurrenceKey(CourseScheduleRuleId(occurrence.scheduleRuleId), AcademicWeekNumber(occurrence.academicWeekNumber)), when (disposition) { CourseOccurrenceDispositionImage.ACTIVE -> CourseOccurrenceDisposition.ACTIVE; CourseOccurrenceDispositionImage.CANCELLED -> CourseOccurrenceDisposition.CANCELLED }, timeOverride?.toDomain(), when (val value = roomOverride) {
    RoomOverrideImage.Unchanged -> RoomOverride.Unchanged
    RoomOverrideImage.Clear -> RoomOverride.Clear
    is RoomOverrideImage.Set -> RoomOverride.Set(value.value)
})

internal fun Exam.toSemanticImage() = ExamImage(id.value, semesterId.value, courseId?.value, title, when (val value = schedule) {
    ExamSchedule.Unscheduled -> ExamScheduleImage.Unscheduled
    is ExamSchedule.DateOnly -> ExamScheduleImage.DateOnly(value.date.toString())
    is ExamSchedule.Exact -> ExamScheduleImage.Exact(value.time.toSemanticImage())
})
internal fun ExamImage.toDomain() = Exam(ExamId(id), SemesterId(semesterId), courseId?.let(::CourseId), title, when (val value = schedule) {
    ExamScheduleImage.Unscheduled -> ExamSchedule.Unscheduled
    is ExamScheduleImage.DateOnly -> ExamSchedule.DateOnly(LocalDate.parse(value.date))
    is ExamScheduleImage.Exact -> ExamSchedule.Exact(value.time.toDomain())
})
