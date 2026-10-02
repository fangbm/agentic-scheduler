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
    /** Explicit test/integration injection; production remains legacy while OD-012 is open. */
    private val agentHistory: AgentHistoryReceiveIntegration? = null,
) : SyncEnvelopeReceiver {
    override
    suspend fun receive(encodedEnvelope: String, serverCursor: Long): EncryptedSyncReceiveResult =
        when (val decrypted = envelopeCodec.decrypt(encodedEnvelope)) {
            is DecryptSyncEnvelopeResult.AuthenticatedPlaintext -> {
                val receipt = DecryptedPayloadReceipt(
                        syncSpaceId = decrypted.envelope.syncSpaceId,
                        mutationId = decrypted.envelope.mutationId,
                        serverCursor = serverCursor,
                        payloadJson = decrypted.payloadJson,
                    )
                if (agentHistory != null && isAgentV3(receipt.payloadJson)) agentHistory.receive(receipt, syncEngine)
                else {
                    val result = syncEngine.receive(receipt)
                    EncryptedSyncReceiveResult.Handled(result, agentHistory?.afterBusinessReceive(receipt.syncSpaceId))
                }
            }
            DecryptSyncEnvelopeResult.AuthenticationFailed -> EncryptedSyncReceiveResult.SecurityFailure.AuthenticationFailed
            DecryptSyncEnvelopeResult.MissingContentKey -> EncryptedSyncReceiveResult.SecurityFailure.MissingContentKey
            is DecryptSyncEnvelopeResult.UnsupportedEnvelopeVersion -> EncryptedSyncReceiveResult.ProtocolFailure.UnsupportedEnvelopeVersion(decrypted.actual)
            is DecryptSyncEnvelopeResult.InvalidEnvelope -> EncryptedSyncReceiveResult.ProtocolFailure.InvalidEnvelope(decrypted.reason)
        }
}

sealed interface EncryptedSyncReceiveResult {
    data class Handled(val result: SyncReceiveResult, val agentHistoryFailure: String? = null) : EncryptedSyncReceiveResult
    data class AgentHandled(val operationId: dev.agenticscheduler.sync.MutationId, val result: AgentSyncPersistResult, val agentHistoryFailure: String? = null) : EncryptedSyncReceiveResult

    sealed interface SecurityFailure : EncryptedSyncReceiveResult {
        data object AuthenticationFailed : SecurityFailure
        data object MissingContentKey : SecurityFailure
    }

    sealed interface ProtocolFailure : EncryptedSyncReceiveResult {
        data class UnsupportedEnvelopeVersion(val actual: Int?) : ProtocolFailure
        data class InvalidEnvelope(val reason: String) : ProtocolFailure
    }
}
