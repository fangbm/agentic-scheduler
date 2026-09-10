package dev.agenticscheduler.domain.planning

import dev.agenticscheduler.domain.id.PlanningProfileId

data class PlanningProfile(
    val id: PlanningProfileId,
    val name: String,
) {
    init {
        require(name.isNotBlank()) { "A PlanningProfile name must not be blank." }
    }
}
