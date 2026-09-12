# Agentic Scheduler — Planner Decisions

> Status: **D6-00 Frozen Decisions**  
> Date: 2026-09-11  
> Applies to: **D6 deterministic Planner + PlanBranch v1**

This document freezes the Planner semantics that D2/D3 intentionally deferred. Coding agents may implement these decisions, but may not silently broaden D6 into later Agent/Sync/security work.

---

# PLN-001 — Planner module boundary

D6 is explicitly authorized to create exactly one new Gradle module:

```text
:shared:planner
```

Dependency direction:

```text
:shared:planner     -> :shared:domain
:shared:application -> :shared:planner + :shared:domain
:shared:database    -> :shared:application + :shared:domain
apps:*              -> :shared:application + :shared:database
```

`:shared:planner` owns pure deterministic planning semantics and algorithms. It must not depend on Room/SQLite, Compose, Ktor, Provider SDKs, Android/Wear APIs, repositories, or system clock reads.

Application code owns snapshot assembly, repository orchestration, ID generation, PlanBranch application, and transaction boundaries.

---

# PLN-002 — PlanningProfile v1 configuration

`PlanningProfile` remains a Domain concept and becomes a real reusable rule set.

The D6 semantic shape is:

```kotlin
data class PlanningProfile(
    val id: PlanningProfileId,
    val name: String,
    val configuration: PlanningProfileConfiguration,
)

sealed interface PlanningProfileConfiguration {
    data object Unconfigured : PlanningProfileConfiguration

    data class Configured(
        val timeZone: TimeZone,
        val weeklyAvailability: ImmutableList<WeeklyAvailabilityWindow>,
        val minimumFocusBlock: Duration,
        val preferredFocusBlock: Duration,
        val maximumFocusBlock: Duration,
        val allDayEventPolicy: AllDayEventPolicy,
    ) : PlanningProfileConfiguration
}

data class WeeklyAvailabilityWindow(
    val dayOfWeek: DayOfWeek,
    val start: LocalTime,
    val endExclusive: LocalTime,
)

enum class AllDayEventPolicy {
    NON_BLOCKING,
    BLOCK_WHOLE_LOCAL_DAY,
}
```

Validation:

```text
name is nonblank
start < endExclusive
minimumFocusBlock > 0
minimum <= preferred <= maximum
all durations finite
availability windows for one weekday do not overlap
availability is stored/returned in canonical weekday/start/end order
cross-midnight weekly windows are represented as two windows, not start > end
```

An empty availability list is valid and means that the profile offers no automatic scheduling time.

`Unconfigured` is a deliberate state. The Planner must return a structured `ProfileUnconfigured` input issue rather than inventing defaults.

D6 v1 does not expose numeric weights for fragmentation, context switching, or schedule stability. Those preferences are frozen globally by PLN-010/PLN-011 instead of becoming per-profile magic numbers.

---

# PLN-003 — PlanningProfile persistence and migration

Current `main` is Room schema v1. D6 is authorized to migrate **v1 -> v2** for PlanningProfile configuration only.

The existing `planning_profiles` table remains authoritative and gains the equivalent of:

```text
configuration_state = UNCONFIGURED | CONFIGURED
time_zone_id?
minimum_focus_duration_iso?
preferred_focus_duration_iso?
maximum_focus_duration_iso?
all_day_event_policy?
```

A new child table is authorized:

```text
planning_profile_availability_windows
- planning_profile_id FK -> planning_profiles.id, NO ACTION
- day_of_week TEXT
- start_local_time TEXT
- end_local_time_exclusive TEXT
- deterministic uniqueness for one exact window
- index on planning_profile_id
```

Canonical persistence rules already frozen by D4 remain in force: Duration uses canonical ISO text, DayOfWeek uses explicit stable text, and LocalTime uses explicit text rather than ordinal/minute guesses.

Migration rule for every existing v1 PlanningProfile:

```text
configuration_state = UNCONFIGURED
all new configuration values = null / no child windows
```

The migration MUST NOT invent availability, timezone, chunk sizes, or all-day policy.

PlanBranch and request-scoped constraints are not persisted in D6 v1.

---

# PLN-004 — Structured D6 Constraint model

D6 v1 supports an intentionally small structured Constraint surface:

```kotlin
enum class ConstraintSource {
    USER,
    PROFILE,
    AGENT_INTERPRETED,
    SYSTEM,
}

sealed interface PlanningConstraint {
    val source: ConstraintSource

    data class UnavailableWindow(
        val time: ZonedTimeRange,
        override val source: ConstraintSource,
    ) : PlanningConstraint
}
```

`UnavailableWindow` is a HARD request-scoped exclusion with an explicit finite lifetime encoded by its range.

D6 does not persist or sync request-scoped constraints. A later milestone may add additional structured constraint/preference kinds, but D6 coding agents must not invent them.

Free text is never a Planner constraint. An Agent may later translate language into the structured surface.

---

# PLN-005 — Explicit PlanningSnapshot

Every result-changing input is explicit. The pure planner receives an immutable snapshot equivalent to:

```text
referenceNow
a concrete planning horizon
configured PlanningProfile
Tasks
TaskDependencies
FocusBlocks
Zoned/AllDay/Floating Events
resolved CourseSessions
Exams
request-scoped PlanningConstraints
per-run ASK-overflow authorizations
```

The planning horizon is a concrete Instant interval. Candidates before `referenceNow` are forbidden.

No Planner algorithm may read:

```text
system clock
system-default timezone
unordered map/set iteration
unseeded randomness
LLM/provider output
repository/database state directly
```

Application code supplies `referenceNow`, builds the snapshot, and canonicalizes source collections before calling Planner code.

---

# PLN-006 — Task eligibility and effort meaning

Automatic placement eligibility:

```text
OPEN        + known remaining > 0 -> eligible
IN_PROGRESS + known remaining > 0 -> eligible
COMPLETED                       -> no new automatic work
CANCELLED                       -> no new automatic work
known remaining == 0            -> no new automatic work
remaining == null               -> no new automatic work; report UnknownRemainingEffort
```

The Planner MUST NOT derive remaining effort from `estimated - completed`. D2 froze those values as independent facts.

For D6 planning, FocusBlock duration is one-to-one planned coverage of Task remaining effort.

Existing FocusBlocks for an ineligible/unknown-effort Task remain real calendar occupancy. Full Replan may remove only future `SOFT + UNPINNED` blocks for `COMPLETED`, `CANCELLED`, or known `remaining == 0`; it must not delete/mutate HARD, PINNED, or FLEXIBLE blocks merely because a Task became ineligible.

For `remaining == null`, existing blocks are not automatically resized/deleted because the Planner lacks authoritative effort truth.

---

# PLN-007 — Existing FocusBlock authority

For a FocusBlock whose start is before `referenceNow`, D6 treats it as already-started/fixed and does not mutate it.

For future FocusBlocks:

```text
PINNED                    -> fixed
HARD + UNPINNED           -> fixed
FLEXIBLE + UNPINNED       -> may move; identity and duration are preserved
SOFT + UNPINNED           -> may move, resize, or be removed by Full Replan
```

Local Reflow never resizes or deletes; it only moves eligible `FLEXIBLE/SOFT + UNPINNED` blocks while preserving identity and duration.

New Planner-created FocusBlocks are always:

```text
Flexibility.SOFT
PinState.UNPINNED
```

D6 v1 does not automatically move Event entities. Event flexibility may authorize future Planner behavior, but this milestone treats Events as occupancy inputs and emits FocusBlock mutations only.

---

# PLN-008 — TaskPriority service order

TaskPriority is a deterministic service-order input, not a numeric hidden weight.

Priority rank:

```text
HIGH   0
NORMAL 1
LOW    2
```

Among dependency-ready eligible Tasks, Full Replan selects the next Task by:

```text
1. deadline class: HARD deadline, then NORMAL deadline, then no deadline
2. effective deadline ascending (when present)
3. TaskPriority rank
4. TaskId canonical text
```

Therefore priority never silently outranks an earlier/harder deadline, but it deterministically resolves otherwise comparable work.

Enum declaration order must not be used as the implementation ranking.

---

# PLN-009 — Dependency scheduling semantics

`TaskDependency(prerequisite, dependent)` is a finish-before-start planning dependency.

A prerequisite is immediately satisfied only when `status == COMPLETED`.

For `OPEN/IN_PROGRESS` prerequisites with known `remaining > 0`, the Planner may schedule dependent work after **planned completion**. Planned completion is the earliest Instant at which cumulative retained/proposed future FocusBlock duration for that prerequisite reaches its authoritative `remaining` effort.

Rules:

```text
COMPLETED prerequisite                -> satisfied from referenceNow
CANCELLED prerequisite                -> dependent blocked; do not assume cancellation satisfies it
remaining == null prerequisite        -> dependent blocked
remaining == 0 but not COMPLETED      -> dependent blocked awaiting explicit completion state
known remaining > 0 but not fully planned -> dependent blocked after available prerequisite plan ends
```

A dependent FocusBlock must start at or after the prerequisite planned-completion Instant.

Existing real-world violations may remain stored. The Planner must surface them and must not create a new violation.

Dependency cycles remain invalid Domain state and are rejected before planning.

---

# PLN-010 — Deadline and overflow semantics

## Exact deadline

`Deadline.Exact.at` is the effective cutoff Instant. Its stored timezone is presentation/provenance; it does not change the Instant.

## DateOnly deadline

`Deadline.DateOnly(date)` resolves using the configured PlanningProfile timezone as:

```text
start of the next local calendar date
```

That Instant is the exclusive completion cutoff. Do not use `23:59`, system timezone, or a fixed 24-hour addition.

## DeadlinePolicy

```text
HARD   -> automatic planning must fully cover the Task by the effective cutoff;
          no automatic after-deadline placement is legal.
NORMAL -> deadline is still preferred, but OverflowPolicy controls whether work may appear after it.
```

`HARD` takes precedence over OverflowPolicy, including `ALLOW`.

## OverflowPolicy for NORMAL deadlines

```text
NEVER -> no automatic after-deadline placement; remaining work stays unscheduled and is reported
ASK   -> same as NEVER unless the PlanningRequest contains explicit one-run authorization for that Task;
         without authorization emit OverflowApprovalRequired
ALLOW -> after-deadline placement is legal inside the planning horizon, but before-deadline placement ranks first
```

ASK authorization is ephemeral request input and does not mutate the Task policy.

A HARD deadline shortfall makes the run `Infeasible` and produces no applicable PlanBranch. NORMAL deadline shortfall is a structured explanation/warning and may still produce an applicable branch.

---

# PLN-011 — Planner participation / occupancy matrix

D6 v1 uses the following occupancy rules.

## Events

```text
Zoned Event    -> fixed concrete occupancy; D6 does not move Event entities
Floating Event -> resolve its local range in PlanningProfile.timeZone for this run only;
                  source value remains Floating and is not mutated
AllDay Event   -> behavior comes from PlanningProfile.allDayEventPolicy
```

For a Floating Event, ambiguous/nonexistent local time resolution is a structured blocking input issue; D6 does not guess an offset or silently ignore the event.

AllDay policy:

```text
NON_BLOCKING            -> visible calendar fact but no Planner occupancy
BLOCK_WHOLE_LOCAL_DAY   -> block each represented local date in PlanningProfile.timeZone
```

## Academic

```text
Scheduled CourseSession -> fixed occupancy
Cancelled CourseSession -> no occupancy
ExamSchedule.Exact      -> fixed occupancy
ExamSchedule.DateOnly   -> no occupancy
ExamSchedule.Unscheduled-> no occupancy
```

D6 never edits CourseScheduleRule, CourseOccurrenceException, CourseSession, or Exam.

## FocusBlocks

Occupancy/movement follows PLN-007.

## Request constraints

`UnavailableWindow` is fixed occupancy/exclusion for automatic planning.

---

# PLN-012 — Availability and DST rules

Planner-created/moved FocusBlocks must be fully contained in configured weekly availability after conversion into the profile timezone and clipping to:

```text
planning horizon
AND [referenceNow, +infinity)
```

Fixed real-world occupancy may exist outside availability; that does not make Domain state invalid.

Weekly availability is local wall-clock intent. If a configured boundary is ambiguous or nonexistent on a timezone transition and cannot be represented without choosing an offset, Planner returns a structured time-resolution issue for that occurrence rather than guessing.

All day-boundary calculations use timezone-aware calendar APIs. D6 never assumes every local day is 24 hours.

---

# PLN-013 — Chunking semantics

Configured bounds apply to newly created and resizable SOFT FocusBlocks.

```text
minimum <= block duration <= maximum
```

Exception: if the Task's entire remaining unscheduled effort is positive but less than `minimumFocusBlock`, one final block exactly equal to that remaining effort is legal.

For remaining effort `R` and one free interval capacity `C`, let:

```text
U = min(R, C, maximumFocusBlock)
```

If `R >= minimumFocusBlock` and `U < minimumFocusBlock`, that interval cannot host a new chunk.

Candidate durations are the distinct legal values from:

```text
U
min(preferredFocusBlock, U)
R                  // when R <= U
R - minimumFocusBlock // when it is within [minimum, U]
minimumFocusBlock
```

Reject any candidate that would leave a positive remainder smaller than `minimumFocusBlock`, except when the whole original remaining effort was already below minimum.

Rank legal candidate durations by:

```text
1. leaves remainder 0 or >= minimum
2. absolute distance from preferredFocusBlock
3. longer duration
```

This finite rule is the D6 v1 chunking contract; no hidden minute grid is introduced.

---

# PLN-014 — Full Replan deterministic algorithm

Full Replan is a deterministic constructive planner, not an opaque weighted/stochastic optimizer.

Algorithm order:

```text
1. validate/canonicalize snapshot
2. resolve profile availability and fixed occupancy
3. remove future mutable FocusBlocks from fixed occupancy
4. keep outside-horizon future FocusBlocks untouched but count them as already-planned Task coverage
5. repeatedly select dependency-ready Tasks using PLN-008
6. place/reuse eligible existing blocks for that Task
7. create additional SOFT+UNPINNED blocks until known demand is covered or no legal slot exists
8. emit structured unscheduled/overflow/dependency explanations
9. canonicalize proposed mutations and explanation order
```

For an existing `FLEXIBLE + UNPINNED` block, duration and identity are immutable during Full Replan; only its time may change.

For `SOFT + UNPINNED`, Full Replan may retain identity while moving/resizing, remove excess blocks, or create new blocks.

Existing FocusBlocks entirely outside the requested horizon are not mutated. Their future duration still counts as already-planned coverage of Task remaining effort. A block that has already started is fixed and does not reduce authoritative `remaining` for planning purposes; `remaining` is the authoritative user/business fact after actual-work updates.

If immutable/flexible existing future planned duration exceeds known remaining effort, preserve what D6 lacks authority to remove and report `OverallocatedPlannedEffort` rather than corrupting the schedule.

---

# PLN-015 — OD-020 scoring model v1

OD-020 is resolved as a **lexicographic deterministic decision model**, not weighted floating-point scoring.

Hard feasibility is non-negotiable. Illegal candidates are rejected before ranking.

Task selection uses PLN-008/PLN-009.

For legal placement candidates of the currently selected Task, compare in this order:

```text
1. deadline class legality / before-deadline beats authorized overflow
2. for authorized overflow: smaller lateness
3. preserve existing placement when possible
4. for moved existing block: smaller absolute movement distance
5. smaller added context-switch count
6. better chunk-duration rank from PLN-013
7. earlier start Instant
8. earlier end Instant
9. canonical FocusBlock/proposal identity text
```

Context switch definition for v1:

```text
two FocusBlocks touch exactly (A.end == B.start) and have different TaskIds
=> one switch

a positive free gap between blocks => no switch penalty
```

Fragmentation is represented by the chunk rule and, after higher-priority criteria, fewer blocks for the same scheduled effort is preferred.

Structured explanation records which lexicographic criteria decided a placement. The Planner must not manufacture hidden weights.

---

# PLN-016 — OD-021 Local Reflow v1

OD-021 is resolved as a bounded, deterministic, minimal-disruption repair mode.

Input includes:

```text
complete PlanningSnapshot
explicit changed/disrupted concrete ranges and/or explicit affected FocusBlockIds
explicit reflow search window
```

Affected set:

```text
- explicitly named FocusBlocks
- plus future FocusBlocks intersecting a disrupted range
```

If an affected block is already-started, PINNED, or HARD, Local Reflow returns a structured immovable-conflict failure; it does not mutate it.

All FocusBlocks not in the affected set are treated as fixed occupancy for this run, even if normally movable. Local Reflow does not cascade into unrelated blocks.

Affected movable blocks are processed by:

```text
original start Instant
then FocusBlockId
```

Local Reflow preserves each block's identity and duration and performs no create/delete/resize.

For each free interval that can contain the block, the candidate start is the point in:

```text
[free.start, free.end - block.duration]
```

closest to the original start (clamped to that interval). Deadline/dependency/availability rules still apply.

Candidate order:

```text
1. smaller absolute movement distance
2. earlier start
3. canonical FocusBlockId
```

Choose the first legal candidate. If any affected block cannot be placed, Local Reflow returns `Infeasible` and applies nothing; callers may request Full Replan.

Same snapshot + same request must produce byte-for-byte equivalent semantic output ordering across supported clients.

---

# PLN-017 — PlanBranch v1

D6 v1 PlanBranch is **local, session-scoped, and not persisted/synchronized**.

A Planner result never writes Active State directly. An applicable result becomes a PlanBranch containing:

```text
PlanBranchId
original PlanningRequest
normalized base facts actually read by the Planner
ordered FocusBlock mutations
structured explanation
lifecycle state
```

Authorized mutation kinds:

```text
CreateFocusBlock
MoveFocusBlock
ResizeFocusBlock
DeleteFocusBlock
```

D6 does not mutate Event, Task identity/status/effort/deadline, Academic source facts, or Exam through PlanBranch.

Lifecycle:

```text
DRAFT
STALE
REBASEABLE
CONFLICTED
APPLIED
DISCARDED
```

The base contract is structural, not a vague database revision number. The branch retains normalized relevant base facts sufficient to detect whether any Planner input it relied on changed.

Time passing alone does not make structural facts unequal, but Apply receives explicit `applyNow` and rejects a proposal whose newly created/moved future interval is no longer safely in the future.

Rebase rebuilds a fresh current snapshot and reruns the same deterministic planning request with a new explicit `referenceNow`; it never blindly patches old proposals onto new state.

---

# PLN-018 — Atomic Apply and FocusBlock deletion

D6 explicitly authorizes one new application persistence operation:

```text
TaskRepository.deleteFocusBlock(FocusBlockId)
```

This is a D6-local active-state delete operation for FocusBlock only. It does not define synchronized tombstone/compaction semantics; D7/D8 must wrap future synchronized deletes in their own history/sync contracts.

PlanBranch Apply runs through `ApplicationTransactionRunner`.

Inside the same logical transaction:

```text
1. re-read/validate all relevant base facts
2. reject STALE/expired/conflicted branch
3. apply every ordered FocusBlock create/move/resize/delete
4. commit all or none
```

No partial Apply is legal.

---

# PLN-019 — Production UUIDv7 generation (OD-004 implementation resolution)

D6 creates FocusBlocks/PlanBranches and reuses the production UUIDv7 generator established by D5-02. PLN-019 resolves the shared OD-004 contract; D6 must not introduce a second generator path.

No third-party UUID dependency is introduced.

Application/infrastructure exposes an injectable generator. Production implementation for the currently supported Android/Wear/JVM targets uses:

```text
RFC UUIDv7 layout
48-bit Unix epoch milliseconds from injected Clock
random rand_a/rand_b bits from java.security.SecureRandom on Android/JVM
version nibble = 7
variant bits = RFC 4122/9562 binary 10
canonical lowercase hyphenated text
```

The generator does not read time/randomness from Domain constructors.

Tests use injected deterministic clock/random bytes and verify:

```text
canonical lowercase UUIDv7 format
correct timestamp field
version/variant bits
independent generated values with distinct random input
strongly typed IDs remain validated by Domain constructors
```

No strict monotonic ordering inside the same millisecond is promised by v1; UUIDv7 timestamp ordering is millisecond-granularity and uniqueness comes from secure random bits.

---

# PLN-020 — Planner output / infeasibility truth

Planner failures are structured and expected, not generic exceptions/nulls.

At minimum distinguish:

```text
invalid/unconfigured profile
invalid snapshot/reference
unknown effort
unresolved Floating/DST time
immovable occupancy conflict
hard deadline capacity shortfall
normal deadline shortfall
overflow approval required
dependency blocked/unknown prerequisite effort
overallocated planned effort
no legal availability
local reflow infeasible
stale/expired PlanBranch apply
```

A HARD infeasibility produces no applicable branch. NORMAL unscheduled work may produce an applicable best-effort branch plus explicit explanation.

Natural-language explanation is never the only copy of Planner truth.

---

# D6 v1 explicit non-goals

D6 does not add:

```text
LLM/Agent scoring
Timefold runtime dependency
stochastic optimization
travel/location-aware planning
energy modeling
generic recurrence
attachment-aware planning
Event auto-movement
PlanBranch persistence/sync
new soft free-text constraint kinds
SyncOperation/ChangeLog/AgentAction
```

Any later expansion that changes these semantics requires an explicit task/decision amendment.
