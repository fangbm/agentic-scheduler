package dev.agenticscheduler.wear

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.Text
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
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
    val displayTimeZone = remember { TimeZone.currentSystemDefault() }
    val today = Clock.System.now().toLocalDateTime(displayTimeZone).date
    val viewport = remember(today, displayTimeZone) {
        CalendarViewport(today, today.plus(7, DateTimeUnit.DAY), displayTimeZone)
    }
    val projection = remember(service, viewport) { service.observe(viewport) }
    val result by projection.collectAsState(emptyProjection())
    val todayItems = result.items.filter { it.isOn(today, displayTimeZone) }.take(3)
    val upcomingItems = result.items.filterNot { it.isOn(today, displayTimeZone) }.take(3)
    val todayText = todayItems.joinToString { it.title }.ifEmpty { "None" }
    val upcomingText = upcomingItems.joinToString { it.title }.ifEmpty { "None" }
    Text("Today\n$todayText\nUpcoming\n$upcomingText${if (result.conflicts.isNotEmpty()) "\nConflict" else ""}")
}

private fun CalendarItem.isOn(date: kotlinx.datetime.LocalDate, displayTimeZone: TimeZone): Boolean = when (this) {
    is CalendarItem.Zoned -> originalRange.start.toLocalDateTime(displayTimeZone).date == date
    is CalendarItem.AllDay -> range.startDate <= date && date < range.endDateExclusive
    is CalendarItem.Floating -> range.start.date == date
    is CalendarItem.DateOnly -> this.date == date
}

private fun emptyProjection() = CalendarProjectionResult(
    emptyList<CalendarItem>().toImmutableList(),
    emptyList<CalendarConflict>().toImmutableList(),
    emptyList<CalendarProjectionIssue>().toImmutableList(),
)
