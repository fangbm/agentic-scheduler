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
4. Approved ADRs
5. IMPLEMENTATION_CONTRACT.md
6. ARCHITECTURE_DIAGRAMS.md
7. MODULE_OWNERSHIP.md
8. UBIQUITOUS_LANGUAGE.md
9. CODING_AGENT_POLICY.md
10. Existing established code pattern
11. Local engineering judgment for LOCAL_REVERSIBLE details only
```

`OPEN_DECISIONS.md` does not override a higher frozen invariant. Its special purpose is to state explicitly that a high-impact implementation choice is **not yet authorized**.

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

# Clarification: local persistence baseline

The persistence **family is already frozen**:

```text
SQLite + Room/KMP direction
```

The statement in `IMPLEMENTATION_CONTRACT.md` that D4 chooses/implements the concrete persistence stack must be interpreted as:

```text
D4 chooses/freezes:
- exact Room version
- exact KMP driver/configuration
- schema/table design
- migration strategy
- schema export/test setup
- transaction integration
- mapping details
```

It does **not** authorize a coding agent to choose SQLDelight or another persistence engine instead of Room/SQLite.

Before D4, D2/D3 still MUST NOT introduce Room annotations/schema/repository implementation early.

---

# Clarification: application/repository boundary

Repository contracts must not be placed into `shared:domain` simply because no application module exists yet.

Their final application-layer placement is explicitly tracked as `OD-011` in `OPEN_DECISIONS.md` and must be resolved before the first repository interface is added.

Do not create `:shared:core` or another module solely to resolve this locally unless the task/decision explicitly authorizes that module.

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
"I'll put repository interfaces in Domain for now."
"I'll add SQLDelight because it is easier here."
"I'll use provider conversation IDs as Agent memory."
"I'll delete the old AgentAction when Undo happens."
"I'll choose an encryption algorithm now."
"I'll add Koin while wiring this feature."
"I'll upgrade Kotlin/Gradle as part of the fix."
"I'll add serialization annotations now for future sync."
"I'll implement D4 persistence while doing D2 because we need it later."
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

## Planner work

Add:

```text
ARCHITECTURE_DIAGRAMS.md
Planner ADR/spec
OD-020 / OD-021 resolution
```

## Persistence work

Add:

```text
D4 persistence decision/spec
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
explicitly resolved in Task Spec / ADR / OPEN_DECISIONS
```

A future decision that the task does not touch may remain pending.

---

# Scope rule

Milestones are scope fences.

```text
D2 does not get to implement D4 because D4 will need the same entity.
D4 does not get to design Sync because persistence will later be synchronized.
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
