package dev.agenticscheduler.domain.academic

import dev.agenticscheduler.domain.id.AcademicYearId
import dev.agenticscheduler.domain.id.CourseId
import dev.agenticscheduler.domain.id.ExamId
import dev.agenticscheduler.domain.id.SemesterId
import dev.agenticscheduler.domain.time.ZonedTimeRange
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlin.time.Instant

class ExamTest {
    private val semester = Semester(
        SemesterId(id(200)), AcademicYearId(id(201)), "Fall", LocalDate(2026, 9, 1), LocalDate(2026, 10, 1), TimeZone.UTC,
        listOf(AcademicWeek(AcademicWeekNumber(1), LocalDate(2026, 9, 1), LocalDate(2026, 9, 8))),
    )
    private val course = Course(CourseId(id(202)), semester.id, "Algorithms", null)

    @Test
    fun `exam schedule states are explicit and title is required`() {
        assertEquals(ExamValidationResult.VALID, validateExamAgainstSemester(exam(ExamSchedule.Unscheduled), semester, null))
        assertEquals(
            ExamValidationResult.VALID,
            validateExamAgainstSemester(exam(ExamSchedule.DateOnly(LocalDate(2026, 9, 10))), semester, null),
        )
        assertEquals(
            ExamValidationResult.VALID,
            validateExamAgainstSemester(exam(ExamSchedule.Exact(exact("2026-09-10T08:00:00Z"))), semester, null),
        )
        assertFailsWith<IllegalArgumentException> { exam(ExamSchedule.Unscheduled).copy(title = " ") }
    }

    @Test
    fun `exam validator follows frozen failure precedence`() {
        val standard = exam(ExamSchedule.Unscheduled, course.id)
        assertEquals(
            ExamValidationResult.EXAM_SEMESTER_MISMATCH,
            validateExamAgainstSemester(standard.copy(semesterId = SemesterId(id(203))), semester, null),
        )
        assertEquals(ExamValidationResult.LINKED_COURSE_REQUIRED, validateExamAgainstSemester(standard, semester, null))
        assertEquals(
            ExamValidationResult.LINKED_COURSE_ID_MISMATCH,
            validateExamAgainstSemester(standard, semester, course.copy(id = CourseId(id(204)))),
        )
        assertEquals(
            ExamValidationResult.LINKED_COURSE_SEMESTER_MISMATCH,
            validateExamAgainstSemester(standard, semester, course.copy(semesterId = SemesterId(id(205)))),
        )
        assertEquals(ExamValidationResult.VALID, validateExamAgainstSemester(standard, semester, course))
        assertEquals(
            ExamValidationResult.SCHEDULE_OUTSIDE_SEMESTER,
            validateExamAgainstSemester(exam(ExamSchedule.DateOnly(LocalDate(2026, 10, 1))), semester, null),
        )
        assertEquals(
            ExamValidationResult.TIME_ZONE_MISMATCH,
            validateExamAgainstSemester(
                exam(ExamSchedule.Exact(ZonedTimeRange(
                    Instant.parse("2026-09-10T08:00:00Z"), Instant.parse("2026-09-10T09:00:00Z"), TimeZone.of("Asia/Shanghai"),
                ))),
                semester,
                null,
            ),
        )
        assertEquals(
            ExamValidationResult.TIME_ZONE_MISMATCH,
            validateExamAgainstSemester(
                exam(ExamSchedule.Exact(ZonedTimeRange(
                    Instant.parse("2026-10-01T00:00:00Z"), Instant.parse("2026-10-01T01:00:00Z"), TimeZone.of("Asia/Shanghai"),
                ))),
                semester,
                null,
            ),
        )
        assertEquals(
            ExamValidationResult.SCHEDULE_OUTSIDE_SEMESTER,
            validateExamAgainstSemester(exam(ExamSchedule.Exact(exact("2026-10-01T00:00:00Z"))), semester, null),
        )
        assertEquals(
            ExamValidationResult.VALID,
            validateExamAgainstSemester(exam(ExamSchedule.Unscheduled), semester, course.copy(semesterId = SemesterId(id(205)))),
        )
    }

    private fun exam(schedule: ExamSchedule, courseId: CourseId? = null): Exam =
        Exam(ExamId(id(210)), semester.id, courseId, "Final", schedule)

    private fun exact(start: String): ZonedTimeRange = ZonedTimeRange(
        Instant.parse(start),
        Instant.parse(start).plus(kotlin.time.Duration.parse("1h")),
        TimeZone.UTC,
    )
}
