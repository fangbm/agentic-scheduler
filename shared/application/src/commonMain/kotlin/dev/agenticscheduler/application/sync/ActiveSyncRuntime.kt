package dev.agenticscheduler.application.sync

import dev.agenticscheduler.application.history.ActiveConflictAwareSourceFactQuery
import dev.agenticscheduler.application.history.ConflictAwareProjection
import dev.agenticscheduler.application.history.ConflictAwareSourceFactQuery
import dev.agenticscheduler.application.history.NoActiveSyncSpaceSourceFactQuery
import dev.agenticscheduler.application.history.NoActiveSyncSpaceWritePolicy
import dev.agenticscheduler.application.history.SyncConflictWriteGuard
import dev.agenticscheduler.application.history.SyncConflictWritePolicy
import dev.agenticscheduler.application.history.SyncEngine
import dev.agenticscheduler.application.history.MutationWallClock
import dev.agenticscheduler.application.id.UuidV7Generator
import dev.agenticscheduler.application.persistence.AcademicRepository
import dev.agenticscheduler.application.persistence.ApplicationTransactionRunner
import dev.agenticscheduler.application.persistence.EventRepository
import dev.agenticscheduler.application.persistence.HistoryRepository
import dev.agenticscheduler.application.persistence.MutationJournalRepository
import dev.agenticscheduler.application.persistence.PlanningProfileRepository
import dev.agenticscheduler.application.persistence.SyncOutboundEnvelopeRepository
import dev.agenticscheduler.application.persistence.SyncReceiveRepository
import dev.agenticscheduler.application.persistence.TaskRepository
import dev.agenticscheduler.sync.AccountId
import dev.agenticscheduler.sync.EntityKind
import dev.agenticscheduler.sync.EntityMutation
import io.ktor.client.HttpClient
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Explicit, deployment-owned D8 endpoint selection. There is deliberately no
 * default endpoint: an unconfigured installation remains local-only.
 */
data class ActiveSyncRuntimeConfiguration(
    val accountId: AccountId,
    val serverBaseUrl: String,
) {
    init {
        require(serverBaseUrl.trim().startsWith("https://")) { "D8 sync runtime requires an explicit HTTPS serverBaseUrl." }
    }
}

/** Application dependencies required to assemble the already-frozen D8 runtime. */
data class ActiveSyncRuntimeDependencies(
    val transactions: ApplicationTransactionRunner,
    val journal: MutationJournalRepository,
    val history: HistoryRepository,
    val receiveState: SyncReceiveRepository,
    val outbound: SyncOutboundEnvelopeRepository,
    val events: EventRepository,
    val tasks: TaskRepository,
    val profiles: PlanningProfileRepository,
    val academics: AcademicRepository,
    val ids: UuidV7Generator,
    val wallClock: MutationWallClock,
    val enrollments: LocalEnrollmentRepository,
    val keyRing: SyncKeyRingRepository,
    val secureStore: PlatformD8SecureStore,
    val pairingHpke: PairingHpke,
)

sealed interface ActiveSyncRuntimeCreation {
    data class Active(val runtime: ActiveSyncRuntime) : ActiveSyncRuntimeCreation
    data object NoEnrollment : ActiveSyncRuntimeCreation
    data object EnrollmentNotActive : ActiveSyncRuntimeCreation
    data object MissingDeviceCredential : ActiveSyncRuntimeCreation
}

sealed interface ActiveSyncRuntimeCatchUpResult {
    data class Completed(
        val rotations: List<RotationPackageOutcome>,
        val transport: SyncTransportRunResult,
    ) : ActiveSyncRuntimeCatchUpResult

    data class RotationFailed(val result: RotationPackageCatchUpResult) : ActiveSyncRuntimeCatchUpResult
}

/**
 * Production D8 composition for one already ACTIVE local enrollment.
 *
 * Catch-up order is frozen intentionally: recipient rotation packages are
 * processed before ordinary encrypted envelopes, so a device that missed an
 * epoch advance obtains its new decrypt key before it reads future ciphertext.
 */
class ActiveSyncRuntime internal constructor(
    private val closeTransport: () -> Unit,
    private val syncSpaceId: dev.agenticscheduler.sync.SyncSpaceId,
    private val rotations: RotationPackageCatchUpService,
    private val worker: SyncTransportWorker,
    val sourceFacts: ConflictAwareSourceFactQuery,
    val writePolicy: SyncConflictWritePolicy,
) {
    suspend fun catchUp(fetchLimit: Int = 100): ActiveSyncRuntimeCatchUpResult {
        val rotationResult = rotations.catchUp()
        if (rotationResult !is RotationPackageCatchUpResult.Completed) {
            return ActiveSyncRuntimeCatchUpResult.RotationFailed(rotationResult)
        }
        return ActiveSyncRuntimeCatchUpResult.Completed(rotationResult.outcomes, worker.run(syncSpaceId, fetchLimit))
    }

    fun close() {
        closeTransport()
    }
}

/** Runtime-owned opaque transport clients. Tests may supply a deterministic transport pair. */
data class ActiveSyncRuntimeTransports(
    val sync: SyncTransport,
    val rotations: RotationPackageTransport,
    val close: () -> Unit,
)

fun interface ActiveSyncRuntimeTransportFactory {
    fun create(
        serverBaseUrl: String,
        deviceCredential: suspend () -> DeviceCredential?,
    ): ActiveSyncRuntimeTransports
}

/** The production transport factory shares one platform Ktor client across lifecycle and sync calls. */
object KtorActiveSyncRuntimeTransportFactory : ActiveSyncRuntimeTransportFactory {
    override fun create(
        serverBaseUrl: String,
        deviceCredential: suspend () -> DeviceCredential?,
    ): ActiveSyncRuntimeTransports {
        val client: HttpClient = createSyncHttpClient()
        return ActiveSyncRuntimeTransports(
            sync = KtorSyncTransport(client, serverBaseUrl, deviceCredential),
            rotations = KtorSyncLifecycleTransport(client, serverBaseUrl, deviceCredential),
            close = client::close,
        )
    }
}

/**
 * Builds a real encrypted transport/lifecycle graph from explicit deployment
 * configuration and durable local enrollment state. The factory never starts
 * pairing, recovery, or bootstrap implicitly.
 */
class ActiveSyncRuntimeFactory(
    private val dependencies: ActiveSyncRuntimeDependencies,
    private val transportFactory: ActiveSyncRuntimeTransportFactory = KtorActiveSyncRuntimeTransportFactory,
) {
    suspend fun create(configuration: ActiveSyncRuntimeConfiguration): ActiveSyncRuntimeCreation {
        val enrollment = dependencies.enrollments.state(configuration.accountId)
            ?: return ActiveSyncRuntimeCreation.NoEnrollment
        val active = enrollment as? LocalEnrollmentState.Active
            ?: return ActiveSyncRuntimeCreation.EnrollmentNotActive
        if (dependencies.secureStore.load(active.deviceCredentialReference) == null) {
            return ActiveSyncRuntimeCreation.MissingDeviceCredential
        }

        val credentialProvider: suspend () -> DeviceCredential? = {
            dependencies.secureStore.load(active.deviceCredentialReference)
        }
        val transports = transportFactory.create(configuration.serverBaseUrl, credentialProvider)
        return try {
            val decryptionKeys = SecureSyncPayloadKeyProvider(dependencies.keyRing, dependencies.secureStore)
            val encryptionKeys = SecureCurrentEncryptionKeyProvider(dependencies.keyRing, dependencies.secureStore)
            val engine = SyncEngine(
                dependencies.transactions,
                dependencies.journal,
                dependencies.history,
                dependencies.receiveState,
                dependencies.events,
                dependencies.tasks,
                dependencies.profiles,
                dependencies.academics,
                dependencies.ids,
                dependencies.wallClock,
            )
            val recipient = RotationKeyPackageRecipientService(
                dependencies.enrollments,
                dependencies.secureStore,
                dependencies.pairingHpke,
                dependencies.secureStore,
                dependencies.keyRing,
                SyncKeyPackageInstaller(dependencies.secureStore, dependencies.keyRing),
                dependencies.transactions,
            )
            val worker = SyncTransportWorker(
                dependencies.history,
                dependencies.outbound,
                dependencies.receiveState,
                AuthenticatedSyncEnvelopeCodec(decryptionKeys, encryptionKeys),
                encryptionKeys,
                active.deviceId,
                transports.sync,
                EncryptedSyncReceiveGateway(AuthenticatedSyncEnvelopeCodec(decryptionKeys, encryptionKeys), engine),
            )
            ActiveSyncRuntimeCreation.Active(
                ActiveSyncRuntime(
                    closeTransport = transports.close,
                    syncSpaceId = active.syncSpaceId,
                    rotations = RotationPackageCatchUpService(transports.rotations, recipient),
                    worker = worker,
                    sourceFacts = ActiveConflictAwareSourceFactQuery(
                        ConflictAwareProjection(dependencies.receiveState),
                        active.syncSpaceId,
                    ),
                    writePolicy = SyncConflictWriteGuard(dependencies.receiveState, active.syncSpaceId),
                ),
            )
        } catch (failure: Throwable) {
            transports.close()
            throw failure
        }
    }
}

/**
 * Stable app-facing projection/write boundary. Before explicit activation it
 * is local-only; after activation every normal read/write crosses D8 conflict
 * projection/guarding without reconstructing presentation services.
 */
class ActiveSyncRuntimeHost {
    private val mutex = Mutex()
    private var active: ActiveSyncRuntime? = null

    val sourceFacts: ConflictAwareSourceFactQuery = object : ConflictAwareSourceFactQuery {
        override suspend fun project(durable: EntityMutation) = mutex.withLock {
            (active?.sourceFacts ?: NoActiveSyncSpaceSourceFactQuery).project(durable)
        }

        override suspend fun projectCollection(entityKind: EntityKind, durable: Collection<EntityMutation>) = mutex.withLock {
            (active?.sourceFacts ?: NoActiveSyncSpaceSourceFactQuery).projectCollection(entityKind, durable)
        }
    }

    val writePolicy: SyncConflictWritePolicy = SyncConflictWritePolicy { proposed ->
        mutex.withLock { (active?.writePolicy ?: NoActiveSyncSpaceWritePolicy).blocks(proposed) }
    }

    suspend fun activate(
        factory: ActiveSyncRuntimeFactory,
        configuration: ActiveSyncRuntimeConfiguration,
    ): ActiveSyncRuntimeCreation = mutex.withLock {
        val created = factory.create(configuration)
        when (created) {
            is ActiveSyncRuntimeCreation.Active -> {
                active?.close()
                active = created.runtime
            }
            else -> {
                // An explicit activation attempt that cannot prove a local
                // ACTIVE enrollment must not leave a prior runtime connected.
                active?.close()
                active = null
            }
        }
        created
    }

    suspend fun catchUp(fetchLimit: Int = 100): ActiveSyncRuntimeCatchUpResult? = mutex.withLock {
        active?.catchUp(fetchLimit)
    }

    suspend fun deactivate() = mutex.withLock {
        active?.close()
        active = null
    }
}
