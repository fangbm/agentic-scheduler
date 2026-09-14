package dev.agenticscheduler.application.history

import dev.agenticscheduler.domain.event.Event
import dev.agenticscheduler.domain.id.EventId
import dev.agenticscheduler.domain.id.FocusBlockId
import dev.agenticscheduler.domain.id.PlanningProfileId
import dev.agenticscheduler.domain.id.TaskId
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
private fun Flexibility.toImage() = FlexibilityImage.valueOf(name)
private fun FlexibilityImage.toDomain() = Flexibility.valueOf(name)
private fun PinState.toImage() = PinStateImage.valueOf(name)
private fun PinStateImage.toDomain() = PinState.valueOf(name)
private fun TaskStatus.toImage() = TaskStatusImage.valueOf(name)
private fun TaskStatusImage.toDomain() = TaskStatus.valueOf(name)
private fun TaskPriority.toImage() = TaskPriorityImage.valueOf(name)
private fun TaskPriorityImage.toDomain() = TaskPriority.valueOf(name)
private fun DeadlinePolicy.toImage() = DeadlinePolicyImage.valueOf(name)
private fun DeadlinePolicyImage.toDomain() = DeadlinePolicy.valueOf(name)
private fun OverflowPolicy.toImage() = OverflowPolicyImage.valueOf(name)
private fun OverflowPolicyImage.toDomain() = OverflowPolicy.valueOf(name)
private fun AllDayEventPolicy.toImage() = AllDayEventPolicyImage.valueOf(name)
private fun AllDayEventPolicyImage.toDomain() = AllDayEventPolicy.valueOf(name)
private fun DayOfWeek.toImage() = DayOfWeekImage.valueOf(name)
private fun DayOfWeekImage.toDomain() = DayOfWeek.valueOf(name)
