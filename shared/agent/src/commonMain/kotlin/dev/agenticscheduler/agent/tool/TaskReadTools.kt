package dev.agenticscheduler.agent.tool

import dev.agenticscheduler.agent.permission.AgentToolCapability
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
    const val TASK_GET = "task.get"
    const val TASK_LIST = "task.list"
}

sealed interface TaskGetToolResult {
    data class Found(val task: Task) : TaskGetToolResult

    data object NotFound : TaskGetToolResult
}

data class TaskListToolInput(
    val status: TaskStatus?,
)

data class TaskListToolResult(
    val tasks: ImmutableList<Task>,
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

    suspend fun execute(taskId: TaskId): TaskGetToolResult =
        tasks.getTask(taskId)?.let(TaskGetToolResult::Found) ?: TaskGetToolResult.NotFound
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

    suspend fun execute(input: TaskListToolInput): TaskListToolResult = TaskListToolResult(
        tasks = tasks.observeTasks()
            .first()
            .asSequence()
            .filter { task -> input.status == null || task.status == input.status }
            .sortedBy { task -> task.id.value }
            .toList()
            .toImmutableList(),
    )
}
