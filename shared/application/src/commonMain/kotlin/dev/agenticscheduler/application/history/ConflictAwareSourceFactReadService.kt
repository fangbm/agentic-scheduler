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
    data class Projected<T>(
        val value: T,
        val syncConflictRefs: List<SyncConflictProjectionRef> = emptyList(),
    ) : ConflictAwareRead<T>
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
        observeCollection(tasks.observeTasks(), EntityKind.TASK, { TaskPut(null, it.toSemanticImage()) }) {
            (it as TaskPut).after.toDomain()
        }

    fun observeFocusBlocks(): Flow<ConflictAwareRead<ImmutableList<FocusBlock>>> =
        observeCollection(tasks.observeFocusBlocks(), EntityKind.FOCUS_BLOCK, { FocusBlockPut(null, it.toSemanticImage()) }) {
            (it as FocusBlockPut).after.toDomain()
        }

    fun observePlanningProfiles(): Flow<ConflictAwareRead<ImmutableList<PlanningProfile>>> =
        observeCollection(profiles.observeAll(), EntityKind.PLANNING_PROFILE, { PlanningProfilePut(null, it.toSemanticImage()) }) {
            (it as PlanningProfilePut).after.toDomain()
        }

    suspend fun event(id: EventId): ConflictAwareRead<Event?> {
        val durable = events.get(id) ?: return ConflictAwareRead.Projected(null)
        val projected = sourceFacts.project(EventPut(null, durable.toSemanticImage()))
        return when (projected) {
            is ConflictProjection.Projected -> ConflictAwareRead.Projected(
                (projected.mutation as? EventPut)?.after?.toDomain(),
                projected.openConflictIds.takeIf { it.isNotEmpty() }?.let {
                    listOf(SyncConflictProjectionRef(EntityKind.EVENT, id.value, it.distinct().sorted()))
                }.orEmpty(),
            )
            is ConflictProjection.Unprojectable -> ConflictAwareRead.Unprojectable(projected.conflictIds, projected.reason)
        }
    }

    override fun observe(viewport: CalendarViewport): Flow<CalendarProjectionResult> {
        val academicBase = combine(
            observeCollection(academics.observeSemesters(), EntityKind.SEMESTER, { SemesterPut(null, it.toSemanticImage()) }) { (it as SemesterPut).after.toDomain() },
            observeCollection(academics.observeCourses(), EntityKind.COURSE, { CoursePut(null, it.toSemanticImage()) }) { (it as CoursePut).after.toDomain() },
            observeCollection(academics.observeCourseScheduleRules(), EntityKind.COURSE_SCHEDULE_RULE, { CourseScheduleRulePut(null, it.toSemanticImage()) }) { (it as CourseScheduleRulePut).after.toDomain() },
            observeCollection(academics.observePeriodTemplates(), EntityKind.PERIOD_TEMPLATE, { PeriodTemplatePut(null, it.toSemanticImage()) }) { (it as PeriodTemplatePut).after.toDomain() },
            observeCollection(academics.observeAcademicHolidays(), EntityKind.ACADEMIC_HOLIDAY, { AcademicHolidayPut(null, it.toSemanticImage()) }) { (it as AcademicHolidayPut).after.toDomain() },
        ) { semesters, courses, rules, templates, holidays ->
            CalendarAcademicFacts(semesters, courses, rules, templates, holidays)
        }
        val academic = combine(
            academicBase,
            observeCollection(academics.observeCourseOccurrenceExceptions(), EntityKind.COURSE_OCCURRENCE_EXCEPTION, { CourseOccurrenceExceptionPut(null, it.toSemanticImage()) }) { (it as CourseOccurrenceExceptionPut).after.toDomain() },
            observeCollection(academics.observeExams(), EntityKind.EXAM, { ExamPut(null, it.toSemanticImage()) }) { (it as ExamPut).after.toDomain() },
        ) { base, exceptions, exams -> base.copy(exceptions = exceptions, exams = exams) }
        return combine(
            observeCollection(events.observeAll(), EntityKind.EVENT, { EventPut(null, it.toSemanticImage()) }) { (it as EventPut).after.toDomain() },
            observeFocusBlocks(),
            academic,
        ) { eventValues, focusBlocks, snapshot ->
            val reads: List<ConflictAwareRead<*>> = listOf(eventValues, focusBlocks) + snapshot.reads()
            val failures = reads.filterIsInstance<ConflictAwareRead.Unprojectable>()
            val syncConflictRefs = reads.filterIsInstance<ConflictAwareRead.Projected<*>>()
                .flatMap { it.syncConflictRefs }
                .distinctBy { it.entityKind to it.entityId }
                .sortedWith(compareBy(SyncConflictProjectionRef::entityKind, SyncConflictProjectionRef::entityId))
            if (failures.isNotEmpty()) {
                CalendarProjectionResult(
                    emptyList<dev.agenticscheduler.application.calendar.CalendarItem>().toImmutableList(),
                    emptyList<dev.agenticscheduler.application.calendar.CalendarConflict>().toImmutableList(),
                    failures.map { CalendarProjectionIssue.SyncConflictUnprojectable(it.conflictIds, it.reason) }
                        .distinct()
                        .sortedBy { it.toString() }
                        .toImmutableList(),
                    syncConflictRefs,
                )
            } else {
                project(
                    viewport,
                    requireNotNull(eventValues.valueOrNull()),
                    requireNotNull(focusBlocks.valueOrNull()),
                    snapshot.toProjectionInput(),
                ).copy(syncConflictRefs = syncConflictRefs)
            }
        }
    }

    private fun <D, T> observeCollection(
        source: Flow<ImmutableList<D>>,
        entityKind: EntityKind,
        toMutation: (D) -> EntityMutation,
        toDomain: (EntityMutation) -> T,
    ): Flow<ConflictAwareRead<ImmutableList<T>>> = combine(source, sourceFacts.observeConflicts()) { values, _ -> values }
        .map { values -> projectCollection(entityKind, values, toMutation, toDomain) }

    private suspend fun <T, D> projectCollection(
        entityKind: EntityKind,
        durable: Collection<D>,
        toMutation: (D) -> EntityMutation,
        toDomain: (EntityMutation) -> T,
    ): ConflictAwareRead<ImmutableList<T>> = when (val projected = sourceFacts.projectCollection(entityKind, durable.map(toMutation))) {
        is ConflictCollectionProjection.Projected -> ConflictAwareRead.Projected(
            projected.mutations.map(toDomain).toImmutableList(),
            projected.syncConflictRefs,
        )
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
