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
            orderedMutations = listOf(FocusBlockDelete(FocusBlockImage("00000000-0000-7000-8000-000000000003", "00000000-0000-7000-8000-000000000004", ZonedTimeRangeImage("2026-01-01T10:00:00Z", "2026-01-01T11:00:00Z", "UTC"), FlexibilityImage.SOFT, PinStateImage.UNPINNED))),
        )
        val expectedV1 = """{"mutationId":"00000000-0000-7000-8000-000000000001","dvv":{"context":[{"replicaId":"00000000-0000-7000-8000-000000000002","counter":4}],"dot":{"replicaId":"00000000-0000-7000-8000-000000000002","counter":5}},"hlc":{"physicalMillis":100,"logical":2,"replicaId":"00000000-0000-7000-8000-000000000002"},"origin":{"type":"PLANNER"},"orderedMutations":[{"type":"FocusBlockDelete","before":{"id":"00000000-0000-7000-8000-000000000003","taskId":"00000000-0000-7000-8000-000000000004","start":"2026-01-01T10:00:00Z","endExclusive":"2026-01-01T11:00:00Z","timeZone":"UTC","flexibility":"SOFT","pinState":"UNPINNED"},"entityKind":"FOCUS_BLOCK"}]}"""
        assertEquals(expectedV1, LocalJournalCodec.encode(operation))
        assertEquals(operation, LocalJournalCodec.decode(expectedV1))
    }
}
