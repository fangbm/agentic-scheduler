# D2 — Core Domain Model Task Spec

> Status: **READY FOR IMPLEMENTATION**  
> Milestone: D2  
> Scope owner: `:shared:domain`  
> Required reading: `AGENTS.md`, `docs/READ_FIRST.md`, `docs/DOMAIN_INVARIANTS.md`, `docs/OPEN_DECISIONS.md`, `docs/IMPLEMENTATION_CONTRACT.md`, `docs/MODULE_OWNERSHIP.md`, `docs/UBIQUITOUS_LANGUAGE.md`.

This is the authoritative implementation contract for D2. It intentionally removes high-impact choices from the Coding Agent.

D2 establishes the smallest stable pure-domain foundation required by later Calendar, Academic, Persistence, Planner, Sync, and Agent milestones.

D2 does **not** implement those later layers.

---

# 1. D2 outcome

At completion, `:shared:domain` must contain tested, immutable, multiplatform domain primitives for:

```text
Strongly typed UUIDv7 entity IDs
TimePlacement
ZonedTimeRange
AllDayRange
FloatingTimeRange
Flexibility
PinState
Deadline
TaskDeadline
DeadlinePolicy
OverflowPolicy
TaskStatus
TaskPriority
TaskEffort
Event
Task
FocusBlock
WorkLog
TaskDependency
TaskDependency validation
PlanningProfile scaffold
```

The resulting module must remain pure Kotlin/KMP domain code.

---

# 2. Exact D2 scope

## D2 MUST

- add the domain types listed in this task;
- enforce all single-object invariants at construction;
- implement deterministic dependency-cycle validation;
- use the canonical names and packages defined here;
- add positive and negative `commonTest` coverage;
- keep all public domain state immutable;
- keep Android/Desktop/Wear builds green;
- keep `shared:domain` free of persistence, network, UI, Agent, Sync, and crypto dependencies.

## D2 MAY

- add small `internal` pure helper functions required to avoid validation duplication;
- split a file when doing so materially improves readability without changing public architecture;
- add comments/KDoc that explain non-obvious domain semantics.

## D2 MUST NOT

- implement Course / CourseSession / AcademicYear / Semester;
- implement Exam;
- implement RecurrenceRule / recurrence expansion;
- implement Reminder;
- implement Project / Inbox;
- implement Constraint or real PlanningProfile policies;
- implement Local Reflow or Full Replan;
- implement conflict detection between different `TimePlacement` kinds;
- add Room, SQLite schema, DAO, repository, migrations, or database annotations;
- add SyncOperation, DVV, HLC, merge logic, wire serialization, or tombstone storage;
- add Agent runtime, LLM SDK, Tool schemas, AgentThread, ContextSummary, or ChangeLog implementation;
- add server/network code;
- add UI features;
- create a new Gradle module;
- add a DI, serialization, logging, functional-error, UUID, or collections framework;
- introduce business defaults not specified by this task.

Later concepts may be referenced in documentation only; they are not D2 implementation scope.

---

# 3. Frozen tool/dependency choices for D2

Repository toolchain remains unchanged.

D2 date/time dependency is resolved as:

```text
org.jetbrains.kotlinx:kotlinx-datetime:0.8.0
```

It is already centralized in the Version Catalog.

Use:

```text
kotlin.time.Instant
kotlin.time.Duration
kotlinx.datetime.LocalDate
kotlinx.datetime.LocalDateTime
kotlinx.datetime.TimeZone
```

Do not use `java.time.*` in `commonMain`.

Do not use deprecated/compat `kotlinx.datetime.Instant` or `kotlinx.datetime.Clock` APIs.

No other third-party dependency is authorized for D2.

---

# 4. Canonical package/file layout

The expected package layout is:

```text
shared/domain/src/commonMain/kotlin/dev/agenticscheduler/domain/
├── id/
│   └── EntityIds.kt
├── time/
│   └── TimePlacement.kt
├── event/
│   └── Event.kt
├── task/
│   ├── Task.kt
│   ├── FocusBlock.kt
│   ├── WorkLog.kt
│   └── TaskDependency.kt
└── planning/
    ├── SchedulingPolicy.kt
    ├── Deadline.kt
    └── PlanningProfile.kt
```

Tests mirror the semantic package structure under `commonTest`.

A Coding Agent may split `EntityIds.kt` or another file if it becomes unwieldy, but must not rename the public types or create another architectural module/package root.

Avoid generic buckets such as:

```text
model
models
util
utils
common
misc
```

for these types.

---

# 5. Strongly typed IDs

D2 must define these exact public ID types:

```text
EventId
TaskId
FocusBlockId
WorkLogId
TaskDependencyId
PlanningProfileId
```

Each ID is a distinct Kotlin value class backed by canonical UUIDv7 text.

Preferred shape:

```kotlin
@JvmInline
value class EventId(val value: String) {
    init {
        requireValidUuidV7(value)
    }
}
```

Equivalent syntax is allowed if required by Kotlin, but semantics are fixed.

## ID invariants

Accepted text must be:

```text
xxxxxxxx-xxxx-7xxx-[89ab]xxx-xxxxxxxxxxxx
```

where every `x` is a lowercase hexadecimal digit.

Therefore IDs must:

- be exactly standard hyphenated UUID text;
- be lowercase;
- encode UUID version 7;
- encode an RFC UUID variant nibble of `8`, `9`, `a`, or `b`;
- be non-empty.

D2 does **not** implement a production UUID generator.

Production generation remains a later application/infrastructure concern.

Tests use fixed valid UUIDv7 strings.

## Forbidden

Do not:

- accept arbitrary strings as valid entity IDs;
- normalize uppercase input silently;
- generate IDs inside entity constructors;
- use one generic untyped `EntityId` everywhere;
- make one typed ID implicitly convertible to another.

---

# 6. Time model

D2 defines one sealed temporal abstraction:

```kotlin
sealed interface TimePlacement
```

with exactly three public implementations:

```text
ZonedTimeRange
AllDayRange
FloatingTimeRange
```

These types themselves implement `TimePlacement`; do not add another wrapper layer such as `TimePlacement.Zoned(range)`.

---

# 7. ZonedTimeRange

Canonical semantic shape:

```kotlin
data class ZonedTimeRange(
    val start: Instant,
    val endExclusive: Instant,
    val timeZone: TimeZone,
) : TimePlacement
```

## Invariants

```text
start < endExclusive
```

Zero-length and reversed ranges fail at construction.

`timeZone` preserves the calendar/display wall-clock context for the exact instant range.

The actual occupied interval is determined by `start` and `endExclusive`; time-zone conversion must not alter the underlying occupied instants.

## D2 helper

Expose deterministic same-kind overlap logic either as a member or domain extension with this exact semantic rule:

```text
A overlaps B iff
A.start < B.endExclusive
AND
B.start < A.endExclusive
```

Adjacent ranges do not overlap.

```text
10:00–11:00
11:00–12:00
=> no overlap
```

No generic cross-kind `TimePlacement.overlaps()` is implemented in D2.

---

# 8. AllDayRange

Canonical shape:

```kotlin
data class AllDayRange(
    val startDate: LocalDate,
    val endDateExclusive: LocalDate,
) : TimePlacement
```

## Invariants

```text
startDate < endDateExclusive
```

A one-day all-day item on 2026-09-10 is:

```text
[2026-09-10, 2026-09-11)
```

Never represent all-day items as `00:00–23:59:59`.

Same-kind overlap uses the same half-open interval rule applied to dates.

AllDayRange has no hidden default timezone in D2.

---

# 9. FloatingTimeRange

Canonical shape:

```kotlin
data class FloatingTimeRange(
    val start: LocalDateTime,
    val endExclusive: LocalDateTime,
) : TimePlacement
```

## Invariants

```text
start < endExclusive
```

Floating time is intentionally not attached to a timezone.

Do not resolve it using `TimeZone.currentSystemDefault()` inside the domain object.

Same-kind overlap uses the half-open local-date-time rule.

Cross-kind resolution/comparison requires explicit context and is outside D2.

---

# 10. SchedulingPolicy types

Define exactly:

```kotlin
enum class Flexibility {
    HARD,
    FLEXIBLE,
    SOFT,
}

enum class PinState {
    PINNED,
    UNPINNED,
}
```

Semantics:

- `HARD`: Planner may not move automatically.
- `FLEXIBLE`: movable only according to later permission/planning policy.
- `SOFT`: normally Planner-movable.
- `PINNED`: explicit protection against automatic movement.
- `UNPINNED`: no explicit pin protection.

`PinState` and `Flexibility` are independent dimensions.

No default value is defined for either in D2.

---

# 11. Deadline model

Define:

```kotlin
sealed interface Deadline
```

with exactly:

```kotlin
data class Exact(
    val at: Instant,
    val timeZone: TimeZone,
) : Deadline

data class DateOnly(
    val date: LocalDate,
) : Deadline
```

The implementation may nest `Exact` / `DateOnly` under `Deadline` or place them as canonical deadline types in the same package, but public terminology must remain `Deadline.Exact` and `Deadline.DateOnly` at the conceptual/API level.

A date-only deadline is intentionally not converted to an arbitrary end-of-day Instant in D2.

Define:

```kotlin
enum class DeadlinePolicy {
    NORMAL,
    HARD,
}

enum class OverflowPolicy {
    NEVER,
    ASK,
    ALLOW,
}
```

Define:

```kotlin
data class TaskDeadline(
    val deadline: Deadline,
    val policy: DeadlinePolicy,
    val overflowPolicy: OverflowPolicy,
)
```

No defaults are supplied.

Interpretation of `NORMAL`, `HARD`, and overflow behavior belongs to later Planner work; D2 only establishes the stable semantic values.

---

# 12. Event

Canonical D2 Event shape:

```kotlin
data class Event(
    val id: EventId,
    val title: String,
    val time: TimePlacement,
    val flexibility: Flexibility,
    val pinState: PinState,
)
```

## Event invariants

- `title.isNotBlank()` must be true.
- `time` is already valid by construction.
- Event always has a TimePlacement.
- Event does not contain Task semantics.

## Exact D2 exclusions

Do not add yet:

```text
CalendarId
RecurrenceRule
Reminder
Location entity
attendee model
external provider ID
sync version
createdAt / updatedAt
Agent metadata
DB annotations
```

These exclusions are deliberate. Their later ownership/semantics must be introduced by their milestone rather than guessed in D2.

Do not impose title trimming, maximum title length, or localization rules in D2. Preserve the caller-provided non-blank text.

---

# 13. Task status and priority

Define exactly:

```kotlin
enum class TaskStatus {
    OPEN,
    IN_PROGRESS,
    COMPLETED,
    CANCELLED,
}

enum class TaskPriority {
    LOW,
    NORMAL,
    HIGH,
}
```

`BLOCKED` is not a stored TaskStatus in D2; whether a Task is blocked by dependencies is derived from dependency/domain state later.

There is no default TaskStatus or TaskPriority in Domain constructors.

---

# 14. TaskEffort

Define:

```kotlin
data class TaskEffort(
    val estimated: Duration?,
    val completed: Duration,
    val remaining: Duration?,
)
```

Semantics:

- `estimated == null` means no estimate is known.
- `remaining == null` means remaining effort is unknown.
- `completed` is always an explicit known accumulated value in the current Task state.

## Invariants

Every non-null Duration must:

```text
be finite
be >= Duration.ZERO
```

`completed` must also be finite and non-negative.

Do **not** enforce:

```text
estimated == completed + remaining
```

because estimates may be revised.

Do **not** derive TaskStatus from effort values.

Therefore these are legal domain states:

```text
remaining == 0, status == IN_PROGRESS
status == COMPLETED, remaining > 0
```

Business/UI flows may later offer convenient transitions; D2 does not invent them.

---

# 15. Task

Canonical D2 Task shape:

```kotlin
data class Task(
    val id: TaskId,
    val title: String,
    val status: TaskStatus,
    val priority: TaskPriority,
    val effort: TaskEffort,
    val deadline: TaskDeadline?,
)
```

## Invariants

- `title.isNotBlank()` must be true.
- `deadline == null` explicitly means the Task has no deadline.
- A Task has no required scheduled time.
- A Task is not an Event.

## D2 exclusions

Do not add yet:

```text
Project association
Tags
attachments
subtasks
location requirement
energy requirement
earliest start / latest finish
chunking rules
recurrence
reminders
sync metadata
persistence metadata
```

Those concepts will be introduced by explicit later tasks.

Do not silently set a default priority/status/deadline/effort.

---

# 16. FocusBlock

Canonical D2 shape:

```kotlin
data class FocusBlock(
    val id: FocusBlockId,
    val taskId: TaskId,
    val time: ZonedTimeRange,
    val flexibility: Flexibility,
    val pinState: PinState,
)
```

## Semantics

FocusBlock is concrete planned time allocated to a Task.

Therefore D2 intentionally restricts FocusBlock time to `ZonedTimeRange` rather than arbitrary `TimePlacement`.

One Task may have zero, one, or many FocusBlocks.

Moving/resizing a FocusBlock does not modify Task identity or Task meaning.

Reference existence (`taskId` actually exists) is a cross-entity/application concern and is not checked by the FocusBlock constructor.

No origin/source enum is added in D2.

---

# 17. WorkLog

Canonical D2 shape:

```kotlin
data class WorkLog(
    val id: WorkLogId,
    val taskId: TaskId,
    val time: ZonedTimeRange,
)
```

## Semantics

```text
FocusBlock = planned work time
WorkLog    = actual work fact
```

WorkLog is append-oriented historical truth conceptually.

D2 does not implement storage, correction records, timers, or WorkLog aggregation.

Reference existence is not constructor-validated.

No note/source/device fields are added in D2.

---

# 18. TaskDependency

Define:

```kotlin
data class TaskDependency(
    val id: TaskDependencyId,
    val prerequisiteTaskId: TaskId,
    val dependentTaskId: TaskId,
)
```

## Constructor invariant

```text
prerequisiteTaskId != dependentTaskId
```

Self-dependency fails immediately at construction.

Two dependency entities with different IDs but the same `(prerequisiteTaskId, dependentTaskId)` pair are semantically duplicate edges and are rejected by dependency-graph validation.

---

# 19. TaskDependency validation

D2 must implement deterministic pure validation for adding a dependency edge to an existing dependency set.

Canonical result:

```kotlin
enum class TaskDependencyValidationResult {
    VALID,
    DUPLICATE,
    CYCLE,
}
```

Expose one clear pure API equivalent to:

```kotlin
fun validateDependencyAddition(
    existing: Collection<TaskDependency>,
    candidate: TaskDependency,
): TaskDependencyValidationResult
```

Exact function/object placement may follow the `task` package, but do not create a repository/service module for it.

## Required behavior

Return:

```text
DUPLICATE
```

when an existing edge has the same prerequisite/dependent pair, regardless of dependency entity ID.

Return:

```text
CYCLE
```

when adding the candidate would make the directed Task dependency graph cyclic.

Otherwise return:

```text
VALID
```

If both duplicate and cycle could theoretically be inferred from malformed existing data, `DUPLICATE` takes precedence for an exact duplicate candidate edge.

The function assumes existing entries are individually construct-valid. D2 does not repair an already-corrupted dependency graph.

Do not depend on collection iteration order for the result.

---

# 20. PlanningProfile scaffold

D2 deliberately creates only the stable identity shell:

```kotlin
data class PlanningProfile(
    val id: PlanningProfileId,
    val name: String,
)
```

Invariant:

```text
name.isNotBlank()
```

No policy fields are added yet.

In particular, D2 must not guess:

```text
working windows
preferred windows
energy curve
max daily load
break duration
overflow behavior
weekday rules
location rules
default profile
```

Those fields are added only when the Planner/constraint task freezes their semantics.

This minimal class is intentional and is not a TODO/placeholder implementation.

---

# 21. Construction/default rules

All D2 entity/value constructors expose semantic fields explicitly.

Do not use Kotlin default parameter values for product-semantic values in these public constructors.

Examples of forbidden hidden defaults:

```kotlin
priority: TaskPriority = NORMAL
status: TaskStatus = OPEN
flexibility: Flexibility = SOFT
pinState: PinState = UNPINNED
deadline: TaskDeadline? = null
```

Even where a future UI will commonly choose one of these values, the Domain constructor must receive the choice explicitly until an approved domain/application creation policy exists.

Nullable fields such as `Task.deadline`, `TaskEffort.estimated`, and `TaskEffort.remaining` are legitimate explicit absence states, but callers must still pass their intended values.

---

# 22. Entity equality and copying

D2 entities may be Kotlin `data class`es and therefore use structural Kotlin equality for state comparison/testing.

Logical identity across revisions is still defined by typed ID.

Therefore:

```text
same logical entity?      compare ID
same complete snapshot?   structural equality may be used
```

Do not infer entity identity from title/time/other mutable semantic fields.

Public properties remain `val`.

---

# 23. Time/ID current-source prohibition

D2 code must contain no production-side calls that create hidden temporal/random inputs, including:

```text
Clock.System.now()
System.currentTimeMillis()
Instant.now()
UUID.randomUUID()
random ID generation
TimeZone.currentSystemDefault() as an implicit domain default
```

D2 objects receive all IDs and temporal values from callers.

This is required for deterministic tests and later replay/sync behavior.

---

# 24. Exact validation ownership

D2 uses this ownership split:

```text
ID syntax/version                  ID value class construction
single time-range ordering         time value construction
blank Event/Task/Profile name      entity construction
negative/infinite TaskEffort       TaskEffort construction
self Task dependency               TaskDependency construction
duplicate dependency edge          dependency graph validator
dependency cycle                    dependency graph validator
referenced Task actually exists     NOT D2 constructor responsibility
calendar conflicts                  NOT D2
Planner feasibility                 NOT D2
DB foreign-key enforcement          NOT D2
```

Do not move these rules into UI or persistence code.

---

# 25. Required test matrix

D2 is not complete without deterministic `commonTest` coverage.

At minimum implement the following cases.

## IDs

- valid lowercase UUIDv7 accepted for every ID type;
- non-UUID rejected;
- UUID v4 rejected;
- uppercase UUIDv7 rejected;
- wrong variant rejected;
- typed IDs remain distinct at compile-time/API boundaries.

## ZonedTimeRange

- normal range accepted;
- zero duration rejected;
- reversed range rejected;
- overlapping ranges return true;
- adjacent ranges return false;
- same Instants with different display time zones retain identical occupied interval semantics.

## AllDayRange

- one-day `[date, nextDate)` accepted;
- multi-day range accepted;
- zero-day range rejected;
- reversed range rejected;
- adjacent dates do not overlap.

## FloatingTimeRange

- normal local range accepted;
- zero/reversed rejected;
- adjacent ranges do not overlap;
- construction does not consult system timezone.

## Event

- valid Event accepted for each TimePlacement kind;
- blank/whitespace-only title rejected;
- explicit HARD/FLEXIBLE/SOFT and PINNED/UNPINNED combinations preserved.

## TaskEffort

- unknown estimate/remaining accepted using null;
- zero durations accepted;
- positive durations accepted;
- negative duration rejected;
- infinite duration rejected;
- revised effort where `estimated != completed + remaining` accepted.

## Task

- Task with no deadline accepted;
- Task with exact deadline accepted;
- Task with date-only deadline accepted;
- blank title rejected;
- COMPLETED with positive remaining effort remains construct-valid;
- IN_PROGRESS with zero remaining effort remains construct-valid.

## FocusBlock

- valid link to TaskId constructs without repository lookup;
- HARD/SOFT/FLEXIBLE and pin combinations are preserved;
- only ZonedTimeRange is accepted by its public type signature.

## WorkLog

- valid WorkLog accepted;
- no independent duration field exists that can disagree with the exact time range.

## TaskDependency

- self-dependency rejected at construction;
- normal edge addition => VALID;
- duplicate pair => DUPLICATE;
- direct two-node cycle => CYCLE;
- longer A→B→C plus C→A => CYCLE;
- independent DAG extension => VALID;
- result independent of input Collection iteration order.

## PlanningProfile

- non-blank name accepted;
- blank/whitespace-only name rejected.

---

# 26. Build/gate commands

D2 must pass at minimum:

```bash
./gradlew :shared:domain:build --no-daemon
./gradlew build --no-daemon
```

CI must remain green.

Warnings unrelated to D2 may be documented, but new D2 warnings should be fixed rather than normalized away without reason.

---

# 27. Acceptance checklist

D2 Gate passes only when all are true:

```text
[ ] kotlinx-datetime 0.8.0 resolves from Version Catalog
[ ] shared:domain remains KMP and dependency-clean
[ ] exact six typed ID families implemented
[ ] UUIDv7 validation implemented and tested
[ ] TimePlacement has exactly Zoned/AllDay/Floating D2 variants
[ ] half-open range invariants enforced
[ ] same-kind overlap semantics tested
[ ] Flexibility implemented exactly HARD/FLEXIBLE/SOFT
[ ] PinState implemented exactly PINNED/UNPINNED
[ ] Deadline / TaskDeadline / policies implemented
[ ] TaskStatus implemented exactly OPEN/IN_PROGRESS/COMPLETED/CANCELLED
[ ] TaskPriority implemented exactly LOW/NORMAL/HIGH
[ ] TaskEffort semantics/invariants implemented
[ ] Event implemented with exact D2 fields
[ ] Task implemented with exact D2 fields
[ ] FocusBlock implemented with exact D2 fields
[ ] WorkLog implemented with exact D2 fields
[ ] TaskDependency + DAG-add validation implemented
[ ] PlanningProfile minimal scaffold implemented
[ ] no product-semantic constructor defaults invented
[ ] no current-time/random/system-timezone hidden reads
[ ] no DB/network/UI/AI/Sync/crypto code added to Domain
[ ] required common tests pass
[ ] repository-wide build passes
[ ] CI green
```

A passing build without these semantic requirements is **not** a D2 pass.

---

# 28. Explicitly deferred to D3+

These are not omissions for a Coding Agent to fill.

```text
D3 Academic:
AcademicYear
Semester
Course
CourseScheduleRule
CourseSession
Exam
AcademicHoliday
PeriodTemplate
occurrence identity/exceptions

Later Core/Product tasks:
Calendar
Reminder
Project
Inbox
Tag / EntityTag
Location
attachments
Task subtasks beyond dependency edge semantics

Planner tasks:
earliest/latest windows
splittable/min/max chunk
energy requirements
PlanningProfile actual policies
Constraint / TemporaryConstraint
FreezeHorizon
Planner scoring
Local Reflow
Full Replan

Persistence/Sync:
Room schema
repository ports
SyncOperation
DVV/HLC
semantic merge
tombstones
serialization

Agent:
AgentThread
AgentMessage
ContextSummary
ContextAnchor implementation
AgentAction
ChangeLog
Tool Layer
ContextAssembler
```

Do not anticipate these by adding fields/annotations/frameworks in D2.

---

# 29. Coding Agent escalation rule for D2

If implementation discovers a necessary choice not answered by this file:

```text
Is it purely LOCAL_REVERSIBLE and invisible to public/cross-module semantics?
    YES → choose the simplest conventional implementation.
    NO  → DO NOT GUESS.
          Mark BLOCKED_BY_DECISION.
          State the exact missing decision.
          Continue only independent safe work.
```

Examples that require escalation:

```text
"Should Event also have CalendarId now?"              → NO, task explicitly excludes it.
"Should TaskPriority add URGENT?"                     → NO, exact enum frozen here.
"Should we add kotlinx.serialization annotations?"   → NO, explicitly out of scope.
"Should we use java.time on JVM and expect/actual?"  → NO, time contract frozen.
"Should dependency cycles throw instead of result?" → NO, graph operation result frozen.
"Should blank titles be trimmed automatically?"      → NO, preserve non-blank input.
```

A local choice such as the internal DFS implementation for cycle detection is allowed, provided it is deterministic and satisfies the specified API/result semantics.

---

# 30. D2 definition of done

D2 is complete when the project has a small, deterministic, tested semantic kernel that later layers can depend on without needing to reinterpret what Event, Task, FocusBlock, WorkLog, basic scheduling policies, IDs, and time ranges mean.

The purpose of D2 is **not** to predict every future field.

The purpose is to make every field introduced in D2 intentional and unambiguous.

**Coding agents implement this contract; they do not extend the product model while doing so.**
