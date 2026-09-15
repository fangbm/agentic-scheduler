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
    /** Valid operation retained locally until every DVV-context dependency has been durably handled. */
    data class PendingCausalGap(val mutationId: MutationId, val missingPrerequisites: List<Dot>) : SyncReceiveResult
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
    suspend fun receive(receipt: DecryptedPayloadReceipt): SyncReceiveResult = receiveInternal(receipt, drainPending = true)

    private suspend fun receiveInternal(receipt: DecryptedPayloadReceipt, drainPending: Boolean): SyncReceiveResult {
        val decoded = SyncWireCodec.decodePayload(receipt.payloadJson)
        val operation = when (decoded) {
            is PayloadDecodeResult.Supported -> decoded.payload.operation
            is PayloadDecodeResult.UnsupportedVersion -> return quarantine(receipt, ProtocolQuarantineReason.UNSUPPORTED_PAYLOAD_VERSION, decoded.actual?.toString())
            is PayloadDecodeResult.UnsupportedMutation -> return quarantine(receipt, ProtocolQuarantineReason.UNSUPPORTED_MUTATION, decoded.discriminator)
            is PayloadDecodeResult.Invalid -> return quarantine(receipt, ProtocolQuarantineReason.INVALID_PAYLOAD, decoded.reason)
        }
        if (operation.mutationId != receipt.mutationId) return quarantine(receipt, ProtocolQuarantineReason.INVALID_PAYLOAD, "Payload MutationId does not match its envelope.")
        val result = try {
            transactions.inWriteTransaction {
                if (history.mutation(operation.mutationId) != null) {
                    receiveState.removePending(receipt.syncSpaceId, operation.mutationId)
                    advanceCursor(receipt)
                    return@inWriteTransaction SyncReceiveResult.Duplicate(MutationId(operation.mutationId))
                }
                val dot = operation.dvv.dot
                val replicaId = ReplicaId(dot.replicaId)
                receiveState.handledDot(receipt.syncSpaceId, replicaId, dot.counter)?.let { existing ->
                    if (existing.mutationId != operation.mutationId) {
                        receiveState.quarantine(ProtocolQuarantine(
                            receipt.syncSpaceId,
                            receipt.mutationId,
                            receipt.serverCursor,
                            ProtocolQuarantineReason.INVALID_PAYLOAD,
                            "Causal dot is already bound to another MutationId.",
                        ))
                        advanceCursor(receipt)
                        return@inWriteTransaction SyncReceiveResult.Quarantined(receipt.mutationId, ProtocolQuarantineReason.INVALID_PAYLOAD)
                    }
                }
                val missingPrerequisites = missingCausalPrerequisites(receipt.syncSpaceId, operation)
                if (missingPrerequisites.isNotEmpty()) {
                    val pending = receiveState.pending(receipt.syncSpaceId, operation.mutationId)
                    require(pending == null || pending.payloadJson == receipt.payloadJson) { "A pending MutationId must retain one payload." }
                    receiveState.savePending(PendingSyncReceive(
                        receipt.syncSpaceId,
                        operation.mutationId,
                        receipt.serverCursor,
                        receipt.payloadJson,
                    ))
                    return@inWriteTransaction SyncReceiveResult.PendingCausalGap(MutationId(operation.mutationId), missingPrerequisites)
                }
                integrityConflict(receipt.syncSpaceId, operation)?.let { conflict ->
                    receiveState.saveConflict(conflict)
                    saveReceivedCausality(operation)
                    markHandled(receipt.syncSpaceId, operation)
                    advanceCursor(receipt)
                    return@inWriteTransaction SyncReceiveResult.Conflicted(conflict.conflictId, conflict.kind)
                }
                when (relationToLocal(operation)) {
                    CausalRelation.BEFORE, CausalRelation.EQUAL -> {
                        saveReceivedCausality(operation)
                        markHandled(receipt.syncSpaceId, operation)
                        advanceCursor(receipt)
                        SyncReceiveResult.IgnoredCausallyKnown(MutationId(operation.mutationId))
                    }
                    CausalRelation.CONCURRENT -> when (val outcome = semanticMerge(receipt.syncSpaceId, operation)) {
                        is SemanticMergeOutcome.Conflicted -> {
                            receiveState.saveConflict(outcome.conflict)
                            outcome.supersededConflictIds.forEach { conflictId ->
                                val prior = receiveState.conflict(conflictId)
                                if (prior?.status == SyncConflictStatus.OPEN) {
                                    receiveState.saveConflict(prior.copy(
                                        status = SyncConflictStatus.SUPERSEDED,
                                        supersededByConflictId = outcome.conflict.conflictId,
                                    ))
                                }
                            }
                            saveReceivedCausality(operation)
                            markHandled(receipt.syncSpaceId, operation)
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
        if (drainPending && result !is SyncReceiveResult.PendingCausalGap) drainPending(receipt.syncSpaceId)
        return result
    }

    private suspend fun quarantine(receipt: DecryptedPayloadReceipt, reason: ProtocolQuarantineReason, detail: String?): SyncReceiveResult = transactions.inWriteTransaction {
        if (receiveState.quarantine(receipt.syncSpaceId, receipt.mutationId) == null) {
            receiveState.quarantine(ProtocolQuarantine(receipt.syncSpaceId, receipt.mutationId, receipt.serverCursor, reason, detail))
        }
        receiveState.removePending(receipt.syncSpaceId, receipt.mutationId)
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
        markHandled(receipt.syncSpaceId, original)
        advanceCursor(receipt)
        return SyncReceiveResult.Applied(MutationId(original.mutationId))
    }

    /**
     * D8 out-of-order delivery guard. A received DVV context proves only that a sender observed
     * a dot; it does not prove this replica has durably handled that operation. Local journal
     * entries and explicit receive outcomes form the independent handled frontier.
     */
    private suspend fun missingCausalPrerequisites(syncSpaceId: SyncSpaceId, operation: SyncOperation): List<Dot> {
        val handled = mutableMapOf<ReplicaId, Long>()
        history.timeline().forEach { committed ->
            val dot = committed.operation.dvv.dot
            val replica = ReplicaId(dot.replicaId)
            handled[replica] = maxOf(handled[replica] ?: -1L, dot.counter)
        }
        receiveState.handledDots(syncSpaceId).forEach { handledDot ->
            handled[handledDot.replicaId] = maxOf(handled[handledDot.replicaId] ?: -1L, handledDot.counter)
        }
        return operation.dvv.context.mapNotNull { component ->
            val replica = ReplicaId(component.replicaId)
            if ((handled[replica] ?: -1L) < component.counter) Dot(replica, component.counter) else null
        }
    }

    private suspend fun markHandled(syncSpaceId: SyncSpaceId, operation: SyncOperation) {
        val dot = operation.dvv.dot
        receiveState.saveHandledDot(HandledReceiveDot(syncSpaceId, ReplicaId(dot.replicaId), dot.counter, operation.mutationId))
        receiveState.removePending(syncSpaceId, operation.mutationId)
    }

    /** Drains persisted receipts in cursor/MutationId order without recursively re-entering receive. */
    private suspend fun drainPending(syncSpaceId: SyncSpaceId) {
        while (true) {
            var progressed = false
            receiveState.pending(syncSpaceId).forEach { pending ->
                val result = receiveInternal(
                    DecryptedPayloadReceipt(pending.syncSpaceId, pending.mutationId, pending.serverCursor, pending.payloadJson),
                    drainPending = false,
                )
                if (result !is SyncReceiveResult.PendingCausalGap) progressed = true
            }
            if (!progressed) return
        }
    }

    private sealed interface SemanticMergeOutcome {
        data class Merged(val effectiveOperation: SyncOperation) : SemanticMergeOutcome
        data class Conflicted(val conflict: SyncConflict, val supersededConflictIds: List<String>) : SemanticMergeOutcome
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
        val directRefs = conflicts.map { (pairing, groups) ->
            SyncConflictEntityRef(pairing.incomingMutation.entityKind, pairing.incomingMutation.entityId, groups)
        }
        val directOperations = conflicts.map { it.first.localOperation } + incoming
        val coalesced = coalesceOpenSemanticComponent(syncSpaceId, incoming, directOperations, directRefs)
        if (coalesced != null) {
            val conflict = SyncConflict(
                conflictId = semanticConflictId(syncSpaceId, coalesced.refs, coalesced.participants),
                syncSpaceId = syncSpaceId,
                entityRefs = coalesced.refs,
                participants = coalesced.participants,
                provisionalMutationId = coalesced.participants.minBy { it.mutationId.value }.mutationId,
                kind = SyncConflictKind.SEMANTIC,
                commonCausalContext = commonCausalContextOf(coalesced.participants),
                status = SyncConflictStatus.OPEN,
            )
            return SemanticMergeOutcome.Conflicted(
                conflict,
                coalesced.absorbedConflictIds.filter { it != conflict.conflictId }.sorted(),
            )
        }
        val concurrentEntityKeys = pairings.map { it.incomingMutation.entityKind to it.incomingMutation.entityId }.toSet()
        val effective = incoming.copy(orderedMutations = incoming.orderedMutations.map { mutation ->
            if ((mutation.entityKind to mutation.entityId) !in concurrentEntityKeys) mutation
            else currentMutation(mutation)?.let { current -> mergeWithCurrent(current, mutation) } ?: mutation
        })
        return SemanticMergeOutcome.Merged(effective)
    }

    /**
     * SYN-013 conflict state is a connected N-way component, never a sequence
     * of pairwise records. Existing OPEN participants are retained even though
     * they were deliberately not accepted into Active State or the journal.
     */
    private suspend fun coalesceOpenSemanticComponent(
        syncSpaceId: SyncSpaceId,
        incoming: SyncOperation,
        directOperations: List<SyncOperation>,
        directRefs: List<SyncConflictEntityRef>,
    ): SemanticConflictComponent? {
        val open = receiveState.conflicts(syncSpaceId)
            .filter { it.status == SyncConflictStatus.OPEN && it.kind == SyncConflictKind.SEMANTIC }
        val operations = directOperations.associateBy(SyncOperation::mutationId).toMutableMap()
        val refs = directRefs.toMutableList()
        val selected = mutableSetOf<String>()
        var expanded: Boolean
        do {
            expanded = false
            open.filterNot { it.conflictId in selected }.forEach { conflict ->
                val participantOperations = conflict.participants.map { participant ->
                    LocalJournalCodec.decode(participant.candidateValuesJson)
                }
                val sharesParticipant = participantOperations.any { it.mutationId in operations }
                val overlapsIncoming = conflict.entityRefs.any { ref ->
                    incoming.orderedMutations.any { mutation ->
                        mutation.entityKind == ref.entityKind &&
                            mutation.entityId == ref.entityId &&
                            mutation.changedSemanticGroups().map(SemanticGroupValue::name).any(ref.groups::contains)
                    }
                } && participantOperations.any { relationBetween(it.dvv, incoming.dvv) == CausalRelation.CONCURRENT }
                if (sharesParticipant || overlapsIncoming) {
                    selected += conflict.conflictId
                    participantOperations.forEach { operations[it.mutationId] = it }
                    refs += conflict.entityRefs
                    expanded = true
                }
            }
        } while (expanded)
        if (directRefs.isEmpty() && selected.isEmpty()) return null
        val participants = operations.values.sortedBy(SyncOperation::mutationId).map { operation ->
            SyncConflictParticipant(MutationId(operation.mutationId), operation.dvv, LocalJournalCodec.encode(operation))
        }
        return SemanticConflictComponent(
            participants = participants,
            refs = refs.groupBy { it.entityKind to it.entityId }
                .map { (entity, values) ->
                    SyncConflictEntityRef(entity.first, entity.second, values.flatMap(SyncConflictEntityRef::groups).distinct().sorted())
                }
                .sortedWith(compareBy(SyncConflictEntityRef::entityKind, SyncConflictEntityRef::entityId)),
            absorbedConflictIds = selected.toList().sorted(),
        )
    }

    private data class SemanticConflictComponent(
        val participants: List<SyncConflictParticipant>,
        val refs: List<SyncConflictEntityRef>,
        val absorbedConflictIds: List<String>,
    )

    /** D8-A06: identity is exactly the canonical participant set plus semantic target. */
    private fun semanticConflictId(
        syncSpaceId: SyncSpaceId,
        refs: List<SyncConflictEntityRef>,
        participants: List<SyncConflictParticipant>,
    ): String = listOf(
        syncSpaceId.value,
        SyncConflictKind.SEMANTIC.name,
        refs.joinToString(",") { "${it.entityKind.name}:${it.entityId}:${it.groups.joinToString("+")}" },
        participants.joinToString(",") { it.mutationId.value },
    ).joinToString("|")

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
