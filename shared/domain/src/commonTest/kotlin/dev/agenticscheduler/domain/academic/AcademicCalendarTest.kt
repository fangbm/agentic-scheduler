package dev.agenticscheduler.domain.academic

import dev.agenticscheduler.domain.id.AcademicYearId
import dev.agenticscheduler.domain.id.SemesterId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.collections.immutable.toImmutableList

class AcademicCalendarTest {
    private val year = AcademicYear(
        AcademicYearId(id(1)),
        "2026-2027",
        LocalDate(2026, 9, 1),
        LocalDate(2027, 9, 1),
    )

    @Test
    fun `academic year and week construction enforce their local invariants`() {
        AcademicWeek(AcademicWeekNumber(1), LocalDate(2026, 9, 1), LocalDate(2026, 9, 8))
        assertFailsWith<IllegalArgumentException> { AcademicYear(year.id, "", year.startDate, year.endDateExclusive) }
        assertFailsWith<IllegalArgumentException> { AcademicYear(year.id, "x", year.startDate, year.startDate) }
        assertFailsWith<IllegalArgumentException> { AcademicYear(year.id, "x", year.endDateExclusive, year.startDate) }
        assertFailsWith<IllegalArgumentException> { AcademicWeekNumber(0) }
        assertFailsWith<IllegalArgumentException> { AcademicWeekNumber(-1) }
        assertFailsWith<IllegalArgumentException> {
            AcademicWeek(AcademicWeekNumber(1), LocalDate(2026, 9, 1), LocalDate(2026, 9, 7))
        }
        assertFailsWith<IllegalArgumentException> {
            AcademicWeek(AcademicWeekNumber(1), LocalDate(2026, 9, 1), LocalDate(2026, 9, 9))
        }
        assertFailsWith<IllegalArgumentException> {
            AcademicWeek(AcademicWeekNumber(1), LocalDate(2026, 9, 1), LocalDate(2026, 9, 1))
        }
    }

    @Test
    fun `semester preserves explicit consecutive numbered weeks while allowing date gaps`() {
        val semester = semester(
            listOf(
                week(1, 1),
                week(2, 8),
                week(3, 22),
            ),
        )
        assertEquals(listOf(1, 2, 3), semester.academicWeeks.map { it.number.value })
        assertFailsWith<IllegalArgumentException> { semester(emptyList()) }
        assertFailsWith<IllegalArgumentException> { semester(listOf(week(2, 8))) }
        assertFailsWith<IllegalArgumentException> { semester(listOf(week(1, 8), week(2, 1))) }
        assertFailsWith<IllegalArgumentException> { semester(listOf(week(1, 1), week(3, 8))) }
        assertFailsWith<IllegalArgumentException> { semester(listOf(week(1, 1), week(2, 5))) }
        assertFailsWith<IllegalArgumentException> {
            semester(listOf(AcademicWeek(AcademicWeekNumber(1), LocalDate(2026, 9, 29), LocalDate(2026, 10, 6))))
        }
        assertFailsWith<IllegalArgumentException> { semester(listOf(week(1, 1))).copy(name = " ") }
    }

    @Test
    fun `semester owns an immutable snapshot of academic weeks`() {
        val suppliedWeeks = mutableListOf(week(1, 1))
        val semester = semester(suppliedWeeks)
        suppliedWeeks.clear()
        assertEquals(listOf(1), semester.academicWeeks.map { it.number.value })
        assertEquals(semester, semester.copy())
        assertEquals(semester.hashCode(), semester.copy().hashCode())
        assertEquals(semester.toString(), semester.copy().toString())
        assertFailsWith<IllegalArgumentException> { semester.copy(academicWeeks = emptyList<AcademicWeek>().toImmutableList()) }
    }

    @Test
    fun `semester membership validator follows frozen precedence`() {
        val valid = semester(listOf(week(1, 1)))
        assertEquals(SemesterAcademicYearValidationResult.VALID, validateSemesterAgainstAcademicYear(year, valid))
        assertEquals(
            SemesterAcademicYearValidationResult.ACADEMIC_YEAR_ID_MISMATCH,
            validateSemesterAgainstAcademicYear(
                year,
                valid.copy(academicYearId = AcademicYearId(id(2))),
            ),
        )
        val shortYear = AcademicYear(year.id, year.name, LocalDate(2026, 9, 2), year.endDateExclusive)
        assertEquals(SemesterAcademicYearValidationResult.OUTSIDE_ACADEMIC_YEAR, validateSemesterAgainstAcademicYear(shortYear, valid))
        Semester(SemesterId(id(11)), year.id, "Also Fall", LocalDate(2026, 9, 1), LocalDate(2026, 10, 1), TimeZone.UTC, listOf(week(1, 1)).toImmutableList())
    }

    private fun semester(weeks: List<AcademicWeek>): Semester = Semester(
        id = SemesterId(id(10)),
        academicYearId = year.id,
        name = "Fall",
        startDate = LocalDate(2026, 9, 1),
        endDateExclusive = LocalDate(2026, 10, 1),
        timeZone = TimeZone.UTC,
        academicWeeks = weeks.toImmutableList(),
    )

    private fun week(number: Int, day: Int): AcademicWeek =
        AcademicWeek(AcademicWeekNumber(number), LocalDate(2026, 9, day), LocalDate(2026, 9, day + 7))
}

internal fun id(number: Int): String = "018f6e68-7d0c-7000-8000-${number.toString(16).padStart(12, '0')}"
