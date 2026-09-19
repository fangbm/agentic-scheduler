# Agentic Scheduler — D5-02 Explicit Creation & Editing

> Task ID: **D5-02**  
> Milestone: **D5 follow-on — local creation/editing**  
> Status: **IMPLEMENTED / BUILD VERIFIED / RUNTIME VERIFICATION PENDING**
> Date: 2026-09-11  
> Prerequisites: D5-01 complete; OD-004 production UUIDv7 generation resolved by `docs/PLANNER_DECISIONS.md` PLN-019
> Implementation revision: `b10bec0` (`fix(d5): make editing tests JUnit compatible`)

---

# 1. Goal

Add explicit local creation and editing for the two source-fact types already required by the first usable personal scheduler surface:

```text
Event
Task
```

D5-02 is deliberately narrow. It does not introduce Planner behavior, Sync/history semantics, generic deletion, academic timetable authoring, reminders, recurrence, Agent Tools, or a new UI/navigation framework.

The result should be a user-visible Android/Desktop flow that can create and edit Event/Task records through application-layer commands while preserving all D2–D6 contracts.

---

# 2. Required reading

Before implementation, read:

```text
docs/READ_FIRST.md
docs/DOMAIN_INVARIANTS.md
docs/IMPLEMENTATION_CONTRACT.md
docs/MODULE_OWNERSHIP.md
docs/OPEN_DECISIONS.md
docs/CALENDAR_DECISIONS.md
docs/PLANNER_DECISIONS.md
docs/tasks/D2_CORE_DOMAIN.md
docs/tasks/D4_PERSISTENCE.md
docs/tasks/D5_CALENDAR_SURFACE.md
this Task Spec
```

Reuse the established `dev.agenticscheduler.application.id.UuidV7Generator`; do not create a second generator path.

---

# 3. Scope

D5-02 implements exactly these user-facing capabilities:

```text
Create Event
Edit Event
Create Task
Edit Task
```

Supported platforms:

```text
Android     create + edit
Desktop     create + edit
Wear OS     remains read-only in D5-02
```

Wear creation/editing is deferred. Do not introduce Watch keyboard/STT/Agent/provider dependencies merely to expand this milestone.

---

# 4. Module and dependency contract

No new Gradle module is authorized.

Ownership remains:

```text
:shared:domain
    Event / Task semantics and invariants

:shared:application
    creation/editing input models
    command/use-case orchestration
    ID generation dependency boundary
    repository/transaction use

:shared:database
    existing Room repository implementations

apps:android / apps:desktop
    forms, visible defaults, validation presentation, navigation local to the feature
```

UI must not import DAOs or Room records.

D5-02 must not add a project-wide DI/MVI/navigation framework. OD-060 remains pending; use the existing minimal Compose/manual composition baseline.

---

# 5. Production ID generation

OD-004 is resolved by `PLN-019`.

Creation uses the application/infrastructure UUIDv7 generator contract:

```text
RFC UUIDv7
48-bit Unix epoch milliseconds from injected Clock
secure random rand_a/rand_b bits
canonical lowercase hyphenated text
Domain constructor receives the generated strongly typed ID
```

Rules:

```text
Create Event -> generate EventId exactly once
Create Task  -> generate TaskId exactly once
Update       -> preserve the existing ID exactly
```

D5-02 MUST NOT introduce a second incompatible production UUID implementation.

If D5-02 and D6 are implemented concurrently, the UUID generator is an explicit integration touchpoint: whichever branch establishes the canonical application/infrastructure generator first is reused by the other after rebase. Do not keep duplicate production generators after integration.

---

# 6. Application command boundary

Implement explicit application operations equivalent to:

```text
CreateEvent
UpdateEvent
CreateTask
UpdateTask
```

Exact Kotlin class/file names are locally reversible, but the semantic input/result contracts below are frozen.

Every successful command executes through the application write boundary and repository contract. A command is one logical local write operation.

D5-02 does not append ChangeLog, SyncOperation, AgentAction, or tombstones; D7 later wraps committed application mutations in those semantics.

---

# 7. Event creation contract

A created Event must contain explicit values for every current Event semantic field:

```text
title
time placement
flexibility
pinState
```

No application command constructor default may silently fill a missing semantic field.

## 7.1 Visible Android/Desktop form defaults

The initial create form MAY preselect only these visible defaults:

```text
time kind      = ZONED
flexibility    = HARD
pinState       = UNPINNED
```

These defaults must be visible/editable before Save. They are presentation defaults, not hidden command defaults: the submitted command still carries explicit values.

Start/end time are not invented by the command layer. A platform screen may prefill a selected calendar date/context, but the exact values must be visible before Save.

## 7.2 Title

```text
blank title -> Save disabled / explicit validation issue
non-blank   -> passed unchanged except ordinary UI text editing
```

Do not silently trim/change semantic text in persistence code. UI may offer ordinary input trimming before command submission only if the resulting displayed value matches what is submitted.

---

# 8. Event time variants

D5-02 supports all existing Event `TimePlacement` variants:

```text
ZonedTimeRange
AllDayRange
FloatingTimeRange
```

The user must be able to see which variant is being created/edited.

## 8.1 Zoned Event

UI input is local wall-clock start/end plus an explicit timezone.

Resolution rule:

```text
exactly one valid Instant for start
and
exactly one valid Instant for end
```

Ambiguous or nonexistent DST wall times are rejected explicitly. Never silently choose earlier/later offset and never shift a nonexistent local time.

The selected timezone is stored on `ZonedTimeRange`; do not substitute system-default timezone after submission.

## 8.2 All-day Event

Input is:

```text
startDate
endDateExclusive
```

Use half-open semantics. Do not convert an AllDay Event to midnight Instants.

## 8.3 Floating Event

Input is:

```text
LocalDateTime start
LocalDateTime endExclusive
```

No timezone is attached or inferred.

Do not silently convert Floating to Zoned.

---

# 9. Event editing contract

Editing may change exactly the current Event fields:

```text
title
time
flexibility
pinState
```

The EventId is immutable.

Editing one Event must not mutate Task, FocusBlock, Course/Academic facts, or Planner profile state.

D5-02 does not implement Event deletion.

D6 v1 does not automatically move Event entities, regardless of the stored Event flexibility value; D5-02 must not reinterpret editing as a Planner operation.

---

# 10. Task creation contract

A newly created Task has the following frozen creation semantics:

```text
status             = OPEN
completed effort   = 0
priority           = NORMAL        // visible form default
estimated effort   = null          // unknown unless user enters it
remaining effort   = null          // unknown unless user enters it
deadline           = null          // absent unless user enables it
```

`OPEN` and `completed = 0` are creation semantics for a new Task, not hidden inference from other fields.

The UI must visibly show the priority selection. Estimated/remaining/deadline fields may be left empty.

Do not infer:

```text
remaining = estimated
remaining = estimated - completed
COMPLETED from remaining == 0
```

Task completion and effort remain independent Domain facts.

---

# 11. Task effort editing

Editing exposes the existing independent effort fields:

```text
estimated: Duration?
completed: Duration
remaining: Duration?
```

Rules remain those of `TaskEffort`:

```text
finite
non-negative
nullable estimated/remaining allowed
```

The UI/application layer must not silently reconcile inconsistent-but-domain-valid business states by arithmetic.

Examples that remain representable:

```text
remaining == 0 && status != COMPLETED
status == COMPLETED && estimated != completed
remaining == null
```

Planner behavior for these states is owned by D6, not D5-02.

---

# 12. Task deadline editing

Task may have no deadline or one `TaskDeadline`.

Supported deadline variants:

```text
Deadline.Exact
Deadline.DateOnly
```

When the user first enables a deadline in the form, the visible policy defaults are:

```text
DeadlinePolicy = NORMAL
OverflowPolicy = ASK
```

These must remain visible/editable before Save.

For `Deadline.Exact`, the stored Instant/timezone contract remains unchanged.

For `Deadline.DateOnly`, D5-02 stores the date only. It MUST NOT resolve it to an Instant during editing; D6 owns the configured-profile timezone cutoff semantics.

---

# 13. Task editing contract

Editing may change:

```text
title
status
priority
effort
deadline
```

TaskId remains immutable.

D5-02 does not implement Task deletion.

D5-02 does not create/delete/replan FocusBlocks as an implicit consequence of editing Task fields.

If a Task edit causes existing FocusBlocks to become inconsistent with future Planner expectations, that real state remains stored and later Planner/reflow behavior may surface it. Editing is not an implicit Full Replan.

---

# 14. Validation and command results

Boundary validation should produce structured application-facing issues for expected user input failures before attempting persistence.

At minimum cover:

```text
blank title
invalid/non-positive time range
ambiguous/nonexistent Zoned local time
negative/non-finite effort
invalid deadline input
missing required Event time fields
```

Update commands additionally distinguish:

```text
NotFound
```

Successful results return the committed Event/Task value or its stable identity plus committed value.

Do not use generic `Boolean`/`null` as the only command result.

Unexpected infrastructure failures may propagate through the existing application/platform error boundary; do not invent a global error framework in D5-02.

---

# 15. Transaction behavior

Creation/editing writes through existing repository ports:

```text
EventRepository.upsert
TaskRepository.upsertTask
```

Use `ApplicationTransactionRunner` for the command write boundary even though v1 commands currently modify one aggregate row. This creates a stable application transaction seam for D7 history/mutation integration without importing Room into application code.

No partial semantic write is legal.

D5-02 does not change the Room schema.

If D6's schema-v2 migration lands concurrently, D5-02 must rebase onto it; D5-02 itself owns no database version bump.

---

# 16. UI behavior

Android/Desktop implement simple explicit forms using existing Compose/manual composition.

Minimum screens/interactions:

```text
Event create form
Event edit form
Task create form
Task edit form
Save
Cancel/discard
validation presentation
```

Cancel/discard performs no repository write.

Editing loads the current entity value into the form; Save submits one explicit application command.

No autosave in D5-02.

No optimistic hidden background write while the user is still editing.

---

# 17. Calendar integration

After successful Event creation/editing, the existing D5-01 repository Flow/projection must update naturally.

Task creation/editing updates Task surfaces but does not create calendar occupancy until a FocusBlock exists.

D5-02 MUST NOT fabricate a FocusBlock merely because a Task has remaining effort or a deadline.

---

# 18. Explicit exclusions

D5-02 does not add:

```text
Event deletion
Task deletion
FocusBlock manual creation/editing UI
Planner invocation / automatic replan
PlanBranch
Course/semester/timetable authoring
Exam editing
Reminder editing
recurrence
generic Project/Inbox entities
ChangeLog
SyncOperation
AgentAction
E2EE
server calls
external calendar writes
Wear creation/editing
project-wide DI/MVI/navigation framework
```

Academic authoring remains a separate future task because its aggregate invariants and child collections are materially more complex than Event/Task forms.

---

# 19. Concurrency with D6 implementation

D5-02 is intentionally safe to implement in parallel with D6 with these boundaries:

```text
D5-02 owns Event/Task create-edit application/UI flow
D6 owns Planner/PlanningProfile/FocusBlock planning semantics
```

Shared integration points:

```text
production UUIDv7 generator
:shared:application build/dependency changes
TaskRepository interface if D6 adds deleteFocusBlock
schema baseline if D6 lands v2 first
```

Contributors must rebase before merge and preserve both contracts.

D5-02 must not overwrite/remove D6's `deleteFocusBlock` addition or Planner dependencies if those land first.

---

# 20. Required tests

At minimum add:

## Application/common tests

```text
Create Event generates one valid EventId
Update Event preserves EventId
Create Task generates one valid TaskId
Update Task preserves TaskId
blank title rejected
repository NotFound update result
Cancel path writes nothing (UI/state test where practical)
```

## Event variant tests

```text
Zoned create/edit round-trip
AllDay create/edit round-trip
Floating create/edit round-trip
ambiguous Zoned local time rejected
nonexistent Zoned local time rejected
Event flexibility/pin changes persist
```

## Task tests

```text
new Task => OPEN + completed=0
visible/default NORMAL priority reaches command explicitly
estimated=null remains null
remaining=null remains null
no remaining inference from estimated
DateOnly deadline remains DateOnly
Exact deadline round-trip
NORMAL + ASK default when deadline first enabled
status/priority/effort/deadline edits persist independently
```

## Persistence/integration tests

```text
create -> close/reopen -> same Event
edit -> close/reopen -> updated Event, same ID
create -> close/reopen -> same Task
edit -> close/reopen -> updated Task, same ID
D5-01 calendar projection observes Event create/edit changes
Task create/edit does not create FocusBlock rows
```

## Platform smoke tests

```text
Android Event/Task create forms render and submit
Desktop Event/Task create forms render and submit
edit forms load current values
validation prevents invalid Save
Wear remains read-only and still builds
```

---

# 21. Acceptance gate

Implementation is complete and static review confirms the scoped application, persistence, and platform composition paths. Build, platform-smoke, and CI verification were intentionally not run in this audit and remain pending; the gate below remains the required verification record.

D5-02 PASS requires:

```text
[ ] Android Event create/edit works
[ ] Desktop Event create/edit works
[ ] Android Task create/edit works
[ ] Desktop Task create/edit works
[ ] Event Zoned/AllDay/Floating semantics preserved
[ ] strict DST rejection for Zoned local input
[ ] Task creation semantics exactly match this spec
[ ] no effort/deadline inference
[ ] IDs generated through OD-004/PLN-019 production boundary
[ ] update preserves identity
[ ] application commands use repository/transaction boundary
[ ] D5-01 projection reacts to Event writes
[ ] Task writes do not fabricate FocusBlocks
[ ] no schema bump owned by D5-02
[ ] no ChangeLog/SyncOperation/AgentAction added early
[ ] Wear remains read-only
[ ] CI green
```

---

# 22. Final invariant

**D5-02 turns explicit user form input into explicit Event/Task source facts. It does not guess schedule semantics, trigger planning implicitly, or preempt D7 history/sync behavior.**
