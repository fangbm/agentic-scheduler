package dev.agenticscheduler.agent.tool

import dev.agenticscheduler.agent.permission.AgentToolCapability
import dev.agenticscheduler.application.calendar.CalendarProjectionResult
import dev.agenticscheduler.application.calendar.CalendarQueryService
import dev.agenticscheduler.application.calendar.CalendarViewport
import dev.agenticscheduler.application.persistence.TaskRepository
import dev.agenticscheduler.domain.id.TaskId
import dev.agenticscheduler.domain.task.Task
import dev.agenticscheduler.domain.task.TaskStatus
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.flow.first

enum class AgentToolAccess {
    READ,
    WRITE,
}

data class AgentToolMetadata(
    val name: String,
    val capability: AgentToolCapability,
    val access: AgentToolAccess,
) {
    init {
        require(name.isNotBlank())
    }
}

/** Frozen internal names from AGT-004. They are not an external/MCP schema promise. */
object AgentToolNames {
    const val CALENDAR_LIST = "calendar.list"
    const val TASK_GET = "task.get"
    const val TASK_LIST = "task.list"
    const val TASK_CREATE = "task.create"
    const val HISTORY_TIMELINE = "history.timeline"
    const val HISTORY_GET_MUTATION = "history.getMutation"
    const val HISTORY_GET_ENTITY_CHANGES = "history.getEntityChanges"
}

/**
 * Typed read-only calendar query. [CalendarViewport] requires the caller to supply an explicit
 * display timezone, so the Agent never reads a platform default timezone on the user's behalf.
 */
class CalendarListTool(
    private val calendar: CalendarQueryService,
) {
    val metadata = AgentToolMetadata(
        name = AgentToolNames.CALENDAR_LIST,
        capability = AgentToolCapability.READ,
        access = AgentToolAccess.READ,
    )

    suspend fun execute(viewport: CalendarViewport): AgentToolOutcome<CalendarProjectionResult> =
        AgentToolOutcome.Success(calendar.observe(viewport).first())
}

data class TaskListToolInput(
    val status: TaskStatus?,
)

/**
 * Typed read-only D9 Tool backed by the existing application repository contract.
 *
 * It deliberately holds no transaction runner or mutation coordinator: executing this Tool
 * cannot turn provider input into a write.
 */
class TaskGetTool(
    private val tasks: TaskRepository,
) {
    val metadata = AgentToolMetadata(
        name = AgentToolNames.TASK_GET,
        capability = AgentToolCapability.READ,
        access = AgentToolAccess.READ,
    )

    suspend fun execute(taskId: TaskId): AgentToolOutcome<Task> =
        tasks.getTask(taskId)?.let { task -> AgentToolOutcome.Success(task) } ?: AgentToolOutcome.NotFound
}

/** Lists tasks in canonical immutable-ID order, optionally constrained by an explicit status. */
class TaskListTool(
    private val tasks: TaskRepository,
) {
    val metadata = AgentToolMetadata(
        name = AgentToolNames.TASK_LIST,
        capability = AgentToolCapability.READ,
        access = AgentToolAccess.READ,
    )

    suspend fun execute(input: TaskListToolInput): AgentToolOutcome<ImmutableList<Task>> = AgentToolOutcome.Success(
        tasks.observeTasks()
            .first()
            .asSequence()
            .filter { task -> input.status == null || task.status == input.status }
            .sortedBy { task -> task.id.value }
            .toList()
            .toImmutableList(),
    )
}
