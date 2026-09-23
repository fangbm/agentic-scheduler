package dev.agenticscheduler.database

import dev.agenticscheduler.application.history.ConflictAwareProjection
import dev.agenticscheduler.application.history.ConflictCollectionProjection
import dev.agenticscheduler.application.history.MutationCoordinator
import dev.agenticscheduler.application.history.MutationWallClock
import dev.agenticscheduler.application.history.NoActiveSyncSpaceWritePolicy
import dev.agenticscheduler.application.history.SyncEngine
import dev.agenticscheduler.application.history.SyncReceiveResult
import dev.agenticscheduler.application.editing.CreateEventInput
import dev.agenticscheduler.application.editing.EditingResult
import dev.agenticscheduler.application.editing.EventEditingService
import dev.agenticscheduler.application.editing.EventTimeInput
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
import dev.agenticscheduler.application.sync.SyncTransport
import dev.agenticscheduler.application.sync.SyncTransportWorker
import dev.agenticscheduler.application.sync.SyncUploadResult
import dev.agenticscheduler.application.sync.RemoteSyncEnvelope
import dev.agenticscheduler.application.sync.TinkSyncPayloadAead
import dev.agenticscheduler.database.repository.RoomAcademicRepository
import dev.agenticscheduler.database.repository.RoomApplicationTransactionRunner
import dev.agenticscheduler.database.repository.RoomEventRepository
import dev.agenticscheduler.database.repository.RoomMutationJournalRepository
import dev.agenticscheduler.database.repository.RoomPlanningProfileRepository
import dev.agenticscheduler.database.repository.RoomSyncReceiveRepository
import dev.agenticscheduler.database.repository.RoomSyncOutboundEnvelopeRepository
import dev.agenticscheduler.database.repository.RoomTaskRepository
import dev.agenticscheduler.domain.id.EventId
import dev.agenticscheduler.domain.event.Event
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
import kotlinx.datetime.LocalDate

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
        val a = Replica("device-a", space, key)
        val b = Replica("device-b", space, key)
        val c = Replica("device-c", space, key)
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

            // A repeated conflict participant is idempotent and reproduces the
            // current durable conflict outcome; it never creates a second component.
            assertIs<EncryptedSyncReceiveResult.Handled>(a.receive(relay.at(4))).let { handled ->
                val repeated = assertIs<SyncReceiveResult.Conflicted>(handled.result)
                assertEquals(SyncConflictKind.SEMANTIC, repeated.kind)
                assertEquals(a.snapshot(eventId).openConflictId, repeated.conflictId)
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

    @Test
    fun `explicit resolution marker clears the same conflict on every replica`() = runBlocking {
        val space = SyncSpaceId("personal-space")
        val key = TinkSyncPayloadAead.generate()
        val relay = OpaqueRelay()
        val a = Replica("device-a", space, key)
        val b = Replica("device-b", space, key)
        val c = Replica("device-c", space, key)
        try {
            val eventId = id(30)
            val baseReplica = id(31)
            val leftReplica = id(32)
            val rightReplica = id(33)
            val base = EventImage(
                eventId,
                "Base",
                EventTimeImage.AllDay(AllDayRangeImage("2026-02-01", "2026-02-02")),
                FlexibilityImage.HARD,
                PinStateImage.UNPINNED,
            )
            val initial = operation(id(34), emptyList(), baseReplica, 1, base.title, null, base)
            val left = operation(id(35), listOf(VersionComponent(baseReplica, 1)), leftReplica, 1, "Left", base, base.copy(title = "Left"))
            val right = operation(id(36), listOf(VersionComponent(baseReplica, 1)), rightReplica, 1, "Right", base, base.copy(title = "Right"))
            listOf(initial, left, right).forEachIndexed { index, operation ->
                relay.publish(DeviceId("resolution-source-${index + 1}"), operation, a.codec)
            }
            a.deliver(relay, listOf(1, 2, 3))
            b.deliver(relay, listOf(1, 3, 2))
            c.deliver(relay, listOf(1, 2, 3))
            val conflictId = a.snapshot(eventId).openConflictId
            assertEquals(conflictId, b.snapshot(eventId).openConflictId)
            assertEquals(conflictId, c.snapshot(eventId).openConflictId)

            // A causally-later ordinary USER edit must not clear the title conflict.
            val ordinaryReplica = id(38)
            val ordinary = SyncOperation(
                id(37),
                DvvSnapshot(
                    listOf(
                        VersionComponent(baseReplica, 1),
                        VersionComponent(leftReplica, 1),
                        VersionComponent(rightReplica, 1),
                    ).sortedBy(VersionComponent::replicaId),
                    DotSnapshot(ordinaryReplica, 1),
                ),
                HlcSnapshot(3, 0, ordinaryReplica),
                MutationOrigin.User,
                listOf(EventPut(
                    base.copy(title = "Left"),
                    base.copy(title = "Left", flexibility = FlexibilityImage.SOFT),
                )),
            )
            relay.publish(DeviceId("ordinary-editor"), ordinary, a.codec)
            a.receive(relay.at(4)); b.receive(relay.at(4)); c.receive(relay.at(4))
            assertEquals(1, a.openConflictCount())
            assertEquals(1, b.openConflictCount())
            assertEquals(1, c.openConflictCount())

            val resolutionReplica = id(40)
            val resolution = SyncOperation(
                id(39),
                DvvSnapshot(
                    listOf(
                        VersionComponent(baseReplica, 1),
                        VersionComponent(leftReplica, 1),
                        VersionComponent(rightReplica, 1),
                        VersionComponent(ordinaryReplica, 1),
                    ).sortedBy(VersionComponent::replicaId),
                    DotSnapshot(resolutionReplica, 1),
                ),
                HlcSnapshot(4, 0, resolutionReplica),
                MutationOrigin.ConflictResolution(conflictId),
                listOf(EventPut(
                    base.copy(title = "Left", flexibility = FlexibilityImage.SOFT),
                    base.copy(title = "Resolved", flexibility = FlexibilityImage.SOFT),
                )),
            )
            relay.publish(DeviceId("resolver"), resolution, a.codec)
            a.receive(relay.at(5)); b.receive(relay.at(5)); c.receive(relay.at(5))

            listOf(a, b, c).forEach { replica ->
                assertEquals("Resolved", replica.eventTitle(eventId))
                assertEquals(0, replica.openConflictCount())
                val conflict = requireNotNull(replica.conflict(conflictId))
                assertEquals(SyncConflictStatus.RESOLVED, conflict.status)
                assertEquals(MutationId(resolution.mutationId), conflict.resolutionMutationId)
            }
        } finally {
            a.close(); b.close(); c.close()
        }
    }
    @Test
    fun `offline local write retains exact ciphertext then catches up through opaque relay`() = runBlocking {
        val space = SyncSpaceId("personal-space")
        val key = TinkSyncPayloadAead.generate()
        val relay = OpaqueRelay()
        val a = Replica("device-a", space, key)
        val b = Replica("device-b", space, key)
        try {
            val transport = RelayTransport(relay).apply { online = false }
            val local = a.createAllDayEvent("Offline local write")
            val mutationId = a.onlyLocalMutationId()

            val offline = a.worker(transport).run(space)
            assertEquals(0, offline.uploaded)
            assertIs<SyncUploadResult.RetryableFailure>(offline.stoppedOnFailure)
            assertEquals("Offline local write", local.title)
            val retained = a.outboundEnvelope(mutationId)
            assertEquals(false, retained.uploaded)

            transport.online = true
            val upload = a.worker(transport).run(space)
            assertEquals(1, upload.uploaded)
            assertEquals(retained.envelope, relay.storedEnvelopes().single(), "Retry uploads the first durable ciphertext verbatim.")

            val catchUp = b.worker(transport).run(space)
            assertEquals(1, catchUp.fetched)
            assertEquals("Offline local write", b.eventTitle(local.id.value))
            assertEquals(1L, b.cursor())
        } finally {
            a.close()
            b.close()
        }
    }

    private class Replica(
        private val device: String,
        private val syncSpaceId: SyncSpaceId,
        key: SyncPayloadAead,
    ) {
        private val database = openInMemoryDesktopDatabase()
        private val journal = RoomMutationJournalRepository(database)
        private val receive = RoomSyncReceiveRepository(database)
        private val events = RoomEventRepository(database)
        private val codecKeys = StaticKeys(syncSpaceId, key)
        private val ids = RfcUuidV7Generator(EpochMillisecondsClock { 1 }, RandomBytes { ByteArray(it) { 1 } })
        private val mutations = MutationCoordinator(RoomApplicationTransactionRunner(database), journal, ids, MutationWallClock { 1 })
        private val editor = EventEditingService(events, ids, mutations, NoActiveSyncSpaceWritePolicy)
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
            ids,
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

        suspend fun createAllDayEvent(title: String): Event = assertIs<EditingResult.Success<Event>>(
            editor.create(CreateEventInput(
                title = title,
                time = EventTimeInput.AllDay(LocalDate(2026, 1, 1), LocalDate(2026, 1, 2)),
                flexibility = dev.agenticscheduler.domain.planning.Flexibility.HARD,
                pinState = dev.agenticscheduler.domain.planning.PinState.UNPINNED,
            )),
        ).value

        suspend fun onlyLocalMutationId(): String = journal.timeline().single().operation.mutationId

        suspend fun outboundEnvelope(mutationId: String) = requireNotNull(RoomSyncOutboundEnvelopeRepository(database).envelope(syncSpaceId, mutationId))

        fun worker(transport: SyncTransport) = SyncTransportWorker(
            history = journal,
            outbound = RoomSyncOutboundEnvelopeRepository(database),
            receive = receive,
            codec = codec,
            encryptionKeys = codecKeys,
            deviceId = DeviceId(device),
            transport = transport,
            receiveGateway = gateway,
        )

        suspend fun eventTitle(eventId: String): String? = events.get(EventId(eventId))?.title
        suspend fun cursor(): Long = receive.serverCursor(syncSpaceId)
        suspend fun openConflictCount(): Int = receive.conflicts(syncSpaceId).count { it.status == SyncConflictStatus.OPEN }
        suspend fun conflict(conflictId: String): SyncConflict? = receive.conflict(conflictId)

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
            assertIs<SyncUploadResult.Stored>(store(encrypted.envelope))
        }

        fun at(cursor: Int): StoredRelayEnvelope = entries.single { it.cursor == cursor.toLong() }
        fun storedEnvelopes(): List<EncryptedEnvelopeV1> = entries.map(StoredRelayEnvelope::envelope)
        fun fetch(syncSpaceId: SyncSpaceId, afterCursor: Long, limit: Int): List<RemoteSyncEnvelope> = entries
            .asSequence()
            .filter { it.envelope.syncSpaceId == syncSpaceId && it.cursor > afterCursor }
            .take(limit)
            .map { RemoteSyncEnvelope(it.cursor, it.envelope) }
            .toList()

        fun store(envelope: EncryptedEnvelopeV1): SyncUploadResult {
            val existing = entries.firstOrNull { it.envelope.mutationId == envelope.mutationId }
            return when {
                existing == null -> SyncUploadResult.Stored(entries.size.toLong() + 1).also {
                    entries += StoredRelayEnvelope((it as SyncUploadResult.Stored).serverCursor, envelope)
                }
                existing.envelope == envelope -> SyncUploadResult.Idempotent(existing.cursor)
                else -> SyncUploadResult.IntegrityConflict("MutationId already has different opaque ciphertext.")
            }
        }
    }

    private class RelayTransport(private val relay: OpaqueRelay) : SyncTransport {
        var online = true

        override suspend fun upload(envelope: EncryptedEnvelopeV1): SyncUploadResult =
            if (online) relay.store(envelope) else SyncUploadResult.RetryableFailure("offline")

        override suspend fun fetch(syncSpaceId: SyncSpaceId, afterCursor: Long, limit: Int): List<RemoteSyncEnvelope> =
            if (online) relay.fetch(syncSpaceId, afterCursor, limit) else emptyList()
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
