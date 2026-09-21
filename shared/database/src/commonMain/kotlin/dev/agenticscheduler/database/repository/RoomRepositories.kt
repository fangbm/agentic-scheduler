package dev.agenticscheduler.database.repository

import androidx.room3.withWriteTransaction
import dev.agenticscheduler.application.persistence.*
import dev.agenticscheduler.database.AgenticSchedulerDatabase
import dev.agenticscheduler.database.mapper.*
import dev.agenticscheduler.domain.academic.*
import dev.agenticscheduler.domain.event.Event
import dev.agenticscheduler.domain.id.*
import dev.agenticscheduler.domain.planning.PlanningProfile
import dev.agenticscheduler.domain.task.*
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import dev.agenticscheduler.database.record.ChangeLogEntryRecord
import dev.agenticscheduler.database.record.FocusBlockTombstoneRecord
import dev.agenticscheduler.database.record.MutationRecord
import dev.agenticscheduler.database.record.ReplicaCausalStateRecord
import dev.agenticscheduler.database.record.SyncOperationJournalRecord
import dev.agenticscheduler.sync.FocusBlockDelete
import dev.agenticscheduler.sync.LocalJournalCodec
import dev.agenticscheduler.sync.MutationOrigin
import dev.agenticscheduler.sync.MutationId
import dev.agenticscheduler.sync.ReplicaId
import dev.agenticscheduler.sync.HlcTimestamp
import dev.agenticscheduler.sync.operationKind
import dev.agenticscheduler.sync.ProtocolQuarantine
import dev.agenticscheduler.sync.ProtocolQuarantineReason
import dev.agenticscheduler.sync.SyncConflict
import dev.agenticscheduler.sync.SyncReceiveStateCodec
import dev.agenticscheduler.sync.SyncSpaceId
import dev.agenticscheduler.database.record.ProtocolQuarantineRecord
import dev.agenticscheduler.database.record.SyncConflictRecord
import dev.agenticscheduler.database.record.SyncSpaceCursorRecord
import dev.agenticscheduler.application.sync.SecretReference
import dev.agenticscheduler.application.sync.LocalEnrollmentRepository
import dev.agenticscheduler.application.sync.LocalEnrollmentState
import dev.agenticscheduler.application.sync.SyncKeyRingRepository
import dev.agenticscheduler.application.sync.SyncSpaceKeyState
import dev.agenticscheduler.application.sync.SyncSpaceContentKeyMetadata
import dev.agenticscheduler.application.sync.SyncSpaceContentKeyUsage
import dev.agenticscheduler.application.sync.InstallSyncSpaceKeyEpochResult
import dev.agenticscheduler.application.sync.InstallSyncKeyPackageResult
import dev.agenticscheduler.application.sync.ContentKeyIdentity
import dev.agenticscheduler.application.sync.SyncKeyPackageKeyReference
import dev.agenticscheduler.application.sync.SyncKeyPackageAdoption
import dev.agenticscheduler.database.record.SyncSpaceKeyStateRecord
import dev.agenticscheduler.database.record.SyncSpaceContentKeyRecord
import dev.agenticscheduler.database.record.PendingSyncReceiveRecord
import dev.agenticscheduler.database.record.HandledReceiveDotRecord
import dev.agenticscheduler.database.record.LocalPairingEnrollmentRecord
import dev.agenticscheduler.application.persistence.StoredOutboundEnvelope
import dev.agenticscheduler.application.persistence.SyncOutboundEnvelopeRepository
import dev.agenticscheduler.sync.DeviceId
import dev.agenticscheduler.sync.EncryptedEnvelopeV1

class RoomApplicationTransactionRunner(private val database: AgenticSchedulerDatabase) : ApplicationTransactionRunner {
    override suspend fun <T> inWriteTransaction(block: suspend () -> T): T = database.withWriteTransaction { block() }
}

/** Room implementation of the D7 atomic journal port; callers already own the write transaction. */
class RoomMutationJournalRepository(private val database: AgenticSchedulerDatabase) : MutationJournalRepository, HistoryRepository {
    override suspend fun localReplicaState(): LocalReplicaCausalState? = database.mutationJournalDao().localReplicaState()?.let { record ->
        LocalReplicaCausalState(
            replicaId = ReplicaId(record.replicaId),
            lastCounter = record.lastCounter,
            observedContext = LocalJournalCodec.decodeContext(record.observedContextJson),
            lastHlc = HlcTimestamp(record.lastHlcPhysicalMillis, record.lastHlcLogical, ReplicaId(record.lastHlcReplicaId)),
        )
    }

    override suspend fun saveLocalReplicaState(state: LocalReplicaCausalState) {
        database.mutationJournalDao().saveLocalReplicaState(ReplicaCausalStateRecord(
            replicaId = state.replicaId.value,
            lastCounter = state.lastCounter,
            observedContextJson = LocalJournalCodec.encodeContext(state.observedContext),
            lastHlcPhysicalMillis = state.lastHlc.physicalMillis,
            lastHlcLogical = state.lastHlc.logical,
            lastHlcReplicaId = state.lastHlc.replicaId.value,
        ))
    }

    override suspend fun appendCommittedMutation(mutation: CommittedMutation) {
        val operation = mutation.operation
        database.mutationJournalDao().insertMutationRecord(MutationRecord(
            mutationId = operation.mutationId,
            origin = operation.origin.durableName(),
            dvvJson = LocalJournalCodec.encodeDvv(operation.dvv),
            hlcPhysicalMillis = operation.hlc.physicalMillis,
            hlcLogical = operation.hlc.logical,
            hlcReplicaId = operation.hlc.replicaId,
            committedAtEpochMillis = mutation.committedAtEpochMillis,
            outboundEligible = mutation.outboundEligible,
        ))
        database.mutationJournalDao().insertChangeLogEntries(operation.orderedMutations.mapIndexed { ordinal, entry ->
            ChangeLogEntryRecord(
                entryId = "${operation.mutationId}:$ordinal",
                mutationId = operation.mutationId,
                ordinal = ordinal,
                entityKind = entry.entityKind.name,
                entityId = entry.entityId,
                operationKind = entry.operationKind(),
                beforeImageJson = LocalJournalCodec.beforeImage(entry),
                afterImageJson = LocalJournalCodec.afterImage(entry),
            )
        })
        database.mutationJournalDao().insertSyncOperation(SyncOperationJournalRecord(operation.mutationId, LocalJournalCodec.version, LocalJournalCodec.encode(operation)))
    }

    override suspend fun advanceFocusBlockTombstones(operation: dev.agenticscheduler.sync.SyncOperation, acceptedDeletes: List<FocusBlockDelete>) {
        acceptedDeletes.forEach { delete ->
            database.mutationJournalDao().upsertFocusBlockTombstone(FocusBlockTombstoneRecord(
                focusBlockId = delete.before.id,
                deletionMutationId = operation.mutationId,
                dvvJson = LocalJournalCodec.encodeDvv(operation.dvv),
                hlcPhysicalMillis = operation.hlc.physicalMillis,
                hlcLogical = operation.hlc.logical,
                hlcReplicaId = operation.hlc.replicaId,
            ))
        }
    }

    override suspend fun timeline(): List<CommittedMutation> = database.mutationJournalDao().timeline().mapNotNull { record -> mutation(record.mutationId) }

    override suspend fun mutation(mutationId: String): CommittedMutation? {
        val record = database.mutationJournalDao().syncOperation(mutationId) ?: return null
        val metadata = database.mutationJournalDao().mutationRecord(mutationId) ?: return null
        return CommittedMutation(LocalJournalCodec.decode(record.operationJson), metadata.committedAtEpochMillis, metadata.outboundEligible)
    }

    override suspend fun entityChanges(entityKind: dev.agenticscheduler.sync.EntityKind, entityId: String): List<HistoryChange> =
        database.mutationJournalDao().entityEntries(entityKind.name, entityId).map { it.toHistoryChange(requireNotNull(database.mutationJournalDao().mutationRecord(it.mutationId))) }.sortedWith(historyChangeComparator)

    override suspend fun diff(mutationId: String): List<HistoryChange> = database.mutationJournalDao().entries(mutationId).map { it.toHistoryChange(requireNotNull(database.mutationJournalDao().mutationRecord(it.mutationId))) }

    override suspend fun focusBlockTombstone(focusBlockId: String): FocusBlockTombstone? = database.mutationJournalDao().focusBlockTombstone(focusBlockId)?.let { FocusBlockTombstone(it.focusBlockId, MutationId(it.deletionMutationId), LocalJournalCodec.decodeDvv(it.dvvJson)) }
}

/** D8 receive metadata implementation. Callers own the encompassing application transaction. */
class RoomSyncReceiveRepository(private val database: AgenticSchedulerDatabase) : SyncReceiveRepository {
    override suspend fun serverCursor(syncSpaceId: SyncSpaceId): Long =
        database.syncReceiveDao().cursor(syncSpaceId.value)?.serverCursor ?: 0L

    override suspend fun saveServerCursor(syncSpaceId: SyncSpaceId, cursor: Long) {
        require(cursor >= 0)
        database.syncReceiveDao().saveCursor(SyncSpaceCursorRecord(syncSpaceId.value, cursor))
    }

    override suspend fun pending(syncSpaceId: SyncSpaceId, mutationId: String): PendingSyncReceive? =
        database.syncReceiveDao().pending(syncSpaceId.value, mutationId)?.let { record ->
            PendingSyncReceive(SyncSpaceId(record.syncSpaceId), record.mutationId, record.serverCursor, record.payloadJson)
        }

    override suspend fun pending(syncSpaceId: SyncSpaceId): List<PendingSyncReceive> =
        database.syncReceiveDao().pending(syncSpaceId.value).map { record ->
            PendingSyncReceive(SyncSpaceId(record.syncSpaceId), record.mutationId, record.serverCursor, record.payloadJson)
        }

    override suspend fun savePending(value: PendingSyncReceive) {
        database.syncReceiveDao().savePending(PendingSyncReceiveRecord(
            value.syncSpaceId.value, value.mutationId, value.serverCursor, value.payloadJson,
        ))
    }

    override suspend fun removePending(syncSpaceId: SyncSpaceId, mutationId: String) {
        database.syncReceiveDao().removePending(syncSpaceId.value, mutationId)
    }

    override suspend fun handledDot(syncSpaceId: SyncSpaceId, replicaId: ReplicaId, counter: Long): HandledReceiveDot? =
        database.syncReceiveDao().handledDot(syncSpaceId.value, replicaId.value, counter)?.let { record ->
            HandledReceiveDot(SyncSpaceId(record.syncSpaceId), ReplicaId(record.replicaId), record.counter, record.mutationId)
        }

    override suspend fun handledDots(syncSpaceId: SyncSpaceId): List<HandledReceiveDot> =
        database.syncReceiveDao().handledDots(syncSpaceId.value).map { record ->
            HandledReceiveDot(SyncSpaceId(record.syncSpaceId), ReplicaId(record.replicaId), record.counter, record.mutationId)
        }

    override suspend fun saveHandledDot(value: HandledReceiveDot) {
        database.syncReceiveDao().saveHandledDot(HandledReceiveDotRecord(
            value.syncSpaceId.value, value.replicaId.value, value.counter, value.mutationId,
        ))
    }

    override suspend fun quarantine(value: ProtocolQuarantine) {
        database.syncReceiveDao().saveQuarantine(ProtocolQuarantineRecord(
            value.syncSpaceId.value, value.mutationId, value.serverCursor, value.reason.name, value.detail,
        ))
    }

    override suspend fun quarantine(syncSpaceId: SyncSpaceId, mutationId: String): ProtocolQuarantine? =
        database.syncReceiveDao().quarantine(syncSpaceId.value, mutationId)?.let { record ->
            ProtocolQuarantine(SyncSpaceId(record.syncSpaceId), record.mutationId, record.serverCursor, ProtocolQuarantineReason.valueOf(record.reason), record.detail)
        }

    override suspend fun saveConflict(value: SyncConflict) {
        database.syncReceiveDao().saveConflict(SyncConflictRecord(
            value.conflictId,
            value.syncSpaceId.value,
            SyncReceiveStateCodec.encodeConflict(value),
        ))
    }

    override suspend fun conflict(conflictId: String): SyncConflict? =
        database.syncReceiveDao().conflict(conflictId)?.let { SyncReceiveStateCodec.decodeConflict(it.conflictJson) }

    override suspend fun conflicts(syncSpaceId: SyncSpaceId): List<SyncConflict> =
        database.syncReceiveDao().conflicts(syncSpaceId.value).map { SyncReceiveStateCodec.decodeConflict(it.conflictJson) }
}

class RoomSyncOutboundEnvelopeRepository(private val database: AgenticSchedulerDatabase) : SyncOutboundEnvelopeRepository {
    override suspend fun envelope(syncSpaceId: SyncSpaceId, mutationId: String): StoredOutboundEnvelope? =
        database.mutationJournalDao().syncOperation(mutationId)?.takeIf { it.outboundSyncSpaceId == syncSpaceId.value }?.let { record ->
            StoredOutboundEnvelope(
                syncSpaceId = SyncSpaceId(requireNotNull(record.outboundSyncSpaceId)),
                mutationId = record.mutationId,
                envelope = EncryptedEnvelopeV1(
                    syncSpaceId = SyncSpaceId(requireNotNull(record.outboundSyncSpaceId)),
                    mutationId = record.mutationId,
                    senderDeviceId = DeviceId(requireNotNull(record.outboundSenderDeviceId)),
                    keyEpoch = requireNotNull(record.outboundKeyEpoch),
                    ciphertextBase64Url = requireNotNull(record.outboundCiphertextBase64Url),
                ),
                uploaded = record.outboundUploaded,
            )
        }

    override suspend fun save(value: StoredOutboundEnvelope) {
        val current = requireNotNull(database.mutationJournalDao().syncOperation(value.mutationId))
        database.mutationJournalDao().upsertSyncOperation(current.copy(
            outboundSyncSpaceId = value.syncSpaceId.value,
            outboundSenderDeviceId = value.envelope.senderDeviceId.value,
            outboundKeyEpoch = value.envelope.keyEpoch,
            outboundCiphertextBase64Url = value.envelope.ciphertextBase64Url,
            outboundUploaded = value.uploaded,
        ))
    }

    override suspend fun markUploaded(syncSpaceId: SyncSpaceId, mutationId: String) {
        val current = requireNotNull(database.mutationJournalDao().syncOperation(mutationId))
        require(current.outboundSyncSpaceId == syncSpaceId.value)
        database.mutationJournalDao().upsertSyncOperation(current.copy(outboundUploaded = true))
    }
}

/** D8-02b key ring. Every write is one Room transaction and stores only opaque secure-store references. */
class RoomSyncKeyMetadataRepository(private val database: AgenticSchedulerDatabase) : SyncKeyRingRepository {
    override suspend fun state(syncSpaceId: SyncSpaceId): SyncSpaceKeyState? = database.withWriteTransaction {
        migrateLegacyState(syncSpaceId)
        database.syncKeyRingDao().state(syncSpaceId.value)?.toState()
    }

    override suspend fun currentEncryptionKey(syncSpaceId: SyncSpaceId): SyncSpaceContentKeyMetadata? = database.withWriteTransaction {
        migrateLegacyState(syncSpaceId)
        database.syncKeyRingDao().state(syncSpaceId.value)?.let { state ->
            database.syncKeyRingDao().key(syncSpaceId.value, state.activeEncryptionEpoch)?.toMetadata()
        }
    }

    override suspend fun decryptionKey(syncSpaceId: SyncSpaceId, keyEpoch: Long): SyncSpaceContentKeyMetadata? = database.withWriteTransaction {
        migrateLegacyState(syncSpaceId)
        database.syncKeyRingDao().key(syncSpaceId.value, keyEpoch)?.toMetadata()
    }

    override suspend fun historicalDecryptKeys(syncSpaceId: SyncSpaceId): List<SyncSpaceContentKeyMetadata> = database.withWriteTransaction {
        migrateLegacyState(syncSpaceId)
        database.syncKeyRingDao().historicalKeys(syncSpaceId.value).map(SyncSpaceContentKeyRecord::toMetadata)
    }

    override suspend fun installNewEpoch(
        syncSpaceId: SyncSpaceId,
        keyEpoch: Long,
        contentKeyReference: SecretReference,
        contentKeyIdentity: ContentKeyIdentity,
    ): InstallSyncSpaceKeyEpochResult = database.withWriteTransaction {
        require(keyEpoch >= 0)
        migrateLegacyState(syncSpaceId)
        val state = database.syncKeyRingDao().state(syncSpaceId.value)
        when {
            state == null -> {
                database.syncKeyRingDao().saveState(SyncSpaceKeyStateRecord(syncSpaceId.value, keyEpoch))
                database.syncKeyRingDao().saveKey(SyncSpaceContentKeyRecord(syncSpaceId.value, keyEpoch, contentKeyReference.value, contentKeyIdentity.value, SyncSpaceContentKeyUsage.ACTIVE.name))
                InstallSyncSpaceKeyEpochResult.Installed
            }
            keyEpoch < state.activeEncryptionEpoch -> InstallSyncSpaceKeyEpochResult.RejectedRollback(state.activeEncryptionEpoch)
            keyEpoch == state.activeEncryptionEpoch -> {
                val active = requireNotNull(database.syncKeyRingDao().key(syncSpaceId.value, keyEpoch))
                if (active.keyIdentity == contentKeyIdentity.value) InstallSyncSpaceKeyEpochResult.Idempotent
                else InstallSyncSpaceKeyEpochResult.IntegrityError
            }
            else -> {
                database.syncKeyRingDao().demoteActive(syncSpaceId.value)
                database.syncKeyRingDao().saveKey(SyncSpaceContentKeyRecord(syncSpaceId.value, keyEpoch, contentKeyReference.value, contentKeyIdentity.value, SyncSpaceContentKeyUsage.ACTIVE.name))
                database.syncKeyRingDao().saveState(SyncSpaceKeyStateRecord(syncSpaceId.value, keyEpoch))
                InstallSyncSpaceKeyEpochResult.Advanced
            }
        }
    }

    override suspend fun installKeyPackage(
        syncSpaceId: SyncSpaceId,
        activeKey: SyncKeyPackageKeyReference,
        historicalReferences: List<SyncKeyPackageKeyReference>,
    ): InstallSyncKeyPackageResult = database.withWriteTransaction {
        val activeEpoch = activeKey.keyEpoch
        require(historicalReferences.map(SyncKeyPackageKeyReference::keyEpoch).distinct().size == historicalReferences.size)
        require(historicalReferences.all { it.keyEpoch < activeEpoch })
        migrateLegacyState(syncSpaceId)
        val state = database.syncKeyRingDao().state(syncSpaceId.value)
        if (state != null && activeEpoch < state.activeEncryptionEpoch) {
            return@withWriteTransaction InstallSyncKeyPackageResult.RejectedRollback(state.activeEncryptionEpoch)
        }

        val adoptedEpochs = linkedSetOf<Long>()
        val reusedEpochs = linkedSetOf<Long>()
        historicalReferences.forEach { historical ->
            val existing = database.syncKeyRingDao().key(syncSpaceId.value, historical.keyEpoch)
            when {
                existing == null -> adoptedEpochs += historical.keyEpoch
                existing.keyIdentity == historical.identity.value -> reusedEpochs += historical.keyEpoch
                else -> return@withWriteTransaction InstallSyncKeyPackageResult.IntegrityError
            }
        }

        val currentActive = state?.let { database.syncKeyRingDao().key(syncSpaceId.value, it.activeEncryptionEpoch) }
        when {
            state != null && activeEpoch == state.activeEncryptionEpoch && currentActive == null ->
                return@withWriteTransaction InstallSyncKeyPackageResult.IntegrityError
            state != null && activeEpoch == state.activeEncryptionEpoch && currentActive!!.keyIdentity != activeKey.identity.value ->
                return@withWriteTransaction InstallSyncKeyPackageResult.IntegrityError
            state != null && activeEpoch == state.activeEncryptionEpoch -> reusedEpochs += activeEpoch
            else -> {
                val existingAtIncomingEpoch = database.syncKeyRingDao().key(syncSpaceId.value, activeEpoch)
                when {
                    existingAtIncomingEpoch == null -> adoptedEpochs += activeEpoch
                    existingAtIncomingEpoch.keyIdentity == activeKey.identity.value -> reusedEpochs += activeEpoch
                    else -> return@withWriteTransaction InstallSyncKeyPackageResult.IntegrityError
                }
            }
        }

        val adoption = SyncKeyPackageAdoption(adoptedEpochs, reusedEpochs)
        historicalReferences.filter { it.keyEpoch in adoptedEpochs }.forEach { historical ->
            database.syncKeyRingDao().saveKey(
                SyncSpaceContentKeyRecord(
                    syncSpaceId.value,
                    historical.keyEpoch,
                    historical.reference.value,
                    historical.identity.value,
                    SyncSpaceContentKeyUsage.DECRYPT_ONLY.name,
                ),
            )
        }

        return@withWriteTransaction when {
            state == null -> {
                database.syncKeyRingDao().saveKey(
                    SyncSpaceContentKeyRecord(
                        syncSpaceId.value,
                        activeEpoch,
                        activeKey.reference.value,
                        activeKey.identity.value,
                        SyncSpaceContentKeyUsage.ACTIVE.name,
                    ),
                )
                database.syncKeyRingDao().saveState(SyncSpaceKeyStateRecord(syncSpaceId.value, activeEpoch))
                InstallSyncKeyPackageResult.Installed(adoption)
            }
            activeEpoch > state.activeEncryptionEpoch -> {
                database.syncKeyRingDao().demoteActive(syncSpaceId.value)
                if (activeEpoch in adoptedEpochs) {
                    database.syncKeyRingDao().saveKey(
                        SyncSpaceContentKeyRecord(
                            syncSpaceId.value,
                            activeEpoch,
                            activeKey.reference.value,
                            activeKey.identity.value,
                            SyncSpaceContentKeyUsage.ACTIVE.name,
                        ),
                    )
                } else {
                    val existing = requireNotNull(database.syncKeyRingDao().key(syncSpaceId.value, activeEpoch))
                    database.syncKeyRingDao().saveKey(existing.copy(usage = SyncSpaceContentKeyUsage.ACTIVE.name))
                }
                database.syncKeyRingDao().saveState(SyncSpaceKeyStateRecord(syncSpaceId.value, activeEpoch))
                InstallSyncKeyPackageResult.Advanced(adoption)
            }
            adoptedEpochs.isNotEmpty() -> InstallSyncKeyPackageResult.Repaired(adoption)
            else -> InstallSyncKeyPackageResult.Idempotent(adoption)
        }
    }

    /** Converts the unmerged v6 single-key record once, preserving it as the active key. */
    private suspend fun migrateLegacyState(syncSpaceId: SyncSpaceId) {
        if (database.syncKeyRingDao().state(syncSpaceId.value) != null) return
        val legacy = database.syncKeyMetadataDao().keyEpoch(syncSpaceId.value) ?: return
        database.syncKeyRingDao().saveState(SyncSpaceKeyStateRecord(legacy.syncSpaceId, legacy.acceptedKeyEpoch))
        database.syncKeyRingDao().saveKey(SyncSpaceContentKeyRecord(legacy.syncSpaceId, legacy.acceptedKeyEpoch, legacy.contentKeySecretRef, legacy.contentKeySecretRef, SyncSpaceContentKeyUsage.ACTIVE.name))
    }
}

/** D8 SYN-006 local enrollment metadata; server enrollment requests are never stored here. */
class RoomLocalEnrollmentRepository(private val database: AgenticSchedulerDatabase) : LocalEnrollmentRepository {
    override suspend fun state(accountId: dev.agenticscheduler.sync.AccountId): LocalEnrollmentState? =
        database.localPairingEnrollmentDao().state(accountId.value)?.toLocalEnrollmentState()

    override suspend fun savePending(value: LocalEnrollmentState.Pending) {
        database.localPairingEnrollmentDao().save(value.toRecord())
    }

    override suspend fun saveActive(value: LocalEnrollmentState.Active) {
        database.localPairingEnrollmentDao().save(value.toRecord())
    }
}

private fun SyncSpaceKeyStateRecord.toState() = SyncSpaceKeyState(SyncSpaceId(syncSpaceId), activeEncryptionEpoch)
private fun SyncSpaceContentKeyRecord.toMetadata() = SyncSpaceContentKeyMetadata(SyncSpaceId(syncSpaceId), keyEpoch, SecretReference(contentKeySecretRef), ContentKeyIdentity(keyIdentity.ifBlank { contentKeySecretRef }), SyncSpaceContentKeyUsage.valueOf(usage))

private fun LocalPairingEnrollmentRecord.toLocalEnrollmentState(): LocalEnrollmentState = when (status) {
    "PENDING" -> {
        require(syncSpaceId == null && accountMasterKeySecretRef == null && deviceCredentialSecretRef == null) {
            "Pending local enrollment must not carry active secret references."
        }
        LocalEnrollmentState.Pending(
            dev.agenticscheduler.sync.AccountId(accountId),
            dev.agenticscheduler.sync.DeviceId(deviceId),
            dev.agenticscheduler.sync.EnrollmentRequestId(enrollmentRequestId),
            dev.agenticscheduler.sync.HpkePublicKeyBase64Url(hpkePublicKeyBase64Url),
            SecretReference(hpkePrivateKeySecretRef),
        )
    }
    "ACTIVE" -> LocalEnrollmentState.Active(
        dev.agenticscheduler.sync.AccountId(accountId),
        dev.agenticscheduler.sync.DeviceId(deviceId),
        dev.agenticscheduler.sync.EnrollmentRequestId(enrollmentRequestId),
        dev.agenticscheduler.sync.HpkePublicKeyBase64Url(hpkePublicKeyBase64Url),
        SecretReference(hpkePrivateKeySecretRef),
        SyncSpaceId(requireNotNull(syncSpaceId)),
        SecretReference(requireNotNull(accountMasterKeySecretRef)),
        SecretReference(requireNotNull(deviceCredentialSecretRef)),
    )
    else -> error("Unknown local pairing enrollment status: $status")
}

private fun LocalEnrollmentState.Pending.toRecord() = LocalPairingEnrollmentRecord(
    accountId.value, deviceId.value, enrollmentRequestId.value, hpkePublicKey.value, hpkePrivateKeyReference.value,
    "PENDING", null, null, null,
)

private fun LocalEnrollmentState.Active.toRecord() = LocalPairingEnrollmentRecord(
    accountId.value, deviceId.value, enrollmentRequestId.value, hpkePublicKey.value, hpkePrivateKeyReference.value,
    "ACTIVE", syncSpaceId.value, accountMasterKeyReference.value, deviceCredentialReference.value,
)

private fun ChangeLogEntryRecord.toHistoryChange(record: MutationRecord) = HistoryChange(mutationId, ordinal, dev.agenticscheduler.sync.EntityKind.valueOf(entityKind), entityId, operationKind, beforeImageJson, afterImageJson, HlcTimestamp(record.hlcPhysicalMillis, record.hlcLogical, ReplicaId(record.hlcReplicaId)))
private val historyChangeComparator = compareBy<HistoryChange>({ it.hlc.physicalMillis }, { it.hlc.logical }, { it.hlc.replicaId.value }, { it.mutationId }, { it.ordinal })

private fun MutationOrigin.durableName(): String = when (this) {
    MutationOrigin.User -> "USER"
    MutationOrigin.Planner -> "PLANNER"
    MutationOrigin.System -> "SYSTEM"
    is MutationOrigin.Undo -> "UNDO:$originalMutationId"
}

class RoomEventRepository(private val database: AgenticSchedulerDatabase) : EventRepository {
    override fun observeAll(): Flow<kotlinx.collections.immutable.ImmutableList<Event>> = database.eventDao().observeAll().map { rows -> rows.map { it.toDomain() }.toImmutableList() }
    override suspend fun get(id: EventId): Event? = database.eventDao().get(id.value)?.toDomain()
    override suspend fun upsert(event: Event) = database.eventDao().upsert(event.toRecord())
}

class RoomTaskRepository(private val database: AgenticSchedulerDatabase) : TaskRepository {
    override fun observeTasks() = database.taskDao().observeAll().map { it.map { row -> row.toDomain() }.toImmutableList() }
    override suspend fun getTask(id: TaskId) = database.taskDao().get(id.value)?.toDomain()
    override suspend fun upsertTask(task: Task) = database.taskDao().upsert(task.toRecord())
    override fun observeFocusBlocks() = database.focusBlockDao().observeAll().map { it.map { row -> row.toDomain() }.toImmutableList() }
    override suspend fun getFocusBlock(id: FocusBlockId) = database.focusBlockDao().get(id.value)?.toDomain()
    override suspend fun upsertFocusBlock(focusBlock: FocusBlock) = database.focusBlockDao().upsert(focusBlock.toRecord())
    override suspend fun deleteFocusBlock(id: FocusBlockId) = database.focusBlockDao().delete(id.value)
    override fun observeWorkLogs() = database.workLogDao().observeAll().map { it.map { row -> row.toDomain() }.toImmutableList() }
    override suspend fun getWorkLog(id: WorkLogId) = database.workLogDao().get(id.value)?.toDomain()
    override suspend fun upsertWorkLog(workLog: WorkLog) = database.workLogDao().upsert(workLog.toRecord())
    override fun observeDependencies() = database.taskDependencyDao().observeAll().map { it.map { row -> row.toDomain() }.toImmutableList() }
    override suspend fun getDependency(id: TaskDependencyId) = database.taskDependencyDao().get(id.value)?.toDomain()
    override suspend fun upsertDependency(dependency: TaskDependency) {
        val record = dependency.toRecord()
        check(database.taskDependencyDao().idForPair(record.prerequisiteTaskId, record.dependentTaskId)?.let { it == record.id } != false) {
            "A TaskDependency pair must be unique."
        }
        database.taskDependencyDao().upsert(record)
    }
}

class RoomPlanningProfileRepository(private val database: AgenticSchedulerDatabase) : PlanningProfileRepository {
    override fun observeAll(): Flow<kotlinx.collections.immutable.ImmutableList<PlanningProfile>> = combine(database.planningProfileDao().observeAll(), database.planningProfileDao().observeAllWindows()) { parents,windows -> parents.map { it.toDomain(windows.filter { window -> window.planningProfileId==it.id }) }.toImmutableList() }
    override suspend fun get(id: PlanningProfileId) = database.planningProfileDao().get(id.value)?.let { it.toDomain(database.planningProfileDao().windows(it.id)) }
    override suspend fun upsert(profile: PlanningProfile) = database.withWriteTransaction { database.planningProfileDao().upsert(profile.toRecord()); database.planningProfileDao().deleteWindows(profile.id.value); database.planningProfileDao().upsertWindows(profile.windowRecords()) }
}

class RoomAcademicRepository(private val database: AgenticSchedulerDatabase) : AcademicRepository {
    override fun observeAcademicYears() = database.academicYearDao().observeAll().map { it.map { row -> row.toDomain() }.toImmutableList() }
    override suspend fun getAcademicYear(id: AcademicYearId) = database.academicYearDao().get(id.value)?.toDomain()
    override suspend fun upsertAcademicYear(value: AcademicYear) = database.academicYearDao().upsert(value.toRecord())

    override fun observeSemesters() = combine(database.semesterDao().observeAll(), database.semesterDao().observeAllWeeks()) { parents, children -> parents.map { parent -> parent.toDomain(children.filter { it.semesterId == parent.id }) }.toImmutableList() }
    override suspend fun getSemester(id: SemesterId) = database.semesterDao().get(id.value)?.let { it.toDomain(database.semesterDao().weeks(it.id)) }
    override suspend fun upsertSemester(value: Semester) = database.withWriteTransaction { database.semesterDao().upsert(value.toRecord()); database.semesterDao().deleteWeeks(value.id.value); database.semesterDao().upsertWeeks(value.weekRecords()) }

    override fun observeCourses() = database.courseDao().observeAll().map { it.map { row -> row.toDomain() }.toImmutableList() }
    override suspend fun getCourse(id: CourseId) = database.courseDao().get(id.value)?.toDomain()
    override suspend fun upsertCourse(value: Course) = database.courseDao().upsert(value.toRecord())

    override fun observePeriodTemplates() = combine(database.periodTemplateDao().observeAll(), database.periodTemplateDao().observeAllPeriods()) { parents, children -> parents.map { parent -> parent.toDomain(children.filter { it.periodTemplateId == parent.id }) }.toImmutableList() }
    override suspend fun getPeriodTemplate(id: PeriodTemplateId) = database.periodTemplateDao().get(id.value)?.let { it.toDomain(database.periodTemplateDao().periods(it.id)) }
    override suspend fun upsertPeriodTemplate(value: PeriodTemplate) = database.withWriteTransaction { database.periodTemplateDao().upsert(value.toRecord()); database.periodTemplateDao().deletePeriods(value.id.value); database.periodTemplateDao().upsertPeriods(value.periodRecords()) }

    override fun observeCourseScheduleRules() = combine(database.courseScheduleRuleDao().observeAll(), database.courseScheduleRuleDao().observeAllWeeks()) { parents, children -> parents.map { parent -> parent.toDomain(children.filter { it.scheduleRuleId == parent.id }) }.toImmutableList() }
    override suspend fun getCourseScheduleRule(id: CourseScheduleRuleId) = database.courseScheduleRuleDao().get(id.value)?.let { it.toDomain(database.courseScheduleRuleDao().weeks(it.id)) }
    override suspend fun upsertCourseScheduleRule(value: CourseScheduleRule) = database.withWriteTransaction { database.courseScheduleRuleDao().upsert(value.toRecord()); database.courseScheduleRuleDao().deleteWeeks(value.id.value); database.courseScheduleRuleDao().upsertWeeks(value.weekRecords()) }

    override fun observeAcademicHolidays() = database.academicHolidayDao().observeAll().map { it.map { row -> row.toDomain() }.toImmutableList() }
    override suspend fun getAcademicHoliday(id: AcademicHolidayId) = database.academicHolidayDao().get(id.value)?.toDomain()
    override suspend fun upsertAcademicHoliday(value: AcademicHoliday) = database.academicHolidayDao().upsert(value.toRecord())
    override fun observeCourseOccurrenceExceptions() = database.courseOccurrenceExceptionDao().observeAll().map { it.map { row -> row.toDomain() }.toImmutableList() }
    override suspend fun getCourseOccurrenceException(id: CourseOccurrenceExceptionId) = database.courseOccurrenceExceptionDao().get(id.value)?.toDomain()
    override suspend fun upsertCourseOccurrenceException(value: CourseOccurrenceException) {
        val record = value.toRecord()
        check(database.courseOccurrenceExceptionDao().idForOccurrence(record.scheduleRuleId, record.academicWeekNumber)?.let { it == record.id } != false) {
            "A CourseOccurrenceException target must be unique."
        }
        database.courseOccurrenceExceptionDao().upsert(record)
    }
    override fun observeExams() = database.examDao().observeAll().map { it.map { row -> row.toDomain() }.toImmutableList() }
    override suspend fun getExam(id: ExamId) = database.examDao().get(id.value)?.toDomain()
    override suspend fun upsertExam(value: Exam) = database.examDao().upsert(value.toRecord())
}
