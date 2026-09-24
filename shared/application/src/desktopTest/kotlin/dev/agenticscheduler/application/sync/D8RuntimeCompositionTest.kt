package dev.agenticscheduler.application.sync

import dev.agenticscheduler.application.history.MutationWallClock
import dev.agenticscheduler.application.id.productionUuidV7Generator
import dev.agenticscheduler.database.openDesktopDatabase
import dev.agenticscheduler.database.repository.RoomD8RuntimeComposition
import dev.agenticscheduler.database.repository.RoomLocalEnrollmentRepository
import dev.agenticscheduler.database.repository.RoomSyncKeyMetadataRepository
import dev.agenticscheduler.sync.AccountId
import dev.agenticscheduler.sync.DeviceId
import dev.agenticscheduler.sync.EnrollmentRequestId
import dev.agenticscheduler.sync.EncryptedEnvelopeV1
import dev.agenticscheduler.sync.SyncSpaceId
import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * Starts from the production Room composition rather than hand-assembling a
 * lifecycle graph. It permanently locks the required rotation-before-envelope
 * ordering and proves that active app reads/writes can swap onto D8's boundary.
 */
class D8RuntimeCompositionTest {
    @Test
    fun `production Room runtime catches rotation packages before encrypted envelopes`() = runBlocking {
        val databaseFile = File.createTempFile("agentic-d8-runtime-", ".db")
        val calls = mutableListOf<String>()
        val store = DesktopPlatformSecureStore(MemoryBackend(), TinkPairingHpke())
        val database = openDesktopDatabase(databaseFile.absolutePath)
        val account = AccountId("runtime-account")
        val space = SyncSpaceId("runtime-space")
        try {
            val hpke = store.generatePairingDeviceKey()
            val credential = store.generate()
            val amk = store.generateAccountMasterKey()
            val content = store.generateContentKey()
            RoomSyncKeyMetadataRepository(database).installNewEpoch(space, 7, content.reference, content.identity)
            RoomLocalEnrollmentRepository(database).saveActive(
                LocalEnrollmentState.Active(
                    account,
                    DeviceId("runtime-device"),
                    EnrollmentRequestId("runtime-enrollment"),
                    hpke.publicKey,
                    hpke.privateKeyReference,
                    space,
                    amk,
                    credential.reference,
                ),
            )

            val runtime = RoomD8RuntimeComposition(
                database,
                store,
                TinkPairingHpke(),
                productionUuidV7Generator(),
                MutationWallClock { 1L },
                ActiveSyncRuntimeTransportFactory { _, _ ->
                    ActiveSyncRuntimeTransports(
                        sync = object : SyncTransport {
                            override suspend fun upload(envelope: EncryptedEnvelopeV1): SyncUploadResult = error("No local mutation is expected.")
                            override suspend fun fetch(syncSpaceId: SyncSpaceId, afterCursor: Long, limit: Int): List<RemoteSyncEnvelope> {
                                calls += "envelopes"
                                return emptyList()
                            }
                        },
                        rotations = object : RotationPackageTransport {
                            override suspend fun rotationPackages(): List<ClientRotationPackageResponse> {
                                calls += "rotations"
                                return emptyList()
                            }
                        },
                        close = {},
                    )
                },
            )

            assertIs<ActiveSyncRuntimeCreation.Active>(
                runtime.activate(ActiveSyncRuntimeConfiguration(account, "https://sync.example")),
            )
            assertIs<ActiveSyncRuntimeCatchUpResult.Completed>(runtime.catchUp())
            assertEquals(listOf("rotations", "envelopes"), calls)
            runtime.deactivate()
        } finally {
            databaseFile.delete()
        }
    }

    private class MemoryBackend : DesktopSecureBackend {
        override val referencePrefix = "test-runtime://"
        private val values = mutableMapOf<String, ByteArray>()
        override fun store(id: String, value: ByteArray) { values[id] = value.copyOf() }
        override fun read(id: String): ByteArray? = values[id]?.copyOf()
        override fun delete(id: String) { values.remove(id) }
    }
}
