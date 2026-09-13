package dev.agenticscheduler.planner

import dev.agenticscheduler.domain.task.TaskStatus

/**
 * Shared snapshot coherence validation (PLN-005). Both planner entries must call
 * this before constructing any Interval or derived view, so an invalid snapshot
 * returns a structured InvalidInput instead of throwing.
 */
internal object SnapshotValidation {

    fun invalidReason(snapshot: PlanningSnapshot): PlannerIssue.InvalidSnapshot? = when {
        snapshot.referenceNow < snapshot.horizon.start || snapshot.referenceNow >= snapshot.horizon.endExclusive ->
            PlannerIssue.InvalidSnapshot("referenceNow must be inside the horizon")
        snapshot.tasks.map { it.id }.distinct().size != snapshot.tasks.size ->
            PlannerIssue.InvalidSnapshot("duplicate TaskId")
        snapshot.focusBlocks.map { it.id }.distinct().size != snapshot.focusBlocks.size ->
            PlannerIssue.InvalidSnapshot("duplicate FocusBlockId")
        snapshot.dependencies.any { dependency ->
            snapshot.tasks.none { it.id == dependency.prerequisiteTaskId } ||
                snapshot.tasks.none { it.id == dependency.dependentTaskId }
        } -> PlannerIssue.InvalidSnapshot("dependency references unknown Task")
        hasDependencyCycle(snapshot) -> PlannerIssue.InvalidSnapshot("dependency cycle")
        else -> null
    }

    private fun hasDependencyCycle(snapshot: PlanningSnapshot): Boolean {
        val edges = snapshot.dependencies.groupBy({ it.prerequisiteTaskId }, { it.dependentTaskId })
        val complete = mutableSetOf<dev.agenticscheduler.domain.id.TaskId>()
        val active = mutableSetOf<dev.agenticscheduler.domain.id.TaskId>()
        fun visit(id: dev.agenticscheduler.domain.id.TaskId): Boolean {
            if (id in active) return true
            if (!complete.add(id)) return false
            active += id
            val found = edges[id].orEmpty().any(::visit)
            active -= id
            return found
        }
        return snapshot.tasks.any { visit(it.id) }
    }
}
