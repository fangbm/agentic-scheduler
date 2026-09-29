package dev.agenticscheduler.agent.tool

import dev.agenticscheduler.agent.history.AgentActionId
import dev.agenticscheduler.agent.permission.AgentPermissionDecision
import dev.agenticscheduler.agent.permission.AgentPermissionEngine
import dev.agenticscheduler.agent.permission.AgentPermissionPolicy
import dev.agenticscheduler.agent.permission.AgentToolCapability
import dev.agenticscheduler.application.editing.CreateEventInput
import dev.agenticscheduler.application.editing.EditingIssue
import dev.agenticscheduler.application.editing.EditingResult
import dev.agenticscheduler.application.editing.EventCreatePreview
import dev.agenticscheduler.application.editing.EventEditingService
import dev.agenticscheduler.application.editing.EventTimeInput
import dev.agenticscheduler.application.editing.EventUpdatePreview
import dev.agenticscheduler.application.editing.UpdateEventInput
import dev.agenticscheduler.application.history.AgentOriginWriteNotAllowed
import dev.agenticscheduler.application.history.MutationExecution
import dev.agenticscheduler.domain.event.Event
import dev.agenticscheduler.domain.id.EventId
import dev.agenticscheduler.domain.planning.Flexibility
import dev.agenticscheduler.domain.planning.PinState
import dev.agenticscheduler.domain.time.AllDayRange
import dev.agenticscheduler.domain.time.FloatingTimeRange
import dev.agenticscheduler.domain.time.TimePlacement
import dev.agenticscheduler.domain.time.ZonedTimeRange
import dev.agenticscheduler.sync.MutationId
import dev.agenticscheduler.sync.MutationOrigin
import kotlinx.coroutines.CancellationException
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.time.Instant

const val EVENT_CREATE_TOOL_NAME = "event.create"
const val EVENT_UPDATE_TOOL_NAME = "event.update"

/** The fields are a tagged union. Only the fields for [kind] may be populated. */
@Serializable
data class EventTimeToolInput(
    val kind: String,
    val start: String? = null,
    val endExclusive: String? = null,
    val startDate: String? = null,
    val endDateExclusive: String? = null,
    val timeZone: String? = null,
)

@Serializable
data class EventCreateToolInput(
    val title: String,
    val time: EventTimeToolInput,
    val flexibility: String,
    val pinState: String,
)

@Serializable
data class EventUpdateToolInput(
    val eventId: String,
    val title: String,
    val time: EventTimeToolInput,
    val flexibility: String,
    val pinState: String,
)

data class EventCreateWritePreview(val before: Event? = null, val after: EventCreatePreview)
data class CommittedEventCreate(val event: Event, val mutationId: MutationId)
data class CommittedEventUpdate(val event: Event, val mutationId: MutationId)

/** Typed Event create; validation precedes permission and IDs are allocated only by the command. */
class EventCreateTool(
    private val editing: EventEditingService,
    private val permissions: AgentPermissionEngine = AgentPermissionEngine(),
) {
    val metadata = AgentToolMetadata(EVENT_CREATE_TOOL_NAME, AgentToolCapability.LOW_RISK_CREATE, AgentToolAccess.WRITE)
    private val json = Json { ignoreUnknownKeys = false; explicitNulls = true }

    suspend fun prepare(argumentsJson: String, policy: AgentPermissionPolicy): AgentToolOutcome<EventCreateWritePreview> {
        val input = when (val decoded = decode(argumentsJson)) {
            is Decoded.Invalid -> return decoded.outcome
            is Decoded.Valid -> decoded.value
        }
        val preview = when (val result = editing.previewCreate(input)) {
            is EditingResult.Success -> EventCreateWritePreview(after = result.value)
            is EditingResult.Invalid -> return AgentToolOutcome.InvalidInput(result.issues.map { it.toEventToolIssue() })
            else -> return AgentToolOutcome.InfrastructureFailure("UNEXPECTED_PREVIEW_RESULT")
        }
        return permissionOutcome(preview, policy)
    }

    suspend fun commit(
        argumentsJson: String,
        shownPreview: EventCreateWritePreview,
        userConfirmed: Boolean,
        policy: AgentPermissionPolicy,
        agentActionId: AgentActionId,
        onCommitted: suspend (MutationExecution<EditingResult<Event>>) -> Unit = {},
    ): AgentToolOutcome<CommittedEventCreate> {
        val prepared = prepare(argumentsJson, policy)
        val current = when (prepared) {
            is AgentToolOutcome.Success -> prepared.payload
            is AgentToolOutcome.ConfirmationRequired -> prepared.preview
            is AgentToolOutcome.InvalidInput -> return prepared
            is AgentToolOutcome.PermissionDenied -> return prepared
            else -> return AgentToolOutcome.InfrastructureFailure("UNEXPECTED_PREVIEW_RESULT")
        }
        if (current != shownPreview) return AgentToolOutcome.Stale
        if (prepared is AgentToolOutcome.ConfirmationRequired && !userConfirmed) {
            return AgentToolOutcome.PermissionDenied(metadata.capability)
        }
        val input = (decode(argumentsJson) as Decoded.Valid).value
        return try {
            when (val result = editing.create(input, MutationOrigin.Agent(agentActionId.value), onCommitted)) {
                is EditingResult.Success -> AgentToolOutcome.Success(CommittedEventCreate(result.value, requireNotNull(result.mutationId)))
                is EditingResult.Invalid -> AgentToolOutcome.InvalidInput(result.issues.map { it.toEventToolIssue() })
                is EditingResult.BlockedBySyncConflict -> AgentToolOutcome.Conflict(result.blocks.map { it.conflictId })
                EditingResult.NotFound -> AgentToolOutcome.InfrastructureFailure("UNEXPECTED_NOT_FOUND")
                EditingResult.Stale -> AgentToolOutcome.InfrastructureFailure("UNEXPECTED_STALE")
            }
        } catch (_: AgentOriginWriteNotAllowed) {
            AgentToolOutcome.PermissionDenied(metadata.capability)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            AgentToolOutcome.InfrastructureFailure("EVENT_CREATE_TRANSACTION")
        }
    }

    fun normalizedPreviewJson(preview: EventCreateWritePreview): String = json.encodeToString(
        CreatePreviewSnapshot(before = null, after = preview.after.toSnapshot()),
    )

    private fun permissionOutcome(preview: EventCreateWritePreview, policy: AgentPermissionPolicy): AgentToolOutcome<EventCreateWritePreview> =
        when (permissions.evaluate(policy, metadata.capability)) {
            AgentPermissionDecision.AllowDirect -> AgentToolOutcome.Success(preview)
            AgentPermissionDecision.RequireConfirmation -> AgentToolOutcome.ConfirmationRequired(preview)
            AgentPermissionDecision.Deny -> AgentToolOutcome.PermissionDenied(metadata.capability)
        }

    private fun decode(arguments: String): Decoded {
        val value = try { json.decodeFromString(EventCreateToolInput.serializer(), arguments) }
        catch (_: SerializationException) { return Decoded.Invalid(invalid("arguments", "INVALID_JSON")) }
        catch (_: IllegalArgumentException) { return Decoded.Invalid(invalid("arguments", "INVALID_JSON")) }
        val issues = mutableListOf<AgentToolInputIssue>()
        val time = decodeTime(value.time, issues)
        val flexibility = Flexibility.entries.firstOrNull { it.name == value.flexibility }
            ?: run { issues += AgentToolInputIssue("flexibility", "UNKNOWN_VALUE"); null }
        val pin = PinState.entries.firstOrNull { it.name == value.pinState }
            ?: run { issues += AgentToolInputIssue("pinState", "UNKNOWN_VALUE"); null }
        if (issues.isNotEmpty()) return Decoded.Invalid(AgentToolOutcome.InvalidInput(issues))
        return Decoded.Valid(CreateEventInput(value.title, time, requireNotNull(flexibility), requireNotNull(pin)))
    }

    private sealed interface Decoded {
        data class Valid(val value: CreateEventInput) : Decoded
        data class Invalid(val outcome: AgentToolOutcome.InvalidInput) : Decoded
    }
}

/** Typed Event update with preview equality and an in-transaction before-image guard. */
class EventUpdateTool(
    private val editing: EventEditingService,
    private val permissions: AgentPermissionEngine = AgentPermissionEngine(),
) {
    val metadata = AgentToolMetadata(EVENT_UPDATE_TOOL_NAME, AgentToolCapability.SOURCE_FACT_UPDATE, AgentToolAccess.WRITE)
    private val json = Json { ignoreUnknownKeys = false; explicitNulls = true }

    suspend fun prepare(argumentsJson: String, policy: AgentPermissionPolicy): AgentToolOutcome<EventUpdatePreview> {
        val input = when (val decoded = decode(argumentsJson)) {
            is Decoded.Invalid -> return decoded.outcome
            is Decoded.Valid -> decoded.value
        }
        val preview = when (val result = editing.previewUpdate(input)) {
            is EditingResult.Success -> result.value
            is EditingResult.Invalid -> return AgentToolOutcome.InvalidInput(result.issues.map { it.toEventToolIssue() })
            EditingResult.NotFound -> return AgentToolOutcome.NotFound
            else -> return AgentToolOutcome.InfrastructureFailure("UNEXPECTED_PREVIEW_RESULT")
        }
        return when (permissions.evaluate(policy, metadata.capability)) {
            AgentPermissionDecision.AllowDirect -> AgentToolOutcome.Success(preview)
            AgentPermissionDecision.RequireConfirmation -> AgentToolOutcome.ConfirmationRequired(preview)
            AgentPermissionDecision.Deny -> AgentToolOutcome.PermissionDenied(metadata.capability)
        }
    }

    suspend fun commit(
        argumentsJson: String,
        shownPreview: EventUpdatePreview,
        userConfirmed: Boolean,
        policy: AgentPermissionPolicy,
        agentActionId: AgentActionId,
        onCommitted: suspend (MutationExecution<EditingResult<Event>>) -> Unit = {},
    ): AgentToolOutcome<CommittedEventUpdate> {
        val prepared = prepare(argumentsJson, policy)
        val current = when (prepared) {
            is AgentToolOutcome.Success -> prepared.payload
            is AgentToolOutcome.ConfirmationRequired -> prepared.preview
            is AgentToolOutcome.InvalidInput -> return prepared
            is AgentToolOutcome.PermissionDenied -> return prepared
            AgentToolOutcome.NotFound -> return AgentToolOutcome.NotFound
            else -> return AgentToolOutcome.InfrastructureFailure("UNEXPECTED_PREVIEW_RESULT")
        }
        if (current != shownPreview) return AgentToolOutcome.Stale
        if (prepared is AgentToolOutcome.ConfirmationRequired && !userConfirmed) {
            return AgentToolOutcome.PermissionDenied(metadata.capability)
        }
        val input = (decode(argumentsJson) as Decoded.Valid).value
        return try {
            when (val result = editing.update(input, MutationOrigin.Agent(agentActionId.value), onCommitted, expectedBefore = shownPreview.before)) {
                is EditingResult.Success -> AgentToolOutcome.Success(CommittedEventUpdate(result.value, requireNotNull(result.mutationId)))
                is EditingResult.Invalid -> AgentToolOutcome.InvalidInput(result.issues.map { it.toEventToolIssue() })
                EditingResult.NotFound -> AgentToolOutcome.NotFound
                EditingResult.Stale -> AgentToolOutcome.Stale
                is EditingResult.BlockedBySyncConflict -> AgentToolOutcome.Conflict(result.blocks.map { it.conflictId })
            }
        } catch (_: AgentOriginWriteNotAllowed) {
            AgentToolOutcome.PermissionDenied(metadata.capability)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            AgentToolOutcome.InfrastructureFailure("EVENT_UPDATE_TRANSACTION")
        }
    }

    fun normalizedPreviewJson(preview: EventUpdatePreview): String = json.encodeToString(
        UpdatePreviewSnapshot(preview.before.toSnapshot(), preview.after.toSnapshot()),
    )

    private fun decode(arguments: String): Decoded {
        val value = try { json.decodeFromString(EventUpdateToolInput.serializer(), arguments) }
        catch (_: SerializationException) { return Decoded.Invalid(invalid("arguments", "INVALID_JSON")) }
        catch (_: IllegalArgumentException) { return Decoded.Invalid(invalid("arguments", "INVALID_JSON")) }
        val issues = mutableListOf<AgentToolInputIssue>()
        val id = runCatching { EventId(value.eventId) }.getOrNull()
            ?: run { issues += AgentToolInputIssue("eventId", "INVALID_ID"); null }
        val time = decodeTime(value.time, issues)
        val flexibility = Flexibility.entries.firstOrNull { it.name == value.flexibility }
            ?: run { issues += AgentToolInputIssue("flexibility", "UNKNOWN_VALUE"); null }
        val pin = PinState.entries.firstOrNull { it.name == value.pinState }
            ?: run { issues += AgentToolInputIssue("pinState", "UNKNOWN_VALUE"); null }
        if (issues.isNotEmpty()) return Decoded.Invalid(AgentToolOutcome.InvalidInput(issues))
        return Decoded.Valid(UpdateEventInput(requireNotNull(id), value.title, time, requireNotNull(flexibility), requireNotNull(pin)))
    }

    private sealed interface Decoded {
        data class Valid(val value: UpdateEventInput) : Decoded
        data class Invalid(val outcome: AgentToolOutcome.InvalidInput) : Decoded
    }
}

private fun decodeTime(value: EventTimeToolInput, issues: MutableList<AgentToolInputIssue>): EventTimeInput? {
    fun irrelevant(vararg names: Pair<String, String?>) {
        names.filter { it.second != null }.forEach { (field) -> issues += AgentToolInputIssue("time.$field", "NOT_APPLICABLE") }
    }
    fun required(raw: String?, field: String): String? = raw ?: run {
        issues += AgentToolInputIssue("time.$field", "REQUIRED")
        null
    }
    return try {
        when (value.kind) {
            "ZONED" -> {
                irrelevant("startDate" to value.startDate, "endDateExclusive" to value.endDateExclusive)
                val start = required(value.start, "start")
                val end = required(value.endExclusive, "endExclusive")
                val zone = required(value.timeZone, "timeZone")
                if (start == null || end == null || zone == null) null else EventTimeInput.Zoned(
                    LocalDateTime.parse(start), LocalDateTime.parse(end), TimeZone.of(zone),
                )
            }
            "ALL_DAY" -> {
                irrelevant("start" to value.start, "endExclusive" to value.endExclusive, "timeZone" to value.timeZone)
                val start = required(value.startDate, "startDate")
                val end = required(value.endDateExclusive, "endDateExclusive")
                if (start == null || end == null) null else EventTimeInput.AllDay(LocalDate.parse(start), LocalDate.parse(end))
            }
            "FLOATING" -> {
                irrelevant("startDate" to value.startDate, "endDateExclusive" to value.endDateExclusive, "timeZone" to value.timeZone)
                val start = required(value.start, "start")
                val end = required(value.endExclusive, "endExclusive")
                if (start == null || end == null) null else EventTimeInput.Floating(LocalDateTime.parse(start), LocalDateTime.parse(end))
            }
            else -> {
                issues += AgentToolInputIssue("time.kind", "UNKNOWN_VALUE")
                null
            }
        }
    } catch (_: IllegalArgumentException) {
        issues += AgentToolInputIssue("time", "INVALID_VALUE")
        null
    }
}

private fun Event.toSnapshot() = EventSnapshot(
    id = id.value,
    title = title,
    time = time.toSnapshot(),
    flexibility = flexibility.name,
    pinState = pinState.name,
)

private fun EventCreatePreview.toSnapshot() = EventSnapshot(
    id = null,
    title = title,
    time = time.toSnapshot(),
    flexibility = flexibility.name,
    pinState = pinState.name,
)

private fun TimePlacement.toSnapshot(): String = when (this) {
    is ZonedTimeRange -> "ZONED:$start:$endExclusive:${timeZone.id}"
    is AllDayRange -> "ALL_DAY:$startDate:$endDateExclusive"
    is FloatingTimeRange -> "FLOATING:$start:$endExclusive"
}

private fun EditingIssue.toEventToolIssue(): AgentToolInputIssue = when (this) {
    EditingIssue.BlankTitle -> AgentToolInputIssue("title", "BLANK")
    EditingIssue.MissingEventTime -> AgentToolInputIssue("time", "REQUIRED")
    EditingIssue.InvalidTimeRange -> AgentToolInputIssue("time", "INVALID_RANGE")
    is EditingIssue.ZonedTimeTransitionRejected -> AgentToolInputIssue(
        if (field.name == "START") "time.start" else "time.endExclusive", "ZONED_TIME_TRANSITION",
    )
    is EditingIssue.InvalidEffort -> AgentToolInputIssue(field.name.lowercase(), "INVALID")
}

private fun invalid(field: String, code: String) = AgentToolOutcome.InvalidInput(listOf(AgentToolInputIssue(field, code)))

@Serializable
private data class EventSnapshot(
    val id: String?,
    val title: String,
    val time: String,
    val flexibility: String,
    val pinState: String,
)

@Serializable
private data class CreatePreviewSnapshot(val before: String? = null, val after: EventSnapshot)

@Serializable
private data class UpdatePreviewSnapshot(val before: EventSnapshot, val after: EventSnapshot)
