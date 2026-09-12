package dev.agenticscheduler.application.editing

import dev.agenticscheduler.application.id.EpochMillisecondsClock
import dev.agenticscheduler.application.id.RandomBytes
import dev.agenticscheduler.application.id.RfcUuidV7Generator
import dev.agenticscheduler.application.persistence.ApplicationTransactionRunner
import dev.agenticscheduler.application.persistence.EventRepository
import dev.agenticscheduler.application.persistence.TaskRepository
import dev.agenticscheduler.domain.event.Event
import dev.agenticscheduler.domain.id.EventId
import dev.agenticscheduler.domain.id.FocusBlockId
import dev.agenticscheduler.domain.id.TaskDependencyId
import dev.agenticscheduler.domain.id.TaskId
import dev.agenticscheduler.domain.id.WorkLogId
import dev.agenticscheduler.domain.planning.DeadlinePolicy
import dev.agenticscheduler.domain.planning.Flexibility
import dev.agenticscheduler.domain.planning.OverflowPolicy
import dev.agenticscheduler.domain.planning.PinState
import dev.agenticscheduler.domain.task.FocusBlock
import dev.agenticscheduler.domain.task.Task
import dev.agenticscheduler.domain.task.TaskDependency
import dev.agenticscheduler.domain.task.TaskPriority
import dev.agenticscheduler.domain.task.TaskStatus
import dev.agenticscheduler.domain.task.WorkLog
import dev.agenticscheduler.domain.time.AllDayRange
import dev.agenticscheduler.domain.time.FloatingTimeRange
import dev.agenticscheduler.domain.time.ZonedTimeRange
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours

class EditingTest {
    @Test
    fun `UUIDv7 generator uses the frozen timestamp version and variant layout`() {
        val value = RfcUuidV7Generator(
            EpochMillisecondsClock { fixedEpochMilliseconds },
            RandomBytes { size -> ByteArray(size) { (it + 1).toByte() } },
        ).next()

        assertTrue(value.matches(Regex("[0-9a-f]{8}-[0-9a-f]{4}-7[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}")))
        assertTrue(value.startsWith("018f6e68-7d0c-7"))
    }

    @Test
    fun `Event creation and editing preserve identity across every time variant`() {
        runBlocking {
        val repository = FakeEventRepository()
        val transactions = CountingTransactions()
        val service = EventEditingService(repository, transactions, generator())

        val created = assertIs<Event>(assertIs<EditingResult.Success<*>>(service.create(eventInput(EventTimeInput.Zoned(
            LocalDateTime(2026, 9, 10, 8, 0),
            LocalDateTime(2026, 9, 10, 9, 0),
            TimeZone.UTC,
        )))).value)
        assertEquals(1, transactions.writes)
        assertEquals(created, repository.get(created.id))
        assertIs<ZonedTimeRange>(created.time)

        val zoned = assertIs<Event>(assertIs<EditingResult.Success<*>>(service.update(UpdateEventInput(
            created.id,
            "Zoned edited",
            EventTimeInput.Zoned(
                LocalDateTime(2026, 9, 10, 10, 0),
                LocalDateTime(2026, 9, 10, 11, 0),
                TimeZone.UTC,
            ),
            Flexibility.HARD,
            PinState.UNPINNED,
        ))).value)
        assertEquals(created.id, zoned.id)
        assertIs<ZonedTimeRange>(zoned.time)

        val allDay = assertIs<Event>(assertIs<EditingResult.Success<*>>(service.update(UpdateEventInput(
            created.id,
            "All day",
            EventTimeInput.AllDay(LocalDate(2026, 9, 11), LocalDate(2026, 9, 12)),
            Flexibility.FLEXIBLE,
            PinState.PINNED,
        ))).value)
        assertEquals(created.id, allDay.id)
        assertIs<AllDayRange>(allDay.time)

        val floating = assertIs<Event>(assertIs<EditingResult.Success<*>>(service.update(UpdateEventInput(
            created.id,
            "Floating",
            EventTimeInput.Floating(LocalDateTime(2026, 9, 12, 8, 0), LocalDateTime(2026, 9, 12, 9, 0)),
            Flexibility.SOFT,
            PinState.UNPINNED,
        ))).value)
        assertEquals(created.id, floating.id)
        assertIs<FloatingTimeRange>(floating.time)
        assertEquals(Flexibility.SOFT, floating.flexibility)
        }
    }

    @Test
    fun `Event validation rejects blank missing and DST transition input`() {
        runBlocking {
        val service = EventEditingService(FakeEventRepository(), CountingTransactions(), generator())

        assertIs<EditingResult.Invalid>(service.create(eventInput(null, title = " ")))
        assertIs<EditingResult.Invalid>(service.create(eventInput(null)))

        val newYork = TimeZone.of("America/New_York")
        val nonexistent = service.create(eventInput(EventTimeInput.Zoned(
            LocalDateTime(2026, 3, 8, 2, 30),
            LocalDateTime(2026, 3, 8, 3, 30),
            newYork,
        )))
        val ambiguous = service.create(eventInput(EventTimeInput.Zoned(
            LocalDateTime(2026, 11, 1, 1, 30),
            LocalDateTime(2026, 11, 1, 2, 30),
            newYork,
        )))

        assertIs<EditingResult.Invalid>(nonexistent)
        assertIs<EditingResult.Invalid>(ambiguous)
        }
    }

    @Test
    fun `Task creation defaults and edits preserve independent effort and deadline facts`() {
        runBlocking {
        val repository = FakeTaskRepository()
        val service = TaskEditingService(repository, CountingTransactions(), generator())

        val created = assertIs<Task>(assertIs<EditingResult.Success<*>>(service.create(CreateTaskInput(
            title = "Write report",
            priority = TaskPriority.NORMAL,
            estimated = 2.hours,
            remaining = null,
            deadline = null,
        ))).value)
        assertEquals(TaskStatus.OPEN, created.status)
        assertEquals(TaskPriority.NORMAL, created.priority)
        assertEquals(Duration.ZERO, created.effort.completed)
        assertEquals(2.hours, created.effort.estimated)
        assertNull(created.effort.remaining)
        assertNull(created.deadline)

        val updated = assertIs<Task>(assertIs<EditingResult.Success<*>>(service.update(UpdateTaskInput(
            id = created.id,
            title = "Write final report",
            status = TaskStatus.IN_PROGRESS,
            priority = TaskPriority.HIGH,
            estimated = 2.hours,
            completed = 1.hours,
            remaining = 5.hours,
            deadline = TaskDeadlineInput.DateOnly(
                LocalDate(2026, 9, 15),
                DeadlinePolicy.NORMAL,
                OverflowPolicy.ASK,
            ),
        ))).value)
        assertEquals(created.id, updated.id)
        assertEquals(5.hours, updated.effort.remaining)
        assertIs<dev.agenticscheduler.domain.planning.Deadline.DateOnly>(updated.deadline!!.deadline)

        val exactDeadline = assertIs<Task>(assertIs<EditingResult.Success<*>>(service.update(UpdateTaskInput(
            id = created.id,
            title = updated.title,
            status = updated.status,
            priority = updated.priority,
            estimated = updated.effort.estimated,
            completed = updated.effort.completed,
            remaining = updated.effort.remaining,
            deadline = TaskDeadlineInput.Exact(
                LocalDateTime(2026, 9, 16, 12, 0),
                TimeZone.UTC,
                DeadlinePolicy.NORMAL,
                OverflowPolicy.ASK,
            ),
        ))).value)
        val exact = assertIs<dev.agenticscheduler.domain.planning.Deadline.Exact>(exactDeadline.deadline!!.deadline)
        assertEquals(LocalDateTime(2026, 9, 16, 12, 0), exact.at.toLocalDateTime(exact.timeZone))
        assertEquals(TimeZone.UTC, exact.timeZone)
        }
    }

    @Test
    fun `updates return NotFound without creating a replacement entity`() {
        runBlocking {
        val eventRepository = FakeEventRepository()
        val taskRepository = FakeTaskRepository()
        val eventService = EventEditingService(eventRepository, CountingTransactions(), generator())
        val taskService = TaskEditingService(taskRepository, CountingTransactions(), generator())

        assertEquals(
            EditingResult.NotFound,
            eventService.update(UpdateEventInput(EventId(id(50)), "Missing", EventTimeInput.AllDay(LocalDate(2026, 9, 1), LocalDate(2026, 9, 2)), Flexibility.HARD, PinState.UNPINNED)),
        )
        assertEquals(
            EditingResult.NotFound,
            taskService.update(UpdateTaskInput(TaskId(id(51)), "Missing", TaskStatus.OPEN, TaskPriority.NORMAL, null, Duration.ZERO, null, null)),
        )
        assertNull(eventRepository.get(EventId(id(50))))
        assertNull(taskRepository.getTask(TaskId(id(51))))
        }
    }

    @Test
    fun `Task validation rejects invalid effort without writing`() {
        runBlocking {
        val repository = FakeTaskRepository()
        val transactions = CountingTransactions()
        val result = TaskEditingService(repository, transactions, generator()).create(
            CreateTaskInput("Invalid", TaskPriority.NORMAL, (-1).hours, null, null),
        )

        assertIs<EditingResult.Invalid>(result)
        assertEquals(0, transactions.writes)
        assertEquals(0, repository.taskCount())
        }
    }

    private fun eventInput(time: EventTimeInput?, title: String = "Meeting") = CreateEventInput(
        title = title,
        time = time,
        flexibility = Flexibility.HARD,
        pinState = PinState.UNPINNED,
    )

    private fun generator() = RfcUuidV7Generator(
        EpochMillisecondsClock { fixedEpochMilliseconds },
        RandomBytes { size -> ByteArray(size) { (it + 1).toByte() } },
    )
}

private const val fixedEpochMilliseconds = 0x018F6E687D0C

private class CountingTransactions : ApplicationTransactionRunner {
    var writes = 0

    override suspend fun <T> inWriteTransaction(block: suspend () -> T): T {
        writes += 1
        return block()
    }
}

private class FakeEventRepository : EventRepository {
    private val values = linkedMapOf<EventId, Event>()
    private val flow = MutableStateFlow<ImmutableList<Event>>(emptyList<Event>().toImmutableList())

    override fun observeAll(): Flow<ImmutableList<Event>> = flow
    override suspend fun get(id: EventId): Event? = values[id]
    override suspend fun upsert(event: Event) {
        values[event.id] = event
        flow.value = values.values.sortedBy { it.id.value }.toImmutableList()
    }
}

private class FakeTaskRepository : TaskRepository {
    private val tasks = linkedMapOf<TaskId, Task>()
    private val taskFlow = MutableStateFlow<ImmutableList<Task>>(emptyList<Task>().toImmutableList())
    private val focusFlow = MutableStateFlow<ImmutableList<FocusBlock>>(emptyList<FocusBlock>().toImmutableList())
    private val workLogFlow = MutableStateFlow<ImmutableList<WorkLog>>(emptyList<WorkLog>().toImmutableList())
    private val dependencyFlow = MutableStateFlow<ImmutableList<TaskDependency>>(emptyList<TaskDependency>().toImmutableList())

    override fun observeTasks(): Flow<ImmutableList<Task>> = taskFlow
    override suspend fun getTask(id: TaskId): Task? = tasks[id]
    override suspend fun upsertTask(task: Task) {
        tasks[task.id] = task
        taskFlow.value = tasks.values.sortedBy { it.id.value }.toImmutableList()
    }
    fun taskCount(): Int = tasks.size
    override fun observeFocusBlocks(): Flow<ImmutableList<FocusBlock>> = focusFlow
    override suspend fun getFocusBlock(id: FocusBlockId): FocusBlock? = null
    override suspend fun upsertFocusBlock(focusBlock: FocusBlock) = Unit
    override suspend fun deleteFocusBlock(id: FocusBlockId) = Unit
    override fun observeWorkLogs(): Flow<ImmutableList<WorkLog>> = workLogFlow
    override suspend fun getWorkLog(id: WorkLogId): WorkLog? = null
    override suspend fun upsertWorkLog(workLog: WorkLog) = Unit
    override fun observeDependencies(): Flow<ImmutableList<TaskDependency>> = dependencyFlow
    override suspend fun getDependency(id: TaskDependencyId): TaskDependency? = null
    override suspend fun upsertDependency(dependency: TaskDependency) = Unit
}

private fun id(number: Int): String = "018f6e68-7d0c-7000-8000-${number.toString(16).padStart(12, '0')}"
