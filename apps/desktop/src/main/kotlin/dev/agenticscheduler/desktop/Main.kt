package dev.agenticscheduler.desktop

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import dev.agenticscheduler.application.calendar.CalendarConflict
import dev.agenticscheduler.application.calendar.CalendarItem
import dev.agenticscheduler.application.calendar.CalendarProjectionIssue
import dev.agenticscheduler.application.calendar.CalendarProjectionResult
import dev.agenticscheduler.application.calendar.CalendarQueryService
import dev.agenticscheduler.application.calendar.CalendarSourceRef
import dev.agenticscheduler.application.calendar.CalendarViewport
import dev.agenticscheduler.application.calendar.RepositoryCalendarQueryService
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
import dev.agenticscheduler.application.history.MutationWallClock
import dev.agenticscheduler.application.history.NoActiveSyncSpaceWritePolicy
import dev.agenticscheduler.application.history.NoActiveSyncSpaceSourceFactQuery
import dev.agenticscheduler.application.planner.DogfoodPlannerService
import dev.agenticscheduler.application.planner.PlanBranchApplyResult
import dev.agenticscheduler.application.planner.PlannerPreview
import dev.agenticscheduler.application.planner.PlanningProfileSettingsService
import dev.agenticscheduler.application.persistence.EventRepository
import dev.agenticscheduler.application.persistence.TaskRepository
import dev.agenticscheduler.database.openDesktopDatabase
import dev.agenticscheduler.database.repository.RoomAcademicRepository
import dev.agenticscheduler.database.repository.RoomApplicationTransactionRunner
import dev.agenticscheduler.database.repository.RoomEventRepository
import dev.agenticscheduler.database.repository.RoomMutationJournalRepository
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
import dev.agenticscheduler.planner.LocalReflowRequest
import dev.agenticscheduler.planner.PlanningHorizon
import java.io.File
import kotlinx.collections.immutable.toImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.coroutines.launch
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

fun main() = application {
    val databaseFile = File(System.getProperty("user.home"), ".agentic-scheduler/agentic-scheduler.db").also { it.parentFile.mkdirs() }
    val database = openDesktopDatabase(databaseFile.absolutePath)
    val events = RoomEventRepository(database)
    val tasks = RoomTaskRepository(database)
    val academics = RoomAcademicRepository(database)
    val profiles = RoomPlanningProfileRepository(database)
    val transactions = RoomApplicationTransactionRunner(database)
    val ids = productionUuidV7Generator()
    val mutations = MutationCoordinator(transactions, RoomMutationJournalRepository(database), ids, MutationWallClock { Clock.System.now().toEpochMilliseconds() })
    val calendar = RepositoryCalendarQueryService(events, tasks, academics)
    Window(onCloseRequest = ::exitApplication, title = "Agentic Scheduler") {
        MaterialTheme {
            Surface {
                DesktopScheduler(calendar, events, tasks, profiles, DogfoodPlannerService(tasks, events, profiles, academics, ids, mutations = mutations, conflictWritePolicy = NoActiveSyncSpaceWritePolicy, sourceFacts = NoActiveSyncSpaceSourceFactQuery), PlanningProfileSettingsService(profiles, ids, mutations, NoActiveSyncSpaceWritePolicy), EventEditingService(events, ids, mutations, NoActiveSyncSpaceWritePolicy), TaskEditingService(tasks, ids, mutations, NoActiveSyncSpaceWritePolicy))
            }
        }
    }
}

@Composable
private fun DesktopScheduler(
    calendar: CalendarQueryService,
    events: EventRepository,
    tasks: TaskRepository,
    profiles: dev.agenticscheduler.application.persistence.PlanningProfileRepository,
    dogfoodPlanner: DogfoodPlannerService,
    profileSettings: PlanningProfileSettingsService,
    eventEditor: EventEditingService,
    taskEditor: TaskEditingService,
) {
    val displayTimeZone = remember { TimeZone.currentSystemDefault() }
    var selectedDate by remember { mutableStateOf(Clock.System.now().toLocalDateTime(displayTimeZone).date) }
    var editingEvent by remember { mutableStateOf<Event?>(null) }
    var editingTask by remember { mutableStateOf<Task?>(null) }
    var creatingEvent by remember { mutableStateOf(false) }
    var creatingTask by remember { mutableStateOf(false) }
    val viewport = remember(selectedDate, displayTimeZone) { CalendarViewport(selectedDate, selectedDate.plus(1, DateTimeUnit.DAY), displayTimeZone) }
    val projection by remember(calendar, viewport) { calendar.observe(viewport) }.collectAsState(emptyProjection())
    val taskValues by tasks.observeTasks().collectAsState(emptyList<Task>().toImmutableList())
    val focusBlocks by tasks.observeFocusBlocks().collectAsState(emptyList<dev.agenticscheduler.domain.task.FocusBlock>().toImmutableList())
    val dateItems = projection.items.filter { it is CalendarItem.AllDay || it is CalendarItem.DateOnly }
    val timedItems = projection.items.filterNot { it is CalendarItem.AllDay || it is CalendarItem.DateOnly }

    LazyColumn {
        item {
            Text("Agenda / Day: $selectedDate")
            Row {
                Button(onClick = { selectedDate = selectedDate.plus(-1, DateTimeUnit.DAY) }) { Text("Previous") }
                Button(onClick = { selectedDate = selectedDate.plus(1, DateTimeUnit.DAY) }) { Text("Next") }
                Button(onClick = { creatingEvent = true }) { Text("New Event") }
                Button(onClick = { creatingTask = true }) { Text("New Task") }
            }
        }
        item { Text("All-day / date-only") }
        items(dateItems, key = { it.source.toString() }) { item -> CalendarRow(item, events, focusBlocks.associate { it.id to (taskValues.firstOrNull { task -> task.id == it.taskId }?.title ?: it.taskId.value) }) { editingEvent = it } }
        item { Text("Timed / floating") }
        items(timedItems, key = { it.source.toString() }) { item -> CalendarRow(item, events, focusBlocks.associate { it.id to (taskValues.firstOrNull { task -> task.id == it.taskId }?.title ?: it.taskId.value) }) { editingEvent = it } }
        item { Text("Tasks") }
        items(taskValues, key = { it.id.value }) { task -> Row { Text(task.title); Button(onClick = { editingTask = task }) { Text("Edit") } } }
        item { if (projection.conflicts.isNotEmpty()) Text("${projection.conflicts.size} conflict(s)"); if (projection.issues.isNotEmpty()) Text("${projection.issues.size} projection issue(s)") }
        item { PlannerDogfoodPanel(profiles, focusBlocks, dogfoodPlanner, profileSettings) }
    }

    if (creatingEvent) EventEditorDialog(null, selectedDate, displayTimeZone, eventEditor, { creatingEvent = false }, { creatingEvent = false })
    editingEvent?.let { EventEditorDialog(it, selectedDate, displayTimeZone, eventEditor, { editingEvent = null }, { editingEvent = null }) }
    if (creatingTask) TaskEditorDialog(null, taskEditor, { creatingTask = false }, { creatingTask = false })
    editingTask?.let { TaskEditorDialog(it, taskEditor, { editingTask = null }, { editingTask = null }) }
}

@Composable
private fun CalendarRow(item: CalendarItem, events: EventRepository, focusTitles: Map<FocusBlockId, String>, onEdit: (Event) -> Unit) {
    val scope = rememberCoroutineScope()
    Row {
        val focusTitle = (item.source as? CalendarSourceRef.FocusBlock)?.let { focusTitles[it.id] }
        Text((focusTitle?.let { "Focus block: $it" } ?: item.title) + if (item is CalendarItem.Floating) " (floating)" else "")
        val source = item.source as? CalendarSourceRef.Event
        if (source != null) Button(onClick = { scope.launch { events.get(source.id)?.let(onEdit) } }) { Text("Edit") }
    }
}

@Composable
private fun EventEditorDialog(existing: Event?, selectedDate: LocalDate, displayTimeZone: TimeZone, editor: EventEditingService, onSaved: () -> Unit, onDismiss: () -> Unit) {
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
                if (time == null) error = "Enter a valid time range." else scope.launch {
                    when (val result = if (existing == null) editor.create(CreateEventInput(title, time, flexibility, pinState)) else editor.update(UpdateEventInput(existing.id, title, time, flexibility, pinState))) {
                        is EditingResult.Success -> onSaved()
                        is EditingResult.Invalid -> error = result.issues.joinToString()
                        EditingResult.NotFound -> error = "Event no longer exists."
                        is EditingResult.BlockedBySyncConflict -> error = "This change intersects an unresolved sync conflict. Resolve it before editing."
                    }
                }
            }) { Text("Save") }
        },
        dismissButton = { Button(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun TaskEditorDialog(existing: Task?, editor: TaskEditingService, onSaved: () -> Unit, onDismiss: () -> Unit) {
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
                if (parsedEstimated == null && estimated.isNotBlank() || parsedRemaining == null && remaining.isNotBlank() || parsedCompleted == null || deadlineEnabled && deadline == null) error = "Enter valid effort and deadline values." else scope.launch {
                    when (val result = if (existing == null) editor.create(CreateTaskInput(title, priority, parsedEstimated, parsedRemaining, deadline)) else editor.update(UpdateTaskInput(existing.id, title, status, priority, parsedEstimated, checkNotNull(parsedCompleted), parsedRemaining, deadline))) {
                        is EditingResult.Success -> onSaved()
                        is EditingResult.Invalid -> error = result.issues.joinToString()
                        EditingResult.NotFound -> error = "Task no longer exists."
                        is EditingResult.BlockedBySyncConflict -> error = "This change intersects an unresolved sync conflict. Resolve it before editing."
                    }
                }
            }) { Text("Save") }
        },
        dismissButton = { Button(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun PlannerDogfoodPanel(
    profiles: dev.agenticscheduler.application.persistence.PlanningProfileRepository,
    focusBlocks: List<dev.agenticscheduler.domain.task.FocusBlock>,
    planner: DogfoodPlannerService,
    profileSettings: PlanningProfileSettingsService,
) {
    val profileValues by profiles.observeAll().collectAsState(emptyList<PlanningProfile>().toImmutableList())
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
    Column {
        Text("Planner dogfood")
        Row {
            Button(onClick = { createProfile = true }) { Text("New PlanningProfile") }
            selected?.let { Button(onClick = { editingProfile = it }) { Text("Edit selected profile") } }
        }
        profileValues.forEach { profile -> Button(onClick = { selectedProfileId = profile.id }) { Text(if (profile.id == selectedProfileId) "Selected: ${profile.name}" else profile.name) } }
        if (profileValues.isEmpty()) Text("Create a PlanningProfile, then configure explicit availability before planning.")
        OutlinedTextField(horizonStart, { horizonStart = it }, label = { Text("Horizon start Instant (e.g. 2026-09-14T09:00:00Z)") })
        OutlinedTextField(horizonEnd, { horizonEnd = it }, label = { Text("Horizon end Instant (exclusive)") })
        Row {
            Button(onClick = {
                val horizon = parseHorizon(horizonStart, horizonEnd)
                if (selected == null || horizon == null) message = "Select a PlanningProfile and enter an explicit positive horizon."
                else scope.launch { preview = planner.fullReplan(selected.id, Clock.System.now(), horizon); message = null }
            }) { Text("Full Replan preview") }
            Button(onClick = {
                val horizon = parseHorizon(horizonStart, horizonEnd)
                val configured = selected?.configuration as? PlanningProfileConfiguration.Configured
                val affected = runCatching { FocusBlockId(affectedId) }.getOrNull()
                if (selected == null || horizon == null || configured == null || affected == null) message = "Local Reflow requires a configured profile, one FocusBlock ID, and an explicit horizon."
                else scope.launch {
                    preview = planner.localReflow(selected.id, Clock.System.now(), horizon, LocalReflowRequest(persistentListOf(affected), persistentListOf(), ZonedTimeRange(horizon.start, horizon.endExclusive, configured.timeZone)))
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
                Text("PlanBranch preview: ${result.branch.mutations.size} FocusBlock mutation(s)")
                result.branch.mutations.forEach { Text(it.toString()) }
                result.branch.issues.forEach { Text("PlannerIssue: $it") }
                Row {
                    Button(onClick = { scope.launch { when (val applied = planner.apply(result.branch, Clock.System.now())) {
                        is PlanBranchApplyResult.Applied -> { message = "PlanBranch applied atomically."; preview = null }
                        is PlanBranchApplyResult.Stale -> { preview = PlannerPreview.Applicable(applied.branch); message = "PlanBranch is stale; preview again before Apply." }
                        is PlanBranchApplyResult.BlockedBySyncConflict -> message = "PlanBranch intersects an unresolved sync conflict. Resolve it before applying."
                    } } }) { Text("Apply PlanBranch") }
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
private fun PlanningProfileDialog(existing: PlanningProfile?, settings: PlanningProfileSettingsService, onSaved: () -> Unit, onDismiss: () -> Unit) {
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
            if (existing == null) scope.launch { runCatching { settings.createUnconfigured(name) }.onSuccess { onSaved() }.onFailure { error = it.message } }
            else {
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
private fun Event?.eventKind(): EventKind = when (this?.time) { is ZonedTimeRange, null -> EventKind.ZONED; is AllDayRange -> EventKind.ALL_DAY; is FloatingTimeRange -> EventKind.FLOATING }
private fun Event?.startText(selectedDate: LocalDate): String = when (val time = this?.time) { is ZonedTimeRange -> time.start.toLocalDateTime(time.timeZone).toString(); is AllDayRange -> time.startDate.toString(); is FloatingTimeRange -> time.start.toString(); null -> if (eventKind() == EventKind.ALL_DAY) selectedDate.toString() else "" }
private fun Event?.endText(selectedDate: LocalDate): String = when (val time = this?.time) { is ZonedTimeRange -> time.endExclusive.toLocalDateTime(time.timeZone).toString(); is AllDayRange -> time.endDateExclusive.toString(); is FloatingTimeRange -> time.endExclusive.toString(); null -> if (eventKind() == EventKind.ALL_DAY) selectedDate.plus(1, DateTimeUnit.DAY).toString() else "" }
private fun Event?.timeZoneText(default: TimeZone): String = (this?.time as? ZonedTimeRange)?.timeZone?.id ?: default.id
private fun EventKind.next(): EventKind = EventKind.entries[(ordinal + 1) % EventKind.entries.size]
private fun Flexibility.next(): Flexibility = Flexibility.entries[(ordinal + 1) % Flexibility.entries.size]
private fun PinState.next(): PinState = PinState.entries[(ordinal + 1) % PinState.entries.size]
private fun TaskStatus.next(): TaskStatus = TaskStatus.entries[(ordinal + 1) % TaskStatus.entries.size]
private fun TaskPriority.next(): TaskPriority = TaskPriority.entries[(ordinal + 1) % TaskPriority.entries.size]
private fun DeadlinePolicy.next(): DeadlinePolicy = DeadlinePolicy.entries[(ordinal + 1) % DeadlinePolicy.entries.size]
private fun OverflowPolicy.next(): OverflowPolicy = OverflowPolicy.entries[(ordinal + 1) % OverflowPolicy.entries.size]
private fun DeadlineKind.next(): DeadlineKind = DeadlineKind.entries[(ordinal + 1) % DeadlineKind.entries.size]
private fun parseEventTime(kind: EventKind, start: String, end: String, zone: String): EventTimeInput? = when (kind) { EventKind.ZONED -> runCatching { EventTimeInput.Zoned(LocalDateTime.parse(start), LocalDateTime.parse(end), TimeZone.of(zone)) }.getOrNull(); EventKind.ALL_DAY -> runCatching { EventTimeInput.AllDay(LocalDate.parse(start), LocalDate.parse(end)) }.getOrNull(); EventKind.FLOATING -> runCatching { EventTimeInput.Floating(LocalDateTime.parse(start), LocalDateTime.parse(end)) }.getOrNull() }
private fun Task?.deadlineKind(): DeadlineKind? = when (this?.deadline?.deadline) { is Deadline.DateOnly -> DeadlineKind.DATE_ONLY; is Deadline.Exact -> DeadlineKind.EXACT; null -> null }
private fun Task?.deadlineValue(): String = when (val deadline = this?.deadline?.deadline) { is Deadline.DateOnly -> deadline.date.toString(); is Deadline.Exact -> deadline.at.toLocalDateTime(deadline.timeZone).toString(); null -> "" }
private fun Task?.deadlineZone(): String = (this?.deadline?.deadline as? Deadline.Exact)?.timeZone?.id ?: ""
private fun parseOptionalDuration(text: String): Duration? = if (text.isBlank()) null else runCatching { Duration.parse(text) }.getOrNull()
private fun parseRequiredDuration(text: String): Duration? = runCatching { Duration.parse(text) }.getOrNull()
private fun parseDeadline(enabled: Boolean, kind: DeadlineKind?, value: String, zone: String, policy: DeadlinePolicy, overflow: OverflowPolicy): TaskDeadlineInput? { if (!enabled) return null; return when (kind) { DeadlineKind.DATE_ONLY -> runCatching { TaskDeadlineInput.DateOnly(LocalDate.parse(value), policy, overflow) }.getOrNull(); DeadlineKind.EXACT -> runCatching { TaskDeadlineInput.Exact(LocalDateTime.parse(value), TimeZone.of(zone), policy, overflow) }.getOrNull(); null -> null } }
private fun emptyProjection() = CalendarProjectionResult(emptyList<CalendarItem>().toImmutableList(), emptyList<CalendarConflict>().toImmutableList(), emptyList<CalendarProjectionIssue>().toImmutableList())
