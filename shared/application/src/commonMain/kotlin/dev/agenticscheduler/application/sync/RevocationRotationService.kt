package dev.agenticscheduler.application.sync

import dev.agenticscheduler.application.persistence.ApplicationTransactionRunner
import dev.agenticscheduler.sync.AccountId
import dev.agenticscheduler.sync.DeviceId
import dev.agenticscheduler.sync.HistoricalSyncSpaceKeyV1
import dev.agenticscheduler.sync.HpkePublicKeyBase64Url
import dev.agenticscheduler.sync.RecoveryEnvelopePlaintextV1
import dev.agenticscheduler.sync.RecoverySyncSpaceKeyRingV1
import dev.agenticscheduler.sync.RecoveryHistoricalKeyV1
import dev.agenticscheduler.sync.RecoveryWireCodec
import dev.agenticscheduler.sync.RotationWireCodec
import dev.agenticscheduler.sync.SyncSpaceId
import dev.agenticscheduler.sync.SyncSpaceKeyPackageV1
import dev.agenticscheduler.sync.encodeCanonicalBase64Url

/** Application port for the frozen SYN-007A directory and atomic SYN-007B submit. */
interface RevocationRotationTransport {
    suspend fun activeDevices(): List<ClientActiveDeviceDirectoryEntry>
    suspend fun revokeDeviceAndRotate(deviceId: String, request: ClientAtomicRevocationRequest)
}

sealed interface RevocationRotationResult {
    data class Prepared(val value: PreparedRevocationRotation) : RevocationRotationResult
    data object NotActive : RevocationRotationResult
    data object InvalidTarget : RevocationRotationResult
    data object DirectoryUnavailable : RevocationRotationResult
    data object DirectoryInvalid : RevocationRotationResult
    data object KeyGenerationFailed : RevocationRotationResult
    data class PackageBuildFailed(val reason: RotationKeyPackageBuildResult) : RevocationRotationResult
    data object RecoveryEnvelopeFailed : RevocationRotationResult
    data class RemoteFailed(val retry: PreparedRevocationRotation) : RevocationRotationResult
    data class Committed(val state: LocalEnrollmentState.Active) : RevocationRotationResult
    data class LocalCommitFailed(val retry: PreparedRevocationRotation) : RevocationRotationResult
    data class LocalKeyRejected(val result: InstallSyncKeyPackageResult) : RevocationRotationResult
}

/** Opaque, retryable attempt state. It never contains raw key material. */
data class PreparedRevocationRotation internal constructor(
    val accountId: AccountId,
    val targetDeviceId: DeviceId,
    val rotationId: String,
    internal val active: LocalEnrollmentState.Active,
    internal val nextActive: SyncSpaceContentKeyMetadata,
    internal val historical: List<SyncSpaceContentKeyMetadata>,
    internal val newAmk: SecretReference,
    internal val request: ClientAtomicRevocationRequest,
) 

/**
 * Client initiator for the frozen server atomic revoke-and-rotate operation.
 * A caller retries an uncertain submission with the returned [PreparedRevocationRotation],
 * therefore the server observes identical bytes for one rotationId.
 */
class RevocationRotationService(
    private val transport: RevocationRotationTransport,
    private val enrollments: LocalEnrollmentRepository,
    private val keyRing: SyncKeyRingRepository,
    private val accountMasterKeys: PlatformAccountMasterKeyStore,
    private val accountMasterKeyGenerator: PlatformAccountMasterKeyGenerator,
    private val contentKeyGenerator: PlatformContentKeyGenerator,
    private val exporter: PlatformPairingKeyMaterialExporter,
    private val packages: RotationKeyPackageBuilder,
    private val keyMaterial: PlatformKeyMaterialStore,
    private val transactions: ApplicationTransactionRunner,
) {
    suspend fun prepare(
        accountId: AccountId,
        targetDeviceId: DeviceId,
        rotationId: String,
        recoverySecret: RecoverySecret,
    ): RevocationRotationResult {
        val active = enrollments.state(accountId) as? LocalEnrollmentState.Active
            ?: return RevocationRotationResult.NotActive
        if (targetDeviceId == active.deviceId || rotationId.isBlank()) return RevocationRotationResult.InvalidTarget
        val directory = try { transport.activeDevices() } catch (_: Throwable) { return RevocationRotationResult.DirectoryUnavailable }
        val recipients = directory.sortedBy(ClientActiveDeviceDirectoryEntry::deviceId)
        if (recipients.map(ClientActiveDeviceDirectoryEntry::deviceId).distinct().size != recipients.size ||
            recipients.none { it.deviceId == active.deviceId.value } || recipients.none { it.deviceId == targetDeviceId.value }
        ) return RevocationRotationResult.DirectoryInvalid
        val remaining = recipients.filter { it.deviceId != targetDeviceId.value }
        if (remaining.isEmpty()) return RevocationRotationResult.InvalidTarget
        val oldActive = keyRing.currentEncryptionKey(active.syncSpaceId) ?: return RevocationRotationResult.DirectoryInvalid
        val oldHistorical = keyRing.historicalDecryptKeys(active.syncSpaceId)
        val nextEpoch = oldActive.keyEpoch.takeIf { it < Long.MAX_VALUE }?.plus(1) ?: return RevocationRotationResult.DirectoryInvalid
        val newAmk = try { accountMasterKeyGenerator.generateAccountMasterKey() } catch (_: Throwable) { return RevocationRotationResult.KeyGenerationFailed }
        val newKey = try { contentKeyGenerator.generateContentKey() } catch (_: Throwable) {
            safeDeleteAmk(newAmk); return RevocationRotationResult.KeyGenerationFailed
        }
        val nextActive = SyncSpaceContentKeyMetadata(active.syncSpaceId, nextEpoch, newKey.reference, newKey.identity, SyncSpaceContentKeyUsage.ACTIVE)
        val historical = (oldHistorical + oldActive.copy(usage = SyncSpaceContentKeyUsage.DECRYPT_ONLY)).sortedBy(SyncSpaceContentKeyMetadata::keyEpoch)
        val request = try { buildRequest(active, rotationId, recoverySecret, newAmk, nextActive, historical, remaining) }
        catch (failure: BuildFailure) {
            safeDeleteStaged(newAmk, newKey.reference)
            return when (failure) {
                is BuildFailure.Package -> RevocationRotationResult.PackageBuildFailed(failure.result)
                BuildFailure.Recovery -> RevocationRotationResult.RecoveryEnvelopeFailed
            }
        }
        return RevocationRotationResult.Prepared(PreparedRevocationRotation(accountId, targetDeviceId, rotationId, active, nextActive, historical, newAmk, request))
    }

    suspend fun submit(value: PreparedRevocationRotation): RevocationRotationResult {
        try { transport.revokeDeviceAndRotate(value.targetDeviceId.value, value.request) }
        catch (_: Throwable) { return RevocationRotationResult.RemoteFailed(value) }
        return publishLocal(value)
    }

    private suspend fun publishLocal(value: PreparedRevocationRotation): RevocationRotationResult {
        val result = try {
            transactions.inWriteTransaction {
                val installation = keyRing.installKeyPackage(
                    value.active.syncSpaceId,
                    SyncKeyPackageKeyReference(value.nextActive.keyEpoch, value.nextActive.contentKeyReference, value.nextActive.contentKeyIdentity),
                    value.historical.map { SyncKeyPackageKeyReference(it.keyEpoch, it.contentKeyReference, it.contentKeyIdentity) },
                )
                if (installation is InstallSyncKeyPackageResult.Installed || installation is InstallSyncKeyPackageResult.Advanced) {
                    enrollments.saveActive(value.active.copy(accountMasterKeyReference = value.newAmk))
                }
                installation
            }
        } catch (_: Throwable) { return RevocationRotationResult.LocalCommitFailed(value) }
        return when (result) {
            is InstallSyncKeyPackageResult.Installed, is InstallSyncKeyPackageResult.Advanced -> {
                safeDeleteAmk(value.active.accountMasterKeyReference)
                RevocationRotationResult.Committed(value.active.copy(accountMasterKeyReference = value.newAmk))
            }
            is InstallSyncKeyPackageResult.Idempotent, is InstallSyncKeyPackageResult.Repaired,
            is InstallSyncKeyPackageResult.IntegrityError, is InstallSyncKeyPackageResult.RejectedRollback -> RevocationRotationResult.LocalKeyRejected(result)
        }
    }

    private suspend fun buildRequest(active: LocalEnrollmentState.Active, rotationId: String, secret: RecoverySecret, amk: SecretReference, next: SyncSpaceContentKeyMetadata, historical: List<SyncSpaceContentKeyMetadata>, recipients: List<ClientActiveDeviceDirectoryEntry>): ClientAtomicRevocationRequest {
        val envelopes = recipients.map { recipient ->
            val hpke = try { HpkePublicKeyBase64Url(recipient.hpkePublicKeyBase64Url) } catch (_: Throwable) { throw BuildFailure.Package(RotationKeyPackageBuildResult.InvalidKeyRing) }
            when (val built = packages.buildStaged(StagedRotationKeyPackageBuildRequest(active.accountId, rotationId, amk, active.syncSpaceId, next, historical, DeviceId(recipient.deviceId), hpke))) {
                is RotationKeyPackageBuildResult.Built -> ClientRotationPackage(recipient.deviceId, encodeCanonicalBase64Url(RotationWireCodec.encodeEnvelope(built.envelope).encodeToByteArray()))
                else -> throw BuildFailure.Package(built)
            }
        }
        val ring = exportRecoveryRing(active.syncSpaceId, next, historical) ?: throw BuildFailure.Recovery
        val rawAmk = exporter.exportAccountMasterKeyForPairing(amk)?.copyRawKeyBytesForPairing() ?: throw BuildFailure.Recovery
        if (rawAmk.size != 32) throw BuildFailure.Recovery
        val envelope = RecoveryEnvelopeCodec.seal(secret, RecoveryEnvelopePlaintextV1(accountId = active.accountId, keyEpoch = next.keyEpoch, accountMasterKeyBase64Url = encodeCanonicalBase64Url(rawAmk), syncSpace = ring))
        return ClientAtomicRevocationRequest(rotationId, encodeCanonicalBase64Url(RecoveryWireCodec.encodeEnvelope(envelope).encodeToByteArray()), envelopes)
    }

    private suspend fun exportRecoveryRing(space: SyncSpaceId, active: SyncSpaceContentKeyMetadata, historical: List<SyncSpaceContentKeyMetadata>): RecoverySyncSpaceKeyRingV1? {
        val current = exporter.exportContentKeyForPairing(active.contentKeyReference) ?: return null
        if (current.identity != active.contentKeyIdentity) return null
        val currentRaw = current.material.copyRawKeyBytesForPairing(); if (currentRaw.size != 32) return null
        val previous = historical.map { key ->
            val exported = exporter.exportContentKeyForPairing(key.contentKeyReference) ?: return null
            if (exported.identity != key.contentKeyIdentity) return null
            val raw = exported.material.copyRawKeyBytesForPairing(); if (raw.size != 32) return null
            RecoveryHistoricalKeyV1(key.keyEpoch, encodeCanonicalBase64Url(raw))
        }
        return RecoverySyncSpaceKeyRingV1(space, active.keyEpoch, encodeCanonicalBase64Url(currentRaw), previous)
    }

    private suspend fun safeDeleteStaged(amk: SecretReference, content: SecretReference) { safeDeleteAmk(amk); try { keyMaterial.delete(content) } catch (_: Throwable) { } }
    private suspend fun safeDeleteAmk(reference: SecretReference) { try { accountMasterKeys.delete(reference) } catch (_: Throwable) { } }
    private sealed class BuildFailure : RuntimeException() {
        data class Package(val result: RotationKeyPackageBuildResult) : BuildFailure()
        data object Recovery : BuildFailure()
    }
}
