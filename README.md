# Agentic Scheduler

A local-first Kotlin Multiplatform scheduler. D8 client E2EE and the first opaque sync-server relay slice are implemented on the D8 review branch; the full multi-device/server acceptance gate is still open.

The repository includes the completed D1–D4 foundations, completed D5-01 Calendar/Agenda surface, implemented D5-02 Event/Task creation-editing flow, completed D6 deterministic Planner/PlanBranch work, and implemented D7 mutation/history/causality foundation. D5-02/D6.5 build and packaging verification passed locally on 2026-09-20; Desktop dogfood passed interactively, and the Android Planner surface plus New Task dialog respond on the API 36 emulator. Full Android Planner input smoke remains pending. D8 client SyncEngine/E2EE, local exact-envelope retry, Ktor HTTPS transport, invitation/bootstrap, opaque enrollment relay, recovery-envelope relay, revocation metadata, and the `:server:sync` relay baseline are implemented on the D8 review branch; secondary-device credential completion/key rotation, production catch-up wiring, Wear route-equivalence, and multi-device adversarial gates remain pending.

## Modules

- `shared:domain`: pure Kotlin Multiplatform domain boundary, shared by Android, Desktop, and Wear OS.
- `shared:application`: application-facing repository/transaction contracts plus shared Calendar projection and Event/Task editing orchestration.
- `shared:database`: Room 3 / SQLite KMP persistence implementation, including exported client schema v10, records, mappers, repositories, and platform database builders.
- `shared:planner`: pure deterministic Planner and PlanBranch logic.
- `shared:sync`: causal metadata and transport-neutral SyncOperation semantics.
- `server:sync`: Ktor/PostgreSQL opaque encrypted-envelope relay; it never decodes SyncPayload or owns semantic merge.
- `apps:android`: Android Compose shell.
- `apps:desktop`: Compose Desktop shell.
- `apps:wear`: Compose for Wear OS shell.

D1 is the architectural bootstrap baseline, not the current feature ceiling. Do not implement a future milestone early merely because its architecture is already documented.

## Current milestone documents

- [`docs/tasks/D4_PERSISTENCE.md`](docs/tasks/D4_PERSISTENCE.md) — completed D4 persistence contract and acceptance record.
- [`docs/CALENDAR_DECISIONS.md`](docs/CALENDAR_DECISIONS.md) — frozen decisions for the first Calendar/Agenda surface.
- [`docs/tasks/D5_CALENDAR_SURFACE.md`](docs/tasks/D5_CALENDAR_SURFACE.md) — completed D5-01 Calendar projection + Agenda/Day task.
- [`docs/tasks/D5_02_CREATION_EDITING.md`](docs/tasks/D5_02_CREATION_EDITING.md) — implemented D5-02 Event/Task creation-editing task; verification pending.
- [`docs/ROADMAP_D5_D9.md`](docs/ROADMAP_D5_D9.md) — reviewed sequencing and decision gates for D5–D10, including D6.5 dogfood scope and D10 final product UI/UX.
- [`docs/PLANNER_DECISIONS.md`](docs/PLANNER_DECISIONS.md) — frozen D6 Planner product/semantic contract.
- [`docs/PLANNER_REWRITE_DECISIONS.md`](docs/PLANNER_REWRITE_DECISIONS.md) — frozen D6-00A clarifications for reservation state, rollback, finite candidates, and HARD cutoff coverage.
- [`docs/tasks/D6_DETERMINISTIC_PLANNER.md`](docs/tasks/D6_DETERMINISTIC_PLANNER.md) — D6 deterministic Planner + PlanBranch outer/public contract.
- [`docs/tasks/D6_PLANNER_CORE_REWRITE.md`](docs/tasks/D6_PLANNER_CORE_REWRITE.md) — completed focused rewrite and review record for the D6 Planner core.
- [`docs/tasks/D7_OPERATION_HISTORY_SYNC_FOUNDATION.md`](docs/tasks/D7_OPERATION_HISTORY_SYNC_FOUNDATION.md) — implemented and verified mutation/history/causality foundation.
- [`docs/tasks/D8_E2EE_SYNC_TRANSPORT.md`](docs/tasks/D8_E2EE_SYNC_TRANSPORT.md) — frozen encrypted Sync/transport task for D8.
- [`docs/tasks/D9_AGENT_RUNTIME.md`](docs/tasks/D9_AGENT_RUNTIME.md) — frozen Agent runtime task for D9.

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
