package dev.agenticscheduler.domain.task

import dev.agenticscheduler.domain.id.TaskId
import dev.agenticscheduler.domain.id.WorkLogId
import dev.agenticscheduler.domain.time.ZonedTimeRange

data class WorkLog(
    val id: WorkLogId,
    val taskId: TaskId,
    val time: ZonedTimeRange,
)
