package dev.agenticscheduler.application.history

import dev.agenticscheduler.application.calendar.CalendarProjectionInput
import dev.agenticscheduler.application.calendar.CalendarProjectionIssue
import dev.agenticscheduler.application.calendar.CalendarProjectionResult
import dev.agenticscheduler.application.calendar.CalendarQueryService
import dev.agenticscheduler.application.calendar.CalendarViewport
import dev.agenticscheduler.application.calendar.project
import dev.agenticscheduler.application.persistence.AcademicRepository
import dev.agenticscheduler.application.persistence.EventRepository
import dev.agenticscheduler.application.persistence.PlanningProfileRepository
import dev.agenticscheduler.application.persistence.TaskRepository
import dev.agenticscheduler.domain.academic.*
import dev.agenticscheduler.domain.event.Event
import dev.agenticscheduler.domain.id.EventId
import dev.agenticscheduler.domain.planning.PlanningProfile
import dev.agenticscheduler.domain.task.FocusBlock
import dev.agenticscheduler.domain.task.Task
import dev.agenticscheduler.sync.*
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map

/** A projected source-fact read or D8-P02's explicit fail-closed result. */
sealed interface ConflictAwareRead<out T> {
    data class Projected<T>(val value: T) : ConflictAwareRead<T>
    data class Unprojectable(val conflictIds: List<String>, val reason: String) : ConflictAwareRead<Nothing>
}

/**
 * D8-P03's single read boundary for Desktop, Android, Wear, and automated readers.
 *
 * It owns no Active State and cannot write, journal, or advance causal state.  Its typed
 * Domain-to-image-to-Domain round trip deliberately preserves D7.1 validation as the final
 * authority after an OPEN-conflict projection.
 */
class ConflictAwareSourceFactReadService(
    private val events: EventRepository,
    private val tasks: TaskRepository,
    private val profiles: PlanningProfileRepository,
    private val academics: AcademicRepository,
    private val sourceFacts: ConflictAwareSourceFactQuery,
) : CalendarQueryService {
    fun observeTasks(): Flow<ConflictAwareRead<ImmutableList<Task>>> =
        tasks.observeTasks().map { values ->
            projectCollection(EntityKind.TASK, values, { TaskPut(null, it.toSemanticImage()) }) {
                (it as TaskPut).after.toDomain()
            }
        }

    fun observeFocusBlocks(): Flow<ConflictAwareRead<ImmutableList<FocusBlock>>> =
        tasks.observeFocusBlocks().map { values ->
            projectCollection(EntityKind.FOCUS_BLOCK, values, { FocusBlockPut(null, it.toSemanticImage()) }) {
                (it as FocusBlockPut).after.toDomain()
            }
        }

    fun observePlanningProfiles(): Flow<ConflictAwareRead<ImmutableList<PlanningProfile>>> =
        profiles.observeAll().map { values ->
            projectCollection(EntityKind.PLANNING_PROFILE, values, { PlanningProfilePut(null, it.toSemanticImage()) }) {
                (it as PlanningProfilePut).after.toDomain()
            }
        }

    suspend fun event(id: EventId): ConflictAwareRead<Event?> {
        val durable = events.get(id) ?: return ConflictAwareRead.Projected(null)
        return when (val projected = sourceFacts.project(EventPut(null, durable.toSemanticImage()))) {
            is ConflictProjection.Projected -> ConflictAwareRead.Projected((projected.mutation as? EventPut)?.after?.toDomain())
            is ConflictProjection.Unprojectable -> ConflictAwareRead.Unprojectable(projected.conflictIds, projected.reason)
        }
    }

    override fun observe(viewport: CalendarViewport): Flow<CalendarProjectionResult> {
        val academicBase = combine(
            academics.observeSemesters().map { values -> projectCollection(EntityKind.SEMESTER, values, { SemesterPut(null, it.toSemanticImage()) }) { (it as SemesterPut).after.toDomain() } },
            academics.observeCourses().map { values -> projectCollection(EntityKind.COURSE, values, { CoursePut(null, it.toSemanticImage()) }) { (it as CoursePut).after.toDomain() } },
            academics.observeCourseScheduleRules().map { values -> projectCollection(EntityKind.COURSE_SCHEDULE_RULE, values, { CourseScheduleRulePut(null, it.toSemanticImage()) }) { (it as CourseScheduleRulePut).after.toDomain() } },
            academics.observePeriodTemplates().map { values -> projectCollection(EntityKind.PERIOD_TEMPLATE, values, { PeriodTemplatePut(null, it.toSemanticImage()) }) { (it as PeriodTemplatePut).after.toDomain() } },
            academics.observeAcademicHolidays().map { values -> projectCollection(EntityKind.ACADEMIC_HOLIDAY, values, { AcademicHolidayPut(null, it.toSemanticImage()) }) { (it as AcademicHolidayPut).after.toDomain() } },
        ) { semesters, courses, rules, templates, holidays ->
            CalendarAcademicFacts(semesters, courses, rules, templates, holidays)
        }
        val academic = combine(
            academicBase,
            academics.observeCourseOccurrenceExceptions().map { values -> projectCollection(EntityKind.COURSE_OCCURRENCE_EXCEPTION, values, { CourseOccurrenceExceptionPut(null, it.toSemanticImage()) }) { (it as CourseOccurrenceExceptionPut).after.toDomain() } },
            academics.observeExams().map { values -> projectCollection(EntityKind.EXAM, values, { ExamPut(null, it.toSemanticImage()) }) { (it as ExamPut).after.toDomain() } },
        ) { base, exceptions, exams -> base.copy(exceptions = exceptions, exams = exams) }
        return combine(
            events.observeAll().map { values -> projectCollection(EntityKind.EVENT, values, { EventPut(null, it.toSemanticImage()) }) { (it as EventPut).after.toDomain() } },
            observeFocusBlocks(),
            academic,
        ) { eventValues, focusBlocks, snapshot ->
            val reads: List<ConflictAwareRead<*>> = listOf(eventValues, focusBlocks) + snapshot.reads()
            val failures = reads.filterIsInstance<ConflictAwareRead.Unprojectable>()
            if (failures.isNotEmpty()) {
                CalendarProjectionResult(
                    emptyList<dev.agenticscheduler.application.calendar.CalendarItem>().toImmutableList(),
                    emptyList<dev.agenticscheduler.application.calendar.CalendarConflict>().toImmutableList(),
                    failures.map { CalendarProjectionIssue.SyncConflictUnprojectable(it.conflictIds, it.reason) }
                        .distinct()
                        .sortedBy { it.toString() }
                        .toImmutableList(),
                )
            } else {
                project(
                    viewport,
                    requireNotNull(eventValues.valueOrNull()),
                    requireNotNull(focusBlocks.valueOrNull()),
                    snapshot.toProjectionInput(),
                )
            }
        }
    }

    private suspend fun <T, D> projectCollection(
        entityKind: EntityKind,
        durable: Collection<D>,
        toMutation: (D) -> EntityMutation,
        toDomain: (EntityMutation) -> T,
    ): ConflictAwareRead<ImmutableList<T>> = when (val projected = sourceFacts.projectCollection(entityKind, durable.map(toMutation))) {
        is ConflictCollectionProjection.Projected -> ConflictAwareRead.Projected(projected.mutations.map(toDomain).toImmutableList())
        is ConflictCollectionProjection.Unprojectable -> ConflictAwareRead.Unprojectable(projected.conflictIds, projected.reason)
    }
}

private data class CalendarAcademicFacts(
    val semesters: ConflictAwareRead<ImmutableList<Semester>>,
    val courses: ConflictAwareRead<ImmutableList<Course>>,
    val rules: ConflictAwareRead<ImmutableList<CourseScheduleRule>>,
    val templates: ConflictAwareRead<ImmutableList<PeriodTemplate>>,
    val holidays: ConflictAwareRead<ImmutableList<AcademicHoliday>>,
    val exceptions: ConflictAwareRead<ImmutableList<CourseOccurrenceException>>? = null,
    val exams: ConflictAwareRead<ImmutableList<Exam>>? = null,
) {
    fun reads(): List<ConflictAwareRead<*>> = listOfNotNull(semesters, courses, rules, templates, holidays, exceptions, exams)

    fun toProjectionInput(): CalendarProjectionInput = CalendarProjectionInput(
        requireNotNull(semesters.valueOrNull()),
        requireNotNull(courses.valueOrNull()),
        requireNotNull(rules.valueOrNull()),
        requireNotNull(templates.valueOrNull()),
        requireNotNull(holidays.valueOrNull()),
        requireNotNull(exceptions?.valueOrNull()),
        requireNotNull(exams?.valueOrNull()),
    )
}

private fun <T> ConflictAwareRead<T>.valueOrNull(): T? = (this as? ConflictAwareRead.Projected<T>)?.value
