package dev.agenticscheduler.agent.permission

import dev.agenticscheduler.agent.history.AgentStateRepository
import dev.agenticscheduler.application.history.AgentOriginWriteGate
import dev.agenticscheduler.application.sync.LocalEnrollmentRepository
import dev.agenticscheduler.application.sync.LocalEnrollmentState
import dev.agenticscheduler.application.sync.AgentOutboundCompatibilityGate
import dev.agenticscheduler.sync.AccountId
import dev.agenticscheduler.sync.SyncSpaceId

/** Trusted host wiring only; no Agent Tool can change the underlying device-local acknowledgement. */
class AgentSyncWriteGate(
    private val accountId: AccountId,
    private val enrollments: LocalEnrollmentRepository,
    private val agentState: AgentStateRepository,
) : AgentOriginWriteGate, AgentOutboundCompatibilityGate {
    override suspend fun mayCommit(): Boolean = when (val state = enrollments.state(accountId)) {
        is LocalEnrollmentState.Active -> agentState.syncAgentOriginEnabled(state.syncSpaceId)
        null, is LocalEnrollmentState.Pending -> true
    }

    override suspend fun enabled(syncSpaceId: SyncSpaceId): Boolean =
        (enrollments.state(accountId) as? LocalEnrollmentState.Active)?.syncSpaceId == syncSpaceId &&
            agentState.syncAgentOriginEnabled(syncSpaceId)
}
