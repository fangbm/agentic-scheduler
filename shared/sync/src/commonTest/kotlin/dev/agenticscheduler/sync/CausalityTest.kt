package dev.agenticscheduler.sync

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.collections.immutable.persistentMapOf

class CausalityTest {
    private val a = ReplicaId("018f6e68-7d0c-7000-8000-000000000001")
    private val b = ReplicaId("018f6e68-7d0c-7000-8000-000000000002")

    @Test fun `DVV distinguishes sequential equality and concurrent histories`() {
        val first = DottedVersionVector(persistentMapOf(), Dot(a, 1))
        val second = DottedVersionVector(first.observedContext(), Dot(a, 2))
        val sameSecond = DottedVersionVector(first.observedContext(), Dot(a, 2))
        val concurrent = DottedVersionVector(first.observedContext(), Dot(b, 1))

        assertEquals(CausalRelation.BEFORE, first.relationTo(second))
        assertEquals(CausalRelation.AFTER, second.relationTo(first))
        assertEquals(CausalRelation.EQUAL, second.relationTo(sameSecond))
        assertEquals(CausalRelation.CONCURRENT, second.relationTo(concurrent))
    }

    @Test fun `HLC local rollback and receive merge never decrease ordering`() {
        val local = HybridLogicalClock.tickLocal(null, 100, a)
        val rollback = HybridLogicalClock.tickLocal(local, 90, a)
        val remote = HlcTimestamp(110, 4, b)
        val merged = HybridLogicalClock.tickReceive(rollback, remote, 95, a)

        assertEquals(HlcTimestamp(100, 1, a), rollback)
        assertEquals(HlcTimestamp(110, 5, a), merged)
        assertEquals(true, merged > remote)
    }

    @Test fun `HLC receive covers each frozen merge branch and stable ordering`() {
        val last = HlcTimestamp(100, 3, a)
        assertEquals(HlcTimestamp(100, 4, a), HybridLogicalClock.tickReceive(last, HlcTimestamp(100, 2, b), 90, a))
        assertEquals(HlcTimestamp(100, 4, a), HybridLogicalClock.tickReceive(last, HlcTimestamp(90, 9, b), 80, a))
        assertEquals(HlcTimestamp(110, 8, a), HybridLogicalClock.tickReceive(last, HlcTimestamp(110, 7, b), 80, a))
        assertEquals(HlcTimestamp(120, 0, a), HybridLogicalClock.tickReceive(last, HlcTimestamp(110, 7, b), 120, a))
        assertEquals(listOf(a, b), listOf(HlcTimestamp(1, 0, b), HlcTimestamp(1, 0, a)).sorted().map(HlcTimestamp::replicaId))
    }

    @Test fun `focus block tombstone suppresses only a causally older put`() {
        val olderPut = DottedVersionVector(emptyMap(), Dot(a, 1)).toSnapshot()
        val tombstone = DottedVersionVector(mapOf(a to 1), Dot(a, 2)).toSnapshot()
        val concurrentPut = DottedVersionVector(mapOf(a to 1), Dot(b, 1)).toSnapshot()
        val laterPut = DottedVersionVector(mapOf(a to 2), Dot(a, 3)).toSnapshot()

        assertEquals(FocusBlockPutAgainstTombstone.SUPPRESS_CAUSALLY_OLDER, FocusBlockTombstoneCausality.decide(olderPut, tombstone))
        assertEquals(FocusBlockPutAgainstTombstone.CONCURRENT_CONFLICT, FocusBlockTombstoneCausality.decide(concurrentPut, tombstone))
        assertEquals(FocusBlockPutAgainstTombstone.APPLY, FocusBlockTombstoneCausality.decide(laterPut, tombstone))
    }
}
