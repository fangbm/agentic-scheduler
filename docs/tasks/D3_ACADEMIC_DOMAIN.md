# D3 — Academic Domain Task Spec

> Status: **READY FOR IMPLEMENTATION**  
> Milestone: D3  
> Scope owner: `:shared:domain`  
> Required reading: `AGENTS.md`, `docs/READ_FIRST.md`, `docs/DOMAIN_INVARIANTS.md`, `docs/ACADEMIC_INVARIANTS.md`, `docs/ACADEMIC_DECISIONS.md`, `docs/OPEN_DECISIONS.md`, `docs/IMPLEMENTATION_CONTRACT.md`, `docs/MODULE_OWNERSHIP.md`, `docs/UBIQUITOUS_LANGUAGE.md`.

This file is the authoritative coding contract for D3.

D3 introduces the Academic Domain as a deterministic semantic layer. It does **not** implement persistence, imports, UI, Planner policy, Sync, Agent behavior, or external calendar mapping.

---

# 1. D3 outcome

At completion, `:shared:domain` contains tested multiplatform Academic primitives for:

```text
AcademicYear
Semester
AcademicWeekNumber
AcademicWeek
Course
TeachingWeekSet
AcademicPeriodNumber
AcademicPeriod
PeriodTemplate
CourseTimeSpec
CourseScheduleRule
CourseOccurrenceKey
CourseOccurrenceDisposition
RoomOverride
CourseOccurrenceException
AcademicHolidayTeachingEffect
AcademicHoliday
CourseSessionState
CourseCancellationReason
CourseSession
CourseSessionResolver
CourseSessionResolutionResult / issues
ExamSchedule
Exam
Academic membership validators
Exam validator
```

The core proof for D3 is:

```text
explicit Semester weeks
+ CourseScheduleRule
+ PeriodTemplate when needed
+ AcademicHoliday
+ CourseOccurrenceException
        ↓
 deterministic pure resolution
        ↓
stable CourseSession projections
```

---

# 2. D3 MUST

- add the exact Academic concepts defined here;
- keep every introduced public state immutable;
- preserve D2 ID/time invariants;
- use explicit Semester timezone semantics;
- model academic week numbering explicitly;
- canonicalize Course teaching-week selection;
- support both clock-time and period-based course rules;
- preserve stable occurrence identity across cancellation/rescheduling/room changes;
- implement deterministic CourseSession resolution;
- reject ambiguous/nonexistent DST wall times instead of guessing;
- implement explicit holiday/exception precedence;
- expose expected multi-entity resolution failures as structured result/issues;
- implement Exam's three schedule states;
- add deterministic positive and negative `commonTest` coverage;
- keep repository-wide build/CI green.

---

# 3. D3 MUST NOT

D3 must not:

```text
implement Room / SQLite schema / DAO / repositories
persist or cache CourseSession
implement ICS/CSV/Excel/screenshot/school-system import
implement recurrence framework shared with Event
make Course inherit/contain Event as its schedule model
make Exam inherit Event
add Reminder
add Project/Inbox/Tag/Attachment
add Location entity integration
add instructor/credits/color fields to Course
add Planner occupancy, Flexibility, PinState, scoring, Local Reflow, Full Replan
add SyncOperation, DVV, HLC, serialization annotations, merge logic
add Agent/LLM/Tool code
add server/network code
add UI features
create a new Gradle module
add any third-party dependency
consult current system time or system default timezone
invent archive/current-semester behavior
invent cross-semester occurrence moves
```

D3 may refine existing D2 source files only where this task explicitly authorizes it, primarily `EntityIds.kt`.

---

# 4. Dependency/tooling

No new dependency is authorized.

Use the repository's existing:

```text
Kotlin 2.3.21
kotlinx-datetime 0.8.0
kotlin.time.Instant
kotlin.time.Duration
kotlinx.datetime.LocalDate
kotlinx.datetime.LocalDateTime
kotlinx.datetime.LocalTime
kotlinx.datetime.TimeZone
kotlinx.datetime.DayOfWeek
```

The pinned `kotlinx-datetime 0.8.0` baseline does not expose `TransitionHandler` as a usable public API for this resolver. D3 therefore freezes **strict transition-rejection semantics**, not a particular library call. If a later dependency upgrade exposes a suitable transition handler, adopting it requires preserving the same reject-on-ambiguity/nonexistence behavior.

Do not use `java.time.*` in `commonMain`.

Do not use `Clock.System.now()`, `TimeZone.currentSystemDefault()`, random IDs, or hidden environment inputs.

---

# 5. Canonical package/file layout

Expected layout:

```text
shared/domain/src/commonMain/kotlin/dev/agenticscheduler/domain/
├── id/
│   └── EntityIds.kt                         # extend existing file
└── academic/
    ├── AcademicCalendar.kt
    ├── Course.kt
    ├── CourseSchedule.kt
    ├── PeriodTemplate.kt
    ├── AcademicHoliday.kt
    ├── CourseSession.kt
    ├── CourseSessionResolver.kt
    └── Exam.kt
```

Small splits are allowed only for readability. Do not create generic `model`, `utils`, `common`, or another architectural package root.

Tests mirror semantic packages under `commonTest`.

---

# 6. New strongly typed IDs

Extend the existing UUIDv7 ID family with exactly:

```text
AcademicYearId
SemesterId
CourseId
CourseScheduleRuleId
CourseOccurrenceExceptionId
ExamId
AcademicHolidayId
PeriodTemplateId
```

They use the exact same canonical lowercase UUIDv7 validation contract as D2 IDs.

D3 does **not** add:

```text
AcademicWeekId
AcademicPeriodId
CourseSessionId
```

because AcademicWeek and AcademicPeriod are owner-scoped values, while CourseSession identity is `CourseOccurrenceKey`.

No production ID generator is added in D3.

---

# 7. AcademicYear

Canonical shape:

```kotlin
data class AcademicYear(
    val id: AcademicYearId,
    val name: String,
    val startDate: LocalDate,
    val endDateExclusive: LocalDate,
)
```

Invariants:

```text
name.isNotBlank()
startDate < endDateExclusive
```

Preserve caller-provided non-blank name text; do not trim automatically.

AcademicYear does not embed `List<Semester>` or `List<SemesterId>` in D3.

---

# 8. AcademicWeekNumber

Define:

```kotlin
@JvmInline
value class AcademicWeekNumber(val value: Int)
```

Invariant:

```text
value >= 1
```

No global maximum is defined. A Semester determines which week numbers exist.

---

# 9. AcademicWeek

Canonical shape:

```kotlin
data class AcademicWeek(
    val number: AcademicWeekNumber,
    val startDate: LocalDate,
    val endDateExclusive: LocalDate,
)
```

Invariants:

```text
endDateExclusive == startDate + 7 calendar days
```

Therefore every AcademicWeek is exactly seven local calendar dates and uses half-open semantics.

Do not require Monday as the start day. Institutions may define a different academic-week boundary.

---

# 10. Semester

Canonical shape:

```kotlin
data class Semester(
    val id: SemesterId,
    val academicYearId: AcademicYearId,
    val name: String,
    val startDate: LocalDate,
    val endDateExclusive: LocalDate,
    val timeZone: TimeZone,
    val academicWeeks: List<AcademicWeek>,
)
```

Construction invariants:

```text
name.isNotBlank()
startDate < endDateExclusive
academicWeeks is non-empty
academicWeeks[0].number == 1
week numbers are consecutive: 1, 2, ..., N
list is already in ascending number order
every week is fully inside Semester date range
week date ranges are strictly chronological and non-overlapping
```

Date gaps between consecutive numbered weeks are allowed.

Do not silently reorder the input list.

Do not require:

```text
Week 1 startDate == Semester startDate
last week endDateExclusive == Semester endDateExclusive
```

Semester boundaries may include orientation, breaks, exams, or administrative dates outside numbered teaching weeks.

D3 adds no `current`, `default`, or `archived` flag.

---

# 11. AcademicYear / Semester membership validator

Implement pure validation equivalent to:

```kotlin
enum class SemesterAcademicYearValidationResult {
    VALID,
    ACADEMIC_YEAR_ID_MISMATCH,
    OUTSIDE_ACADEMIC_YEAR,
}

fun validateSemesterAgainstAcademicYear(
    academicYear: AcademicYear,
    semester: Semester,
): SemesterAcademicYearValidationResult
```

Rules:

- mismatched `semester.academicYearId` returns `ACADEMIC_YEAR_ID_MISMATCH`;
- otherwise a Semester range not fully contained in the AcademicYear returns `OUTSIDE_ACADEMIC_YEAR`;
- otherwise `VALID`.

Overlapping Semesters are not checked/rejected by this validator.

---

# 12. Course

Canonical shape:

```kotlin
data class Course(
    val id: CourseId,
    val semesterId: SemesterId,
    val name: String,
    val code: String?,
)
```

Invariants:

```text
name.isNotBlank()
code == null || code.isNotBlank()
```

Preserve caller-provided non-blank text. No automatic trim/case normalization.

D3 Course has no instructor, credits, color, location entity, recurrence, reminders, planner policy, persistence, or external-calendar IDs.

---

# 13. TeachingWeekSet

`TeachingWeekSet` is the canonical Domain representation of the weeks selected by a CourseScheduleRule.

Required public semantics are equivalent to:

```kotlin
class TeachingWeekSet private constructor(
    val weeks: List<AcademicWeekNumber>,
) {
    companion object {
        fun of(weeks: Collection<AcademicWeekNumber>): TeachingWeekSet
    }
}
```

Exact class/data-class syntax may vary if required to enforce invariants, but semantics are fixed.

`TeachingWeekSet.of(...)`:

- rejects an empty collection;
- removes duplicate week numbers;
- sorts ascending by numeric value;
- exposes immutable canonical ascending `weeks`;
- implements value equality/hash semantics based on the canonical week list.

Therefore:

```text
of(1,3,5) == of(5,3,1,3)
```

D3 does not store ODD/EVEN/range syntax as separate variants.

No parser for text like `odd`, `1-16`, or `1,3,5` is part of D3.

---

# 14. AcademicPeriodNumber

Define:

```kotlin
@JvmInline
value class AcademicPeriodNumber(val value: Int)
```

Invariant:

```text
value >= 1
```

No global upper bound.

---

# 15. AcademicPeriod

Canonical shape:

```kotlin
data class AcademicPeriod(
    val number: AcademicPeriodNumber,
    val start: LocalTime,
    val endExclusive: LocalTime,
)
```

Invariant:

```text
start < endExclusive
```

D3 periods do not cross local midnight.

---

# 16. PeriodTemplate

Canonical shape:

```kotlin
data class PeriodTemplate(
    val id: PeriodTemplateId,
    val name: String,
    val periods: List<AcademicPeriod>,
)
```

Invariants:

```text
name.isNotBlank()
periods is non-empty
period numbers are strictly ascending and unique
the list is already in period-number order
time ranges are in the same chronological order
periods do not overlap
```

Adjacent periods are allowed. Gaps are allowed.

Period numbers need not be consecutive and need not start at 1, although each number itself is positive.

Do not silently sort malformed PeriodTemplate input.

---

# 17. CourseTimeSpec

Define exactly:

```kotlin
sealed interface CourseTimeSpec {
    data class ClockTime(
        val start: LocalTime,
        val endExclusive: LocalTime,
    ) : CourseTimeSpec

    data class PeriodBased(
        val periodTemplateId: PeriodTemplateId,
        val startPeriod: AcademicPeriodNumber,
        val endPeriodInclusive: AcademicPeriodNumber,
    ) : CourseTimeSpec
}
```

`ClockTime` invariant:

```text
start < endExclusive
```

It does not cross midnight in D3.

`PeriodBased` invariant:

```text
startPeriod.value <= endPeriodInclusive.value
```

The constructor does not look up the PeriodTemplate. Missing templates/periods are cross-entity resolver issues.

For PeriodBased resolution:

```text
resolved local start = referenced startPeriod.start
resolved local end   = referenced endPeriodInclusive.endExclusive
```

If either referenced period does not exist, resolution fails explicitly.

---

# 18. CourseScheduleRule

Canonical shape:

```kotlin
data class CourseScheduleRule(
    val id: CourseScheduleRuleId,
    val courseId: CourseId,
    val dayOfWeek: DayOfWeek,
    val teachingWeeks: TeachingWeekSet,
    val time: CourseTimeSpec,
    val room: String?,
)
```

Invariant:

```text
room == null || room.isNotBlank()
```

One rule has one weekday and one time specification. It creates at most one base occurrence in each selected academic week.

A Course that meets Monday and Thursday uses two rules.

No `Flexibility`, `PinState`, `Reminder`, generic RecurrenceRule, or Planner occupancy field is added here.

---

# 19. CourseOccurrenceKey

Define:

```kotlin
data class CourseOccurrenceKey(
    val scheduleRuleId: CourseScheduleRuleId,
    val academicWeekNumber: AcademicWeekNumber,
)
```

This is the complete D3 stable identity for a logical course occurrence.

A moved/cancelled/reinstated occurrence keeps the same key.

Never derive occurrence identity from current effective start time or room.

---

# 20. RoomOverride

Define exactly these semantics:

```kotlin
sealed interface RoomOverride {
    data object Unchanged : RoomOverride

    data class Set(
        val value: String,
    ) : RoomOverride

    data object Clear : RoomOverride
}
```

`RoomOverride.Set.value.isNotBlank()` is required.

Meaning:

```text
Unchanged → retain CourseScheduleRule.room
Set(x)    → effective room becomes x
Clear     → effective room becomes null
```

Do not collapse Unchanged and Clear into nullable String semantics.

---

# 21. CourseOccurrenceException

Define:

```kotlin
enum class CourseOccurrenceDisposition {
    ACTIVE,
    CANCELLED,
}

data class CourseOccurrenceException(
    val id: CourseOccurrenceExceptionId,
    val occurrenceKey: CourseOccurrenceKey,
    val disposition: CourseOccurrenceDisposition,
    val timeOverride: ZonedTimeRange?,
    val roomOverride: RoomOverride,
)
```

Construction invariant for `CANCELLED`:

```text
timeOverride == null
roomOverride == RoomOverride.Unchanged
```

An `ACTIVE` exception may legally contain no overrides. This means "explicitly keep/reinstate this base occurrence" and is required to override holiday suspension.

A time override is an exact concrete `ZonedTimeRange`; it may move the class to a different weekday or academic week while preserving the same occurrence key.

D3 does not support changing the target Course/rule/week identity through an exception.

---

# 22. AcademicHoliday

Define:

```kotlin
enum class AcademicHolidayTeachingEffect {
    NO_EFFECT,
    SUSPEND_TEACHING,
}

data class AcademicHoliday(
    val id: AcademicHolidayId,
    val semesterId: SemesterId,
    val name: String,
    val dates: AllDayRange,
    val teachingEffect: AcademicHolidayTeachingEffect,
)
```

Invariant:

```text
name.isNotBlank()
```

Holiday name has no behavioral meaning.

D3 does not infer teaching suspension from text.

Range containment in Semester is a resolver/cross-entity responsibility because AcademicHoliday construction alone does not have Semester state.

---

# 23. CourseSession

Define:

```kotlin
enum class CourseCancellationReason {
    ACADEMIC_HOLIDAY,
    EXPLICIT_EXCEPTION,
}

sealed interface CourseSessionState {
    data class Scheduled(
        val time: ZonedTimeRange,
        val room: String?,
    ) : CourseSessionState

    data class Cancelled(
        val reason: CourseCancellationReason,
    ) : CourseSessionState
}

data class CourseSession(
    val occurrenceKey: CourseOccurrenceKey,
    val courseId: CourseId,
    val baseTime: ZonedTimeRange,
    val state: CourseSessionState,
)
```

`CourseSessionState.Scheduled.room`, when non-null, must be non-blank. The resolver only constructs valid scheduled room values.

`baseTime` is always the rule-derived exact base occurrence even when effective time is moved or state is cancelled.

No CourseSessionId exists in D3.

CourseSession is a derived projection and is not an independently editable aggregate.

---

# 24. Course resolver input

Expose one clear pure API equivalent to:

```kotlin
fun resolveCourseSessions(
    semester: Semester,
    course: Course,
    rules: Collection<CourseScheduleRule>,
    periodTemplates: Collection<PeriodTemplate>,
    holidays: Collection<AcademicHoliday>,
    exceptions: Collection<CourseOccurrenceException>,
): CourseSessionResolutionResult
```

Exact placement may be a top-level function or stateless object named `CourseSessionResolver`. Do not create a repository/application service/module for it.

Only rules for the supplied Course are valid input; passing foreign-course rules is an explicit issue, not a filtering request.

Only holidays for the supplied Semester are valid input; passing foreign-semester holidays is an explicit issue.

---

# 25. Resolution result

Define:

```kotlin
sealed interface CourseSessionResolutionResult {
    data class Success(
        val sessions: List<CourseSession>,
    ) : CourseSessionResolutionResult

    data class Invalid(
        val issues: List<CourseSessionResolutionIssue>,
    ) : CourseSessionResolutionResult
}
```

Rules:

- `Success.sessions` is complete for all selected rule/week occurrences;
- `Invalid.issues` is non-empty;
- D3 does not return partial sessions alongside issues;
- issue ordering is deterministic;
- input collection iteration order must not affect final issues or sessions.

---

# 26. Resolution issues

Use a sealed interface with these exact public semantic cases. Data-class property naming may use the obvious typed IDs shown below; do not replace the issue model with free-form strings.

```text
CourseSemesterMismatch
DuplicateRuleId
RuleCourseMismatch
UnknownAcademicWeek
DuplicatePeriodTemplateId
UnknownPeriodTemplate
UnknownStartPeriod
UnknownEndPeriod
DuplicateHolidayId
HolidaySemesterMismatch
HolidayOutsideSemester
DuplicateExceptionId
DuplicateExceptionTarget
OrphanException
ExceptionTimeZoneMismatch
ExceptionOutsideSemester
DstTransitionRejected
```

Minimum required typed context:

```text
CourseSemesterMismatch
  expected SemesterId / actual SemesterId

DuplicateRuleId
  CourseScheduleRuleId

RuleCourseMismatch
  ruleId / expected CourseId / actual CourseId

UnknownAcademicWeek
  ruleId / AcademicWeekNumber

DuplicatePeriodTemplateId
  PeriodTemplateId

UnknownPeriodTemplate
  ruleId / PeriodTemplateId

UnknownStartPeriod / UnknownEndPeriod
  ruleId / PeriodTemplateId / AcademicPeriodNumber

DuplicateHolidayId
  AcademicHolidayId

HolidaySemesterMismatch
  holidayId / expected SemesterId / actual SemesterId

HolidayOutsideSemester
  AcademicHolidayId

DuplicateExceptionId
  CourseOccurrenceExceptionId

DuplicateExceptionTarget
  CourseOccurrenceKey

OrphanException
  exceptionId / CourseOccurrenceKey

ExceptionTimeZoneMismatch
  CourseOccurrenceExceptionId

ExceptionOutsideSemester
  CourseOccurrenceExceptionId

DstTransitionRejected
  CourseOccurrenceKey
```

Issue list canonical sorting:

1. by issue case in the order listed above;
2. then by involved canonical UUID text ascending;
3. then by AcademicWeek/Period numeric value where needed.

This ordering rule is part of deterministic replay behavior.

---

# 27. Resolver validation phase

Before generating successful sessions, validate the full input set.

Required checks:

```text
course.semesterId == semester.id
rule IDs unique
all rules reference course.id
all rule TeachingWeekSet values exist in semester.academicWeeks
PeriodTemplate IDs unique
all PeriodBased rules reference an existing template
referenced start/end periods exist
holiday IDs unique
all holidays reference semester.id
all holiday ranges are fully inside Semester date range
exception IDs unique
only one exception may target each CourseOccurrenceKey
every exception targets a base occurrence that exists in the supplied rules/week sets
timeOverride.timeZone == semester.timeZone
timeOverride fully lies inside Semester local date bounds
```

If any validation issue exists, return `Invalid` with all deterministically discoverable issues. Do not silently discard bad entities and continue with partial resolution.

---

# 28. Finding the occurrence date

For each `(rule, selected AcademicWeek)` pair:

1. inspect the explicit seven dates in `[week.startDate, week.endDateExclusive)`;
2. find the unique date whose `dayOfWeek == rule.dayOfWeek`;
3. use that date as the base occurrence local date.

Because AcademicWeek is exactly seven calendar days, there is exactly one matching weekday.

Do not calculate occurrence date from Semester start date or previous occurrence Instant.

---

# 29. Resolve ClockTime

For `CourseTimeSpec.ClockTime`:

```text
local start = occurrenceDate + ClockTime.start
local end   = occurrenceDate + ClockTime.endExclusive
```

Resolve both LocalDateTimes to Instant using `semester.timeZone` with **strict transition rejection**.

For the pinned `kotlinx-datetime 0.8.0` baseline, do not rely on a nonexistent `TransitionHandler` API. The resolver must deterministically reject both categories:

```text
nonexistent wall time → no valid Instant maps back to the requested LocalDateTime
ambiguous wall time   → more than one valid Instant maps back to the requested LocalDateTime
```

A local/reversible implementation may use round-trip validation plus explicit alternative-offset detection. The exact internal mechanism is not frozen, but the semantic result is: **exactly one valid Instant is required**. Never silently choose an earlier/later offset and never shift a nonexistent local time.

If either local datetime is ambiguous/nonexistent because of a timezone transition, record `DstTransitionRejected(occurrenceKey)`.

On success create:

```text
ZonedTimeRange(startInstant, endInstant, semester.timeZone)
```

---

# 30. Resolve PeriodBased

For `CourseTimeSpec.PeriodBased`:

```text
local start = occurrenceDate + startPeriod.start
local end   = occurrenceDate + endPeriodInclusive.endExclusive
```

Use the same explicit Semester timezone + strict transition-rejection behavior defined in §29.

The resolved base time spans continuously from the first period start through the final period end, including any breaks between periods.

D3 does not split one multi-period class into multiple CourseSessions.

---

# 31. Holiday coverage

Holiday coverage checks the **base occurrence local date** in Semester timezone.

A holiday covers a base occurrence when:

```text
holiday.dates.startDate <= baseLocalDate
AND baseLocalDate < holiday.dates.endDateExclusive
```

With no explicit exception, if at least one covering holiday has:

```text
SUSPEND_TEACHING
```

the resolved session is:

```text
Cancelled(ACADEMIC_HOLIDAY)
```

`NO_EFFECT` never cancels.

Multiple covering holidays do not create duplicate sessions.

---

# 32. Exception precedence

For each base occurrence, locate its zero-or-one exception.

Apply exactly:

```text
if exception.disposition == CANCELLED:
    state = Cancelled(EXPLICIT_EXCEPTION)

else if exception.disposition == ACTIVE:
    effectiveTime = exception.timeOverride ?: baseTime
    effectiveRoom = apply RoomOverride to rule.room
    state = Scheduled(effectiveTime, effectiveRoom)

else if covering SUSPEND_TEACHING holiday exists:
    state = Cancelled(ACADEMIC_HOLIDAY)

else:
    state = Scheduled(baseTime, rule.room)
```

Explicit exception wins over holiday behavior.

Do not re-apply holiday cancellation to an explicit ACTIVE time override.

---

# 33. Semester containment for exact overrides

For D3, `CourseOccurrenceException.timeOverride` must use `semester.timeZone` and be fully contained in the Semester's local date interval.

Interpret containment by converting the override's exact `start` and `endExclusive` back to `LocalDateTime` in `semester.timeZone`.

Valid when:

```text
start local date >= semester.startDate
and
endExclusive is no later than local 00:00 at semester.endDateExclusive
```

Equivalent implementation is allowed, but a class may not start before Semester start or extend past Semester end.

Cross-semester occurrence moves are explicitly not D3 behavior.

---

# 34. Canonical successful output ordering

Successful sessions are sorted by:

```text
1. occurrenceKey.academicWeekNumber.value ascending
2. occurrenceKey.scheduleRuleId.value lexicographically ascending
```

Do not sort canonical resolver output by effective time.

Moving a class must not change the stable resolution order of its logical occurrence.

---

# 35. ExamSchedule

Define exactly:

```kotlin
sealed interface ExamSchedule {
    data object Unscheduled : ExamSchedule

    data class DateOnly(
        val date: LocalDate,
    ) : ExamSchedule

    data class Exact(
        val time: ZonedTimeRange,
    ) : ExamSchedule
}
```

No nullable start/end representation.

No exam-type taxonomy in D3.

---

# 36. Exam

Canonical shape:

```kotlin
data class Exam(
    val id: ExamId,
    val semesterId: SemesterId,
    val courseId: CourseId?,
    val title: String,
    val schedule: ExamSchedule,
)
```

Invariant:

```text
title.isNotBlank()
```

`courseId == null` explicitly means the Exam is not linked to a Course.

D3 does not add study tasks, reminders, location, type, grade/score, planner policy, or Event inheritance.

---

# 37. Exam validator

Implement:

```kotlin
enum class ExamValidationResult {
    VALID,
    EXAM_SEMESTER_MISMATCH,
    LINKED_COURSE_REQUIRED,
    LINKED_COURSE_ID_MISMATCH,
    LINKED_COURSE_SEMESTER_MISMATCH,
    SCHEDULE_OUTSIDE_SEMESTER,
    TIME_ZONE_MISMATCH,
}

fun validateExamAgainstSemester(
    exam: Exam,
    semester: Semester,
    linkedCourse: Course?,
): ExamValidationResult
```

Precedence is exactly the enum order above after `VALID` is excluded; return the first applicable failure in that listed order.

Rules:

1. `exam.semesterId != semester.id` → `EXAM_SEMESTER_MISMATCH`.
2. `exam.courseId != null && linkedCourse == null` → `LINKED_COURSE_REQUIRED`.
3. `exam.courseId != null && linkedCourse!!.id != exam.courseId` → `LINKED_COURSE_ID_MISMATCH`.
4. linked Course exists but `linkedCourse.semesterId != exam.semesterId` → `LINKED_COURSE_SEMESTER_MISMATCH`.
5. `DateOnly` date outside `[semester.startDate, semester.endDateExclusive)` → `SCHEDULE_OUTSIDE_SEMESTER`.
6. `Exact.time.timeZone != semester.timeZone` → `TIME_ZONE_MISMATCH`.
7. Exact time not fully contained in Semester local date bounds → `SCHEDULE_OUTSIDE_SEMESTER`.
8. otherwise → `VALID`.

If `exam.courseId == null`, `linkedCourse` is ignored for semantic linkage; callers should normally pass null.

`Unscheduled` is valid with respect to date bounds/timezone.

---

# 38. Academic source of truth

D3 freezes this relationship:

```text
AcademicYear
    │
Semester
    ├─ explicit AcademicWeek list
    └─ explicit TimeZone
          │
Course ───┤
  │       │
  ▼       │
CourseScheduleRule
  │
  ├─ TeachingWeekSet
  ├─ DayOfWeek
  ├─ ClockTime OR PeriodBased ──> PeriodTemplate
  └─ base room
        │
        ├────────── AcademicHoliday
        │
        └────────── CourseOccurrenceException
                         │
                         ▼
                 deterministic resolver
                         │
                         ▼
                    CourseSession
                    DERIVED ONLY
```

No alternate authoritative CourseSession mutation path is allowed.

---

# 39. D3 equality / identity

Logical identity:

```text
AcademicYear            → AcademicYearId
Semester                → SemesterId
Course                  → CourseId
CourseScheduleRule      → CourseScheduleRuleId
AcademicHoliday         → AcademicHolidayId
CourseOccurrenceException → CourseOccurrenceExceptionId
Exam                    → ExamId
PeriodTemplate          → PeriodTemplateId
CourseSession           → CourseOccurrenceKey
AcademicWeek            → Semester + AcademicWeekNumber
AcademicPeriod          → PeriodTemplate + AcademicPeriodNumber
```

Structural equality may still be used to compare complete immutable snapshots.

---

# 40. D3 validation ownership

```text
UUIDv7 syntax                         typed ID construction
AcademicWeekNumber >= 1              value construction
AcademicPeriodNumber >= 1            value construction
AcademicWeek exactly 7 days           AcademicWeek construction
Semester internal week consistency    Semester construction
AcademicYear date range               AcademicYear construction
Course text validity                  Course construction
TeachingWeekSet canonicalization      TeachingWeekSet factory
AcademicPeriod local range            AcademicPeriod construction
PeriodTemplate internal consistency   PeriodTemplate construction
ClockTime ordering                    CourseTimeSpec.ClockTime construction
PeriodBased period-number ordering    CourseTimeSpec.PeriodBased construction
CourseScheduleRule room validity      rule construction
RoomOverride.Set text validity        override construction
CANCELLED override contradiction      CourseOccurrenceException construction
cross-entity course/rule/week refs    CourseSession resolver
period-template lookup                CourseSession resolver
holiday semester/range checks         CourseSession resolver
exception target/precedence           CourseSession resolver
DST local-time ambiguity              CourseSession resolver
Semester within AcademicYear          membership validator
Exam linked-course/date/timezone      Exam validator
Calendar overlap conflict             NOT D3
Planner movement/occupancy            NOT D3
DB foreign keys                       NOT D3
```

---

# 41. Required tests — IDs

Add tests that every new ID family:

- accepts a valid canonical lowercase UUIDv7;
- rejects invalid UUID text/v4/uppercase/wrong variant;
- remains a distinct Kotlin type.

No generator test because D3 still has no production generator.

---

# 42. Required tests — Academic calendar

At minimum:

```text
AcademicYear valid range accepted
AcademicYear blank name rejected
AcademicYear zero/reversed range rejected
AcademicWeekNumber 0/negative rejected
AcademicWeek exactly seven days accepted
AcademicWeek six/eight/zero days rejected
Semester valid consecutive weeks accepted
Semester with a date gap between Week 2 and Week 3 accepted
Semester empty weeks rejected
Semester Week 1 missing rejected
Semester non-consecutive numbers rejected
Semester unsorted numbers rejected
Semester overlapping week ranges rejected
Semester week outside Semester rejected
Semester name blank rejected
Semester-vs-AcademicYear validator covers all three results
overlapping separate Semesters are not globally rejected
```

---

# 43. Required tests — TeachingWeekSet

At minimum:

```text
empty rejected
single week accepted
unordered values canonicalized ascending
duplicate values deduplicated
equivalent input order/duplicates => equal value
odd-week style explicit set remains explicit 1,3,5,...
```

Do not add text parsing tests.

---

# 44. Required tests — periods/time specs

At minimum:

```text
AcademicPeriod normal accepted
zero/reversed AcademicPeriod rejected
PeriodTemplate normal accepted
adjacent periods accepted
gaps between periods accepted
overlapping periods rejected
unsorted period numbers rejected
time order contradicting period order rejected
non-consecutive period numbers allowed
ClockTime valid accepted
ClockTime zero/reversed rejected
PeriodBased single period accepted
PeriodBased multi-period accepted
PeriodBased start > end rejected
```

---

# 45. Required tests — Course/rule/occurrence

At minimum:

```text
Course normal and null code accepted
blank Course name rejected
blank non-null code rejected
CourseScheduleRule null room accepted
blank non-null room rejected
one Course can have multiple independent rules
CourseOccurrenceKey equality depends only on ruleId + week number
moving time does not alter occurrence key
RoomOverride Unchanged/Set/Clear semantics
blank RoomOverride.Set rejected
CANCELLED exception with time override rejected
CANCELLED exception with room override rejected
ACTIVE exception with no overrides accepted
```

---

# 46. Required tests — resolver success semantics

Create deterministic fixtures covering:

```text
ClockTime rule resolves each selected explicit academic week
PeriodBased rule resolves through PeriodTemplate
Week dates with a Semester gap resolve from explicit AcademicWeek date, not arithmetic
same Course with two weekly rules creates two independent occurrences
base room preserved
ACTIVE time override moves occurrence but keeps CourseOccurrenceKey/baseTime
RoomOverride.Set changes effective room
RoomOverride.Clear removes effective room
RoomOverride.Unchanged preserves base room
explicit CANCELLED produces Cancelled(EXPLICIT_EXCEPTION)
SUSPEND_TEACHING holiday produces Cancelled(ACADEMIC_HOLIDAY)
NO_EFFECT holiday does not cancel
explicit ACTIVE with no override reinstates holiday-suppressed base session
explicit ACTIVE moved onto holiday remains scheduled
explicit CANCELLED beats holiday
multiple covering holidays create one logical CourseSession
resolver allows two different rules to produce overlapping sessions
canonical output ordering follows week number then rule UUID text
input Collection iteration order does not alter output
```

---

# 47. Required tests — resolver invalid input

Cover every public issue case at least once:

```text
CourseSemesterMismatch
DuplicateRuleId
RuleCourseMismatch
UnknownAcademicWeek
DuplicatePeriodTemplateId
UnknownPeriodTemplate
UnknownStartPeriod
UnknownEndPeriod
DuplicateHolidayId
HolidaySemesterMismatch
HolidayOutsideSemester
DuplicateExceptionId
DuplicateExceptionTarget
OrphanException
ExceptionTimeZoneMismatch
ExceptionOutsideSemester
DstTransitionRejected
```

For multiple simultaneous issues, assert deterministic issue ordering.

The resolver must return no partial successful session list when invalid.

---

# 48. Required DST tests

Use a real IANA timezone with known transitions, such as `America/New_York`, and prove all three strict-resolution cases:

```text
spring-forward nonexistent wall time
    → Invalid(DstTransitionRejected(...))

fall-back ambiguous wall time
    → Invalid(DstTransitionRejected(...))

normal wall time with exactly one valid Instant
    → resolves successfully
```

The tests must demonstrate that resolution does not silently shift a nonexistent time and does not silently select either offset for an ambiguous time.

Do not mock the timezone rule into a different semantic behavior.

---

# 49. Required tests — Exam

At minimum:

```text
Exam Unscheduled accepted
Exam DateOnly accepted
Exam Exact accepted
blank title rejected
courseId null explicitly valid
validator: Exam Semester mismatch
validator: linked Course required
validator: linked Course ID mismatch
validator: linked Course belongs to another Semester
validator: DateOnly inside Semester valid
validator: DateOnly before/after Semester invalid
validator: Exact same Semester timezone valid
validator: Exact timezone mismatch invalid
validator: Exact outside Semester invalid
Unscheduled does not fail date/timezone validation
```

---

# 50. Determinism/static checks

Production D3 commonMain must contain no hidden source equivalent to:

```text
Clock.System.now()
System.currentTimeMillis()
Instant.now()
UUID.randomUUID()
TimeZone.currentSystemDefault()
random iteration/tie breaking
```

Resolver results must be reproducible from the exact same immutable inputs.

---

# 51. Build/gate commands

D3 must pass at minimum:

```bash
./gradlew :shared:domain:build --no-daemon
./gradlew build --no-daemon
```

CI must be green.

The existing unrelated Android host-test warning is not a D3 blocker as long as common tests execute through the configured Desktop KMP test target. D3 must not introduce new warnings and normalize them away without justification.

---

# 52. D3 acceptance checklist

D3 Gate passes only if all are true:

```text
[ ] exact eight new typed UUIDv7 ID families implemented
[ ] AcademicYear implemented exactly
[ ] AcademicWeekNumber implemented exactly
[ ] AcademicWeek exactly-seven-day invariant implemented
[ ] Semester exact fields + week invariants implemented
[ ] SemesterAcademicYear validator implemented
[ ] Course exact fields implemented
[ ] TeachingWeekSet canonical factory/equality implemented
[ ] AcademicPeriodNumber / AcademicPeriod implemented
[ ] PeriodTemplate exact invariants implemented
[ ] CourseTimeSpec has exactly ClockTime / PeriodBased D3 variants
[ ] CourseScheduleRule exact fields implemented
[ ] CourseOccurrenceKey stable identity implemented
[ ] RoomOverride has exactly Unchanged / Set / Clear
[ ] CourseOccurrenceException exact semantics implemented
[ ] AcademicHoliday exact behavior model implemented
[ ] CourseSession exact derived state model implemented
[ ] CourseSession resolver returns Success or non-empty Invalid issues
[ ] every resolver issue case implemented/tested
[ ] explicit exception > holiday > base rule precedence implemented
[ ] strict DST transition rejection (nonexistent + ambiguous) implemented/tested
[ ] resolver output deterministic and input-order independent
[ ] CourseSession has no independent generated ID
[ ] CourseSession is not made an authoritative mutable source
[ ] ExamSchedule exactly Unscheduled / DateOnly / Exact
[ ] Exam exact D3 fields implemented
[ ] Exam validator implemented/tested
[ ] no Planner defaults attached to academic entities
[ ] no DB/repository/import/UI/Sync/Agent/server code introduced
[ ] no new third-party dependency
[ ] required common tests pass
[ ] repository-wide build passes
[ ] CI green
```

A successful compile without the semantic/resolver requirements is not a D3 pass.

---

# 53. Explicitly deferred beyond D3

These are deliberate non-features, not TODOs for the Coding Agent:

```text
course instructors
credits
grades
colors
generic Location integration
attachments
course reminders
exam-type taxonomy
exam study-task linkage
school timetable import
CSV/Excel/ICS academic import
screenshot OCR/LLM import
school-system adapters
shared institutional holiday calendars
cross-semester occurrence moves
semester archive/current selection persistence
Calendar occupancy mapping
Planner HARD/FLEXIBLE/SOFT mapping
conflict detection
Room schema / DAO / repository
CourseSession cache/materialized view
Sync serialization/merge
external calendar mapping
Agent tools
```

---

# 54. Coding Agent escalation rule

If implementation discovers a choice not answered by this task:

```text
Is it local, reversible, and invisible to public/cross-module semantics?
    YES → use the simplest deterministic implementation.
    NO  → DO NOT GUESS.
          Mark BLOCKED_BY_DECISION.
          State the exact missing decision.
          Continue only independent safe work.
```

Examples:

```text
"Should Course have instructor now?"                 → NO, explicitly deferred.
"Should odd/even weeks become an enum?"             → NO, canonical TeachingWeekSet fixed.
"Should CourseSession get a UUID anyway?"           → NO, occurrence identity fixed.
"Should a holiday cancel because its name says so?" → NO, structured teachingEffect only.
"Which DST offset should ambiguous time choose?"     → neither; reject transition.
"Should ACTIVE override be re-cancelled by holiday?"→ NO, exception precedence fixed.
"Should resolver ignore an orphan exception?"       → NO, explicit Invalid issue.
"Can the internal weekday lookup use a 0..6 loop?"  → YES, local/reversible if deterministic.
```

---

# 55. D3 definition of done

D3 is complete when Academic schedule facts can represent real semester/course timetables, selected teaching weeks, custom period schedules, holidays, cancellations, room changes, and one-off rescheduling **without** pretending that a Course is a repeating Event and without creating competing sources of truth.

The same D3 input must always produce the same CourseSession projections or the same structured invalidity.

**Coding agents implement Academic truth; they do not invent academic policy while doing so.**
