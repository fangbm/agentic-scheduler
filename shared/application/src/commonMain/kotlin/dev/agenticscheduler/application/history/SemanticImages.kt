package dev.agenticscheduler.application.history

import dev.agenticscheduler.domain.event.Event
import dev.agenticscheduler.domain.planning.PlanningProfile
import dev.agenticscheduler.domain.planning.PlanningProfileConfiguration
import dev.agenticscheduler.domain.task.FocusBlock
import dev.agenticscheduler.domain.task.Task
import dev.agenticscheduler.domain.time.AllDayRange
import dev.agenticscheduler.domain.time.FloatingTimeRange
import dev.agenticscheduler.domain.time.ZonedTimeRange
import dev.agenticscheduler.sync.AvailabilityWindowImage
import dev.agenticscheduler.sync.EventImage
import dev.agenticscheduler.sync.FocusBlockImage
import dev.agenticscheduler.sync.PlanningProfileImage
import dev.agenticscheduler.sync.TaskImage

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
