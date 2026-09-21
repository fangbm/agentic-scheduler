# Agentic Scheduler — D8 Conflict Projection / Write Guard Amendment

> Status: **FROZEN — D8-01c CONTRACT AMENDMENT**  
> Applies to: `docs/SYNC_SECURITY_DECISIONS.md` SYN-013 / SYN-015, `docs/D8_RECEIVE_CONFLICT_AMENDMENT.md`, and `docs/tasks/D8_E2EE_SYNC_TRANSPORT.md` conflict/read/write gates  
> Date: 2026-09-15  
> Decision: adopt **A — conflict-aware semantic-group overlay**; reject **B — materialize provisional candidate into durable Active State**.

This amendment is normative for D8. Where this document is more specific than earlier D8 wording, this document controls. It does not change D7 MutationId semantics, D8 wire version 1, or the no-HLC-LWW rule.

---

## D8-P01 — Provisional conflict state is a derived read projection

An OPEN `SyncConflict` MUST NOT modify durable Active State merely to make every device display the same provisional branch.

The deterministic provisional branch is represented as a derived, read-only projection:

```text
Durable Active State
    + OPEN SyncConflict
    + canonical provisional candidate values
    -> Conflict-aware Projection
```

The projection is:

```text
read-only
derived
non-journaled
non-synchronizing
not a MutationId
not causal acceptance
not conflict resolution
```

Rendering a provisional value MUST NOT append ChangeLog, append a SyncOperation, allocate a DVV dot, update tombstones, or mutate Active State.

The lexicographically smallest competing `MutationId` remains the deterministic provisional tie-break already frozen by SYN-013. HLC MUST NOT affect provisional selection.

---

## D8-P02 — Overlay only conflicted semantic groups

The projection MUST NOT replace an entire entity image with the provisional participant's historical `after` image.

For each OPEN conflict entity reference, only the semantic groups named by `SyncConflict.entityRefs.groups` are overlaid onto the current durable Active State.

Equivalent rule:

```text
ProjectedEntity =
    current durable Active State
    + provisional candidate values restricted to OPEN conflicted groups
```

All current non-conflicting groups remain unchanged.

Example:

```text
current durable Event:
    title = "Local title"
    time  = RemoteTime      // already accepted by a compatible disjoint merge

OPEN conflict:
    title = "Local title" vs "Other title"
    provisional candidate title = "Other title"

projected Event:
    title = "Other title"
    time  = RemoteTime      // MUST NOT roll back
```

Projection therefore consumes the same frozen typed semantic-group model used by D8 merge. It MUST NOT implement a second ad-hoc JSON field merge or timestamp merge.

If a provisional candidate value for a required conflicted group cannot be reconstructed unambiguously from durable conflict participants/candidate state, the implementation MUST fail closed: expose the conflict as unresolved/unprojectable and block writes to the affected group rather than invent a value.

---

## D8-P03 — One application-level projection boundary

Conflict-aware projection belongs in `:shared:application`, not only in a Desktop/Android UI adapter.

Surfaces that promise deterministic user-visible conflict state or make automated planning decisions MUST consume the same projection boundary, including:

```text
Desktop read surfaces
Android read surfaces
Wear read surfaces
Planner planning snapshots / previews
future Agent context reads that include synchronized source facts
```

Raw repositories remain the durable Active State storage boundary and may still be used by history/replay/resolution internals. A UI or automated consumer MUST NOT bypass the projection boundary when reading an entity/group that can be affected by an OPEN conflict.

A suitable application capability may be named differently, but is semantically equivalent to:

```text
ConflictAwareProjection
projectEvent(...)
projectTask(...)
projectPlanningProfile(...)
projectFocusBlock(...)
projectExam(...)
...
```

The exact API shape is an implementation detail; the single semantic read boundary is not.

---

## D8-P04 — Conflict write guard

OPEN conflicts create a write guard at the semantic-group boundary.

For a proposed normal application mutation:

```text
changedGroups(proposedMutation)
    ∩ openConflict.groups
```

If the intersection is non-empty, the normal write MUST NOT commit.

The structured application result must be equivalent to:

```text
BlockedBySyncConflict(
    conflictId,
    entityKind,
    entityId,
    blockedGroups,
)
```

This guard applies at minimum to automatic Planner and Agent writes. Ordinary non-resolution user edits that intersect an OPEN conflicted group must also be routed to explicit conflict resolution rather than silently acting as an implicit resolution.

Non-conflicting groups may continue to be written normally when the caller can declare and validate its changed semantic groups precisely.

A caller that cannot safely determine its changed groups MAY conservatively block the entire conflicted entity. This is a permitted conservative implementation, not permission to allow an intersecting write.

---

## D8-P05 — Explicit resolution is the only conflicted-group write exception

`SyncConflictResolutionService` is the explicit exception to D8-P04.

Resolution:

```text
selects or constructs explicit valid candidate values
-> creates a normal typed local mutation
-> DVV observes every conflict participant
-> updates durable Active State
-> marks SyncConflict RESOLVED in the same atomic application transaction
```

Resolution MUST NOT be implemented by mutating the provisional projection or by copying a provisional candidate into Active State without a new MutationId.

After successful resolution, the OPEN projection disappears because the conflict is RESOLVED; subsequent reads come from the newly committed durable Active State.

---

## D8-P06 — Why materialization is rejected

D8 v1 explicitly rejects materializing the provisional candidate into Active State solely for display convergence.

That design would make a display-only tie-break look like an accepted source fact without a corresponding resolution MutationId, and would blur the distinction between:

```text
durable accepted fact
provisional display choice
explicit user resolution
```

It would also risk overwriting already accepted compatible semantic groups with stale values carried inside a participant's historical full image.

Therefore:

```text
A — conflict-aware semantic-group overlay: ACCEPTED
B — provisional candidate materialized into Active State: REJECTED
```

---

## D8-P07 — Required D8-01c tests

D8-01c must include tests equivalent to:

```text
same OPEN conflict on two replicas with different current local branches
    -> both project the same provisional conflicted group value

projection overlays only the conflicted group
    -> accepted non-conflicting groups remain unchanged

projection does not mutate Active State
projection does not append ChangeLog / SyncOperation / DVV state
HLC changes do not change provisional projection selection

Planner write intersects OPEN conflict group
    -> BlockedBySyncConflict; no Active State/journal change

Agent write intersects OPEN conflict group
    -> BlockedBySyncConflict; no Active State/journal change

normal user edit intersects OPEN conflict group
    -> routed/rejected in favor of explicit resolution

write touches only disjoint semantic group
    -> allowed when the caller provides precise changed-group semantics

explicit SyncConflictResolutionService resolution
    -> allowed
    -> new MutationId observes every participant
    -> Active State commits chosen value
    -> conflict becomes RESOLVED
    -> provisional overlay disappears
```

For grouped Planner/Agent operations, if any child mutation intersects an OPEN conflict group, the entire proposed MutationId group MUST be rejected before partial Active State application.

---

## D8-P08 — Completion gate

D8-01c is not complete until:

```text
[ ] provisional conflict display uses conflict-aware projection, not Active State materialization
[ ] projection overlays only conflicted semantic groups
[ ] accepted disjoint groups survive provisional projection unchanged
[ ] projection is read-only/non-journaled/non-causal
[ ] UI and automated Planner/Agent reads use the shared application projection boundary
[ ] intersecting automatic Planner/Agent writes are structurally blocked
[ ] ordinary user writes cannot implicitly resolve an OPEN conflicted group
[ ] explicit resolution remains the only conflicted-group write exception
[ ] whole grouped automatic write rejects atomically when one child is blocked
[ ] repository CI is green
```
