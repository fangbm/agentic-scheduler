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

For D3 specifically, mandatory milestone documents include:

```text
docs/tasks/D3_ACADEMIC_DOMAIN.md
docs/ACADEMIC_INVARIANTS.md
docs/ACADEMIC_DECISIONS.md
```

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

Do not invent product-semantic defaults such as priority, flexibility, reminder timing, deadline/overflow behavior, Agent autonomy, Tool permission, Provider/model, PlanningProfile, academic holiday behavior, course occurrence behavior, or history/context retention.

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

A milestone-specific decision document may freeze additional decisions for that milestone. Coding agents must not reinterpret a `RESOLVED` milestone decision merely because the global register has not duplicated every detail.

Do not reason that an unspecified option is allowed because no document forbids it.

## Current D3 scope fence

The authoritative D3 scope is `docs/tasks/D3_ACADEMIC_DOMAIN.md`.

### D3 MUST

- implement the specified pure Academic Domain types and validators;
- implement deterministic CourseSession resolution;
- preserve explicit academic-week, period, timezone, holiday, and occurrence-exception semantics;
- use canonical vocabulary;
- add the required deterministic common tests.

### D3 MAY

- extend the existing typed UUIDv7 ID file with the exact D3 ID families;
- add small internal pure helpers needed by deterministic academic resolution;
- split Academic source files for readability without changing public architecture.

### D3 MUST NOT

- add Room schema/DAO/repository implementation;
- persist/materialize CourseSession as an authoritative source;
- add generic recurrence infrastructure for convenience;
- add school/CSV/Excel/ICS/screenshot import;
- add Planner movement/occupancy defaults to academic entities;
- add SyncOperation/wire serialization;
- add server/network code;
- add Agent runtime/provider adapters;
- build UI feature flows;
- introduce DI/navigation/state frameworks;
- add future serialization annotations;
- create new Gradle modules;
- add third-party dependencies;
- invent Course instructor/credits/color/location/reminder fields.

## Determinism

Do not introduce hidden semantic inputs such as system time reads inside pure logic, random IDs in tests, unordered iteration as a tie-break, implicit platform timezone/locale, silent DST offset selection, or unseeded randomness.

For D3, the same complete academic input must produce the same `CourseSessionResolutionResult` including canonical session/issue ordering.

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
