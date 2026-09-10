# Agentic Scheduler

The D1 bootstrap for a local-first, end-to-end encrypted scheduler.

## Modules

- `shared:domain`: pure Kotlin Multiplatform domain boundary, shared by Android, Desktop, and Wear OS.
- `shared:database`: KMP module boundary reserved for the later persistence implementation; intentionally contains no schema, Room, or repository code in D1.
- `apps:android`: Android Compose shell.
- `apps:desktop`: Compose Desktop shell.
- `apps:wear`: Compose for Wear OS shell.

The current code scope is intentionally limited to D1. No persistence, account,
sync, E2EE, AI, or planner implementation belongs in this bootstrap baseline.

## Development guardrails

Before implementing D2 or any non-trivial feature, start with:

- [`docs/READ_FIRST.md`](docs/READ_FIRST.md) — authoritative development decision order and no-guess rules.
- [`docs/DOMAIN_INVARIANTS.md`](docs/DOMAIN_INVARIANTS.md) — frozen Domain and Agent invariants.
- [`docs/IMPLEMENTATION_CONTRACT.md`](docs/IMPLEMENTATION_CONTRACT.md) — implementation choices and prohibited autonomous decisions.
- [`docs/OPEN_DECISIONS.md`](docs/OPEN_DECISIONS.md) — high-impact choices that are explicitly unresolved; `PENDING` means do not guess.
- [`docs/MODULE_OWNERSHIP.md`](docs/MODULE_OWNERSHIP.md) — ownership of semantics, validation, state, transactions, and side effects.
- [`docs/UBIQUITOUS_LANGUAGE.md`](docs/UBIQUITOUS_LANGUAGE.md) — canonical project vocabulary.
- [`docs/CODING_AGENT_POLICY.md`](docs/CODING_AGENT_POLICY.md) — mandatory policy for delegated coding work.
- [`docs/TASK_SPEC_TEMPLATE.md`](docs/TASK_SPEC_TEMPLATE.md) — template for deterministic milestone/task delegation.
- [`docs/ARCHITECTURE_DIAGRAMS.md`](docs/ARCHITECTURE_DIAGRAMS.md) — detailed deterministic runtime/data-flow architecture.

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
