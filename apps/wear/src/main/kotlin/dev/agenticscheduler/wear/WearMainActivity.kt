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
import dev.agenticscheduler.application.history.ConflictAwareSourceFactReadService
import dev.agenticscheduler.application.calendar.intersectsLocalDate
import dev.agenticscheduler.application.id.productionUuidV7Generator
import dev.agenticscheduler.application.history.MutationWallClock
import dev.agenticscheduler.application.sync.ActiveSyncRuntimeConfiguration
import dev.agenticscheduler.application.sync.AndroidKeystoreSecureStore
import dev.agenticscheduler.application.sync.TinkPairingHpke
import dev.agenticscheduler.database.openAndroidDatabase
import dev.agenticscheduler.database.repository.RoomAcademicRepository
import dev.agenticscheduler.database.repository.RoomEventRepository
import dev.agenticscheduler.database.repository.RoomPlanningProfileRepository
import dev.agenticscheduler.database.repository.RoomTaskRepository
import dev.agenticscheduler.database.repository.RoomD8RuntimeComposition
import dev.agenticscheduler.sync.AccountId
import kotlinx.collections.immutable.toImmutableList
import kotlin.time.Clock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.TimeZone
import kotlinx.datetime.plus
import kotlinx.datetime.toLocalDateTime

class WearMainActivity : ComponentActivity() {
    private val database by lazy { openAndroidDatabase(this) }
    private val d8Runtime by lazy {
        RoomD8RuntimeComposition(
            database,
            AndroidKeystoreSecureStore(this),
            TinkPairingHpke(),
            productionUuidV7Generator(),
            MutationWallClock { Clock.System.now().toEpochMilliseconds() },
        )
    }
    private val d8Scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val calendarQueryService: CalendarQueryService by lazy {
        ConflictAwareSourceFactReadService(
            RoomEventRepository(database),
            RoomTaskRepository(database),
            RoomPlanningProfileRepository(database),
            RoomAcademicRepository(database),
            d8Runtime.sourceFacts,
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        d8RuntimeConfigurationOrNull()?.let { configuration ->
            d8Scope.launch {
                d8Runtime.activate(configuration)
                d8Runtime.catchUp()
            }
        }
        setContent {
            MaterialTheme {
                WearAgenda(calendarQueryService)
            }
        }
    }

    override fun onDestroy() {
        d8Scope.cancel()
        super.onDestroy()
    }

    private fun d8RuntimeConfigurationOrNull(): ActiveSyncRuntimeConfiguration? {
        val metadata = packageManager.getApplicationInfo(packageName, android.content.pm.PackageManager.GET_META_DATA).metaData
        val baseUrl = metadata?.getString(D8_SYNC_BASE_URL)?.trim().orEmpty()
        val accountId = metadata?.getString(D8_SYNC_ACCOUNT_ID)?.trim().orEmpty()
        if (baseUrl.isEmpty() && accountId.isEmpty()) return null
        require(baseUrl.isNotEmpty() && accountId.isNotEmpty()) { "D8 sync manifest configuration requires both base URL and account ID." }
        return ActiveSyncRuntimeConfiguration(AccountId(accountId), baseUrl)
    }

    private companion object {
        const val D8_SYNC_BASE_URL = "dev.agenticscheduler.sync.BASE_URL"
        const val D8_SYNC_ACCOUNT_ID = "dev.agenticscheduler.sync.ACCOUNT_ID"
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
    val todayItems = result.items.filter { it.intersectsLocalDate(today, displayTimeZone) }.take(3)
    val upcomingItems = result.items.filterNot { it.intersectsLocalDate(today, displayTimeZone) }.take(3)
    val todayText = todayItems.joinToString { it.title }.ifEmpty { "None" }
    val upcomingText = upcomingItems.joinToString { it.title }.ifEmpty { "None" }
    Text("Today\n$todayText\nUpcoming\n$upcomingText${if (result.conflicts.isNotEmpty()) "\nConflict" else ""}")
}

private fun emptyProjection() = CalendarProjectionResult(
    emptyList<CalendarItem>().toImmutableList(),
    emptyList<CalendarConflict>().toImmutableList(),
    emptyList<CalendarProjectionIssue>().toImmutableList(),
)
