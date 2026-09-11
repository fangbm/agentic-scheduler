# Agentic Scheduler — Reviewed Roadmap D5–D9

> Status: **Roadmap Baseline — individual Task Specs remain authoritative**  
> Baseline: D5-01 complete; D5-02 ready; D6 complete  
> Date: 2026-09-11

This roadmap records intended sequencing only. It does not authorize a milestone whose required decisions remain `PENDING`.

---

# Sequence

```text
D5-01 Calendar / Application read surface      COMPLETE
D5-02 Event/Task creation + editing            READY (parallel follow-on)
 ↓
D6  Deterministic Planner + PlanBranch         COMPLETE
 ↓
D7  Mutation Journal / ChangeLog / Undo / DVV-HLC
 ↓
D8  E2EE Multi-device Sync + Thin Server
 ↓
D9  Agent Runtime + Typed Tools
```

D5-02 may be implemented in parallel with D6 because it is limited to Event/Task create-edit flows and does not own Planner semantics. The shared UUIDv7 generator/application boundary is an explicit integration touchpoint and must be reconciled before merge.

The main milestone order remains deliberate:

```text
Domain semantics
→ persistence
→ observable calendar surface
→ deterministic planning
→ auditable mutations/history
→ encrypted synchronization
→ LLM orchestration
```

The Agent comes after deterministic Tools/Planner/history semantics exist. It must orchestrate established capabilities rather than define their truth.

---

# D5 — Calendar / Application Surface

D5-01 Calendar projection / Agenda-Day baseline is complete.

D5-02 explicit Event/Task creation/editing is now implementation-ready. The production UUIDv7 implementation previously deferred under OD-004 is frozen by D6-00 / PLN-019 and is reused by D5-02.

D5-02 scope is intentionally narrow:

```text
Android/Desktop Event create + edit
Android/Desktop Task create + edit
Wear remains read-only
no delete
no Planner invocation
no ChangeLog/SyncOperation/AgentAction
no academic timetable authoring
```

Authoritative D5 sources:

```text
docs/CALENDAR_DECISIONS.md
docs/tasks/D5_CALENDAR_SURFACE.md
docs/tasks/D5_02_CREATION_EDITING.md
```

---

# D6 — Deterministic Planner

D6-00 is complete and D6 implementation is authorized.

Split:

```text
D6-00  Planner semantic decisions             COMPLETE
D6-01  deterministic Planner engine           COMPLETE
D6-02  PlanBranch preview/rebase/apply        COMPLETE
```

Authoritative sources:

```text
docs/PLANNER_DECISIONS.md
docs/tasks/D6_DETERMINISTIC_PLANNER.md
docs/OPEN_DECISIONS.md  // OD-020 / OD-021 / OD-004 resolved
```

The frozen D6 contract covers:

```text
PlanningProfile real rule set
Constraint model
Task eligibility
TaskPriority planning influence
TaskDependency scheduling semantics
DeadlinePolicy / OverflowPolicy semantics
DateOnly deadline resolution
unknown remaining-effort behavior
working/availability windows
chunking/min/preferred/max FocusBlock rules
Academic/Exam participation
AllDay/Floating planning participation
OD-020 lexicographic deterministic decision model
OD-021 Local Reflow deterministic search/tie-break
:shared:planner module authorization
session-scoped PlanBranch lifecycle
atomic Apply
production UUIDv7 generation
```

Inherited rule remains:

```text
HARD + UNPINNED still cannot be moved automatically by Planner.
PINNED is additional user protection, not the only source of immovability.
```

---

# D7 — Mutation / History / Causality Foundation

Recommended split:

```text
D7-01  typed mutation groups + ChangeLog
D7-02  explicit Undo support matrix
D7-03  DVV/HLC + internal SyncOperation journal
```

D7 freezes local semantic mutation/history behavior before network transport exists.

Production wire serialization remains D8 scope unless OD-030 is intentionally resolved earlier.

Do not create generic tombstone/delete behavior before synchronized delete semantics are explicitly frozen. D6's FocusBlock delete authorization is local active-state behavior only and does not resolve synchronized deletion/tombstone semantics.

---

# D8 — E2EE Multi-device Sync

D8 remains `BLOCKED_BY_DECISION` until the required security/protocol/auth choices exist.

Minimum blockers:

```text
OD-030 wire encoding/versioning
OD-031 semantic merge matrix
OD-040 content encryption
OD-041 device pairing/key approval
server/account authentication model
device authentication/session model
sync-space/workspace identity + key scope
server module boundary
exact Ktor/PostgreSQL dependency versions
```

Server remains transport/storage infrastructure and does not own plaintext merge semantics.

OD-012 local database encryption at rest remains a separate production-user-data gate; E2EE does not replace it.

---

# D9 — Agent Runtime

Recommended split:

```text
D9-00  context/permission/provider decisions
D9-01  shared Agent core + Android/Desktop integration
D9-02  synchronized AgentThread/history protocol amendment
D9-03  Wear Agent/provider provisioning after OD-042
```

Minimum D9-01 blockers:

```text
OD-050 context budgeting
OD-051 compaction lifecycle
OD-053 AgentThread retention/deletion
Tool permission/autonomy baseline
provider adapter transport/dependency
provider credential secure-storage policy
```

OD-052 semantic embedding retrieval may remain deferred for v1.

OD-054 is required before external/MCP Tool compatibility is promised, not for purely internal Tool evolution.

---

# Cross-milestone schema rule

Future Task Specs MUST NOT pre-write a migration such as:

```text
v1 → v2
```

unless the immediately preceding frozen milestone guarantees that exact source version.

D6 is now the exception by construction: D5-01 and D5-02 own no schema change and the D6 decision contract explicitly freezes migration `v1 -> v2` in `PLANNER_DECISIONS.md`.

For later milestones use:

```text
current schema N → N+1
```

and replace `N` only when the implementation task is approved.

---

# Cross-milestone new-concept rule

Every new durable/synchronizable concept must define before production use:

```text
typed identity where applicable
primary owner
persistence mapping
local-only vs synchronized status
merge policy before joining Sync
retention/deletion semantics where applicable
canonical vocabulary
```

This especially applies to:

```text
Constraint
PlanBranch
ChangeLog
SyncOperation
SyncConflict
AgentThread
AgentMessage
AgentAction
```

D6 explicitly keeps request Constraints and PlanBranch local/session-scoped and non-synchronized.

---

# Production data security reminder

OD-012 remains `PENDING` after D4.

D5–D7 may be implemented and tested against the D4 plaintext local database baseline, but no milestone may describe the product as ready for production-sensitive user data until local-at-rest protection is explicitly resolved.
