# Agentic Scheduler — D5 Calendar Surface Baseline

> Task ID: **D5-01**  
> Title: **Calendar Projection & Agenda/Day Surface**  
> Milestone: **D5 — Calendar / Application Surface**  
> Status: **IMPLEMENTED / VERIFIED / COMPLETE**
> Date: 2026-09-11
> Final implementation revision: `2db9836` (`fix: classify cross midnight Wear items by intersection`)

---

# 1. Goal

Build the first useful local calendar surface on top of the completed D2/D3/D4 foundations without entering Planner, Sync, Agent, or production-creation scope.

D5-01 establishes:

```text
repository-backed calendar projection
+ deterministic mixed-source ordering
+ derived CourseSession integration
+ concrete-range conflict detection
+ structured projection issues
+ Android/Desktop Agenda/Day UI
+ Wear read-only upcoming agenda
```

The milestone proof is:

```text
persisted authoritative source facts
        ↓ repositories
shared application calendar projection
        ↓ deterministic merge/derive
same semantic Agenda/Day result
        ↓
Android / Desktop / Wear rendering
```

---

# 2. Required reading

Implementation begins by reading:

```text
[x] AGENTS.md
[x] docs/READ_FIRST.md
[x] docs/DOMAIN_INVARIANTS.md
[x] docs/ACADEMIC_INVARIANTS.md
[x] docs/ACADEMIC_DECISIONS.md
[x] docs/CALENDAR_DECISIONS.md
[x] docs/OPEN_DECISIONS.md
[x] docs/IMPLEMENTATION_CONTRACT.md
[x] docs/ARCHITECTURE_DIAGRAMS.md
[x] docs/MODULE_OWNERSHIP.md
[x] docs/UBIQUITOUS_LANGUAGE.md
[x] docs/CODING_AGENT_POLICY.md
[x] docs/tasks/D2_CORE_DOMAIN.md
[x] docs/tasks/D3_ACADEMIC_DOMAIN.md
[x] docs/tasks/D4_PERSISTENCE.md
```

For D5-specific rendering/projection choices, this Task Spec and `docs/CALENDAR_DECISIONS.md` are authoritative.

---

# 3. Current baseline

D4 is complete.

Current modules:

```text
:shared:domain
:shared:application
:shared:database
:apps:android
:apps:desktop
:apps:wear
```

Current application persistence ports already expose:

```text
EventRepository
TaskRepository
PlanningProfileRepository
AcademicRepository
ApplicationTransactionRunner
```

D5-01 is read-oriented and does not need new write contracts.

---

# 4. Scope

## MUST

D5-01 MUST:

```text
- keep all calendar projection/query semantics in :shared:application
- consume application repository ports, never DAOs from UI
- preserve Zoned / AllDay / Floating semantics
- derive CourseSession from D3 source facts
- expose D3 academic resolution failures visibly
- project ExamSchedule.DateOnly without inventing an Instant
- omit ExamSchedule.Unscheduled from calendar projection
- detect deterministic overlap conflicts for concrete instant ranges
- define explicit stable output ordering
- render Agenda/Day on Android and Desktop
- render a compact read-only upcoming/today agenda on Wear
- operate entirely from local state/offline
- keep D2/D3/D4 tests green
- pass repository-wide CI
```

## MAY

D5-01 MAY:

```text
- choose exact Kotlin file split inside :shared:application
- choose exact private helper names
- share small Compose rendering helpers inside an existing app source set where already supported
- use platform lazy lists for rendering
- add UI-only formatting helpers
- add deterministic test fixture builders
```

## MUST NOT

D5-01 MUST NOT:

```text
- add Planner / Local Reflow / Full Replan
- create PlanBranch
- create new Domain entities from UI input
- choose a production UUID generator
- persist CourseSession
- add a CourseSession table/cache as authoritative state
- create SyncOperation / ChangeLog / AgentAction
- add network/server code
- add Provider/LLM SDKs
- add reminders/notifications
- add generic recurrence
- add external calendar adapters
- add project-wide DI/MVI/navigation frameworks
- assume system-default timezone for Floating or AllDay values
- invent Planner occupancy defaults from calendar visibility
- reject persisted overlapping commitments merely because they conflict
```

---

# 5. Module/package contract

D5-01 creates no new Gradle module.

Canonical application package:

```text
dev.agenticscheduler.application.calendar
```

Platform UI remains under each existing app namespace.

Dependency direction:

```text
apps:* → :shared:application → :shared:domain
apps:* → :shared:domain       // display types where needed
:shared:database → :shared:application + :shared:domain
```

Platform UI must not import:

```text
RoomDatabase
DAO types
persistence record classes
```

---

# 6. Calendar viewport

D5-01 defines an immutable application projection input equivalent to:

```kotlin
data class CalendarViewport(
    val startDate: LocalDate,
    val endDateExclusive: LocalDate,
    val displayTimeZone: TimeZone,
)
```

Invariant:

```text
startDate < endDateExclusive
```

The viewport is an explicit semantic input. Projection code never reads system-default timezone to decide what a Zoned item means on screen.

---

# 7. Projection source identity

D5-01 defines an application-level source reference that preserves original identity.

Canonical semantic shape:

```kotlin
sealed interface CalendarSourceRef {
    data class Event(val id: EventId) : CalendarSourceRef
    data class FocusBlock(val id: FocusBlockId) : CalendarSourceRef
    data class CourseSession(val key: CourseOccurrenceKey) : CalendarSourceRef
    data class Exam(val id: ExamId) : CalendarSourceRef
}
```

Equivalent naming/file organization is allowed, but the projection must not infer identity from title or current start time.

---

# 8. Projection item model

D5-01 exposes semantic presentation values separate from Domain entities.

Canonical semantic shape:

```kotlin
sealed interface CalendarItem {
    val source: CalendarSourceRef
    val title: String

    data class Zoned(
        override val source: CalendarSourceRef,
        override val title: String,
        val originalRange: ZonedTimeRange,
    ) : CalendarItem

    data class AllDay(
        override val source: CalendarSourceRef,
        override val title: String,
        val range: AllDayRange,
    ) : CalendarItem

    data class Floating(
        override val source: CalendarSourceRef,
        override val title: String,
        val range: FloatingTimeRange,
    ) : CalendarItem

    data class DateOnly(
        override val source: CalendarSourceRef,
        override val title: String,
        val date: LocalDate,
    ) : CalendarItem
}
```

The exact public type names may vary locally if the semantics remain identical and the task/decision names are retained in KDoc/tests.

Rules:

```text
Event.Zoned        → Zoned
Event.AllDay       → AllDay
Event.Floating     → Floating
FocusBlock         → Zoned
CourseSession      → Zoned
Exam.Exact         → Zoned
Exam.DateOnly      → DateOnly
Exam.Unscheduled   → no CalendarItem
```

Projection items are not persisted.

---

# 9. Display-time grouping rule

For `CalendarItem.Zoned`:

- underlying occupied interval remains the original Instant range;
- date/day grouping for Agenda/Day is calculated in `viewport.displayTimeZone`;
- display conversion must not mutate or replace the source Domain value.

For `AllDay`:

- group by its explicit local date range;
- never convert to midnight instants.

For `Floating`:

- group by its own local date-time range;
- never attach viewport timezone.

For `DateOnly`:

- group by the explicit date only;
- never invent a start/end instant.

---

# 10. CourseSession derivation

D5-01 derives sessions from authoritative Academic source facts:

```text
Semester
+ Course
+ CourseScheduleRule
+ PeriodTemplate
+ AcademicHoliday
+ CourseOccurrenceException
```

Use the existing deterministic D3 resolver.

The application projection layer may index/group repository snapshots for efficiency, but it must feed semantically complete source facts into the resolver.

Forbidden:

```text
persisting CourseSession
creating a generated CourseSessionId
using current start time as session identity
silently dropping invalid academic resolution
```

---

# 11. Projection issues

Academic source inconsistencies must remain visible.

Define an application-level issue model equivalent to:

```kotlin
sealed interface CalendarProjectionIssue {
    data class AcademicResolution(
        val courseId: CourseId,
        val issue: CourseSessionResolutionIssue,
    ) : CalendarProjectionIssue
}
```

Additional D5-local projection issues may be added only when they describe a real input/projection problem and do not invent business defaults.

A broken academic rule must not disappear as an empty calendar day.

---

# 12. Conflict model

D5-01 detects conflicts only for items with concrete occupied Instant ranges.

Conflict-capable sources:

```text
Zoned Event
FocusBlock
CourseSession
ExamSchedule.Exact
```

Canonical semantic shape:

```kotlin
data class CalendarConflict(
    val first: CalendarSourceRef,
    val second: CalendarSourceRef,
)
```

Each unordered pair appears exactly once in canonical source order.

Overlap rule:

```text
A.start < B.endExclusive
AND
B.start < A.endExclusive
```

Adjacent ranges are not conflicts.

D5-01 does not label AllDay/Floating cross-kind overlap as conflict because the missing timezone/occupancy policy is intentionally unresolved.

---

# 13. Stable ordering

Never rely on Flow combination order, repository emission order, map iteration, or Compose insertion order.

Source-kind tie-break rank:

```text
EVENT          0
COURSE_SESSION 1
EXAM           2
FOCUS_BLOCK    3
```

Canonical ordering:

## AllDay / DateOnly group

```text
start date
end date exclusive when present; DateOnly behaves as [date, date+1) for ordering only
source-kind rank
stable source identity text
```

The ordering-only synthetic exclusive date for DateOnly is not stored and is not interpreted as occupied time.

## Zoned group

```text
start Instant
endExclusive Instant
source-kind rank
stable source identity text
```

## Floating group

```text
start LocalDateTime
endExclusive LocalDateTime
source-kind rank
stable source identity text
```

Conflict pair ordering uses the same canonical source identity comparator.

---

# 14. Projection result

Expose a result equivalent to:

```kotlin
data class CalendarProjectionResult(
    val items: ImmutableList<CalendarItem>,
    val conflicts: ImmutableList<CalendarConflict>,
    val issues: ImmutableList<CalendarProjectionIssue>,
)
```

All retained public collections use the already-frozen immutable collection policy.

---

# 15. Calendar query service

D5-01 adds one application service equivalent to:

```kotlin
interface CalendarQueryService {
    fun observe(viewport: CalendarViewport): Flow<CalendarProjectionResult>
}
```

Concrete implementation may be named `RepositoryCalendarQueryService` or equivalent.

It consumes only D4 application repository interfaces.

The implementation combines the relevant repository Flows, derives Academic sessions, filters to the viewport, computes conflicts, and emits canonical ordering.

The service owns no UI lifecycle scope.

---

# 16. Viewport filtering

D5-01 must define filtering deterministically.

## Zoned

Include when the occupied Instant interval intersects the Instant span covered by the viewport dates in `displayTimeZone`.

The implementation must use timezone-aware conversion APIs and must not approximate a day as `24h` across DST.

## AllDay

Include when the AllDay date range intersects:

```text
[viewport.startDate, viewport.endDateExclusive)
```

## Floating

Include when the local-date-time range intersects the local date span represented by the viewport dates.

No timezone is attached.

## DateOnly

Include iff:

```text
startDate <= date < endDateExclusive
```

---

# 17. UI architecture

D5-01 resolves OD-061 for the first Agenda/Day renderer through `docs/CALENDAR_DECISIONS.md`.

Shared application code owns:

```text
projection
ordering
conflict facts
projection issues
viewport semantics
```

Platform Compose code owns:

```text
visual hierarchy
pixels/layout
scroll state
interaction
accessibility
platform lifecycle
formatting/localization
```

No project-wide MVI/navigation/DI framework is introduced.

---

# 18. Android Agenda/Day minimum

Android must provide a minimal real screen that can:

```text
- select/show one local day inside a viewport
- display AllDay/DateOnly items separately from timed/floating items
- display timed items in chronological order
- distinguish Floating values visually from Zoned values
- surface conflict indication
- surface projection issues without crashing
- update when repository Flows emit new state
```

No editing controls are required in D5-01.

---

# 19. Desktop Agenda/Day minimum

Desktop provides equivalent semantic behavior to Android.

Exact responsive/pixel layout may differ, but projection meaning, ordering, and conflict truth come from `:shared:application` rather than platform-specific recomputation.

---

# 20. Wear agenda minimum

Wear provides a compact read-only Today/Upcoming surface backed by the same application projection semantics.

Requirements:

```text
- local/offline data only is sufficient
- no network dependency
- no provider dependency
- no edit/create flow
- same source identity and ordering semantics
```

Wear may display fewer presentation fields because of screen constraints, but it must not redefine source meaning.

---

# 21. Offline requirement

The first calendar surface must work after local persistence has loaded even with:

```text
no server
no account
no network
no AI Provider
```

D5-01 is a local-first application milestone.

---

# 22. Local database security status

OD-012 remains `PENDING`.

D5-01 may develop/test against the D4 plaintext local database baseline.

This milestone must not claim that the application is ready to handle production-sensitive user data until local-at-rest protection is explicitly resolved.

D5-01 MUST NOT choose SQLCipher or another encryption solution on its own.

---

# 23. Creation/editing intentionally deferred

D5-01 is read-oriented.

D5-02 subsequently introduced explicit Event/Task creation and editing after OD-004 / PLN-019 froze the UUIDv7 generator and its form defaults. D5-01 remains read-oriented.

In particular D5-01 does not create:

```text
CreateEvent
CreateTask
CreateCourse
production IdGenerator implementation
```

---

# 24. Required tests — application projection

At minimum:

```text
CalendarViewport rejects empty/reversed ranges
Zoned Event projects without changing underlying instants
AllDay remains date-based
Floating remains timezone-free
Exam.DateOnly remains date-only
Exam.Unscheduled is omitted
CourseSession is derived and uses CourseOccurrenceKey identity
CourseSessionResolutionIssue is surfaced
mixed source ordering is canonical
repository input iteration permutation does not change result
adjacent Zoned ranges do not conflict
overlapping concrete ranges do conflict
each conflict pair emitted once
AllDay/Floating are not silently coerced into Zoned conflicts
viewport filtering across DST boundary is correct
repository Flow update produces a new projection
close/reopen persistence produces identical projection for same viewport
```

---

# 25. Required tests — platform surface

Use the existing project test stack; do not add a UI test framework without explicit justification/authorization.

At minimum where technically practical:

```text
Android app compiles with real D5 screen composition
Desktop app compiles with real D5 screen composition
Wear app compiles with read-only agenda composition
platform smoke/unit tests verify projection is consumed rather than recomputed semantically
```

Manual visual verification may supplement but not replace shared semantic tests.

---

# 26. D5-01 acceptance criteria

D5-01 passes only when every item below is true.

```text
[x] docs/CALENDAR_DECISIONS.md is merged and OD-061 is resolved for Agenda/Day
[x] no new Gradle module was added
[x] calendar projection code lives in :shared:application
[x] UI does not call DAOs/Room records
[x] CalendarViewport has explicit displayTimeZone
[x] Zoned / AllDay / Floating distinctions are preserved
[x] Exam.DateOnly remains date-only
[x] Exam.Unscheduled is omitted from calendar projection
[x] CourseSession remains derived and unpersisted
[x] academic resolution issues are surfaced
[x] stable mixed-source ordering is explicit
[x] concrete instant-range conflicts are deterministic
[x] adjacent ranges do not conflict
[x] conflict state is rendered, not rejected
[x] Android Agenda/Day is functional
[x] Desktop Agenda/Day is functional
[x] Wear read-only agenda is functional offline
[x] no production entity creation/ID generation was introduced
[x] no Planner/Sync/Agent/server scope was entered
[x] D2 tests remain green
[x] D3 tests remain green
[x] D4 persistence tests remain green
[x] repository-wide CI is green
```

---

# 27. Completion report

The implementation report must state:

```text
Implemented
Tests/verification actually run
Projection API and ordering
Conflict scope
Academic resolution behavior
Platform surfaces changed
Architecture/invariant impact
LOCAL_REVERSIBLE assumptions
BLOCKED_BY_DECISION items
Intentionally out-of-scope D5-02 follow-ups
```

---

# Final D5-01 rule

**Calendar is a deterministic read surface over authoritative local state. It does not become a second source of Domain truth, Planner policy, or persistence semantics.**
