# Agentic Scheduler — D7 Mutation / History / Causality Decisions

> Status: **D7-00 FROZEN**  
> Date: 2026-09-12  
> Applies to: D7 durable mutation journal, ChangeLog, Undo, DVV/HLC foundation

This document freezes the decisions required to implement D7. D7 remains sequenced after D6 closes, but no additional architecture decision is required for D7-01/02/03 unless implementation discovers a contradiction with an earlier frozen contract.

---

# HST-001 — One logical transaction, one MutationId

One successful logical application transaction owns exactly one `MutationId`.

```text
user/application intent
→ validate
→ one application write transaction
    ├─ Active State mutation group
    ├─ ChangeLog group
    ├─ causal metadata allocation
    └─ durable SyncOperation journal row
→ commit all or none
```

A `MutationId` identifies an ordered group of one or more typed entity mutations. Planner Apply therefore remains one logical mutation even when it creates/moves/resizes/deletes several FocusBlocks.

D9 `AgentAction` may reference one or more `MutationId`s but never replaces this transaction boundary.

`MutationId` uses the canonical application UUIDv7 generator.

---

# HST-002 — Typed mutation vocabulary v1

D7 records semantic source-fact operations, never SQL patches, Room records, or arbitrary JSON commands.

V1 mutation kinds are frozen for source facts already persisted by D4-D6:

```text
EventPut(before?, after)
TaskPut(before?, after)
PlanningProfilePut(before?, after)
FocusBlockPut(before?, after)
FocusBlockDelete(before)
WorkLogAppend(after)
TaskDependencyPut(before?, after)
AcademicYearPut(before?, after)
SemesterPut(before?, after)                  // aggregate includes AcademicWeek state
CoursePut(before?, after)
PeriodTemplatePut(before?, after)            // aggregate includes period state
AcademicHolidayPut(before?, after)
CourseScheduleRulePut(before?, after)        // aggregate includes teaching-week state
CourseOccurrenceExceptionPut(before?, after)
ExamPut(before?, after)
```

`before == null` means creation. `Put` does not authorize UI editing or deletion that does not already exist; it only gives a durable semantic vocabulary to an authorized application write.

`CourseSession` is derived and never appears as a mutation kind.

`PlanBranch`, request-scoped `PlanningConstraint`, Calendar projections, and ContextSummary are not synchronized source facts and never appear in the D7 mutation vocabulary.

WorkLog remains append-oriented: an existing WorkLog ID with different content is invalid, not an update.

---

# HST-002A — Strongly typed semantic images

> Status: **D7.1 AMENDMENT — FROZEN before D8 receive implementation**

Every synchronized `EntityMutation` owns a canonical, strongly typed semantic
image. `GenericFactImage` and `SemanticField` were pre-release implementation
drafts and are removed; they have no compatibility or decoder obligation.

`shared:sync` continues to own the serializable image DTOs and does not depend
on Domain. `:shared:application` owns explicit two-way mappers between Domain
objects and those images. The mapper reconstructs Domain objects so existing
Domain and cross-entity validation remains the final authority.

The semantic image model uses dedicated enums and sealed variants for states
such as time placement, deadlines, course time, room override, and exam
schedule. It must not encode those states as kind strings plus nullable fields.
Canonical collection ordering is part of normalized equality:

```text
AcademicWeek / AcademicPeriod        number ascending
TeachingWeekSet                       ascending and unique
PlanningProfile availability          weekday, then start/end
DVV context                           ReplicaId lexicographic
```

The D7 journal's `LocalJournalCodec.version` remains `1`: no released durable
data exists, so old development JSON is intentionally unsupported. This changes
no Room schema and does not alter the HST-002 operation vocabulary.

---

# HST-003 — Delete/tombstone scope v1

D7 authorizes synchronized deletion semantics only for `FocusBlock`, because D6 already has an explicit `deleteFocusBlock` application/persistence path.

D7 does **not** invent Event, Task, Academic, PlanningProfile, WorkLog, or TaskDependency deletion.

A successful `FocusBlockDelete`:

```text
deletes the active FocusBlock row
+ appends ChangeLog facts including the before image
+ appends the typed delete operation
+ persists a FocusBlock tombstone carrying identity + causal metadata
```

Tombstones are never physically compacted in D7. OD-032 remains pending and is not a D7 blocker because compaction is disabled.

A causally older FocusBlock `Put` cannot resurrect a tombstoned block. Concurrent `Put` vs `Delete` remains a D8 semantic conflict.

---

# HST-004 — ChangeLog shape

History is append-only and grouped by MutationId.

Conceptual durable shape:

```text
MutationRecord
- mutationId
- origin
- DVV
- HLC
- committedAt informational local instant

ChangeLogEntry
- entryId
- mutationId
- ordinal
- entityKind
- entityId
- operationKind
- beforeImage?
- afterImage?
```

`ordinal` is stable within the mutation group and participates in canonical replay ordering.

Active State is authoritative current truth. ChangeLog is authoritative committed history. Neither is reconstructed from natural-language descriptions.

`committedAt` is diagnostic/UI metadata only; causal semantics use DVV and deterministic merge semantics, never wall-clock LWW.

V1 origins:

```text
USER
PLANNER
UNDO(originalMutationId)
SYSTEM
```

D9 may add `AGENT(agentActionId)` through an explicit schema/protocol amendment.

Database migrations themselves are not user ChangeLog mutations.

---

# HST-005 — Undo is compensation, never history deletion

Undo creates a new compensating MutationId. It never removes or rewrites the original history.

V1 support matrix:

```text
Event update                 SUPPORTED if current == original after-image
Task update                  SUPPORTED if current == original after-image
PlanningProfile update       SUPPORTED if current == original after-image
FocusBlock create            SUPPORTED -> FocusBlockDelete
FocusBlock move/resize/put   SUPPORTED if current == original after-image
FocusBlock delete            SUPPORTED -> restore same ID if still absent and tombstone is current
Planner Apply mutation group SUPPORTED atomically if every child inverse precondition holds

Event create                 UNSUPPORTED (no Event delete contract)
Task create                  UNSUPPORTED (no Task delete contract)
Academic create/update       UNSUPPORTED in D7 v1
TaskDependency put           UNSUPPORTED in D7 v1
WorkLog append               UNSUPPORTED in D7 v1
```

A grouped Undo is all-or-none. If one child inverse is unsafe, no child is applied.

Expected results are structured:

```text
UndoApplied(newMutationId)
UndoUnsupported(reason)
UndoConflict(entity/group/current facts)
UndoNotFound
```

Undo compares typed normalized facts, not database row bytes.

---

# HST-006 — Dotted Version Vector

D7 creates `:shared:sync` and places causal primitives there.

Typed concepts:

```text
ReplicaId = UUIDv7 identity for one installed logical replica
Dot(replicaId, counter)
DottedVersionVector(context, dot)
```

Rules:

```text
counter is a persisted non-negative 64-bit integer per ReplicaId
one committed local MutationId consumes exactly one next local counter
allocation occurs inside the same database transaction as Active State + journal
failed/rolled-back transactions do not publish a dot
remote application merges observed context before the next local mutation
```

DVV determines:

```text
causally-before
causally-after
same/equivalent history
concurrent
```

Map serialization/canonical display orders ReplicaIds lexicographically. Map ordering itself has no causal meaning.

A restored application installation is a new ReplicaId unless the user intentionally restores the replica causal state as part of a full device backup.

---

# HST-007 — Hybrid Logical Clock

HLC is ordering/diagnostic metadata, never semantic conflict resolution.

V1 timestamp:

```text
HlcTimestamp(
    physicalMillis: Long,
    logical: Long,
    replicaId: ReplicaId,
)
```

Local tick from `(last, wallNow)`:

```text
physical = max(last.physical, wallNow)
logical  = if physical > last.physical then 0 else last.logical + 1
```

Receive tick from `(last, remote, wallNow)` follows the standard HLC merge:

```text
physical = max(last.physical, remote.physical, wallNow)

if physical == last.physical && physical == remote.physical:
    logical = max(last.logical, remote.logical) + 1
else if physical == last.physical:
    logical = last.logical + 1
else if physical == remote.physical:
    logical = remote.logical + 1
else:
    logical = 0
```

Stable display ordering is `(physicalMillis, logical, replicaId)`.

Persist the last local HLC. Wall-clock rollback must never decrease emitted HLC order.

---

# HST-008 — Internal SyncOperation journal

D7 persists one internal `SyncOperation` per MutationId.

Conceptual shape:

```text
SyncOperation(
    mutationId,
    dvv,
    hlc,
    origin,
    orderedMutations,
)
```

The operation journal is durable even before network transport exists.

D7 has no delivery/acknowledgement truth because no server exists yet. D8 adds transport state separately; it must not mutate operation semantics to mean delivery status.

Replay of the same operation against the same causal state is deterministic and idempotent.

The journal may use the shared transport-neutral DTOs/codec selected for D8, but D7 does not open sockets or define server behavior.

---

# HST-009 — Module/dependency boundary

D7 authorizes:

```text
:shared:sync
```

Ownership:

```text
:shared:domain
    current business/source-fact semantics

:shared:sync
    ReplicaId / DVV / HLC
    typed SyncOperation semantic DTOs
    deterministic causal helpers
    no Room / Ktor server / UI

:shared:application
    MutationCoordinator
    ChangeLog / history / Undo use cases
    application command integration
    depends on :shared:sync + :shared:domain

:shared:database
    Room records/mappers/repositories for D7 durable state
```

`:shared:sync` must not depend on `:shared:application` or `:shared:database`.

No sync metadata is added to Domain entity classes.

---

# HST-010 — Local journal codec

D7 may persist typed mutation images as versioned JSON using:

```text
org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0
Kotlin serialization plugin version = project Kotlin version
```

The local codec is explicit and versioned. Domain types do not receive wire annotations; sync/history DTOs own serialization annotations and mapping.

D8 freezes the production outer transport envelope. A local journal codec version is not permission to bypass D8 E2EE/wire rules.

---

# HST-011 — Persistence migration

D7 increments the then-current Room schema `N -> N+1`.

Required durable concerns:

```text
mutation_record
change_log_entry
sync_operation_journal
replica_causal_state
focus_block_tombstone
```

Exact SQL/Room names are locally reversible. Required invariants are not:

```text
MutationId unique
(change mutationId, ordinal) unique
local Replica counter + HLC durable
journal row atomic with Active State write
FocusBlock tombstone survives active-row deletion
```

No destructive migration fallback.

---

# HST-012 — History read boundary

Application read APIs expose capabilities equivalent to:

```text
history.timeline(cursor?, limit)
history.getMutation(mutationId)
history.getEntityChanges(entityKind, entityId, cursor?, limit)
history.getDiff(mutationId)
history.canUndo(mutationId)
history.undo(mutationId)
```

History reads never mutate Active State. `undo` is a new application write operation and therefore gets its own MutationId/ChangeLog/SyncOperation.

---

# HST-013 — D7 completion gate

D7 implementation is complete only when:

```text
[ ] every successful supported local write is journaled atomically
[ ] forced failure rolls back Active State + ChangeLog + SyncOperation + causal counter
[ ] Planner Apply remains one grouped MutationId
[ ] typed operation replay is deterministic/idempotent
[ ] supported Undo matrix passes success/conflict tests
[ ] unsupported Undo returns structured result without mutation
[ ] FocusBlock tombstone semantics pass causal replay tests
[ ] DVV sequential/concurrent/dominance tests pass
[ ] HLC wall-clock rollback/receive tests pass
[ ] migration N -> N+1 passes with existing D6 data
[ ] no generic SQL/JSON mutation API exists
[ ] no object-level LWW exists
[ ] repository-wide CI is green
```

D7 implementation starts only after D6 is explicitly complete.
