package dev.agenticscheduler.application.planner

import dev.agenticscheduler.application.id.UuidV7Generator
import dev.agenticscheduler.application.persistence.ApplicationTransactionRunner
import dev.agenticscheduler.application.persistence.TaskRepository
import dev.agenticscheduler.domain.id.FocusBlockId
import dev.agenticscheduler.domain.id.PlanBranchId
import dev.agenticscheduler.domain.planning.Flexibility
import dev.agenticscheduler.domain.planning.PinState
import dev.agenticscheduler.domain.task.FocusBlock
import dev.agenticscheduler.planner.FocusBlockMutation
import dev.agenticscheduler.planner.LocalReflowRequest
import dev.agenticscheduler.planner.PlannerIssue
import dev.agenticscheduler.planner.PlannerResult
import dev.agenticscheduler.planner.PlanningSnapshot
import kotlin.time.Instant
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.toImmutableList

enum class PlanBranchStatus { DRAFT, STALE, REBASEABLE, CONFLICTED, APPLIED, DISCARDED }

sealed interface PlanningRequest {
    data object FullReplan : PlanningRequest
    data class LocalReflow(val request: LocalReflowRequest) : PlanningRequest
}

/** Session-local preview; it is intentionally not a Room entity or persistence port. */
data class PlanBranch(
    val id: PlanBranchId,
    val originalRequest: PlanningRequest,
    /** Normalized facts read by the planner. referenceNow is not a structural source fact. */
    val baseFacts: PlanningSnapshot,
    val mutations: ImmutableList<FocusBlockMutation>,
    val explanations: ImmutableList<PlannerIssue>,
    val status: PlanBranchStatus = PlanBranchStatus.DRAFT,
) {
    fun discard(): PlanBranch = copy(status = PlanBranchStatus.DISCARDED)
    fun markRebaseable(): PlanBranch = copy(status = PlanBranchStatus.REBASEABLE)
}

class PlanBranchFactory(private val uuidV7: UuidV7Generator) {
    fun fromApplicableResult(
        request: PlanningRequest,
        snapshot: PlanningSnapshot,
        result: PlannerResult.Success,
    ): PlanBranch = PlanBranch(
        id = PlanBranchId(uuidV7.next()),
        originalRequest = request,
        baseFacts = snapshot,
        mutations = result.mutations,
        explanations = result.issues,
    )
}

sealed interface PlannerPreview {
    data class Applicable(val branch: PlanBranch) : PlannerPreview
    data class Infeasible(val issues: ImmutableList<PlannerIssue>) : PlannerPreview
    data class InvalidInput(val issues: ImmutableList<PlannerIssue>) : PlannerPreview
}

/** Application-owned orchestration for preview and deterministic rebase; neither call writes Active State. */
class PlannerPreviewService(
    private val planner: dev.agenticscheduler.planner.DeterministicPlanner,
    private val branches: PlanBranchFactory,
) {
    fun preview(request: PlanningRequest, snapshot: PlanningSnapshot): PlannerPreview {
        val result = when (request) {
            PlanningRequest.FullReplan -> planner.fullReplan(snapshot)
            is PlanningRequest.LocalReflow -> planner.localReflow(snapshot, request.request)
        }
        return when (result) {
            is PlannerResult.Success -> PlannerPreview.Applicable(branches.fromApplicableResult(request, snapshot, result))
            is PlannerResult.Infeasible -> PlannerPreview.Infeasible(result.issues)
            is PlannerResult.InvalidInput -> PlannerPreview.InvalidInput(result.issues)
        }
    }

    /** Rebase creates a new branch from current facts and explicit current referenceNow; it never patches old mutations. */
    fun rebase(branch: PlanBranch, currentSnapshot: PlanningSnapshot): PlannerPreview {
        require(branch.status == PlanBranchStatus.STALE || branch.status == PlanBranchStatus.REBASEABLE) { "Only stale/rebaseable PlanBranches can be rebased." }
        return preview(branch.originalRequest, currentSnapshot)
    }
}

sealed interface PlanBranchApplyResult {
    data class Applied(val branch: PlanBranch) : PlanBranchApplyResult
    data class Stale(val branch: PlanBranch) : PlanBranchApplyResult
}

/**
 * The snapshot supplier is application orchestration: it re-reads every fact the branch used.
 * This makes staleness structural instead of relying on a broad database revision counter.
 */
class PlanBranchApplier(
    private val transactions: ApplicationTransactionRunner,
    private val tasks: TaskRepository,
    private val uuidV7: UuidV7Generator,
    private val currentSnapshot: suspend () -> PlanningSnapshot,
) {
    suspend fun apply(branch: PlanBranch, applyNow: Instant): PlanBranchApplyResult = transactions.inWriteTransaction {
        if (branch.status != PlanBranchStatus.DRAFT || currentSnapshot().withoutReferenceNow() != branch.baseFacts.withoutReferenceNow()) {
            return@inWriteTransaction PlanBranchApplyResult.Stale(branch.copy(status = PlanBranchStatus.STALE))
        }
        val targetIds = branch.mutations.mapNotNull { mutation -> when (mutation) {
            is FocusBlockMutation.Create -> null
            is FocusBlockMutation.Move -> mutation.id
            is FocusBlockMutation.Resize -> mutation.id
            is FocusBlockMutation.Delete -> mutation.id
        } }
        if (targetIds.distinct().size != targetIds.size || branch.mutations.any { mutation ->
            when (mutation) {
                is FocusBlockMutation.Create -> mutation.draft.time.start <= applyNow
                is FocusBlockMutation.Move -> mutation.time.start <= applyNow
                is FocusBlockMutation.Resize -> mutation.time.start <= applyNow
                is FocusBlockMutation.Delete -> false
            }
        }) return@inWriteTransaction PlanBranchApplyResult.Stale(branch.copy(status = PlanBranchStatus.STALE))
        val existing = targetIds.associateWith { tasks.getFocusBlock(it) }
        if (existing.values.any { it == null }) return@inWriteTransaction PlanBranchApplyResult.Stale(branch.copy(status = PlanBranchStatus.STALE))
        branch.mutations.forEach { mutation -> when (mutation) {
            is FocusBlockMutation.Create -> tasks.upsertFocusBlock(FocusBlock(
                id = FocusBlockId(uuidV7.next()), taskId = mutation.draft.taskId, time = mutation.draft.time,
                flexibility = Flexibility.SOFT, pinState = PinState.UNPINNED,
            ))
            is FocusBlockMutation.Move -> tasks.upsertFocusBlock(requireNotNull(existing[mutation.id]).copy(time = mutation.time))
            is FocusBlockMutation.Resize -> tasks.upsertFocusBlock(requireNotNull(existing[mutation.id]).copy(time = mutation.time))
            is FocusBlockMutation.Delete -> tasks.deleteFocusBlock(mutation.id)
        } }
        PlanBranchApplyResult.Applied(branch.copy(status = PlanBranchStatus.APPLIED))
    }
}

private fun PlanningSnapshot.withoutReferenceNow() = copy(referenceNow = Instant.DISTANT_PAST)
