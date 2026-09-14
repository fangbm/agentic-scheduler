package dev.agenticscheduler.sync

import kotlin.test.Test
import kotlin.test.assertEquals

class LocalJournalCodecTest {
    @Test fun `version one codec round trips a typed semantic operation`() {
        val operation = SyncOperation(
            mutationId = "00000000-0000-7000-8000-000000000001",
            dvv = DvvSnapshot(listOf(VersionComponent("00000000-0000-7000-8000-000000000002", 4)), DotSnapshot("00000000-0000-7000-8000-000000000002", 5)),
            hlc = HlcSnapshot(100, 2, "00000000-0000-7000-8000-000000000002"),
            origin = MutationOrigin.Planner,
            orderedMutations = listOf(FocusBlockDelete(FocusBlockImage("00000000-0000-7000-8000-000000000003", "00000000-0000-7000-8000-000000000004", "2026-01-01T10:00:00Z", "2026-01-01T11:00:00Z", "UTC", "SOFT", "UNPINNED"))),
        )
        assertEquals(operation, LocalJournalCodec.decode(LocalJournalCodec.encode(operation)))
    }
}
