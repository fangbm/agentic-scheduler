package dev.agenticscheduler.wear

import android.os.Bundle
import android.net.ConnectivityManager
import android.net.Network
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.Text
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import dev.agenticscheduler.application.calendar.CalendarItem
import dev.agenticscheduler.application.calendar.CalendarConflict
import dev.agenticscheduler.application.calendar.CalendarProjectionIssue
import dev.agenticscheduler.application.calendar.CalendarProjectionResult
import dev.agenticscheduler.application.calendar.CalendarViewport
import dev.agenticscheduler.application.history.ConflictAwareRead
import dev.agenticscheduler.application.history.ConflictAwareSourceFactReadService
import dev.agenticscheduler.application.calendar.intersectsLocalDate
import dev.agenticscheduler.application.id.productionUuidV7Generator
import dev.agenticscheduler.application.history.MutationWallClock
import dev.agenticscheduler.application.sync.ActiveSyncRuntimeConfiguration
import dev.agenticscheduler.application.sync.ActiveSyncRuntimeCreation
import dev.agenticscheduler.application.sync.ActiveSyncCatchUpTrigger
import dev.agenticscheduler.application.sync.AndroidKeystoreSecureStore
import dev.agenticscheduler.application.sync.TinkPairingHpke
import dev.agenticscheduler.database.openAndroidDatabase
import dev.agenticscheduler.database.repository.RoomAcademicRepository
import dev.agenticscheduler.database.repository.RoomEventRepository
import dev.agenticscheduler.database.repository.RoomPlanningProfileRepository
import dev.agenticscheduler.database.repository.RoomTaskRepository
import dev.agenticscheduler.database.repository.RoomD8RuntimeComposition
import dev.agenticscheduler.domain.task.Task
import dev.agenticscheduler.sync.AccountId
import kotlinx.collections.immutable.toImmutableList
import kotlin.time.Clock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.TimeZone
import kotlinx.datetime.plus
import kotlinx.datetime.toLocalDateTime

class WearMainActivity : ComponentActivity() {
    private val d8StartupState = mutableStateOf(D8StartupState.Activating)
    private val database by lazy { openAndroidDatabase(this) }
    private val d8RuntimeLazy = lazy {
        RoomD8RuntimeComposition(
            database,
            AndroidKeystoreSecureStore(this),
            TinkPairingHpke(),
            productionUuidV7Generator(),
            MutationWallClock { Clock.System.now().toEpochMilliseconds() },
        )
    }
    private val d8Runtime by d8RuntimeLazy
    private val d8Scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val d8ShutdownScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile private var d8SyncTrigger: ActiveSyncCatchUpTrigger? = null
    private var d8NetworkCallback: ConnectivityManager.NetworkCallback? = null
    private val calendarQueryService: ConflictAwareSourceFactReadService by lazy {
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
        val configuration = try {
            d8RuntimeConfigurationOrNull()
        } catch (_: Throwable) {
            d8StartupState.value = D8StartupState.Blocked
            null
        }
        if (d8StartupState.value != D8StartupState.Blocked) {
            d8StartupState.value = D8StartupState.Activating
            d8Scope.launch {
                try {
                    val creation = configuration?.let { d8Runtime.activate(it) }
                        ?: d8Runtime.activateWithoutConfiguration()
                    when (creation) {
                        is ActiveSyncRuntimeCreation.Active -> {
                            val trigger = d8Runtime.newCatchUpTrigger(
                                d8Scope,
                                onUnexpectedFailure = { android.util.Log.w("D8Sync", "Catch-up failed unexpectedly; retry remains scheduled.") },
                            )
                            d8SyncTrigger = trigger
                            trigger.start()
                            registerNetworkRetry(trigger)
                            d8StartupState.value = D8StartupState.Ready
                        }
                        is ActiveSyncRuntimeCreation.ActiveEnrollmentOffline -> d8StartupState.value = D8StartupState.Ready
                        ActiveSyncRuntimeCreation.NoEnrollment -> d8StartupState.value = D8StartupState.Ready
                        is ActiveSyncRuntimeCreation.MultipleActiveEnrollments -> d8StartupState.value = D8StartupState.Blocked
                        ActiveSyncRuntimeCreation.EnrollmentNotActive,
                        is ActiveSyncRuntimeCreation.ActiveEnrollmentAccountMismatch,
                        is ActiveSyncRuntimeCreation.MissingDeviceCredential,
                        -> d8StartupState.value = D8StartupState.Blocked
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Throwable) {
                    d8StartupState.value = D8StartupState.Blocked
                }
            }
        }
        setContent {
            val startupState by d8StartupState
            MaterialTheme {
                when (startupState) {
                    D8StartupState.Ready -> WearAgenda(calendarQueryService)
                    D8StartupState.Activating -> Text("Connecting to your secure sync space…")
                    D8StartupState.Blocked -> Text("Sync setup is unavailable. Restore the device credential or check the configured account and server.")
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        d8SyncTrigger?.requestCatchUp()
    }

    override fun onDestroy() {
        d8NetworkCallback?.let { callback ->
            runCatching { getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(callback) }
        }
        d8NetworkCallback = null
        d8SyncTrigger?.close()
        d8SyncTrigger = null
        d8Scope.cancel()
        if (d8RuntimeLazy.isInitialized()) {
            d8ShutdownScope.launch {
                try {
                    d8Runtime.deactivate()
                } finally {
                    d8ShutdownScope.cancel()
                }
            }
        }
        super.onDestroy()
    }

    private fun registerNetworkRetry(trigger: ActiveSyncCatchUpTrigger) {
        val connectivity = getSystemService(ConnectivityManager::class.java)
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                trigger.requestCatchUp()
            }
        }
        connectivity.registerDefaultNetworkCallback(callback)
        d8NetworkCallback = callback
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

private enum class D8StartupState { Activating, Ready, Blocked }

@androidx.compose.runtime.Composable
private fun WearAgenda(service: ConflictAwareSourceFactReadService) {
    val displayTimeZone = remember { TimeZone.currentSystemDefault() }
    val today = Clock.System.now().toLocalDateTime(displayTimeZone).date
    val viewport = remember(today, displayTimeZone) {
        CalendarViewport(today, today.plus(7, DateTimeUnit.DAY), displayTimeZone)
    }
    val projection = remember(service, viewport) { service.observe(viewport) }
    val result by projection.collectAsState(emptyProjection())
    val taskRead by remember(service) { service.observeTasks() }.collectAsState(
        ConflictAwareRead.Projected(emptyList<Task>().toImmutableList()),
    )
    val todayItems = result.items.filter { it.intersectsLocalDate(today, displayTimeZone) }.take(3)
    val upcomingItems = result.items.filterNot { it.intersectsLocalDate(today, displayTimeZone) }.take(3)
    val todayText = todayItems.joinToString { it.title }.ifEmpty { "None" }
    val upcomingText = upcomingItems.joinToString { it.title }.ifEmpty { "None" }
    val taskSyncConflictRefs = (taskRead as? ConflictAwareRead.Projected<*>)?.syncConflictRefs.orEmpty()
    val syncConflictCount = (result.syncConflictRefs + taskSyncConflictRefs)
        .flatMap { it.conflictIds }
        .distinct()
        .size
    val calendarOverlap = if (result.conflicts.isNotEmpty()) "\nCalendar overlaps: ${result.conflicts.size}" else ""
    val syncConflicts = if (syncConflictCount > 0) "\nSync conflicts: $syncConflictCount" else ""
    Text("Today\n$todayText\nUpcoming\n$upcomingText$calendarOverlap$syncConflicts")
}

private fun emptyProjection() = CalendarProjectionResult(
    emptyList<CalendarItem>().toImmutableList(),
    emptyList<CalendarConflict>().toImmutableList(),
    emptyList<CalendarProjectionIssue>().toImmutableList(),
)
