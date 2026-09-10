# Agentic Scheduler — Coding Agent Instructions

This file is the mandatory repository entrypoint for coding agents.

## Before coding

Read, in order:

1. the current Task Spec / acceptance criteria;
2. `docs/READ_FIRST.md`;
3. `docs/DOMAIN_INVARIANTS.md`;
4. `docs/OPEN_DECISIONS.md`;
5. relevant ADRs/specifications;
6. `docs/IMPLEMENTATION_CONTRACT.md`;
7. `docs/ARCHITECTURE_DIAGRAMS.md`;
8. `docs/MODULE_OWNERSHIP.md`;
9. `docs/UBIQUITOUS_LANGUAGE.md`;
10. `docs/CODING_AGENT_POLICY.md`;
11. affected code and tests.

Do not start from nearby code patterns before checking the frozen contracts.

## Core rule

**Do not turn an unspecified high-impact choice into an implementation decision.**

If an ambiguity affects Domain semantics, public/cross-module contracts, business defaults, persistence compatibility, Planner determinism, Sync, E2EE, Agent context/history, Tool permissions, module boundaries, device interoperability, security, or user-data loss:

```text
DO NOT GUESS
→ check docs/OPEN_DECISIONS.md
→ if unresolved, mark BLOCKED_BY_DECISION
→ implement only independent safe work
```

Only `LOCAL_REVERSIBLE` implementation details may be chosen autonomously.

## Non-negotiable invariants

```text
IDs are client-generated and immutable.
Time semantics are richer than Unix timestamps.
Task != Event.
Task != FocusBlock.
Course != repeating Event.
AI never mutates Domain/storage directly.
PlanBranch != Active Calendar.
Sync metadata != Domain semantics.
Provider session != Agent memory.
Agent conversation continuity is application-owned.
Agent can inspect authoritative ChangeLog/history.
Context compaction never destroys authoritative history.
```

See `docs/DOMAIN_INVARIANTS.md` for the full invariant set.

## No invented defaults

Do not invent product-semantic defaults such as priority, flexibility, reminder timing, deadline/overflow behavior, Agent autonomy, Tool permission, Provider/model, PlanningProfile, or history/context retention.

Require explicit values or a recorded decision.

## Architecture boundaries

`shared:domain` is pure Domain code and must not depend on:

```text
Room / database APIs
Ktor / networking
Compose / UI
Android Context / Wear APIs
LLM Provider SDKs
Sync transport
Crypto implementation
```

Domain objects do not receive persistence or future wire-format annotations for convenience.

The only legal LLM mutation path is:

```text
LLM proposal
→ typed Tool Call
→ validation
→ deterministic Domain/Planner rules
→ permission
→ Preview/PlanBranch when required
→ atomic transaction
→ ToolResult + AgentAction + ChangeLog
→ SyncOperation
```

No LLM/provider-to-database bypass is allowed.

## Open decisions are blockers

A `PENDING` item in `docs/OPEN_DECISIONS.md` is explicitly **not authorized** for autonomous selection.

Do not reason that an unspecified option is allowed because no document forbids it.

## Current D2 scope fence

Until a more specific D2 Task Spec overrides this section:

### D2 MUST

- implement requested pure Domain IDs/value objects/entities;
- enforce specified invariants;
- use canonical vocabulary;
- add deterministic common tests.

### D2 MAY

- add pure helper/value types required by D2;
- add an explicitly approved multiplatform time dependency via the version catalog.

### D2 MUST NOT

- add Room schema/DAO/repository implementation;
- add SyncOperation wire serialization;
- add server/network code;
- add Agent runtime/provider adapters;
- implement later Planner algorithms;
- build UI feature flows;
- introduce DI/navigation/state frameworks;
- add future serialization annotations;
- create new Gradle modules unless the Task Spec explicitly authorizes them.

## Determinism

Do not introduce hidden semantic inputs such as system time reads inside pure logic, random IDs in tests, unordered iteration as a tie-break, implicit platform timezone/locale, or unseeded randomness.

## Completion

A task is complete only when requested behavior and required tests are complete and no required acceptance path remains blocked.

Report:

```text
Implemented
Tests/verification actually run
Architecture/invariant impact
LOCAL_REVERSIBLE assumptions
BLOCKED_BY_DECISION items
Intentionally out-of-scope follow-ups
```

A passing build does not by itself prove semantic completion.
