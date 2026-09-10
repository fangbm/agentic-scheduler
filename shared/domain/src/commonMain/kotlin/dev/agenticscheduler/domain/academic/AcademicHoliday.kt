package dev.agenticscheduler.domain.academic

import dev.agenticscheduler.domain.id.AcademicHolidayId
import dev.agenticscheduler.domain.id.SemesterId
import dev.agenticscheduler.domain.time.AllDayRange

enum class AcademicHolidayTeachingEffect {
    NO_EFFECT,
    SUSPEND_TEACHING,
}

data class AcademicHoliday(
    val id: AcademicHolidayId,
    val semesterId: SemesterId,
    val name: String,
    val dates: AllDayRange,
    val teachingEffect: AcademicHolidayTeachingEffect,
) {
    init {
        require(name.isNotBlank()) { "An academic holiday name must not be blank." }
    }
}
