# Agentic Scheduler — D6 Deterministic Planner & PlanBranch

> Task ID: **D6-01 / D6-02**  
> Milestone: **D6 — Planner**  
> Status: **FIX-AND-RECHECK**
> Date: 2026-09-11  
> D6-00 decision source: `docs/PLANNER_DECISIONS.md`
> Implementation: `a994981` + `0b6c499`; semantic correction in progress from `4535975`.

---

# 1. Goal

Implement the first deterministic local Planner and isolated PlanBranch workflow on top of the completed D2–D5 foundations.

D6 establishes:

```text
configured PlanningProfile rules
+ structured request-scoped Planner constraints
+ immutable explicit PlanningSnapshot
+ deterministic Full Replan
+ deterministic Local Reflow
+ structured explanations/infeasibility
+ isolated session-scoped PlanBranch
+ stale/rebase/apply semantics
+ atomic FocusBlock mutation apply
```

The Planner remains deterministic core logic. No LLM, Provider, Sync, or server component participates in scheduling truth.

---

# 2. Required reading

Implementation begins by reading:

```text
AGENTS.md
docs/READ_FIRST.md
docs/DOMAIN_INVARIANTS.md
docs/ACADEMIC_INVARIANTS.md
docs/ACADEMIC_DECISIONS.md
docs/CALENDAR_DECISIONS.md
docs/PLANNER_DECISIONS.md
docs/OPEN_DECISIONS.md
docs/IMPLEMENTATION_CONTRACT.md
docs/ARCHITECTURE_DIAGRAMS.md
docs/MODULE_OWNERSHIP.md
docs/UBIQUITOUS_LANGUAGE.md
docs/CODING_AGENT_POLICY.md
docs/PERSISTENCE_DECISIONS.md
docs/tasks/D2_CORE_DOMAIN.md
docs/tasks/D3_ACADEMIC_DOMAIN.md
docs/tasks/D4_PERSISTENCE.md
docs/tasks/D5_CALENDAR_SURFACE.md
```

For Planner semantics, this Task Spec plus `docs/PLANNER_DECISIONS.md` are authoritative.

---

# 3. D6-00 decisions are frozen

D6 implementation MUST use the frozen decisions in `docs/PLANNER_DECISIONS.md`.

In particular:

```text
PlanningProfile configuration         PLN-002 / PLN-003
Constraint model                      PLN-004
PlanningSnapshot                      PLN-005
Task eligibility/effort               PLN-006
FocusBlock movement authority         PLN-007
TaskPriority                          PLN-008
TaskDependency scheduling             PLN-009
Deadline / overflow                   PLN-010
Event/Academic/Exam participation     PLN-011
availability / DST                    PLN-012
chunking                              PLN-013
Full Replan                           PLN-014
OD-020 lexicographic decision model   PLN-015
OD-021 Local Reflow                   PLN-016
PlanBranch                            PLN-017
atomic Apply / delete                 PLN-018
production UUIDv7 generation          PLN-019
structured planner failures           PLN-020
```

Coding agents MUST NOT replace these with local defaults or alternative optimizer semantics.

---

# 4. Scope split

```text
D6-01
- PlanningProfile configuration Domain changes
- Room schema v1 -> v2 migration
- :shared:planner module
- PlanningSnapshot and validation
- Full Replan
- Local Reflow
- structured Planner output/explanations

D6-02
- production UUIDv7 generator
- PlanBranch lifecycle
- application snapshot assembly/orchestration
- stale detection/rebase
- atomic Apply
- FocusBlock delete persistence operation
```

Both are authorized by this spec and may be implemented in one coherent change set.

---

# 5. Module authorization

D6 creates exactly one new Gradle module:

```text
:shared:planner
```

Required dependency direction:

```text
:shared:planner      -> :shared:domain
:shared:application  -> :shared:planner + :shared:domain
:shared:database     -> :shared:application + :shared:domain
apps:*               -> existing application/database composition
```

`:shared:planner` MUST NOT depend on:

```text
Room / SQLite
Compose
Ktor
Provider SDKs
Android/Wear APIs
repositories
ApplicationTransactionRunner
system clock reads
```

No other new module is authorized by D6.

---

# 6. PlanningProfile Domain change

Update `PlanningProfile` to the exact semantics frozen by PLN-002.

Required concepts:

```text
PlanningProfileConfiguration.Unconfigured
PlanningProfileConfiguration.Configured
WeeklyAvailabilityWindow
AllDayEventPolicy
```

Retained collection fields use the existing immutable-collections policy.

The configured state validates:

```text
finite positive focus durations
minimum <= preferred <= maximum
non-overlapping same-day availability windows
start < endExclusive
canonical availability ordering
explicit TimeZone
explicit AllDayEventPolicy
```

Do not add default profile values in constructors, migrations, repository mappers, or UI/application assembly.

---

# 7. Persistence schema v2

Current schema is v1. D6 bumps Room to:

```text
version = 2
```

Implement the PlanningProfile storage contract from PLN-003.

The v1 -> v2 migration MUST preserve every existing row and migrate every old PlanningProfile to `Unconfigured` without inventing Planner settings.

Required database work:

```text
planning_profiles configuration columns
planning_profile_availability_windows child table
explicit mapper validation for configured/unconfigured variants
schema export for version 2
migration test 1 -> 2
close/reopen round-trip for Configured and Unconfigured profiles
```

No PlanBranch or request Constraint table is added.

No destructive migration fallback is allowed.

---

# 8. Application persistence change

Keep `PlanningProfileRepository` in `:shared:application` and Room implementation in `:shared:database`.

D6 also explicitly adds:

```kotlin
suspend fun deleteFocusBlock(id: FocusBlockId)
```

to the Task persistence boundary (exact interface organization may remain locally consistent).

Only FocusBlock deletion is authorized here. D6 does not create generic Task/Event/Academic delete semantics.

---

# 9. Planner public input

Define a complete immutable Planner input equivalent to `PlanningSnapshot` with:

```text
referenceNow
planning horizon
a configured PlanningProfile
Tasks
TaskDependencies
FocusBlocks
Events
resolved CourseSessions
Exams
PlanningConstraints
ASK-overflow authorization set
```

The application layer is responsible for deriving CourseSessions from authoritative D3 facts before constructing the snapshot.

The Planner validates cross-reference/coherence problems and returns structured input issues rather than reading repositories to repair them.

Every input collection is canonicalized before result-affecting iteration.

---

# 10. Constraint v1

Implement only the D6 constraint kind frozen by PLN-004:

```text
PlanningConstraint.UnavailableWindow
ConstraintSource = USER | PROFILE | AGENT_INTERPRETED | SYSTEM
```

It is request-scoped, finite, hard, and non-persistent.

Do not add soft free-text preferences, generic expressions, recurrence constraints, or Agent-specific prompt fields.

---

# 11. Task eligibility

Implement PLN-006 exactly.

Planner-created work:

```text
OPEN / IN_PROGRESS + known remaining > 0 only
```

Explicit issues/results cover:

```text
UnknownRemainingEffort
known remaining == 0
COMPLETED/CANCELLED cleanup eligibility for SOFT+UNPINNED future blocks
```

Never compute authoritative remaining effort as `estimated - completed`.

---

# 12. FocusBlock mutation authority

Implement PLN-007 exactly.

Future block authority matrix:

```text
PINNED               fixed
HARD                  fixed
FLEXIBLE + UNPINNED   move only; preserve ID + duration
SOFT + UNPINNED       Full Replan may move/resize/delete
```

Already-started blocks are fixed.

New blocks:

```text
SOFT + UNPINNED
```

D6 Planner proposals contain only FocusBlock mutations.

---

# 13. Task ordering and dependencies

Task selection uses PLN-008, with explicit rank constants rather than enum ordinals.

Dependencies use PLN-009 finish-before-start semantics.

Planner output must visibly distinguish dependency blocking cases such as:

```text
cancelled prerequisite
unknown prerequisite effort
remaining == 0 but not explicitly COMPLETED
prerequisite not fully planned
```

Do not silently mark a dependency satisfied from effort arithmetic alone.

---

# 14. Deadline implementation

Implement PLN-010 exactly.

Important invariants:

```text
DateOnly cutoff = start of next date in PlanningProfile.timeZone
HARD always forbids automatic overflow
NORMAL + NEVER forbids overflow
NORMAL + ASK requires explicit per-run authorization
NORMAL + ALLOW permits overflow inside horizon but ranks before-deadline first
```

A HARD shortfall returns `Infeasible` and no applicable PlanBranch.

A NORMAL shortfall/ASK requirement is structured explanation and may coexist with a valid best-effort branch.

---

# 15. Occupancy conversion

Implement PLN-011 / PLN-012.

Fixed occupancy includes as applicable:

```text
Zoned Event
Floating Event resolved in explicit PlanningProfile timezone
AllDay Event when profile policy is BLOCK_WHOLE_LOCAL_DAY
Scheduled CourseSession
ExamSchedule.Exact
fixed FocusBlocks
UnavailableWindow constraints
```

Non-occupancy:

```text
Cancelled CourseSession
ExamSchedule.DateOnly
ExamSchedule.Unscheduled
AllDay Event under NON_BLOCKING
```

D6 never edits Event/Academic/Exam source facts.

Ambiguous/nonexistent Floating/availability local-time resolution is reported explicitly. Do not choose an offset silently.

---

# 16. Free-interval construction

Construct legal free intervals from:

```text
configured weekly availability
intersect planning horizon
intersect [referenceNow, +infinity)
subtract fixed occupancy
subtract request UnavailableWindow constraints
```

Use half-open interval semantics everywhere.

Real stored conflicts remain valid facts. The Planner must not create a new illegal overlap in proposed FocusBlocks.

DST/day calculations use calendar/timezone APIs, never fixed 24-hour arithmetic.

---

# 17. Chunking

Implement the finite chunk candidate rule from PLN-013 exactly.

There is no hidden 5/15-minute scheduling grid.

Tests must include:

```text
remaining < minimum exception
minimum boundary
preferred boundary
maximum boundary
sub-minimum remainder rejection
free interval shorter than minimum
candidate tie determinism
```

---

# 18. Full Replan

Implement PLN-014 as the D6 v1 deterministic constructive planner.

Required characteristics:

```text
no stochastic search
no hidden weights
canonical Task selection
canonical free-interval/candidate ordering
outside-horizon FocusBlocks untouched
future outside-horizon coverage counted
FLEXIBLE blocks preserve ID/duration
SOFT blocks may be reused/moved/resized/deleted
new blocks only when needed
```

Full Replan may leave NORMAL/no-deadline work unscheduled when capacity is insufficient; it must report exact unscheduled effort.

HARD deadline infeasibility prevents an applicable proposal.

---

# 19. OD-020 implementation

OD-020 is **RESOLVED** by PLN-015.

No weighted score table is allowed.

Candidate comparison order is frozen as:

```text
deadline legality / before-overflow
smaller authorized lateness
schedule preservation
smaller movement distance
smaller context-switch delta
chunk-duration rank
start
end
canonical identity
```

Task selection rank remains separate and precedes candidate placement.

Explanation output must retain the deterministic reason/criteria used.

---

# 20. Local Reflow / OD-021

OD-021 is **RESOLVED** by PLN-016.

Local Reflow:

```text
uses explicit affected/disrupted inputs
uses explicit search window
never cascades into unrelated movable blocks
never creates/deletes/resizes
preserves ID and duration
moves only eligible future FLEXIBLE/SOFT + UNPINNED blocks
chooses closest legal position, then earlier start, then ID
returns Infeasible atomically if any affected block cannot be placed
```

Tests must prove identical result under input permutation.

---

# 21. Planner result model

Use explicit operation-specific results, consistent with OD-002.

Conceptually distinguish:

```text
Success / applicable proposal
Infeasible / no applicable branch
InvalidInput
```

Structured issues/explanations include the families frozen by PLN-020.

Do not use generic null/false or natural-language strings as the only result truth.

---

# 22. Production UUIDv7 generator

Implement PLN-019 and the now-resolved implementation portion of OD-004.

Required boundary:

```text
application/infrastructure injectable ID generator
production Clock + SecureRandom adapter
Domain constructors receive IDs; they never generate IDs
```

Current supported production targets are Android/Wear/JVM Desktop, so platform randomness uses `java.security.SecureRandom` in the appropriate Android/JVM source sets.

No UUID library dependency is added.

---

# 23. PlanBranch

D6 v1 PlanBranch is session-local and non-persistent.

Required state:

```text
PlanBranchId
original request
normalized relevant base facts
ordered FocusBlock mutations
structured explanation
lifecycle status
```

Lifecycle:

```text
DRAFT
STALE
REBASEABLE
CONFLICTED
APPLIED
DISCARDED
```

Mutation kinds:

```text
CreateFocusBlock
MoveFocusBlock
ResizeFocusBlock
DeleteFocusBlock
```

No Active State write occurs when merely constructing/previewing a branch.

---

# 24. Stale / rebase semantics

At Apply time, compare the current relevant facts against the branch's normalized base facts.

If any fact used by the Planner changed:

```text
DRAFT -> STALE
```

No blind Apply.

Rebase:

```text
rebuild current snapshot
use new explicit referenceNow
rerun same deterministic request
produce refreshed proposal/explanation
```

Do not implement Git runtime/repository mechanics for PlanBranch.

---

# 25. Atomic Apply

Apply goes through `ApplicationTransactionRunner`.

Inside the same transaction:

```text
validate current base/staleness
validate applyNow against proposed future intervals
apply all FocusBlock mutations
commit all or none
```

A forced failure after any intermediate mutation must roll back every mutation.

D6 does not append ChangeLog/SyncOperation/AgentAction; those are later milestone responsibilities.

---

# 26. Tests — pure Planner

At minimum:

```text
configured/unconfigured PlanningProfile validation
availability canonicalization/non-overlap
DST availability resolution
TaskStatus eligibility matrix
remaining null / zero behavior
priority rank not enum ordinal
finish-before-start dependency cases
cancelled/unknown/zero prerequisite cases
Exact deadline
DateOnly next-day-start cutoff
HARD shortfall
NORMAL NEVER / ASK / ALLOW
ASK with and without per-run authorization
AllDay NON_BLOCKING / BLOCK_WHOLE_LOCAL_DAY
Floating resolution + DST failure
CourseSession / Exam occupancy
HARD + UNPINNED fixed
PINNED fixed
FLEXIBLE move-only
SOFT move/resize/delete
chunk boundary matrix
Full Replan deterministic replay
input permutation produces identical result
Local Reflow closest-position tie-break
Local Reflow infeasible is all-or-nothing
context-switch definition
outside-horizon coverage behavior
```

---

# 27. Tests — application/persistence

At minimum:

```text
Room migration v1 -> v2
existing PlanningProfile becomes Unconfigured
Configured PlanningProfile round-trip including availability windows
schema v2 export committed
FocusBlock delete repository behavior
UUIDv7 deterministic fixture generator tests
production generated ID format/version/variant tests
PlanBranch creation causes no Active State mutation
stale Apply refusal
expired Apply refusal
deterministic rebase
atomic multi-mutation Apply commit
atomic Apply forced rollback
close/reopen preserves PlanningProfile configuration
D2–D5 tests remain green
```

---

# 28. MUST NOT

D6 MUST NOT:

```text
add Timefold as runtime dependency
add stochastic optimizer/random search
read system clock inside :shared:planner
move Event entities automatically
persist/sync PlanBranch
persist request constraints
add ChangeLog/SyncOperation/AgentAction
implement Agent/LLM orchestration
choose local DB encryption
add network/server code
introduce project-wide DI/MVI/navigation frameworks
invent travel/energy/recurrence/location planning
weaken HARD/PINNED semantics
use object/repository iteration order as tie-break
```

---

# 29. D6 acceptance criteria

D6 passes only when:

```text
[x] D6-00 Planner decisions frozen in docs/PLANNER_DECISIONS.md
[x] OD-020 decision contract resolved
[x] OD-021 decision contract resolved
[x] :shared:planner module authorized
[x] PlanningProfile/Constraint semantics frozen
[x] task eligibility/priority/dependency/deadline/overflow semantics frozen
[x] Academic/Exam/AllDay/Floating participation frozen
[x] production UUIDv7 implementation contract frozen

[ ] :shared:planner exists with allowed dependencies only
[ ] PlanningProfile Domain model matches PLN-002
[ ] schema v2 + migration 1 -> 2 pass
[ ] Full Replan is deterministic
[ ] Local Reflow is deterministic
[ ] structured infeasibility/explanations exist
[ ] HARD/PINNED authority tests pass
[ ] PlanBranch is isolated from Active State
[ ] stale branch cannot blind-apply
[ ] rebase is deterministic
[ ] Apply is atomic
[ ] UUIDv7 generation is injectable/testable
[ ] no Planner/Agent/Sync scope violation exists
[ ] repository-wide CI is green
```

The unchecked implementation items are the authorized D6 work. No additional architecture approval is required to implement them exactly as specified.
