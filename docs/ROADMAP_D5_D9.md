# Agentic Scheduler — Reviewed Roadmap D5–D9

> Status: **Roadmap Baseline — individual Task Specs remain authoritative**  
> Baseline: D4 complete  
> Date: 2026-09-11

This roadmap records intended sequencing only. It does not authorize a milestone whose required decisions remain `PENDING`.

---

# Sequence

```text
D5  Calendar / Application Surface
 ↓
D6  Deterministic Planner + PlanBranch
 ↓
D7  Mutation Journal / ChangeLog / Undo / DVV-HLC
 ↓
D8  E2EE Multi-device Sync + Thin Server
 ↓
D9  Agent Runtime + Typed Tools
```

The order is deliberate:

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

Split:

```text
D5-01  Calendar projection + conflict facts + Agenda/Day read UI
D5-02  Explicit creation/editing after production UUIDv7 generator decision
```

D5-01 is implementation-ready through:

```text
docs/CALENDAR_DECISIONS.md
docs/tasks/D5_CALENDAR_SURFACE.md
```

D5-02 must resolve the exact production ID generator implementation under OD-004 before creating new entities.

---

# D6 — Deterministic Planner

Recommended split:

```text
D6-00  Planner semantic decisions
D6-01  deterministic Planner engine
D6-02  PlanBranch preview/rebase/apply
```

D6 cannot become `READY` until it freezes at minimum:

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
chunking/min/max FocusBlock rules
Academic/Exam participation
AllDay/Floating planning participation
OD-020 scoring model
OD-021 Local Reflow deterministic search/tie-break
```

Inherited rule:

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

Do not create generic tombstone/delete behavior before synchronized delete semantics are explicitly frozen.

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

Use:

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

---

# Production data security reminder

OD-012 remains `PENDING` after D4.

D5–D7 may be implemented and tested against the D4 plaintext local database baseline, but no milestone may describe the product as ready for production-sensitive user data until local-at-rest protection is explicitly resolved.
