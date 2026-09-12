# Agentic Scheduler — D7 Operation Journal, History, Undo & Causality Foundation

> Task ID: **D7-01 / D7-02 / D7-03**  
> Milestone: **D7 — Audit / Mutation / Sync Foundation**  
> Status: **SPEC FROZEN — READY AFTER D6**  
> Date: 2026-09-12  
> Decision source: `docs/HISTORY_SYNC_DECISIONS.md`

---

# 1. Goal

Make every successful local logical mutation durable, auditable, undo-aware, and causally identifiable before network synchronization exists.

D7 output:

```text
MutationId + atomic typed mutation group
ChangeLog
explicit limited Undo
ReplicaId + DVV + HLC
:shared:sync causal/operation model
durable internal SyncOperation journal
FocusBlock tombstone semantics
history read APIs
Room migration N -> N+1
```

Production network transport/E2EE/server remain D8.

---

# 2. Required reading

```text
docs/DOMAIN_INVARIANTS.md
docs/IMPLEMENTATION_CONTRACT.md
docs/MODULE_OWNERSHIP.md
docs/OPEN_DECISIONS.md
docs/HISTORY_SYNC_DECISIONS.md
docs/PLANNER_DECISIONS.md
docs/PLANNER_REWRITE_DECISIONS.md
docs/tasks/D6_DETERMINISTIC_PLANNER.md
docs/tasks/D6_PLANNER_CORE_REWRITE.md
this Task Spec
```

D7 implementation must not begin until D6 is explicitly COMPLETE. Documentation/design work may proceed earlier.

---

# 3. Split

```text
D7-01  mutation coordinator + typed mutation group + ChangeLog
D7-02  explicit Undo support matrix + compensation
D7-03  :shared:sync + DVV/HLC + durable SyncOperation journal + tombstone
```

Recommended integration order is D7-03 causal primitives first, then D7-01 atomic orchestration, then D7-02 Undo, while keeping each commit buildable.

---

# 4. Module contract

Create:

```text
:shared:sync
```

Allowed direction:

```text
shared:sync        -> shared:domain
shared:application -> shared:sync + shared:domain + shared:planner
shared:database    -> shared:application + shared:sync + shared:domain
apps               -> application composition; no direct journal table access
```

`:shared:sync` contains causal/semantic operation types, not networking/server code.

No Domain entity receives Room/sync metadata.

---

# 5. Atomic mutation coordinator

All synchronizable application writes must pass one coordinator/transaction seam equivalent to:

```text
allocate MutationId
build typed ordered entity mutation group
allocate local DVV dot + HLC
begin application DB transaction
    apply Active State writes
    append MutationRecord + ChangeLog entries
    append SyncOperation journal
    update replica causal state
    persist FocusBlock tombstone when applicable
commit
```

Any failure rolls all of these back.

Do not retrofit history by observing repository Flows after commit.

No generic repository interceptor may guess before/after values outside the application command transaction.

---

# 6. Integration with existing commands

At minimum wrap existing successful writes from:

```text
D5-02 Event create/update
D5-02 Task create/update
D6 Planner Apply FocusBlock create/move/resize/delete
PlanningProfile update command when the prototype settings surface introduces it
```

Other D4 source-fact repository writes use the frozen typed mutation vocabulary when/if exposed through an application mutation command.

Tests and migrations may still seed repositories directly; seeding is not user ChangeLog history unless the test explicitly exercises MutationCoordinator.

---

# 7. Typed operation requirements

Use the exact v1 vocabulary in HST-002.

Each entry carries normalized semantic DTO values sufficient for:

```text
history diff
safe Undo precondition checks
deterministic replay
D8 semantic merge
```

Do not serialize Room records.

WorkLog is append-only. CourseSession is never journaled as source fact.

---

# 8. ChangeLog/read surface

Expose application capabilities equivalent to:

```text
history.timeline
history.getMutation
history.getEntityChanges
history.getDiff
history.canUndo
history.undo
```

Timeline ordering for UI is stable by HLC then MutationId; that ordering is not a merge policy.

History UI may be minimal in D7. The application/query contract and structured results are required.

---

# 9. Undo

Implement exactly the HST-005 support matrix.

Critical invariants:

```text
Undo is a new MutationId
original history remains
current-state equality/precondition is checked in the same write transaction
grouped Planner Undo is all-or-none
unsupported mutation kind is explicit, not silently ignored
```

A diverged entity produces `UndoConflict`; do not overwrite newer user/remote facts.

---

# 10. DVV / HLC

Implement HST-006/HST-007 literally.

Pure unit tests must cover:

```text
DVV equality/dominance/concurrency
counter persistence
rollback does not publish dot
HLC local same-millisecond increments
HLC wall-clock rollback
HLC receive merge branches
stable display ordering
```

Do not use HLC to choose semantic winners.

---

# 11. FocusBlock tombstone

Only FocusBlock delete joins D7 v1 deletion semantics.

Required behavior:

```text
active row removed
tombstone retained
before image in ChangeLog
causally older Put suppressed during replay
Undo may restore same ID only under HST-005 preconditions
no physical compaction
```

Do not add Event/Task delete to make the protocol symmetric.

---

# 12. Persistence

Bump current Room schema `N -> N+1` only when D7 implementation starts.

Required durable concerns are frozen by HST-011. Export schema and add migration tests from the actual then-current version.

The D7 task must not assume D6's numeric schema version remains the immediate predecessor if an intervening approved schema milestone lands first.

---

# 13. D8 compatibility seam

D7 does not open network connections.

The durable `SyncOperation` must nevertheless be transport-neutral and contain the semantic facts D8 needs:

```text
MutationId
DVV
HLC
origin
ordered typed entity mutations
```

No server cursor, delivery retry count, ciphertext, key ID, or account credential belongs inside semantic SyncOperation.

---

# 14. Required test matrix

```text
Event create/update atomic journal
Task create/update atomic journal
Planner multi-FocusBlock Apply = one MutationId
forced active-state failure rolls history/journal/causal state back
forced journal failure rolls Active State back
ChangeLog ordinal ordering
history diff round-trip
supported Event/Task/Profile Undo
FocusBlock create/move/resize/delete Undo
Planner grouped Undo atomic success/failure
unsupported create/academic/WorkLog Undo result
DVV sequential + concurrent + dominance
HLC rollback + receive merge
replay duplicate idempotency
FocusBlock tombstone suppresses causally older Put
local journal codec compatibility fixture
migration N -> N+1 preserves D6 facts
```

---

# 15. Explicit exclusions

D7 MUST NOT add:

```text
network transport
Ktor server
E2EE key management
server authentication
semantic merge engine
physical tombstone compaction
AgentThread/AgentAction
provider credentials
external calendar writes
generic SQL mutation commands
object-level LWW
```

---

# 16. Completion gate

D7 PASS requires every HST-013 item plus:

```text
[ ] D6 is COMPLETE before integration begins
[ ] :shared:sync dependency direction verified
[ ] all existing user/planner write commands either use MutationCoordinator or are explicitly non-synchronizable
[ ] ChangeLog/Undo public result vocabulary is structured
[ ] Room migration/schema export passes
[ ] repository-wide CI green
```

When complete, update this status to `COMPLETE` and advance D8 implementation gate.