package dev.agenticscheduler.planner

import dev.agenticscheduler.domain.planning.PlanningProfileConfiguration
import kotlinx.collections.immutable.toImmutableList

/**
 * Deterministic pure implementation of D6 Full Replan and Local Reflow.
 *
 * The public contract (PLN-005 input snapshot, PLN-017 planner results,
 * PLN-020 structured failures) is unchanged; the internals are the D6-R1
 * reservation + task-local delta architecture from
 * docs/PLANNER_REWRITE_DECISIONS.md.
 */
class DeterministicPlanner {

    fun fullReplan(snapshot: PlanningSnapshot): PlannerResult {
        val config = snapshot.profile.configuration as? PlanningProfileConfiguration.Configured
            ?: return PlannerResult.InvalidInput(listOf(PlannerIssue.ProfileUnconfigured).toImmutableList())
        return FullReplanEngine(snapshot, config, ProposalSequence()).run()
    }

    fun localReflow(snapshot: PlanningSnapshot, request: LocalReflowRequest): PlannerResult {
        val config = snapshot.profile.configuration as? PlanningProfileConfiguration.Configured
            ?: return PlannerResult.InvalidInput(listOf(PlannerIssue.ProfileUnconfigured).toImmutableList())
        return LocalReflowEngine(snapshot, config, request).run()
    }
}
