# READ FIRST — Agentic Scheduler Development Guardrails

> Status: **Mandatory entrypoint for implementation work**

This file exists to make the project's decision system unambiguous for humans and coding agents.

Before implementing any non-trivial task, read the relevant project documents. Do not treat missing detail as permission to invent a lasting design choice.

---

# Authoritative decision order

Use the following precedence when deciding implementation behavior:

```text
1. Current approved Task Spec / acceptance criteria
2. DOMAIN_INVARIANTS.md
3. OPEN_DECISIONS.md
   - a PENDING item blocks autonomous selection
   - a RESOLVED item is binding at the level stated
4. Approved ADRs / milestone decision documents
5. IMPLEMENTATION_CONTRACT.md
6. ARCHITECTURE_DIAGRAMS.md
7. MODULE_OWNERSHIP.md
8. UBIQUITOUS_LANGUAGE.md
9. CODING_AGENT_POLICY.md
10. Existing established code pattern
11. Local engineering judgment for LOCAL_REVERSIBLE details only
```

`OPEN_DECISIONS.md` does not override a higher frozen invariant. Its special purpose is to state explicitly whether a high-impact implementation choice is authorized.

If approved sources conflict:

```text
DO NOT GUESS
→ BLOCKED_BY_SPEC_CONFLICT
→ identify the exact conflicting statements
→ implement only independent, non-disputed work
```

---

# Meaning of an unspecified choice

For a purely local, reversible implementation detail, normal engineering judgment is allowed.

For anything that affects:

```text
Domain semantics
public/cross-module contracts
business defaults
persistence compatibility
Planner determinism
Sync / merge / causality
E2EE / secret handling
Agent memory/history authority
Tool permissions
module boundaries
device interoperability
server/client responsibility
user data loss
```

an unspecified choice means:

> **the decision is missing, not that the coding agent may choose freely.**

Consult `OPEN_DECISIONS.md` or register/escalate the missing decision.

---

# Current module/application baseline

D4 is complete.

Current modules are:

```text
:shared:domain
:shared:application
:shared:planner
:shared:sync
:shared:database
:apps:android
:apps:desktop
:apps:wear
```

D4 resolved the local persistence and repository boundary:

```text
:shared:application
→ owns application-facing repository/transaction contracts
→ depends on :shared:domain

:shared:database
→ implements :shared:application persistence contracts
→ maps Room records ↔ Domain
→ owns Room/SQLite schema/migrations
```

The concrete D4 stack is frozen by `docs/PERSISTENCE_DECISIONS.md` and `docs/tasks/D4_PERSISTENCE.md`.

Coding agents must not revisit Room/SQLite choice or repository placement inside unrelated tasks.

OD-012 local database encryption at rest remains pending and is a production-sensitive-data gate.

---

# Clarification: domain error behavior

The resolved baseline is:

```text
Single-object/value invariant violation
→ cannot enter valid Domain State
→ enforce at construction/private validated factory
→ Kotlin precondition/fail-fast behavior is acceptable

Expected multi-entity/business operation failure
→ explicit operation-specific result/error state
→ not generic null/false
```

No third-party `Either` library is authorized by default.

---

# Mandatory no-guess examples

A coding agent must not independently decide any of the following:

```text
"I'll default Task priority to MEDIUM."
"I'll use LWW for this sync conflict."
"I'll move repository interfaces into Domain."
"I'll replace Room with SQLDelight because it is easier here."
"I'll use provider conversation IDs as Agent memory."
"I'll delete the old AgentAction when Undo happens."
"I'll choose an encryption algorithm now."
"I'll add Koin while wiring this feature."
"I'll upgrade Kotlin/Gradle as part of the fix."
"I'll add serialization annotations now for future sync."
"I'll implement a later milestone because the current feature may need it someday."
```

Each is either explicitly forbidden or requires a recorded decision.

---

# Required documents by task class

## Domain work

```text
READ_FIRST.md
DOMAIN_INVARIANTS.md
IMPLEMENTATION_CONTRACT.md
UBIQUITOUS_LANGUAGE.md
MODULE_OWNERSHIP.md
OPEN_DECISIONS.md
CODING_AGENT_POLICY.md
current Task Spec
```

## Academic work

Add:

```text
ACADEMIC_INVARIANTS.md
ACADEMIC_DECISIONS.md
relevant D3 task/amendment docs
```

## Calendar/application surface work

Add:

```text
CALENDAR_DECISIONS.md
ARCHITECTURE_DIAGRAMS.md
D5 Task Spec
OD-060 / OD-061 status as applicable
```

## Planner work

Add:

```text
ARCHITECTURE_DIAGRAMS.md
Planner decision/spec documents
OD-020 / OD-021 resolution
```

## Persistence work

Add:

```text
PERSISTENCE_DECISIONS.md
D4 persistence task/compatibility baseline
OD-010 / OD-011 / OD-012 as applicable
```

## Sync work

Add:

```text
Sync/E2EE specs and ADRs
OD-030 through OD-033 resolutions as applicable
```

## E2EE / credential work

Add:

```text
Security/E2EE specs and ADRs
OD-040 through OD-042 resolutions as applicable
```

## Agent/context work

Add:

```text
Agent spec
History/Context architecture sections
OD-050 through OD-054 resolutions as applicable
```

## Wear AI/provider work

Add:

```text
Wear capability/provider architecture
security decisions for credential provisioning
```

---

# Task readiness rule

A task may be marked `READY` only when every high-impact decision required by its acceptance path is either:

```text
already frozen
or
explicitly resolved in Task Spec / ADR / OPEN_DECISIONS / approved milestone decision doc
```

A future decision that the task does not touch may remain pending.

---

# Scope rule

Milestones are scope fences.

```text
D2 does not get to implement D4 because D4 will need the same entity.
D5 does not get to invent Planner occupancy because a calendar item is visible.
D7 does not get to freeze a wire protocol merely because D8 will need one.
Sync does not get to weaken Domain semantics because merge would be easier.
Agent does not get to bypass Tools because direct writes would be simpler.
```

Build only what the current task needs while preserving the later architecture boundaries already frozen.

---

# Truth rule for implementation agents

When explaining the application itself, factual authority remains:

```text
Current Domain State
>
Structured ToolResult / ChangeLog
>
Structured Agent State
>
Raw Conversation
>
Generated ContextSummary
```

When deciding how to write project code, documentation/decision authority follows the separate development precedence defined at the top of this file.

Do not confuse these two hierarchies.

---

# Final instruction

**Autonomy is encouraged in execution and discouraged in unapproved architecture creation.**

A visible `BLOCKED_BY_DECISION` is healthier than a hidden guess that becomes a migration problem six milestones later.
