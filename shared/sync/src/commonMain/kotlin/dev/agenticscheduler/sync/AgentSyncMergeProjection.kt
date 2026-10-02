package dev.agenticscheduler.sync

/** Semantic conflicts are derived from immutable Agent operations, never from timestamps. */
enum class AgentSemanticConflictKind { THREAD_TITLE, THREAD_DELETE_APPEND, CONCURRENT_TURN_FORK, DELETE_RESOLUTION }
enum class AgentSemanticConflictState { OPEN, RESOLVED, SUPERSEDED }

data class AgentSemanticConflict(
    val threadId: AgentThreadSyncId,
    val kind: AgentSemanticConflictKind,
    val participantOperationIds: List<MutationId>,
    val state: AgentSemanticConflictState,
    val resolutionOperationIds: List<MutationId> = emptyList(),
) {
    init {
        require(participantOperationIds == participantOperationIds.distinct().sortedBy { it.value })
        require(resolutionOperationIds == resolutionOperationIds.distinct().sortedBy { it.value })
    }

    /** Local-only key; the tuple, not a wire conflictId, is authoritative. */
    val localConflictKey: String = "${threadId.value}|${participantOperationIds.joinToString("|") { it.value }}"
}

data class AgentThreadTitleCandidate(val operationId: MutationId, val title: String?)

sealed interface AgentThreadTitleProjection {
    data object Unavailable : AgentThreadTitleProjection
    data class Resolved(val title: String?) : AgentThreadTitleProjection
    data class Conflict(val candidates: List<AgentThreadTitleCandidate>) : AgentThreadTitleProjection
}

data class AgentProjectedTurn(
    val manifestOperationId: MutationId,
    val manifest: TurnFinalized,
    val hlc: AgentHlcSnapshot,
    /** Exactly the manifest members, in declared order. */
    val members: List<AgentSyncOperation>,
)

data class AgentThreadHistoryProjection(
    val threadId: AgentThreadSyncId,
    val title: AgentThreadTitleProjection,
    val tombstoned: Boolean,
    val turns: List<AgentProjectedTurn>,
    val conflicts: List<AgentSemanticConflict>,
    val providerContinuationAllowed: Boolean,
)

sealed interface AgentDeleteResolutionValidation {
    data object Valid : AgentDeleteResolutionValidation
    data class Invalid(val reason: String) : AgentDeleteResolutionValidation
}

/**
 * Pure Agent-history merge rules shared by persistence and deterministic tests.
 * Inputs contain only durable handled facts and locally authored outbox facts.
 */
object AgentSyncMergeProjection {
    fun project(
        threadId: AgentThreadSyncId,
        operations: List<AgentSyncOperation>,
        activeTurnIds: Set<AgentTurnSyncId>,
        tombstoned: Boolean = false,
    ): AgentThreadHistoryProjection {
        val threadOperations = operations
            .filter { it.agentEvent.threadIdForMerge() == threadId }
            .distinctBy { it.operationId }
            .sortedBy { it.operationId.value }
        val byId = threadOperations.associateBy { it.operationId }
        val deletes = threadOperations.filter { it.agentEvent is ThreadDeleted }
        val isTombstoned = tombstoned || deletes.isNotEmpty()
        val titleCandidates = maximalTitleCandidates(threadOperations)
        val title = when {
            titleCandidates.isEmpty() -> AgentThreadTitleProjection.Unavailable
            titleCandidates.map { it.second }.distinct().size == 1 -> AgentThreadTitleProjection.Resolved(titleCandidates.first().second)
            else -> AgentThreadTitleProjection.Conflict(titleCandidates.map { AgentThreadTitleCandidate(it.first, it.second) })
        }

        val conflicts = buildList {
            val titleConflict = titleCandidates.takeIf { candidates -> candidates.map { it.second }.distinct().size > 1 }
            if (titleConflict != null) {
                add(conflict(threadId, AgentSemanticConflictKind.THREAD_TITLE, titleConflict.map { it.first }, AgentSemanticConflictState.OPEN))
            }

            val deleteComponents = deleteAppendComponents(threadId, threadOperations)
            deleteComponents.forEach { component ->
                val resolutions = validResolutionsFor(component, threadOperations)
                val maximal = resolutions.filter { candidate ->
                    resolutions.none { later -> later != candidate && AgentCausality.observes(later, candidate) }
                }.sortedBy { it.operationId.value }
                val sameDecision = maximal.map { it.agentEvent.deleteResolutionValue() }.distinct().size <= 1
                when {
                    maximal.isEmpty() -> add(conflict(threadId, AgentSemanticConflictKind.THREAD_DELETE_APPEND, component.map { it.operationId }, AgentSemanticConflictState.OPEN))
                    sameDecision -> add(conflict(threadId, AgentSemanticConflictKind.THREAD_DELETE_APPEND, component.map { it.operationId }, AgentSemanticConflictState.RESOLVED, maximal.map { it.operationId }))
                    else -> {
                        add(conflict(threadId, AgentSemanticConflictKind.THREAD_DELETE_APPEND, component.map { it.operationId }, AgentSemanticConflictState.SUPERSEDED, maximal.map { it.operationId }))
                        add(conflict(threadId, AgentSemanticConflictKind.DELETE_RESOLUTION, component.map { it.operationId } + maximal.map { it.operationId }, AgentSemanticConflictState.OPEN, maximal.map { it.operationId }))
                    }
                }
            }

            val activeTurnOperations = threadOperations.filter { operation ->
                val manifest = operation.agentEvent as? TurnFinalized
                manifest != null && manifest.turnId in activeTurnIds
            }
            val forks = concurrentSiblingTurnComponents(threadId, activeTurnOperations)
            forks.forEach { component ->
                add(conflict(threadId, AgentSemanticConflictKind.CONCURRENT_TURN_FORK, component.map { it.operationId }, AgentSemanticConflictState.OPEN))
            }
        }.distinctBy { it.localConflictKey to it.kind }.sortedWith(compareBy({ it.kind.name }, { it.localConflictKey }))

        val turnsById = threadOperations.filter { operation ->
            (operation.agentEvent as? TurnFinalized)?.turnId in activeTurnIds
        }.mapNotNull { manifestOperation ->
            val manifest = manifestOperation.agentEvent as? TurnFinalized ?: return@mapNotNull null
            val members = manifest.orderedMembers.mapNotNull { member ->
                val identity = member.recordIdentity()
                threadOperations.firstOrNull { it.agentEvent.immutableRecordIdentityForMerge() == identity }
            }
            if (members.size != manifest.orderedMembers.size) null else manifestOperation to AgentProjectedTurn(manifestOperation.operationId, manifest, manifestOperation.hlc, members)
        }.associate { it.second.manifest.turnId to it }
        val orderedTurns = topologicalPresentationOrder(turnsById.values.toList())
        val hasOpenConflict = conflicts.any { it.state == AgentSemanticConflictState.OPEN }
        return AgentThreadHistoryProjection(
            threadId = threadId,
            title = title,
            tombstoned = isTombstoned,
            turns = if (isTombstoned) emptyList() else orderedTurns,
            conflicts = conflicts,
            providerContinuationAllowed = !isTombstoned && !hasOpenConflict,
        )
    }

    /** Validates the frozen participant set and DVV context before a resolution is handled/authored. */
    fun validateDeleteResolution(
        resolutionOperation: AgentSyncOperation,
        priorOperations: List<AgentSyncOperation>,
    ): AgentDeleteResolutionValidation {
        val event = resolutionOperation.agentEvent as? ThreadDeleteConflictResolved
            ?: return AgentDeleteResolutionValidation.Invalid("Operation is not ThreadDeleteConflictResolved.")
        val threadOperations = priorOperations.filter { it.agentEvent.threadIdForMerge() == event.threadId && it.operationId != resolutionOperation.operationId }
        val components = deleteAppendComponents(event.threadId, threadOperations)
        val matches = components.filter { component ->
            val coreIds = component.map { it.operationId }.toSet()
            val knownResolutions = validResolutionsFor(component, threadOperations)
            val observedResolutionIds = knownResolutions.filter { AgentCausality.observes(resolutionOperation, it) }.map { it.operationId }.toSet()
            val expected = coreIds + observedResolutionIds
            event.participantOperationIds.toSet() == expected
        }
        if (matches.size != 1) {
            return AgentDeleteResolutionValidation.Invalid("Resolution participants must exactly name one current delete-conflict component.")
        }
        val participants = matches.single().map { it.operationId }.toSet() + event.participantOperationIds
        val participantOperations = threadOperations.filter { it.operationId in participants }
        if (participantOperations.map { it.operationId }.toSet() != participants) {
            return AgentDeleteResolutionValidation.Invalid("A resolution participant operation is missing from this SyncSpace.")
        }
        if (participantOperations.any { !AgentCausality.observes(resolutionOperation, it) }) {
            return AgentDeleteResolutionValidation.Invalid("Resolution DVV does not observe every participant dot.")
        }
        if (event.participantOperationIds.any { it == resolutionOperation.operationId }) {
            return AgentDeleteResolutionValidation.Invalid("A resolution cannot list itself as a conflict participant.")
        }
        return AgentDeleteResolutionValidation.Valid
    }

    private fun deleteAppendComponents(
        threadId: AgentThreadSyncId,
        operations: List<AgentSyncOperation>,
    ): List<List<AgentSyncOperation>> {
        val deletes = operations.filter { it.agentEvent is ThreadDeleted }
        val competing = operations.filter { it.agentEvent.isThreadContentForDeleteConflict() }
        val byId = (deletes + competing).distinctBy { it.operationId }.associateBy { it.operationId }
        val parent = byId.keys.associateWith { it }.toMutableMap()
        fun root(id: MutationId): MutationId {
            var current = id
            while (parent.getValue(current) != current) current = parent.getValue(current)
            var cursor = id
            while (parent.getValue(cursor) != current) {
                val next = parent.getValue(cursor)
                parent[cursor] = current
                cursor = next
            }
            return current
        }
        fun union(a: MutationId, b: MutationId) {
            val ar = root(a)
            val br = root(b)
            if (ar != br) {
                val (first, second) = if (ar.value <= br.value) ar to br else br to ar
                parent[second] = first
            }
        }
        for (delete in deletes) for (append in competing) {
            if (AgentCausality.concurrent(delete, append)) union(delete.operationId, append.operationId)
        }
        return byId.values.groupBy { root(it.operationId) }.values
            .filter { component -> component.any { it.agentEvent is ThreadDeleted } && component.any { it.agentEvent.isThreadContentForDeleteConflict() } }
            .map { it.sortedBy { operation -> operation.operationId.value } }
            .sortedBy { it.first().operationId.value }
    }

    private fun validResolutionsFor(component: List<AgentSyncOperation>, operations: List<AgentSyncOperation>): List<AgentSyncOperation> {
        val coreIds = component.map { it.operationId }.toSet()
        val threadId = (component.first().agentEvent.threadIdForMerge()) ?: return emptyList()
        return operations.filter { candidate ->
            val event = candidate.agentEvent as? ThreadDeleteConflictResolved ?: return@filter false
            if (event.threadId != threadId || !event.participantOperationIds.containsAll(coreIds)) return@filter false
            val extras = event.participantOperationIds.filterNot(coreIds::contains)
            val validExtras = extras.all { extraId ->
                operations.any { prior ->
                    prior.operationId == extraId && prior.agentEvent is ThreadDeleteConflictResolved &&
                        (prior.agentEvent as ThreadDeleteConflictResolved).threadId == threadId &&
                        (prior.agentEvent as ThreadDeleteConflictResolved).participantOperationIds.containsAll(coreIds)
                }
            }
            validExtras && AgentCausality.observesAll(candidate, event.participantOperationIds.mapNotNull { id -> operations.firstOrNull { it.operationId == id } })
        }
    }

    private fun concurrentSiblingTurnComponents(threadId: AgentThreadSyncId, manifests: List<AgentSyncOperation>): List<List<AgentSyncOperation>> {
        val turnOps = manifests.filter { it.agentEvent is TurnFinalized && it.agentEvent.threadId == threadId }
        val parent = turnOps.associate { it.operationId to it.operationId }.toMutableMap()
        fun root(id: MutationId): MutationId {
            var current = id
            while (parent.getValue(current) != current) current = parent.getValue(current)
            return current
        }
        for (leftIndex in turnOps.indices) for (rightIndex in leftIndex + 1 until turnOps.size) {
            val left = turnOps[leftIndex]
            val right = turnOps[rightIndex]
            val leftManifest = left.agentEvent as TurnFinalized
            val rightManifest = right.agentEvent as TurnFinalized
            if (leftManifest.parentTurnIds.intersect(rightManifest.parentTurnIds.toSet()).isNotEmpty() && AgentCausality.concurrent(left, right)) {
                val leftRoot = root(left.operationId)
                val rightRoot = root(right.operationId)
                if (leftRoot != rightRoot) parent[rightRoot] = leftRoot
            }
        }
        return turnOps.groupBy { root(it.operationId) }.values.filter { it.size > 1 }
            .map { it.sortedBy { operation -> operation.operationId.value } }
    }

    private fun maximalTitleCandidates(operations: List<AgentSyncOperation>): List<Pair<MutationId, String?>> {
        val titleOperations = operations.mapNotNull { operation ->
            when (val event = operation.agentEvent) {
                is ThreadCreated -> operation.operationId to event.title
                is ThreadTitleSet -> operation.operationId to event.title
                else -> null
            }
        }
        return titleOperations.filter { candidate ->
            val op = operations.first { it.operationId == candidate.first }
            titleOperations.none { later ->
                later.first != candidate.first && AgentCausality.observes(operations.first { it.operationId == later.first }, op)
            }
        }.sortedBy { it.first.value }
    }

    private fun topologicalPresentationOrder(turns: List<AgentProjectedTurn>): List<AgentProjectedTurn> {
        val byId = turns.associateBy { it.manifest.turnId }
        val remaining = byId.toMutableMap()
        val output = mutableListOf<AgentProjectedTurn>()
        while (remaining.isNotEmpty()) {
            val ready = remaining.values.filter { turn ->
                turn.manifest.parentTurnIds.none { parent -> parent in remaining.keys && parent != turn.manifest.turnId }
            }.sortedWith(compareBy<AgentProjectedTurn>({ it.hlc.physicalMillis }, { it.hlc.logical }, { it.hlc.replicaId.value }, { it.manifestOperationId.value }))
            val next = (ready.ifEmpty { remaining.values.toList().sortedWith(compareBy({ it.hlc.physicalMillis }, { it.hlc.logical }, { it.hlc.replicaId.value }, { it.manifestOperationId.value })) }).first()
            output += next
            remaining.remove(next.manifest.turnId)
        }
        return output
    }

    private fun conflict(
        threadId: AgentThreadSyncId,
        kind: AgentSemanticConflictKind,
        ids: List<MutationId>,
        state: AgentSemanticConflictState,
        resolutions: List<MutationId> = emptyList(),
    ) = AgentSemanticConflict(threadId, kind, ids.distinct().sortedBy { it.value }, state, resolutions.distinct().sortedBy { it.value })
}

/** DVV relation. HLC is intentionally absent from semantic conflict decisions. */
object AgentCausality {
    fun observes(observer: AgentSyncOperation, observed: AgentSyncOperation): Boolean {
        if (observer.operationId == observed.operationId) return false
        return if (observer.agentDvv.dot.replicaId == observed.agentDvv.dot.replicaId &&
            observer.agentDvv.dot.counter > observed.agentDvv.dot.counter) {
            true
        } else {
            observer.agentDvv.context.singleOrNull { it.replicaId == observed.agentDvv.dot.replicaId }
                ?.counter?.let { it >= observed.agentDvv.dot.counter } == true
        }
    }

    fun concurrent(left: AgentSyncOperation, right: AgentSyncOperation): Boolean =
        !observes(left, right) && !observes(right, left)

    fun observesAll(observer: AgentSyncOperation, participants: List<AgentSyncOperation>): Boolean =
        participants.all { observes(observer, it) }
}

private fun AgentSyncEvent.threadIdForMerge(): AgentThreadSyncId? = when (this) {
    is ThreadCreated -> threadId
    is ThreadTitleSet -> threadId
    is MessageAppended -> threadId
    is ToolCallFinalized -> threadId
    is ToolResultAppended -> threadId
    is ActionFinalized -> threadId
    is TurnFinalized -> threadId
    is ThreadDeleted -> threadId
    is ThreadDeleteConflictResolved -> threadId
}

private fun AgentSyncEvent.isThreadContentForDeleteConflict(): Boolean = when (this) {
    is ThreadCreated, is ThreadTitleSet, is MessageAppended, is ToolCallFinalized, is ToolResultAppended, is TurnFinalized -> true
    is ActionFinalized, is ThreadDeleted, is ThreadDeleteConflictResolved -> false
}

private fun AgentSyncEvent.deleteResolutionValue(): Pair<AgentThreadDeleteResolution, AgentThreadSyncId?>? =
    (this as? ThreadDeleteConflictResolved)?.let { it.resolution to it.replacementThreadId }

private fun AgentSyncEvent.immutableRecordIdentityForMerge(): Pair<String, String>? = when (this) {
    is ThreadCreated -> "THREAD" to threadId.value
    is MessageAppended -> "MESSAGE" to messageId.value
    is ToolCallFinalized -> "TOOL_CALL" to callId.value
    is ToolResultAppended -> "TOOL_RESULT" to resultId.value
    is ActionFinalized -> "ACTION" to actionId.value
    is TurnFinalized -> "TURN" to turnId.value
    is ThreadTitleSet, is ThreadDeleted, is ThreadDeleteConflictResolved -> null
}

private fun TurnMemberReference.recordIdentity(): Pair<String, String> = when (this) {
    is MessageMember -> "MESSAGE" to id.value
    is ToolCallMember -> "TOOL_CALL" to id.value
    is ToolResultMember -> "TOOL_RESULT" to id.value
    is ActionMember -> "ACTION" to id.value
}
