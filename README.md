# Agentic Scheduler

A local-first, end-to-end encrypted scheduler built with Kotlin Multiplatform.

The repository began with the D1 bootstrap. D2 Core Domain and D3 Academic Domain contracts/implementation now extend that baseline; later persistence, Planner, Sync, E2EE, Agent, and server milestones remain governed by their own scope fences and decisions.

## Modules

- `shared:domain`: pure Kotlin Multiplatform domain boundary, shared by Android, Desktop, and Wear OS.
- `shared:database`: KMP module boundary reserved for the later persistence implementation; intentionally contains no schema, Room, or repository code before D4 authorizes them.
- `apps:android`: Android Compose shell.
- `apps:desktop`: Compose Desktop shell.
- `apps:wear`: Compose for Wear OS shell.

D1 is the architectural bootstrap baseline, not the current feature ceiling. Do not implement a future milestone early merely because its architecture is already documented.

## Development guardrails

Before implementing any non-trivial feature, start with:

- [`docs/READ_FIRST.md`](docs/READ_FIRST.md) — authoritative development decision order and no-guess rules.
- [`docs/DOMAIN_INVARIANTS.md`](docs/DOMAIN_INVARIANTS.md) — frozen Domain and Agent invariants.
- [`docs/IMPLEMENTATION_CONTRACT.md`](docs/IMPLEMENTATION_CONTRACT.md) — implementation choices and prohibited autonomous decisions.
- [`docs/OPEN_DECISIONS.md`](docs/OPEN_DECISIONS.md) — high-impact choices that are explicitly unresolved; `PENDING` means do not guess.
- [`docs/MODULE_OWNERSHIP.md`](docs/MODULE_OWNERSHIP.md) — ownership of semantics, validation, state, transactions, and side effects.
- [`docs/UBIQUITOUS_LANGUAGE.md`](docs/UBIQUITOUS_LANGUAGE.md) — canonical project vocabulary.
- [`docs/CODING_AGENT_POLICY.md`](docs/CODING_AGENT_POLICY.md) — mandatory policy for delegated coding work.
- [`docs/TASK_SPEC_TEMPLATE.md`](docs/TASK_SPEC_TEMPLATE.md) — template for deterministic milestone/task delegation.
- [`docs/ARCHITECTURE_DIAGRAMS.md`](docs/ARCHITECTURE_DIAGRAMS.md) — detailed deterministic runtime/data-flow architecture.
- [`docs/IMMUTABLE_COLLECTIONS_DECISION.md`](docs/IMMUTABLE_COLLECTIONS_DECISION.md) — frozen immutable/persistent collection policy and dependency decision.
- [`docs/tasks/D3_IMMUTABLE_COLLECTIONS_AMENDMENT.md`](docs/tasks/D3_IMMUTABLE_COLLECTIONS_AMENDMENT.md) — narrow D3 amendment/migration plan for immutable collection ownership.

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
