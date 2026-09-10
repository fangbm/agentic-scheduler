package dev.agenticscheduler.domain.id

private val uuidV7Pattern = Regex(
    pattern = "^[0-9a-f]{8}-[0-9a-f]{4}-7[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$",
)

internal fun requireValidUuidV7(value: String) {
    require(uuidV7Pattern.matches(value)) { "Expected lowercase RFC UUIDv7 text." }
}

@JvmInline
value class EventId(val value: String) {
    init { requireValidUuidV7(value) }
}

@JvmInline
value class TaskId(val value: String) {
    init { requireValidUuidV7(value) }
}

@JvmInline
value class FocusBlockId(val value: String) {
    init { requireValidUuidV7(value) }
}

@JvmInline
value class WorkLogId(val value: String) {
    init { requireValidUuidV7(value) }
}

@JvmInline
value class TaskDependencyId(val value: String) {
    init { requireValidUuidV7(value) }
}

@JvmInline
value class PlanningProfileId(val value: String) {
    init { requireValidUuidV7(value) }
}

@JvmInline
value class AcademicYearId(val value: String) {
    init { requireValidUuidV7(value) }
}

@JvmInline
value class SemesterId(val value: String) {
    init { requireValidUuidV7(value) }
}

@JvmInline
value class CourseId(val value: String) {
    init { requireValidUuidV7(value) }
}

@JvmInline
value class CourseScheduleRuleId(val value: String) {
    init { requireValidUuidV7(value) }
}

@JvmInline
value class CourseOccurrenceExceptionId(val value: String) {
    init { requireValidUuidV7(value) }
}

@JvmInline
value class ExamId(val value: String) {
    init { requireValidUuidV7(value) }
}

@JvmInline
value class AcademicHolidayId(val value: String) {
    init { requireValidUuidV7(value) }
}

@JvmInline
value class PeriodTemplateId(val value: String) {
    init { requireValidUuidV7(value) }
}
