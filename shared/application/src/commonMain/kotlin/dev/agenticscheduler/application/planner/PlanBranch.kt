package dev.agenticscheduler.application.planner

import dev.agenticscheduler.application.id.UuidV7Generator
import dev.agenticscheduler.application.history.MutationCoordinator
import dev.agenticscheduler.application.history.MutationScope
import dev.agenticscheduler.application.history.toSemanticImage
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
import dev.agenticscheduler.planner.PlannerExplanation
import dev.agenticscheduler.planner.PlannerResult
import dev.agenticscheduler.planner.PlanningSnapshot
import dev.agenticscheduler.sync.FocusBlockDelete
import dev.agenticscheduler.sync.FocusBlockPut
import dev.agenticscheduler.sync.MutationOrigin
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
    /** Non-fatal planner diagnostics are separate from placement decision evidence. */
    val issues: ImmutableList<PlannerIssue>,
    val explanations: ImmutableList<PlannerExplanation>,
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
        issues = result.issues,
        explanations = result.explanations,
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
    private val mutations: MutationCoordinator? = null,
    /** A null snapshot means the current facts cannot be resolved safely, so Apply is stale. */
    private val currentSnapshot: suspend () -> PlanningSnapshot?,
) {
    suspend fun apply(branch: PlanBranch, applyNow: Instant): PlanBranchApplyResult {
        if (mutations != null) {
            return mutations.executeIfAny(MutationOrigin.Planner) { applyWithin(branch, applyNow, this) }?.value
                ?: PlanBranchApplyResult.Stale(branch.copy(status = PlanBranchStatus.STALE))
        }
        return transactions.inWriteTransaction { applyWithin(branch, applyNow, null) }
    }

    private suspend fun applyWithin(branch: PlanBranch, applyNow: Instant, scope: MutationScope?): PlanBranchApplyResult {
        if (branch.status != PlanBranchStatus.DRAFT || currentSnapshot()?.withoutReferenceNow() != branch.baseFacts.withoutReferenceNow()) {
            return PlanBranchApplyResult.Stale(branch.copy(status = PlanBranchStatus.STALE))
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
        }) return PlanBranchApplyResult.Stale(branch.copy(status = PlanBranchStatus.STALE))
        val existing = targetIds.associateWith { tasks.getFocusBlock(it) }
        if (existing.values.any { it == null }) return PlanBranchApplyResult.Stale(branch.copy(status = PlanBranchStatus.STALE))
        // A PlanBranch only authorizes changes to blocks that are still future at
        // the instant of Apply. Checking only the proposed destination would let a
        // started block be deleted or moved away after the preview was created.
        if (existing.values.filterNotNull().any { it.time.start <= applyNow }) {
            return PlanBranchApplyResult.Stale(branch.copy(status = PlanBranchStatus.STALE))
        }
        branch.mutations.forEach { mutation -> when (mutation) {
            is FocusBlockMutation.Create -> {
                val created = FocusBlock(
                id = FocusBlockId(uuidV7.next()), taskId = mutation.draft.taskId, time = mutation.draft.time,
                flexibility = Flexibility.SOFT, pinState = PinState.UNPINNED,
                )
                tasks.upsertFocusBlock(created)
                scope?.record(FocusBlockPut(null, created.toSemanticImage()))
            }
            is FocusBlockMutation.Move -> {
                val before = requireNotNull(existing[mutation.id]); val after = before.copy(time = mutation.time)
                tasks.upsertFocusBlock(after); scope?.record(FocusBlockPut(before.toSemanticImage(), after.toSemanticImage()))
            }
            is FocusBlockMutation.Resize -> {
                val before = requireNotNull(existing[mutation.id]); val after = before.copy(time = mutation.time)
                tasks.upsertFocusBlock(after); scope?.record(FocusBlockPut(before.toSemanticImage(), after.toSemanticImage()))
            }
            is FocusBlockMutation.Delete -> {
                val before = requireNotNull(existing[mutation.id]); tasks.deleteFocusBlock(mutation.id); scope?.record(FocusBlockDelete(before.toSemanticImage()))
            }
        } }
        return PlanBranchApplyResult.Applied(branch.copy(status = PlanBranchStatus.APPLIED))
    }
}

private fun PlanningSnapshot.withoutReferenceNow() = copy(referenceNow = Instant.DISTANT_PAST)
