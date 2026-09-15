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

/** D8's durable receive seam: causal operations apply, concurrent operations use SYN-013 semantic groups. */
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
                    saveReceivedCausality(operation)
                    advanceCursor(receipt)
                    return@inWriteTransaction SyncReceiveResult.Conflicted(conflict.conflictId, conflict.kind)
                }
                when (relationToLocal(operation)) {
                    CausalRelation.BEFORE, CausalRelation.EQUAL -> {
                        advanceCursor(receipt)
                        SyncReceiveResult.IgnoredCausallyKnown(MutationId(operation.mutationId))
                    }
                    CausalRelation.CONCURRENT -> when (val outcome = semanticMerge(receipt.syncSpaceId, operation)) {
                        is SemanticMergeOutcome.Conflicted -> {
                            receiveState.saveConflict(outcome.conflict)
                            saveReceivedCausality(operation)
                            advanceCursor(receipt)
                            SyncReceiveResult.Conflicted(outcome.conflict.conflictId, outcome.conflict.kind)
                        }
                        is SemanticMergeOutcome.Merged -> commitReceived(operation, outcome.effectiveOperation, receipt)
                    }
                    CausalRelation.AFTER -> {
                        commitReceived(operation, operation, receipt)
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

    private suspend fun commitReceived(original: SyncOperation, effective: SyncOperation, receipt: DecryptedPayloadReceipt): SyncReceiveResult {
        apply(effective)
        journal.appendCommittedMutation(CommittedMutation(original, wallClock.nowEpochMillis()))
        journal.advanceFocusBlockTombstones(original, original.orderedMutations.filterIsInstance<FocusBlockDelete>())
        saveReceivedCausality(original)
        advanceCursor(receipt)
        return SyncReceiveResult.Applied(MutationId(original.mutationId))
    }

    private sealed interface SemanticMergeOutcome {
        data class Merged(val effectiveOperation: SyncOperation) : SemanticMergeOutcome
        data class Conflicted(val conflict: SyncConflict) : SemanticMergeOutcome
    }

    /**
     * SYN-013 compares only concurrent local operations that touched the same semantic entity.
     * A conflict blocks the entire incoming operation; otherwise its changed groups overlay
     * current typed state, preserving concurrent disjoint groups without an LWW decision.
     */
    private suspend fun semanticMerge(syncSpaceId: SyncSpaceId, incoming: SyncOperation): SemanticMergeOutcome {
        data class Pairing(val localOperation: SyncOperation, val localMutation: EntityMutation, val incomingMutation: EntityMutation)
        val pairings = incoming.orderedMutations.flatMap { remote ->
            history.entityChanges(remote.entityKind, remote.entityId)
                .mapNotNull { change -> history.mutation(change.mutationId)?.operation }
                .distinctBy(SyncOperation::mutationId)
                .filter { local -> relationBetween(local.dvv, incoming.dvv) == CausalRelation.CONCURRENT }
                .flatMap { local -> local.orderedMutations
                    .filter { it.entityKind == remote.entityKind && it.entityId == remote.entityId }
                    .map { localMutation -> Pairing(local, localMutation, remote) }
                }
        }
        val conflicts = pairings.map { pairing -> pairing to conflictingGroupNames(pairing.localMutation, pairing.incomingMutation) }
            .filter { (_, groups) -> groups.isNotEmpty() }
        if (conflicts.isNotEmpty()) {
            val participants = (conflicts.map { it.first.localOperation } + incoming)
                .distinctBy(SyncOperation::mutationId)
                .sortedBy(SyncOperation::mutationId)
                .map { operation -> SyncConflictParticipant(MutationId(operation.mutationId), operation.dvv, LocalJournalCodec.encode(operation)) }
            val refs = conflicts.map { (pairing, groups) -> SyncConflictEntityRef(pairing.incomingMutation.entityKind, pairing.incomingMutation.entityId, groups) }
                .groupBy { it.entityKind to it.entityId }
                .map { (entity, values) -> SyncConflictEntityRef(entity.first, entity.second, values.flatMap(SyncConflictEntityRef::groups).distinct().sorted()) }
                .sortedWith(compareBy(SyncConflictEntityRef::entityKind, SyncConflictEntityRef::entityId))
            val conflictId = listOf(
                syncSpaceId.value,
                SyncConflictKind.SEMANTIC.name,
                refs.joinToString(",") { "${it.entityKind.name}:${it.entityId}:${it.groups.joinToString("+")}" },
                participants.joinToString(",") { it.mutationId.value },
            ).joinToString("|")
            return SemanticMergeOutcome.Conflicted(SyncConflict(
                conflictId = conflictId,
                syncSpaceId = syncSpaceId,
                entityRefs = refs,
                participants = participants,
                provisionalMutationId = participants.minBy { it.mutationId.value }.mutationId,
                kind = SyncConflictKind.SEMANTIC,
                commonCausalContext = commonCausalContextOf(participants),
                status = SyncConflictStatus.OPEN,
            ))
        }
        val concurrentEntityKeys = pairings.map { it.incomingMutation.entityKind to it.incomingMutation.entityId }.toSet()
        val effective = incoming.copy(orderedMutations = incoming.orderedMutations.map { mutation ->
            if ((mutation.entityKind to mutation.entityId) !in concurrentEntityKeys) mutation
            else currentMutation(mutation)?.let { current -> mergeWithCurrent(current, mutation) } ?: mutation
        })
        return SemanticMergeOutcome.Merged(effective)
    }

    private suspend fun currentMutation(incoming: EntityMutation): EntityMutation? = when (incoming) {
        is EventPut -> events.get(dev.agenticscheduler.domain.id.EventId(incoming.entityId))?.toSemanticImage()?.let { EventPut(null, it) }
        is TaskPut -> tasks.getTask(dev.agenticscheduler.domain.id.TaskId(incoming.entityId))?.toSemanticImage()?.let { TaskPut(null, it) }
        is PlanningProfilePut -> profiles.get(dev.agenticscheduler.domain.id.PlanningProfileId(incoming.entityId))?.toSemanticImage()?.let { PlanningProfilePut(null, it) }
        is FocusBlockPut -> tasks.getFocusBlock(FocusBlockId(incoming.entityId))?.toSemanticImage()?.let { FocusBlockPut(null, it) }
        is ExamPut -> academics.getExam(dev.agenticscheduler.domain.id.ExamId(incoming.entityId))?.toSemanticImage()?.let { ExamPut(null, it) }
        else -> null
    }

    private fun relationBetween(left: DvvSnapshot, right: DvvSnapshot): CausalRelation {
        val leftVector = left.toDottedVersionVector().observedContext()
        val rightVector = right.toDottedVersionVector().observedContext()
        var leftGreater = false
        var rightGreater = false
        (leftVector.keys + rightVector.keys).forEach { replica ->
            when ((leftVector[replica] ?: -1L).compareTo(rightVector[replica] ?: -1L)) {
                1 -> leftGreater = true
                -1 -> rightGreater = true
            }
        }
        return when {
            !leftGreater && !rightGreater -> CausalRelation.EQUAL
            leftGreater && !rightGreater -> CausalRelation.AFTER
            !leftGreater && rightGreater -> CausalRelation.BEFORE
            else -> CausalRelation.CONCURRENT
        }
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
