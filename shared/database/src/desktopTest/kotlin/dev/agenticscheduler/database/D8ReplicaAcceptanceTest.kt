package dev.agenticscheduler.database

import dev.agenticscheduler.application.history.ConflictAwareProjection
import dev.agenticscheduler.application.history.ConflictCollectionProjection
import dev.agenticscheduler.application.history.MutationWallClock
import dev.agenticscheduler.application.history.SyncEngine
import dev.agenticscheduler.application.history.SyncReceiveResult
import dev.agenticscheduler.application.id.EpochMillisecondsClock
import dev.agenticscheduler.application.id.RandomBytes
import dev.agenticscheduler.application.id.RfcUuidV7Generator
import dev.agenticscheduler.application.sync.AuthenticatedSyncEnvelopeCodec
import dev.agenticscheduler.application.sync.CurrentEncryptionKeyLookup
import dev.agenticscheduler.application.sync.CurrentEncryptionKeyProvider
import dev.agenticscheduler.application.sync.EncryptedSyncReceiveGateway
import dev.agenticscheduler.application.sync.EncryptedSyncReceiveResult
import dev.agenticscheduler.application.sync.EncryptSyncPayloadResult
import dev.agenticscheduler.application.sync.SyncEnvelopeBinding
import dev.agenticscheduler.application.sync.SyncPayloadAead
import dev.agenticscheduler.application.sync.SyncPayloadKeyLookup
import dev.agenticscheduler.application.sync.SyncPayloadKeyProvider
import dev.agenticscheduler.application.sync.TinkSyncPayloadAead
import dev.agenticscheduler.database.repository.RoomAcademicRepository
import dev.agenticscheduler.database.repository.RoomApplicationTransactionRunner
import dev.agenticscheduler.database.repository.RoomEventRepository
import dev.agenticscheduler.database.repository.RoomMutationJournalRepository
import dev.agenticscheduler.database.repository.RoomPlanningProfileRepository
import dev.agenticscheduler.database.repository.RoomSyncReceiveRepository
import dev.agenticscheduler.database.repository.RoomTaskRepository
import dev.agenticscheduler.domain.id.EventId
import dev.agenticscheduler.domain.time.AllDayRange
import dev.agenticscheduler.sync.AllDayRangeImage
import dev.agenticscheduler.sync.DeviceId
import dev.agenticscheduler.sync.DotSnapshot
import dev.agenticscheduler.sync.DvvSnapshot
import dev.agenticscheduler.sync.EncryptedEnvelopeV1
import dev.agenticscheduler.sync.EntityKind
import dev.agenticscheduler.sync.EventImage
import dev.agenticscheduler.sync.EventPut
import dev.agenticscheduler.sync.EventTimeImage
import dev.agenticscheduler.sync.FlexibilityImage
import dev.agenticscheduler.sync.HlcSnapshot
import dev.agenticscheduler.sync.MutationId
import dev.agenticscheduler.sync.MutationOrigin
import dev.agenticscheduler.sync.PinStateImage
import dev.agenticscheduler.sync.SyncConflictStatus
import dev.agenticscheduler.sync.SyncOperation
import dev.agenticscheduler.sync.SyncPayloadV1
import dev.agenticscheduler.sync.SyncSpaceId
import dev.agenticscheduler.sync.SyncWireCodec
import dev.agenticscheduler.sync.VersionComponent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlinx.coroutines.runBlocking

/**
 * D8 completion acceptance: three independent durable replicas receive the
 * same opaque relay envelopes in deliberately different orders. The relay
 * never receives payload JSON or a key; this harness still uses the real
 * desktop Tink AEAD through the authenticated gateway, then exercises
 * durable causality, conflict components, projection, cursors and duplicates
 * together.
 */
class D8ReplicaAcceptanceTest {
    @Test
    fun `three replicas converge on one projected N-way conflict through opaque relay`() = runBlocking {
        val space = SyncSpaceId("personal-space")
        val key = TinkSyncPayloadAead.generate()
        val relay = OpaqueRelay()
        val a = Replica(space, key)
        val b = Replica(space, key)
        val c = Replica(space, key)
        try {
            val eventId = id(10)
            val baseReplica = id(1)
            val base = EventImage(
                eventId,
                "Base title",
                EventTimeImage.AllDay(AllDayRangeImage("2026-01-01", "2026-01-02")),
                FlexibilityImage.HARD,
                PinStateImage.UNPINNED,
            )
            val initial = operation(
                mutationId = id(20),
                context = emptyList(),
                replicaId = baseReplica,
                counter = 1,
                title = base.title,
                before = null,
                after = base,
            )
            val updateA = operation(id(21), listOf(VersionComponent(baseReplica, 1)), id(2), 1, "A title", base, base.copy(title = "A title"))
            val updateB = operation(id(22), listOf(VersionComponent(baseReplica, 1)), id(3), 1, "B title", base, base.copy(title = "B title"))
            val updateC = operation(id(23), listOf(VersionComponent(baseReplica, 1)), id(4), 1, "C title", base, base.copy(title = "C title"))

            listOf(initial, updateA, updateB, updateC).forEachIndexed { index, operation ->
                relay.publish(sender = DeviceId("sender-${index + 1}"), operation = operation, codec = a.codec)
            }
            assertFalse(relay.storedEnvelopes().any { it.ciphertextBase64Url.contains("title", ignoreCase = true) })

            a.deliver(relay, listOf(1, 2, 3, 4))
            b.deliver(relay, listOf(1, 3, 4, 2))
            c.deliver(relay, listOf(1, 4, 2, 3))

            // A repeated stored envelope is a durable no-op, not a second conflict component.
            assertIs<EncryptedSyncReceiveResult.Handled>(a.receive(relay.at(4))).let { handled ->
                assertEquals(SyncReceiveResult.Duplicate(MutationId(updateC.mutationId)), handled.result)
            }

            val snapshots = listOf(a, b, c).map { it.snapshot(eventId) }
            assertEquals(listOf("A title", "A title", "A title"), snapshots.map { it.projectedTitle })
            assertEquals(listOf(4L, 4L, 4L), snapshots.map { it.cursor })
            assertEquals(listOf(4, 4, 4), snapshots.map { it.handledDots })
            assertEquals(1, snapshots.map { it.openConflictId }.distinct().size)
            assertEquals(listOf(updateA.mutationId, updateB.mutationId, updateC.mutationId), snapshots.first().participants)
            assertEquals(snapshots.first(), snapshots[1])
            assertEquals(snapshots.first(), snapshots[2])
        } finally {
            a.close()
            b.close()
            c.close()
        }
    }

    private class Replica(
        private val syncSpaceId: SyncSpaceId,
        key: SyncPayloadAead,
    ) {
        private val database = openInMemoryDesktopDatabase()
        private val journal = RoomMutationJournalRepository(database)
        private val receive = RoomSyncReceiveRepository(database)
        private val events = RoomEventRepository(database)
        private val codecKeys = StaticKeys(syncSpaceId, key)
        val codec = AuthenticatedSyncEnvelopeCodec(codecKeys, codecKeys)
        private val engine = SyncEngine(
            RoomApplicationTransactionRunner(database),
            journal,
            journal,
            receive,
            events,
            RoomTaskRepository(database),
            RoomPlanningProfileRepository(database),
            RoomAcademicRepository(database),
            RfcUuidV7Generator(EpochMillisecondsClock { 1 }, RandomBytes { ByteArray(it) { 1 } }),
            MutationWallClock { 1 },
        )
        private val gateway = EncryptedSyncReceiveGateway(codec, engine)

        suspend fun deliver(relay: OpaqueRelay, cursors: List<Int>) {
            cursors.forEach { receive(relay.at(it)) }
        }

        suspend fun receive(stored: StoredRelayEnvelope) = gateway.receive(
            SyncWireCodec.encodeEnvelope(stored.envelope),
            stored.cursor,
        )

        suspend fun snapshot(eventId: String): ReplicaSnapshot {
            val event = requireNotNull(events.get(EventId(eventId)))
            val range = assertIs<AllDayRange>(event.time)
            val durable = EventImage(
                id = event.id.value,
                title = event.title,
                time = EventTimeImage.AllDay(AllDayRangeImage(range.startDate.toString(), range.endDateExclusive.toString())),
                flexibility = FlexibilityImage.valueOf(event.flexibility.name),
                pinState = PinStateImage.valueOf(event.pinState.name),
            )
            val projection = assertIs<ConflictCollectionProjection.Projected>(
                ConflictAwareProjection(receive).projectCollection(
                    syncSpaceId,
                    EntityKind.EVENT,
                    listOf(EventPut(null, durable)),
                ),
            )
            val projected = assertIs<EventPut>(projection.mutations.single())
            val conflict = receive.conflicts(syncSpaceId).single { it.status == SyncConflictStatus.OPEN }
            return ReplicaSnapshot(
                projectedTitle = projected.after.title,
                cursor = receive.serverCursor(syncSpaceId),
                handledDots = receive.handledDots(syncSpaceId).size,
                openConflictId = conflict.conflictId,
                participants = conflict.participants.map { it.mutationId.value },
            )
        }

        fun close() = database.close()
    }

    private data class ReplicaSnapshot(
        val projectedTitle: String,
        val cursor: Long,
        val handledDots: Int,
        val openConflictId: String,
        val participants: List<String>,
    )

    private class OpaqueRelay {
        private val entries = mutableListOf<StoredRelayEnvelope>()

        suspend fun publish(sender: DeviceId, operation: SyncOperation, codec: AuthenticatedSyncEnvelopeCodec) {
            val encrypted = assertIs<EncryptSyncPayloadResult.Encrypted>(codec.encrypt(
                SyncEnvelopeBinding(SyncSpaceId("personal-space"), operation.mutationId, sender, 7),
                SyncPayloadV1(operation = operation),
            ))
            entries += StoredRelayEnvelope(entries.size.toLong() + 1, encrypted.envelope)
        }

        fun at(cursor: Int): StoredRelayEnvelope = entries.single { it.cursor == cursor.toLong() }
        fun storedEnvelopes(): List<EncryptedEnvelopeV1> = entries.map(StoredRelayEnvelope::envelope)
    }

    private data class StoredRelayEnvelope(val cursor: Long, val envelope: EncryptedEnvelopeV1)

    private class StaticKeys(
        private val syncSpaceId: SyncSpaceId,
        private val key: SyncPayloadAead,
    ) : SyncPayloadKeyProvider, CurrentEncryptionKeyProvider {
        override suspend fun keyFor(syncSpaceId: SyncSpaceId, keyEpoch: Long): SyncPayloadKeyLookup =
            if (syncSpaceId == this.syncSpaceId && keyEpoch == 7L) SyncPayloadKeyLookup.Available(key) else SyncPayloadKeyLookup.Missing

        override suspend fun currentEncryptionKey(syncSpaceId: SyncSpaceId): CurrentEncryptionKeyLookup =
            if (syncSpaceId == this.syncSpaceId) CurrentEncryptionKeyLookup.Available(7, key) else CurrentEncryptionKeyLookup.Missing
    }

    private fun operation(
        mutationId: String,
        context: List<VersionComponent>,
        replicaId: String,
        counter: Long,
        title: String,
        before: EventImage?,
        after: EventImage,
    ) = SyncOperation(
        mutationId,
        DvvSnapshot(context, DotSnapshot(replicaId, counter)),
        HlcSnapshot(1, 0, replicaId),
        MutationOrigin.User,
        listOf(EventPut(before, after.copy(title = title))),
    )

    private fun id(number: Int) = "00000000-0000-7000-8000-0000000000${number.toString().padStart(2, '0')}"
}
