# Agentic Scheduler — D6 Planner Domain, Deterministic Planner & PlanBranch

> Task ID: **D6-00/01/02 draft**  
> Milestone: **D6 — Planner**  
> Status: **BLOCKED_BY_DECISION**  
> Date: 2026-09-11

---

# 1. Goal

D6 owns the Planner semantics intentionally deferred by D2/D3.

It must not begin by choosing an optimizer. It first freezes what planning means, then implements deterministic scheduling, then adds isolated PlanBranch preview/rebase/apply.

Recommended split:

```text
D6-00  Planner semantic decisions
D6-01  deterministic Planner engine
D6-02  PlanBranch preview/rebase/apply
```

---

# 2. Proposed module boundary

When D6 is approved, it should create exactly:

```text
:shared:planner
```

Proposed dependency:

```text
:shared:planner → :shared:domain
```

Application orchestration may consume `:shared:planner`.

Planner must not depend on:

```text
Room / SQLite
Compose
Ktor
Provider SDKs
Android/Wear APIs
system clock reads
```

This module creation is not authorized until D6-00 is approved.

---

# 3. Required D6-00 semantic decisions

D2 deliberately created only a stable PlanningProfile identity shell. D6 must define the real planning rule set.

At minimum freeze:

```text
PlanningProfile rule fields
structured Constraint model
working/availability windows
temporary no-schedule windows
minimum FocusBlock size
maximum FocusBlock size
task splitting/chunking rule
fragmentation policy
context-switch policy
schedule-stability/disruption policy
```

No implementation agent may invent defaults for those values.

---

# 4. Task eligibility

D6-00 must define Planner behavior for:

```text
TaskStatus.OPEN
TaskStatus.IN_PROGRESS
TaskStatus.COMPLETED
TaskStatus.CANCELLED
remaining effort == null
remaining effort == 0
```

Task completion remains a business/user state and is not mathematically derived from effort.

---

# 5. TaskPriority

D6-00 must define whether and where:

```text
LOW
NORMAL
HIGH
```

changes planning choice.

Enum declaration order must not become an accidental score.

---

# 6. Task dependencies

D6-00 must define scheduling semantics for TaskDependency.

At minimum decide:

```text
whether dependent work may be scheduled before prerequisite work
whether prerequisite completion or merely planned completion is required
how dependency-related infeasibility is reported
```

The dependency graph remains a DAG invariant owned by deterministic Domain logic.

---

# 7. Deadline semantics

D2 explicitly deferred the Planner meaning of:

```text
DeadlinePolicy.NORMAL
DeadlinePolicy.HARD
OverflowPolicy.NEVER
OverflowPolicy.ASK
OverflowPolicy.ALLOW
```

D6-00 must freeze those meanings.

`Deadline.DateOnly` must not silently become "23:59 system timezone". The exact timezone/day-boundary resolution rule must be explicit.

---

# 8. Movement / occupancy inheritance

Already-frozen rules:

```text
PINNED => Planner cannot automatically move the item.
HARD   => Planner cannot automatically move the item even when UNPINNED.
```

`PinState` and `Flexibility` remain independent dimensions.

D6-00 must additionally define Planner participation/movement behavior for:

```text
FLEXIBLE
SOFT
CourseSession
ExamSchedule.Exact
AllDay Event
Floating Event
```

D3 intentionally did not assign implicit Planner occupancy/movement defaults to Academic entities.

Do not infer Planner policy from D5 rendering visibility.

---

# 9. Explicit deterministic input

The Planner must consume one complete immutable snapshot/input containing every value that can affect the result.

Forbidden hidden inputs:

```text
system clock
system-default timezone
unordered collection iteration
unseeded randomness
LLM preference
platform-specific iteration behavior
```

If stochastic optimization is ever introduced, the seed becomes an explicit reproducible input and requires a separate decision.

---

# 10. OD-020 — scoring model

OD-020 remains `PENDING`.

Recommended direction: lexicographic objectives rather than one opaque weighted floating-point score.

The final decision must explicitly order or otherwise define:

```text
hard feasibility
deadline satisfaction/overflow behavior
pinned/hard preservation
movement/disruption
fragmentation
context switching
TaskPriority influence
stable placement preference
canonical final tie-break
```

LLM output never supplies missing scoring weights/preferences.

---

# 11. OD-021 — Local Reflow

OD-021 remains `PENDING`.

The final deterministic rule must define:

```text
affected-set construction
candidate-slot enumeration
search horizon
hard rejection rules
comparison tuple
canonical tie-break
```

Same complete snapshot must produce the same proposal on every supported client.

---

# 12. Planner modes

D6 intends to implement:

```text
Local Reflow
Full Replan
```

Both produce proposed state.

Neither writes Active State directly.

---

# 13. Structured explanation

Planner output must contain structured explanation facts, including as applicable:

```text
ConstraintMatch
DecisionReason
objective/score components
moved blocks
unscheduled effort
infeasibility reasons
```

Natural-language explanation is downstream presentation and never the only copy of Planner truth.

---

# 14. PlanBranch lifecycle

Minimum intended lifecycle:

```text
DRAFT
STALE
REBASEABLE
CONFLICTED
APPLIED
DISCARDED
```

A PlanBranch is not Active State.

Before Apply, branch-only proposals trigger no ordinary active-state side effects.

---

# 15. PlanBranch base identity

Do not leave "revision/fingerprint" ambiguous.

Recommended v1 direction:

```text
record the exact relevant immutable base facts/versions required by the proposal
compare them structurally at Apply/rebase time
```

A cryptographic snapshot hash is not required merely to detect staleness.

The exact contract is part of D6-02 approval.

---

# 16. Apply

PlanBranch Apply is one application-level transaction through `ApplicationTransactionRunner`.

```text
all proposed active-state changes commit
or
none commit
```

A stale branch cannot blind-apply.

---

# 17. Persistence/schema rule

D6 must explicitly choose whether PlanBranch survives process restart.

If D6 expands durable PlanningProfile/Constraint state or persists PlanBranch:

```text
bump then-current Room schema N → N+1
export schema
add migration tests
```

Do not assume D4 schema v1 is still current when the future implementation begins.

---

# 18. Out of D6 v1 unless separately introduced

Current Domain does not yet contain enough semantics for these to be silently added:

```text
travel/location-aware planning
energy requirements
attachment-aware planning
generic recurrence planning
```

---

# 19. Required tests once READY

At minimum:

```text
deterministic replay
input iteration permutation => identical output
HARD + UNPINNED never auto-moves
PINNED never auto-moves
deadline policy matrix
DateOnly deadline resolution
unknown remaining-effort case
TaskDependency planning case
chunking boundary cases
stale branch refusal
deterministic rebase
atomic Apply rollback
DST/timezone cases
no hidden system clock/random input
```

---

# 20. READY gate

D6 must remain `BLOCKED_BY_DECISION` until:

```text
[ ] PlanningProfile/Constraint semantics frozen
[ ] task eligibility frozen
[ ] TaskPriority influence frozen
[ ] dependency scheduling semantics frozen
[ ] DeadlinePolicy semantics frozen
[ ] OverflowPolicy semantics frozen
[ ] DateOnly deadline resolution frozen
[ ] unknown-effort behavior frozen
[ ] availability/chunking semantics frozen
[ ] Academic/Exam/AllDay/Floating Planner participation frozen
[ ] OD-020 resolved
[ ] OD-021 resolved
[ ] module boundary approved
```

Only then should D6-01/D6-02 be rewritten as implementation-ready specs.
