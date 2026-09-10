package dev.agenticscheduler.domain.planning

import kotlin.time.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone

sealed interface Deadline {
    data class Exact(
        val at: Instant,
        val timeZone: TimeZone,
    ) : Deadline

    data class DateOnly(
        val date: LocalDate,
    ) : Deadline
}

enum class DeadlinePolicy {
    NORMAL,
    HARD,
}

enum class OverflowPolicy {
    NEVER,
    ASK,
    ALLOW,
}

data class TaskDeadline(
    val deadline: Deadline,
    val policy: DeadlinePolicy,
    val overflowPolicy: OverflowPolicy,
)
