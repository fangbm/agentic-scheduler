package dev.agenticscheduler.android

import android.os.Bundle
import android.net.ConnectivityManager
import android.net.Network
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.text.input.PasswordVisualTransformation
import dev.agenticscheduler.application.calendar.CalendarConflict
import dev.agenticscheduler.application.calendar.CalendarItem
import dev.agenticscheduler.application.calendar.CalendarProjectionIssue
import dev.agenticscheduler.application.calendar.CalendarProjectionResult
import dev.agenticscheduler.application.calendar.CalendarSourceRef
import dev.agenticscheduler.application.calendar.CalendarViewport
import dev.agenticscheduler.application.editing.CreateEventInput
import dev.agenticscheduler.application.editing.CreateTaskInput
import dev.agenticscheduler.application.editing.EditingResult
import dev.agenticscheduler.application.editing.EventEditingService
import dev.agenticscheduler.application.editing.EventTimeInput
import dev.agenticscheduler.application.editing.TaskDeadlineInput
import dev.agenticscheduler.application.editing.TaskEditingService
import dev.agenticscheduler.application.editing.UpdateEventInput
import dev.agenticscheduler.application.editing.UpdateTaskInput
import dev.agenticscheduler.application.id.productionUuidV7Generator
import dev.agenticscheduler.application.history.MutationCoordinator
import dev.agenticscheduler.application.history.AgentOriginWriteGate
import dev.agenticscheduler.application.history.HistoryQueryService
import dev.agenticscheduler.application.history.UndoService
import dev.agenticscheduler.application.history.MutationWallClock
import dev.agenticscheduler.application.history.ConflictAwareRead
import dev.agenticscheduler.application.history.ConflictAwareSourceFactReadService
import dev.agenticscheduler.application.planner.DogfoodPlannerService
import dev.agenticscheduler.application.planner.PlanBranch
import dev.agenticscheduler.application.planner.PlanBranchApplyResult
import dev.agenticscheduler.application.planner.PlannerPreview
import dev.agenticscheduler.application.planner.PlanningProfileSettingsService
import dev.agenticscheduler.database.openAndroidDatabase
import dev.agenticscheduler.database.repository.RoomAcademicRepository
import dev.agenticscheduler.database.repository.RoomApplicationTransactionRunner
import dev.agenticscheduler.database.repository.RoomEventRepository
import dev.agenticscheduler.database.repository.RoomMutationJournalRepository
import dev.agenticscheduler.database.repository.RoomD8RuntimeComposition
import dev.agenticscheduler.database.repository.RoomPlanningProfileRepository
import dev.agenticscheduler.database.repository.RoomTaskRepository
import dev.agenticscheduler.domain.event.Event
import dev.agenticscheduler.domain.id.FocusBlockId
import dev.agenticscheduler.domain.id.PlanningProfileId
import dev.agenticscheduler.domain.planning.AllDayEventPolicy
import dev.agenticscheduler.domain.planning.Deadline
import dev.agenticscheduler.domain.planning.DeadlinePolicy
import dev.agenticscheduler.domain.planning.Flexibility
import dev.agenticscheduler.domain.planning.OverflowPolicy
import dev.agenticscheduler.domain.planning.PinState
import dev.agenticscheduler.domain.planning.PlanningProfile
import dev.agenticscheduler.domain.planning.PlanningProfileConfiguration
import dev.agenticscheduler.domain.planning.WeeklyAvailabilityWindow
import dev.agenticscheduler.domain.task.Task
import dev.agenticscheduler.domain.task.TaskPriority
import dev.agenticscheduler.domain.task.TaskStatus
import dev.agenticscheduler.domain.time.AllDayRange
import dev.agenticscheduler.domain.time.FloatingTimeRange
import dev.agenticscheduler.domain.time.ZonedTimeRange
import dev.agenticscheduler.application.sync.ActiveSyncRuntimeConfiguration
import dev.agenticscheduler.application.sync.ActiveSyncRuntimeCreation
import dev.agenticscheduler.application.sync.ActiveSyncCatchUpTrigger
import dev.agenticscheduler.application.sync.AgentOutboundCompatibilityGate
import dev.agenticscheduler.application.sync.AndroidKeystoreSecureStore
import dev.agenticscheduler.application.sync.LocalEnrollmentRepository
import dev.agenticscheduler.application.sync.LocalEnrollmentState
import dev.agenticscheduler.application.sync.PlatformSecretMaterial
import dev.agenticscheduler.application.sync.PlatformSecretStore
import dev.agenticscheduler.application.sync.SecretReference
import dev.agenticscheduler.application.sync.TinkPairingHpke
import dev.agenticscheduler.sync.AccountId
import dev.agenticscheduler.agent.history.AgentMessage
import dev.agenticscheduler.agent.history.AgentStateRepository
import dev.agenticscheduler.agent.history.AgentThreadId
import dev.agenticscheduler.agent.history.AgentToolCall
import dev.agenticscheduler.agent.history.AgentToolCallState
import dev.agenticscheduler.agent.history.AgentToolResult
import dev.agenticscheduler.agent.history.ProviderConfig
import dev.agenticscheduler.agent.history.ProviderConfigId
import dev.agenticscheduler.agent.permission.AgentSyncWriteGate
import dev.agenticscheduler.agent.provider.OpenAiCompatibleProvider
import dev.agenticscheduler.agent.provider.SecretStoreProviderCredentialResolver
import dev.agenticscheduler.agent.runtime.AgentClock
import dev.agenticscheduler.agent.runtime.AgentRunService
import dev.agenticscheduler.agent.runtime.AgentRunResult
import dev.agenticscheduler.agent.tool.*
import dev.agenticscheduler.database.repository.RoomAgentStateRepository
import dev.agenticscheduler.database.repository.RoomLocalEnrollmentRepository
import io.ktor.client.HttpClient
import io.ktor.client.engine.android.Android
import dev.agenticscheduler.planner.LocalReflowRequest
import dev.agenticscheduler.planner.PlanningHorizon
import kotlinx.collections.immutable.toImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.CancellationException
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.plus
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Clock
import kotlin.time.Duration

class MainActivity : ComponentActivity() {
    private val d8StartupState = mutableStateOf(D8StartupState.Activating)
    private val d8SyncStoppedReason = mutableStateOf<String?>(null)
    private val database by lazy { openAndroidDatabase(this) }
    private val events by lazy { RoomEventRepository(database) }
    private val tasks by lazy { RoomTaskRepository(database) }
    private val transactionRunner by lazy { RoomApplicationTransactionRunner(database) }
    private val ids by lazy { productionUuidV7Generator() }
    private val secureStore by lazy { AndroidKeystoreSecureStore(this) }
    private val academics by lazy { RoomAcademicRepository(database) }
    private val profiles by lazy { RoomPlanningProfileRepository(database) }
    private val agentState by lazy { RoomAgentStateRepository(database) }
    private val localEnrollments by lazy { RoomLocalEnrollmentRepository(database) }
    private val agentWriteGate by lazy { AndroidActiveEnrollmentAgentWriteGate(localEnrollments, agentState) }
    private val mutations by lazy {
        MutationCoordinator(
            transactionRunner,
            RoomMutationJournalRepository(database),
            ids,
            MutationWallClock { Clock.System.now().toEpochMilliseconds() },
            agentWriteGate,
        )
    }
    private val d8RuntimeLazy = lazy {
        RoomD8RuntimeComposition(
            database,
            secureStore,
            TinkPairingHpke(),
            ids,
            MutationWallClock { Clock.System.now().toEpochMilliseconds() },
            agentOutboundGate = agentWriteGate,
        )
    }
    private val d8Runtime by d8RuntimeLazy
    private val d8Scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val d8ShutdownScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile private var d8SyncTrigger: ActiveSyncCatchUpTrigger? = null
    @Volatile private var d8IsForeground = false
    private var d8NetworkCallback: ConnectivityManager.NetworkCallback? = null
    private val reads by lazy { ConflictAwareSourceFactReadService(events, tasks, profiles, academics, d8Runtime.sourceFacts) }
    private val eventEditor by lazy { EventEditingService(events, ids, mutations, d8Runtime.writePolicy) }
    private val taskEditor by lazy { TaskEditingService(tasks, ids, mutations, d8Runtime.writePolicy) }
    private val dogfoodPlanner by lazy { DogfoodPlannerService(tasks, events, profiles, academics, ids, mutations = mutations, conflictWritePolicy = d8Runtime.writePolicy, sourceFacts = d8Runtime.sourceFacts) }
    private val profileSettings by lazy { PlanningProfileSettingsService(profiles, ids, mutations, d8Runtime.writePolicy) }
    private val agentHttpClientLazy = lazy { HttpClient(Android) }
    private val agentHttpClient by agentHttpClientLazy
    private val agentRun by lazy {
        val journal = RoomMutationJournalRepository(database)
        val history = HistoryQueryService(journal)
        val undo = UndoService(mutations, journal, events, tasks, profiles, d8Runtime.writePolicy)
        AgentRunService(
            state = agentState,
            provider = OpenAiCompatibleProvider(agentHttpClient, SecretStoreProviderCredentialResolver(secureStore)),
            taskGet = TaskGetTool(tasks),
            taskCreate = TaskCreateTool(taskEditor),
            ids = ids,
            clock = AgentClock { Clock.System.now().toEpochMilliseconds() },
            taskList = TaskListTool(tasks),
            historyReads = HistoryReadTools(history),
            taskUpdate = TaskUpdateTool(taskEditor),
            calendarList = CalendarListTool(reads),
            eventCreate = EventCreateTool(eventEditor),
            eventUpdate = EventUpdateTool(eventEditor),
            plannerFullReplan = PlannerPreviewFullReplanTool(DogfoodPlannerPreviewApplication(dogfoodPlanner)),
            plannerLocalReflow = PlannerPreviewLocalReflowTool(DogfoodPlannerPreviewApplication(dogfoodPlanner)),
            historyUndo = HistoryUndoTool(D7HistoryUndoApplication(undo, history)),
            planningProfileUpdate = PlanningProfileUpdateTool(profileSettings),
            plannerApplyBranch = PlannerApplyBranchTool(dogfoodPlanner),
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
                                pollingIntervalMillis = ActiveSyncCatchUpTrigger.DESKTOP_ANDROID_POLL_INTERVAL_MILLIS,
                                onUnexpectedFailure = { android.util.Log.w("D8Sync", "Catch-up failed unexpectedly; transient retry remains scheduled.") },
                                onNonRetryableFailure = { reason ->
                                    android.util.Log.e("D8Sync", "Automatic sync stopped: $reason. Check account credentials or sync integrity before retrying.")
                                    runOnUiThread { d8SyncStoppedReason.value = reason }
                                },
                            )
                            d8SyncTrigger = trigger
                            trigger.start()
                            trigger.setForeground(d8IsForeground)
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
            val syncStoppedReason by d8SyncStoppedReason
            MaterialTheme {
                // Also expose the stable Compose tags as Android resource IDs so the
                // physical-device acceptance path can use ADB/UIAutomator selectors.
                Surface(modifier = Modifier.semantics { testTagsAsResourceId = true }) {
                    when (startupState) {
                        D8StartupState.Ready -> AndroidScheduler(
                            reads,
                            dogfoodPlanner,
                            profileSettings,
                            eventEditor,
                            taskEditor,
                            agentState,
                            agentRun,
                            secureStore,
                            localEnrollments,
                            ids,
                            syncStoppedReason = syncStoppedReason,
                            onRetrySync = {
                                d8SyncStoppedReason.value = null
                                d8SyncTrigger?.retryNow()
                            },
                        )
                        D8StartupState.Activating -> D8StartupStatus("Connecting to your secure sync space…")
                        D8StartupState.Blocked -> D8StartupStatus("Sync setup is unavailable. Restore the device credential or check the configured account and server.")
                    }
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        d8IsForeground = true
        d8SyncTrigger?.setForeground(true)
    }

    override fun onStop() {
        d8IsForeground = false
        d8SyncTrigger?.setForeground(false)
        super.onStop()
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
        if (agentHttpClientLazy.isInitialized()) agentHttpClient.close()
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

    /**
     * Deployment configuration is intentionally explicit. D10 may provide UI
     * for it; D8 accepts only app-owned manifest metadata and never guesses an
     * endpoint or account.
     */
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

@Composable
private fun D8StartupStatus(message: String) {
    Column { Text(message) }
}

@Composable
private fun AndroidScheduler(
    reads: ConflictAwareSourceFactReadService,
    dogfoodPlanner: DogfoodPlannerService,
    profileSettings: PlanningProfileSettingsService,
    eventEditor: EventEditingService,
    taskEditor: TaskEditingService,
    agentState: AgentStateRepository,
    agentRun: AgentRunService,
    secureStore: PlatformSecretStore,
    enrollments: LocalEnrollmentRepository,
    ids: dev.agenticscheduler.application.id.UuidV7Generator,
    syncStoppedReason: String?,
    onRetrySync: () -> Unit,
) {
    val displayTimeZone = remember { TimeZone.currentSystemDefault() }
    var selectedDate by remember { mutableStateOf(Clock.System.now().toLocalDateTime(displayTimeZone).date) }
    var editingEvent by remember { mutableStateOf<Event?>(null) }
    var editingTask by remember { mutableStateOf<Task?>(null) }
    var creatingEvent by remember { mutableStateOf(false) }
    var creatingTask by remember { mutableStateOf(false) }
    val viewport = remember(selectedDate, displayTimeZone) {
        CalendarViewport(selectedDate, selectedDate.plus(1, DateTimeUnit.DAY), displayTimeZone)
    }
    val projection by remember(reads, viewport) { reads.observe(viewport) }.collectAsState(emptyProjection())
    val taskRead by remember(reads) { reads.observeTasks() }.collectAsState(ConflictAwareRead.Projected(emptyList<Task>().toImmutableList()))
    val focusRead by remember(reads) { reads.observeFocusBlocks() }.collectAsState(ConflictAwareRead.Projected(emptyList<dev.agenticscheduler.domain.task.FocusBlock>().toImmutableList()))
    val taskValues = (taskRead as? ConflictAwareRead.Projected)?.value.orEmpty().toImmutableList()
    val focusBlocks = (focusRead as? ConflictAwareRead.Projected)?.value.orEmpty().toImmutableList()
    val projectedSyncConflictRefs =
        (taskRead as? ConflictAwareRead.Projected<*>)?.syncConflictRefs.orEmpty() +
            (focusRead as? ConflictAwareRead.Projected<*>)?.syncConflictRefs.orEmpty()
    val syncConflictCount = (projection.syncConflictRefs + projectedSyncConflictRefs)
        .flatMap { it.conflictIds }
        .distinct()
        .size
    val dateItems = projection.items.filter { it is CalendarItem.AllDay || it is CalendarItem.DateOnly }
    val timedItems = projection.items.filterNot { it is CalendarItem.AllDay || it is CalendarItem.DateOnly }

    LazyColumn {
        if (syncStoppedReason != null) {
            item(key = "sync-stopped") {
                Column {
                    Text("Automatic sync stopped ($syncStoppedReason). Check the account credentials or sync integrity, then retry.")
                    Button(onClick = onRetrySync) { Text("Retry sync") }
                }
            }
        }
        item {
            Text("Agenda / Day: $selectedDate")
            Row(Modifier.fillMaxWidth()) {
                Button(
                    modifier = Modifier.weight(1f),
                    onClick = { selectedDate = selectedDate.plus(-1, DateTimeUnit.DAY) },
                ) { Text("Previous") }
                Button(
                    modifier = Modifier.weight(1f),
                    onClick = { selectedDate = selectedDate.plus(1, DateTimeUnit.DAY) },
                ) { Text("Next") }
            }
            Row(Modifier.fillMaxWidth()) {
                Button(modifier = Modifier.weight(1f), onClick = { creatingEvent = true }) { Text("New Event") }
                Button(modifier = Modifier.weight(1f), onClick = { creatingTask = true }) { Text("New Task") }
            }
        }
        item { Text("All-day / date-only") }
        items(dateItems, key = { it.source.toString() }) { item -> CalendarRow(item, reads, focusBlocks.associate { it.id to (taskValues.firstOrNull { task -> task.id == it.taskId }?.title ?: it.taskId.value) }) { editingEvent = it } }
        item { Text("Timed / floating") }
        items(timedItems, key = { it.source.toString() }) { item -> CalendarRow(item, reads, focusBlocks.associate { it.id to (taskValues.firstOrNull { task -> task.id == it.taskId }?.title ?: it.taskId.value) }) { editingEvent = it } }
        item { Text("Tasks") }
        items(taskValues, key = { it.id.value }) { task ->
            Row {
                Text(task.title)
                Button(onClick = { editingTask = task }) { Text("Edit") }
            }
        }
        item {
            if (projection.conflicts.isNotEmpty()) Text("${projection.conflicts.size} calendar overlap(s)")
            if (syncConflictCount > 0) Text("$syncConflictCount sync conflict(s) require resolution")
            if (projection.issues.isNotEmpty()) Text("${projection.issues.size} projection issue(s)")
            if (taskRead is ConflictAwareRead.Unprojectable || focusRead is ConflictAwareRead.Unprojectable) Text("Sync conflict source facts require resolution before they can be displayed.")
        }
        item { AndroidAgentPanel(agentState, agentRun, secureStore, enrollments, ids) }
        // Calendar source facts above can add or remove lazy items. Keep the pending
        // PlanBranch preview attached to this semantic panel rather than its list index.
        item(key = "planner-dogfood") { PlannerDogfoodPanel(reads, focusBlocks, dogfoodPlanner, profileSettings) }
    }

    if (creatingEvent) EventEditorDialog(null, selectedDate, displayTimeZone, eventEditor, { creatingEvent = false }, { creatingEvent = false })
    editingEvent?.let { event -> EventEditorDialog(event, selectedDate, displayTimeZone, eventEditor, { editingEvent = null }, { editingEvent = null }) }
    if (creatingTask) TaskEditorDialog(null, taskEditor, { creatingTask = false }, { creatingTask = false })
    editingTask?.let { task -> TaskEditorDialog(task, taskEditor, { editingTask = null }, { editingTask = null }) }
}

@Composable
private fun AndroidAgentPanel(
    state: AgentStateRepository,
    runService: AgentRunService,
    secureStore: PlatformSecretStore,
    enrollments: LocalEnrollmentRepository,
    ids: dev.agenticscheduler.application.id.UuidV7Generator,
) {
    val scope = rememberCoroutineScope()
    val configChoices = remember { mutableStateListOf<ProviderConfig>() }
    val messages = remember { mutableStateListOf<AgentMessage>() }
    val toolCalls = remember { mutableStateListOf<AgentToolCall>() }
    val toolResults = remember { mutableStateListOf<AgentToolResult>() }
    var configId by remember { mutableStateOf<ProviderConfigId?>(null) }
    var selectedConfigId by remember { mutableStateOf<ProviderConfigId?>(null) }
    var threadChoices by remember { mutableStateOf(emptyList<dev.agenticscheduler.agent.history.AgentThread>()) }
    var threadId by remember { mutableStateOf<AgentThreadId?>(null) }
    var baseUrl by remember { mutableStateOf("") }
    var model by remember { mutableStateOf("") }
    var maxContext by remember { mutableStateOf("") }
    var reservedOutput by remember { mutableStateOf("") }
    var toolCalling by remember { mutableStateOf<Boolean?>(null) }
    var streaming by remember { mutableStateOf<Boolean?>(null) }
    var credential by remember { mutableStateOf("") }
    var removeSavedCredential by remember { mutableStateOf(false) }
    var command by remember { mutableStateOf("") }
    var status by remember { mutableStateOf("Configure a provider to begin.") }
    var pendingConfirmation by remember { mutableStateOf<Pair<dev.agenticscheduler.agent.history.AgentToolCallId, String>?>(null) }
    var confirmThreadDeletion by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var configurationError by remember { mutableStateOf<String?>(null) }
    var effectivePolicy by remember { mutableStateOf<dev.agenticscheduler.agent.permission.AgentPermissionPolicy?>(null) }
    var activeEnrollment by remember { mutableStateOf<LocalEnrollmentState.Active?>(null) }
    var allowSyncedAgentWrites by remember { mutableStateOf(false) }

    suspend fun reloadConfig() {
        configChoices.clear()
        configChoices.addAll(state.providerConfigs())
        selectedConfigId = state.selectedProviderConfigId()
        effectivePolicy = state.permissionPolicy()
        threadChoices = state.threads().sortedByDescending { it.createdAtEpochMillis }
        if (threadId == null) threadId = threadChoices.firstOrNull()?.id
        activeEnrollment = enrollments.states().filterIsInstance<LocalEnrollmentState.Active>().singleOrNull()
        allowSyncedAgentWrites = activeEnrollment?.let { state.syncAgentOriginEnabled(it.syncSpaceId) } ?: false
    }
    suspend fun reloadThread(id: AgentThreadId) {
        messages.clear(); messages.addAll(state.messages(id).sortedWith(compareBy({ it.ordinal }, { it.id.value })))
        toolCalls.clear(); toolCalls.addAll(state.toolCalls(id).sortedWith(compareBy({ it.ordinal }, { it.id.value })))
        toolResults.clear(); toolResults.addAll(state.toolResults(id).sortedWith(compareBy({ it.ordinal }, { it.id.value })))
        pendingConfirmation = toolCalls.lastOrNull { it.state == AgentToolCallState.WAITING_CONFIRMATION }
            ?.let { call -> call.previewJson?.let { preview -> call.id to preview } }
    }

    LaunchedEffect(state) { reloadConfig() }
    LaunchedEffect(selectedConfigId, configChoices.size) {
        val selected = configChoices.firstOrNull { it.id == selectedConfigId }
        if (selected != null) {
            configId = selected.id
            baseUrl = selected.baseUrl
            model = selected.model
            maxContext = selected.maxContextUnits.toString()
            reservedOutput = selected.reservedOutputUnits.toString()
            toolCalling = selected.toolCallingSupported
            streaming = selected.streamingSupported
        }
    }
    LaunchedEffect(threadId) { threadId?.let { reloadThread(it) } }

    Column {
        Text("Universal Command")
        Text("This device’s Agent permission policy")
        effectivePolicy?.let { policy ->
            dev.agenticscheduler.agent.permission.AgentToolCapability.entries.forEach { capability ->
                Text("${capability.name}: ${policy.modeFor(capability).name}")
            }
        }
        activeEnrollment?.let { active ->
            Row {
                Checkbox(
                    checked = allowSyncedAgentWrites,
                    onCheckedChange = { enabled -> scope.launch {
                        state.setSyncAgentOriginEnabled(active.syncSpaceId, enabled)
                        allowSyncedAgentWrites = state.syncAgentOriginEnabled(active.syncSpaceId)
                    } },
                )
                Text("I confirm every enrolled device is upgraded for Agent-origin sync writes")
            }
            Text(if (allowSyncedAgentWrites) {
                "Agent-origin sync writes are enabled for this SyncSpace."
            } else {
                "Agent-origin writes stay local until you enable this acknowledgement."
            })
        } ?: Text("No single active SyncSpace is selected. Agent writes remain local-only.")

        Text("Provider configuration")
        configChoices.forEach { config ->
            Row {
                RadioButton(selected = config.id == selectedConfigId, onClick = {
                    selectedConfigId = config.id
                    configId = config.id
                    credential = ""
                    removeSavedCredential = false
                    scope.launch { state.selectProviderConfig(config.id); reloadConfig() }
                })
                Text("${config.model} · ${config.baseUrl}")
            }
        }
        OutlinedTextField(baseUrl, { baseUrl = it }, label = { Text("Provider base URL (explicit)") })
        OutlinedTextField(model, { model = it }, label = { Text("Model (explicit)") })
        OutlinedTextField(maxContext, { maxContext = it }, label = { Text("Maximum context units") })
        OutlinedTextField(reservedOutput, { reservedOutput = it }, label = { Text("Reserved output units") })
        Text("Structured tool calling")
        Row {
            RadioButton(selected = toolCalling == true, onClick = { toolCalling = true }); Text("Enabled")
            RadioButton(selected = toolCalling == false, onClick = { toolCalling = false }); Text("Disabled")
        }
        Text("Streaming")
        Row {
            RadioButton(selected = streaming == true, onClick = { streaming = true }); Text("Enabled")
            RadioButton(selected = streaming == false, onClick = { streaming = false }); Text("Disabled")
        }
        OutlinedTextField(
            value = credential,
            onValueChange = {
                credential = it
                if (it.isNotEmpty()) removeSavedCredential = false
            },
            label = { Text("Optional API credential (stored in Android secure storage)") },
            visualTransformation = PasswordVisualTransformation(),
            enabled = !busy && !removeSavedCredential,
        )
        val selectedCredentialReference = configId?.let { selectedId ->
            configChoices.firstOrNull { it.id == selectedId }?.credentialReference
        }
        if (selectedCredentialReference != null) {
            Row {
                Checkbox(
                    checked = removeSavedCredential,
                    onCheckedChange = { remove ->
                        removeSavedCredential = remove
                        if (remove) credential = ""
                    },
                    enabled = !busy,
                )
                Text("Remove saved credential")
            }
        }
        Text(if (removeSavedCredential && credential.isBlank()) {
            "Saving will remove the selected credential from Android secure storage."
        } else {
            "Leave the credential blank to keep the selected secure-store credential. Enter a value to replace it. Credentialed endpoints require HTTPS."
        })
        Text("A credential-free HTTP endpoint on another device or host can expose prompts and schedule data in transit.")
        configurationError?.let { Text(it) }
        Button(enabled = !busy, onClick = {
            val max = maxContext.toLongOrNull()
            val output = reservedOutput.toLongOrNull()
            if (baseUrl.isBlank() || model.isBlank() || max == null || output == null || output <= 0 || max <= output || toolCalling == null || streaming == null) {
                configurationError = "Enter URL, model, positive context/output capacities, and choose tool-calling and streaming support."
            } else if (
                (credential.isNotEmpty() || (!removeSavedCredential && selectedCredentialReference != null)) &&
                !baseUrl.trim().startsWith("https://", ignoreCase = true)
            ) {
                configurationError = "A provider with an API credential must use HTTPS."
            } else scope.launch {
                busy = true
                configurationError = null
                var newReference: SecretReference? = null
                var supersededCredential: SecretReference? = null
                var published = false
                try {
                    val previous = configId?.let { state.providerConfig(it) }
                    val previousCredential = previous?.credentialReference
                    val ref = if (credential.isNotEmpty()) {
                        val secretBytes = credential.encodeToByteArray()
                        try {
                            secureStore.importSecret(AndroidAgentCredential(secretBytes)).also { newReference = it }
                        } finally { secretBytes.fill(0) }
                    } else if (removeSavedCredential) null else previousCredential
                    val config = ProviderConfig(
                        id = configId ?: ProviderConfigId(ids.next()),
                        baseUrl = baseUrl.trim(),
                        model = model.trim(),
                        maxContextUnits = max,
                        reservedOutputUnits = output,
                        streamingSupported = requireNotNull(streaming),
                        toolCallingSupported = requireNotNull(toolCalling),
                        credentialReference = ref,
                    )
                    state.saveProviderConfig(config)
                    published = true
                    state.selectProviderConfig(config.id)
                    supersededCredential = previousCredential?.takeIf { it != ref }
                    credential = ""
                    removeSavedCredential = false
                    configId = config.id
                    selectedConfigId = config.id
                    status = "Provider configuration saved. The runtime probes structured tool support before enabling Tools."
                    reloadConfig()
                } catch (_: Exception) {
                    // Before publishing Room metadata, an imported key is safe to clean up.
                    // Once metadata is durable, it must stay available even if later UI work fails.
                    if (!published) newReference?.let { runCatching { secureStore.delete(it) } }
                    configurationError = "Provider configuration could not be saved. Secret contents were not stored in Agent history."
                } finally {
                    busy = false
                }
                // This is post-publication cleanup. Failure leaves an orphaned secure object,
                // never a Room reference to a deleted credential.
                if (published) {
                    supersededCredential?.let { reference -> runCatching { secureStore.delete(reference) } }
                }
            }
        }) { Text("Save provider configuration") }

        Text("Agent conversation")
        Row(Modifier.fillMaxWidth()) {
            Button(modifier = Modifier.weight(1f), enabled = !busy && pendingConfirmation == null, onClick = {
                scope.launch {
                    threadId = runService.createThread()
                    reloadConfig()
                    threadId?.let { reloadThread(it) }
                    status = "New application-owned AgentThread created."
                }
            }) { Text("New conversation") }
            Button(modifier = Modifier.weight(1f), enabled = !busy && pendingConfirmation == null && threadId != null, onClick = { confirmThreadDeletion = true }) {
                Text("Delete conversation")
            }
        }
        threadChoices.forEach { thread ->
            Button(enabled = !busy && pendingConfirmation == null && thread.id != threadId, onClick = { threadId = thread.id }) {
                Text(if (thread.id == threadId) "Current conversation" else "Conversation ${thread.id.value.take(8)}")
            }
        }
        OutlinedTextField(command, { command = it }, label = { Text("Ask, query, or request a typed action") })
        Button(enabled = !busy && threadId != null && selectedConfigId != null && command.isNotBlank(), onClick = {
            val id = threadId ?: return@Button
            val request = command
            command = ""
            scope.launch {
                busy = true
                status = if (streaming == true) "Sending · streaming response…" else "Sending · thinking…"
                try {
                    when (val result = runService.run(id, request)) {
                        is AgentRunResult.Completed -> status = "Completed"
                        is AgentRunResult.AwaitingConfirmation -> {
                            pendingConfirmation = result.callId to result.previewJson
                            status = "Confirmation required"
                        }
                        is AgentRunResult.Failed -> status = agentFailureMessage(result.redactedCode)
                    }
                    reloadThread(id)
                    reloadConfig()
                } catch (_: Exception) { status = "Agent run failed. Check provider availability and retry." }
                finally { busy = false }
            }
        }) { Text("Send") }
        Text(status)
        Text("Conversation")
        messages.forEach { message ->
            Text("${message.role.name}: ${message.content}")
        }
        Text("Structured Tool calls")
        toolCalls.forEach { call ->
            Text("${call.name} · ${call.state.name}")
            Text("Input: ${call.argumentsJson}")
            call.previewJson?.let { Text("Preview: $it") }
        }
        Text("Structured Tool results")
        toolResults.forEach { result ->
            Text("${result.status.name}: ${result.resultJson}")
        }
    }

    if (confirmThreadDeletion) {
        AlertDialog(
            onDismissRequest = { if (!busy) confirmThreadDeletion = false },
            title = { Text("Delete this conversation?") },
            text = { Text("This removes its local messages, Tool calls and results, and summaries. Committed AgentAction and ChangeLog audit facts remain. Deletion does not erase every historical encrypted copy.") },
            confirmButton = { Button(enabled = !busy, onClick = {
                val id = threadId ?: return@Button
                scope.launch {
                    busy = true
                    try {
                        state.deleteThread(id)
                        confirmThreadDeletion = false
                        pendingConfirmation = null
                        messages.clear(); toolCalls.clear(); toolResults.clear()
                        threadId = null
                        reloadConfig()
                        status = "Conversation deleted. Committed audit facts remain."
                    } catch (_: Exception) {
                        status = "Conversation could not be deleted."
                    } finally { busy = false }
                }
            }) { Text("Delete") } },
            dismissButton = { Button(enabled = !busy, onClick = { confirmThreadDeletion = false }) { Text("Cancel") } },
        )
    }

    pendingConfirmation?.takeUnless { confirmThreadDeletion }?.let { (callId, preview) ->
        AlertDialog(
            onDismissRequest = { },
            title = { Text("Confirm Agent action") },
            text = { Text("Preview of the validated change:\n$preview") },
            confirmButton = { Button(enabled = !busy, onClick = {
                val id = threadId ?: return@Button
                scope.launch {
                    busy = true
                    pendingConfirmation = null
                    status = "Applying confirmed action…"
                    try {
                        when (val result = runService.confirm(id, callId, approved = true)) {
                            is AgentRunResult.Completed -> status = "Confirmed action completed"
                            is AgentRunResult.AwaitingConfirmation -> { pendingConfirmation = result.callId to result.previewJson; status = "Confirmation required" }
                            is AgentRunResult.Failed -> status = agentFailureMessage(result.redactedCode)
                        }
                        reloadThread(id)
                    } catch (_: Exception) { status = "Confirmed action failed; inspect the structured Tool result." }
                    finally { busy = false }
                }
            }) { Text("Confirm") } },
            dismissButton = { Row {
                Button(enabled = !busy, onClick = {
                    val id = threadId ?: return@Button
                    scope.launch {
                        busy = true
                        pendingConfirmation = null
                        try {
                            when (val result = runService.confirm(id, callId, approved = false)) {
                                is AgentRunResult.Completed -> status = "Denied. No write was authorized."
                                is AgentRunResult.AwaitingConfirmation -> { pendingConfirmation = result.callId to result.previewJson; status = "Confirmation required" }
                                is AgentRunResult.Failed -> status = agentFailureMessage(result.redactedCode)
                            }
                            reloadThread(id)
                        } catch (_: Exception) { status = "Denial could not be recorded." }
                        finally { busy = false }
                    }
                }) { Text("Deny") }
                Button(enabled = !busy, onClick = { confirmThreadDeletion = true }) { Text("Delete conversation") }
            } },
        )
    }
}

private fun agentFailureMessage(code: String): String = when {
    code.contains("PROVIDER") || code.contains("NETWORK") || code.startsWith("HTTP_") || code.contains("CREDENTIAL") -> "Provider/network unavailable ($code)"
    code.contains("PERMISSION") || code.contains("DENIED") -> "Permission denied ($code)"
    code.contains("STALE") || code.contains("CONFLICT") -> "Stale or conflicting state ($code)"
    code.contains("INFEASIBLE") -> "Planner reports an infeasible request ($code)"
    else -> "Agent request failed ($code)"
}

private class AndroidAgentCredential(private val raw: ByteArray) : PlatformSecretMaterial {
    override fun copyRawSecretBytesForSecureStore(): ByteArray = raw.copyOf()
}

@Composable
private fun CalendarRow(item: CalendarItem, reads: ConflictAwareSourceFactReadService, focusTitles: Map<FocusBlockId, String>, onEdit: (Event) -> Unit) {
    val scope = rememberCoroutineScope()
    Row {
        val focusTitle = (item.source as? CalendarSourceRef.FocusBlock)?.let { focusTitles[it.id] }
        Text((focusTitle?.let { "Focus block: $it" } ?: item.title) + if (item is CalendarItem.Floating) " (floating)" else "")
        val source = item.source as? CalendarSourceRef.Event
        if (source != null) {
            Button(onClick = { scope.launch { (reads.event(source.id) as? ConflictAwareRead.Projected)?.value?.let(onEdit) } }) { Text("Edit") }
        }
    }
}

@Composable
private fun EventEditorDialog(
    existing: Event?,
    selectedDate: LocalDate,
    displayTimeZone: TimeZone,
    editor: EventEditingService,
    onSaved: () -> Unit,
    onDismiss: () -> Unit,
) {
    var kind by remember(existing) { mutableStateOf(existing.eventKind()) }
    var title by remember(existing) { mutableStateOf(existing?.title ?: "") }
    var start by remember(existing, selectedDate) { mutableStateOf(existing.startText(selectedDate)) }
    var end by remember(existing, selectedDate) { mutableStateOf(existing.endText(selectedDate)) }
    var timeZone by remember(existing, displayTimeZone) { mutableStateOf(existing.timeZoneText(displayTimeZone)) }
    var flexibility by remember(existing) { mutableStateOf(existing?.flexibility ?: Flexibility.HARD) }
    var pinState by remember(existing) { mutableStateOf(existing?.pinState ?: PinState.UNPINNED) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (existing == null) "New Event" else "Edit Event") },
        text = {
            Column {
                OutlinedTextField(title, { title = it }, label = { Text("Title") })
                Button(onClick = { kind = kind.next() }) { Text("Time kind: $kind") }
                OutlinedTextField(start, { start = it }, label = { Text(if (kind == EventKind.ALL_DAY) "Start date (YYYY-MM-DD)" else "Start (YYYY-MM-DDTHH:MM)") })
                OutlinedTextField(end, { end = it }, label = { Text(if (kind == EventKind.ALL_DAY) "End exclusive date" else "End exclusive") })
                if (kind == EventKind.ZONED) OutlinedTextField(timeZone, { timeZone = it }, label = { Text("Time zone") })
                Button(onClick = { flexibility = flexibility.next() }) { Text("Flexibility: $flexibility") }
                Button(onClick = { pinState = pinState.next() }) { Text("Pin state: $pinState") }
                error?.let { Text(it) }
            }
        },
        confirmButton = {
            Button(onClick = {
                val time = parseEventTime(kind, start, end, timeZone)
                if (time == null) {
                    error = "Enter a valid time range."
                } else {
                    scope.launch {
                        val result = if (existing == null) {
                            editor.create(CreateEventInput(title, time, flexibility, pinState))
                        } else {
                            editor.update(UpdateEventInput(existing.id, title, time, flexibility, pinState))
                        }
                        when (result) {
                            is EditingResult.Success -> onSaved()
                            is EditingResult.Invalid -> error = result.issues.joinToString()
                            EditingResult.NotFound -> error = "Event no longer exists."
                            EditingResult.Stale -> error = "Event changed. Reload it before saving."
                            is EditingResult.BlockedBySyncConflict -> error = "This change intersects an unresolved sync conflict. Resolve it before editing."
                        }
                    }
                }
            }) { Text("Save") }
        },
        dismissButton = { Button(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun TaskEditorDialog(
    existing: Task?,
    editor: TaskEditingService,
    onSaved: () -> Unit,
    onDismiss: () -> Unit,
) {
    var title by remember(existing) { mutableStateOf(existing?.title ?: "") }
    var status by remember(existing) { mutableStateOf(existing?.status ?: TaskStatus.OPEN) }
    var priority by remember(existing) { mutableStateOf(existing?.priority ?: TaskPriority.NORMAL) }
    var estimated by remember(existing) { mutableStateOf(existing?.effort?.estimated?.toString() ?: "") }
    var completed by remember(existing) { mutableStateOf(existing?.effort?.completed?.toString() ?: Duration.ZERO.toString()) }
    var remaining by remember(existing) { mutableStateOf(existing?.effort?.remaining?.toString() ?: "") }
    var deadlineEnabled by remember(existing) { mutableStateOf(existing?.deadline != null) }
    var deadlineKind by remember(existing) { mutableStateOf(existing.deadlineKind()) }
    var deadlineValue by remember(existing) { mutableStateOf(existing.deadlineValue()) }
    var deadlineZone by remember(existing) { mutableStateOf(existing.deadlineZone()) }
    var deadlinePolicy by remember(existing) { mutableStateOf(existing?.deadline?.policy ?: DeadlinePolicy.NORMAL) }
    var overflowPolicy by remember(existing) { mutableStateOf(existing?.deadline?.overflowPolicy ?: OverflowPolicy.ASK) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (existing == null) "New Task" else "Edit Task") },
        text = {
            Column {
                OutlinedTextField(title, { title = it }, label = { Text("Title") })
                if (existing != null) Button(onClick = { status = status.next() }) { Text("Status: $status") } else Text("Status: OPEN")
                Button(onClick = { priority = priority.next() }) { Text("Priority: $priority") }
                OutlinedTextField(estimated, { estimated = it }, label = { Text("Estimated effort (optional, e.g. 1h)") })
                if (existing != null) OutlinedTextField(completed, { completed = it }, label = { Text("Completed effort") }) else Text("Completed effort: 0s")
                OutlinedTextField(remaining, { remaining = it }, label = { Text("Remaining effort (optional)") })
                Button(onClick = { deadlineEnabled = !deadlineEnabled }) { Text(if (deadlineEnabled) "Deadline enabled" else "Add deadline") }
                if (deadlineEnabled) {
                    Button(onClick = { deadlineKind = deadlineKind?.next() ?: DeadlineKind.DATE_ONLY }) { Text("Deadline kind: ${deadlineKind ?: "Choose"}") }
                    OutlinedTextField(deadlineValue, { deadlineValue = it }, label = { Text(if (deadlineKind == DeadlineKind.EXACT) "Deadline (YYYY-MM-DDTHH:MM)" else "Deadline date (YYYY-MM-DD)") })
                    if (deadlineKind == DeadlineKind.EXACT) OutlinedTextField(deadlineZone, { deadlineZone = it }, label = { Text("Time zone") })
                    Button(onClick = { deadlinePolicy = deadlinePolicy.next() }) { Text("Deadline policy: $deadlinePolicy") }
                    Button(onClick = { overflowPolicy = overflowPolicy.next() }) { Text("Overflow policy: $overflowPolicy") }
                }
                error?.let { Text(it) }
            }
        },
        confirmButton = {
            Button(onClick = {
                val parsedEstimated = parseOptionalDuration(estimated)
                val parsedRemaining = parseOptionalDuration(remaining)
                val parsedCompleted = if (existing == null) Duration.ZERO else parseRequiredDuration(completed)
                val deadline = parseDeadline(deadlineEnabled, deadlineKind, deadlineValue, deadlineZone, deadlinePolicy, overflowPolicy)
                if (parsedEstimated == null && estimated.isNotBlank() || parsedRemaining == null && remaining.isNotBlank() || parsedCompleted == null || deadlineEnabled && deadline == null) {
                    error = "Enter valid effort and deadline values."
                } else {
                    scope.launch {
                        val result = if (existing == null) {
                            editor.create(CreateTaskInput(title, priority, parsedEstimated, parsedRemaining, deadline))
                        } else {
                            editor.update(UpdateTaskInput(existing.id, title, status, priority, parsedEstimated, checkNotNull(parsedCompleted), parsedRemaining, deadline))
                        }
                        when (result) {
                            is EditingResult.Success -> onSaved()
                            is EditingResult.Invalid -> error = result.issues.joinToString()
                            EditingResult.NotFound -> error = "Task no longer exists."
                            EditingResult.Stale -> error = "Task changed. Reload it before saving."
                            is EditingResult.BlockedBySyncConflict -> error = "This change intersects an unresolved sync conflict. Resolve it before editing."
                        }
                    }
                }
            }) { Text("Save") }
        },
        dismissButton = { Button(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun PlannerDogfoodPanel(
    reads: ConflictAwareSourceFactReadService,
    focusBlocks: List<dev.agenticscheduler.domain.task.FocusBlock>,
    planner: DogfoodPlannerService,
    profileSettings: PlanningProfileSettingsService,
) {
    val profileRead by remember(reads) { reads.observePlanningProfiles() }.collectAsState(ConflictAwareRead.Projected(emptyList<PlanningProfile>().toImmutableList()))
    val profileValues = (profileRead as? ConflictAwareRead.Projected)?.value.orEmpty().toImmutableList()
    var selectedProfileId by remember { mutableStateOf<PlanningProfileId?>(null) }
    var createProfile by remember { mutableStateOf(false) }
    var editingProfile by remember { mutableStateOf<PlanningProfile?>(null) }
    var horizonStart by remember { mutableStateOf("") }
    var horizonEnd by remember { mutableStateOf("") }
    var affectedId by remember { mutableStateOf("") }
    var preview by remember { mutableStateOf<PlannerPreview?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val selected = profileValues.firstOrNull { it.id == selectedProfileId }

    Column(Modifier.testTag("planner-dogfood")) {
        Text("Planner dogfood")
        Text("PlanningProfile")
        Row {
            Button(onClick = { createProfile = true }) { Text("New PlanningProfile") }
            selected?.let { Button(onClick = { editingProfile = it }) { Text("Edit selected profile") } }
        }
        profileValues.forEach { profile ->
            Button(onClick = { selectedProfileId = profile.id }) {
                Text(if (profile.id == selectedProfileId) "Selected: ${profile.name}" else profile.name)
            }
        }
        if (profileRead is ConflictAwareRead.Unprojectable) Text("Sync conflict source facts require resolution before they can be displayed.")
        if (profileValues.isEmpty()) Text("Create a PlanningProfile, then configure explicit availability before planning.")
        OutlinedTextField(horizonStart, { horizonStart = it }, modifier = Modifier.testTag("planner-horizon-start"), label = { Text("Horizon start Instant (e.g. 2026-09-14T09:00:00Z)") })
        OutlinedTextField(horizonEnd, { horizonEnd = it }, modifier = Modifier.testTag("planner-horizon-end"), label = { Text("Horizon end Instant (exclusive)") })
        Row {
            Button(modifier = Modifier.testTag("planner-full-replan"), onClick = {
                val horizon = parseHorizon(horizonStart, horizonEnd)
                if (selected == null || horizon == null) message = "Select a PlanningProfile and enter an explicit positive horizon."
                else scope.launch { preview = planner.fullReplan(selected.id, Clock.System.now(), horizon); message = null }
            }) { Text("Full Replan preview") }
            Button(onClick = {
                val horizon = parseHorizon(horizonStart, horizonEnd)
                val configured = selected?.configuration as? PlanningProfileConfiguration.Configured
                val affected = runCatching { FocusBlockId(affectedId) }.getOrNull()
                if (selected == null || horizon == null || configured == null || affected == null) {
                    message = "Local Reflow requires a configured profile, one FocusBlock ID, and an explicit horizon."
                } else scope.launch {
                    val search = ZonedTimeRange(horizon.start, horizon.endExclusive, configured.timeZone)
                    preview = planner.localReflow(selected.id, Clock.System.now(), horizon, LocalReflowRequest(persistentListOf(affected), persistentListOf(), search))
                    message = null
                }
            }) { Text("Local Reflow preview") }
        }
        Text("Affected FocusBlock for Local Reflow")
        focusBlocks.forEach { block -> Button(onClick = { affectedId = block.id.value }) { Text(block.id.value) } }
        OutlinedTextField(affectedId, { affectedId = it }, label = { Text("FocusBlock ID") })
        message?.let { Text(it) }
        when (val result = preview) {
            is PlannerPreview.Applicable -> {
                Text("PlanBranch preview: ${result.branch.mutations.size} FocusBlock mutation(s)", modifier = Modifier.testTag("planner-preview"))
                result.branch.mutations.forEach { Text(it.toString()) }
                result.branch.issues.forEach { Text("PlannerIssue: $it") }
                Row {
                    Button(modifier = Modifier.testTag("planner-apply"), onClick = {
                        scope.launch {
                            when (val applied = planner.apply(result.branch, Clock.System.now())) {
                                is PlanBranchApplyResult.Applied -> { message = "PlanBranch applied atomically."; preview = null }
                                is PlanBranchApplyResult.Stale -> { preview = PlannerPreview.Applicable(applied.branch); message = "PlanBranch is stale; preview again before Apply." }
                                is PlanBranchApplyResult.BlockedBySyncConflict -> message = "PlanBranch intersects an unresolved sync conflict. Resolve it before applying."
                            }
                        }
                    }) { Text("Apply PlanBranch") }
                    Button(onClick = { preview = null; message = "PlanBranch cancelled; Active State was unchanged." }) { Text("Cancel preview") }
                }
            }
            is PlannerPreview.Infeasible -> result.issues.forEach { Text("PlannerIssue / infeasible: $it") }
            is PlannerPreview.InvalidInput -> result.issues.forEach { Text("PlannerIssue / invalid input: $it") }
            null -> Unit
        }
    }
    if (createProfile) PlanningProfileDialog(null, profileSettings, { createProfile = false }, { createProfile = false })
    editingProfile?.let { PlanningProfileDialog(it, profileSettings, { editingProfile = null }, { editingProfile = null }) }
}

@Composable
private fun PlanningProfileDialog(
    existing: PlanningProfile?,
    settings: PlanningProfileSettingsService,
    onSaved: () -> Unit,
    onDismiss: () -> Unit,
) {
    var name by remember(existing) { mutableStateOf(existing?.name ?: "") }
    val configured = existing?.configuration as? PlanningProfileConfiguration.Configured
    var timeZone by remember(existing) { mutableStateOf(configured?.timeZone?.id ?: "") }
    var minimum by remember(existing) { mutableStateOf(configured?.minimumFocusBlock?.toString() ?: "") }
    var preferred by remember(existing) { mutableStateOf(configured?.preferredFocusBlock?.toString() ?: "") }
    var maximum by remember(existing) { mutableStateOf(configured?.maximumFocusBlock?.toString() ?: "") }
    var windows by remember(existing) { mutableStateOf(configured?.weeklyAvailability?.joinToString("\n") { "${it.dayOfWeek} ${it.start}-${it.endExclusive}" } ?: "") }
    var allDayPolicy by remember(existing) { mutableStateOf(configured?.allDayEventPolicy) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (existing == null) "New PlanningProfile" else "PlanningProfile settings") },
        text = { Column {
            OutlinedTextField(name, { name = it }, label = { Text("Profile name") })
            if (existing != null) {
                OutlinedTextField(timeZone, { timeZone = it }, label = { Text("Time zone, e.g. Asia/Shanghai") })
                OutlinedTextField(minimum, { minimum = it }, label = { Text("Minimum FocusBlock duration") })
                OutlinedTextField(preferred, { preferred = it }, label = { Text("Preferred FocusBlock duration") })
                OutlinedTextField(maximum, { maximum = it }, label = { Text("Maximum FocusBlock duration") })
                OutlinedTextField(windows, { windows = it }, label = { Text("Availability: MONDAY 09:00-17:00 per line") })
                Button(onClick = { allDayPolicy = when (allDayPolicy) { null -> AllDayEventPolicy.NON_BLOCKING; AllDayEventPolicy.NON_BLOCKING -> AllDayEventPolicy.BLOCK_WHOLE_LOCAL_DAY; AllDayEventPolicy.BLOCK_WHOLE_LOCAL_DAY -> null } }) { Text("All-day Event policy: ${allDayPolicy ?: "Choose explicitly"}") }
            } else Text("New profiles start deliberately Unconfigured; configure it after creation.")
            error?.let { Text(it) }
        } },
        confirmButton = { Button(onClick = {
            if (existing == null) {
                scope.launch { runCatching { settings.createUnconfigured(name) }.onSuccess { onSaved() }.onFailure { error = it.message } }
            } else {
                val configuration = parseConfiguration(timeZone, minimum, preferred, maximum, windows, allDayPolicy)
                if (configuration == null) error = "Enter a valid timezone, ordered positive durations, non-overlapping availability, and choose an all-day policy."
                else scope.launch { runCatching { settings.save(existing.copy(name = name, configuration = configuration)) }.onSuccess { onSaved() }.onFailure { error = it.message } }
            }
        }) { Text(if (existing == null) "Create" else "Save") } },
        dismissButton = { Button(onClick = onDismiss) { Text("Cancel") } },
    )
}

private fun parseHorizon(start: String, end: String): PlanningHorizon? = runCatching { PlanningHorizon(kotlin.time.Instant.parse(start), kotlin.time.Instant.parse(end)) }.getOrNull()
private fun parseConfiguration(zone: String, minimum: String, preferred: String, maximum: String, windows: String, policy: AllDayEventPolicy?): PlanningProfileConfiguration.Configured? = runCatching {
    val parsedWindows = windows.lines().filter { it.isNotBlank() }.map { line ->
        val parts = line.trim().split(Regex("\\s+"), limit = 2)
        val times = parts[1].split("-", limit = 2)
        WeeklyAvailabilityWindow(DayOfWeek.valueOf(parts[0].uppercase()), LocalTime.parse(times[0]), LocalTime.parse(times[1]))
    }.toImmutableList()
    PlanningProfileConfiguration.Configured(TimeZone.of(zone), parsedWindows, Duration.parse(minimum), Duration.parse(preferred), Duration.parse(maximum), requireNotNull(policy))
}.getOrNull()

private enum class EventKind { ZONED, ALL_DAY, FLOATING }
private enum class DeadlineKind { DATE_ONLY, EXACT }

private fun Event?.eventKind(): EventKind = when (this?.time) {
    is ZonedTimeRange, null -> EventKind.ZONED
    is AllDayRange -> EventKind.ALL_DAY
    is FloatingTimeRange -> EventKind.FLOATING
}

private fun Event?.startText(selectedDate: LocalDate): String = when (val time = this?.time) {
    is ZonedTimeRange -> time.start.toLocalDateTime(time.timeZone).toString()
    is AllDayRange -> time.startDate.toString()
    is FloatingTimeRange -> time.start.toString()
    null -> if (eventKind() == EventKind.ALL_DAY) selectedDate.toString() else ""
}

private fun Event?.endText(selectedDate: LocalDate): String = when (val time = this?.time) {
    is ZonedTimeRange -> time.endExclusive.toLocalDateTime(time.timeZone).toString()
    is AllDayRange -> time.endDateExclusive.toString()
    is FloatingTimeRange -> time.endExclusive.toString()
    null -> if (eventKind() == EventKind.ALL_DAY) selectedDate.plus(1, DateTimeUnit.DAY).toString() else ""
}

private fun Event?.timeZoneText(default: TimeZone): String = (this?.time as? ZonedTimeRange)?.timeZone?.id ?: default.id
private fun EventKind.next(): EventKind = EventKind.entries[(ordinal + 1) % EventKind.entries.size]
private fun Flexibility.next(): Flexibility = Flexibility.entries[(ordinal + 1) % Flexibility.entries.size]
private fun PinState.next(): PinState = PinState.entries[(ordinal + 1) % PinState.entries.size]
private fun TaskStatus.next(): TaskStatus = TaskStatus.entries[(ordinal + 1) % TaskStatus.entries.size]
private fun TaskPriority.next(): TaskPriority = TaskPriority.entries[(ordinal + 1) % TaskPriority.entries.size]
private fun DeadlinePolicy.next(): DeadlinePolicy = DeadlinePolicy.entries[(ordinal + 1) % DeadlinePolicy.entries.size]
private fun OverflowPolicy.next(): OverflowPolicy = OverflowPolicy.entries[(ordinal + 1) % OverflowPolicy.entries.size]
private fun DeadlineKind.next(): DeadlineKind = DeadlineKind.entries[(ordinal + 1) % DeadlineKind.entries.size]

private fun parseEventTime(kind: EventKind, start: String, end: String, zone: String): EventTimeInput? = when (kind) {
    EventKind.ZONED -> runCatching { EventTimeInput.Zoned(LocalDateTime.parse(start), LocalDateTime.parse(end), TimeZone.of(zone)) }.getOrNull()
    EventKind.ALL_DAY -> runCatching { EventTimeInput.AllDay(LocalDate.parse(start), LocalDate.parse(end)) }.getOrNull()
    EventKind.FLOATING -> runCatching { EventTimeInput.Floating(LocalDateTime.parse(start), LocalDateTime.parse(end)) }.getOrNull()
}

private fun Task?.deadlineKind(): DeadlineKind? = when (this?.deadline?.deadline) {
    is Deadline.DateOnly -> DeadlineKind.DATE_ONLY
    is Deadline.Exact -> DeadlineKind.EXACT
    null -> null
}

private fun Task?.deadlineValue(): String = when (val deadline = this?.deadline?.deadline) {
    is Deadline.DateOnly -> deadline.date.toString()
    is Deadline.Exact -> deadline.at.toLocalDateTime(deadline.timeZone).toString()
    null -> ""
}

private fun Task?.deadlineZone(): String = (this?.deadline?.deadline as? Deadline.Exact)?.timeZone?.id ?: ""
private fun parseOptionalDuration(text: String): Duration? = if (text.isBlank()) null else runCatching { Duration.parse(text) }.getOrNull()
private fun parseRequiredDuration(text: String): Duration? = runCatching { Duration.parse(text) }.getOrNull()

private fun parseDeadline(enabled: Boolean, kind: DeadlineKind?, value: String, zone: String, policy: DeadlinePolicy, overflow: OverflowPolicy): TaskDeadlineInput? {
    if (!enabled) return null
    return when (kind) {
        DeadlineKind.DATE_ONLY -> runCatching { TaskDeadlineInput.DateOnly(LocalDate.parse(value), policy, overflow) }.getOrNull()
        DeadlineKind.EXACT -> runCatching { TaskDeadlineInput.Exact(LocalDateTime.parse(value), TimeZone.of(zone), policy, overflow) }.getOrNull()
        null -> null
    }
}

private fun emptyProjection() = CalendarProjectionResult(
    emptyList<CalendarItem>().toImmutableList(),
    emptyList<CalendarConflict>().toImmutableList(),
    emptyList<CalendarProjectionIssue>().toImmutableList(),
)

/** One local database can only emit Agent-origin payload v2 after its sole ACTIVE replica opted in. */
private class AndroidActiveEnrollmentAgentWriteGate(
    private val enrollments: LocalEnrollmentRepository,
    private val state: AgentStateRepository,
) : AgentOriginWriteGate, AgentOutboundCompatibilityGate {
    override suspend fun mayCommit(): Boolean {
        val active = enrollments.states().filterIsInstance<LocalEnrollmentState.Active>()
        return when (active.size) {
            0 -> true
            1 -> AgentSyncWriteGate(active.single().accountId, enrollments, state).mayCommit()
            else -> false
        }
    }

    override suspend fun enabled(syncSpaceId: dev.agenticscheduler.sync.SyncSpaceId): Boolean {
        val active = enrollments.states().filterIsInstance<LocalEnrollmentState.Active>()
        val selected = active.singleOrNull { it.syncSpaceId == syncSpaceId } ?: return false
        return active.size == 1 && AgentSyncWriteGate(selected.accountId, enrollments, state).enabled(syncSpaceId)
    }
}
