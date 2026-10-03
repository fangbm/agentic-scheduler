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
    private val explicitExport: AgentHistoryExplicitExport? = null,
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

    /** Read-only eligibility is safe to refresh; consent changes never call export. */
    suspend fun historyExportAvailability(space: SyncSpaceId): AgentHistoryExportAvailability? = explicitExport?.availability(space)

    /** Dedicated user-triggered action; intentionally never called by setFromUser or startup. */
    suspend fun exportHistoryFromUser(space: SyncSpaceId): AgentHistoryExportResult =
        checkNotNull(explicitExport) { "Historical export is unavailable in this composition." }.exportFromUser(space)
}
