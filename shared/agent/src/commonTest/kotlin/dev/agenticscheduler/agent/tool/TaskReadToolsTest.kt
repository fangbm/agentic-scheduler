package dev.agenticscheduler.agent.tool

import dev.agenticscheduler.agent.permission.AgentToolCapability
import dev.agenticscheduler.application.persistence.TaskRepository
import dev.agenticscheduler.domain.id.FocusBlockId
import dev.agenticscheduler.domain.id.TaskDependencyId
import dev.agenticscheduler.domain.id.TaskId
import dev.agenticscheduler.domain.id.WorkLogId
import dev.agenticscheduler.domain.task.FocusBlock
import dev.agenticscheduler.domain.task.Task
import dev.agenticscheduler.domain.task.TaskDependency
import dev.agenticscheduler.domain.task.TaskEffort
import dev.agenticscheduler.domain.task.TaskPriority
import dev.agenticscheduler.domain.task.TaskStatus
import dev.agenticscheduler.domain.task.WorkLog
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlin.time.Duration.Companion.ZERO

class TaskReadToolsTest {
    @Test
    fun `task get returns structured not found and never writes`() = runReadToolTest {
        val repository = RecordingTaskRepository(tasks = persistentListOf(task("018f12a3-4b5c-7000-8000-000000000002", "Read docs")))
        val tool = TaskGetTool(repository)

        assertEquals(
            AgentToolOutcome.NotFound,
            tool.execute(TaskId("018f12a3-4b5c-7000-8000-000000000001")),
        )
        assertEquals(0, repository.writeCount)
        assertEquals(AgentToolCapability.READ, tool.metadata.capability)
        assertEquals(AgentToolAccess.READ, tool.metadata.access)
    }

    @Test
    fun `task list filters and canonically orders without writes`() = runReadToolTest {
        val first = task("018f12a3-4b5c-7000-8000-000000000001", "First", TaskStatus.OPEN)
        val second = task("018f12a3-4b5c-7000-8000-000000000002", "Second", TaskStatus.COMPLETED)
        val repository = RecordingTaskRepository(tasks = persistentListOf(second, first))
        val tool = TaskListTool(repository)

        val all = tool.execute(TaskListToolInput(status = null))
        val completed = tool.execute(TaskListToolInput(status = TaskStatus.COMPLETED))

        assertEquals(
            listOf(first.id, second.id),
            (all as AgentToolOutcome.Success).payload.map(Task::id),
        )
        assertEquals(
            listOf(second),
            (completed as AgentToolOutcome.Success).payload,
        )
        assertEquals(0, repository.writeCount)
        assertEquals(AgentToolNames.TASK_LIST, tool.metadata.name)
    }

    private fun task(id: String, title: String, status: TaskStatus = TaskStatus.OPEN) = Task(
        id = TaskId(id),
        title = title,
        status = status,
        priority = TaskPriority.NORMAL,
        effort = TaskEffort(estimated = null, completed = ZERO, remaining = null),
        deadline = null,
    )

    private fun runReadToolTest(block: suspend () -> Unit) = runBlocking { block() }
}

private class RecordingTaskRepository(
    private val tasks: ImmutableList<Task>,
) : TaskRepository {
    var writeCount = 0
        private set

    override fun observeTasks(): Flow<ImmutableList<Task>> = flowOf(tasks)
    override suspend fun getTask(id: TaskId): Task? = tasks.firstOrNull { it.id == id }
    override suspend fun upsertTask(task: Task) { writeCount += 1 }
    override fun observeFocusBlocks(): Flow<ImmutableList<FocusBlock>> = flowOf(persistentListOf())
    override suspend fun getFocusBlock(id: FocusBlockId): FocusBlock? = null
    override suspend fun upsertFocusBlock(focusBlock: FocusBlock) { writeCount += 1 }
    override suspend fun deleteFocusBlock(id: FocusBlockId) { writeCount += 1 }
    override fun observeWorkLogs(): Flow<ImmutableList<WorkLog>> = flowOf(persistentListOf())
    override suspend fun getWorkLog(id: WorkLogId): WorkLog? = null
    override suspend fun upsertWorkLog(workLog: WorkLog) { writeCount += 1 }
    override fun observeDependencies(): Flow<ImmutableList<TaskDependency>> = flowOf(persistentListOf())
    override suspend fun getDependency(id: TaskDependencyId): TaskDependency? = null
    override suspend fun upsertDependency(dependency: TaskDependency) { writeCount += 1 }
}
