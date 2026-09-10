# Agentic Scheduler — D3 Academic Decisions

> Status: **RESOLVED / FROZEN for D3**  
> Purpose: record Academic-domain decisions that would otherwise become coding-agent guesswork.

These decisions are implementation-authorizing decisions for D3. They refine, but do not replace, `docs/DOMAIN_INVARIANTS.md` and `docs/ACADEMIC_INVARIANTS.md`.

---

## AD-001 — Academic week model

```text
Status: RESOLVED
Decision:
- Semester owns an explicit non-empty ordered list of AcademicWeek values.
- AcademicWeekNumber is a positive integer value type.
- Semester week numbers are consecutive starting at 1.
- Every AcademicWeek is exactly seven calendar days, half-open.
- Date gaps between numbered weeks are allowed.
- Course rules select weeks through canonical TeachingWeekSet.
- Academic weeks are never inferred by fixed Instant arithmetic.
Impact: CONTRACT_AFFECTING
```

Rationale: institutions may have breaks that are not counted as teaching weeks, while course rules still need stable week-number semantics.

---

## AD-002 — TeachingWeekSet canonical representation

```text
Status: RESOLVED
Decision:
- Domain stores explicit AcademicWeekNumber values only.
- ODD / EVEN / 1-16 are input syntaxes, not persistent Domain variants.
- TeachingWeekSet.of(...) sorts ascending and removes duplicates canonically.
- Empty TeachingWeekSet is invalid.
Impact: CONTRACT_AFFECTING / SYNC_FRIENDLY
```

Rationale: equivalent inputs should produce equivalent semantic state and later semantic diffs.

---

## AD-003 — Course rule cardinality and occurrence identity

```text
Status: RESOLVED
Decision:
- One CourseScheduleRule contains one DayOfWeek and one CourseTimeSpec.
- It creates at most one base occurrence per selected academic week.
- Courses meeting multiple times per week use multiple rules.
- Logical occurrence identity is CourseOccurrenceKey(scheduleRuleId, academicWeekNumber).
- D3 does not generate CourseSessionId.
Impact: ARCHITECTURE_AFFECTING
```

Rationale: this removes ambiguity in one-off exception targeting and keeps identity stable across rescheduling.

---

## AD-004 — Course wall-clock time model

```text
Status: RESOLVED
Decision:
CourseTimeSpec has exactly two D3 variants:
1. ClockTime(start: LocalTime, endExclusive: LocalTime)
2. PeriodBased(periodTemplateId, startPeriod, endPeriodInclusive)

- ClockTime is same-local-date only in D3; start < endExclusive.
- Period numbers are positive value types.
- PeriodTemplate explicitly maps period numbers to local clock ranges.
- Academic periods are ordered and non-overlapping; gaps allowed.
Impact: CONTRACT_AFFECTING
```

No period number has globally fixed time semantics.

---

## AD-005 — Semester timezone and DST resolution

```text
Status: RESOLVED
Decision:
- Semester owns an explicit TimeZone.
- Base CourseScheduleRule time is wall-clock time resolved in Semester.timeZone.
- No system-default timezone may be consulted implicitly.
- Ambiguous/nonexistent DST local times use strict transition-rejection semantics.
- Resolver reports an explicit issue instead of choosing an offset or shifting time.
Impact: CONTRACT_AFFECTING / DETERMINISM
```

The D3 baseline is `kotlinx-datetime 0.8.0`, which does not expose `TransitionHandler` as a usable public API for this resolver. Therefore the contract is semantic rather than API-specific: exactly one valid Instant must exist for a requested local wall time. Nonexistent and ambiguous local times are both rejected deterministically. A future library primitive may replace the internal mechanism only if it preserves these semantics.

---

## AD-006 — CourseSession source of truth

```text
Status: RESOLVED
Decision:
CourseSession is a deterministic derived projection.
Authoritative state is:
Semester + AcademicWeek definitions
+ Course
+ CourseScheduleRule
+ PeriodTemplate
+ AcademicHoliday
+ CourseOccurrenceException

CourseSession is not independently edited in D3.
Impact: ARCHITECTURE_AFFECTING
```

A future persistence cache may materialize CourseSession only as a rebuildable projection.

---

## AD-007 — Occurrence exception model

```text
Status: RESOLVED
Decision:
CourseOccurrenceException contains:
- immutable exception ID
- CourseOccurrenceKey target
- CourseOccurrenceDisposition: ACTIVE | CANCELLED
- optional exact ZonedTimeRange override
- RoomOverride: Unchanged | Set | Clear

CANCELLED carries no time/room override.
ACTIVE with no override is valid and can reinstate a holiday-suppressed occurrence.
Impact: CONTRACT_AFFECTING
```

A one-off change never mutates the base rule.

---

## AD-008 — Academic exception precedence

```text
Status: RESOLVED
Decision:
1. explicit CourseOccurrenceException
2. AcademicHoliday teaching suspension
3. base CourseScheduleRule

Explicit ACTIVE reinstates/schedules even on a holiday.
Explicit CANCELLED always cancels.
Without an explicit exception, any covering SUSPEND_TEACHING holiday cancels the base occurrence.
Impact: CONTRACT_AFFECTING
```

Holiday names have no behavioral meaning.

---

## AD-009 — AcademicHoliday scope and behavior

```text
Status: RESOLVED
Decision:
- AcademicHoliday is Semester-scoped in D3.
- date range uses AllDayRange.
- teaching effect is exactly NO_EFFECT | SUSPEND_TEACHING.
- its date range must be inside the Semester when used by the resolver.
Impact: CONTRACT_AFFECTING
```

Cross-semester/shared institutional holiday calendars are deferred.

---

## AD-010 — CourseSession state

```text
Status: RESOLVED
Decision:
CourseSession contains:
- CourseOccurrenceKey
- CourseId
- baseTime: ZonedTimeRange
- state: CourseSessionState

CourseSessionState:
- Scheduled(time: ZonedTimeRange, room: String?)
- Cancelled(reason)

CancellationReason:
- ACADEMIC_HOLIDAY
- EXPLICIT_EXCEPTION
Impact: CONTRACT_AFFECTING
```

Cancellation is never represented as `time = null`.

---

## AD-011 — Resolver invalid-input policy

```text
Status: RESOLVED
Decision:
Cross-entity academic inconsistencies are explicit resolver/validator failures.
The resolver must not silently drop malformed rules, exceptions, holidays, or references.
Input collection order must not change output or issue ordering.
Impact: DETERMINISM / CORRECTNESS
```

At minimum D3 reports:

```text
course/semester mismatch
rule/course mismatch
unknown teaching week
unknown period template
unknown referenced period
holiday/semester mismatch
holiday outside semester
duplicate entity IDs in resolver collections
duplicate exception target
orphan exception
exception timezone mismatch
exception outside semester
DST transition rejection
```

---

## AD-012 — Resolver ordering

```text
Status: RESOLVED
Decision:
Successful CourseSession output canonical order:
1. AcademicWeekNumber ascending
2. CourseScheduleRuleId canonical text ascending
Impact: DETERMINISM
```

Ordering follows stable occurrence identity, not effective time. UI may later sort by effective scheduled time.

---

## AD-013 — Course model D3 surface

```text
Status: RESOLVED
Decision:
Course D3 fields are exactly:
- CourseId
- SemesterId
- name: String
- code: String?

name must be non-blank.
code may be null; if non-null it must be non-blank.
No instructor, color, credits, tags, attachments, sync metadata, or planner policy in D3.
Impact: CONTRACT_AFFECTING
```

---

## AD-014 — Semester model D3 surface

```text
Status: RESOLVED
Decision:
Semester D3 fields are exactly:
- SemesterId
- AcademicYearId
- name
- startDate
- endDateExclusive
- TimeZone
- academicWeeks

No embedded Course list.
No current/default-semester flag.
No archive field in D3.
Impact: CONTRACT_AFFECTING
```

Historical preservation is a non-destructive invariant; lifecycle/persistence details are deferred.

---

## AD-015 — AcademicYear model D3 surface

```text
Status: RESOLVED
Decision:
AcademicYear D3 fields are exactly:
- AcademicYearId
- name
- startDate
- endDateExclusive

No embedded Semester list.
Overlapping Semesters are not globally forbidden.
Impact: CONTRACT_AFFECTING
```

---

## AD-016 — Exam model D3 surface

```text
Status: RESOLVED
Decision:
Exam fields:
- ExamId
- SemesterId
- optional CourseId
- title
- ExamSchedule

ExamSchedule:
- Unscheduled
- DateOnly(LocalDate)
- Exact(ZonedTimeRange)

No exam-type enum, study-plan fields, reminders, Event inheritance, or planner policy in D3.
Impact: CONTRACT_AFFECTING
```

Cross-entity validation requires course-linked Exams to reference a Course in the same Semester. Scheduled exam dates/times must fall inside the Semester, and Exact uses the Semester timezone.

---

## AD-017 — Academic entities do not imply Planner movement defaults

```text
Status: RESOLVED
Decision:
D3 does not add Flexibility/PinState/default occupancy to CourseSession or Exam.
Planner/Calendar integration will explicitly map Academic facts to scheduling policy later.
Impact: BOUNDARY_AFFECTING
```

This prevents hidden assumptions such as "all classes are HARD" from leaking into Academic truth.

---

# Deferred Academic decisions

The following are deliberately **not** part of D3 and must not be guessed:

```text
school-system adapter schemas
screenshot/import parsing syntax
academic calendar import conflict policy
course instructors / credits / colors
generic location entity integration
shared/cross-semester holiday calendars
cross-semester course occurrence moves
exam-type taxonomy
course reminders
planner occupancy/flexibility mapping
persistence/materialized CourseSession cache strategy
external calendar mapping
```

A future task may add them only after freezing their semantics.

---

# D3 authorization rule

D3 implementation is authorized only inside the exact surface defined by `docs/tasks/D3_ACADEMIC_DOMAIN.md`.

If code requires changing any decision above:

```text
DO NOT GUESS
→ mark BLOCKED_BY_DECISION
→ record the missing/contradictory decision
→ continue only independent safe work
```
