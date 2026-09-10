package dev.agenticscheduler.domain.academic

import dev.agenticscheduler.domain.id.CourseId
import dev.agenticscheduler.domain.id.ExamId
import dev.agenticscheduler.domain.id.SemesterId
import dev.agenticscheduler.domain.time.ZonedTimeRange
import kotlinx.datetime.LocalDate

sealed interface ExamSchedule {
    data object Unscheduled : ExamSchedule

    data class DateOnly(
        val date: LocalDate,
    ) : ExamSchedule

    data class Exact(
        val time: ZonedTimeRange,
    ) : ExamSchedule
}

data class Exam(
    val id: ExamId,
    val semesterId: SemesterId,
    val courseId: CourseId?,
    val title: String,
    val schedule: ExamSchedule,
) {
    init {
        require(title.isNotBlank()) { "An exam title must not be blank." }
    }
}

enum class ExamValidationResult {
    VALID,
    EXAM_SEMESTER_MISMATCH,
    LINKED_COURSE_REQUIRED,
    LINKED_COURSE_ID_MISMATCH,
    LINKED_COURSE_SEMESTER_MISMATCH,
    SCHEDULE_OUTSIDE_SEMESTER,
    TIME_ZONE_MISMATCH,
}

fun validateExamAgainstSemester(
    exam: Exam,
    semester: Semester,
    linkedCourse: Course?,
): ExamValidationResult {
    if (exam.semesterId != semester.id) return ExamValidationResult.EXAM_SEMESTER_MISMATCH
    if (exam.courseId != null && linkedCourse == null) return ExamValidationResult.LINKED_COURSE_REQUIRED
    if (exam.courseId != null && linkedCourse!!.id != exam.courseId) return ExamValidationResult.LINKED_COURSE_ID_MISMATCH
    if (exam.courseId != null && linkedCourse != null && linkedCourse.semesterId != exam.semesterId) {
        return ExamValidationResult.LINKED_COURSE_SEMESTER_MISMATCH
    }

    return when (val schedule = exam.schedule) {
        ExamSchedule.Unscheduled -> ExamValidationResult.VALID
        is ExamSchedule.DateOnly -> if (semester.containsLocalDate(schedule.date)) {
            ExamValidationResult.VALID
        } else {
            ExamValidationResult.SCHEDULE_OUTSIDE_SEMESTER
        }
        is ExamSchedule.Exact -> when {
            !semester.containsExactRange(schedule.time.start, schedule.time.endExclusive) ->
                ExamValidationResult.SCHEDULE_OUTSIDE_SEMESTER
            schedule.time.timeZone != semester.timeZone -> ExamValidationResult.TIME_ZONE_MISMATCH
            else -> ExamValidationResult.VALID
        }
    }
}
