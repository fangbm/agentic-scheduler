package dev.agenticscheduler.application.sync

import dev.agenticscheduler.sync.SyncSpaceId

data class AgentConversationSyncSetting(
    val syncSpaceId: SyncSpaceId,
    val consent: Boolean,
    val recoveryState: AgentSyncBackfillRecoveryState,
)

/** UI-only capability. This service is not registered as an Agent Tool or background dependency. */
class AgentConversationSyncSettings(
    private val enrollments: LocalEnrollmentRepository,
    private val consent: AgentSyncUserConsentPersistence,
    private val transportState: AgentSyncTransportPersistence,
    private val history: AgentSyncPersistence,
) {
    suspend fun settings(): List<AgentConversationSyncSetting> = enrollments.states()
        .filterIsInstance<LocalEnrollmentState.Active>().map { it.syncSpaceId }.distinct()
        .sortedBy { it.value }.map { space ->
            AgentConversationSyncSetting(space, transportState.conversationConsent(space),
                history.backfillState(space)?.recoveryState ?: AgentSyncBackfillRecoveryState.IDLE)
        }

    /** Must be invoked from an explicit user control; never from startup or consent observation. */
    suspend fun setFromUser(space: SyncSpaceId, enabled: Boolean, allActiveDevicesV3Acknowledged: Boolean) {
        check(enrollments.states().filterIsInstance<LocalEnrollmentState.Active>().any { it.syncSpaceId == space }) {
            "NO_ACTIVE_ENROLLMENT"
        }
        require(!enabled || allActiveDevicesV3Acknowledged) { "ACTIVE_DEVICES_V3_ACKNOWLEDGEMENT_REQUIRED" }
        consent.setConversationConsent(space, enabled, allActiveDevicesV3Acknowledged)
    }
}
