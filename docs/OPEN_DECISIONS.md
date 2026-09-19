# Agentic Scheduler — Open Decisions Register

> Status: **Mandatory Decision Register**  
> Updated: 2026-09-12  
> Purpose: ensure an undecided architecture choice is never mistaken for permission to guess.

A `PENDING` item means contributors MUST NOT choose that architecture/security behavior on their own. `RESOLVED` decisions are frozen by the cited source. `DEFERRED` features are intentionally outside the current implementation gate.

---

# Already frozen / not open

```text
Client stack                 Kotlin Multiplatform
Android/Desktop UI           Compose / Compose Multiplatform
Wear UI                      Compose for Wear OS
JVM target                   17
Local persistence            Room 3 / SQLite KMP
Server framework             Kotlin + Ktor
Server database              PostgreSQL
Production sync              custom semantic sync; not Git runtime
Causality                    Dotted Version Vector
Logical ordering             HLC
Merge                        semantic groups + explicit conflicts; no generic LWW
IDs                          client-generated immutable UUIDv7
Agent principle              Agentic Surface, Deterministic Core
LLM writes                   typed Tool path only
Agent memory                 application-owned
PlanBranch                   isolated proposal; stale cannot blind-apply
Wear                         real local-first replica
Academic CourseSession       deterministic derived projection
```

---

# D2 / Domain

## OD-001 — Exact multiplatform date/time dependency

```text
Status: RESOLVED
Decision: kotlinx-datetime 0.8.0; Kotlin stdlib Instant/Clock; Kotlin Duration;
          no java.time in commonMain.
Source: D2 Task Spec + version catalog
```

## OD-002 — Domain invalid-construction convention

```text
Status: RESOLVED
Decision: local/simple invariants fail fast; expected multi-entity/business failures
          use explicit operation result types; no third-party Either framework.
Source: D2 Task Spec
```

## OD-003 — Immutable retained collections

```text
Status: RESOLVED
Decision: immutable public Domain state; retained collection state uses
          kotlinx-collections-immutable 0.5.2 where aliasing/mutation matters.
Source: docs/IMMUTABLE_COLLECTIONS_DECISION.md
```

## OD-004 — ID generation

```text
Status: RESOLVED
Decision: Domain consumes typed IDs; application/infrastructure injects RFC UUIDv7
          generator using Clock + SecureRandom; lowercase canonical text; no UUID lib.
Source: docs/PLANNER_DECISIONS.md PLN-019
```

---

# D3 / Academic

## OD-005 — Academic week / teaching-week representation

```text
Status: RESOLVED
Decision: explicit seven-day AcademicWeek ranges; TeachingWeekSet canonical explicit set;
          odd/even/range strings are input syntax only.
Source: docs/ACADEMIC_DECISIONS.md AD-001/002
```

## OD-006 — Course rule / occurrence identity

```text
Status: RESOLVED
Decision: one rule = one weekday/time spec; multiple meetings = multiple rules;
          CourseOccurrenceKey(ruleId, academicWeekNumber) is stable identity.
Source: docs/ACADEMIC_DECISIONS.md AD-003
```

## OD-007 — Academic time/timezone/DST

```text
Status: RESOLVED
Decision: ClockTime | PeriodBased; Semester owns TimeZone; wall-clock semantics;
          ambiguous/nonexistent transitions rejected.
Source: docs/ACADEMIC_DECISIONS.md AD-004/005
```

## OD-008 — CourseSession source/precedence

```text
Status: RESOLVED
Decision: CourseSession derived; source facts are rule/template/holiday/exception;
          exception > holiday suspension > base rule.
Source: docs/ACADEMIC_DECISIONS.md AD-006..012
```

## OD-009 — D3 Academic surfaces

```text
Status: RESOLVED
Decision: exact D3 entities/ExamSchedule frozen by D3 specs/amendments.
Source: docs/tasks/D3_ACADEMIC_DOMAIN.md
```

---

# D4 / Persistence

## OD-010 — Room/KMP configuration

```text
Status: RESOLVED
Decision: Room 3.0.3; SQLite KMP 2.7.0; BundledSQLiteDriver; KSP 2.3.12;
          exported schemas; no destructive migration fallback.
Source: docs/PERSISTENCE_DECISIONS.md
```

## OD-011 — Repository port placement

```text
Status: RESOLVED
Decision: application repository ports in :shared:application;
          Room implementations in :shared:database.
Source: docs/PERSISTENCE_DECISIONS.md
```

## OD-012 — Local database encryption at rest

```text
Status: PENDING
Must resolve by: before claiming production-sensitive local-data readiness
Impact: SECURITY / PRIVACY
```

D8 secure key storage/E2EE does not automatically encrypt ordinary local SQLite rows.

---

# Planner

## OD-020 — Planner decision model

```text
Status: RESOLVED
Decision: deterministic lexicographic placement; no weights/stochastic score.
Source: docs/PLANNER_DECISIONS.md PLN-015
        + docs/PLANNER_REWRITE_DECISIONS.md
```

## OD-021 — Local Reflow

```text
Status: RESOLVED
Decision: bounded deterministic move-only repair over explicit affected set/window;
          preserve ID/duration; all-or-none failure.
Source: docs/PLANNER_DECISIONS.md PLN-016
```

## OD-022 — Timefold benchmark

```text
Status: DEFERRED
Decision: benchmark/reference spike only; never runtime without a new decision.
```

---

# D7 History / causality

## OD-033 — Sync operation granularity

```text
Status: RESOLVED
Decision: one logical application transaction = one MutationId = one atomic ordered
          typed entity-mutation group = one internal SyncOperation journal record.
Source: docs/HISTORY_SYNC_DECISIONS.md HST-001/HST-008
```

D7 additionally freezes typed mutation vocabulary, explicit Undo support, FocusBlock-only tombstone semantics, DVV/HLC, and `:shared:sync` in `docs/HISTORY_SYNC_DECISIONS.md`.

---

# D8 Sync / protocol

## OD-030 — Wire encoding/versioning

```text
Status: RESOLVED
Decision: kotlinx.serialization JSON 1.11.0; EncryptedEnvelopeV1 + encrypted
          SyncPayloadV1; explicit versions; unknown inner operation quarantines whole
          MutationId; Domain has no wire annotations.
Source: docs/SYNC_SECURITY_DECISIONS.md SYN-003
```

## OD-031 — Semantic merge matrix

```text
Status: RESOLVED FOR D8 V1 ENTITY SET
Decision: DVV causality; concurrent disjoint semantic groups merge; same group equal
          coalesces; same group different => SyncConflict; no timestamp/object LWW;
          one MutationId remains atomic.
Source: docs/SYNC_SECURITY_DECISIONS.md SYN-013/SYN-014
```

Future synchronized entity kinds require an explicit matrix amendment before joining the protocol.

## OD-032 — Tombstone compaction safety

```text
Status: PENDING
Must resolve by: before physical tombstone/history compaction
Impact: DATA_LOSS
```

D7/D8 retain FocusBlock tombstones indefinitely in v1; this pending item does not block sync while compaction stays disabled.

---

# D8 E2EE / device security

## OD-040 — Content encryption

```text
Status: RESOLVED
Decision: Tink Java/Android 1.23.0; AES256_GCM SyncPayload encryption;
          Tink-managed nonce; fixed authenticated AAD; no custom crypto.
Source: docs/SYNC_SECURITY_DECISIONS.md SYN-004/SYN-005
```

## OD-041 — Device pairing/key approval

```text
Status: RESOLVED
Decision: device HPKE X25519/HKDF-SHA256/AES-256-GCM; pending enrollment;
          existing-device explicit approval + 8-digit SAS; 256-bit Recovery Secret;
          revocation rotates AMK + SyncSpace content key epoch.
Source: docs/SYNC_SECURITY_DECISIONS.md SYN-006/SYN-007
```

## OD-042 — ProviderCredentialEnvelope crypto

```text
Status: RESOLVED
Decision: target-device Tink HPKE using the D8 device key; version + target + config +
          credential revision bound as AAD; monotonic anti-rollback; never SyncOperation.
Source: docs/SYNC_SECURITY_DECISIONS.md SYN-018
```

---

# D9 Agent / context

## OD-050 — Context budgeting

```text
Status: RESOLVED
Decision: provider adapter supplies deterministic BudgetUnits/capacity; reserve >=20%
          for output; mandatory system/tools/current-command/anchor; remaining input
          filled by frozen authority-priority classes/caps.
Source: docs/AGENT_DECISIONS.md AGT-009
```

## OD-051 — Context compaction

```text
Status: RESOLVED
Decision: compact oldest closed prefix when raw/prompt pressure crosses frozen gates;
          retain recent/unresolved interactions; immutable source ranges; summary is
          local derived non-authoritative state; failure never deletes raw history.
Source: docs/AGENT_DECISIONS.md AGT-010
```

## OD-052 — Semantic history retrieval / embeddings

```text
Status: DEFERRED FOR D9 V1
Must resolve by: first semantic embedding/index implementation
Impact: ARCHITECTURE / PRIVACY
```

Structured exact history retrieval is sufficient for D9-01.

## OD-053 — AgentThread retention/deletion

```text
Status: RESOLVED
Decision: no automatic time purge; explicit thread deletion purges active raw
          conversational rows while committed AgentAction/ChangeLog audit remains;
          D9-02 propagates causal thread tombstone; no promise of retroactive erasure
          of every historical encrypted byte.
Source: docs/AGENT_DECISIONS.md AGT-011/AGT-013
```

## OD-054 — External Tool/MCP schema compatibility

```text
Status: PENDING
Must resolve by: before MCP/external Tool compatibility is promised
```

Internal D9 Tools may evolve during alpha.

---

# UI

## OD-060 — Shared UI state/navigation architecture

```text
Status: PENDING
Current default: minimal Compose state + constructor/manual dependency composition;
                 no project-wide framework introduction.
Must resolve by: if feature complexity requires a shared framework.
```

## OD-061 — Calendar rendering

```text
Status: RESOLVED FOR D5 AGENDA/DAY
Decision: semantic projection in :shared:application; platform Compose rendering;
          viewport-bounded lazy Agenda/Day; no shared UI module.
Source: docs/CALENDAR_DECISIONS.md
```

---

# Infrastructure

## OD-070 — Project-wide logging framework

```text
Status: PENDING
Current default: minimal platform diagnostics; never log secrets/private plaintext.
```

## OD-071 — Configuration/secrets abstraction

```text
Status: RESOLVED FOR D8/D9 BASELINE
Decision: server secrets via environment/secret injection; ordinary non-secret server
          config via Ktor ApplicationConfig/environment; client secrets behind
          PlatformSecretStore + SecretRef; no universal config framework.
Source: docs/SYNC_SECURITY_DECISIONS.md SYN-009/SYN-010
        + docs/AGENT_DECISIONS.md AGT-007
```

---

# External calendar

## OD-080 — Internal/external calendar mapping

```text
Status: PENDING
Must resolve by: first bidirectional CalDAV/Google/Outlook adapter
Impact: ARCHITECTURE / LOOP-PREVENTION
```

ICS import/export may define a narrower mapping separately.

---

# Decision workflow

When a remaining pending decision becomes necessary:

```text
implementation reaches pending choice
→ compare alternatives/security/compatibility impact
→ explicit decision/ADR
→ update this register + authoritative decision/task docs
→ implementation gate opens
```

**Unknown is a valid explicit project state. Hidden guesses are not.**