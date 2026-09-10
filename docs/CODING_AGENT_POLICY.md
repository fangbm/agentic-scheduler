# Agentic Scheduler — Coding Agent Policy

> Status: **Mandatory v1**  
> Audience: coding agents, reviewers, maintainers delegating implementation work.

The purpose of this policy is to prevent a coding agent from turning an unspecified choice into a permanent architectural decision merely because it was trying to finish a task autonomously.

---

# 1. Required reading order before implementation

For any non-trivial task, read the relevant sources in this order:

```text
1. Current task spec / acceptance criteria
2. docs/DOMAIN_INVARIANTS.md
3. relevant ADR(s), if any
4. docs/IMPLEMENTATION_CONTRACT.md
5. docs/ARCHITECTURE_DIAGRAMS.md
6. docs/MODULE_OWNERSHIP.md
7. docs/UBIQUITOUS_LANGUAGE.md
8. existing code in the affected module(s)
9. relevant tests
```

Do not begin by copying the nearest code pattern before checking whether that pattern is consistent with the frozen specification.

---

# 2. Task boundary is authoritative

Every task has both positive and negative scope.

A coding agent is expected to complete all in-scope work, but must not use that goal as permission to implement later roadmap layers.

Examples of unauthorized scope expansion:

```text
D2 Domain task → adding Room schema
D2 Domain task → adding Sync serialization
Calendar UI task → redesigning Planner architecture
Wear Tile task → introducing a new DI framework
Bug fix → upgrading Kotlin/Gradle/AGP
Agent Tool task → exposing generic repository write access
```

If later work is obviously useful, note it as follow-up rather than implementing it automatically.

---

# 3. No invented business defaults

When a domain/API field requires a product-semantic default and none is specified, do not choose one based on intuition.

Examples:

```text
priority = MEDIUM
flexibility = SOFT
default reminder = 10 minutes
default deadline behavior = HARD
default Agent mode = Assisted
default provider = OpenAI
```

These are product decisions, not harmless coding conveniences.

Required behavior:

```text
If caller can provide value → require it explicitly.
If API cannot proceed without a default → BLOCKED_BY_DECISION.
```

---

# 4. No hidden architecture decisions

The following always require explicit authorization through task/spec/ADR and cannot be inferred:

```text
new Gradle module
new persistence engine
new serialization/wire format
new cryptographic primitive/key hierarchy
new sync conflict policy
new Last-Write-Wins rule
new DI framework
new global UI architecture framework
new provider-memory strategy
new server/client responsibility boundary
new background Agent permission model
new cross-device secret transport
new public domain vocabulary replacing canonical terms
```

---

# 5. Ambiguity classification

When the implementation encounters an unspecified decision, classify it before proceeding.

## A. LOCAL_REVERSIBLE

May be decided by the coding agent.

Criteria: all must be true.

```text
- entirely local implementation detail
- not persisted or transmitted
- does not alter public/domain semantics
- does not create a new cross-module contract
- easy to replace later
- no security/data-loss effect
```

Examples:

- name of a private local variable;
- internal loop organization;
- extracting a private pure helper;
- choosing `when` versus `if`.

Use the simplest clear implementation.

## B. CONTRACT_AFFECTING

Do not guess.

Examples:

- public method shape;
- cross-module interface ownership;
- business default;
- canonical name;
- persistence mapping that constrains future semantics;
- public error behavior.

Action:

```text
mark BLOCKED_BY_DECISION for disputed portion
state the exact missing decision
continue only independent safe work
```

## C. ARCHITECTURE_AFFECTING

Do not implement without approved decision/ADR.

Includes:

```text
Domain invariant
Planner determinism
PlanBranch semantics
Sync / merge / causality
E2EE / secrets
Agent context/history authority
Tool permissions
module dependency direction
server/client responsibility
Wear security/provider behavior
```

## D. SECURITY_OR_DATA_LOSS

Stop immediately on the affected path.

Never infer a permissive fallback for:

```text
secret handling
E2EE downgrade
irreversible deletion
history destruction
sync conflict loss
permission bypass
plaintext server exposure
```

---

# 6. Missing information does not grant permission

The absence of a rule means the decision is undecided, not that the agent may choose freely.

Do not reason:

> "The specification doesn't forbid it, so it is allowed."

For cross-cutting choices the rule is the opposite:

> **If the decision would become expensive to change later, lack of specification means it must be escalated.**

---

# 7. Existing code is not automatically authoritative

Existing code may be incomplete, temporary, or wrong.

If implementation conflicts with frozen docs:

```text
Frozen invariant/spec > existing code pattern
```

Do not propagate a known contradiction into new code.

Also do not launch an unauthorized repo-wide cleanup. Report the discrepancy and change only what the current task permits.

---

# 8. No speculative abstraction

Do not build architecture for hypothetical future requirements unless required by the current specification.

Avoid:

```text
interfaces with one implementation "for future flexibility"
generic plugin frameworks before a second implementation exists
abstract base entities that collapse domain distinctions
premature repository/service layers
future-proof serialization annotations in Domain
```

Prefer the smallest implementation that satisfies current contracts while preserving frozen boundaries.

---

# 9. No generic escape hatches

Do not create APIs whose purpose is to avoid defining semantics.

Forbidden examples for the Agent layer:

```text
executeSql(query)
updateEntity(type, arbitraryJson)
runCommand(commandString)
writeMemory(arbitraryBlob)
executeAction(arbitraryJson)
```

Use typed capabilities with explicit validation, risk, permission, transaction, audit, and Undo behavior.

---

# 10. No silent fallback behavior

When an important operation cannot satisfy its contract, surface a typed/explicit failure rather than silently doing something weaker.

Examples:

```text
semantic merge fails → SyncConflict, not LWW
PlanBranch stale → STALE, not force apply
missing Agent context → retrieve/clarify, not guess
provider call fails → Tool/Provider failure, not pretend success
infeasible schedule → InfeasibleSchedule, not schedule outside bounds
unsupported Watch STT → disable capability, not cloud STT fallback
```

---

# 11. Agent context rule

A coding agent implementing the product's Agent must preserve application-owned continuity.

Never make correctness depend on provider-side thread/session memory.

Do not collapse:

```text
AgentThread
ContextSummary
ContextAnchor
AgentAction
ChangeLog
ToolResult
```

into a generic `messages` or `memory` concept when their authority/lifecycle differs.

When context is insufficient, the runtime Agent should use approved retrieval tools or request clarification rather than hallucinating missing state.

---

# 12. History rule

Historical facts are append-oriented and inspectable.

Do not implement Undo by deleting evidence of the original action.

Do not let generated summaries overwrite authoritative historical records.

Do not let provider chat logs become the only explanation of what actually changed.

---

# 13. Determinism rule

Do not introduce hidden nondeterminism into Domain, Planner, merge, or replay-sensitive logic.

Watch for:

```text
current system time read from inside pure logic
random UUID/random score generation
unordered hash iteration as output order
platform-dependent locale/timezone defaults
LLM output affecting deterministic Planner score without structured input
```

Inject/provide time, IDs, timezone, locale, ordering, and seed where they affect semantics.

---

# 14. Dependency rule

Before adding a dependency, answer:

```text
What current requirement needs it?
Which module owns it?
Is it multiplatform-compatible where required?
Does it alter binary/platform constraints?
Is there already an approved solution?
Is the dependency decision inside current task scope?
```

If the answer to the last question is no, do not add it.

---

# 15. Refactor rule

Allowed:

- local refactor necessary for correctness;
- extraction needed to test current behavior;
- small cleanup directly caused by the task.

Not allowed without explicit scope:

- broad naming migration;
- module movement;
- architecture replacement;
- dependency modernization;
- unrelated style cleanup across many files;
- changing public contracts just to make implementation prettier.

---

# 16. Migration rule

Once persistent/wire data exists, any change to its interpretation requires an explicit migration/compatibility plan.

Never change serialized meaning and assume all users/devices update simultaneously.

Future changes involving:

```text
DB schema
SyncOperation schema
E2EE envelope
ProviderCredentialEnvelope
external mapping IDs
```

must state backward/forward compatibility expectations.

---

# 17. Platform divergence rule

Platform-specific UI/integration is expected. Domain semantic divergence is not.

Android, Desktop, and Wear may expose different capabilities, but they must not independently redefine:

```text
Event
Task
FocusBlock
CourseSession
PlanBranch
AgentAction
SyncOperation
```

If a platform cannot support a capability, model capability availability explicitly rather than changing the shared concept.

---

# 18. Required implementation report

When finishing a delegated coding task, report at least:

```text
Implemented:
- concise list of completed behavior

Tests/verification:
- commands/tests actually run and results

Architecture:
- affected invariants/contracts, or "none"

Decisions/assumptions:
- approved decisions used
- LOCAL_REVERSIBLE assumptions made
- any BLOCKED_BY_DECISION items

Out of scope:
- important intentionally unimplemented follow-ups
```

Do not claim a task is complete if required acceptance criteria remain blocked.

---

# 19. Required task packet

Before substantial implementation, the task should ideally specify:

```text
Goal
Inputs / existing state
MUST
MAY
MUST NOT
Acceptance criteria
Relevant canonical docs
Allowed dependencies/module changes
Migration requirement
Security/privacy impact
Unresolved decisions
```

Use `TASK_SPEC_TEMPLATE.md` for this structure.

If no task packet exists, derive only obvious scope from the user request and frozen documentation. Do not infer permission for cross-cutting decisions.

---

# 20. D2-specific guardrail

Until D2 is explicitly declared complete, D2 domain work follows:

```text
MUST:
- implement requested pure Domain IDs/value objects/entities
- enforce specified single-object/cross-entity invariants
- add deterministic common tests
- use canonical vocabulary

MAY:
- add pure helper/value types needed by the above
- add an explicitly required multiplatform time dependency via version catalog

MUST NOT:
- implement Room/SQL schema
- implement repository persistence
- implement SyncOperation wire schema
- implement Agent runtime/provider adapters
- implement server APIs
- implement full Planner algorithms
- add UI feature flows
- introduce DI framework
- add serialization annotations for future convenience
```

D2 is successful when Domain semantics are trustworthy, not when future milestones have been prebuilt.

---

# 21. Never optimize for "agent autonomy" over project integrity

A coding agent should be autonomous about **execution**, not about redefining the system.

It may autonomously:

```text
inspect code
write implementation
write tests
run verification
fix local bugs
perform reversible local refactors
```

It may not autonomously:

```text
invent product semantics
change architecture
weaken security
choose irreversible compatibility behavior
expand task scope into later milestones
```

---

# Final policy

When the only way to "finish" a task is to guess a high-impact decision, the correct result is not a guessed implementation.

The correct result is a clearly identified decision boundary.
