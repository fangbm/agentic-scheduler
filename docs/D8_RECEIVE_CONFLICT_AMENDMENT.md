# Agentic Scheduler — D8 Receive / Conflict Amendment

> Status: **FROZEN — D8-01b/01c CONTRACT AMENDMENT**  
> Applies to: `docs/SYNC_SECURITY_DECISIONS.md` SYN-013 / SYN-014 / SYN-015 and `docs/tasks/D8_E2EE_SYNC_TRANSPORT.md` receive/conflict gates  
> Date: 2026-09-15  
> Reason: make integrity-conflict handling and common causal ancestry explicit before D8-01c semantic merge lands.

This amendment is normative for D8. Where this document is more specific than the earlier D8-00 wording, this document controls. It does not change D7 semantics, wire version 1, or the no-HLC-LWW rule.

---

## D8-A01 — Expected remote semantic conditions must be structured outcomes

The receive path must not use unchecked exceptions as the normal representation of an expected remote semantic or integrity condition.

In particular, a validly decoded remote operation that violates a frozen merge/integrity rule must become one of:

```text
Applied / coalesced
Ignored causally known
Durable SyncConflict
Durable protocol quarantine, only when the payload/protocol itself is invalid or unsupported
```

Expected semantic conflicts must not escape `SyncEngine.receive` as `IllegalStateException`, `IllegalArgumentException`, or an implementation-specific repository error.

A transaction failure caused by an unexpected infrastructure/programming fault still rolls the transaction back and may propagate; this rule is only about protocol/semantic conditions that D8 explicitly knows how to classify.

---

## D8-A02 — WorkLog append-only integrity matrix

SYN-014 WorkLog semantics are frozen as follows.

For an incoming `WorkLogAppend(after = R)` with `WorkLogId = X`:

```text
no local WorkLog X
    -> apply R

local WorkLog X exists and normalized(local) == normalized(R)
    -> coalesce / idempotent success

local WorkLog X exists and normalized(local) != normalized(R)
    -> durable structured INTEGRITY SyncConflict
```

The third case is **not**:

```text
an INVALID_PAYLOAD quarantine
an exception-only failure
last-writer-wins
an overwrite
```

The existing local WorkLog is not overwritten while the integrity conflict is OPEN.

The conflict is handled atomically with receive metadata. Once the integrity conflict itself is durably stored, the server cursor may advance past that envelope. The cursor must not advance if conflict persistence rolls back or otherwise fails.

Repeated delivery of the same conflicting operation must converge to the same open conflict state rather than create unbounded duplicate conflicts.

---

## D8-A03 — SyncConflict classification

`SyncConflict` gains an explicit conflict classification:

```text
SyncConflictKind
- SEMANTIC
- INTEGRITY
```

`SEMANTIC` covers ordinary SYN-013/SYN-014 concurrent same-group differing values.

`INTEGRITY` covers a validly decoded operation whose identity/invariant contract forbids treating the differing value as an ordinary merge candidate. D8 v1 requires at minimum:

```text
WorkLog same WorkLogId + different normalized value -> INTEGRITY
```

This classification is trusted-local state only. It is not added to the server-visible envelope.

Conflict resolution remains explicit and emits a normal typed local mutation whose DVV observes the competing operations. `INTEGRITY` does not authorize silent overwrite or timestamp arbitration.

---

## D8-A04 — Common causal ancestry representation

SYN-015's “common causal ancestry when available” is frozen as a **context-only version vector**, not as a dotted operation and not as an HLC timestamp.

Equivalent model:

```text
CausalContextSnapshot
- components: List<VersionComponent>   // canonical ReplicaId lexicographic order

SyncConflict
- ...
- commonCausalContext: CausalContextSnapshot?
```

For conflict participants whose complete version vectors are known, the common causal context is the greatest version vector dominated by every participant vector:

```text
for each ReplicaId r:
    common[r] = min(participantCompleteVector[i][r])
```

Absent replica components are treated as “not observed” and are omitted from the resulting canonical context.

The participant complete vector includes its DVV context plus its own dot.

`commonCausalContext` is informational/audit/resolution context. It does not choose a winner and must never be derived from HLC ordering.

The field may be `null` only when the implementation cannot reconstruct all participant causal vectors from durable local state. When all participant DVVs are available, implementations must persist the computed context rather than arbitrarily omit it.

---

## D8-A05 — Whole-MutationId atomicity for conflict creation

Conflict classification happens at the MutationId group boundary.

If one child mutation causes an integrity or semantic conflict with a concurrent/local candidate, the incoming MutationId is not partially applied. The whole operation becomes a conflict participant according to SYN-013.

For a durable conflict:

```text
Active State changes for the incoming group: none, except an already-defined deterministic provisional display mechanism that does not resolve the conflict
SyncConflict: persisted
receive/conflict metadata: persisted
server cursor: advanced only in the same successful durable handling transaction
```

No convenient non-conflicting child of the same MutationId may be committed independently.

---

## D8-A06 — Conflict identity / duplicate delivery

A deterministic conflict identity must be used so all repeated deliveries of the same participant set update/coalesce the same durable conflict rather than append duplicates.

The exact storage key encoding is an implementation detail, but it must be a pure function of the SyncSpace plus the canonically sorted participant MutationIds and conflict classification/semantic target needed to distinguish independent conflicts.

`provisionalMutationId` remains the lexicographically smallest participant MutationId as already frozen by SYN-013. It is display-only and never marks the conflict resolved.

---

## D8-A07 — D8-01c required tests added by this amendment

D8-01c must add tests equivalent to:

```text
WorkLog same ID + same normalized image -> idempotent/coalesced, no conflict
WorkLog same ID + different normalized image -> durable INTEGRITY SyncConflict
WorkLog integrity conflict does not overwrite Active State
WorkLog integrity conflict advances cursor only after conflict persistence commits
re-delivery of same integrity conflict does not create another conflict
semantic conflict has kind SEMANTIC
integrity conflict has kind INTEGRITY
common causal context is component-wise minimum of participant complete vectors
common causal context is canonical ReplicaId order
HLC changes do not affect common causal context or conflict winner semantics
one conflicting child prevents partial application of the entire MutationId group
```

---

## D8-A08 — Gate

D8-01b/01c is not complete until:

```text
[ ] expected WorkLog identity divergence cannot escape receive as IllegalStateException
[ ] WorkLog same-ID/different-value persists an INTEGRITY SyncConflict
[ ] SyncConflict exposes SEMANTIC vs INTEGRITY classification
[ ] SyncConflict persists commonCausalContext when participant DVVs are available
[ ] durable conflict handling and cursor advance are atomic
[ ] repeated delivery converges on one durable conflict
[ ] whole-MutationId conflict atomicity is tested
[ ] repository CI is green
```

---

## D8-A09 — Out-of-order causal-gap receive

An operation's DVV context proves what its sender observed. It does **not** prove
that this replica has durably handled each referenced operation. Receive must
therefore maintain a durable handled-dot frontier separate from
`LocalReplicaCausalState.observedContext`.

For a valid decoded operation whose DVV context requires a dot outside that
frontier:

```text
do not apply any child Active State change
→ persist the exact receipt as pending receive state
→ do not advance the server cursor past that receipt
→ accept/durably handle missing ancestors
→ drain eligible pending receipts in stable server-cursor / MutationId order
```

A remote dot joins the handled frontier only after its operation has a durable
receive outcome: applied/coalesced, a durable structured conflict, or an
explicit causal acknowledgement. Protocol quarantine does not make a causal
ancestor handled.

The pending receipt must retain one immutable payload for its MutationId. A
conflicting payload under the same MutationId is invalid protocol input; it may
not silently replace a pending operation.

D8-01 requires a regression equivalent to:

```text
B:1 creates Event E
B:2 creates Task T with B:1 in its DVV context
A receives B:2 first
    -> T is not active; B:2 is durable pending; cursor does not advance
A receives B:1
    -> E applies; B:2 drains; T applies; cursor advances through B:2
```
