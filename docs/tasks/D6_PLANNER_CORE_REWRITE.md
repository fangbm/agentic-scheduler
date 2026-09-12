# Agentic Scheduler — D6 Planner Core Rewrite

> Task ID: **D6-R1**  
> Milestone: **D6 — Planner**  
> Status: **IMPLEMENTED — local verification green; remote CI and D6 status flip pending push**  
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

The original D6 task remains `FIX-AND-RECHECK` until remote CI is green on push, per the
completion gate in section 18.
