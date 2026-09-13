# Agentic Scheduler — D6 Planner Core Rewrite

> Task ID: **D6-R1**  
> Milestone: **D6 — Planner**  
> Status: **IMPLEMENTED — CI green (run #126); review round complete; D6 status flip pending merge**  
> Date: 2026-09-12  
> Base task: `docs/tasks/D6_DETERMINISTIC_PLANNER.md`  
> Rewrite decisions: `docs/PLANNER_REWRITE_DECISIONS.md`

---

# 1. Why this rewrite exists

The first D6 Planner implementation proved the outer D6 architecture, persistence, PlanBranch, and deterministic interfaces, but repeated correctness fixes exposed one structural weakness in the Planner core:

```text
real Active State occupancy
mutable existing blocks
tentative proposals
coverage/completion
displacement
rollback
```

were represented by several independently mutated collections rather than one explicit accepted planning state.

The result was a series of locally correct patches that could leave another derived view stale or semantically incomplete.

D6-R1 replaces the Planner core with an explicit reservation + task-local delta architecture. It is not a product-scope expansion.

---

# 2. Authoritative reading order

For this rewrite, read in this order:

```text
docs/PLANNER_DECISIONS.md
docs/PLANNER_REWRITE_DECISIONS.md
docs/tasks/D6_DETERMINISTIC_PLANNER.md
docs/tasks/D6_PLANNER_CORE_REWRITE.md
```

The original D6 task remains authoritative for public contracts and milestone scope.

`PLANNER_REWRITE_DECISIONS.md` is the authoritative clarification for Planner-core execution semantics. Where it is more specific than the original PLN-014 / PLN-015 wording, use the rewrite clarification.

---

# 3. Scope

Rewrite:

```text
Deterministic Full Replan internals
candidate generation / ordering
FocusBlock reservation handling
coverage / planned-completion calculation
displacement / relocation
rollback / tentative planning state
shared pure legality helpers
Local Reflow internals where needed to use the corrected shared legality model
Planner regression tests
```

Retain and do not casually rewrite:

```text
PlanningProfile Domain contract
Room v2 and migration 1 -> 2
UUIDv7 infrastructure
repository interfaces and Room implementations
PlanningSnapshot public shape
FocusBlockMutation public vocabulary
PlannerResult public vocabulary unless a test proves a missing structured result
PlanBranch outer lifecycle/orchestration
PlanBranch stale/apply transaction logic
TaskRepository.deleteFocusBlock
D5-02 editing code
```

No D7/D8/D9 work is authorized here.

---

# 4. Branch / integration strategy

Use a dedicated branch:

```text
rewrite/d6-planner-core
```

Do not develop this as a sequence of partial semantic patches on `main`.

Recommended transition:

```text
existing DeterministicPlanner remains untouched initially
new DeterministicPlannerV2 is implemented behind tests
all rewrite tests target V2
when the full D6 matrix is green:
    replace the canonical implementation in one coherent change
    remove V2 suffix / old implementation
    run repository-wide tests
```

If keeping two classes temporarily creates excessive duplication, an internal package-private rewrite implementation may be used instead. The important rule is that a half-rewritten Planner must not become the canonical `main` implementation.

---

# 5. Target internal architecture

The exact names are implementation-local, but the rewrite should have concepts equivalent to the following.

## 5.1 Accepted reservation

```kotlin
internal data class Reservation(
    val taskId: TaskId,
    val blockId: FocusBlockId?,
    val range: InstantRange,
    val authority: ReservationAuthority,
    val source: ReservationSource,
    val originalRange: InstantRange?,
)

enum class ReservationAuthority {
    FIXED,
    FLEXIBLE,
    SOFT,
    NEW,
}

sealed interface ReservationSource {
    data class Existing(val id: FocusBlockId) : ReservationSource
    data class Proposed(val key: ProposalKey) : ReservationSource
}
```

This type is Planner-internal. Do not move sync/history metadata into Domain.

## 5.2 Planning state

```kotlin
internal data class PlanningState(
    val acceptedReservations: ImmutableList<Reservation>,
    val acceptedMutations: ImmutableList<FocusBlockMutation>,
    val coverageByTask: ...,
    val completionByTask: ...,
    val issues: ...,
)
```

Prefer deriving free intervals from accepted reservations instead of maintaining a second mutable free-interval truth.

`coverageByTask` / `completionByTask` may be memoized, but the implementation must have one semantic source of truth and deterministic invalidation/recomputation.

## 5.3 Task-local delta

```kotlin
internal data class TaskPlanDelta(
    val taskId: TaskId,
    val removedReservations: ...,
    val addedReservations: ...,
    val mutations: ...,
    val issues: ...,
)
```

A delta is evaluated against one immutable accepted state.

Commit is atomic:

```text
accepted state + valid delta -> next accepted state
```

Reject is exact:

```text
accepted state + rejected delta -> original accepted state
```

No cleanup-by-TaskId rollback is permitted.

---

# 6. Phase R0 — Freeze regression tests first

Before writing the new engine, add failing tests that capture the known failure classes.

Required R0 regression set:

```text
R0-01 unknown remaining + existing FLEXIBLE remains occupancy
R0-02 unknown remaining + existing SOFT remains occupancy
R0-03 COMPLETED/CANCELLED/remaining-zero FLEXIBLE remains occupancy
R0-04 dependency-blocked Task existing FLEXIBLE/SOFT remains occupancy
R0-05 prerequisite completion after horizon -> no invalid range / no dependent placement
R0-06 NORMAL/no-deadline proposal cannot erase an unrelocatable FLEXIBLE block
R0-07 failed displacement restores every existing reservation and derived free space
R0-08 SOFT resize considers same-start candidate
R0-09 existing SOFT smaller movement outranks later PLN-015 criteria
R0-10 alternate legal duration with better context-switch rank can beat preferred duration
R0-11 HARD deadline ignores after-cutoff coverage for satisfaction
R0-12 outside-horizon mutable coverage counts but is never mutated
```

Keep every already-existing D6 regression test.

R0 is complete only when these tests fail for the expected semantic reason on the old engine, not because fixtures are invalid.

---

# 7. Phase R1 — Pure interval / time legality layer

Extract or rewrite the pure primitives first.

Required helpers should cover:

```text
half-open intersection
subtraction / free intervals
range containment
availability expansion in PlanningProfile timezone
DST-safe LocalDateTime resolution
AllDay occupancy policy
Floating event resolution
CourseSession / Exam occupancy
Deadline.Exact / DateOnly effective cutoff
```

Properties:

```text
no system clock
no system timezone
no repository access
no mutation of caller collections
canonical output ordering
invalid/ambiguous local time -> structured issue, never guessed offset
```

Add focused unit tests before wiring Full Replan.

---

# 8. Phase R2 — Snapshot normalization and initial reservations

Normalize the Planner input into one canonical internal state.

For every existing FocusBlock, create exactly one reservation according to D6R-001 / D6R-002.

Important cases:

```text
already started -> FIXED
PINNED -> FIXED
HARD -> FIXED
outside horizon -> FIXED and untouched, future duration may count coverage
unknown/ineligible Task block -> FIXED for this run except authorized SOFT cleanup
eligible inside-horizon FLEXIBLE -> movable FLEXIBLE reservation
eligible inside-horizon SOFT -> movable SOFT reservation
```

Do not globally remove every movable FocusBlock from semantic occupancy.

The accepted state must always be able to reconstruct the actual Active State schedule plus accepted mutations.

---

# 9. Phase R3 — Unified effort / completion / dependency truth

Implement one set of pure functions for:

```text
general future planned coverage
HARD-before-cutoff coverage
planned completion
prerequisite readiness
dependent earliest legal start
```

Rules:

```text
Task.effort.remaining is authoritative
never derive remaining from estimated - completed
started blocks do not reduce authoritative remaining
CANCELLED prerequisite blocks dependent
unknown prerequisite effort blocks dependent
remaining == 0 but not COMPLETED blocks dependent
partial prerequisite coverage does not produce completion
```

For HARD deadline feasibility, after-cutoff coverage is occupancy only and does not satisfy the deadline.

Add tests for each rule in isolation.

---

# 10. Phase R4 — Finite candidate generation

Implement D6R-007 before task displacement logic.

For each legal free interval, enumerate structural start candidates such as:

```text
interval start
dependency-completion boundary
deadline/overflow boundary
clamped original start for existing block
accepted reservation boundaries when structurally relevant
```

Then enumerate every legal PLN-013 duration.

Candidate generation must never create an invalid or zero-length range.

No epsilon and no minute grid.

Suggested internal model:

```kotlin
internal data class PlacementCandidate(
    val range: InstantRange,
    val sourceBlockId: FocusBlockId?,
    val durationRank: Int,
    val movementDistanceMillis: Long,
    val contextSwitchDelta: Int,
    val overflowLatenessMillis: Long,
    ...
)
```

Do not encode the lexicographic decision as a weighted numeric score.

---

# 11. Phase R5 — Exact PLN-015 comparator and decision trace

Apply the frozen comparison order exactly:

```text
1 deadline legality / before-overflow
2 authorized lateness
3 preserve existing placement
4 movement distance
5 context-switch delta
6 PLN-013 duration rank
7 earlier start
8 earlier end
9 canonical identity
```

The comparator may use precomputed candidate fields, but each field must have explicit stable semantics.

Add a real decision trace:

```text
winner
runner-up / compared candidate when useful
first criterion that differed
relevant normalized values
```

Public explanations may remain compact, but they must be derived from the actual decision, not from mutation type alone.

---

# 12. Phase R6 — Task-local Full Replan transaction

Process dependency-ready Tasks in PLN-008 order.

For one selected Task:

```text
1 derive tentative view from accepted PlanningState
2 consider reuse of exact existing placement
3 consider legal move/resize of its existing reservations
4 enumerate legal new placement candidates if demand remains
5 perform any required displacement/relocation inside the same Task-local transaction
6 verify no overlap, authority, deadline, dependency, and coverage invariants
7 emit TaskPlanDelta
8 commit delta atomically or discard it completely
```

NORMAL/no-deadline work is best-effort. Lack of capacity leaves exact unscheduled effort and does not globally fail the run.

HARD work becomes `Infeasible` only after the finite legal candidate/displacement search is exhausted.

---

# 13. Phase R7 — Displacement / relocation

Implement displacement as an explicit finite operation, not mutation-list cleanup.

## FLEXIBLE

```text
same ID
same duration
may change time only
```

A higher-ranked candidate may tentatively displace it only if a legal relocation is proven in the same transaction.

If relocation fails, the tentative state is rejected exactly.

## SOFT

May be:

```text
preserved
moved
resized
deleted
```

When displaced and later replanned, preserve identity where a legal retained/moved/resized solution wins PLN-015.

## HARD / PINNED / started / fixed-for-run

Never displaced.

Add explicit multi-block rollback tests where only one of several original blocks conflicts. After rollback, all original blocks must again occupy their original times.

---

# 14. Phase R8 — Local Reflow rewrite

Keep Local Reflow independent from Full Replan displacement.

Algorithm:

```text
1 canonicalize affected set
2 unrelated blocks are fixed occupancy
3 process affected blocks by original start, then FocusBlockId
4 preserve ID + duration
5 derive closest legal candidate in search window
6 provisionally commit the move for dependency checks of later affected blocks
7 if any affected block fails, return Infeasible with no mutations
```

Reuse time/deadline/dependency helpers from R1/R3.

Do not create/delete/resize or cascade to unrelated movable blocks.

---

# 15. Phase R9 — Output canonicalization

Before returning:

```text
assert accepted reservation state has no Planner-created illegal overlap
assert every mutation respects FocusBlock authority
assert every mutation refers to a known source block where required
canonicalize mutation order
canonicalize issue order
canonicalize explanation order
```

Same semantic snapshot under input permutation must yield equal semantic output.

A debug-only/internal invariant checker is encouraged in tests.

---

# 16. Phase R10 — Integrate with existing PlanBranch

After Planner-core tests pass:

```text
wire canonical DeterministicPlanner to rewritten engine
run PlanBranch preview tests
run deterministic rebase tests
run stale/apply tests
run atomic apply / forced rollback tests
```

Do not change PlanBranch persistence/sync scope.

Only change the outer PlanBranch code if a failing test proves it violates the already-frozen D6 contract.

---

# 17. Full rewrite test gate

Before replacing the old engine, pure Planner tests must cover at least:

```text
PlanningProfile configured/unconfigured
availability canonicalization
DST gap / overlap rejection
TaskStatus eligibility matrix
remaining null / zero
all dependency prerequisite states
Exact deadline
DateOnly next-day-start
HARD before-cutoff feasibility
NORMAL NEVER / ASK / ALLOW
ASK authorized and unauthorized
AllDay both policies
Floating valid + DST failure
CourseSession scheduled/cancelled
Exam Exact/DateOnly/Unscheduled
HARD fixed
PINNED fixed
FLEXIBLE move-only
SOFT preserve/move/resize/delete
unknown/ineligible block occupancy
blocked-task block occupancy
outside-horizon untouched + counted
PLN-013 duration boundary matrix
PLN-015 preserve/movement/context/duration/start ordering
finite candidate determinism
Full Replan deterministic replay
input permutation invariance
best-effort displacement rollback
HARD displacement infeasibility
Local Reflow closest position
Local Reflow dependency provisional state
Local Reflow all-or-nothing
```

Application/persistence gates from the original D6 task remain required.

---

# 18. Completion gate

D6-R1 is complete only when:

```text
[ ] D6R decisions implemented exactly
[ ] old Planner correctness regressions reproduced by tests
[ ] new reservation model has one accepted semantic state
[ ] no ad-hoc TaskId rollback remains
[ ] candidate starts are finite and structural
[ ] all legal PLN-013 durations participate in comparison
[ ] existing-block movement ranking is effective, not decorative
[ ] HARD deadline satisfaction uses before-cutoff coverage
[ ] ineligible/blocked existing blocks never disappear from occupancy
[ ] FLEXIBLE displacement is atomic and same-ID/same-duration
[ ] Full Replan best-effort behavior does not manufacture global infeasibility
[ ] Local Reflow remains bounded and all-or-nothing
[ ] deterministic replay + permutation tests pass
[ ] PlanBranch preview/rebase/apply tests pass
[ ] repository-wide CI is green
```

Only after this gate may the original D6 task be changed from `FIX-AND-RECHECK` to `COMPLETE`.

---

# 19. MUST NOT

The rewrite MUST NOT:

```text
add Timefold or another optimizer
introduce stochastic search
add hidden weights
use minute grids or epsilon scheduling
change Task.effort.remaining semantics
weaken HARD/PINNED/FLEXIBLE authority
move Event entities
persist PlanBranch
add D7 ChangeLog/SyncOperation
add D8 network/E2EE
add Agent/LLM logic
add new Room schema
rewrite stable D6 outer layers without a failing conformance test
merge a partially rewritten Planner core to main
```

---

# 20. Recommended commit sequence

A clean branch history can be:

```text
1. test: freeze D6 planner rewrite regressions
2. refactor: add planner reservation state primitives
3. refactor: unify planner coverage and dependency truth
4. feat: implement finite placement candidate enumeration
5. feat: implement transactional full replan
6. feat: implement atomic displacement and relocation
7. refactor: align local reflow with shared legality
8. test: complete D6 planner conformance matrix
9. refactor: replace legacy deterministic planner core
10. docs: close D6 rewrite after CI verification
```

Do not mark D6 complete in commit 9. Verify repository-wide CI first, then close the docs in a separate commit.

---

# 21. Implementation record (2026-09-12)

Branch `rewrite/d6-planner-core`, commits `d9a8e66`..`290de63` following the recommended sequence.

## Delivered

```text
Interval / TimeResolution          pure half-open interval math, DST-safe resolution,
                                   availability union, occupancy conversion, cutoffs
Reservation / PlanningState        one accepted semantic state; snapshot normalization
                                   with narrow authorized cleanup (D6R-001/002)
EffortTruth                        coverage, planned completion, dependency readiness,
                                   HARD before-cutoff satisfaction (D6R-005/006)
CandidateGeneration                finite structural starts x PLN-013 durations (D6R-007)
CandidateComparison                exact PLN-015 order + decision-trace explanations (D6R-008)
FullReplanEngine                   task-local Tentative -> TaskPlanDelta transaction,
                                   rank-gated displacement with proven relocation (D6R-003/004)
LocalReflowEngine                  bounded move-only repair on shared legality (D6R-009)
DeterministicPlanner               canonical facade; legacy engine deleted in 290de63
```

## Verification

```text
:shared:planner            47 tests green (13 legacy D6 + 12 R0 + 22 conformance/matrix)
:shared:application        21 tests green (PlanBranch create/stale/rebase/atomic apply)
:shared:domain             42 tests green
full repository build      BUILD SUCCESSFUL (320 tasks, includes apps)
```

R0 outcome on the legacy engine: R0-01..R0-09 and R0-11 failed for the documented
semantic reasons (ghost occupancy, rollback-by-retraction, missing clamped-original
candidates, after-cutoff satisfaction). R0-10 and R0-12 already passed because their
failure classes were closed by the pre-rewrite patches `a81b421` / `ef297e3`; both are
retained as conformance guards. The legacy suite's overflow fragmentation failure
(`normal deadline overflow honors never ask and allow`) is fixed by the rewrite.

## Explicit stable semantics recorded under D6R-008's field allowance

```text
authorized lateness (criterion 2)  = max(0, candidate.start + demand - cutoff): how late
                                   the Task's demand would complete if the rest landed
                                   contiguously after the candidate. Same-start candidates
                                   covering the same demand therefore tie here and the
                                   chunk-duration rank decides instead of fragmenting.
preserve existing placement (3)   = 3-tier rank: 0 exact original placement, 1 no
                                   displacement required, 2 displacement required.
availability                       = coalesced union of weekly windows; availability is a
                                   set of time, so adjacent windows form one legal region.
displacement eligibility           = strict PLN-008 service-order rank: a reservation of a
                                   not-yet-planned higher-ranked Task is never displaced.
search views                       = only the reservation being replaced by its own step is
                                   excluded from occupancy; all other reservations, including
                                   the Task's own pending subjects, stay real occupancy.
```

The original D6 task remains `FIX-AND-RECHECK` until this branch merges with CI green, per the
completion gate in section 18.

---

# 23. Second review round record (2026-09-13)

Post-review corrections on the same branch (commit `7d2bf1c`):

```text
P1 chunk rank          PlacementCandidate.durationRank (a per-segment list index that is not
                       comparable across structural starts) replaced by globally comparable
                       PLN-013 fields: preferredDistanceMillis + durationMillis, compared as
                       (distance from preferred, then longer duration). CandidateComparison
                       and the decision-trace keys use a ChunkRank value.
P1 relocation search   findRelocation rewritten as a complete finite search: every
                       structural start of every free interval (including the clamped
                       original start) enumerated and deadline/dependency filtered, best
                       option by smallest movement then earlier start. The closest position
                       being illegal no longer means "no relocation".
P1/P2 SOFT identity    A displaced SOFT block whose same-duration relocation is impossible
                       is now resized into the remaining legal slot for its own Task's
                       demand (never enlarged) instead of being deleted and re-created;
                       the FocusBlock identity survives as a net Resize.
P2 boundaries          The displacement view removes lower-authority reservations from
                       occupancy; their start/end boundaries are re-added as structural
                       start points of the resulting intervals.
```

New regressions in `PlannerReviewRound2Test`:

```text
cross-segment chunk ranks compare by global PLN-013 order (90m far chunk beats a
  locally-ranked 30m near chunk)
FLEXIBLE relocation finds the earlier deadline-legal position when the closest
  position violates the deadline (also exercises displaced-reservation boundaries
  as structural starts)
SOFT displacement falls back to identity-preserving resize instead of delete
```

Note on the relocation-search and boundary regressions: the candidate-enumeration
contract is what the tests pin; greedy earlier-start tie-breaks make some
boundary-start candidates lose to otherwise-equal earlier candidates, so a
boundary candidate does not always decide the final placement.

Updated verification totals: planner 57, application 21, domain 42, database 15 tests green;
full repository build successful.

---

# 24. Third review round record (2026-09-13)

Post-review corrections on the same branch (commit `7afee35`):

```text
P1 blocked relocation   relocationOptions returns no options when the displaced
                        Task's dependency completion is unresolved (D6R-002/PLN-009).
                        The previous "?: referenceNow" guess moved dependency-blocked
                        Tasks' blocks; the round-2 regression was reversed to pin the
                        contract (block keeps its original reservation, demand
                        reported as uncoverable).
P1 cross-task closure   TaskPlanDelta now reports displaced owner Tasks; after a
                        commit, every already-planned displaced owner - plus its
                        transitive already-planned dependents - is invalidated
                        (replaced/resized/proposed reservations removed, untouched
                        originals and the displacing replacements kept) and re-queued
                        for re-planning against the new truth. Issues are attributed
                        per Task so stale issues are replaced, not accumulated.
P1 arrangement truth    relocationOptions evaluates dependency boundaries against the
                        POST-arrangement view (earlier relocations in the same
                        transaction applied), so a relocation never relies on a
                        prerequisite position it just moved.
P1 authoritative HARD   finalize() validates every eligible HARD Task against the
                        final accepted state (coverageCompletedByCutoff), covering
                        dependency-blocked HARD Tasks that never reach planTask;
                        their runs are now Infeasible with HardDeadlineShortfall.
P1 backtracking         arrangeDisplacements is a finite depth-first search with
                        backtracking over each displaced reservation's relocation
                        options; the displaced reservations' range boundaries AND
                        their Tasks' deadline boundaries are structural starts of the
                        relocation space, so a greedy first choice can no longer
                        manufacture a false infeasibility.
P1 relocation ranking   relocation options are ranked with the same PLN-015
                        comparator as placements (CandidateComparison) - the
                        same-duration-first shortcut is gone; a before-deadline
                        resize now beats a closer same-duration overflow move.
P2 traces               displacement Move/Resize/Delete explanations carry the
                        decision criteria of the relocation comparison; delete keeps
                        CANONICAL_IDENTITY (a fallback after all options failed).
P2 NEW authority        planner-created proposals are displaceable exactly like the
                        SOFT blocks they materialize into.
Hardening               PlanningInvariants now also verifies dependency legality for
                        every planner-touched reservation.
```

New regressions in `PlannerReviewRound3Test` (plus the reversed round-2 expectation):

```text
displacing a prerequisite invalidates and replans the dependent
dependency-blocked HARD Task yields Infeasible with shortfall
multi-reservation displacement backtracks to a feasible arrangement
soft relocation obeys PLN-015 (before-deadline resize beats closer overflow move)
  and carries the DEADLINE_LEGALITY decision trace
relocation of a dependency-blocked Task is illegal (reversed round-2 expectation)
```

Note on the PLN-015 relocation ranking: the option ranking is shared with placement
ranking (one comparator); outcome-level discrimination of the deadline-class rule is
pinned by the before-deadline-resize fixture.

Updated verification totals: planner 64, application 21, domain 42, database 15 tests green;
full repository build successful.

---

# 25. Fourth review round record (2026-09-13)

Post-review corrections on the same branch (commit `9884727`):

```text
P0 NEW displacement    A displaced planner-created proposal (Proposed source, no
                       Active State ID) no longer crashes requireNotNull(blockId):
                       its replacement/removal is a silent accepted-state change and
                       netMutations() derives the final Create set, so the output
                       contains Creates only.
P0 closure seeding     The closure is seeded by every Task whose accepted truth
                       (future coverage / planned completion) changed across a
                       delta - covering the planned Task's own self-replan as much
                       as cross-task displacements - instead of displacement alone.
                       The planned Task's fresh delta is excluded from the
                       invalidation itself; its already-planned dependents are not.
P1 identity restore    invalidateTask() now undoes the Task's accepted delta by
                       restoring its normalized original reservations (replacing
                       moved/resized/proposed ones), so an invalidated existing
                       FLEXIBLE block keeps its FocusBlock identity - never
                       Delete + Create (D6R-001).
P2 relocation traces   A relocation chosen as the comparator winner carries the
                       decision trace; a feasibility-driven fallback choice (whose
                       comparator-winner subtree was infeasible) records
                       CANONICAL_IDENTITY instead of criteria it actually lost.
P2 lateness reference  relocation options rank overflow lateness by the displaced
                       Task's remaining demand, matching the placement semantics.
```

New regressions in `PlannerReviewRound4Test`:

```text
later-ready HARD Task displaces a prior NEW proposal without throwing; the net
  output is Creates only and no mutation references a nonexistent ID
prerequisite self-replan invalidates the already-planned dependent (no invariant
  throw; the dependent is re-planned behind the moved completion)
the invalidated dependent's original FLEXIBLE identity survives (no Delete +
  Create; at most one net Move)
```

Also corrected the third-round record's commit hash (`7afee35`, not `f7a23ba`).

Updated verification totals: planner 63, application 21, domain 42, database 15 tests green;
full repository build successful.

---

# 26. Fifth review round record (2026-09-13)

Post-review corrections on the same branch (commit `c9325d4`):

```text
P0 selected-task      Every placement candidate with a displacement arrangement is
   dependency         re-validated against the POST-arrangement dependency truth:
   legality           dependencyEarliestStart(planningTask) computed over current
                      reservations - displaced + relocation replacements + the
                      candidate itself. A candidate that displaced its own
                      prerequisite past its own start (or pushed a prerequisite
                      out of coverage) is rejected and the search continues;
                      PLN-009 is enforced before anything enters the accepted
                      state instead of relying on the final invariant throw.
P1 closure rollback   invalidateTask() is provenance-aware: reservations the
   provenance         CURRENT delta explicitly relocated or removed for the
                      invalidated Task survive (they are part of the planned
                      Task's atomic TaskPlanDelta - D6R-003/004); the Task's own
                      earlier delta contributions roll back to the normalized
                      originals. Provenance is the stable reservation source
                      identity (Existing block ID / Proposed proposal key) - no
                      history system involved. A current-delta relocation can no
                      longer be silently undone by the closure.
P2 relocation truth   relocationOptions evaluates the displaced Task's dependency
                      boundary against the post-arrangement view including the
                      candidate itself, so a displaced Task that depends on the
                      planning Task respects the planning Task's post-candidate
                      completion.
P2 regression         R4-02 fixture strengthened per review: the dependent's
                      original [09:30,10:30) is legal until the prerequisite's
                      availability repair moves the completion to 11:30, and the
                      closure must produce exactly one net Move of the SAME
                      FocusBlock ID to [11:30,12:30) - zero or two mutations fail.
```

New regressions in `PlannerReviewRound4Test` (strengthened):

```text
prerequisite self-replan invalidates the already-planned dependent: exactly one
  net Move, same FocusBlock ID, [11:30,12:30), no Delete/Create
```

Updated verification totals: planner 63, application 21, domain 42, database 15 tests green;
full repository build successful.

---

# 27. Sixth review round record (2026-09-13)

Post-review corrections on the same branch (commit `e8c50f8`):

```text
P1 subject exclusion  The relocation provisional state now excludes the planning
                      Task's own subject reservation that the candidate replaces
                      (arrangeDisplacements/relocationOptions take the subject).
                      Without it, a legal swap (HARD block 11-12 -> 10-11 while a
                      displaced FLEXIBLE 10-11 -> 11-12) was judged infeasible
                      because the old subject still occupied the relocation space.
P1 dependency-resolvable DFS
                      The displacement DFS no longer iterates the displaced
                      reservations in fixed range order: every remaining
                      reservation is tried as the next placement, so a displaced
                      dependency chain (D depends on P) is deferred until P's
                      replacement makes D's dependency floor resolvable. The base
                      case re-validates every replacement's dependency floor
                      against the COMPLETE post-arrangement state before accepting
                      the arrangement, so a later relocation cannot invalidate an
                      earlier one unnoticed.
```

New regressions in `PlannerReviewRound6Test`:

```text
existing HARD subject can swap with a displaced FLEXIBLE block (A 11-12 -> 10-11,
  B 10-11 -> 11-12; the swap is provable, not falsely infeasible)
displaced dependency chain is arrangement-order independent (P 10-11 -> 11-12,
  D 09-10 -> 12-13, D after P's new completion; fixed-order DFS fails here)
selected Task cannot displace its own prerequisite past its own start
  (A 09:00-10:30 rejected because the displaced prerequisite's completion moves
  to 11:00; the run reports the HARD shortfall instead of entering an illegal
  state or crashing on the final invariant)
```

The provenance-survival regression (current-delta relocation surviving the
invalidated owner's rollback) remains pinned by the round-3 closure test, which
asserts the owner's kept replacement ([10:00,11:00]) as the final position.

Updated verification totals: planner 66, application 21, domain 42, database 15 tests green;
full repository build successful.

---

# 22. Review round record (2026-09-13)

Post-review corrections on the same branch (commit `8e6e511`, rebased onto `ba3d326`):

```text
P0 invalid snapshot  SnapshotValidation extracted; both planner entries validate the
                     snapshot before any Interval or derived view is constructed, and
                     FullReplanEngine futureBounds is lazy, so an out-of-range
                     referenceNow returns InvalidInput instead of throwing.
P0 net mutations     finalize() diffs the input Active State against the final accepted
                     reservation state and emits exactly one mutation per FocusBlockId
                     (Move after two displacements, Resize after displacement+resize,
                     Delete for removals). The internal event log is no longer the output,
                     so every Success is applicable by PlanBranch Apply.
P1 reflow deps       Local Reflow now evaluates dependencies through EffortTruth over a
                     provisional reservation view; cancelled / unknown / zero remaining /
                     partially planned prerequisites make the reflow Infeasible.
P1 durations         PLN-013 duration sets are enumerated per structural start from that
                     start's own segment capacity (CandidateGeneration.durationsForSegment).
P2 boundary          Local Reflow immovable check uses start < referenceNow, matching
                     PLN-007 and Full Replan.
```

New regressions in `PlannerReviewRegressionTest`:

```text
referenceNow at/before horizon boundary -> InvalidInput from both entries, never throw
Local Reflow invalid snapshot -> InvalidInput
FLEXIBLE displaced twice -> exactly one final Move
SOFT displaced then resized -> exactly one final Resize
Local Reflow cancelled/unknown/zero prerequisite -> Infeasible(DependencyBlocked)
later structural start enumerates its own segment-capped PLN-013 durations
```

Updated verification totals: planner 54, application 21, domain 42, database 15 tests green;
full repository build successful after the rebase. CI on the pushed branch: green (run #126).
