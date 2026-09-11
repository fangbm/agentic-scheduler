# Agentic Scheduler — Coding Agent Instructions

This file is the mandatory repository entrypoint for coding agents.

## Before coding

Read, in order:

1. the current Task Spec / acceptance criteria;
2. `docs/READ_FIRST.md`;
3. `docs/DOMAIN_INVARIANTS.md`;
4. milestone-specific invariant/decision documents referenced by the current Task Spec;
5. `docs/OPEN_DECISIONS.md`;
6. relevant ADRs/specifications;
7. `docs/IMPLEMENTATION_CONTRACT.md`;
8. `docs/ARCHITECTURE_DIAGRAMS.md`;
9. `docs/MODULE_OWNERSHIP.md`;
10. `docs/UBIQUITOUS_LANGUAGE.md`;
11. `docs/CODING_AGENT_POLICY.md`;
12. affected code and tests.

Do not start from nearby code patterns before checking the frozen contracts.

A historical milestone's task document remains authoritative for that milestone, but its old scope fence is not automatically the current repository scope. The **current Task Spec** defines the active positive and negative scope.

## Core rule

**Do not turn an unspecified high-impact choice into an implementation decision.**

If an ambiguity affects Domain semantics, public/cross-module contracts, business defaults, persistence compatibility, Planner determinism, Sync, E2EE, Agent context/history, Tool permissions, module boundaries, device interoperability, security, or user-data loss:

```text
DO NOT GUESS
→ check the current Task Spec and milestone decision docs
→ check docs/OPEN_DECISIONS.md
→ if unresolved, mark BLOCKED_BY_DECISION
→ implement only independent safe work
```

Only `LOCAL_REVERSIBLE` implementation details may be chosen autonomously.

## Current module baseline

The repository currently contains:

```text
:shared:domain
:shared:application
:shared:database
:apps:android
:apps:desktop
:apps:wear
```

A future Task Spec may explicitly authorize another module. Do not create one merely because it seems cleaner.

## Non-negotiable invariants

```text
IDs are client-generated and immutable.
Time semantics are richer than Unix timestamps.
Task != Event.
Task != FocusBlock.
Course != repeating Event.
CourseSession is derived academic state, not an independently editable Event.
AI never mutates Domain/storage directly.
PlanBranch != Active Calendar.
Sync metadata != Domain semantics.
Provider session != Agent memory.
Agent conversation continuity is application-owned.
Agent can inspect authoritative ChangeLog/history.
Context compaction never destroys authoritative history.
```

See `docs/DOMAIN_INVARIANTS.md` and milestone-specific invariant documents for the full invariant set.

## No invented defaults

Do not invent product-semantic defaults such as priority, flexibility, reminder timing, deadline/overflow behavior, Agent autonomy, Tool permission, Provider/model, PlanningProfile, academic holiday behavior, course occurrence behavior, calendar participation, or history/context retention.

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

D4 established the current application/persistence boundary:

```text
:shared:application
    → consumes :shared:domain
    → owns application-facing repository/transaction contracts

:shared:database
    → implements :shared:application persistence contracts
    → maps database records ↔ :shared:domain
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

A current Task Spec or milestone-specific decision document may explicitly resolve a decision for that milestone. When it does, follow the more specific approved decision and keep the global register synchronized as part of the same documentation change.

Do not reason that an unspecified option is allowed because no document forbids it.

## Determinism

Do not introduce hidden semantic inputs such as:

```text
system time reads inside pure logic
random IDs in deterministic tests
unordered iteration as a tie-break
implicit platform timezone/locale
silent DST offset selection
unseeded randomness
LLM preference masquerading as Planner truth
```

When a current Task Spec defines a deterministic comparator, projection, resolver, Planner rule, or merge rule, identical complete inputs must produce identical structured outputs across supported clients.

## Scope control

Follow the current Task Spec's `MUST`, `MAY`, and `MUST NOT` sections exactly.

Do not implement a later milestone merely because its architecture is already documented. A useful future abstraction is not sufficient authorization to add it now.

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
