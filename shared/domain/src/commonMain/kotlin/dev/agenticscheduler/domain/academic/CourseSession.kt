package dev.agenticscheduler.domain.academic

import dev.agenticscheduler.domain.id.CourseId
import dev.agenticscheduler.domain.id.CourseOccurrenceExceptionId
import dev.agenticscheduler.domain.time.ZonedTimeRange

data class CourseOccurrenceException(
    val id: CourseOccurrenceExceptionId,
    val key: CourseOccurrenceKey,
    val disposition: CourseOccurrenceDisposition,
    val timeOverride: ZonedTimeRange?,
    val roomOverride: RoomOverride,
) {
    init {
        if (disposition == CourseOccurrenceDisposition.CANCELLED) {
            require(timeOverride == null) { "A cancelled occurrence must not have a time override." }
            require(roomOverride == RoomOverride.Unchanged) {
                "A cancelled occurrence must not have a room override."
            }
        }
    }
}

enum class CourseCancellationReason {
    ACADEMIC_HOLIDAY,
    EXPLICIT_EXCEPTION,
}

sealed interface CourseSessionState {
    data class Scheduled(
        val time: ZonedTimeRange,
        val room: String?,
    ) : CourseSessionState {
        init {
            require(room == null || room.isNotBlank()) { "A room must be null or non-blank." }
        }
    }

    data class Cancelled(
        val reason: CourseCancellationReason,
    ) : CourseSessionState
}

data class CourseSession(
    val key: CourseOccurrenceKey,
    val courseId: CourseId,
    val baseTime: ZonedTimeRange,
    val state: CourseSessionState,
)
