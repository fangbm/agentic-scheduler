package dev.agenticscheduler.application.planner

import dev.agenticscheduler.domain.academic.CourseSession
import dev.agenticscheduler.domain.academic.Exam
import dev.agenticscheduler.domain.event.Event
import dev.agenticscheduler.domain.id.TaskId
import dev.agenticscheduler.domain.planning.PlanningProfile
import dev.agenticscheduler.domain.task.FocusBlock
import dev.agenticscheduler.domain.task.Task
import dev.agenticscheduler.domain.task.TaskDependency
import dev.agenticscheduler.planner.PlanningConstraint
import dev.agenticscheduler.planner.PlanningHorizon
import dev.agenticscheduler.planner.PlanningSnapshot
import kotlin.time.Instant
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.toImmutableList

/**
 * Repository orchestration supplies already-authoritative facts (including D3-resolved sessions).
 * Canonicalization is deliberately here, before the pure planner is called.
 */
class PlanningSnapshotAssembler {
    fun assemble(
        referenceNow: Instant,
        horizon: PlanningHorizon,
        profile: PlanningProfile,
        tasks: Collection<Task>,
        dependencies: Collection<TaskDependency>,
        focusBlocks: Collection<FocusBlock>,
        events: Collection<Event>,
        courseSessions: Collection<CourseSession>,
        exams: Collection<Exam>,
        constraints: Collection<PlanningConstraint>,
        askOverflowAuthorizedTaskIds: Collection<TaskId>,
    ): PlanningSnapshot = PlanningSnapshot(
        referenceNow = referenceNow,
        horizon = horizon,
        profile = profile,
        tasks = tasks.sortedBy { it.id.value }.toImmutableList(),
        dependencies = dependencies.sortedBy { it.id.value }.toImmutableList(),
        focusBlocks = focusBlocks.sortedBy { it.id.value }.toImmutableList(),
        events = events.sortedBy { it.id.value }.toImmutableList(),
        courseSessions = courseSessions.sortedBy { it.occurrenceKey.toString() }.toImmutableList(),
        exams = exams.sortedBy { it.id.value }.toImmutableList(),
        constraints = constraints.sortedBy { it.toString() }.toImmutableList(),
        askOverflowAuthorizedTaskIds = askOverflowAuthorizedTaskIds.sortedBy { it.value }.toImmutableList(),
    )
}
