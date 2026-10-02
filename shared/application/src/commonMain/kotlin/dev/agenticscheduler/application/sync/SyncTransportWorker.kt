package dev.agenticscheduler.application.sync

import dev.agenticscheduler.application.persistence.*
import dev.agenticscheduler.sync.*
import kotlinx.coroutines.CancellationException

sealed interface SyncUploadResult {
    data class Stored(val serverCursor: Long) : SyncUploadResult
    data class Idempotent(val serverCursor: Long) : SyncUploadResult
    data class RetryableFailure(val detail: String) : SyncUploadResult
    data class IntegrityConflict(val detail: String) : SyncUploadResult
    data class NonRetryableFailure(val detail: String) : SyncUploadResult
}

interface SyncTransport {
    suspend fun upload(envelope: EncryptedEnvelopeV1): SyncUploadResult
    suspend fun fetch(syncSpaceId: SyncSpaceId, afterCursor: Long, limit: Int): List<RemoteSyncEnvelope>
}

data class RemoteSyncEnvelope(val serverCursor: Long, val envelope: EncryptedEnvelopeV1) {
    init { require(serverCursor > 0) }
}

fun interface SyncEnvelopeReceiver {
    suspend fun receive(encodedEnvelope: String, serverCursor: Long): EncryptedSyncReceiveResult
}

/** Host-owned, device-local acknowledgement; Agent tools cannot grant it. */
fun interface AgentOutboundCompatibilityGate { suspend fun enabled(syncSpaceId: SyncSpaceId): Boolean }

data class SyncTransportRunResult(
    val uploaded: Int,
    val fetched: Int,
    val applied: Int,
    /** Business outbound failure only; compatibility holds are reported separately. */
    val stoppedOnFailure: SyncUploadResult? = null,
    val stoppedOnReceiveFailure: String? = null,
    /** Compatibility or unsatisfied business causality only, excluding eligible failed/unattempted work. */
    val heldBusinessOperationIds: List<String> = emptyList(),
    val agentOutbound: AgentTransportProgress = AgentTransportProgress(),
    val stoppedOnFetchFailure: SyncUploadResult? = null,
    val agentHistoryReceiveFailure: String? = null,
)

/** Exact durable ciphertext precedes upload. Business, Agent and inbound progress independently. */
class SyncTransportWorker(
    private val history: HistoryRepository,
    private val outbound: SyncOutboundEnvelopeRepository,
    private val receive: SyncReceiveRepository,
    private val codec: AuthenticatedSyncEnvelopeCodec,
    private val encryptionKeys: CurrentEncryptionKeyProvider,
    private val deviceId: DeviceId,
    private val transport: SyncTransport,
    private val receiveGateway: SyncEnvelopeReceiver,
    private val agentOutboundGate: AgentOutboundCompatibilityGate = AgentOutboundCompatibilityGate { false },
    /** Absent in production composition until the OD-012 release gate is satisfied. */
    private val agentHistoryTransport: AgentHistoryOutboundTransport? = null,
) {
    suspend fun run(syncSpaceId: SyncSpaceId, fetchLimit: Int = 100): SyncTransportRunResult {
        require(fetchLimit > 0)
        var uploaded = 0
        var outboundFailure: SyncUploadResult? = null
        val held = mutableListOf<String>()
        try {
            val timeline = history.timeline()
            val coverage = mutableMapOf<String, MutableSet<Long>>()
            receive.handledDots(syncSpaceId).forEach { coverage.getOrPut(it.replicaId.value) { mutableSetOf() }.add(it.counter) }
            val pending = timeline.filter(CommittedMutation::outboundEligible).toMutableList()
            // Only authenticated received dots or acknowledged ciphertext for THIS space prove sharing.
            pending.toList().forEach { committed ->
                if (outbound.envelope(syncSpaceId, committed.operation.mutationId)?.uploaded == true) {
                    coverage.getOrPut(committed.operation.dvv.dot.replicaId) { mutableSetOf() }.add(committed.operation.dvv.dot.counter)
                    pending.remove(committed)
                }
            }
            val gate = agentOutboundGate.enabled(syncSpaceId)
            fun eligible(committed: CommittedMutation): Boolean {
                val op = committed.operation
                return (op.origin !is MutationOrigin.Agent || gate) &&
                    op.dvv.context.all { component ->
                        (coverage[component.replicaId]?.maxOrNull() ?: -1L) >= component.counter &&
                            pending.none { prerequisite -> prerequisite.operation.dvv.dot.let { dot ->
                                dot.replicaId == component.replicaId && dot.counter <= component.counter
                            } }
                    }
            }
            try {
                while (pending.isNotEmpty()) {
                    val candidate = pending.firstOrNull(::eligible) ?: break
                    val op = candidate.operation
                    val stored = outbound.envelope(syncSpaceId, op.mutationId) ?: when (val key = encryptionKeys.currentEncryptionKey(syncSpaceId)) {
                        CurrentEncryptionKeyLookup.Missing -> { outboundFailure = SyncUploadResult.RetryableFailure("Missing active content key."); break }
                        is CurrentEncryptionKeyLookup.Available -> {
                            val binding = SyncEnvelopeBinding(syncSpaceId, op.mutationId, deviceId, key.keyEpoch)
                            val encrypted = if (op.origin is MutationOrigin.Agent) codec.encrypt(binding, SyncPayloadV2(operation = op))
                                else codec.encrypt(binding, SyncPayloadV1(operation = op))
                            if (encrypted !is EncryptSyncPayloadResult.Encrypted) { outboundFailure = encrypted.uploadFailure(); break }
                            StoredOutboundEnvelope(syncSpaceId, op.mutationId, encrypted.envelope, false).also { outbound.save(it) }
                        }
                    }
                    when (val result = upload(transport, stored.envelope)) {
                        is SyncUploadResult.Stored, is SyncUploadResult.Idempotent -> {
                            outbound.markUploaded(syncSpaceId, op.mutationId)
                            uploaded++
                            pending.remove(candidate)
                            coverage.getOrPut(op.dvv.dot.replicaId) { mutableSetOf() }.add(op.dvv.dot.counter)
                        }
                        else -> { outboundFailure = result; break }
                    }
                }
            } finally {
                held += pending.filterNot(::eligible).map { it.operation.mutationId }
            }
        } catch (cancelled: CancellationException) { throw cancelled
        } catch (failure: Exception) { outboundFailure = transportFailure(failure) }

        val agentProgress = try { agentHistoryTransport?.upload(syncSpaceId) ?: AgentTransportProgress()
        } catch (cancelled: CancellationException) { throw cancelled
        } catch (failure: Exception) { AgentTransportProgress(failure = transportFailure(failure)) }

        var fetched = 0
        var applied = 0
        var fetchFailure: SyncUploadResult? = null
        var receiveFailure: String? = null
        var agentReceiveFailure: String? = null
        try {
            var cursor = receive.serverCursor(syncSpaceId)
            receiveLoop@ while (true) {
                val batch = try { transport.fetch(syncSpaceId, cursor, fetchLimit)
                } catch (cancelled: CancellationException) { throw cancelled
                } catch (failure: Exception) { fetchFailure = transportFailure(failure); break }
                if (batch.isEmpty()) break
                for (remote in batch) {
                    if (remote.envelope.syncSpaceId != syncSpaceId || remote.serverCursor <= cursor) {
                        receiveFailure = "INVALID_SERVER_ENVELOPE"; break@receiveLoop
                    }
                    fetched++
                    when (val result = receiveGateway.receive(SyncWireCodec.encodeEnvelope(remote.envelope), remote.serverCursor)) {
                        is EncryptedSyncReceiveResult.Handled, is EncryptedSyncReceiveResult.AgentHandled -> {
                            val agentFailure = when (result) {
                                is EncryptedSyncReceiveResult.Handled -> result.agentHistoryFailure
                                is EncryptedSyncReceiveResult.AgentHandled -> result.agentHistoryFailure
                            }
                            if (agentFailure != null) agentReceiveFailure = agentFailure
                            applied++
                            cursor = maxOf(cursor, remote.serverCursor)
                        }
                        else -> { receiveFailure = result.toString(); break@receiveLoop }
                    }
                }
                if (batch.size < fetchLimit) break
            }
        } catch (cancelled: CancellationException) { throw cancelled
        } catch (_: Exception) { receiveFailure = "SYNC_RECEIVE_FAILURE" }
        return SyncTransportRunResult(uploaded, fetched, applied, outboundFailure, receiveFailure, held, agentProgress, fetchFailure, agentReceiveFailure)
    }
}

internal suspend fun upload(transport: SyncTransport, envelope: EncryptedEnvelopeV1): SyncUploadResult = try {
    transport.upload(envelope)
} catch (cancelled: CancellationException) { throw cancelled
} catch (failure: Exception) { transportFailure(failure) }

internal fun transportFailure(failure: Exception): SyncUploadResult =
    if (failure is SyncTransportException) failure.asUploadFailure() else SyncUploadResult.RetryableFailure("TRANSPORT_OR_PERSISTENCE_FAILURE")

internal fun EncryptSyncPayloadResult.uploadFailure(): SyncUploadResult = when (this) {
    EncryptSyncPayloadResult.MissingContentKey -> SyncUploadResult.RetryableFailure("Missing active content key.")
    is EncryptSyncPayloadResult.NonActiveKeyEpoch -> SyncUploadResult.RetryableFailure("Active key epoch changed.")
    is EncryptSyncPayloadResult.InvalidPayload -> if (reason == "AGENT_SYNC_PAYLOAD_TOO_LARGE") SyncUploadResult.NonRetryableFailure(reason) else SyncUploadResult.IntegrityConflict(reason)
    is EncryptSyncPayloadResult.Encrypted -> error("Encrypted payload is not a failure.")
}
