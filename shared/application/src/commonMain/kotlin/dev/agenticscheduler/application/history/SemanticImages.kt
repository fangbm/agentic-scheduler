package dev.agenticscheduler.application.history

import dev.agenticscheduler.domain.event.Event
import dev.agenticscheduler.domain.planning.PlanningProfile
import dev.agenticscheduler.domain.planning.PlanningProfileConfiguration
import dev.agenticscheduler.domain.task.FocusBlock
import dev.agenticscheduler.domain.task.Task
import dev.agenticscheduler.domain.time.AllDayRange
import dev.agenticscheduler.domain.time.FloatingTimeRange
import dev.agenticscheduler.domain.time.ZonedTimeRange
import dev.agenticscheduler.domain.id.EventId
import dev.agenticscheduler.domain.id.FocusBlockId
import dev.agenticscheduler.domain.id.PlanningProfileId
import dev.agenticscheduler.domain.id.TaskId
import dev.agenticscheduler.domain.planning.AllDayEventPolicy
import dev.agenticscheduler.domain.planning.Deadline
import dev.agenticscheduler.domain.planning.DeadlinePolicy
import dev.agenticscheduler.domain.planning.Flexibility
import dev.agenticscheduler.domain.planning.OverflowPolicy
import dev.agenticscheduler.domain.planning.PinState
import dev.agenticscheduler.domain.planning.TaskDeadline
import dev.agenticscheduler.domain.task.TaskEffort
import dev.agenticscheduler.domain.task.TaskPriority
import dev.agenticscheduler.domain.task.TaskStatus
import dev.agenticscheduler.sync.AvailabilityWindowImage
import dev.agenticscheduler.sync.EventImage
import dev.agenticscheduler.sync.FocusBlockImage
import dev.agenticscheduler.sync.PlanningProfileImage
import dev.agenticscheduler.sync.TaskImage
import kotlinx.collections.immutable.toImmutableList
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlin.time.Duration
import kotlin.time.Instant

internal fun Event.toSemanticImage(): EventImage = when (val placement = time) {
    is ZonedTimeRange -> EventImage(id.value, title, flexibility.name, pinState.name, "ZONED", placement.start.toString(), placement.endExclusive.toString(), placement.timeZone.id)
    is AllDayRange -> EventImage(id.value, title, flexibility.name, pinState.name, "ALL_DAY", placement.startDate.toString(), placement.endDateExclusive.toString())
    is FloatingTimeRange -> EventImage(id.value, title, flexibility.name, pinState.name, "FLOATING", placement.start.toString(), placement.endExclusive.toString())
}

internal fun Task.toSemanticImage(): TaskImage {
    val deadline = deadline?.deadline
    return TaskImage(id.value, title, status.name, priority.name, effort.estimated?.toIsoString(), effort.completed.toIsoString(), effort.remaining?.toIsoString(), when (deadline) { null -> null; is dev.agenticscheduler.domain.planning.Deadline.Exact -> "EXACT"; is dev.agenticscheduler.domain.planning.Deadline.DateOnly -> "DATE_ONLY" }, when (deadline) { is dev.agenticscheduler.domain.planning.Deadline.Exact -> deadline.at.toString(); is dev.agenticscheduler.domain.planning.Deadline.DateOnly -> deadline.date.toString(); null -> null }, (deadline as? dev.agenticscheduler.domain.planning.Deadline.Exact)?.timeZone?.id, this.deadline?.policy?.name, this.deadline?.overflowPolicy?.name)
}

internal fun FocusBlock.toSemanticImage() = FocusBlockImage(id.value, taskId.value, time.start.toString(), time.endExclusive.toString(), time.timeZone.id, flexibility.name, pinState.name)

internal fun PlanningProfile.toSemanticImage(): PlanningProfileImage = when (val configuration = configuration) {
    PlanningProfileConfiguration.Unconfigured -> PlanningProfileImage(id.value, name, "UNCONFIGURED")
    is PlanningProfileConfiguration.Configured -> PlanningProfileImage(id.value, name, "CONFIGURED", configuration.timeZone.id, configuration.weeklyAvailability.map { AvailabilityWindowImage(it.dayOfWeek.name, it.start.toString(), it.endExclusive.toString()) }, configuration.minimumFocusBlock.toIsoString(), configuration.preferredFocusBlock.toIsoString(), configuration.maximumFocusBlock.toIsoString(), configuration.allDayEventPolicy.name)
}

internal fun EventImage.toDomain(): Event = Event(EventId(id), title, when (timeKind) {
    "ZONED" -> ZonedTimeRange(Instant.parse(start), Instant.parse(endExclusive), TimeZone.of(requireNotNull(timeZone)))
    "ALL_DAY" -> AllDayRange(LocalDate.parse(start), LocalDate.parse(endExclusive))
    "FLOATING" -> FloatingTimeRange(LocalDateTime.parse(start), LocalDateTime.parse(endExclusive))
    else -> error("Unknown semantic Event time kind: $timeKind")
}, Flexibility.valueOf(flexibility), PinState.valueOf(pinState))

internal fun TaskImage.toDomain(): Task {
    val deadline = deadlineKind?.let { kind -> TaskDeadline(
        when (kind) {
            "EXACT" -> Deadline.Exact(Instant.parse(requireNotNull(deadlineValue)), TimeZone.of(requireNotNull(deadlineTimeZone)))
            "DATE_ONLY" -> Deadline.DateOnly(LocalDate.parse(requireNotNull(deadlineValue)))
            else -> error("Unknown semantic Task deadline kind: $kind")
        }, DeadlinePolicy.valueOf(requireNotNull(deadlinePolicy)), OverflowPolicy.valueOf(requireNotNull(overflowPolicy)),
    ) }
    return Task(TaskId(id), title, TaskStatus.valueOf(status), TaskPriority.valueOf(priority), TaskEffort(estimatedEffort?.let(Duration::parseIsoString), Duration.parseIsoString(completedEffort), remainingEffort?.let(Duration::parseIsoString)), deadline)
}

internal fun FocusBlockImage.toDomain() = FocusBlock(FocusBlockId(id), TaskId(taskId), ZonedTimeRange(Instant.parse(start), Instant.parse(endExclusive), TimeZone.of(timeZone)), Flexibility.valueOf(flexibility), PinState.valueOf(pinState))

internal fun PlanningProfileImage.toDomain(): PlanningProfile = PlanningProfile(PlanningProfileId(id), name, when (configurationState) {
    "UNCONFIGURED" -> PlanningProfileConfiguration.Unconfigured
    "CONFIGURED" -> PlanningProfileConfiguration.Configured(TimeZone.of(requireNotNull(timeZone)), weeklyAvailability.map { dev.agenticscheduler.domain.planning.WeeklyAvailabilityWindow(DayOfWeek.valueOf(it.dayOfWeek), LocalTime.parse(it.start), LocalTime.parse(it.endExclusive)) }.toImmutableList(), Duration.parseIsoString(requireNotNull(minimumFocusDuration)), Duration.parseIsoString(requireNotNull(preferredFocusDuration)), Duration.parseIsoString(requireNotNull(maximumFocusDuration)), AllDayEventPolicy.valueOf(requireNotNull(allDayEventPolicy)))
    else -> error("Unknown semantic PlanningProfile configuration state: $configurationState")
})
