package dev.agenticscheduler.sync

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LocalJournalCodecTest {
    @Test fun `version one codec round trips a typed semantic operation`() {
        val operation = SyncOperation(
            mutationId = "00000000-0000-7000-8000-000000000001",
            dvv = DvvSnapshot(listOf(VersionComponent("00000000-0000-7000-8000-000000000002", 4)), DotSnapshot("00000000-0000-7000-8000-000000000002", 5)),
            hlc = HlcSnapshot(100, 2, "00000000-0000-7000-8000-000000000002"),
            origin = MutationOrigin.Planner,
            orderedMutations = listOf(FocusBlockDelete(FocusBlockImage("00000000-0000-7000-8000-000000000003", "00000000-0000-7000-8000-000000000004", ZonedTimeRangeImage("2026-01-01T10:00:00Z", "2026-01-01T11:00:00Z", "UTC"), FlexibilityImage.SOFT, PinStateImage.UNPINNED))),
        )
        val expectedV1 = """{"mutationId":"00000000-0000-7000-8000-000000000001","dvv":{"context":[{"replicaId":"00000000-0000-7000-8000-000000000002","counter":4}],"dot":{"replicaId":"00000000-0000-7000-8000-000000000002","counter":5}},"hlc":{"physicalMillis":100,"logical":2,"replicaId":"00000000-0000-7000-8000-000000000002"},"origin":{"type":"PLANNER"},"orderedMutations":[{"type":"FocusBlockDelete","before":{"id":"00000000-0000-7000-8000-000000000003","taskId":"00000000-0000-7000-8000-000000000004","time":{"start":"2026-01-01T10:00:00Z","endExclusive":"2026-01-01T11:00:00Z","timeZone":"UTC"},"flexibility":"SOFT","pinState":"UNPINNED"},"entityKind":"FOCUS_BLOCK"}]}"""
        assertEquals(expectedV1, LocalJournalCodec.encode(operation))
        assertEquals(operation, LocalJournalCodec.decode(expectedV1))
    }

    @Test fun `every mutation kind has a dedicated typed codec discriminator`() {
        val image = FocusBlockImage(id(1), id(2), ZonedTimeRangeImage("2026-01-01T10:00:00Z", "2026-01-01T11:00:00Z", "UTC"), FlexibilityImage.SOFT, PinStateImage.UNPINNED)
        val mutations = listOf<EntityMutation>(
            EventPut(null, EventImage(id(3), "event", EventTimeImage.AllDay(AllDayRangeImage("2026-01-01", "2026-01-02")), FlexibilityImage.HARD, PinStateImage.UNPINNED)),
            TaskPut(null, TaskImage(id(4), "task", TaskStatusImage.OPEN, TaskPriorityImage.NORMAL, TaskEffortImage(null, "PT0S", null), null)),
            PlanningProfilePut(null, PlanningProfileImage(id(5), "profile", PlanningProfileConfigurationImage.Unconfigured)),
            FocusBlockPut(null, image), FocusBlockDelete(image),
            WorkLogAppend(WorkLogImage(id(6), id(4), image.time)),
            TaskDependencyPut(null, TaskDependencyImage(id(7), id(4), id(8))),
            AcademicYearPut(null, AcademicYearImage(id(9), "year", "2026-01-01", "2027-01-01")),
            SemesterPut(null, SemesterImage(id(10), id(9), "semester", "2026-01-01", "2026-02-01", "UTC", listOf(AcademicWeekImage(1, "2026-01-01", "2026-01-08")))),
            CoursePut(null, CourseImage(id(11), id(10), "course", null)),
            PeriodTemplatePut(null, PeriodTemplateImage(id(12), "periods", listOf(AcademicPeriodImage(1, "09:00", "10:00")))),
            AcademicHolidayPut(null, AcademicHolidayImage(id(13), id(10), "holiday", AllDayRangeImage("2026-01-10", "2026-01-11"), AcademicHolidayTeachingEffectImage.NO_EFFECT)),
            CourseScheduleRulePut(null, CourseScheduleRuleImage(id(14), id(11), DayOfWeekImage.MONDAY, listOf(1), CourseTimeSpecImage.ClockTime("09:00", "10:00"), null)),
            CourseOccurrenceExceptionPut(null, CourseOccurrenceExceptionImage(id(15), CourseOccurrenceKeyImage(id(14), 1), CourseOccurrenceDispositionImage.CANCELLED, null, RoomOverrideImage.Unchanged)),
            ExamPut(null, ExamImage(id(16), id(10), null, "exam", ExamScheduleImage.Unscheduled)),
        )
        mutations.forEach { mutation ->
            val encoded = LocalJournalCodec.encodeMutation(mutation)
            assertTrue(encoded.startsWith("{\"type\":\"${mutation.operationKind()}\""), encoded)
            assertEquals(mutation, LocalJournalCodec.decodeMutation(encoded))
        }
    }

    private fun id(number: Int) = "00000000-0000-7000-8000-${number.toString().padStart(12, '0')}"
}
