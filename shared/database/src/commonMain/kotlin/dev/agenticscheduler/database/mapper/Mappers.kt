package dev.agenticscheduler.database.mapper

import dev.agenticscheduler.database.record.*
import dev.agenticscheduler.domain.academic.*
import dev.agenticscheduler.domain.event.Event
import dev.agenticscheduler.domain.id.*
import dev.agenticscheduler.domain.planning.*
import dev.agenticscheduler.domain.task.*
import dev.agenticscheduler.domain.time.*
import kotlin.time.Duration
import kotlin.time.Instant
import kotlinx.collections.immutable.toImmutableList
import kotlinx.datetime.*

private fun Instant.seconds() = epochSeconds
private fun Instant.nanos() = nanosecondsOfSecond
private fun instant(seconds: Long, nanos: Int) = Instant.fromEpochSeconds(seconds, nanos.toLong())
private fun range(startSeconds: Long, startNanos: Int, endSeconds: Long, endNanos: Int, zone: String) = ZonedTimeRange(instant(startSeconds, startNanos), instant(endSeconds, endNanos), TimeZone.of(zone))

internal fun Event.toRecord(): EventRecord = when (val value = time) {
    is ZonedTimeRange -> EventRecord(id.value, title, flexibility.name, pinState.name, "ZONED", value.start.seconds(), value.start.nanos(), value.endExclusive.seconds(), value.endExclusive.nanos(), value.timeZone.id, null, null, null, null)
    is AllDayRange -> EventRecord(id.value, title, flexibility.name, pinState.name, "ALL_DAY", null, null, null, null, null, value.startDate.toString(), value.endDateExclusive.toString(), null, null)
    is FloatingTimeRange -> EventRecord(id.value, title, flexibility.name, pinState.name, "FLOATING", null, null, null, null, null, null, null, value.start.toString(), value.endExclusive.toString())
}
internal fun EventRecord.toDomain() = Event(EventId(id), title, when (timeKind) {
    "ZONED" -> range(requireNotNull(zonedStartSeconds), requireNotNull(zonedStartNanos), requireNotNull(zonedEndSeconds), requireNotNull(zonedEndNanos), requireNotNull(zonedTimeZone))
    "ALL_DAY" -> AllDayRange(LocalDate.parse(requireNotNull(allDayStartDate)), LocalDate.parse(requireNotNull(allDayEndDateExclusive)))
    "FLOATING" -> FloatingTimeRange(LocalDateTime.parse(requireNotNull(floatingStartLocal)), LocalDateTime.parse(requireNotNull(floatingEndLocalExclusive)))
    else -> error("Unknown Event time kind: $timeKind")
}, Flexibility.valueOf(flexibility), PinState.valueOf(pinState))

internal fun Task.toRecord() = TaskRecord(id.value, title, status.name, priority.name, effort.estimated?.toIsoString(), effort.completed.toIsoString(), effort.remaining?.toIsoString(), when (deadline?.deadline) { is Deadline.Exact -> "EXACT"; is Deadline.DateOnly -> "DATE_ONLY"; null -> null }, (deadline?.deadline as? Deadline.Exact)?.at?.seconds(), (deadline?.deadline as? Deadline.Exact)?.at?.nanos(), (deadline?.deadline as? Deadline.Exact)?.timeZone?.id, (deadline?.deadline as? Deadline.DateOnly)?.date?.toString(), deadline?.policy?.name, deadline?.overflowPolicy?.name)
internal fun TaskRecord.toDomain() = Task(TaskId(id), title, TaskStatus.valueOf(status), TaskPriority.valueOf(priority), TaskEffort(effortEstimatedIso?.let(Duration::parseIsoString), Duration.parseIsoString(effortCompletedIso), effortRemainingIso?.let(Duration::parseIsoString)), deadlineKind?.let { kind -> TaskDeadline(when (kind) { "EXACT" -> Deadline.Exact(instant(requireNotNull(deadlineExactSeconds), requireNotNull(deadlineExactNanos)), TimeZone.of(requireNotNull(deadlineTimeZone))); "DATE_ONLY" -> Deadline.DateOnly(LocalDate.parse(requireNotNull(deadlineDate))); else -> error("Unknown deadline kind: $kind") }, DeadlinePolicy.valueOf(requireNotNull(deadlinePolicy)), OverflowPolicy.valueOf(requireNotNull(overflowPolicy))) })
internal fun FocusBlock.toRecord() = FocusBlockRecord(id.value, taskId.value, time.start.seconds(), time.start.nanos(), time.endExclusive.seconds(), time.endExclusive.nanos(), time.timeZone.id, flexibility.name, pinState.name)
internal fun FocusBlockRecord.toDomain() = FocusBlock(FocusBlockId(id), TaskId(taskId), range(startSeconds, startNanos, endSeconds, endNanos, timeZone), Flexibility.valueOf(flexibility), PinState.valueOf(pinState))
internal fun WorkLog.toRecord() = WorkLogRecord(id.value, taskId.value, time.start.seconds(), time.start.nanos(), time.endExclusive.seconds(), time.endExclusive.nanos(), time.timeZone.id)
internal fun WorkLogRecord.toDomain() = WorkLog(WorkLogId(id), TaskId(taskId), range(startSeconds, startNanos, endSeconds, endNanos, timeZone))
internal fun TaskDependency.toRecord() = TaskDependencyRecord(id.value, prerequisiteTaskId.value, dependentTaskId.value)
internal fun TaskDependencyRecord.toDomain() = TaskDependency(TaskDependencyId(id), TaskId(prerequisiteTaskId), TaskId(dependentTaskId))
internal fun PlanningProfile.toRecord() = PlanningProfileRecord(id.value, name)
internal fun PlanningProfileRecord.toDomain() = PlanningProfile(PlanningProfileId(id), name)

internal fun AcademicYear.toRecord() = AcademicYearRecord(id.value, name, startDate.toString(), endDateExclusive.toString())
internal fun AcademicYearRecord.toDomain() = AcademicYear(AcademicYearId(id), name, LocalDate.parse(startDate), LocalDate.parse(endDateExclusive))
internal fun Semester.toRecord() = SemesterRecord(id.value, academicYearId.value, name, startDate.toString(), endDateExclusive.toString(), timeZone.id)
internal fun Semester.weekRecords() = academicWeeks.map { AcademicWeekRecord(id.value, it.number.value, it.startDate.toString(), it.endDateExclusive.toString()) }
internal fun SemesterRecord.toDomain(weeks: List<AcademicWeekRecord>) = Semester(SemesterId(id), AcademicYearId(academicYearId), name, LocalDate.parse(startDate), LocalDate.parse(endDateExclusive), TimeZone.of(timeZone), weeks.sortedBy { it.weekNumber }.map { AcademicWeek(AcademicWeekNumber(it.weekNumber), LocalDate.parse(it.startDate), LocalDate.parse(it.endDateExclusive)) }.toImmutableList())
internal fun Course.toRecord() = CourseRecord(id.value, semesterId.value, name, code)
internal fun CourseRecord.toDomain() = Course(CourseId(id), SemesterId(semesterId), name, code)
internal fun PeriodTemplate.toRecord() = PeriodTemplateRecord(id.value, name)
internal fun PeriodTemplate.periodRecords() = periods.map { AcademicPeriodRecord(id.value, it.number.value, it.start.toString(), it.endExclusive.toString()) }
internal fun PeriodTemplateRecord.toDomain(periods: List<AcademicPeriodRecord>) = PeriodTemplate(PeriodTemplateId(id), name, periods.sortedBy { it.periodNumber }.map { AcademicPeriod(AcademicPeriodNumber(it.periodNumber), LocalTime.parse(it.startLocalTime), LocalTime.parse(it.endLocalTimeExclusive)) }.toImmutableList())
internal fun CourseScheduleRule.toRecord() = when (val value = time) {
    is CourseTimeSpec.ClockTime -> CourseScheduleRuleRecord(id.value, courseId.value, dayOfWeek.name, room, "CLOCK", value.start.toString(), value.endExclusive.toString(), null, null, null)
    is CourseTimeSpec.PeriodBased -> CourseScheduleRuleRecord(id.value, courseId.value, dayOfWeek.name, room, "PERIOD_BASED", null, null, value.periodTemplateId.value, value.startPeriod.value, value.endPeriodInclusive.value)
}
internal fun CourseScheduleRule.weekRecords() = teachingWeeks.weeks.map { CourseRuleWeekRecord(id.value, it.value) }
internal fun CourseScheduleRuleRecord.toDomain(weeks: List<CourseRuleWeekRecord>) = CourseScheduleRule(CourseScheduleRuleId(id), CourseId(courseId), DayOfWeek.valueOf(dayOfWeek), TeachingWeekSet.of(weeks.sortedBy { it.weekNumber }.map { AcademicWeekNumber(it.weekNumber) }), when (timeKind) {
    "CLOCK" -> CourseTimeSpec.ClockTime(LocalTime.parse(requireNotNull(clockStart)), LocalTime.parse(requireNotNull(clockEndExclusive)))
    "PERIOD_BASED" -> CourseTimeSpec.PeriodBased(PeriodTemplateId(requireNotNull(periodTemplateId)), AcademicPeriodNumber(requireNotNull(startPeriod)), AcademicPeriodNumber(requireNotNull(endPeriodInclusive)))
    else -> error("Unknown course rule time kind: $timeKind")
}, room)
internal fun AcademicHoliday.toRecord() = AcademicHolidayRecord(id.value, semesterId.value, name, dates.startDate.toString(), dates.endDateExclusive.toString(), teachingEffect.name)
internal fun AcademicHolidayRecord.toDomain() = AcademicHoliday(AcademicHolidayId(id), SemesterId(semesterId), name, AllDayRange(LocalDate.parse(startDate), LocalDate.parse(endDateExclusive)), AcademicHolidayTeachingEffect.valueOf(teachingEffect))
internal fun CourseOccurrenceException.toRecord() = CourseOccurrenceExceptionRecord(id.value, occurrenceKey.scheduleRuleId.value, occurrenceKey.academicWeekNumber.value, disposition.name, timeOverride?.start?.seconds(), timeOverride?.start?.nanos(), timeOverride?.endExclusive?.seconds(), timeOverride?.endExclusive?.nanos(), timeOverride?.timeZone?.id, when (roomOverride) { RoomOverride.Unchanged -> "UNCHANGED"; RoomOverride.Clear -> "CLEAR"; is RoomOverride.Set -> "SET" }, (roomOverride as? RoomOverride.Set)?.value)
internal fun CourseOccurrenceExceptionRecord.toDomain() = CourseOccurrenceException(CourseOccurrenceExceptionId(id), CourseOccurrenceKey(CourseScheduleRuleId(scheduleRuleId), AcademicWeekNumber(academicWeekNumber)), CourseOccurrenceDisposition.valueOf(disposition), if (startSeconds == null && startNanos == null && endSeconds == null && endNanos == null && timeZone == null) null else range(requireNotNull(startSeconds), requireNotNull(startNanos), requireNotNull(endSeconds), requireNotNull(endNanos), requireNotNull(timeZone)), when (roomOverrideKind) { "UNCHANGED" -> RoomOverride.Unchanged; "CLEAR" -> RoomOverride.Clear; "SET" -> RoomOverride.Set(requireNotNull(roomOverrideValue)); else -> error("Unknown room override kind: $roomOverrideKind") })
internal fun Exam.toRecord() = when (val value = schedule) {
    ExamSchedule.Unscheduled -> ExamRecord(id.value, semesterId.value, courseId?.value, title, "UNSCHEDULED", null, null, null, null, null, null)
    is ExamSchedule.DateOnly -> ExamRecord(id.value, semesterId.value, courseId?.value, title, "DATE_ONLY", value.date.toString(), null, null, null, null, null)
    is ExamSchedule.Exact -> ExamRecord(id.value, semesterId.value, courseId?.value, title, "EXACT", null, value.time.start.seconds(), value.time.start.nanos(), value.time.endExclusive.seconds(), value.time.endExclusive.nanos(), value.time.timeZone.id)
}
internal fun ExamRecord.toDomain() = Exam(ExamId(id), SemesterId(semesterId), courseId?.let(::CourseId), title, when (scheduleKind) { "UNSCHEDULED" -> ExamSchedule.Unscheduled; "DATE_ONLY" -> ExamSchedule.DateOnly(LocalDate.parse(requireNotNull(scheduleDate))); "EXACT" -> ExamSchedule.Exact(range(requireNotNull(startSeconds), requireNotNull(startNanos), requireNotNull(endSeconds), requireNotNull(endNanos), requireNotNull(timeZone))); else -> error("Unknown exam schedule kind: $scheduleKind") })
