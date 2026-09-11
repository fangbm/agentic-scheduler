# Agentic Scheduler — Open Decisions Register

> Status: **Mandatory Decision Register**  
> Purpose: ensure an undecided architecture choice is never mistaken for permission to guess.

A `PENDING` item in this document means:

> **Coding agents and contributors MUST NOT choose an implementation on their own.**

The relevant implementation task remains partially or fully `BLOCKED_BY_DECISION` until the item is resolved through an explicit task decision or ADR.

This register does not list every local implementation detail. It lists choices whose accidental selection would create lasting coupling, compatibility cost, security risk, or cross-module divergence.

---

# Already frozen / not open

The following are **not** open decisions and must not be revisited inside unrelated tasks:

```text
Client stack               Kotlin Multiplatform
Android/Desktop UI         Compose / Compose Multiplatform
Wear UI                    Compose for Wear OS
JVM target                 17
Local persistence family   SQLite with Room/KMP direction
Server framework           Kotlin + Ktor
Server database            PostgreSQL
Blob storage               S3-compatible / MinIO
Production sync            custom Git-like semantic sync; NOT Git runtime
Causality                  Dotted Version Vector
Logical ordering           HLC
Merge                      semantic merge + explicit conflicts
IDs                        client-generated immutable UUIDv7 semantics
Domain collections         kotlinx.collections.immutable 0.5.2 for retained immutable collection state
Agent principle            Agentic Surface, Deterministic Core
LLM writes                 typed Tool path only; never direct DB writes
Agent memory               application-owned, not Provider-owned
History                    ChangeLog/AgentAction inspectable by Agent
PlanBranch                 isolated proposal state; stale cannot blind-apply
Wear                       real local-first node
Wear LLM default           Watch-originated request; Phone config inheritance
Server plaintext           schedule/task/attachment plaintext not required
Academic occurrence identity CourseOccurrenceKey(ruleId, academicWeekNumber)
Academic CourseSession       deterministic derived projection, not source of truth
```

Exact implementation details beneath these decisions may still appear below as pending.

---

# Decision states

```text
PENDING     — implementation is not authorized to choose yet
RESOLVED    — decision is frozen; source/ADR must be linked
DEFERRED    — explicitly out of current roadmap scope; do not implement
SUPERSEDED  — replaced by another recorded decision
```

---

# D2 / Domain decisions

## OD-001 — Exact multiplatform date/time dependency version

```text
Status: RESOLVED
Decision: org.jetbrains.kotlinx:kotlinx-datetime:0.8.0
Compatibility baseline: Kotlin 2.3.21
Usage: kotlinx-datetime for LocalDate/LocalDateTime/TimeZone semantics;
       Kotlin stdlib kotlin.time.Instant / kotlin.time.Clock where instant/clock
       semantics are required; Kotlin Duration for durations.
No java.time in commonMain.
Source: D2 Core Domain Task Spec + repository Version Catalog
Impact: CONTRACT_AFFECTING
```

The dependency is centralized in `gradle/libs.versions.toml` and exposed to `shared:domain` through the version catalog. Coding agents must not substitute the `0.6.x-compat` artifact or downgrade/upgrade it inside unrelated work.

## OD-002 — Domain invalid-construction error convention

```text
Status: RESOLVED
Decision: simple single-value/object invariants fail fast at construction using
          Kotlin precondition mechanisms or private validated factories;
          expected multi-entity/business operation failures use explicit
          operation-specific result/error types.
No third-party Either library.
Impact: CONTRACT_AFFECTING
```

This means invalid `TimeRange(start >= end)` is a programmer/input-boundary error that cannot enter active Domain State, while operations such as dependency-cycle creation or stale PlanBranch application expose explicit expected failures.

## OD-003 — Domain object mutability and retained collection ownership

```text
Status: RESOLVED
Decision:
- immutable public Domain state by default; val/copy/explicit transitions;
- retained public collection-valued Domain state uses immutable collection types
  when caller aliasing or later mutation could violate the object's invariants;
- selected implementation baseline is
  org.jetbrains.kotlinx:kotlinx-collections-immutable:0.5.2;
- public stored state prefers ImmutableList/ImmutableSet/ImmutableMap;
- PersistentList/PersistentSet/PersistentMap are used when structural sharing or
  persistent-update operations are intentionally part of the state transition;
- ordinary Collection/List/Set/Map remain valid for non-retained inputs and local
  deterministic algorithm scratch/results where mutability does not escape.
Source: docs/IMMUTABLE_COLLECTIONS_DECISION.md
Impact: CONTRACT_AFFECTING / ARCHITECTURE_AFFECTING
```

The library is experimental/API-subject-to-change, so the exact version is pinned and may not be silently changed by unrelated work. This decision is first authorized for implementation by `docs/tasks/D3_IMMUTABLE_COLLECTIONS_AMENDMENT.md`; it does not retroactively invalidate D2's milestone-local prohibition on adding a collections framework.

## OD-004 — ID generation implementation

```text
Status: RESOLVED
Decision:
- Domain receives strongly typed IDs; constructors never generate IDs;
- application/infrastructure owns an injectable production generator;
- production generator uses RFC UUIDv7 layout with 48-bit Unix epoch milliseconds
  from injected Clock and rand_a/rand_b from java.security.SecureRandom on the
  currently supported Android/Wear/JVM targets;
- canonical output is lowercase hyphenated UUIDv7 text;
- no third-party UUID dependency is introduced;
- tests use deterministic injected clock/random sources.
Source: docs/PLANNER_DECISIONS.md PLN-019
        + docs/tasks/D6_DETERMINISTIC_PLANNER.md
Impact: CONTRACT_AFFECTING
```

No strict monotonic ordering within one millisecond is promised by v1. The frozen Domain UUIDv7 validation/identity semantics remain unchanged.

---

# D3 / Academic decisions

## OD-005 — Academic week and teaching-week representation

```text
Status: RESOLVED
Decision:
- Semester owns explicit seven-day AcademicWeek ranges.
- week numbers are consecutive from 1; gaps between week date ranges are allowed.
- TeachingWeekSet is the canonical explicit sorted/deduplicated week selection.
- ODD/EVEN/range strings are input syntax only, not Domain storage variants.
Source: docs/ACADEMIC_DECISIONS.md AD-001 / AD-002
Impact: CONTRACT_AFFECTING
```

## OD-006 — Course rule and occurrence identity

```text
Status: RESOLVED
Decision:
- one CourseScheduleRule has one weekday and one CourseTimeSpec;
- multiple weekly meetings use multiple rules;
- CourseOccurrenceKey(scheduleRuleId, academicWeekNumber) is stable session identity;
- D3 has no generated CourseSessionId.
Source: docs/ACADEMIC_DECISIONS.md AD-003
Impact: ARCHITECTURE_AFFECTING
```

## OD-007 — Academic time, period, timezone, and DST semantics

```text
Status: RESOLVED
Decision:
- CourseTimeSpec = ClockTime | PeriodBased;
- PeriodTemplate explicitly maps AcademicPeriodNumber to local clock ranges;
- Semester owns explicit TimeZone;
- base course times use wall-clock semantics;
- ambiguous/nonexistent timezone transitions are rejected, not guessed.
Source: docs/ACADEMIC_DECISIONS.md AD-004 / AD-005
Impact: CONTRACT_AFFECTING / DETERMINISM
```

## OD-008 — CourseSession source of truth and exception precedence

```text
Status: RESOLVED
Decision:
- CourseSession is a deterministic derived projection;
- authoritative state is rules/templates/holidays/occurrence exceptions;
- one-off changes use CourseOccurrenceException;
- precedence is explicit occurrence exception > holiday suspension > base rule;
- room override distinguishes Unchanged / Set / Clear.
Source: docs/ACADEMIC_DECISIONS.md AD-006 / AD-007 / AD-008 / AD-010 / AD-011 / AD-012
Impact: ARCHITECTURE_AFFECTING / CORRECTNESS
```

## OD-009 — D3 Academic entity surfaces

```text
Status: RESOLVED
Decision:
- exact D3 AcademicYear, Semester, Course, AcademicHoliday, and Exam surfaces are
  frozen by docs/tasks/D3_ACADEMIC_DOMAIN.md plus approved D3 amendments;
- ExamSchedule = Unscheduled | DateOnly | Exact;
- Academic entities do not receive implicit Planner movement/occupancy defaults.
Source: docs/ACADEMIC_DECISIONS.md AD-009 / AD-013 / AD-014 / AD-015 / AD-016 / AD-017
        + docs/tasks/D3_IMMUTABLE_COLLECTIONS_AMENDMENT.md
Impact: CONTRACT_AFFECTING / BOUNDARY_AFFECTING
```

No D3-required Academic decision remains `PENDING`. Deferred Academic features are listed in `docs/ACADEMIC_DECISIONS.md` and must not be guessed into D3.

---

# D4 / Persistence decisions

## OD-010 — Concrete Room/KMP database configuration

```text
Status: RESOLVED
Decision: Room 3.0.3 with SQLite KMP 2.7.0 and BundledSQLiteDriver on
          Android, Wear OS, and JVM Desktop; KSP 2.3.12; Room schema v1
          exported to shared/database/schemas; AgenticSchedulerDatabase uses
          agentic-scheduler.db with no destructive-migration fallback.
Source: docs/PERSISTENCE_DECISIONS.md PD-001 / PD-002
        + docs/tasks/D4_PERSISTENCE.md
Impact: ARCHITECTURE_AFFECTING
```

The D4 persistence configuration is frozen. Later persistence work must retain the recorded compatibility and migration rules unless an explicit ADR or task supersedes them.

The resolved configuration covers:

- exact Room version;
- KMP platform driver/configuration;
- database file/version convention;
- schema export location;
- migration test strategy;
- transaction coordinator integration;
- mapping conventions.

D2/D3 must not add Room annotations/schema early.

## OD-011 — Application/repository port placement

```text
Status: RESOLVED
Decision: application-facing repository ports live in :shared:application
          under dev.agenticscheduler.application.persistence; Room/SQLite
          implementations remain in :shared:database.
Source: docs/PERSISTENCE_DECISIONS.md PD-003 / PD-004
        + docs/tasks/D4_PERSISTENCE.md
Impact: ARCHITECTURE_AFFECTING
```

The resolved boundary preserves these constraints:

- concrete persistence implementation belongs outside Domain;
- Domain entities do not depend on Room/database APIs;
- platform UI does not own repositories;
- repository placement must not invert dependency direction.

Coding agents must not move repository interfaces into `shared:domain` or create `shared:core` unless an explicit later decision supersedes this boundary.

## OD-012 — Local database encryption at rest

```text
Status: PENDING
Must resolve by: before handling production user data
Impact: SECURITY_OR_DATA_LOSS
```

E2EE protects synchronized/server data, but local-at-rest protection and platform key integration require a separate explicit decision.

Do not silently assume plaintext local SQLite is the final production security model, and do not introduce SQLCipher or another encryption dependency without this decision.

---

# Planner decisions

## OD-020 — Planner scoring model v1

```text
Status: RESOLVED
Decision: deterministic lexicographic decision model; no weighted floating-point
          score and no hidden LLM/provider preference. Task selection uses explicit
          deadline/dependency/priority ordering. Legal placement candidates rank by
          deadline legality/overflow, lateness, schedule preservation, movement,
          context-switch delta, chunk rank, time, then canonical identity.
Source: docs/PLANNER_DECISIONS.md PLN-015
        + docs/tasks/D6_DETERMINISTIC_PLANNER.md
Impact: ARCHITECTURE_AFFECTING
```

Hard feasibility is non-negotiable. Fragmentation, context-switch, disruption, schedule stability, and infeasibility behavior are explicitly frozen in `PLANNER_DECISIONS.md`.

## OD-021 — Local Reflow deterministic search/tie-break rules

```text
Status: RESOLVED
Decision: bounded non-cascading repair over an explicit affected set and explicit
          search window; move-only for eligible future FLEXIBLE/SOFT + UNPINNED
          FocusBlocks; preserve identity/duration; candidate position is the legal
          point closest to original start, then earlier start, then FocusBlockId;
          any unplaceable affected block makes the reflow Infeasible and applies none.
Source: docs/PLANNER_DECISIONS.md PLN-016
        + docs/tasks/D6_DETERMINISTIC_PLANNER.md
Impact: ARCHITECTURE_AFFECTING
```

Identical normalized snapshot + request must replay to equivalent semantic output ordering across supported clients.

## OD-022 — Timefold benchmark protocol

```text
Status: DEFERRED
Frozen direction: Timefold is benchmark/reference spike only, not runtime dependency
Impact: LOCAL/EXPERIMENTAL until benchmark begins
```

No coding task may add Timefold to production runtime without a new decision.

---

# Sync / protocol decisions

## OD-030 — SyncOperation wire encoding and schema versioning

```text
Status: PENDING
Must resolve by: before production SyncOperation serialization
Impact: ARCHITECTURE_AFFECTING
```

Must freeze:

- serialization library/format;
- envelope version field;
- backward/forward compatibility policy;
- unknown field/operation handling;
- canonical representation requirements;
- deterministic encoding requirements if hashes/signatures depend on bytes.

Domain serialization annotations must not be added preemptively before this decision.

## OD-031 — Field/group semantic merge matrix

```text
Status: PENDING
Must resolve by: before merge engine implementation
Impact: ARCHITECTURE_AFFECTING / DATA_LOSS
```

Must define merge behavior per entity/field group, including at minimum:

```text
Event time placement
Event title/description/location
Task status/effort/deadline
FocusBlock placement
Course rules/exceptions
Reminder targets
PlanBranch metadata
AgentThread/history records
```

No generic object-level LWW fallback is permitted.

## OD-032 — Tombstone compaction safety rule

```text
Status: PENDING
Must resolve by: before tombstone physical compaction
Impact: DATA_LOSS
```

Until resolved, preserve deletion history rather than guessing that a tombstone is safe to remove.

## OD-033 — Sync operation granularity

```text
Status: PENDING
Must resolve by: before sync protocol finalization
Impact: ARCHITECTURE_AFFECTING
```

Need exact boundary between logical transaction, AgentAction, Domain mutation operations, and one/multiple SyncOperations.

---

# E2EE / security decisions

## OD-040 — Concrete content encryption primitives/formats

```text
Status: PENDING
Must resolve by: before E2EE production implementation
Frozen hierarchy: Account Master Key → Workspace/Calendar Key → encrypted data
Impact: SECURITY_OR_DATA_LOSS
```

Must freeze audited standard primitives/library, nonce strategy, authenticated metadata, envelope versioning, key rotation, recovery, and test vectors.

Coding agents must never invent cryptography.

## OD-041 — Device pairing / key approval protocol v1

```text
Status: PENDING
Must resolve by: first multi-device E2EE enrollment implementation
Impact: SECURITY_OR_DATA_LOSS
```

Frozen product semantics:

- existing-device approval or Recovery Key;
- master key never stored server-side in plaintext;
- future QR pairing is allowed.

Exact protocol is not yet authorized.

## OD-042 — ProviderCredentialEnvelope cryptographic primitive

```text
Status: PENDING
Must resolve by: Wear provider credential provisioning implementation
Impact: SECURITY_OR_DATA_LOSS
```

Frozen semantics:

- target-device encrypted;
- only target Watch decrypts;
- versioned anti-rollback;
- revoke/wipe behavior;
- never ordinary workspace SyncOperation.

Do not choose HPKE/custom RSA/etc. without security decision.

---

# Agent / context decisions

## OD-050 — Context window budgeting policy

```text
Status: PENDING
Must resolve by: production ContextAssembler/compaction implementation
Impact: CONTRACT_AFFECTING
```

Must define deterministic budgets/priority classes for:

```text
system/tool schemas
current command
ContextAnchor
current domain facts
recent ToolResults
recent raw messages
ContextSummary
retrieved history
```

Provider token limits may parameterize the budget, but must not change factual authority rules.

## OD-051 — Context compaction trigger and summary lifecycle

```text
Status: PENDING
Must resolve by: first persistent AgentThread compaction implementation
Impact: ARCHITECTURE_AFFECTING
```

Must define:

- when compaction occurs;
- immutable source range tracking;
- incremental summary update strategy;
- summary invalidation/versioning;
- provider/model-change behavior;
- failure behavior.

Authoritative raw/history data must remain recoverable regardless of this decision.

## OD-052 — Semantic history retrieval / embedding implementation

```text
Status: PENDING
Must resolve by: semantic historical retrieval implementation
Impact: ARCHITECTURE_AFFECTING / PRIVACY
```

Must define:

- embedding model/provider/local option;
- whether embeddings are local-only or synchronized;
- index format;
- rebuild/version behavior;
- privacy boundary;
- fallback when semantic index unavailable.

Structured exact history search is independent and must not wait for this feature.

## OD-053 — AgentThread retention/deletion policy

```text
Status: PENDING
Must resolve by: before production privacy/retention behavior ships
Impact: PRIVACY / DATA_LOSS
```

Compaction is not deletion. User deletion/retention semantics must be explicit.

## OD-054 — Tool schema version compatibility

```text
Status: PENDING
Must resolve by: before externally exposed Tool/MCP compatibility is promised
Impact: CONTRACT_AFFECTING
```

Internal tools may evolve during development, but no agent may invent version negotiation semantics before this decision.

---

# UI decisions

## OD-060 — Shared UI state/navigation architecture

```text
Status: PENDING
Must resolve by: when feature complexity exceeds simple screen-local state
Current default: minimal Compose state + constructor/manual dependency composition;
                 no framework introduction.
Impact: ARCHITECTURE_AFFECTING if project-wide framework is introduced
```

Do not introduce MVI/navigation/DI frameworks preemptively.

## OD-061 — Calendar rendering architecture

```text
Status: RESOLVED FOR D5 AGENDA/DAY BASELINE
Decision:
- semantic calendar projection/grouping/ordering/conflict facts live in :shared:application;
- platform Compose code owns rendering, interaction, accessibility, and lifecycle;
- D5 uses viewport-bounded Agenda/Day lazy-list rendering;
- no shared UI Gradle module is introduced;
- Week-grid/Month-grid layout and large-calendar virtualization remain deferred
  and require an OD-061 amendment before their first production implementation.
Source: docs/CALENDAR_DECISIONS.md CD-001 / CD-002 / CD-007
        + docs/tasks/D5_CALENDAR_SURFACE.md
Impact: CONTRACT/PERFORMANCE
```

The D5 resolution is intentionally narrow: it prevents the first Agenda/Day renderer from entrenching business logic in UI while preserving freedom to choose a specialized Week/Month virtualization architecture later.

---

# Infrastructure decisions

## OD-070 — Project-wide logging framework

```text
Status: PENDING
Must resolve by: when logging grows beyond minimal platform diagnostics
Current default: minimal logging; never log secrets/private plaintext
Impact: CONTRACT/OPERATIONS
```

## OD-071 — Configuration/secrets abstraction

```text
Status: PENDING
Must resolve by: first production server/provider configuration implementation
Impact: SECURITY / OPERATIONS
```

Do not create a universal config format from a local milestone need.

---

# External calendar decisions

## OD-080 — Internal↔external calendar mapping contract

```text
Status: PENDING
Must resolve by: first bidirectional CalDAV/Google/Outlook adapter
Impact: ARCHITECTURE_AFFECTING
```

Must freeze external identity mapping, recurrence exception mapping, unsupported-field behavior, conflict ownership, and loop prevention.

ICS import/export may define its narrower mapping separately.

---

# Decision workflow

When a pending item becomes necessary:

```text
Task reaches PENDING decision
      ↓
Discuss alternatives and compatibility/security impact
      ↓
Choose explicit result
      ↓
ADR if architecture/security/data compatibility is affected
      ↓
Update this register to RESOLVED + link decision
      ↓
Update Implementation Contract / Invariants if needed
      ↓
Task becomes READY
```

Do not merely remove an item from this file. Preserve the decision record.

---

# Audit rule

Before a milestone is declared implementation-ready, review this register and verify that no `PENDING` item is required by that milestone's acceptance path.

A milestone can contain deferred future decisions; it cannot require an unresolved decision and still honestly be marked fully `READY`.

---

# Final rule

**Unknown is a valid explicit project state. Hidden guesses are not.**