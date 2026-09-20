package dev.agenticscheduler.application.sync

import dev.agenticscheduler.application.history.DecryptedPayloadReceipt
import dev.agenticscheduler.application.history.SyncEngine
import dev.agenticscheduler.application.history.SyncReceiveResult

/**
 * Sole D8 bridge from an opaque transport envelope into the plaintext
 * SyncEngine. Envelope authentication failures stop here before the engine
 * can inspect or write state; authenticated inner protocol failures continue
 * to SyncEngine so its durable quarantine/cursor transaction remains sole
 * owner of that behavior.
 */
class EncryptedSyncReceiveGateway(
    private val envelopeCodec: AuthenticatedSyncEnvelopeCodec,
    private val syncEngine: SyncEngine,
) : SyncEnvelopeReceiver {
    override
    suspend fun receive(encodedEnvelope: String, serverCursor: Long): EncryptedSyncReceiveResult =
        when (val decrypted = envelopeCodec.decrypt(encodedEnvelope)) {
            is DecryptSyncEnvelopeResult.AuthenticatedPlaintext -> EncryptedSyncReceiveResult.Handled(
                syncEngine.receive(
                    DecryptedPayloadReceipt(
                        syncSpaceId = decrypted.envelope.syncSpaceId,
                        mutationId = decrypted.envelope.mutationId,
                        serverCursor = serverCursor,
                        payloadJson = decrypted.payloadJson,
                    ),
                ),
            )
            DecryptSyncEnvelopeResult.AuthenticationFailed -> EncryptedSyncReceiveResult.SecurityFailure.AuthenticationFailed
            DecryptSyncEnvelopeResult.MissingContentKey -> EncryptedSyncReceiveResult.SecurityFailure.MissingContentKey
            is DecryptSyncEnvelopeResult.UnsupportedEnvelopeVersion -> EncryptedSyncReceiveResult.ProtocolFailure.UnsupportedEnvelopeVersion(decrypted.actual)
            is DecryptSyncEnvelopeResult.InvalidEnvelope -> EncryptedSyncReceiveResult.ProtocolFailure.InvalidEnvelope(decrypted.reason)
        }
}

sealed interface EncryptedSyncReceiveResult {
    data class Handled(val result: SyncReceiveResult) : EncryptedSyncReceiveResult

    sealed interface SecurityFailure : EncryptedSyncReceiveResult {
        data object AuthenticationFailed : SecurityFailure
        data object MissingContentKey : SecurityFailure
    }

    sealed interface ProtocolFailure : EncryptedSyncReceiveResult {
        data class UnsupportedEnvelopeVersion(val actual: Int?) : ProtocolFailure
        data class InvalidEnvelope(val reason: String) : ProtocolFailure
    }
}
