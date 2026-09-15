package dev.agenticscheduler.application.history

import dev.agenticscheduler.application.persistence.SyncReceiveRepository
import dev.agenticscheduler.sync.*

/** D8-P04's structured normal-write rejection. Resolution deliberately does not use this guard. */
data class SyncConflictWriteBlock(
    val conflictId: String,
    val entityKind: EntityKind,
    val entityId: String,
    val blockedGroups: List<String>,
)

/** D8-P04 boundary used by every normal source-fact command. */
fun interface SyncConflictWritePolicy {
    suspend fun blocks(proposed: Collection<EntityMutation>): List<SyncConflictWriteBlock>
}

class SyncConflictWriteGuard(
    private val receiveState: SyncReceiveRepository,
    private val syncSpaceId: SyncSpaceId,
) : SyncConflictWritePolicy {
    override suspend fun blocks(proposed: Collection<EntityMutation>): List<SyncConflictWriteBlock> =
        receiveState.conflicts(syncSpaceId)
            .asSequence()
            .filter { it.status == SyncConflictStatus.OPEN }
            .flatMap { conflict -> proposed.asSequence().flatMap { mutation ->
                val changed = mutation.changedSemanticGroups().map(SemanticGroupValue::name).toSet()
                conflict.entityRefs.asSequence()
                    .filter { it.entityKind == mutation.entityKind && it.entityId == mutation.entityId }
                    .map { ref -> SyncConflictWriteBlock(conflict.conflictId, ref.entityKind, ref.entityId, ref.groups.filter(changed::contains).sorted()) }
                    .filter { it.blockedGroups.isNotEmpty() }
            } }
            .sortedWith(compareBy(SyncConflictWriteBlock::conflictId, SyncConflictWriteBlock::entityKind, SyncConflictWriteBlock::entityId))
            .toList()
}

/**
 * A local installation that has not enrolled a Personal SyncSpace cannot have
 * an OPEN remote conflict. D8-02 replaces this policy at enrollment time; it
 * exists so every normal command still crosses the same application seam.
 */
data object NoActiveSyncSpaceWritePolicy : SyncConflictWritePolicy {
    override suspend fun blocks(proposed: Collection<EntityMutation>): List<SyncConflictWriteBlock> = emptyList()
}

sealed interface ConflictProjection {
    /** [mutation] is null only for a projected FocusBlock delete. No durable state is changed. */
    data class Projected(val mutation: EntityMutation?, val openConflictIds: List<String>) : ConflictProjection
    data class Unprojectable(val conflictIds: List<String>, val reason: String) : ConflictProjection
}

/**
 * D8-P01/P02's read-only semantic overlay. Callers supply the durable image they
 * already read; this service never touches repositories, journals, or causal state.
 */
class ConflictAwareProjection(private val receiveState: SyncReceiveRepository) {
    suspend fun project(
        syncSpaceId: SyncSpaceId,
        entityKind: EntityKind,
        entityId: String,
        durable: EntityMutation?,
    ): ConflictProjection {
        var projected = durable
        val conflicts = receiveState.conflicts(syncSpaceId)
            .filter { it.status == SyncConflictStatus.OPEN && it.entityRefs.any { ref -> ref.entityKind == entityKind && ref.entityId == entityId } }
            .sortedBy(SyncConflict::conflictId)
        for (conflict in conflicts) {
            val provisional = conflict.participants.firstOrNull { it.mutationId == conflict.provisionalMutationId }
                ?: return ConflictProjection.Unprojectable(listOf(conflict.conflictId), "Provisional participant is unavailable.")
            val operation = runCatching { LocalJournalCodec.decode(provisional.candidateValuesJson) }.getOrNull()
                ?: return ConflictProjection.Unprojectable(listOf(conflict.conflictId), "Provisional candidate cannot be decoded.")
            val candidate = operation.orderedMutations.singleOrNull { it.entityKind == entityKind && it.entityId == entityId }
                ?: return ConflictProjection.Unprojectable(listOf(conflict.conflictId), "Provisional candidate does not identify the conflicted entity exactly.")
            val groups = conflict.entityRefs.first { it.entityKind == entityKind && it.entityId == entityId }.groups.toSet()
            when (val overlay = overlayConflictedGroups(projected, candidate, groups)) {
                is OverlayResult.Value -> projected = overlay.mutation
                OverlayResult.Invalid -> return ConflictProjection.Unprojectable(
                    listOf(conflict.conflictId),
                    "Provisional candidate cannot overlay the conflicted semantic groups.",
                )
            }
        }
        return ConflictProjection.Projected(projected, conflicts.map(SyncConflict::conflictId))
    }
}

/** The single D8-P03 source-fact projection boundary for application readers. */
fun interface ConflictAwareSourceFactQuery {
    suspend fun project(durable: EntityMutation): ConflictProjection
}

class ActiveConflictAwareSourceFactQuery(
    private val projection: ConflictAwareProjection,
    private val syncSpaceId: SyncSpaceId,
) : ConflictAwareSourceFactQuery {
    override suspend fun project(durable: EntityMutation): ConflictProjection =
        projection.project(syncSpaceId, durable.entityKind, durable.entityId, durable)
}

/** Before D8-02 enrollment there is no remote conflict state to overlay. */
data object NoActiveSyncSpaceSourceFactQuery : ConflictAwareSourceFactQuery {
    override suspend fun project(durable: EntityMutation): ConflictProjection =
        ConflictProjection.Projected(durable, emptyList())
}

/** Applies only [groups], never a candidate's unrelated historical fields. */
private sealed interface OverlayResult {
    /** A null mutation is the explicit, valid projection of a FocusBlock delete. */
    data class Value(val mutation: EntityMutation?) : OverlayResult
    data object Invalid : OverlayResult
}

private fun overlayConflictedGroups(current: EntityMutation?, candidate: EntityMutation, groups: Set<String>): OverlayResult {
    // Absence is not enough information to synthesize a complete typed entity
    // image. The sole exception is an explicit delete, which projects absence.
    if (current == null) return when {
        candidate is FocusBlockDelete && groups == setOf("existence") -> OverlayResult.Value(null)
        // A FocusBlockPut carries a complete typed image. For the explicit
        // put-vs-delete existence conflict, an absent replica can therefore
        // render the same provisional Put without materializing it.
        candidate is FocusBlockPut && groups == setOf("existence") -> OverlayResult.Value(candidate)
        else -> OverlayResult.Invalid
    }
    if (current.entityKind != candidate.entityKind || current.entityId != candidate.entityId) return OverlayResult.Invalid
    return when {
        current is EventPut && candidate is EventPut -> OverlayResult.Value(current.copy(after = current.after.copy(
            title = if ("title" in groups) candidate.after.title else current.after.title,
            time = if ("time" in groups) candidate.after.time else current.after.time,
            flexibility = if ("flexibility" in groups) candidate.after.flexibility else current.after.flexibility,
            pinState = if ("pinState" in groups) candidate.after.pinState else current.after.pinState,
        )))
        current is TaskPut && candidate is TaskPut -> OverlayResult.Value(current.copy(after = current.after.copy(
            title = if ("title" in groups) candidate.after.title else current.after.title,
            status = if ("status" in groups) candidate.after.status else current.after.status,
            priority = if ("priority" in groups) candidate.after.priority else current.after.priority,
            effort = if ("effort" in groups) candidate.after.effort else current.after.effort,
            deadline = if ("deadline" in groups) candidate.after.deadline else current.after.deadline,
        )))
        current is PlanningProfilePut && candidate is PlanningProfilePut -> OverlayResult.Value(current.copy(after = overlayProfile(current.after, candidate.after, groups)))
        current is FocusBlockPut && candidate is FocusBlockDelete -> if (groups == setOf("existence")) OverlayResult.Value(null) else OverlayResult.Invalid
        current is FocusBlockPut && candidate is FocusBlockPut -> OverlayResult.Value(current.copy(after = current.after.copy(
            taskId = if ("taskId" in groups) candidate.after.taskId else current.after.taskId,
            time = if ("time" in groups) candidate.after.time else current.after.time,
            flexibility = if ("authority" in groups) candidate.after.flexibility else current.after.flexibility,
            pinState = if ("authority" in groups) candidate.after.pinState else current.after.pinState,
        )))
        current is ExamPut && candidate is ExamPut -> OverlayResult.Value(current.copy(after = current.after.copy(
            semesterId = if ("reference" in groups) candidate.after.semesterId else current.after.semesterId,
            courseId = if ("reference" in groups) candidate.after.courseId else current.after.courseId,
            title = if ("title" in groups) candidate.after.title else current.after.title,
            schedule = if ("schedule" in groups) candidate.after.schedule else current.after.schedule,
        )))
        groups == setOf("whole") || groups == setOf("append") -> OverlayResult.Value(candidate)
        else -> OverlayResult.Invalid
    }
}

private fun overlayProfile(current: PlanningProfileImage, candidate: PlanningProfileImage, groups: Set<String>): PlanningProfileImage {
    if ("configuration.mode" in groups) return current.copy(
        name = if ("name" in groups) candidate.name else current.name,
        configuration = candidate.configuration,
    )
    val currentConfigured = current.configuration as? PlanningProfileConfigurationImage.Configured ?: return current
    val candidateConfigured = candidate.configuration as? PlanningProfileConfigurationImage.Configured ?: return current
    val currentByDay = currentConfigured.weeklyAvailability.groupBy { it.dayOfWeek }
    val candidateByDay = candidateConfigured.weeklyAvailability.groupBy { it.dayOfWeek }
    val availability = (currentByDay.keys + candidateByDay.keys).sortedBy { it.ordinal }.flatMap { day ->
        if ("configuration.weeklyAvailability.$day" in groups) candidateByDay[day].orEmpty() else currentByDay[day].orEmpty()
    }
    return current.copy(
        name = if ("name" in groups) candidate.name else current.name,
        configuration = currentConfigured.copy(
            timeZone = if ("configuration.timeZone" in groups) candidateConfigured.timeZone else currentConfigured.timeZone,
            weeklyAvailability = availability,
            minimumFocusDuration = if ("configuration.focusDurations" in groups) candidateConfigured.minimumFocusDuration else currentConfigured.minimumFocusDuration,
            preferredFocusDuration = if ("configuration.focusDurations" in groups) candidateConfigured.preferredFocusDuration else currentConfigured.preferredFocusDuration,
            maximumFocusDuration = if ("configuration.focusDurations" in groups) candidateConfigured.maximumFocusDuration else currentConfigured.maximumFocusDuration,
            allDayEventPolicy = if ("configuration.allDayEventPolicy" in groups) candidateConfigured.allDayEventPolicy else currentConfigured.allDayEventPolicy,
        ),
    )
}
