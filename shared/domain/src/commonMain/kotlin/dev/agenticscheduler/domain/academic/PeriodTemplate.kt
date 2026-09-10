package dev.agenticscheduler.domain.academic

import dev.agenticscheduler.domain.id.PeriodTemplateId
import kotlinx.datetime.LocalTime

@JvmInline
value class AcademicPeriodNumber(val value: Int) {
    init {
        require(value >= 1) { "An academic period number must be positive." }
    }
}

data class AcademicPeriod(
    val number: AcademicPeriodNumber,
    val start: LocalTime,
    val endExclusive: LocalTime,
) {
    init {
        require(start < endExclusive) { "An academic period must have a positive local-time range." }
    }
}

data class PeriodTemplate(
    val id: PeriodTemplateId,
    val name: String,
    val periods: List<AcademicPeriod>,
) {
    init {
        require(name.isNotBlank()) { "A period template name must not be blank." }
        require(periods.isNotEmpty()) { "A period template must define periods." }

        periods.forEachIndexed { index, period ->
            if (index > 0) {
                val previous = periods[index - 1]
                require(previous.number.value < period.number.value) {
                    "Period numbers must be strictly ascending and already sorted."
                }
                require(previous.endExclusive <= period.start) {
                    "Period time ranges must not overlap."
                }
            }
        }
    }
}
