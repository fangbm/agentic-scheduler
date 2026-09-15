package dev.agenticscheduler.application.history

import dev.agenticscheduler.application.id.UuidV7Generator
import dev.agenticscheduler.application.persistence.*
import dev.agenticscheduler.domain.id.FocusBlockId
import dev.agenticscheduler.domain.id.WorkLogId
import dev.agenticscheduler.sync.*

/** D8-01b decrypted receive input. D8-02 owns envelope authentication and supplies this trusted context. */
data class DecryptedPayloadReceipt(
    val syncSpaceId: SyncSpaceId,
    val mutationId: String,
    val serverCursor: Long,
    val payloadJson: String,
) { init { require(serverCursor >= 0) } }

sealed interface SyncReceiveResult {
    data class Applied(val mutationId: MutationId) : SyncReceiveResult
    data class Duplicate(val mutationId: MutationId) : SyncReceiveResult
    data class IgnoredCausallyKnown(val mutationId: MutationId) : SyncReceiveResult
    data class RequiresSemanticMerge(val mutationId: MutationId) : SyncReceiveResult
    data class Conflicted(val conflictId: String, val kind: SyncConflictKind) : SyncReceiveResult
    data class Quarantined(val mutationId: String, val reason: ProtocolQuarantineReason) : SyncReceiveResult
}

/**
 * D8-01b's durable receive seam. It intentionally does not choose a concurrent
 * winner: D8-01c receives those operations before their cursor can advance.
 */
class SyncEngine(
    private val transactions: ApplicationTransactionRunner,
    private val journal: MutationJournalRepository,
    private val history: HistoryRepository,
    private val receiveState: SyncReceiveRepository,
    private val events: EventRepository,
    private val tasks: TaskRepository,
    private val profiles: PlanningProfileRepository,
    private val academics: AcademicRepository,
    private val ids: UuidV7Generator,
    private val wallClock: MutationWallClock,
) {
    suspend fun receive(receipt: DecryptedPayloadReceipt): SyncReceiveResult {
        val decoded = SyncWireCodec.decodePayload(receipt.payloadJson)
        val operation = when (decoded) {
            is PayloadDecodeResult.Supported -> decoded.payload.operation
            is PayloadDecodeResult.UnsupportedVersion -> return quarantine(receipt, ProtocolQuarantineReason.UNSUPPORTED_PAYLOAD_VERSION, decoded.actual?.toString())
            is PayloadDecodeResult.UnsupportedMutation -> return quarantine(receipt, ProtocolQuarantineReason.UNSUPPORTED_MUTATION, decoded.discriminator)
            is PayloadDecodeResult.Invalid -> return quarantine(receipt, ProtocolQuarantineReason.INVALID_PAYLOAD, decoded.reason)
        }
        if (operation.mutationId != receipt.mutationId) return quarantine(receipt, ProtocolQuarantineReason.INVALID_PAYLOAD, "Payload MutationId does not match its envelope.")
        return try {
            transactions.inWriteTransaction {
                if (history.mutation(operation.mutationId) != null) {
                    advanceCursor(receipt)
                    return@inWriteTransaction SyncReceiveResult.Duplicate(MutationId(operation.mutationId))
                }
                integrityConflict(receipt.syncSpaceId, operation)?.let { conflict ->
                    receiveState.saveConflict(conflict)
                    advanceCursor(receipt)
                    return@inWriteTransaction SyncReceiveResult.Conflicted(conflict.conflictId, conflict.kind)
                }
                when (relationToLocal(operation)) {
                    CausalRelation.BEFORE, CausalRelation.EQUAL -> {
                        advanceCursor(receipt)
                        SyncReceiveResult.IgnoredCausallyKnown(MutationId(operation.mutationId))
                    }
                    CausalRelation.CONCURRENT -> SyncReceiveResult.RequiresSemanticMerge(MutationId(operation.mutationId))
                    CausalRelation.AFTER -> {
                        apply(operation)
                        journal.appendCommittedMutation(CommittedMutation(operation, wallClock.nowEpochMillis()))
                        journal.advanceFocusBlockTombstones(operation, operation.orderedMutations.filterIsInstance<FocusBlockDelete>())
                        saveReceivedCausality(operation)
                        advanceCursor(receipt)
                        SyncReceiveResult.Applied(MutationId(operation.mutationId))
                    }
                }
            }
        } catch (failure: IllegalArgumentException) {
            quarantine(receipt, ProtocolQuarantineReason.INVALID_PAYLOAD, failure.message)
        }
    }

    private suspend fun quarantine(receipt: DecryptedPayloadReceipt, reason: ProtocolQuarantineReason, detail: String?): SyncReceiveResult = transactions.inWriteTransaction {
        if (receiveState.quarantine(receipt.syncSpaceId, receipt.mutationId) == null) {
            receiveState.quarantine(ProtocolQuarantine(receipt.syncSpaceId, receipt.mutationId, receipt.serverCursor, reason, detail))
        }
        advanceCursor(receipt)
        SyncReceiveResult.Quarantined(receipt.mutationId, reason)
    }

    private suspend fun relationToLocal(operation: SyncOperation): CausalRelation {
        val prior = journal.localReplicaState() ?: return CausalRelation.AFTER
        val incoming = operation.dvv.toDottedVersionVector().observedContext()
        val replicas = (incoming.keys + prior.observedContext.keys).sortedBy(ReplicaId::value)
        var incomingGreater = false
        var localGreater = false
        replicas.forEach { replica ->
            when ((incoming[replica] ?: -1L).compareTo(prior.observedContext[replica] ?: -1L)) {
                1 -> incomingGreater = true
                -1 -> localGreater = true
            }
        }
        return when {
            !incomingGreater && !localGreater -> CausalRelation.EQUAL
            incomingGreater && !localGreater -> CausalRelation.AFTER
            !incomingGreater && localGreater -> CausalRelation.BEFORE
            else -> CausalRelation.CONCURRENT
        }
    }

    private suspend fun apply(operation: SyncOperation) {
        operation.orderedMutations.forEach { mutation -> when (mutation) {
            is EventPut -> events.upsert(mutation.after.toDomain())
            is TaskPut -> tasks.upsertTask(mutation.after.toDomain())
            is PlanningProfilePut -> profiles.upsert(mutation.after.toDomain())
            is FocusBlockPut -> tasks.upsertFocusBlock(mutation.after.toDomain())
            is FocusBlockDelete -> tasks.deleteFocusBlock(FocusBlockId(mutation.entityId))
            is WorkLogAppend -> {
                val current = tasks.getWorkLog(WorkLogId(mutation.entityId))
                if (current == null) tasks.upsertWorkLog(mutation.after.toDomain())
            }
            is TaskDependencyPut -> tasks.upsertDependency(mutation.after.toDomain())
            is AcademicYearPut -> academics.upsertAcademicYear(mutation.after.toDomain())
            is SemesterPut -> academics.upsertSemester(mutation.after.toDomain())
            is CoursePut -> academics.upsertCourse(mutation.after.toDomain())
            is PeriodTemplatePut -> academics.upsertPeriodTemplate(mutation.after.toDomain())
            is AcademicHolidayPut -> academics.upsertAcademicHoliday(mutation.after.toDomain())
            is CourseScheduleRulePut -> academics.upsertCourseScheduleRule(mutation.after.toDomain())
            is CourseOccurrenceExceptionPut -> academics.upsertCourseOccurrenceException(mutation.after.toDomain())
            is ExamPut -> academics.upsertExam(mutation.after.toDomain())
        } }
    }

    /** D8-A02/A05 preflight: a single immutable WorkLog divergence conflicts the whole incoming MutationId. */
    private suspend fun integrityConflict(syncSpaceId: SyncSpaceId, incoming: SyncOperation): SyncConflict? {
        val divergent = incoming.orderedMutations.filterIsInstance<WorkLogAppend>().mapNotNull { mutation ->
            val current = tasks.getWorkLog(WorkLogId(mutation.entityId)) ?: return@mapNotNull null
            if (current.toSemanticImage() == mutation.after) return@mapNotNull null
            val change = history.entityChanges(EntityKind.WORK_LOG, mutation.entityId).lastOrNull() ?: return@mapNotNull null
            val local = history.mutation(change.mutationId)?.operation ?: return@mapNotNull null
            mutation.entityId to local
        }
        if (divergent.isEmpty()) return null
        val localOperations = divergent.map { it.second }.distinctBy(SyncOperation::mutationId)
        val participants = (localOperations + incoming).sortedBy(SyncOperation::mutationId).map { operation ->
            SyncConflictParticipant(MutationId(operation.mutationId), operation.dvv, LocalJournalCodec.encode(operation))
        }
        val refs = divergent.map { (workLogId, _) -> SyncConflictEntityRef(EntityKind.WORK_LOG, workLogId, listOf("append")) }
            .distinctBy { it.entityId }.sortedBy { it.entityId }
        val conflictId = listOf(syncSpaceId.value, SyncConflictKind.INTEGRITY.name, refs.joinToString(",") { "${it.entityKind.name}:${it.entityId}" }, participants.joinToString(",") { it.mutationId.value }).joinToString("|")
        return SyncConflict(
            conflictId = conflictId,
            syncSpaceId = syncSpaceId,
            entityRefs = refs,
            participants = participants,
            provisionalMutationId = participants.minBy { it.mutationId.value }.mutationId,
            kind = SyncConflictKind.INTEGRITY,
            commonCausalContext = commonCausalContextOf(participants),
            status = SyncConflictStatus.OPEN,
        )
    }

    private suspend fun saveReceivedCausality(operation: SyncOperation) {
        val prior = journal.localReplicaState()
        val replicaId = prior?.replicaId ?: ReplicaId(ids.next())
        val observed = buildMap {
            putAll(prior?.observedContext.orEmpty())
            operation.dvv.toDottedVersionVector().observedContext().forEach { (replica, counter) -> put(replica, maxOf(get(replica) ?: -1L, counter)) }
        }
        val receivedHlc = HybridLogicalClock.tickReceive(prior?.lastHlc, operation.hlc.toTimestamp(), wallClock.nowEpochMillis(), replicaId)
        journal.saveLocalReplicaState(LocalReplicaCausalState(replicaId, prior?.lastCounter ?: 0L, observed, receivedHlc))
    }

    private suspend fun advanceCursor(receipt: DecryptedPayloadReceipt) {
        receiveState.saveServerCursor(receipt.syncSpaceId, maxOf(receiveState.serverCursor(receipt.syncSpaceId), receipt.serverCursor))
    }
}
