package dev.agenticscheduler.android

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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

class MainActivity : ComponentActivity() {
    private val calendarQueryService: CalendarQueryService by lazy {
        val database = openAndroidDatabase(this)
        RepositoryCalendarQueryService(RoomEventRepository(database), RoomTaskRepository(database), RoomAcademicRepository(database))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface {
                    AndroidAgenda(calendarQueryService)
                }
            }
        }
    }
}

@androidx.compose.runtime.Composable
private fun AndroidAgenda(service: CalendarQueryService) {
    val viewport = CalendarViewport(LocalDate(2026, 9, 11), LocalDate(2026, 9, 12), TimeZone.of("Asia/Shanghai"))
    val result by service.observe(viewport).collectAsState(CalendarProjectionResult(emptyList<CalendarItem>().toImmutableList(), emptyList<CalendarConflict>().toImmutableList(), emptyList<CalendarProjectionIssue>().toImmutableList()))
    LazyColumn {
        item {
        Text("Agenda / Day")
        Text("All-day: ${result.items.count { it is CalendarItem.AllDay || it is CalendarItem.DateOnly }}")
        }
        items(result.items, key = { it.source.toString() }) { item -> Text(item.title + if (item is CalendarItem.Floating) " (floating)" else "") }
        item {
        if (result.conflicts.isNotEmpty()) Text("${result.conflicts.size} conflict(s)")
        if (result.issues.isNotEmpty()) Text("${result.issues.size} projection issue(s)")
        }
    }
}
