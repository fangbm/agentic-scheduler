# Agentic Scheduler — D6 Planner Core Rewrite Decisions

> Status: **D6-00A FROZEN — rewrite clarification**  
> Date: 2026-09-12  
> Applies to: **D6 deterministic Planner core rewrite only**  
> Base contract: `docs/PLANNER_DECISIONS.md`

This document is an additive clarification for the D6 Planner rewrite. It does not reopen the public D6 product contract, module boundary, persistence schema, PlanBranch API, or UUIDv7 decision. It freezes implementation-significant semantics that were underspecified in PLN-014 / PLN-015 and that caused the first Planner implementation to require repeated local patches.

If wording in this document is more specific than the older PLN-014 / PLN-015 wording, this document governs the D6 rewrite.

---

# D6R-001 — Active State FocusBlocks are reservations, not disposable search input

Every FocusBlock present in the input `PlanningSnapshot` remains a real calendar fact until the Planner emits and commits an authorized replacement mutation for that exact block.

A search implementation may temporarily exclude a movable block from one free-interval calculation, but it MUST NOT make the block disappear from the semantic planning state.

At every accepted intermediate state, each existing FocusBlock is represented by exactly one of:

```text
its original Active State reservation
or
an accepted proposed replacement reservation for the same FocusBlock
or
an authorized Delete mutation for a SOFT block
```

The Planner must never produce a proposal that overlaps an existing block merely because that block was temporarily removed from a search occupancy set.

---

# D6R-002 — Reservation authority

For rewrite internals, Full Replan must preserve the authority frozen by PLN-006 / PLN-007.

Conceptually classify reservations as:

```text
FIXED
- already-started FocusBlock
- PINNED FocusBlock
- HARD FocusBlock
- FocusBlock entirely outside the requested horizon
- FocusBlock owned by a Task that is not currently eligible for automatic planning,
  unless an explicitly authorized cleanup rule below applies

FLEXIBLE
- future, inside-horizon, UNPINNED + FLEXIBLE block for an eligible Task
- identity and duration immutable; time may move

SOFT
- future, inside-horizon, UNPINNED + SOFT block for an eligible Task
- may move / resize / delete

NEW
- Planner-created SOFT + UNPINNED proposal
```

Cleanup remains narrow:

```text
COMPLETED / CANCELLED / known remaining == 0
-> future SOFT + UNPINNED may be deleted
-> FLEXIBLE / HARD / PINNED remain real occupancy

remaining == null
-> no automatic resize/delete/move based on effort truth
-> existing FocusBlocks remain occupancy
```

A dependency-blocked Task is not silently treated as absent. Until that Task receives an accepted replanning delta, its existing blocks remain represented by their original reservations.

---

# D6R-003 — Task-local proposal transaction

Full Replan must not mutate global planner truth incrementally and then attempt ad-hoc rollback by TaskId.

Planning one Task is conceptually transactional:

```text
PlanningState before Task
    ↓
Task-local tentative view
    ↓
finite candidate search / displacement / relocation
    ↓
TaskPlanDelta
    ↓
accept -> atomically produce next PlanningState
reject -> discard delta, PlanningState is byte-for-byte semantically unchanged
```

A rejected tentative plan must restore all planner-visible facts, including:

```text
reservations / occupancy
free intervals or their derivable source state
mutations
task coverage
planned completion
dependency readiness
proposal-local explanations
```

Do not implement rollback by only removing mutations or only zeroing Task coverage.

The implementation may derive free intervals on demand from the accepted reservation set instead of storing mutable free-interval state.

---

# D6R-004 — Displacement and relocation semantics

Task service order remains PLN-008 / PLN-009. Existing mutable reservations do not gain an unconditional veto over a higher-ranked Task, but Planner authority must still be respected.

When a selected Task candidate conflicts with a lower-ranked mutable reservation:

```text
SOFT reservation
-> may be tentatively moved/resized/removed under normal SOFT authority

FLEXIBLE reservation
-> may be tentatively displaced only if a legal same-ID, same-duration relocation
   can be proven in the same tentative planning transaction
```

If a FLEXIBLE relocation cannot be completed:

```text
conflicting best-effort NORMAL / no-deadline proposal
-> reject/retract that conflicting tentative proposal as needed
-> preserve the FLEXIBLE block
-> leave affected best-effort work unscheduled with structured explanation

conflicting HARD obligation
-> try the next legal candidate / displacement arrangement
-> only after the finite legal search is exhausted may the run become Infeasible
```

The Planner must never resolve this case by retaining both overlapping blocks, deleting/resizing FLEXIBLE, or leaking a partially rolled-back proposal.

---

# D6R-005 — One authoritative coverage / completion model

Planner coverage and dependency completion must be computed from one accepted reservation state, not from independent ad-hoc maps with different semantics.

General future planned coverage:

```text
retained future existing reservations
+ accepted proposed future reservations
```

Already-started blocks remain fixed occupancy but do not reduce authoritative `Task.effort.remaining`; PLN-014 remains in force.

For an OPEN / IN_PROGRESS prerequisite with known positive remaining effort, planned completion is the earliest Instant at which cumulative accepted future coverage reaches authoritative remaining effort.

The following never produce planned completion merely from arithmetic:

```text
CANCELLED
remaining == null
remaining == 0 but status != COMPLETED
partially planned prerequisite
```

If a prerequisite's planned completion is at or after the planning horizon end, dependent work has no legal in-horizon start. Return an unscheduled/dependency result; never construct an invalid interval.

---

# D6R-006 — HARD deadline satisfaction uses before-cutoff coverage

Fixed or user-protected blocks after a HARD deadline remain real occupancy and are never moved merely to satisfy the deadline.

However, a future block or portion of coverage that completes after the effective HARD cutoff does **not** satisfy the requirement that the Task be fully covered by that cutoff.

Therefore:

```text
HARD deadline satisfaction
= cumulative accepted future planned coverage completed by cutoff
```

A PINNED/HARD/FLEXIBLE block after the cutoff may remain stored and visible, but it cannot hide a HARD deadline shortfall.

If full authoritative remaining effort cannot be covered by the cutoff, Full Replan returns `Infeasible` with `HardDeadlineShortfall` and no applicable PlanBranch.

NORMAL deadline overflow semantics remain unchanged.

---

# D6R-007 — Finite structural candidate set; no epsilon/grid

PLN-015 remains lexicographic. The rewrite must make the candidate domain finite and explicit.

For one legal free interval, candidate starts are drawn only from structural critical points that fall inside the valid start domain, including as applicable:

```text
free interval start
dependency planned-completion boundary
effective deadline / overflow boundary
clamped original start for an existing movable block
other explicit reservation boundaries that are already present in the accepted planning state
```

The exact implementation may deduplicate equivalent Instants, but it MUST NOT introduce:

```text
minute grids
5/15-minute grids
random sampling
"+ epsilon" timestamps solely to avoid an adjacency context-switch penalty
```

A positive gap removes the context-switch penalty only when that positive gap arises from an actual structural candidate boundary. The Planner does not manufacture an infinitesimal gap.

For every structural start, enumerate all legal duration candidates from PLN-013. Candidate generation is therefore conceptually:

```text
finite structural starts
×
finite PLN-013 legal durations
→ reject illegal candidates
→ PLN-015 lexicographic ordering
```

For existing blocks, exact original placement is considered first when legal. If resizing/moving is required, the clamped original start must be present so that the movement-distance criterion is meaningful.

---

# D6R-008 — Candidate ranking remains PLN-015

After hard legality filtering, candidate comparison remains:

```text
1. before-deadline vs authorized overflow
2. smaller authorized lateness
3. preserve existing placement
4. smaller absolute movement distance for an existing block
5. smaller added context-switch count
6. better PLN-013 chunk-duration rank
7. earlier start
8. earlier end
9. canonical identity
```

Do not pre-select one duration before higher-priority criteria such as movement or context switching are compared.

Explanation output must be derived from the actual comparison/decision trace. A static per-mutation list of possible criteria is not sufficient evidence of why a candidate won.

---

# D6R-009 — Local Reflow stays a separate bounded repair algorithm

Local Reflow reuses only shared legality/time primitives:

```text
availability resolution
deadline legality
dependency completion / legality
interval arithmetic
canonical ordering
```

It does not reuse Full Replan displacement/eviction behavior.

Unrelated blocks remain fixed occupancy. Affected blocks are move-only, preserve identity/duration, and are committed provisionally in deterministic original-start / FocusBlockId order. Any failure returns `Infeasible` for the whole Local Reflow result.

---

# D6R-010 — Rewrite scope fence

The D6 rewrite is authorized to replace Planner-core internals, tests, and pure helper types only.

Retain unless a failing conformance test proves a contract bug outside Planner core:

```text
PlanningProfile Domain model
Room v2 schema and v1 -> v2 migration
application UUIDv7 generator
TaskRepository.deleteFocusBlock
PlanningSnapshot public contract
Planner result / mutation public vocabulary
PlanBranch application transaction boundary
stale/apply/rebase outer workflow
```

The rewrite does not authorize D7 history/journal, D8 sync/E2EE, Agent runtime, new persistence schema, Event movement, or a new optimizer dependency.

---

# D6R-011 — Rewrite verification rule

Every previously discovered Planner correctness failure must become a regression test before the rewrite is accepted.

At minimum add explicit tests for:

```text
unknown-effort Task existing FLEXIBLE/SOFT occupancy is preserved
COMPLETED/CANCELLED/remaining-zero FLEXIBLE remains occupancy
dependency-blocked Task existing movable blocks remain occupancy
outside-horizon prerequisite completion never creates invalid ranges
best-effort proposal cannot erase or overlap an unrelocatable FLEXIBLE block
displacement rollback restores every original reservation and recomputes derived availability
SOFT resize considers same-start / clamped-original candidates
smaller movement outranks context-switch/chunk/start where PLN-015 says it should
all PLN-013 durations participate in the global candidate comparison
HARD fixed coverage after cutoff does not satisfy the deadline
input permutation yields identical semantic output
Local Reflow all-or-nothing and provisional dependency legality
```

No D6 PASS may be declared from repository-wide CI alone while this rewrite matrix is incomplete.
