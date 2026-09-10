package dev.agenticscheduler.domain.event

import dev.agenticscheduler.domain.id.EventId
import dev.agenticscheduler.domain.planning.Flexibility
import dev.agenticscheduler.domain.planning.PinState
import dev.agenticscheduler.domain.time.TimePlacement

data class Event(
    val id: EventId,
    val title: String,
    val time: TimePlacement,
    val flexibility: Flexibility,
    val pinState: PinState,
) {
    init {
        require(title.isNotBlank()) { "An Event title must not be blank." }
    }
}
