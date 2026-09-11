package dev.agenticscheduler.wear

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.Text
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import dev.agenticscheduler.application.calendar.CalendarItem
import dev.agenticscheduler.application.calendar.CalendarConflict
import dev.agenticscheduler.application.calendar.CalendarProjectionIssue
import dev.agenticscheduler.application.calendar.CalendarProjectionResult
import dev.agenticscheduler.application.calendar.CalendarQueryService
import dev.agenticscheduler.application.calendar.CalendarViewport
import dev.agenticscheduler.application.calendar.RepositoryCalendarQueryService
import dev.agenticscheduler.database.openAndroidDatabase
import dev.agenticscheduler.database.repository.RoomAcademicRepository
import dev.agenticscheduler.database.repository.RoomEventRepository
import dev.agenticscheduler.database.repository.RoomTaskRepository
import kotlinx.collections.immutable.toImmutableList
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone

class WearMainActivity : ComponentActivity() {
    private val calendarQueryService: CalendarQueryService by lazy {
        val database = openAndroidDatabase(this)
        RepositoryCalendarQueryService(RoomEventRepository(database), RoomTaskRepository(database), RoomAcademicRepository(database))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                WearAgenda(calendarQueryService)
            }
        }
    }
}

@androidx.compose.runtime.Composable
private fun WearAgenda(service: CalendarQueryService) {
    val viewport = CalendarViewport(LocalDate(2026, 9, 11), LocalDate(2026, 9, 12), TimeZone.of("Asia/Shanghai"))
    val result by service.observe(viewport).collectAsState(CalendarProjectionResult(emptyList<CalendarItem>().toImmutableList(), emptyList<CalendarConflict>().toImmutableList(), emptyList<CalendarProjectionIssue>().toImmutableList()))
    Text("Today: ${result.items.take(3).joinToString { it.title }}${if (result.conflicts.isNotEmpty()) " • conflict" else ""}")
}
