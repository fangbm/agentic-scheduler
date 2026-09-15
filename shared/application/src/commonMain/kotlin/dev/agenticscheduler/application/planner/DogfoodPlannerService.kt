package dev.agenticscheduler.application.planner

import dev.agenticscheduler.application.id.UuidV7Generator
import dev.agenticscheduler.application.history.MutationCoordinator
import dev.agenticscheduler.application.history.ConflictAwareSourceFactQuery
import dev.agenticscheduler.application.history.ConflictProjection
import dev.agenticscheduler.application.history.SyncConflictWriteBlock
import dev.agenticscheduler.application.history.SyncConflictWritePolicy
import dev.agenticscheduler.application.history.toDomain
import dev.agenticscheduler.application.history.toSemanticImage
import dev.agenticscheduler.application.persistence.AcademicRepository
import dev.agenticscheduler.application.persistence.EventRepository
import dev.agenticscheduler.application.persistence.PlanningProfileRepository
import dev.agenticscheduler.application.persistence.TaskRepository
import dev.agenticscheduler.domain.academic.CourseSessionResolutionResult
import dev.agenticscheduler.domain.academic.resolveCourseSessions
import dev.agenticscheduler.domain.id.PlanningProfileId
import dev.agenticscheduler.domain.planning.PlanningProfile
import dev.agenticscheduler.planner.DeterministicPlanner
import dev.agenticscheduler.planner.LocalReflowRequest
import dev.agenticscheduler.planner.PlannerIssue
import dev.agenticscheduler.planner.PlanningHorizon
import dev.agenticscheduler.planner.PlanningSnapshot
import dev.agenticscheduler.sync.MutationOrigin
import dev.agenticscheduler.sync.PlanningProfilePut
import dev.agenticscheduler.sync.*
import kotlin.time.Instant
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.flow.first

/**
 * D6.5's application-owned bridge between the persisted Active State and the
 * pure D6 Planner. It deliberately keeps PlanBranches session-local: preview
 * never writes, and Apply remains delegated to [PlanBranchApplier].
 */
class DogfoodPlannerService(
    private val tasks: TaskRepository,
    private val events: EventRepository,
    private val profiles: PlanningProfileRepository,
    private val academics: AcademicRepository,
    private val uuidV7: UuidV7Generator,
    private val planner: DeterministicPlanner = DeterministicPlanner(),
    private val snapshotAssembler: PlanningSnapshotAssembler = PlanningSnapshotAssembler(),
    private val mutations: MutationCoordinator,
    private val conflictWritePolicy: SyncConflictWritePolicy,
    private val sourceFacts: ConflictAwareSourceFactQuery,
) {
    private val previews = PlannerPreviewService(planner, PlanBranchFactory(uuidV7))

    suspend fun fullReplan(
        profileId: PlanningProfileId,
        referenceNow: Instant,
        horizon: PlanningHorizon,
    ): PlannerPreview = preview(profileId, referenceNow, horizon, PlanningRequest.FullReplan)

    suspend fun localReflow(
        profileId: PlanningProfileId,
        referenceNow: Instant,
        horizon: PlanningHorizon,
        request: LocalReflowRequest,
    ): PlannerPreview = preview(profileId, referenceNow, horizon, PlanningRequest.LocalReflow(request))

    suspend fun apply(branch: PlanBranch, applyNow: Instant): PlanBranchApplyResult {
        // A source fact that becomes unresolved after Preview cannot be safely
        // applied. Marking the session-only branch stale keeps Active State intact.
        val current = assembleCurrent(
            profileId = branch.baseFacts.profile.id,
            referenceNow = applyNow,
            horizon = branch.baseFacts.horizon,
        )
        if (current is SnapshotAssembly.Invalid) {
            return PlanBranchApplyResult.Stale(branch.copy(status = PlanBranchStatus.STALE))
        }
        return PlanBranchApplier(
            tasks = tasks,
            uuidV7 = uuidV7,
            currentSnapshot = {
                when (val refreshed = assembleCurrent(branch.baseFacts.profile.id, applyNow, branch.baseFacts.horizon)) {
                    is SnapshotAssembly.Ready -> refreshed.snapshot
                    is SnapshotAssembly.Invalid -> null
                }
            },
            mutations = mutations,
            conflictWritePolicy = conflictWritePolicy,
        ).apply(branch, applyNow)
    }

    private suspend fun preview(
        profileId: PlanningProfileId,
        referenceNow: Instant,
        horizon: PlanningHorizon,
        request: PlanningRequest,
    ): PlannerPreview = when (val current = assembleCurrent(profileId, referenceNow, horizon)) {
        is SnapshotAssembly.Ready -> previews.preview(request, current.snapshot)
        is SnapshotAssembly.Invalid -> PlannerPreview.InvalidInput(current.issues)
    }

    private suspend fun assembleCurrent(
        profileId: PlanningProfileId,
        referenceNow: Instant,
        horizon: PlanningHorizon,
    ): SnapshotAssembly {
        val profile = profiles.get(profileId)
            ?: return SnapshotAssembly.Invalid("PlanningProfile ${profileId.value} no longer exists.")
        val projectedProfile = projectOne(PlanningProfilePut(null, profile.toSemanticImage())) { (it as? PlanningProfilePut)?.after?.toDomain() }
            ?: return SnapshotAssembly.Invalid("PlanningProfile ${profileId.value} has an unresolved sync conflict projection.")
        val semesters = projectList(academics.observeSemesters().first(), { SemesterPut(null, it.toSemanticImage()) }) { (it as? SemesterPut)?.after?.toDomain() }
            ?: return SnapshotAssembly.Invalid("Semester source facts have an unresolved sync conflict projection.")
        val courses = projectList(academics.observeCourses().first(), { CoursePut(null, it.toSemanticImage()) }) { (it as? CoursePut)?.after?.toDomain() }
            ?: return SnapshotAssembly.Invalid("Course source facts have an unresolved sync conflict projection.")
        val rules = projectList(academics.observeCourseScheduleRules().first(), { CourseScheduleRulePut(null, it.toSemanticImage()) }) { (it as? CourseScheduleRulePut)?.after?.toDomain() }
            ?: return SnapshotAssembly.Invalid("Course schedule source facts have an unresolved sync conflict projection.")
        val templates = projectList(academics.observePeriodTemplates().first(), { PeriodTemplatePut(null, it.toSemanticImage()) }) { (it as? PeriodTemplatePut)?.after?.toDomain() }
            ?: return SnapshotAssembly.Invalid("Period template source facts have an unresolved sync conflict projection.")
        val holidays = projectList(academics.observeAcademicHolidays().first(), { AcademicHolidayPut(null, it.toSemanticImage()) }) { (it as? AcademicHolidayPut)?.after?.toDomain() }
            ?: return SnapshotAssembly.Invalid("Academic holiday source facts have an unresolved sync conflict projection.")
        val exceptions = projectList(academics.observeCourseOccurrenceExceptions().first(), { CourseOccurrenceExceptionPut(null, it.toSemanticImage()) }) { (it as? CourseOccurrenceExceptionPut)?.after?.toDomain() }
            ?: return SnapshotAssembly.Invalid("Course occurrence source facts have an unresolved sync conflict projection.")
        val academicIssues = mutableListOf<String>()
        val sessions = courses.flatMap { course ->
            val semester = semesters.firstOrNull { it.id == course.semesterId }
            if (semester == null) {
                academicIssues += "Course ${course.id.value} references missing Semester ${course.semesterId.value}."
                emptyList()
            } else {
                when (val resolution = resolveCourseSessions(
                    semester = semester,
                    course = course,
                    rules = rules.filter { it.courseId == course.id },
                    periodTemplates = templates,
                    holidays = holidays.filter { it.semesterId == semester.id },
                    exceptions = exceptions.filter { exception -> rules.any { it.id == exception.occurrenceKey.scheduleRuleId && it.courseId == course.id } },
                )) {
                    is CourseSessionResolutionResult.Success -> resolution.sessions
                    is CourseSessionResolutionResult.Invalid -> {
                        academicIssues += resolution.issues.map { "Course ${course.id.value}: $it" }
                        emptyList()
                    }
                }
            }
        }
        if (academicIssues.isNotEmpty()) return SnapshotAssembly.Invalid(academicIssues)

        return SnapshotAssembly.Ready(snapshotAssembler.assemble(
            referenceNow = referenceNow,
            horizon = horizon,
            profile = projectedProfile,
            tasks = projectList(tasks.observeTasks().first(), { TaskPut(null, it.toSemanticImage()) }) { (it as? TaskPut)?.after?.toDomain() }
                ?: return SnapshotAssembly.Invalid("Task source facts have an unresolved sync conflict projection."),
            dependencies = projectList(tasks.observeDependencies().first(), { TaskDependencyPut(null, it.toSemanticImage()) }) { (it as? TaskDependencyPut)?.after?.toDomain() }
                ?: return SnapshotAssembly.Invalid("Task dependency source facts have an unresolved sync conflict projection."),
            focusBlocks = projectList(tasks.observeFocusBlocks().first(), { FocusBlockPut(null, it.toSemanticImage()) }, allowDeletion = true) { (it as? FocusBlockPut)?.after?.toDomain() }
                ?: return SnapshotAssembly.Invalid("FocusBlock source facts have an unresolved sync conflict projection."),
            events = projectList(events.observeAll().first(), { EventPut(null, it.toSemanticImage()) }) { (it as? EventPut)?.after?.toDomain() }
                ?: return SnapshotAssembly.Invalid("Event source facts have an unresolved sync conflict projection."),
            courseSessions = sessions,
            exams = projectList(academics.observeExams().first(), { ExamPut(null, it.toSemanticImage()) }) { (it as? ExamPut)?.after?.toDomain() }
                ?: return SnapshotAssembly.Invalid("Exam source facts have an unresolved sync conflict projection."),
            constraints = emptyList(),
            askOverflowAuthorizedTaskIds = emptyList(),
        ))
    }

    private suspend fun <T> projectOne(
        durable: EntityMutation,
        decode: (EntityMutation) -> T?,
    ): T? = when (val projected = sourceFacts.project(durable)) {
        is ConflictProjection.Projected -> projected.mutation?.let(decode)
        is ConflictProjection.Unprojectable -> null
    }

    private suspend fun <T> projectList(
        values: List<T>,
        durable: (T) -> EntityMutation,
        allowDeletion: Boolean = false,
        decode: (EntityMutation) -> T?,
    ): List<T>? {
        val projected = mutableListOf<T>()
        values.forEach { value ->
            when (val result = sourceFacts.project(durable(value))) {
                is ConflictProjection.Projected -> {
                    if (result.mutation == null) {
                        if (!allowDeletion) return null
                    } else {
                        projected += decode(result.mutation) ?: return null
                    }
                }
                is ConflictProjection.Unprojectable -> return null
            }
        }
        return projected
    }

    private sealed interface SnapshotAssembly {
        data class Ready(val snapshot: PlanningSnapshot) : SnapshotAssembly
        data class Invalid(val issues: kotlinx.collections.immutable.ImmutableList<PlannerIssue>) : SnapshotAssembly {
            constructor(reason: String) : this(listOf(PlannerIssue.InvalidSnapshot(reason)).toImmutableList())
            constructor(reasons: List<String>) : this(reasons.sorted().map(PlannerIssue::InvalidSnapshot).toImmutableList())
        }
    }
}

sealed interface PlanningProfileSettingsResult {
    data class Success(val profile: PlanningProfile) : PlanningProfileSettingsResult
    data class BlockedBySyncConflict(val blocks: kotlinx.collections.immutable.ImmutableList<SyncConflictWriteBlock>) : PlanningProfileSettingsResult
}

/** Profile settings writes use the application transaction boundary, never a platform DAO. */
class PlanningProfileSettingsService(
    private val profiles: PlanningProfileRepository,
    private val uuidV7: UuidV7Generator,
    private val mutations: MutationCoordinator,
    private val conflictWritePolicy: SyncConflictWritePolicy,
) {
    suspend fun createUnconfigured(name: String): PlanningProfileSettingsResult {
        val profile = PlanningProfile(
            PlanningProfileId(uuidV7.next()),
            name,
            dev.agenticscheduler.domain.planning.PlanningProfileConfiguration.Unconfigured,
        )
        var result: PlanningProfileSettingsResult? = null
        val execution = mutations.executeIfAny(MutationOrigin.User) {
            val proposed = PlanningProfilePut(null, profile.toSemanticImage())
            val blocks = conflictWritePolicy.blocks(listOf(proposed))
            result = if (blocks.isEmpty()) {
                profiles.upsert(profile)
                record(proposed)
                PlanningProfileSettingsResult.Success(profile)
            } else {
                PlanningProfileSettingsResult.BlockedBySyncConflict(blocks.toImmutableList())
            }
            requireNotNull(result)
        }
        return execution?.value ?: requireNotNull(result)
    }

    suspend fun save(profile: PlanningProfile): PlanningProfileSettingsResult {
        var result: PlanningProfileSettingsResult? = null
        val execution = mutations.executeIfAny(MutationOrigin.User) {
            val before = profiles.get(profile.id)
            val proposed = PlanningProfilePut(before?.toSemanticImage(), profile.toSemanticImage())
            val blocks = conflictWritePolicy.blocks(listOf(proposed))
            result = if (blocks.isEmpty()) {
                profiles.upsert(profile)
                record(proposed)
                PlanningProfileSettingsResult.Success(profile)
            } else {
                PlanningProfileSettingsResult.BlockedBySyncConflict(blocks.toImmutableList())
            }
            requireNotNull(result)
        }
        return execution?.value ?: requireNotNull(result)
    }
}
