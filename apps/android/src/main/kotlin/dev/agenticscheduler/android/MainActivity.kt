package dev.agenticscheduler.android

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Button
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
import kotlin.time.Clock
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.TimeZone
import kotlinx.datetime.plus
import kotlinx.datetime.toLocalDateTime

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
    val displayTimeZone = remember { TimeZone.currentSystemDefault() }
    var selectedDate by remember {
        mutableStateOf(Clock.System.now().toLocalDateTime(displayTimeZone).date)
    }
    val viewport = remember(selectedDate, displayTimeZone) {
        CalendarViewport(selectedDate, selectedDate.plus(1, DateTimeUnit.DAY), displayTimeZone)
    }
    val projection = remember(service, viewport) { service.observe(viewport) }
    val result by projection.collectAsState(emptyProjection())
    val dateItems = result.items.filter { it is CalendarItem.AllDay || it is CalendarItem.DateOnly }
    val timedItems = result.items.filterNot { it is CalendarItem.AllDay || it is CalendarItem.DateOnly }

    LazyColumn {
        item {
            Text("Agenda / Day: $selectedDate")
            Row {
                Button(onClick = { selectedDate = selectedDate.plus(-1, DateTimeUnit.DAY) }) { Text("Previous") }
                Button(onClick = { selectedDate = selectedDate.plus(1, DateTimeUnit.DAY) }) { Text("Next") }
            }
        }
        item { Text("All-day / date-only") }
        items(dateItems, key = { it.source.toString() }) { item -> Text(item.title) }
        item { Text("Timed / floating") }
        items(timedItems, key = { it.source.toString() }) { item -> Text(item.title + if (item is CalendarItem.Floating) " (floating)" else "") }
        item {
            if (result.conflicts.isNotEmpty()) Text("${result.conflicts.size} conflict(s)")
            if (result.issues.isNotEmpty()) Text("${result.issues.size} projection issue(s)")
        }
    }
}

private fun emptyProjection() = CalendarProjectionResult(
    emptyList<CalendarItem>().toImmutableList(),
    emptyList<CalendarConflict>().toImmutableList(),
    emptyList<CalendarProjectionIssue>().toImmutableList(),
)
