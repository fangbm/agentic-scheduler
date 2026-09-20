package dev.agenticscheduler.application.editing

import dev.agenticscheduler.application.id.UuidV7Generator
import dev.agenticscheduler.application.history.MutationCoordinator
import dev.agenticscheduler.application.history.SyncConflictWriteBlock
import dev.agenticscheduler.application.history.SyncConflictWritePolicy
import dev.agenticscheduler.application.history.toSemanticImage
import dev.agenticscheduler.application.persistence.EventRepository
import dev.agenticscheduler.application.persistence.TaskRepository
import dev.agenticscheduler.domain.event.Event
import dev.agenticscheduler.domain.id.EventId
import dev.agenticscheduler.domain.id.TaskId
import dev.agenticscheduler.domain.planning.Deadline
import dev.agenticscheduler.domain.planning.DeadlinePolicy
import dev.agenticscheduler.domain.planning.OverflowPolicy
import dev.agenticscheduler.domain.planning.TaskDeadline
import dev.agenticscheduler.domain.planning.Flexibility
import dev.agenticscheduler.domain.planning.PinState
import dev.agenticscheduler.domain.task.Task
import dev.agenticscheduler.domain.task.TaskEffort
import dev.agenticscheduler.domain.task.TaskPriority
import dev.agenticscheduler.domain.task.TaskStatus
import dev.agenticscheduler.domain.time.AllDayRange
import dev.agenticscheduler.domain.time.FloatingTimeRange
import dev.agenticscheduler.domain.time.TimePlacement
import dev.agenticscheduler.domain.time.ZonedTimeRange
import dev.agenticscheduler.sync.EventPut
import dev.agenticscheduler.sync.MutationOrigin
import dev.agenticscheduler.sync.TaskPut
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.toImmutableList
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.offsetAt
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

sealed interface EventTimeInput {
    data class Zoned(
        val start: LocalDateTime,
        val endExclusive: LocalDateTime,
        val timeZone: TimeZone,
    ) : EventTimeInput

    data class AllDay(
        val startDate: LocalDate,
        val endDateExclusive: LocalDate,
    ) : EventTimeInput

    data class Floating(
        val start: LocalDateTime,
        val endExclusive: LocalDateTime,
    ) : EventTimeInput
}

sealed interface TaskDeadlineInput {
    data class Exact(
        val at: LocalDateTime,
        val timeZone: TimeZone,
        val policy: DeadlinePolicy,
        val overflowPolicy: OverflowPolicy,
    ) : TaskDeadlineInput

    data class DateOnly(
        val date: LocalDate,
        val policy: DeadlinePolicy,
        val overflowPolicy: OverflowPolicy,
    ) : TaskDeadlineInput
}

data class CreateEventInput(
    val title: String,
    val time: EventTimeInput?,
    val flexibility: Flexibility,
    val pinState: PinState,
)

data class UpdateEventInput(
    val id: EventId,
    val title: String,
    val time: EventTimeInput?,
    val flexibility: Flexibility,
    val pinState: PinState,
)

data class CreateTaskInput(
    val title: String,
    val priority: TaskPriority,
    val estimated: Duration?,
    val remaining: Duration?,
    val deadline: TaskDeadlineInput?,
)

data class UpdateTaskInput(
    val id: TaskId,
    val title: String,
    val status: TaskStatus,
    val priority: TaskPriority,
    val estimated: Duration?,
    val completed: Duration,
    val remaining: Duration?,
    val deadline: TaskDeadlineInput?,
)

sealed interface EditingResult<out T> {
    data class Success<T>(val value: T) : EditingResult<T>
    data class Invalid(val issues: ImmutableList<EditingIssue>) : EditingResult<Nothing>
    data object NotFound : EditingResult<Nothing>
    data class BlockedBySyncConflict(val blocks: ImmutableList<SyncConflictWriteBlock>) : EditingResult<Nothing>
}

sealed interface EditingIssue {
    data object BlankTitle : EditingIssue
    data object MissingEventTime : EditingIssue
    data object InvalidTimeRange : EditingIssue
    data class ZonedTimeTransitionRejected(val field: ZonedTimeField) : EditingIssue
    data class InvalidEffort(val field: EffortField) : EditingIssue
}

enum class ZonedTimeField {
    START,
    END,
    DEADLINE,
}

enum class EffortField {
    ESTIMATED,
    COMPLETED,
    REMAINING,
}

class EventEditingService(
    private val events: EventRepository,
    private val ids: UuidV7Generator,
    private val mutations: MutationCoordinator,
    private val conflictWritePolicy: SyncConflictWritePolicy,
) {
    suspend fun create(input: CreateEventInput): EditingResult<Event> {
        val built = validateEvent(input.title, input.time)
        if (built is EventInput.Invalid) return EditingResult.Invalid(built.issues)
        val event = Event(EventId(ids.next()), input.title, (built as EventInput.Valid).time, input.flexibility, input.pinState)
        var result: EditingResult<Event>? = null
        val execution = mutations.executeIfAny(MutationOrigin.User) {
            val proposed = EventPut(null, event.toSemanticImage())
            val blocks = conflictWritePolicy.blocks(listOf(proposed))
            result = if (blocks.isEmpty()) {
                events.upsert(event)
                record(proposed)
                EditingResult.Success(event)
            } else {
                EditingResult.BlockedBySyncConflict(blocks.toImmutableList())
            }
            requireNotNull(result)
        }
        return execution?.value ?: requireNotNull(result)
    }

    suspend fun update(input: UpdateEventInput): EditingResult<Event> {
        val built = validateEvent(input.title, input.time)
        if (built is EventInput.Invalid) return EditingResult.Invalid(built.issues)
        val event = Event(input.id, input.title, (built as EventInput.Valid).time, input.flexibility, input.pinState)
        var result: EditingResult<Event>? = null
        val execution = mutations.executeIfAny(MutationOrigin.User) {
            val before = events.get(input.id)
            result = if (before == null) {
                EditingResult.NotFound
            } else {
                val proposed = EventPut(before.toSemanticImage(), event.toSemanticImage())
                val blocks = conflictWritePolicy.blocks(listOf(proposed))
                if (blocks.isEmpty()) {
                    events.upsert(event)
                    record(proposed)
                    EditingResult.Success(event)
                } else {
                    EditingResult.BlockedBySyncConflict(blocks.toImmutableList())
                }
            }
            requireNotNull(result)
        }
        return execution?.value ?: requireNotNull(result)
    }
}

class TaskEditingService(
    private val tasks: TaskRepository,
    private val ids: UuidV7Generator,
    private val mutations: MutationCoordinator,
    private val conflictWritePolicy: SyncConflictWritePolicy,
) {
    suspend fun create(input: CreateTaskInput): EditingResult<Task> {
        val built = validateTask(input.title, input.estimated, Duration.ZERO, input.remaining, input.deadline)
        if (built is TaskInput.Invalid) return EditingResult.Invalid(built.issues)
        val valid = built as TaskInput.Valid
        val task = Task(TaskId(ids.next()), input.title, TaskStatus.OPEN, input.priority, valid.effort, valid.deadline)
        var result: EditingResult<Task>? = null
        val execution = mutations.executeIfAny(MutationOrigin.User) {
            val proposed = TaskPut(null, task.toSemanticImage())
            val blocks = conflictWritePolicy.blocks(listOf(proposed))
            result = if (blocks.isEmpty()) {
                tasks.upsertTask(task)
                record(proposed)
                EditingResult.Success(task)
            } else {
                EditingResult.BlockedBySyncConflict(blocks.toImmutableList())
            }
            requireNotNull(result)
        }
        return execution?.value ?: requireNotNull(result)
    }

    suspend fun update(input: UpdateTaskInput): EditingResult<Task> {
        val built = validateTask(input.title, input.estimated, input.completed, input.remaining, input.deadline)
        if (built is TaskInput.Invalid) return EditingResult.Invalid(built.issues)
        val valid = built as TaskInput.Valid
        val task = Task(input.id, input.title, input.status, input.priority, valid.effort, valid.deadline)
        var result: EditingResult<Task>? = null
        val execution = mutations.executeIfAny(MutationOrigin.User) {
            val before = tasks.getTask(input.id)
            result = if (before == null) {
                EditingResult.NotFound
            } else {
                val proposed = TaskPut(before.toSemanticImage(), task.toSemanticImage())
                val blocks = conflictWritePolicy.blocks(listOf(proposed))
                if (blocks.isEmpty()) {
                    tasks.upsertTask(task)
                    record(proposed)
                    EditingResult.Success(task)
                } else {
                    EditingResult.BlockedBySyncConflict(blocks.toImmutableList())
                }
            }
            requireNotNull(result)
        }
        return execution?.value ?: requireNotNull(result)
    }
}

private sealed interface EventInput {
    data class Valid(val time: TimePlacement) : EventInput
    data class Invalid(val issues: ImmutableList<EditingIssue>) : EventInput
}

private fun validateEvent(
    title: String,
    time: EventTimeInput?,
): EventInput {
    val issues = mutableListOf<EditingIssue>()
    if (title.isBlank()) issues += EditingIssue.BlankTitle
    val placement = resolveEventTime(time, issues)
    return if (issues.isEmpty()) {
        EventInput.Valid(checkNotNull(placement))
    } else {
        EventInput.Invalid(issues.toImmutableList())
    }
}

private fun resolveEventTime(
    input: EventTimeInput?,
    issues: MutableList<EditingIssue>,
): TimePlacement? = when (input) {
    null -> {
        issues += EditingIssue.MissingEventTime
        null
    }
    is EventTimeInput.AllDay -> {
        if (input.startDate >= input.endDateExclusive) {
            issues += EditingIssue.InvalidTimeRange
            null
        } else {
            AllDayRange(input.startDate, input.endDateExclusive)
        }
    }
    is EventTimeInput.Floating -> {
        if (input.start >= input.endExclusive) {
            issues += EditingIssue.InvalidTimeRange
            null
        } else {
            FloatingTimeRange(input.start, input.endExclusive)
        }
    }
    is EventTimeInput.Zoned -> {
        val start = input.start.toStrictInstantOrNull(input.timeZone)
        val end = input.endExclusive.toStrictInstantOrNull(input.timeZone)
        if (start == null) issues += EditingIssue.ZonedTimeTransitionRejected(ZonedTimeField.START)
        if (end == null) issues += EditingIssue.ZonedTimeTransitionRejected(ZonedTimeField.END)
        if (start != null && end != null && start >= end) issues += EditingIssue.InvalidTimeRange
        if (issues.isEmpty()) ZonedTimeRange(checkNotNull(start), checkNotNull(end), input.timeZone) else null
    }
}

private sealed interface TaskInput {
    data class Valid(
        val effort: TaskEffort,
        val deadline: TaskDeadline?,
    ) : TaskInput

    data class Invalid(val issues: ImmutableList<EditingIssue>) : TaskInput
}

private fun validateTask(
    title: String,
    estimated: Duration?,
    completed: Duration,
    remaining: Duration?,
    deadline: TaskDeadlineInput?,
): TaskInput {
    val issues = mutableListOf<EditingIssue>()
    if (title.isBlank()) issues += EditingIssue.BlankTitle
    validateEffort(estimated, EffortField.ESTIMATED, issues)
    validateEffort(completed, EffortField.COMPLETED, issues)
    validateEffort(remaining, EffortField.REMAINING, issues)
    val taskDeadline = resolveDeadline(deadline, issues)
    return if (issues.isEmpty()) {
        TaskInput.Valid(TaskEffort(estimated, completed, remaining), taskDeadline)
    } else {
        TaskInput.Invalid(issues.toImmutableList())
    }
}

private fun validateEffort(
    value: Duration?,
    field: EffortField,
    issues: MutableList<EditingIssue>,
) {
    if (value != null && (!value.isFinite() || value < Duration.ZERO)) {
        issues += EditingIssue.InvalidEffort(field)
    }
}

private fun resolveDeadline(
    input: TaskDeadlineInput?,
    issues: MutableList<EditingIssue>,
): TaskDeadline? = when (input) {
    null -> null
    is TaskDeadlineInput.DateOnly -> TaskDeadline(
        Deadline.DateOnly(input.date),
        input.policy,
        input.overflowPolicy,
    )
    is TaskDeadlineInput.Exact -> {
        val instant = input.at.toStrictInstantOrNull(input.timeZone)
        if (instant == null) {
            issues += EditingIssue.ZonedTimeTransitionRejected(ZonedTimeField.DEADLINE)
            null
        } else {
            TaskDeadline(
                Deadline.Exact(instant, input.timeZone),
                input.policy,
                input.overflowPolicy,
            )
        }
    }
}

private fun LocalDateTime.toStrictInstantOrNull(timeZone: TimeZone): Instant? {
    val selected = try {
        toInstant(timeZone)
    } catch (_: IllegalArgumentException) {
        return null
    }
    if (selected.toLocalDateTime(timeZone) != this) return null

    val selectedOffset = timeZone.offsetAt(selected).totalSeconds
    val nearbyOffsets = listOf(
        timeZone.offsetAt(selected - 48.hours),
        timeZone.offsetAt(selected + 48.hours),
    ).map { it.totalSeconds }.distinct()
    val hasAlternativeInstant = nearbyOffsets.any { alternateOffset ->
        alternateOffset != selectedOffset &&
            (selected + (selectedOffset - alternateOffset).seconds).toLocalDateTime(timeZone) == this
    }
    return if (hasAlternativeInstant) null else selected
}
