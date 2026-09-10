package dev.agenticscheduler.domain.academic

import dev.agenticscheduler.domain.id.CourseId
import dev.agenticscheduler.domain.id.SemesterId

data class Course(
    val id: CourseId,
    val semesterId: SemesterId,
    val name: String,
    val code: String?,
) {
    init {
        require(name.isNotBlank()) { "A course name must not be blank." }
        require(code == null || code.isNotBlank()) { "A course code must be null or non-blank." }
    }
}
