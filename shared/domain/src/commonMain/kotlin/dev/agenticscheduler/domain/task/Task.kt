package dev.agenticscheduler.domain.task

import dev.agenticscheduler.domain.id.TaskId
import dev.agenticscheduler.domain.planning.TaskDeadline
import kotlin.time.Duration

enum class TaskStatus {
    OPEN,
    IN_PROGRESS,
    COMPLETED,
    CANCELLED,
}

enum class TaskPriority {
    LOW,
    NORMAL,
    HIGH,
}

data class TaskEffort(
    val estimated: Duration?,
    val completed: Duration,
    val remaining: Duration?,
) {
    init {
        requireValidEffort(estimated, "estimated")
        requireValidEffort(completed, "completed")
        requireValidEffort(remaining, "remaining")
    }
}

private fun requireValidEffort(value: Duration?, fieldName: String) {
    if (value != null) {
        require(value.isFinite() && value >= Duration.ZERO) {
            "$fieldName effort must be finite and non-negative."
        }
    }
}

data class Task(
    val id: TaskId,
    val title: String,
    val status: TaskStatus,
    val priority: TaskPriority,
    val effort: TaskEffort,
    val deadline: TaskDeadline?,
) {
    init {
        require(title.isNotBlank()) { "A Task title must not be blank." }
    }
}
