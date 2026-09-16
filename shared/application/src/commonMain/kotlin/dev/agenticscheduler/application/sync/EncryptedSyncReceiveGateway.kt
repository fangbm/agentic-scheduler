package dev.agenticscheduler.application.sync

import dev.agenticscheduler.application.history.DecryptedPayloadReceipt
import dev.agenticscheduler.application.history.SyncEngine
import dev.agenticscheduler.application.history.SyncReceiveResult

/**
 * Sole D8 bridge from an opaque transport envelope into the plaintext
 * SyncEngine. Authentication/protocol failures stop here, before the engine
 * can inspect or write Active State, history, conflicts, or cursors.
 */
class EncryptedSyncReceiveGateway(
    private val envelopeCodec: AuthenticatedSyncEnvelopeCodec,
    private val syncEngine: SyncEngine,
) {
    suspend fun receive(encodedEnvelope: String, serverCursor: Long): EncryptedSyncReceiveResult =
        when (val decrypted = envelopeCodec.decrypt(encodedEnvelope)) {
            is DecryptSyncEnvelopeResult.Decrypted -> EncryptedSyncReceiveResult.Handled(
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
            is DecryptSyncEnvelopeResult.InvalidPayload -> EncryptedSyncReceiveResult.ProtocolFailure.InvalidPayload(decrypted.reason)
            is DecryptSyncEnvelopeResult.UnsupportedPayloadVersion -> EncryptedSyncReceiveResult.ProtocolFailure.UnsupportedPayloadVersion(decrypted.actual)
            is DecryptSyncEnvelopeResult.UnsupportedMutation -> EncryptedSyncReceiveResult.ProtocolFailure.UnsupportedMutation(decrypted.discriminator)
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
        data class InvalidPayload(val reason: String) : ProtocolFailure
        data class UnsupportedPayloadVersion(val actual: Int?) : ProtocolFailure
        data class UnsupportedMutation(val discriminator: String?) : ProtocolFailure
    }
}
