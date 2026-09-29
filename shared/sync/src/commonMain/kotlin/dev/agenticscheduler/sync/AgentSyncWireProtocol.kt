package dev.agenticscheduler.sync

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull

private val agentUuidV7Pattern = Regex("^[0-9a-f]{8}-[0-9a-f]{4}-7[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$")

/** Agent causality identity. It is intentionally not interchangeable with D7 [ReplicaId]. */
@JvmInline
@Serializable
value class AgentReplicaId(val value: String) {
    init { require(agentUuidV7Pattern.matches(value)) { "AgentReplicaId must be lowercase RFC UUIDv7 text." } }
}

private fun requireAgentRecordId(value: String) {
    require(agentUuidV7Pattern.matches(value)) { "Agent history IDs must be lowercase RFC UUIDv7 text." }
}

@JvmInline @Serializable value class AgentThreadSyncId(val value: String) { init { requireAgentRecordId(value) } }
@JvmInline @Serializable value class AgentMessageSyncId(val value: String) { init { requireAgentRecordId(value) } }
@JvmInline @Serializable value class AgentToolCallSyncId(val value: String) { init { requireAgentRecordId(value) } }
@JvmInline @Serializable value class AgentToolResultSyncId(val value: String) { init { requireAgentRecordId(value) } }
@JvmInline @Serializable value class AgentActionSyncId(val value: String) { init { requireAgentRecordId(value) } }
@JvmInline @Serializable value class AgentTurnSyncId(val value: String) { init { requireAgentRecordId(value) } }

@Serializable
data class AgentVersionComponent(val replicaId: AgentReplicaId, val counter: Long) {
    init { require(counter >= 0) }
}

@Serializable
data class AgentDot(val replicaId: AgentReplicaId, val counter: Long) {
    init { require(counter >= 0) }
}

/** V3 causal metadata uses its own replica identity namespace and canonical context order. */
@Serializable
data class AgentDvvSnapshot(val context: List<AgentVersionComponent>, val dot: AgentDot) {
    init {
        require(context == context.sortedBy { it.replicaId.value }) { "Agent DVV context must be sorted by AgentReplicaId." }
        require(context.map { it.replicaId }.distinct().size == context.size) { "Agent DVV context replica IDs must be unique." }
    }
}

@Serializable
data class AgentHlcSnapshot(val physicalMillis: Long, val logical: Long, val replicaId: AgentReplicaId) {
    init { require(logical >= 0) }
}

/** One encrypted V3 operation. This is an Agent history fact, never a D7 business SyncOperation. */
@Serializable
data class AgentSyncOperation(
    val operationId: MutationId,
    val agentDvv: AgentDvvSnapshot,
    val hlc: AgentHlcSnapshot,
    val agentEvent: AgentSyncEvent,
)

@Serializable
data class SyncPayloadV3(
    val payloadVersion: Int = AgentSyncWireCodec.PAYLOAD_VERSION,
    val operation: AgentSyncOperation,
)

/** Closed first-alpha event vocabulary. Provider/session identifiers and pending execution state have no fields here. */
@Serializable
sealed interface AgentSyncEvent

@Serializable
@SerialName("ThreadCreated")
data class ThreadCreated(
    val threadId: AgentThreadSyncId,
    val title: String?,
    val createdAtEpochMillis: Long,
) : AgentSyncEvent

@Serializable
@SerialName("ThreadTitleSet")
data class ThreadTitleSet(val threadId: AgentThreadSyncId, val title: String?) : AgentSyncEvent

@Serializable
@SerialName("MessageAppended")
data class MessageAppended(
    val messageId: AgentMessageSyncId,
    val threadId: AgentThreadSyncId,
    val turnId: AgentTurnSyncId,
    val role: AgentMessageRoleV3,
    val content: String,
    val createdAtEpochMillis: Long,
) : AgentSyncEvent

@Serializable
enum class AgentMessageRoleV3 { USER, ASSISTANT, TOOL }

@Serializable
@SerialName("ToolCallFinalized")
data class ToolCallFinalized(
    val callId: AgentToolCallSyncId,
    val threadId: AgentThreadSyncId,
    val turnId: AgentTurnSyncId,
    val sourceMessageId: AgentMessageSyncId,
    val toolName: String,
    val normalizedInputs: JsonObject,
    val status: FinalAgentToolCallStatus,
) : AgentSyncEvent {
    init { require(toolName.isNotBlank()) }
}

@Serializable
enum class FinalAgentToolCallStatus { COMPLETED, FAILED, DENIED }

@Serializable
@SerialName("ToolResultAppended")
data class ToolResultAppended(
    val resultId: AgentToolResultSyncId,
    val callId: AgentToolCallSyncId,
    val threadId: AgentThreadSyncId,
    val turnId: AgentTurnSyncId,
    val status: AgentToolResultStatusV3,
    val result: JsonElement,
    val businessMutationIds: List<MutationId>,
) : AgentSyncEvent

@Serializable
enum class AgentToolResultStatusV3 {
    SUCCESS, INVALID_INPUT, NOT_FOUND, PERMISSION_DENIED, STALE, UNSUPPORTED, CONFLICT, INFEASIBLE, INFRASTRUCTURE_FAILURE,
}

@Serializable
@SerialName("ActionFinalized")
data class ActionFinalized(
    val actionId: AgentActionSyncId,
    val threadId: AgentThreadSyncId?,
    val sourceMessageId: AgentMessageSyncId?,
    val toolCallIds: List<AgentToolCallSyncId>,
    val toolResultIds: List<AgentToolResultSyncId>,
    val businessMutationIds: List<MutationId>,
    val status: FinalAgentActionStatus,
) : AgentSyncEvent

@Serializable
enum class FinalAgentActionStatus { SUCCEEDED, FAILED, DENIED, STALE }

@Serializable
@SerialName("TurnFinalized")
data class TurnFinalized(
    val turnId: AgentTurnSyncId,
    val threadId: AgentThreadSyncId,
    val parentTurnIds: List<AgentTurnSyncId>,
    val orderedMembers: List<TurnMemberReference>,
    val outcome: AgentTurnOutcome,
) : AgentSyncEvent

@Serializable
sealed interface TurnMemberReference

@Serializable @SerialName("MESSAGE") data class MessageMember(val id: AgentMessageSyncId) : TurnMemberReference
@Serializable @SerialName("TOOL_CALL") data class ToolCallMember(val id: AgentToolCallSyncId) : TurnMemberReference
@Serializable @SerialName("TOOL_RESULT") data class ToolResultMember(val id: AgentToolResultSyncId) : TurnMemberReference
@Serializable @SerialName("ACTION") data class ActionMember(val id: AgentActionSyncId) : TurnMemberReference

@Serializable
enum class AgentTurnOutcome { SUCCEEDED, FAILED }

@Serializable
@SerialName("ThreadDeleted")
data class ThreadDeleted(val threadId: AgentThreadSyncId) : AgentSyncEvent

sealed interface AgentPayloadDecodeResult {
    data class Supported(val payload: SyncPayloadV3) : AgentPayloadDecodeResult
    data class UnsupportedVersion(val actual: Int?) : AgentPayloadDecodeResult
    data class UnsupportedEvent(val discriminator: String?) : AgentPayloadDecodeResult
    data class OuterIdMismatch(val expected: MutationId, val actual: MutationId) : AgentPayloadDecodeResult
    data object PayloadTooLarge : AgentPayloadDecodeResult
    data class Invalid(val reason: String) : AgentPayloadDecodeResult
}

class AgentSyncPayloadTooLargeException : IllegalArgumentException("AGENT_SYNC_PAYLOAD_TOO_LARGE")

/** D9-02 V3-only JSON codec. Deliberately does not dispatch or reinterpret D8 V1/V2 or envelope data. */
object AgentSyncWireCodec {
    const val PAYLOAD_VERSION: Int = 3
    const val MAX_PLAINTEXT_UTF8_BYTES: Int = 262_144

    private val json = Json {
        encodeDefaults = true
        ignoreUnknownKeys = true
        classDiscriminator = "type"
    }

    fun encodePayload(payload: SyncPayloadV3): String {
        require(payload.payloadVersion == PAYLOAD_VERSION) { "Only payload v$PAYLOAD_VERSION can be encoded." }
        val encoded = json.encodeToString(SyncPayloadV3.serializer(), payload)
        if (encoded.encodeToByteArray().size > MAX_PLAINTEXT_UTF8_BYTES) throw AgentSyncPayloadTooLargeException()
        return encoded
    }

    fun decodePayload(encoded: String, authenticatedMutationId: MutationId): AgentPayloadDecodeResult {
        if (encoded.encodeToByteArray().size > MAX_PLAINTEXT_UTF8_BYTES) return AgentPayloadDecodeResult.PayloadTooLarge
        val root = try { json.parseToJsonElement(encoded) as? JsonObject } catch (_: SerializationException) { null }
            ?: return AgentPayloadDecodeResult.Invalid("Payload is not a JSON object.")
        val versionValue = root["payloadVersion"]
        val versionPrimitive = versionValue as? JsonPrimitive
        if (versionValue != null && (versionPrimitive == null || versionPrimitive.isString || versionPrimitive.intOrNull == null)) {
            return AgentPayloadDecodeResult.Invalid("Payload version must be an integer.")
        }
        val version = versionPrimitive?.intOrNull
        if (version != PAYLOAD_VERSION) return AgentPayloadDecodeResult.UnsupportedVersion(version)
        val operationJson = root["operation"] as? JsonObject
        val eventJson = operationJson?.get("agentEvent") as? JsonObject
        val eventTypeValue = eventJson?.get("type")
        val eventTypePrimitive = eventTypeValue as? JsonPrimitive
        if (eventTypeValue != null && (eventTypePrimitive == null || !eventTypePrimitive.isString)) {
            return AgentPayloadDecodeResult.Invalid("Agent event discriminator must be a string.")
        }
        val eventType = eventTypePrimitive?.contentOrNull
        if (eventType != null && eventType !in knownEventTypes) return AgentPayloadDecodeResult.UnsupportedEvent(eventType)
        return try {
            val payload = json.decodeFromJsonElement(SyncPayloadV3.serializer(), root)
            if (payload.operation.operationId != authenticatedMutationId) {
                AgentPayloadDecodeResult.OuterIdMismatch(authenticatedMutationId, payload.operation.operationId)
            } else {
                AgentPayloadDecodeResult.Supported(payload)
            }
        } catch (failure: SerializationException) {
            AgentPayloadDecodeResult.Invalid(failure.message ?: "V3 payload cannot be decoded.")
        } catch (failure: IllegalArgumentException) {
            AgentPayloadDecodeResult.Invalid(failure.message ?: "V3 payload violates its wire contract.")
        }
    }

    private val knownEventTypes = setOf(
        "ThreadCreated", "ThreadTitleSet", "MessageAppended", "ToolCallFinalized",
        "ToolResultAppended", "ActionFinalized", "TurnFinalized", "ThreadDeleted",
    )
}
