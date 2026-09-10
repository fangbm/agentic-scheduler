package dev.agenticscheduler.domain.task

import dev.agenticscheduler.domain.id.TaskDependencyId
import dev.agenticscheduler.domain.id.TaskId

data class TaskDependency(
    val id: TaskDependencyId,
    val prerequisiteTaskId: TaskId,
    val dependentTaskId: TaskId,
) {
    init {
        require(prerequisiteTaskId != dependentTaskId) { "A Task cannot depend on itself." }
    }
}

enum class TaskDependencyValidationResult {
    VALID,
    DUPLICATE,
    CYCLE,
}

fun validateDependencyAddition(
    existing: Collection<TaskDependency>,
    candidate: TaskDependency,
): TaskDependencyValidationResult {
    if (existing.any {
            it.prerequisiteTaskId == candidate.prerequisiteTaskId &&
                it.dependentTaskId == candidate.dependentTaskId
        }
    ) {
        return TaskDependencyValidationResult.DUPLICATE
    }

    val adjacency = mutableMapOf<TaskId, MutableList<TaskId>>()
    existing.forEach { dependency ->
        adjacency.getOrPut(dependency.prerequisiteTaskId) { mutableListOf() }
            .add(dependency.dependentTaskId)
    }

    return if (hasPath(
            adjacency = adjacency,
            start = candidate.dependentTaskId,
            target = candidate.prerequisiteTaskId,
        )
    ) {
        TaskDependencyValidationResult.CYCLE
    } else {
        TaskDependencyValidationResult.VALID
    }
}

private fun hasPath(
    adjacency: Map<TaskId, List<TaskId>>,
    start: TaskId,
    target: TaskId,
): Boolean {
    val visited = mutableSetOf<TaskId>()
    val pending = ArrayDeque<TaskId>()
    pending.addLast(start)

    while (pending.isNotEmpty()) {
        val current = pending.removeLast()
        if (current == target) return true
        if (!visited.add(current)) continue
        adjacency[current].orEmpty().forEach(pending::addLast)
    }
    return false
}
