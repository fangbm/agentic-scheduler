# Agentic Scheduler

A local-first, end-to-end encrypted scheduler built with Kotlin Multiplatform.

The repository includes the D1 bootstrap, D2 Core Domain, D3 Academic Domain, and completed D4 local-persistence baseline. D5-01 now has an implementation-ready Calendar/Agenda contract; later Planner, History/Sync, E2EE, Agent, and server milestones remain governed by their own scope fences and decisions.

## Modules

- `shared:domain`: pure Kotlin Multiplatform domain boundary, shared by Android, Desktop, and Wear OS.
- `shared:application`: application-facing repository/transaction contracts and the home for shared application/query semantics introduced after D4.
- `shared:database`: Room 3 / SQLite KMP persistence implementation, including the exported v1 schema, records, mappers, repositories, and platform database builders.
- `apps:android`: Android Compose shell.
- `apps:desktop`: Compose Desktop shell.
- `apps:wear`: Compose for Wear OS shell.

D1 is the architectural bootstrap baseline, not the current feature ceiling. Do not implement a future milestone early merely because its architecture is already documented.

## Current milestone documents

- [`docs/tasks/D4_PERSISTENCE.md`](docs/tasks/D4_PERSISTENCE.md) — completed D4 persistence contract and acceptance record.
- [`docs/CALENDAR_DECISIONS.md`](docs/CALENDAR_DECISIONS.md) — frozen decisions for the first Calendar/Agenda surface.
- [`docs/tasks/D5_CALENDAR_SURFACE.md`](docs/tasks/D5_CALENDAR_SURFACE.md) — D5-01 implementation-ready Calendar projection + Agenda/Day task.
- [`docs/ROADMAP_D5_D9.md`](docs/ROADMAP_D5_D9.md) — reviewed sequencing and decision gates for D5–D9.
- [`docs/tasks/D6_DETERMINISTIC_PLANNER.md`](docs/tasks/D6_DETERMINISTIC_PLANNER.md) — blocked Planner draft; decision checklist before implementation.
- [`docs/tasks/D7_OPERATION_HISTORY_SYNC_FOUNDATION.md`](docs/tasks/D7_OPERATION_HISTORY_SYNC_FOUNDATION.md) — draft mutation/history/causality foundation.
- [`docs/tasks/D8_E2EE_SYNC_TRANSPORT.md`](docs/tasks/D8_E2EE_SYNC_TRANSPORT.md) — security/protocol-blocked encrypted Sync draft.
- [`docs/tasks/D9_AGENT_RUNTIME.md`](docs/tasks/D9_AGENT_RUNTIME.md) — context/permission/provider-blocked Agent draft.

## Development guardrails

Before implementing any non-trivial feature, start with:

- [`docs/READ_FIRST.md`](docs/READ_FIRST.md) — authoritative development decision order and no-guess rules.
- [`docs/DOMAIN_INVARIANTS.md`](docs/DOMAIN_INVARIANTS.md) — frozen Domain and Agent invariants.
- [`docs/IMPLEMENTATION_CONTRACT.md`](docs/IMPLEMENTATION_CONTRACT.md) — implementation choices and prohibited autonomous decisions.
- [`docs/OPEN_DECISIONS.md`](docs/OPEN_DECISIONS.md) — high-impact choices that are explicitly unresolved; `PENDING` means do not guess.
- [`docs/MODULE_OWNERSHIP.md`](docs/MODULE_OWNERSHIP.md) — ownership of semantics, validation, state, transactions, and side effects.
- [`docs/UBIQUITOUS_LANGUAGE.md`](docs/UBIQUITOUS_LANGUAGE.md) — canonical project vocabulary.
- [`docs/CODING_AGENT_POLICY.md`](docs/CODING_AGENT_POLICY.md) — mandatory policy for delegated coding work.
- [`docs/DEPENDENCY_POLICY.md`](docs/DEPENDENCY_POLICY.md) — mandatory dependency-selection policy; every Agent-selected third-party dependency must propose and pin an exact version.
- [`docs/TASK_SPEC_TEMPLATE.md`](docs/TASK_SPEC_TEMPLATE.md) — template for deterministic milestone/task delegation.
- [`docs/ARCHITECTURE_DIAGRAMS.md`](docs/ARCHITECTURE_DIAGRAMS.md) — detailed deterministic runtime/data-flow architecture.
- [`docs/IMMUTABLE_COLLECTIONS_DECISION.md`](docs/IMMUTABLE_COLLECTIONS_DECISION.md) — frozen immutable/persistent collection policy and dependency decision.

A missing high-impact decision is a visible project state, not permission for a coding agent to invent a default.

## Build

Use JDK 17 and an Android SDK containing API 37.0 and Build Tools 37.0.0:

```powershell
$env:JAVA_HOME = 'C:\Program Files\Java\jdk-17.0.1'
.\gradlew.bat build
.\gradlew.bat :apps:desktop:run
```

On Windows, `gradlew.bat` defaults to the ignored project-local
`.gradle-user-home` cache. This avoids a Gradle 9.6 test-worker issue in
Windows user profiles whose path contains non-ASCII characters. Set
`GRADLE_USER_HOME` before invoking the wrapper to override that default.
