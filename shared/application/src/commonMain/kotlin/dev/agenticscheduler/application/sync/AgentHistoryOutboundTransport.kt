package dev.agenticscheduler.application.sync

import dev.agenticscheduler.application.persistence.HistoryRepository
import dev.agenticscheduler.application.persistence.SyncOutboundEnvelopeRepository
import dev.agenticscheduler.application.persistence.SyncReceiveRepository
import dev.agenticscheduler.sync.*
import kotlin.io.encoding.Base64

data class AgentTransportProgress(
    val uploaded: Int = 0,
    val heldOperationIds: List<String> = emptyList(),
    val consentOff: Boolean = false,
    val failure: SyncUploadResult? = null,
)

/** Deployment-supplied relay bounds, in addition to the frozen V3 plaintext cap. */
data class AgentEnvelopeUploadLimits(val maxCiphertextBytes: Int, val maxRequestBodyBytes: Int) {
    init { require(maxCiphertextBytes > 0 && maxRequestBodyBytes > 0) }
}

/** Explicit integration seam; production hosts do not inject it while OD-012 is open. */
class AgentHistoryOutboundTransport(
    private val persistence: AgentSyncPersistence,
    private val state: AgentSyncTransportPersistence,
    private val history: HistoryRepository,
    private val businessOutbound: SyncOutboundEnvelopeRepository,
    private val businessReceive: SyncReceiveRepository,
    private val codec: AuthenticatedSyncEnvelopeCodec,
    private val keys: CurrentEncryptionKeyProvider,
    private val deviceId: DeviceId,
    private val transport: SyncTransport,
    private val limits: AgentEnvelopeUploadLimits,
) {
    suspend fun upload(space: SyncSpaceId): AgentTransportProgress {
        var uploaded = 0
        val held = mutableSetOf<String>()
        try {
            val records = state.outboundRecords(space)
            val pending = records.filter { it.state != AgentOutboundTransportState.UPLOADED }
            if (!state.conversationConsent(space)) {
                state.setHeld(space, pending.map { it.operation.operationId.value }, true)
                return AgentTransportProgress(heldOperationIds = pending.map { it.operation.operationId.value }, consentOff = true)
            }
            val all = records.map { it.operation }
            val manifests = all.mapNotNull { op -> (op.agentEvent as? TurnFinalized)?.let { op to it } }
            val units = mutableListOf<List<AgentOutboundTransportRecord>>()
            val assigned = mutableSetOf<String>()
            for ((manifestOp, manifest) in manifests) {
                val members = records.filter { it.operation.agentEvent.turnIdForTransport() == manifest.turnId }
                assigned += members.map { it.operation.operationId.value }
                val memberRefs = members.filter { it.operation.agentEvent !is TurnFinalized }.mapNotNull { it.operation.agentEvent.memberForTransport() }
                val complete = memberRefs.toSet() == manifest.orderedMembers.toSet() && memberRefs.size == manifest.orderedMembers.size &&
                    members.all { it.operation.agentEvent.threadIdForTransport() == manifest.threadId } &&
                    agentHistoryTurnLinkError(manifest, members.filter { it.operation.agentEvent !is TurnFinalized }.map { it.operation.agentEvent }) == null
                val parentsAvailable = manifest.parentTurnIds.all { parent ->
                    manifests.any { it.second.turnId == parent } || persistence.turnState(space, parent.value) in
                        setOf(AgentSyncTurnState.COMPLETE_VERIFIED, AgentSyncTurnState.ACTIVE, AgentSyncTurnState.TOMBSTONED)
                }
                val dependenciesShared = members.flatMap { it.operation.agentEvent.businessReferences() }.distinct().all { shared(space, it.value) }
                val otherDependencies = members.any { persistence.pendingDependencies(space, it.operation.operationId.value).any { it.kind != AgentSyncDependencyKind.BUSINESS_MUTATION } }
                if (!complete || !parentsAvailable || !dependenciesShared || otherDependencies) held += members.map { it.operation.operationId.value }
                else units += members.sortedWith(compareBy<AgentOutboundTransportRecord> { it.operation.operationId == manifestOp.operationId }
                    .thenBy { it.operation.agentDvv.dot.counter }.thenBy { it.operation.operationId.value })
            }
            for (record in pending.filter { it.operation.operationId.value !in assigned }) {
                if (record.operation.agentEvent.turnIdForTransport() != null || !record.operation.agentEvent.businessReferences().all { shared(space, it.value) } ||
                    persistence.pendingDependencies(space, record.operation.operationId.value).any { it.kind != AgentSyncDependencyKind.BUSINESS_MUTATION })
                    held += record.operation.operationId.value
                else units += listOf(record)
            }
            // D1: never silently publish erased private predecessors. A delete observing them also stays pending.
            val deletedThreads = all.mapNotNull { (it.agentEvent as? ThreadDeleted)?.threadId }.toMutableSet()
            for (thread in pending.mapNotNull { it.operation.agentEvent.threadIdForTransport() }.distinct()) {
                if (persistence.isThreadTombstoned(space, thread.value)) deletedThreads += thread
            }
            val erasedUnpublished = pending.filter { it.operation.agentEvent.threadIdForTransport() in deletedThreads &&
                it.operation.agentEvent !is ThreadDeleted && it.operation.agentEvent !is ThreadDeleteConflictResolved && it.operation.agentEvent !is ActionFinalized }
            held += erasedUnpublished.map { it.operation.operationId.value }
            val blockedDots = erasedUnpublished.map { it.operation.agentDvv.dot }
            pending.filter { record -> record.operation.agentEvent is ThreadDeleted && blockedDots.any { dot ->
                record.operation.agentDvv.context.any { it.replicaId == dot.replicaId && it.counter >= dot.counter }
            } }.forEach { held += it.operation.operationId.value }
            // A child of a held/incomplete parent turn shares that barrier; unrelated root turns remain independent.
            do {
                val before = held.size
                for ((_, manifest) in manifests) {
                    val unit = records.filter { it.operation.agentEvent.turnIdForTransport() == manifest.turnId }
                    val parentHeld = manifest.parentTurnIds.any { parent -> records.any { it.operation.agentEvent.turnIdForTransport() == parent && it.operation.operationId.value in held } }
                    if (parentHeld || unit.any { it.operation.operationId.value in held }) held += unit.map { it.operation.operationId.value }
                }
            } while (before != held.size)
            held.retainAll(pending.map { it.operation.operationId.value }.toSet())
            state.setHeld(space, held.toList(), true)
            for (unit in units) {
                val remaining = unit.filter { it.state != AgentOutboundTransportState.UPLOADED }
                if (remaining.isEmpty() || unit.any { it.operation.operationId.value in held }) continue
                // Consent is checked at both preflight and publication, including exact-ciphertext retries.
                if (!state.conversationConsent(space)) return AgentTransportProgress(uploaded, held.toList(), true)
                if (remaining.any { history.mutation(it.operation.operationId.value) != null })
                    return AgentTransportProgress(uploaded, held.toList(), failure = SyncUploadResult.IntegrityConflict("Agent operation ID is already a D7 business MutationId."))
                val envelopes = mutableListOf<EncryptedEnvelopeV1>()
                for (record in remaining) {
                    val envelope = record.envelope ?: when (val key = keys.currentEncryptionKey(space)) {
                        CurrentEncryptionKeyLookup.Missing -> return AgentTransportProgress(uploaded, held.toList(), failure = SyncUploadResult.RetryableFailure("Missing active content key."))
                        is CurrentEncryptionKeyLookup.Available -> when (val result = codec.encrypt(
                            SyncEnvelopeBinding(space, record.operation.operationId.value, deviceId, key.keyEpoch), SyncPayloadV3(operation = record.operation))) {
                            is EncryptSyncPayloadResult.Encrypted -> result.envelope
                            else -> return AgentTransportProgress(uploaded, held.toList(), failure = result.uploadFailure())
                        }
                    }
                    envelopes += envelope
                    if (Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT_OPTIONAL).decode(envelope.ciphertextBase64Url).size > limits.maxCiphertextBytes ||
                        SyncWireCodec.encodeEnvelope(envelope).encodeToByteArray().size > limits.maxRequestBodyBytes)
                        return AgentTransportProgress(uploaded, held.toList(), failure = SyncUploadResult.NonRetryableFailure("AGENT_SYNC_PAYLOAD_TOO_LARGE"))
                }
                // Preflight EVERY event (including size checks) and atomically retain the entire ready batch before first upload.
                val durable = state.retainEnvelopes(space, envelopes)
                for (record in remaining) for (dependency in persistence.pendingDependencies(space, record.operation.operationId.value)) {
                    if (dependency.kind == AgentSyncDependencyKind.BUSINESS_MUTATION && shared(space, dependency.key)) persistence.resolvePendingDependency(space, dependency)
                }
                state.setHeld(space, remaining.map { it.operation.operationId.value }, false)
                for (envelope in durable) {
                    if (!state.conversationConsent(space)) return AgentTransportProgress(uploaded, held.toList(), true)
                    when (val result = dev.agenticscheduler.application.sync.upload(transport, envelope)) {
                        is SyncUploadResult.Stored, is SyncUploadResult.Idempotent -> { state.markUploaded(space, envelope.mutationId); uploaded++ }
                        else -> return AgentTransportProgress(uploaded, held.toList(), failure = result)
                    }
                }
            }
            return AgentTransportProgress(uploaded, held.toList())
        } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled
        } catch (failure: Exception) { return AgentTransportProgress(uploaded, held.toList(), failure = transportFailure(failure)) }
    }

    private suspend fun shared(space: SyncSpaceId, id: String): Boolean {
        val fact = history.mutation(id) ?: return false
        if (fact.outboundEligible) return businessOutbound.envelope(space, id)?.uploaded == true
        val dot = fact.operation.dvv.dot
        return businessReceive.handledDot(space, ReplicaId(dot.replicaId), dot.counter)?.mutationId == id
    }
}

internal fun AgentSyncEvent.businessReferences(): List<MutationId> = when (this) {
    is ActionFinalized -> businessMutationIds
    is ToolResultAppended -> businessMutationIds
    else -> emptyList()
}

internal fun AgentSyncEvent.turnIdForTransport(): AgentTurnSyncId? = when (this) {
    is MessageAppended -> turnId
    is ToolCallFinalized -> turnId
    is ToolResultAppended -> turnId
    is ActionFinalized -> turnId
    is TurnFinalized -> turnId
    else -> null
}

internal fun AgentSyncEvent.threadIdForTransport(): AgentThreadSyncId? = when (this) {
    is ThreadCreated -> threadId
    is ThreadTitleSet -> threadId
    is MessageAppended -> threadId
    is ToolCallFinalized -> threadId
    is ToolResultAppended -> threadId
    is ActionFinalized -> threadId
    is TurnFinalized -> threadId
    is ThreadDeleted -> threadId
    is ThreadDeleteConflictResolved -> threadId
}

private fun AgentSyncEvent.memberForTransport(): TurnMemberReference? = when (this) {
    is MessageAppended -> MessageMember(messageId)
    is ToolCallFinalized -> ToolCallMember(callId)
    is ToolResultAppended -> ToolResultMember(resultId)
    is ActionFinalized -> ActionMember(actionId)
    else -> null
}
