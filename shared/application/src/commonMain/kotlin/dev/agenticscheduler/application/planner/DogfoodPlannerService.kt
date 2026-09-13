package dev.agenticscheduler.application.planner

import dev.agenticscheduler.application.id.UuidV7Generator
import dev.agenticscheduler.application.persistence.AcademicRepository
import dev.agenticscheduler.application.persistence.ApplicationTransactionRunner
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
    private val transactions: ApplicationTransactionRunner,
    private val uuidV7: UuidV7Generator,
    private val planner: DeterministicPlanner = DeterministicPlanner(),
    private val snapshotAssembler: PlanningSnapshotAssembler = PlanningSnapshotAssembler(),
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
            transactions = transactions,
            tasks = tasks,
            uuidV7 = uuidV7,
            currentSnapshot = {
                when (val refreshed = assembleCurrent(branch.baseFacts.profile.id, applyNow, branch.baseFacts.horizon)) {
                    is SnapshotAssembly.Ready -> refreshed.snapshot
                    is SnapshotAssembly.Invalid -> null
                }
            },
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
        val semesters = academics.observeSemesters().first()
        val courses = academics.observeCourses().first()
        val rules = academics.observeCourseScheduleRules().first()
        val templates = academics.observePeriodTemplates().first()
        val holidays = academics.observeAcademicHolidays().first()
        val exceptions = academics.observeCourseOccurrenceExceptions().first()
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
            profile = profile,
            tasks = tasks.observeTasks().first(),
            dependencies = tasks.observeDependencies().first(),
            focusBlocks = tasks.observeFocusBlocks().first(),
            events = events.observeAll().first(),
            courseSessions = sessions,
            exams = academics.observeExams().first(),
            constraints = emptyList(),
            askOverflowAuthorizedTaskIds = emptyList(),
        ))
    }

    private sealed interface SnapshotAssembly {
        data class Ready(val snapshot: PlanningSnapshot) : SnapshotAssembly
        data class Invalid(val issues: kotlinx.collections.immutable.ImmutableList<PlannerIssue>) : SnapshotAssembly {
            constructor(reason: String) : this(listOf(PlannerIssue.InvalidSnapshot(reason)).toImmutableList())
            constructor(reasons: List<String>) : this(reasons.sorted().map(PlannerIssue::InvalidSnapshot).toImmutableList())
        }
    }
}

/** Profile settings writes use the application transaction boundary, never a platform DAO. */
class PlanningProfileSettingsService(
    private val profiles: PlanningProfileRepository,
    private val transactions: ApplicationTransactionRunner,
    private val uuidV7: UuidV7Generator,
) {
    suspend fun createUnconfigured(name: String): PlanningProfile = transactions.inWriteTransaction {
        val profile = PlanningProfile(
            PlanningProfileId(uuidV7.next()),
            name,
            dev.agenticscheduler.domain.planning.PlanningProfileConfiguration.Unconfigured,
        )
        profiles.upsert(profile)
        profile
    }

    suspend fun save(profile: PlanningProfile): PlanningProfile = transactions.inWriteTransaction {
        profiles.upsert(profile)
        profile
    }
}
