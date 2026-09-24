package dev.agenticscheduler.sync

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/** Transport-neutral, durable semantic operation. Delivery state deliberately lives outside this type. */
@Serializable
data class SyncOperation(
    val mutationId: String,
    val dvv: DvvSnapshot,
    val hlc: HlcSnapshot,
    val origin: MutationOrigin,
    val orderedMutations: List<EntityMutation>,
) {
    init { require(orderedMutations.isNotEmpty()) { "A SyncOperation must contain at least one entity mutation." } }
}

@Serializable
data class DvvSnapshot(val context: List<VersionComponent>, val dot: DotSnapshot) {
    init {
        require(context == context.sortedBy(VersionComponent::replicaId)) { "DVV context must be serialized in ReplicaId order." }
        require(context.map(VersionComponent::replicaId).distinct().size == context.size) { "DVV context replicas must be unique." }
    }
}

@Serializable data class VersionComponent(val replicaId: String, val counter: Long) { init { require(counter >= 0) } }
@Serializable data class DotSnapshot(val replicaId: String, val counter: Long) { init { require(counter >= 0) } }
@Serializable data class HlcSnapshot(val physicalMillis: Long, val logical: Long, val replicaId: String) { init { require(logical >= 0) } }

@Serializable
sealed interface MutationOrigin {
    @Serializable @SerialName("USER") data object User : MutationOrigin
    @Serializable @SerialName("PLANNER") data object Planner : MutationOrigin
    @Serializable @SerialName("SYSTEM") data object System : MutationOrigin
    /** SYN-015A explicit cross-replica conflict-resolution intent. */
    @Serializable @SerialName("CONFLICT_RESOLUTION")
    data class ConflictResolution(val conflictId: String) : MutationOrigin {
        init { require(conflictId.isNotBlank()) { "Resolved conflict id must not be blank." } }
    }
    @Serializable @SerialName("UNDO") data class Undo(val originalMutationId: String) : MutationOrigin
}

/** HST-002's frozen v1 operation vocabulary; these are semantic facts, never Room rows or SQL patches. */
@Serializable
sealed interface EntityMutation { val entityKind: EntityKind; val entityId: String }

@Serializable enum class EntityKind { EVENT, TASK, PLANNING_PROFILE, FOCUS_BLOCK, WORK_LOG, TASK_DEPENDENCY, ACADEMIC_YEAR, SEMESTER, COURSE, PERIOD_TEMPLATE, ACADEMIC_HOLIDAY, COURSE_SCHEDULE_RULE, COURSE_OCCURRENCE_EXCEPTION, EXAM }

@Serializable @SerialName("EventPut") data class EventPut(val before: EventImage?, val after: EventImage) : EntityMutation { override val entityKind = EntityKind.EVENT; override val entityId get() = after.id }
@Serializable @SerialName("TaskPut") data class TaskPut(val before: TaskImage?, val after: TaskImage) : EntityMutation { override val entityKind = EntityKind.TASK; override val entityId get() = after.id }
@Serializable @SerialName("PlanningProfilePut") data class PlanningProfilePut(val before: PlanningProfileImage?, val after: PlanningProfileImage) : EntityMutation { override val entityKind = EntityKind.PLANNING_PROFILE; override val entityId get() = after.id }
@Serializable @SerialName("FocusBlockPut") data class FocusBlockPut(val before: FocusBlockImage?, val after: FocusBlockImage) : EntityMutation { override val entityKind = EntityKind.FOCUS_BLOCK; override val entityId get() = after.id }
@Serializable @SerialName("FocusBlockDelete") data class FocusBlockDelete(val before: FocusBlockImage) : EntityMutation { override val entityKind = EntityKind.FOCUS_BLOCK; override val entityId get() = before.id }
@Serializable @SerialName("WorkLogAppend") data class WorkLogAppend(val after: WorkLogImage) : EntityMutation { override val entityKind = EntityKind.WORK_LOG; override val entityId get() = after.id }
@Serializable @SerialName("TaskDependencyPut") data class TaskDependencyPut(val before: TaskDependencyImage?, val after: TaskDependencyImage) : EntityMutation { override val entityKind = EntityKind.TASK_DEPENDENCY; override val entityId get() = after.id }
@Serializable @SerialName("AcademicYearPut") data class AcademicYearPut(val before: AcademicYearImage?, val after: AcademicYearImage) : EntityMutation { override val entityKind = EntityKind.ACADEMIC_YEAR; override val entityId get() = after.id }
@Serializable @SerialName("SemesterPut") data class SemesterPut(val before: SemesterImage?, val after: SemesterImage) : EntityMutation { override val entityKind = EntityKind.SEMESTER; override val entityId get() = after.id }
@Serializable @SerialName("CoursePut") data class CoursePut(val before: CourseImage?, val after: CourseImage) : EntityMutation { override val entityKind = EntityKind.COURSE; override val entityId get() = after.id }
@Serializable @SerialName("PeriodTemplatePut") data class PeriodTemplatePut(val before: PeriodTemplateImage?, val after: PeriodTemplateImage) : EntityMutation { override val entityKind = EntityKind.PERIOD_TEMPLATE; override val entityId get() = after.id }
@Serializable @SerialName("AcademicHolidayPut") data class AcademicHolidayPut(val before: AcademicHolidayImage?, val after: AcademicHolidayImage) : EntityMutation { override val entityKind = EntityKind.ACADEMIC_HOLIDAY; override val entityId get() = after.id }
@Serializable @SerialName("CourseScheduleRulePut") data class CourseScheduleRulePut(val before: CourseScheduleRuleImage?, val after: CourseScheduleRuleImage) : EntityMutation { override val entityKind = EntityKind.COURSE_SCHEDULE_RULE; override val entityId get() = after.id }
@Serializable @SerialName("CourseOccurrenceExceptionPut") data class CourseOccurrenceExceptionPut(val before: CourseOccurrenceExceptionImage?, val after: CourseOccurrenceExceptionImage) : EntityMutation { override val entityKind = EntityKind.COURSE_OCCURRENCE_EXCEPTION; override val entityId get() = after.id }
@Serializable @SerialName("ExamPut") data class ExamPut(val before: ExamImage?, val after: ExamImage) : EntityMutation { override val entityKind = EntityKind.EXAM; override val entityId get() = after.id }

/** Canonical typed source-fact images used by D7 history and D8 replication. */
@Serializable data class EventImage(val id: String, val title: String, val time: EventTimeImage, val flexibility: FlexibilityImage, val pinState: PinStateImage)
@Serializable data class TaskImage(val id: String, val title: String, val status: TaskStatusImage, val priority: TaskPriorityImage, val effort: TaskEffortImage, val deadline: TaskDeadlineImage?)
@Serializable data class PlanningProfileImage(val id: String, val name: String, val configuration: PlanningProfileConfigurationImage)
@Serializable data class FocusBlockImage(val id: String, val taskId: String, val time: ZonedTimeRangeImage, val flexibility: FlexibilityImage, val pinState: PinStateImage)

object LocalJournalCodec {
    const val version: Int = 1
    private val json = Json { encodeDefaults = true; classDiscriminator = "type" }
    fun encode(operation: SyncOperation): String = json.encodeToString(SyncOperation.serializer(), operation)
    fun decode(encoded: String): SyncOperation = json.decodeFromString(SyncOperation.serializer(), encoded)
    fun encodeMutation(mutation: EntityMutation): String = json.encodeToString(EntityMutation.serializer(), mutation)
    fun decodeMutation(encoded: String): EntityMutation = json.decodeFromString(EntityMutation.serializer(), encoded)
    fun encodeContext(context: Map<ReplicaId, Long>): String = json.encodeToString(ListSerializer(VersionComponent.serializer()), context.entries.sortedBy { it.key.value }.map { VersionComponent(it.key.value, it.value) })
    fun decodeContext(encoded: String): Map<ReplicaId, Long> = json.decodeFromString(ListSerializer(VersionComponent.serializer()), encoded).associate { ReplicaId(it.replicaId) to it.counter }
    fun encodeDvv(dvv: DvvSnapshot): String = json.encodeToString(DvvSnapshot.serializer(), dvv)
    fun decodeDvv(encoded: String): DvvSnapshot = json.decodeFromString(DvvSnapshot.serializer(), encoded)

    fun beforeImage(mutation: EntityMutation): String? = when (mutation) {
        is EventPut -> mutation.before?.let { json.encodeToString(EventImage.serializer(), it) }
        is TaskPut -> mutation.before?.let { json.encodeToString(TaskImage.serializer(), it) }
        is PlanningProfilePut -> mutation.before?.let { json.encodeToString(PlanningProfileImage.serializer(), it) }
        is FocusBlockPut -> mutation.before?.let { json.encodeToString(FocusBlockImage.serializer(), it) }
        is FocusBlockDelete -> json.encodeToString(FocusBlockImage.serializer(), mutation.before)
        is WorkLogAppend -> null
        is TaskDependencyPut -> mutation.before?.let { json.encodeToString(TaskDependencyImage.serializer(), it) }
        is AcademicYearPut -> mutation.before?.let { json.encodeToString(AcademicYearImage.serializer(), it) }
        is SemesterPut -> mutation.before?.let { json.encodeToString(SemesterImage.serializer(), it) }
        is CoursePut -> mutation.before?.let { json.encodeToString(CourseImage.serializer(), it) }
        is PeriodTemplatePut -> mutation.before?.let { json.encodeToString(PeriodTemplateImage.serializer(), it) }
        is AcademicHolidayPut -> mutation.before?.let { json.encodeToString(AcademicHolidayImage.serializer(), it) }
        is CourseScheduleRulePut -> mutation.before?.let { json.encodeToString(CourseScheduleRuleImage.serializer(), it) }
        is CourseOccurrenceExceptionPut -> mutation.before?.let { json.encodeToString(CourseOccurrenceExceptionImage.serializer(), it) }
        is ExamPut -> mutation.before?.let { json.encodeToString(ExamImage.serializer(), it) }
    }

    fun afterImage(mutation: EntityMutation): String? = when (mutation) {
        is EventPut -> json.encodeToString(EventImage.serializer(), mutation.after)
        is TaskPut -> json.encodeToString(TaskImage.serializer(), mutation.after)
        is PlanningProfilePut -> json.encodeToString(PlanningProfileImage.serializer(), mutation.after)
        is FocusBlockPut -> json.encodeToString(FocusBlockImage.serializer(), mutation.after)
        is FocusBlockDelete -> null
        is WorkLogAppend -> json.encodeToString(WorkLogImage.serializer(), mutation.after)
        is TaskDependencyPut -> json.encodeToString(TaskDependencyImage.serializer(), mutation.after)
        is AcademicYearPut -> json.encodeToString(AcademicYearImage.serializer(), mutation.after)
        is SemesterPut -> json.encodeToString(SemesterImage.serializer(), mutation.after)
        is CoursePut -> json.encodeToString(CourseImage.serializer(), mutation.after)
        is PeriodTemplatePut -> json.encodeToString(PeriodTemplateImage.serializer(), mutation.after)
        is AcademicHolidayPut -> json.encodeToString(AcademicHolidayImage.serializer(), mutation.after)
        is CourseScheduleRulePut -> json.encodeToString(CourseScheduleRuleImage.serializer(), mutation.after)
        is CourseOccurrenceExceptionPut -> json.encodeToString(CourseOccurrenceExceptionImage.serializer(), mutation.after)
        is ExamPut -> json.encodeToString(ExamImage.serializer(), mutation.after)
    }
}

fun EntityMutation.operationKind(): String = when (this) {
    is EventPut -> "EventPut"; is TaskPut -> "TaskPut"; is PlanningProfilePut -> "PlanningProfilePut"; is FocusBlockPut -> "FocusBlockPut"; is FocusBlockDelete -> "FocusBlockDelete"; is WorkLogAppend -> "WorkLogAppend"; is TaskDependencyPut -> "TaskDependencyPut"; is AcademicYearPut -> "AcademicYearPut"; is SemesterPut -> "SemesterPut"; is CoursePut -> "CoursePut"; is PeriodTemplatePut -> "PeriodTemplatePut"; is AcademicHolidayPut -> "AcademicHolidayPut"; is CourseScheduleRulePut -> "CourseScheduleRulePut"; is CourseOccurrenceExceptionPut -> "CourseOccurrenceExceptionPut"; is ExamPut -> "ExamPut"
}

fun DottedVersionVector.toSnapshot(): DvvSnapshot = DvvSnapshot(context.entries.map { VersionComponent(it.key.value, it.value) }, DotSnapshot(dot.replicaId.value, dot.counter))
fun HlcTimestamp.toSnapshot(): HlcSnapshot = HlcSnapshot(physicalMillis, logical, replicaId.value)
fun HlcSnapshot.toTimestamp(): HlcTimestamp = HlcTimestamp(physicalMillis, logical, ReplicaId(replicaId))
