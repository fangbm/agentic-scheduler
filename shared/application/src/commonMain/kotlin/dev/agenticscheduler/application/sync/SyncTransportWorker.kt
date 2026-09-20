package dev.agenticscheduler.application.sync

import dev.agenticscheduler.application.persistence.HistoryRepository
import dev.agenticscheduler.application.persistence.StoredOutboundEnvelope
import dev.agenticscheduler.application.persistence.SyncOutboundEnvelopeRepository
import dev.agenticscheduler.application.persistence.SyncReceiveRepository
import dev.agenticscheduler.sync.DeviceId
import dev.agenticscheduler.sync.EncryptedEnvelopeV1
import dev.agenticscheduler.sync.SyncPayloadV1
import dev.agenticscheduler.sync.SyncSpaceId

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

data class SyncTransportRunResult(
    val uploaded: Int,
    val fetched: Int,
    val applied: Int,
    val stoppedOnFailure: SyncUploadResult? = null,
    val stoppedOnReceiveFailure: String? = null,
)

/**
 * Uploads committed operations only after their exact ciphertext envelope is
 * durable locally, then feeds fetched envelopes through the authenticated
 * receive gateway. A failed network call never rolls back local history.
 */
class SyncTransportWorker(
    private val history: HistoryRepository,
    private val outbound: SyncOutboundEnvelopeRepository,
    private val receive: SyncReceiveRepository,
    private val codec: AuthenticatedSyncEnvelopeCodec,
    private val encryptionKeys: CurrentEncryptionKeyProvider,
    private val deviceId: DeviceId,
    private val transport: SyncTransport,
    private val receiveGateway: SyncEnvelopeReceiver,
) {
    suspend fun run(syncSpaceId: SyncSpaceId, fetchLimit: Int = 100): SyncTransportRunResult {
        require(fetchLimit > 0)
        var uploaded = 0
        history.timeline().forEach { committed ->
            val operation = committed.operation
            val existing = outbound.envelope(syncSpaceId, operation.mutationId)
            val stored = existing ?: when (val key = encryptionKeys.currentEncryptionKey(syncSpaceId)) {
                CurrentEncryptionKeyLookup.Missing -> return SyncTransportRunResult(uploaded, 0, 0, SyncUploadResult.RetryableFailure("Missing active content key."))
                is CurrentEncryptionKeyLookup.Available -> when (val encrypted = codec.encrypt(
                    SyncEnvelopeBinding(syncSpaceId, operation.mutationId, deviceId, key.keyEpoch),
                    SyncPayloadV1(operation = operation),
                )) {
                    is EncryptSyncPayloadResult.Encrypted -> {
                        val value = StoredOutboundEnvelope(syncSpaceId, operation.mutationId, encrypted.envelope, false)
                        outbound.save(value)
                        value
                    }
                    EncryptSyncPayloadResult.MissingContentKey -> return SyncTransportRunResult(uploaded, 0, 0, SyncUploadResult.RetryableFailure("Missing active content key."))
                    is EncryptSyncPayloadResult.NonActiveKeyEpoch -> return SyncTransportRunResult(uploaded, 0, 0, SyncUploadResult.RetryableFailure("Active key epoch changed."))
                    is EncryptSyncPayloadResult.InvalidPayload -> return SyncTransportRunResult(uploaded, 0, 0, SyncUploadResult.IntegrityConflict(encrypted.reason))
                }
            }
            if (!stored.uploaded) {
                when (val result = transport.upload(stored.envelope)) {
                    is SyncUploadResult.Stored, is SyncUploadResult.Idempotent -> {
                        outbound.markUploaded(syncSpaceId, operation.mutationId)
                        uploaded++
                    }
                    is SyncUploadResult.RetryableFailure, is SyncUploadResult.IntegrityConflict, is SyncUploadResult.NonRetryableFailure ->
                        return SyncTransportRunResult(uploaded, 0, 0, result)
                }
            }
        }

        var fetched = 0
        var applied = 0
        var cursor = receive.serverCursor(syncSpaceId)
        while (true) {
            val batch = transport.fetch(syncSpaceId, cursor, fetchLimit)
            if (batch.isEmpty()) break
            batch.forEach { remote ->
                val result = receiveGateway.receive(
                    dev.agenticscheduler.sync.SyncWireCodec.encodeEnvelope(remote.envelope),
                    remote.serverCursor,
                )
                fetched++
                when (result) {
                    is EncryptedSyncReceiveResult.Handled -> {
                        applied++
                        cursor = maxOf(cursor, remote.serverCursor)
                    }
                    is EncryptedSyncReceiveResult.SecurityFailure ->
                        return SyncTransportRunResult(uploaded, fetched, applied, stoppedOnReceiveFailure = result.toString())
                    is EncryptedSyncReceiveResult.ProtocolFailure ->
                        return SyncTransportRunResult(uploaded, fetched, applied, stoppedOnReceiveFailure = result.toString())
                }
            }
            if (batch.size < fetchLimit) break
        }
        return SyncTransportRunResult(uploaded, fetched, applied)
    }
}
