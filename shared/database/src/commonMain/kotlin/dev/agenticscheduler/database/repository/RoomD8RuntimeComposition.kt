package dev.agenticscheduler.database.repository

import dev.agenticscheduler.application.history.MutationWallClock
import dev.agenticscheduler.application.history.SyncConflictWritePolicy
import dev.agenticscheduler.application.history.ConflictAwareSourceFactQuery
import dev.agenticscheduler.application.id.UuidV7Generator
import dev.agenticscheduler.application.sync.ActiveSyncRuntimeCatchUpResult
import dev.agenticscheduler.application.sync.ActiveSyncCatchUpTrigger
import dev.agenticscheduler.application.sync.ActiveSyncRuntimeConfiguration
import dev.agenticscheduler.application.sync.ActiveSyncRuntimeCreation
import dev.agenticscheduler.application.sync.ActiveSyncRuntimeDependencies
import dev.agenticscheduler.application.sync.ActiveSyncRuntimeFactory
import dev.agenticscheduler.application.sync.ActiveSyncRuntimeHost
import dev.agenticscheduler.application.sync.ActiveSyncRuntimeTransportFactory
import dev.agenticscheduler.application.sync.KtorActiveSyncRuntimeTransportFactory
import dev.agenticscheduler.application.sync.PairingHpke
import dev.agenticscheduler.application.sync.PlatformD8SecureStore
import dev.agenticscheduler.database.AgenticSchedulerDatabase
import kotlinx.coroutines.CoroutineScope

/**
 * Production Room composition for the D8 runtime. All lifecycle metadata and
 * source facts use the same database/transaction boundary; secrets stay behind
 * [PlatformD8SecureStore] and never enter Room.
 */
class RoomD8RuntimeComposition(
    private val database: AgenticSchedulerDatabase,
    secureStore: PlatformD8SecureStore,
    pairingHpke: PairingHpke,
    ids: UuidV7Generator,
    wallClock: MutationWallClock,
    transportFactory: ActiveSyncRuntimeTransportFactory = KtorActiveSyncRuntimeTransportFactory,
) {
    private val transactions = RoomApplicationTransactionRunner(database)
    private val journal = RoomMutationJournalRepository(database)
    private val receiveState = RoomSyncReceiveRepository(database)
    private val host = ActiveSyncRuntimeHost()
    private val factory = ActiveSyncRuntimeFactory(
        ActiveSyncRuntimeDependencies(
            transactions = transactions,
            journal = journal,
            history = journal,
            receiveState = receiveState,
            outbound = RoomSyncOutboundEnvelopeRepository(database),
            events = RoomEventRepository(database),
            tasks = RoomTaskRepository(database),
            profiles = RoomPlanningProfileRepository(database),
            academics = RoomAcademicRepository(database),
            ids = ids,
            wallClock = wallClock,
            enrollments = RoomLocalEnrollmentRepository(database),
            keyRing = RoomSyncKeyMetadataRepository(database),
            secureStore = secureStore,
            pairingHpke = pairingHpke,
        ),
        transportFactory,
    )

    /** Use these for every normal source-fact read/write in the app composition. */
    val sourceFacts: ConflictAwareSourceFactQuery get() = host.sourceFacts
    val writePolicy: SyncConflictWritePolicy get() = host.writePolicy

    suspend fun activate(configuration: ActiveSyncRuntimeConfiguration): ActiveSyncRuntimeCreation =
        host.activate(factory, configuration)

    suspend fun catchUp(fetchLimit: Int = 100): ActiveSyncRuntimeCatchUpResult? = host.catchUp(fetchLimit)

    /**
     * Observe durable local outbound mutations and serialize catch-up work for
     * the lifetime of the supplied application scope.
     */
    fun newCatchUpTrigger(
        scope: CoroutineScope,
        fetchLimit: Int = 100,
        onUnexpectedFailure: () -> Unit = {},
    ): ActiveSyncCatchUpTrigger = ActiveSyncCatchUpTrigger(
        scope = scope,
        committedOutboundMutationRevision = database.mutationJournalDao().observeOutboundMutationRevision(),
        catchUp = { catchUp(fetchLimit) },
        onUnexpectedFailure = onUnexpectedFailure,
    )

    suspend fun deactivate() = host.deactivate()
}
