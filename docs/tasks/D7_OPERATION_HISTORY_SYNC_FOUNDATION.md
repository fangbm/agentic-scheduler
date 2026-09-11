# Agentic Scheduler — D7 Operation Journal, History, Undo & Causality Foundation

> Task ID: **D7-00/01/02/03 draft**  
> Milestone: **D7 — Audit / Mutation / Sync Foundation**  
> Status: **DRAFT — not implementation-ready**  
> Date: 2026-09-11

---

# 1. Goal

Give every successful local logical mutation a durable transaction identity and auditable history before network synchronization exists.

Intended D7 output:

```text
MutationId
+ typed mutation group
+ ChangeLog
+ explicit limited Undo
+ DVV
+ HLC
+ internal SyncOperation
+ durable operation journal
```

Production wire serialization and network transport remain D8.

---

# 2. Recommended split

```text
D7-00  mutation/delete/Undo decisions
D7-01  typed mutation group + ChangeLog
D7-02  explicit Undo support matrix
D7-03  DVV/HLC + internal operation journal
```

---

# 3. Proposed ownership

Recommended future module boundary:

```text
:shared:application
    mutation orchestration
    history read contracts
    Undo use cases

:shared:sync
    DVV/HLC
    SyncOperation semantic model
    causality helpers

:shared:database
    persistence records/migrations
```

Creating `:shared:sync` is architecture-affecting and requires D7-00 approval before implementation.

Sync metadata must not be added to Domain entities.

---

# 4. Atomic local mutation path

After D7, a successful synchronizable local command should follow:

```text
validate
→ one application transaction
    ├─ active-state writes
    ├─ ChangeLog append
    └─ internal SyncOperation append
→ commit
```

All or none.

D9 may later add AgentAction for Agent-originated operations without weakening this atomicity rule.

---

# 5. OD-033 — operation granularity

OD-033 must be resolved before D7 implementation.

Recommended semantic shape:

```text
one logical application transaction = one MutationId
one MutationId contains one ordered group of typed entity mutations
replica application of the group is atomic
```

Do not use generic SQL patches or opaque arbitrary JSON as the authoritative mutation meaning.

---

# 6. Typed mutation vocabulary

The exact mutation set must be frozen from the entity operations that actually exist at D7 start.

Conceptual examples:

```text
EventUpsert
TaskUpsert
FocusBlockUpsert
AcademicSourceUpsert
```

Do not invent delete/update forms merely for protocol symmetry.

A mutation carries typed semantic values or explicit transport-neutral DTOs rather than Room records.

---

# 7. ChangeLog

ChangeLog records successful state changes, not conversational claims.

Minimum intended facts:

```text
ChangeLogEntryId
MutationId
origin
logical timestamp / HLC
affected typed entity identity
typed operation
reversible structured facts where supported
```

History is append-oriented.

Current Domain State remains authoritative for current truth; ChangeLog is authoritative history of committed change.

---

# 8. Undo support matrix

Undo is not automatically available for every mutation.

D7-00 must publish an explicit matrix:

```text
mutation kind
inverse/compensation rule
preconditions
conflict result when current state diverged
```

For v1 it is acceptable to support only update/move mutations with safe explicit inverses.

Undo creates a new compensating mutation/ChangeLog entry.

Undo never deletes prior history.

---

# 9. Deletion/tombstone gate

D4 repositories intentionally did not define public delete semantics.

D7 MUST NOT add generic synchronized deletion/tombstone behavior merely because Sync will eventually need it.

Before the first delete mutation, explicitly freeze:

```text
which entity kinds are deletable
related-entity effects
reference behavior
history behavior
Undo behavior
tombstone payload/identity
```

OD-032 physical tombstone compaction remains a later decision.

---

# 10. WorkLog correction rule

WorkLog is append-oriented historical fact.

D7 must not expose ordinary "rewrite any WorkLog" semantics without a dedicated correction/tombstone contract.

A future WorkLog correction must remain auditable.

---

# 11. DVV / HLC

D7 intends to implement the already-frozen causality directions:

```text
DVV => causal ancestry / concurrency
HLC => monotonic stable ordering metadata
```

HLC is never semantic Last-Write-Wins policy.

Wall-clock rollback must not break HLC monotonicity.

---

# 12. Internal SyncOperation

D7 may define and persist a typed semantic operation object suitable for later transport.

D7 MUST NOT freeze production wire bytes merely to store the local operation journal.

Therefore OD-030 may remain `PENDING` through D7 if no production serialization is introduced.

---

# 13. Persistence/schema rule

D7 bumps the then-current Room schema:

```text
N → N+1
```

Expected durable concerns:

```text
ChangeLog
operation journal
Device/HLC causal state
```

Do not predeclare a numeric source version until the D7 implementation task is approved.

Do not add tombstone tables if deletion remains out of scope.

---

# 14. History read APIs

D7 should expose application read capabilities equivalent to:

```text
history.timeline
history.getMutation
history.getEntityChanges
history.getDiff
```

Exact internal names may vary, but history APIs are read-only with respect to Active State.

---

# 15. Required tests once READY

At minimum:

```text
active state + ChangeLog + operation journal atomic commit
forced failure rolls all three back
Undo appends compensating history
diverged Undo returns explicit conflict
DVV sequential case
DVV concurrent case
HLC monotonic under wall-clock rollback
operation replay is deterministic
migration N → N+1
no production wire serialization dependency
no object-level LWW fallback
```

---

# 16. READY gate

D7 remains DRAFT until:

```text
[ ] OD-033 operation granularity resolved
[ ] typed mutation vocabulary frozen
[ ] Undo support matrix frozen
[ ] delete/tombstone scope explicitly included or excluded
[ ] WorkLog correction behavior explicitly excluded or frozen
[ ] :shared:sync module boundary approved if used
[ ] persistence tables/API shapes frozen
```

OD-030 need not block D7 while D7 remains transport-format agnostic.
