package dev.agenticscheduler.domain.task

import dev.agenticscheduler.domain.id.FocusBlockId
import dev.agenticscheduler.domain.id.TaskId
import dev.agenticscheduler.domain.planning.Flexibility
import dev.agenticscheduler.domain.planning.PinState
import dev.agenticscheduler.domain.time.ZonedTimeRange

data class FocusBlock(
    val id: FocusBlockId,
    val taskId: TaskId,
    val time: ZonedTimeRange,
    val flexibility: Flexibility,
    val pinState: PinState,
)
