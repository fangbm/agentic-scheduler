# Agentic Scheduler — Academic Domain Invariants

> Status: **Frozen Baseline v1**  
> Applies from: **D3 Academic Domain**  
> Parent contracts: `docs/DOMAIN_INVARIANTS.md`, `docs/IMPLEMENTATION_CONTRACT.md`.

This document removes Academic-domain implementation ambiguity before D3 coding begins. If this document conflicts with a lower-priority implementation pattern, this document wins. If a future requirement needs to violate one of these invariants, change it deliberately through an ADR/task-contract update; do not let a coding agent silently reinterpret the model.

---

## 1. AcademicYear, Semester, Course, Rule, and Session are different concepts

The academic hierarchy is semantic, not a convenience wrapper around Event recurrence:

```text
AcademicYear
    ↓
Semester
    ↓
Course
    ↓
CourseScheduleRule
    ↓
CourseOccurrenceKey
    ↓
CourseSession (resolved projection)
```

Permanent false equivalences:

```text
Course == repeating Event                  FALSE
CourseScheduleRule == RecurrenceRule       FALSE
CourseSession == Event occurrence          FALSE
AcademicHoliday == Event                   FALSE
Exam == Event(type = EXAM)                 FALSE
```

---

## 2. Semester owns the academic timezone

Every Semester carries an explicit `TimeZone`.

Course rules preserve wall-clock semantics. They are resolved in the Semester timezone. No Academic-domain code may use `TimeZone.currentSystemDefault()` as an implicit default.

A user travelling to another timezone does not change the academic schedule's meaning.

---

## 3. Academic weeks are explicit calendar ranges

Academic week numbers are not inferred from `semesterStart + N × 7 days`.

A Semester explicitly contains numbered `AcademicWeek` values. Every AcademicWeek is exactly seven calendar days and is represented as a half-open range:

```text
[startDate, endDateExclusive)
```

The numbered weeks are consecutive starting at 1, but their date ranges may contain gaps between weeks. A gap can represent a break that is not counted as an academic teaching week.

Example:

```text
Week 1  Sep 21 → Sep 28
Week 2  Sep 28 → Oct 05
          <break not counted as a week>
Week 3  Oct 12 → Oct 19
```

This means week arithmetic is never allowed to assume that AcademicWeek 3 begins exactly 14 days after AcademicWeek 1.

---

## 4. TeachingWeekSet is the only canonical stored week-selection form

Course rules do not persist separate semantic forms such as:

```text
ODD
EVEN
1-16
1,3,5,7...
```

Those may be accepted by import/UI parsing later, but the Domain representation is a canonical set of explicit `AcademicWeekNumber` values.

For example, "odd weeks 1–16" becomes:

```text
1, 3, 5, 7, 9, 11, 13, 15
```

A `TeachingWeekSet` is non-empty, unique, and ordered ascending canonically.

Equivalent user input must therefore converge to equivalent Domain state.

---

## 5. One CourseScheduleRule means at most one base occurrence per academic week

A CourseScheduleRule has exactly one:

```text
Course
DayOfWeek
TeachingWeekSet
CourseTimeSpec
base room (optional)
```

If a Course meets more than once per week, it has multiple CourseScheduleRules.

Do not add a list of weekdays/times inside one rule in D3.

This makes occurrence identity unambiguous.

---

## 6. Academic wall-clock time has exactly two D3 source forms

Course rules express time as one of:

```text
CourseTimeSpec
├─ ClockTime
└─ PeriodBased
```

`ClockTime` contains a local start and local exclusive end.

`PeriodBased` references a `PeriodTemplate` plus start/end period numbers.

A period number is metadata. It is never globally equivalent to a fixed clock time.

---

## 7. PeriodTemplate is an explicit mapping, not a formula

A PeriodTemplate contains concrete `AcademicPeriod` entries mapping period numbers to local clock ranges.

Example:

```text
Period 1  08:30–09:15
Period 2  09:25–10:10
Period 3  10:25–11:10
```

Academic periods are ordered, non-overlapping, and individually valid half-open local-time ranges.

Gaps between periods are allowed.

A CourseScheduleRule referencing a missing template or missing referenced period is invalid input to resolution. The resolver must report it; it must not guess a clock time.

---

## 8. Base course occurrences use wall-clock recurrence semantics

For each selected AcademicWeek, a rule chooses the unique date inside that explicit seven-day week matching its `DayOfWeek`, combines that date with its clock/period time, and resolves it using the Semester timezone.

It must not obtain later occurrences by adding fixed 7×24-hour Instant durations to an earlier occurrence.

---

## 9. DST transitions are never guessed

Converting academic wall-clock time to exact Instants must reject ambiguous or nonexistent local date-time transitions rather than silently choosing an offset.

The D3 dependency baseline is `kotlinx-datetime 0.8.0`, which does not expose `TransitionHandler` as a usable public API for this resolver. Therefore D3 requires **strict transition-rejection semantics**, not a particular library call: the implementation must explicitly detect both nonexistent wall times and ambiguous wall times and reject either case deterministically.

A valid implementation may use round-trip validation plus explicit alternative-offset detection, or a future library primitive with equivalent reject semantics after a deliberate dependency/API update. It must never silently choose an earlier/later offset or shift a nonexistent local time.

If a base occurrence lands in an unresolved DST transition, course resolution returns an explicit resolution issue. A coding agent must not choose "earlier offset", "later offset", or shift the local time automatically.

---

## 10. CourseOccurrenceKey is the stable identity of a logical session

A logical course occurrence is identified by:

```text
CourseOccurrenceKey(
    scheduleRuleId,
    academicWeekNumber,
)
```

No random `CourseSessionId` is generated for resolved sessions in D3.

Moving a session to another day/time, changing its room, cancelling it, or reinstating it does not change the CourseOccurrenceKey.

The current start time must never be used as occurrence identity.

---

## 11. CourseSession is a deterministic projection, not an independent write source

The authoritative Academic schedule state is:

```text
Semester academic weeks
+ Course
+ CourseScheduleRule
+ PeriodTemplate
+ AcademicHoliday
+ CourseOccurrenceException
```

`CourseSession` is derived from those inputs.

```text
Authoritative academic state
          ↓
 deterministic resolver
          ↓
     CourseSession
```

D3 must not create a second independently editable CourseSession table/model that can disagree with its rules and exceptions.

A later database layer may cache resolved sessions for performance only if the cache remains disposable/rebuildable and never becomes the semantic source of truth.

---

## 12. A one-off change is an occurrence exception

Changing one class occurrence must not mutate the whole CourseScheduleRule.

Examples:

```text
one class cancelled
one class moved to Wednesday
one class moved to another room
holiday make-up class reinstated
```

are represented by `CourseOccurrenceException` targeting the stable CourseOccurrenceKey.

---

## 13. CourseOccurrenceException precedence is exact

For one base occurrence, D3 precedence is:

```text
1. Explicit CourseOccurrenceException
2. AcademicHoliday teaching suspension
3. Base CourseScheduleRule
```

Resolution behavior:

```text
explicit CANCELLED exception
    → cancelled regardless of holiday/base state

explicit ACTIVE exception
    → scheduled regardless of holiday suspension
    → apply explicit time/room overrides when present

no explicit exception + SUSPEND_TEACHING holiday
    → cancelled by holiday

otherwise
    → scheduled from base rule
```

An ACTIVE exception with no field override is valid because it can explicitly reinstate a base occurrence that a holiday would otherwise suppress.

If an ACTIVE exception moves a class onto a holiday date, the explicit exception still wins.

---

## 14. Room override must distinguish unchanged, set, and clear

`null` cannot simultaneously mean both:

```text
leave the base room unchanged
```

and:

```text
remove the room
```

Therefore occurrence room override has explicit tri-state semantics:

```text
RoomOverride
├─ Unchanged
├─ Set(value)
└─ Clear
```

`Set(value)` requires non-blank text.

Base rule `room: String?` may use `null` to mean that no base room is known/assigned, because there is no competing "unchanged" meaning at that level.

---

## 15. Cancellation and rescheduling do not erase the base occurrence

A resolved CourseSession always retains its `baseTime` even if its current state is cancelled or moved.

This allows deterministic explanation and later history/UI behavior such as:

> "Week 7's Monday class was moved to Wednesday."

The base occurrence is the rule-derived reference point; the effective scheduled time may differ through an exception.

---

## 16. AcademicHoliday behavior comes from structured semantics, never its name

An AcademicHoliday has an explicit teaching effect:

```text
NO_EFFECT
SUSPEND_TEACHING
```

The system must not infer cancellation because the title contains words like "holiday", "vacation", or "National Day".

A `NO_EFFECT` holiday is descriptive calendar metadata only for D3 course resolution.

---

## 17. Holidays are Semester-scoped in D3

An AcademicHoliday belongs to a Semester and its date range must be contained within that Semester's date range when used for course resolution.

School-wide/cross-semester reusable holiday calendars may be introduced later through an explicit model change. D3 does not guess that relationship.

---

## 18. CourseSession cancellation is explicit state

Resolved session state is not represented by `time = null`.

Conceptually:

```text
CourseSessionState
├─ Scheduled(time, room)
└─ Cancelled(reason)
```

Cancellation reason in D3 distinguishes at least:

```text
ACADEMIC_HOLIDAY
EXPLICIT_EXCEPTION
```

---

## 19. Invalid cross-entity references are reported, not ignored

The resolver must not silently skip malformed academic input.

Examples include:

```text
Course references another Semester
Rule references another Course
Rule references AcademicWeek not present in Semester
PeriodBased rule references unknown template/period
Holiday references another Semester
OccurrenceException targets no base occurrence
Multiple exceptions target the same occurrence
Exception time uses a different timezone from Semester
```

These produce explicit deterministic resolution issues.

"Best effort" dropping of invalid academic facts is forbidden in D3.

---

## 20. Multiple rules may resolve to overlapping sessions

Academic Domain may faithfully represent a real conflicting timetable.

D3 does not reject two CourseSessions merely because they overlap.

Conflict detection belongs to later Calendar/Planner validation.

---

## 21. Exception time may move inside the Semester, not outside it in D3

A time override may move an occurrence to another day or another academic week, but its effective local date in the Semester timezone must remain inside:

```text
[semester.startDate, semester.endDateExclusive)
```

Cross-semester course moves require a future explicit model decision rather than implicit behavior.

---

## 22. CourseSession resolver output order is deterministic

Resolver output must not depend on input collection iteration order.

Canonical D3 ordering is:

```text
AcademicWeekNumber ascending
then CourseScheduleRuleId canonical text ascending
```

Rescheduling an occurrence does not alter its canonical resolver ordering because ordering follows occurrence identity, not mutable effective time.

A UI may sort the resulting sessions by effective time for display.

---

## 23. Semester week definitions are canonical and explicit

A Semester's week list:

- is non-empty;
- starts at AcademicWeekNumber 1;
- uses consecutive week numbers;
- is already in ascending number order;
- contains no overlapping week date ranges;
- keeps every AcademicWeek within the Semester date range.

The constructor does not silently reorder malformed Semester week input.

Date gaps between consecutive numbered academic weeks are valid.

---

## 24. AcademicYear contains time scope, not duplicated child lists

D3 `AcademicYear` provides identity, name, and date range. `Semester` points to `AcademicYearId`.

D3 does not also store a mutable `List<SemesterId>` on AcademicYear because that would create two relationship sources of truth before persistence/repository ownership is defined.

The same one-direction ownership rule applies to Course: Course points to Semester; Semester does not embed mutable Course lists in D3.

---

## 25. Semester overlap is not globally forbidden

Two Semesters in the same AcademicYear are not rejected merely because their date ranges overlap. Real institutions may have overlapping terms, short sessions, or special programs.

Whether one Semester is selected as the user's current/default Semester is application state, not an AcademicYear invariant.

---

## 26. Historical Semester preservation is non-destructive

Archiving/history behavior must never mean deleting or rewriting academic identity.

D3 does not add an archive lifecycle field yet. Later archival UX/persistence may hide a historical Semester from default views, but:

```text
AcademicYearId
SemesterId
CourseId
CourseScheduleRuleId
CourseOccurrenceKey
```

remain stable.

---

## 27. Exam is a first-class academic entity with explicit schedule state

D3 Exam schedule has exactly three semantic forms:

```text
ExamSchedule
├─ Unscheduled
├─ DateOnly
└─ Exact
```

Do not represent unknown exam time using nullable `start/end` fields.

An Exam may optionally reference a Course. A missing Course reference means the Exam is not course-linked; it does not mean "unknown course" unless capture/inbox semantics explicitly say so elsewhere.

---

## 28. Exam cross-entity semantics are explicit

When an Exam references a Course:

- the Course must exist;
- that Course must belong to the same Semester as the Exam.

For scheduled Exams in D3:

- a DateOnly date must fall inside the Semester date range;
- an Exact schedule uses the Semester timezone;
- its effective local date must fall inside the Semester date range.

The Semester range should therefore include its exam period where the institution treats exams as part of that Semester.

---

## 29. Academic entities do not silently become Planner policy

D3 models academic truth. It does not yet decide Planner scoring or movement authority by adding hidden defaults such as:

```text
all classes are HARD
all exams are PINNED
all holidays block the whole day
```

Later Planner/Calendar integration must explicitly map academic facts to occupancy/flexibility policy.

This keeps Academic Domain facts separate from Planner policy.

---

## 30. D3 remains pure Domain

The Academic resolver and validators are deterministic pure logic under `:shared:domain`.

D3 must not introduce:

```text
Room / SQLite
repositories
network
serialization annotations
SyncOperation / HLC / DVV
Agent / LLM
UI
platform APIs
system clock reads
random ID generation
```

All IDs, dates, timezones, templates, holidays, and exceptions are explicit inputs.

---

# Academic source-of-truth summary

```text
AcademicYear
     │
     ▼
Semester ──────────────┐
 │ explicit weeks      │ explicit TimeZone
 │                     │
 ▼                     │
Course                  │
 │                      │
 ▼                      │
CourseScheduleRule      │
 │ weekday              │
 │ TeachingWeekSet      │
 │ Clock/Period time    │
 │ base room            │
 │                      │
 ├─────────────┐        │
 │             │        │
 ▼             ▼        ▼
PeriodTemplate Holidays / Exceptions
        \        |        /
         \       |       /
          └──────┼──────┘
                 ▼
      deterministic resolver
                 ▼
          CourseSession
       (derived projection)
```

The central rule is:

> **Academic schedule facts are explicit; occurrence resolution is deterministic; one-off reality is represented by exceptions, never by silently rewriting the base rule.**
