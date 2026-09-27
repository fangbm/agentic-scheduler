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
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

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
        val rotationRequestEntered = CompletableDeferred<Unit>()
        val releaseRotationRequest = CompletableDeferred<Unit>()
        var transportClosed = false
        var runtime: RoomD8RuntimeComposition? = null
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

            runtime = RoomD8RuntimeComposition(
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
                                rotationRequestEntered.complete(Unit)
                                releaseRotationRequest.await()
                                return emptyList()
                            }
                        },
                        close = { transportClosed = true },
                    )
                },
            )

            assertIs<ActiveSyncRuntimeCreation.Active>(
                runtime!!.activate(ActiveSyncRuntimeConfiguration(account, "https://sync.example")),
            )
            val catchUp = async { runtime!!.catchUp() }
            withTimeout(5_000) { rotationRequestEntered.await() }

            // Conflict policy reaches Room while the network request is held.
            // This would time out if the Host mutex covered catch-up or policy IO.
            assertTrue(withTimeout(5_000) { runtime!!.writePolicy.blocks(emptyList()) }.isEmpty())

            // Deactivation detaches immediately, while the leased transport
            // remains alive until the in-flight catch-up exits.
            withTimeout(5_000) { runtime!!.deactivate() }
            assertFalse(transportClosed)

            releaseRotationRequest.complete(Unit)
            assertIs<ActiveSyncRuntimeCatchUpResult.Completed>(withTimeout(5_000) { catchUp.await() })
            assertEquals(listOf("rotations", "envelopes"), calls)
            assertTrue(transportClosed)
            assertNull(runtime!!.catchUp())
        } finally {
            if (!releaseRotationRequest.isCompleted) releaseRotationRequest.complete(Unit)
            runtime?.deactivate()
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
