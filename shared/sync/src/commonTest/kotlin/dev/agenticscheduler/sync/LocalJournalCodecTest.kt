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
        val expectedV1 = """{"mutationId":"00000000-0000-7000-8000-000000000001","dvv":{"context":[{"replicaId":"00000000-0000-7000-8000-000000000002","counter":4}],"dot":{"replicaId":"00000000-0000-7000-8000-000000000002","counter":5}},"hlc":{"physicalMillis":100,"logical":2,"replicaId":"00000000-0000-7000-8000-000000000002"},"origin":{"type":"PLANNER"},"orderedMutations":[{"type":"FocusBlockDelete","before":{"id":"00000000-0000-7000-8000-000000000003","taskId":"00000000-0000-7000-8000-000000000004","time":{"start":"2026-01-01T10:00:00Z","endExclusive":"2026-01-01T11:00:00Z","timeZone":"UTC"},"flexibility":"SOFT","pinState":"UNPINNED"},"entityKind":"FOCUS_BLOCK"}]}"""
        assertEquals(expectedV1, LocalJournalCodec.encode(operation))
        assertEquals(operation, LocalJournalCodec.decode(expectedV1))
    }

    @Test fun `every mutation kind has a frozen version one typed fixture`() {
        val image = FocusBlockImage(id(1), id(2), ZonedTimeRangeImage("2026-01-01T10:00:00Z", "2026-01-01T11:00:00Z", "UTC"), FlexibilityImage.SOFT, PinStateImage.UNPINNED)
        val fixtures = listOf(
            fixture(EventPut(null, EventImage(id(3), "event", EventTimeImage.AllDay(AllDayRangeImage("2026-01-01", "2026-01-02")), FlexibilityImage.HARD, PinStateImage.UNPINNED)), """{"type":"EventPut","before":null,"after":{"id":"00000000-0000-7000-8000-000000000003","title":"event","time":{"type":"ALL_DAY","dates":{"startDate":"2026-01-01","endDateExclusive":"2026-01-02"}},"flexibility":"HARD","pinState":"UNPINNED"},"entityKind":"EVENT"}"""),
            fixture(TaskPut(null, TaskImage(id(4), "task", TaskStatusImage.OPEN, TaskPriorityImage.NORMAL, TaskEffortImage(null, "PT0S", null), null)), """{"type":"TaskPut","before":null,"after":{"id":"00000000-0000-7000-8000-000000000004","title":"task","status":"OPEN","priority":"NORMAL","effort":{"estimated":null,"completed":"PT0S","remaining":null},"deadline":null},"entityKind":"TASK"}"""),
            fixture(PlanningProfilePut(null, PlanningProfileImage(id(5), "profile", PlanningProfileConfigurationImage.Unconfigured)), """{"type":"PlanningProfilePut","before":null,"after":{"id":"00000000-0000-7000-8000-000000000005","name":"profile","configuration":{"type":"UNCONFIGURED"}},"entityKind":"PLANNING_PROFILE"}"""),
            fixture(FocusBlockPut(null, image), """{"type":"FocusBlockPut","before":null,"after":{"id":"00000000-0000-7000-8000-000000000001","taskId":"00000000-0000-7000-8000-000000000002","time":{"start":"2026-01-01T10:00:00Z","endExclusive":"2026-01-01T11:00:00Z","timeZone":"UTC"},"flexibility":"SOFT","pinState":"UNPINNED"},"entityKind":"FOCUS_BLOCK"}"""),
            fixture(FocusBlockDelete(image), """{"type":"FocusBlockDelete","before":{"id":"00000000-0000-7000-8000-000000000001","taskId":"00000000-0000-7000-8000-000000000002","time":{"start":"2026-01-01T10:00:00Z","endExclusive":"2026-01-01T11:00:00Z","timeZone":"UTC"},"flexibility":"SOFT","pinState":"UNPINNED"},"entityKind":"FOCUS_BLOCK"}"""),
            fixture(WorkLogAppend(WorkLogImage(id(6), id(4), image.time)), """{"type":"WorkLogAppend","after":{"id":"00000000-0000-7000-8000-000000000006","taskId":"00000000-0000-7000-8000-000000000004","time":{"start":"2026-01-01T10:00:00Z","endExclusive":"2026-01-01T11:00:00Z","timeZone":"UTC"}},"entityKind":"WORK_LOG"}"""),
            fixture(TaskDependencyPut(null, TaskDependencyImage(id(7), id(4), id(8))), """{"type":"TaskDependencyPut","before":null,"after":{"id":"00000000-0000-7000-8000-000000000007","prerequisiteTaskId":"00000000-0000-7000-8000-000000000004","dependentTaskId":"00000000-0000-7000-8000-000000000008"},"entityKind":"TASK_DEPENDENCY"}"""),
            fixture(AcademicYearPut(null, AcademicYearImage(id(9), "year", "2026-01-01", "2027-01-01")), """{"type":"AcademicYearPut","before":null,"after":{"id":"00000000-0000-7000-8000-000000000009","name":"year","startDate":"2026-01-01","endDateExclusive":"2027-01-01"},"entityKind":"ACADEMIC_YEAR"}"""),
            fixture(SemesterPut(null, SemesterImage(id(10), id(9), "semester", "2026-01-01", "2026-02-01", "UTC", listOf(AcademicWeekImage(1, "2026-01-01", "2026-01-08")))), """{"type":"SemesterPut","before":null,"after":{"id":"00000000-0000-7000-8000-000000000010","academicYearId":"00000000-0000-7000-8000-000000000009","name":"semester","startDate":"2026-01-01","endDateExclusive":"2026-02-01","timeZone":"UTC","academicWeeks":[{"number":1,"startDate":"2026-01-01","endDateExclusive":"2026-01-08"}]},"entityKind":"SEMESTER"}"""),
            fixture(CoursePut(null, CourseImage(id(11), id(10), "course", null)), """{"type":"CoursePut","before":null,"after":{"id":"00000000-0000-7000-8000-000000000011","semesterId":"00000000-0000-7000-8000-000000000010","name":"course","code":null},"entityKind":"COURSE"}"""),
            fixture(PeriodTemplatePut(null, PeriodTemplateImage(id(12), "periods", listOf(AcademicPeriodImage(1, "09:00", "10:00")))), """{"type":"PeriodTemplatePut","before":null,"after":{"id":"00000000-0000-7000-8000-000000000012","name":"periods","periods":[{"number":1,"start":"09:00","endExclusive":"10:00"}]},"entityKind":"PERIOD_TEMPLATE"}"""),
            fixture(AcademicHolidayPut(null, AcademicHolidayImage(id(13), id(10), "holiday", AllDayRangeImage("2026-01-10", "2026-01-11"), AcademicHolidayTeachingEffectImage.NO_EFFECT)), """{"type":"AcademicHolidayPut","before":null,"after":{"id":"00000000-0000-7000-8000-000000000013","semesterId":"00000000-0000-7000-8000-000000000010","name":"holiday","dates":{"startDate":"2026-01-10","endDateExclusive":"2026-01-11"},"teachingEffect":"NO_EFFECT"},"entityKind":"ACADEMIC_HOLIDAY"}"""),
            fixture(CourseScheduleRulePut(null, CourseScheduleRuleImage(id(14), id(11), DayOfWeekImage.MONDAY, listOf(1), CourseTimeSpecImage.ClockTime("09:00", "10:00"), null)), """{"type":"CourseScheduleRulePut","before":null,"after":{"id":"00000000-0000-7000-8000-000000000014","courseId":"00000000-0000-7000-8000-000000000011","dayOfWeek":"MONDAY","teachingWeeks":[1],"time":{"type":"CLOCK_TIME","start":"09:00","endExclusive":"10:00"},"room":null},"entityKind":"COURSE_SCHEDULE_RULE"}"""),
            fixture(CourseOccurrenceExceptionPut(null, CourseOccurrenceExceptionImage(id(15), CourseOccurrenceKeyImage(id(14), 1), CourseOccurrenceDispositionImage.CANCELLED, null, RoomOverrideImage.Unchanged)), """{"type":"CourseOccurrenceExceptionPut","before":null,"after":{"id":"00000000-0000-7000-8000-000000000015","occurrence":{"scheduleRuleId":"00000000-0000-7000-8000-000000000014","academicWeekNumber":1},"disposition":"CANCELLED","timeOverride":null,"roomOverride":{"type":"UNCHANGED"}},"entityKind":"COURSE_OCCURRENCE_EXCEPTION"}"""),
            fixture(ExamPut(null, ExamImage(id(16), id(10), null, "exam", ExamScheduleImage.Unscheduled)), """{"type":"ExamPut","before":null,"after":{"id":"00000000-0000-7000-8000-000000000016","semesterId":"00000000-0000-7000-8000-000000000010","courseId":null,"title":"exam","schedule":{"type":"UNSCHEDULED"}},"entityKind":"EXAM"}"""),
        )
        fixtures.forEach { fixture ->
            assertEquals(fixture.expectedV1, LocalJournalCodec.encodeMutation(fixture.mutation))
            assertEquals(fixture.mutation, LocalJournalCodec.decodeMutation(fixture.expectedV1))
        }
    }

    private data class MutationFixture(val mutation: EntityMutation, val expectedV1: String)
    private fun fixture(mutation: EntityMutation, expectedV1: String) = MutationFixture(mutation, expectedV1)

    private fun id(number: Int) = "00000000-0000-7000-8000-${number.toString().padStart(12, '0')}"
}
