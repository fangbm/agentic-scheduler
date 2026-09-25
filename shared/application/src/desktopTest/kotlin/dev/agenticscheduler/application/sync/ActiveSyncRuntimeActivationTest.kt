package dev.agenticscheduler.application.sync

import dev.agenticscheduler.application.history.MutationWallClock
import dev.agenticscheduler.application.id.productionUuidV7Generator
import dev.agenticscheduler.database.openDesktopDatabase
import dev.agenticscheduler.database.repository.RoomLocalEnrollmentRepository
import dev.agenticscheduler.database.repository.RoomSyncReceiveRepository
import dev.agenticscheduler.database.repository.RoomSyncKeyMetadataRepository
import dev.agenticscheduler.database.repository.RoomD8RuntimeComposition
import dev.agenticscheduler.sync.*
import java.io.File
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class ActiveSyncRuntimeActivationTest {
    @Test
    fun `missing credential keeps active enrollment conflict guard while no enrollment stays local`() = runBlocking {
        val databaseFile = File.createTempFile("agentic-d8-activation-", ".db")
        val store = DesktopPlatformSecureStore(MemoryBackend(), TinkPairingHpke())
        val database = openDesktopDatabase(databaseFile.absolutePath)
        val account = AccountId("activation-account")
        val space = SyncSpaceId("activation-space")
        try {
            val hpke = store.generatePairingDeviceKey()
            val credential = store.generate()
            val amk = store.generateAccountMasterKey()
            val contentKey = store.generateContentKey()
            RoomSyncKeyMetadataRepository(database).installNewEpoch(
                space,
                7,
                contentKey.reference,
                contentKey.identity,
            )
            RoomLocalEnrollmentRepository(database).saveActive(
                LocalEnrollmentState.Active(
                    account,
                    DeviceId("activation-device"),
                    EnrollmentRequestId("activation-enrollment"),
                    hpke.publicKey,
                    hpke.privateKeyReference,
                    space,
                    amk,
                    credential.reference,
                ),
            )
            store.delete(credential.reference)

            val conflicted = event("conflicted-event", "before")
            RoomSyncReceiveRepository(database).saveConflict(conflict(conflicted))
            val runtime = RoomD8RuntimeComposition(
                database,
                store,
                TinkPairingHpke(),
                productionUuidV7Generator(),
                MutationWallClock { 1L },
            )

            assertIs<ActiveSyncRuntimeCreation.MissingDeviceCredential>(
                runtime.activate(ActiveSyncRuntimeConfiguration(account, "https://sync.example")),
            )
            assertEquals(
                listOf("title"),
                runtime.writePolicy.blocks(listOf(EventPut(conflicted, conflicted.copy(title = "edited"))))
                    .single().blockedGroups,
            )
            assertEquals(
                emptyList(),
                runtime.writePolicy.blocks(listOf(EventPut(null, event("unrelated-event", "new")))),
            )

            assertIs<ActiveSyncRuntimeCreation.NoEnrollment>(
                runtime.activate(
                    ActiveSyncRuntimeConfiguration(AccountId("local-only-account"), "https://sync.example"),
                ),
            )
            assertEquals(
                emptyList(),
                runtime.writePolicy.blocks(listOf(EventPut(conflicted, conflicted.copy(title = "local edit")))),
            )
            runtime.deactivate()
        } finally {
            databaseFile.delete()
        }
    }

    private fun event(id: String, title: String) = EventImage(
        id,
        title,
        EventTimeImage.AllDay(AllDayRangeImage("2026-01-03", "2026-01-04")),
        FlexibilityImage.HARD,
        PinStateImage.UNPINNED,
    )

    private fun conflict(local: EventImage): SyncConflict {
        val remote = local.copy(title = "remote")
        val localMutationId = id(2)
        val remoteMutationId = id(1)
        val localReplica = id(3)
        val remoteReplica = id(4)
        val operations = listOf(
            SyncOperation(
                localMutationId,
                DvvSnapshot(emptyList(), DotSnapshot(localReplica, 1)),
                HlcSnapshot(1, 0, localReplica),
                MutationOrigin.User,
                listOf(EventPut(null, local)),
            ),
            SyncOperation(
                remoteMutationId,
                DvvSnapshot(emptyList(), DotSnapshot(remoteReplica, 1)),
                HlcSnapshot(2, 0, remoteReplica),
                MutationOrigin.User,
                listOf(EventPut(null, remote)),
            ),
        )
        val participants = operations.sortedBy(SyncOperation::mutationId).map {
            SyncConflictParticipant(MutationId(it.mutationId), it.dvv, LocalJournalCodec.encode(it))
        }
        return SyncConflict(
            conflictId = "activation-conflict-${local.id}",
            syncSpaceId = SyncSpaceId("activation-space"),
            entityRefs = listOf(SyncConflictEntityRef(EntityKind.EVENT, local.id, listOf("title"))),
            participants = participants,
            provisionalMutationId = participants.first().mutationId,
            kind = SyncConflictKind.SEMANTIC,
            commonCausalContext = commonCausalContextOf(participants),
            status = SyncConflictStatus.OPEN,
        )
    }

    private fun id(value: Int) = "00000000-0000-7000-8000-0000000000${value.toString().padStart(2, '0')}"

    private class MemoryBackend : DesktopSecureBackend {
        override val referencePrefix = "test-activation://"
        private val values = mutableMapOf<String, ByteArray>()
        override fun store(id: String, value: ByteArray) { values[id] = value.copyOf() }
        override fun read(id: String): ByteArray? = values[id]?.copyOf()
        override fun delete(id: String) { values.remove(id) }
    }
}
