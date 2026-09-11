# Agentic Scheduler — Implementation Contract

> Status: **Frozen Baseline v1**  
> Purpose: eliminate high-impact implementation guessing by humans and coding agents.

This document defines the default implementation choices that are allowed without a new architectural decision. If a task, ADR, or specification explicitly overrides a rule here, the more specific approved decision wins. If two approved sources conflict, implementation must stop and report the conflict rather than choosing one arbitrarily.

---

# 1. Decision precedence

When deciding how to implement a task, use this order:

```text
1. Current task acceptance criteria / task spec
2. DOMAIN_INVARIANTS.md
3. Approved ADRs
4. IMPLEMENTATION_CONTRACT.md
5. ARCHITECTURE_DIAGRAMS.md
6. MODULE_OWNERSHIP.md
7. UBIQUITOUS_LANGUAGE.md
8. Existing established code pattern
9. Local engineering judgment
```

A lower source may fill a gap but may not contradict a higher source.

If two higher-priority sources conflict:

```text
DO NOT GUESS
→ mark BLOCKED_BY_SPEC_CONFLICT
→ identify the conflicting statements
→ do not implement the disputed behavior
```

---

# 2. Frozen toolchain baseline

Until an explicit dependency/toolchain task changes them, use the repository versions as authoritative:

```text
JDK / JVM target          17
Kotlin                    2.3.21
Gradle Wrapper            9.6.0
Android Gradle Plugin     9.4.0
Compose Multiplatform     1.10.3
Android compileSdk        37
Android targetSdk         37
Android app minSdk        26
Wear app minSdk           30
```

Versions belong in `gradle/libs.versions.toml` when catalog-compatible.

Coding agents must not silently upgrade/downgrade Kotlin, Gradle, AGP, Compose, SDK levels, or major dependencies while implementing an unrelated task.

---

# 3. Package and namespace convention

Canonical root package:

```text
dev.agenticscheduler
```

Current module/package roots include:

```text
dev.agenticscheduler.android
dev.agenticscheduler.desktop
dev.agenticscheduler.wear
dev.agenticscheduler.domain
dev.agenticscheduler.application
dev.agenticscheduler.database
```

For domain code, prefer semantic packages rather than generic `model`, `util`, or `common` buckets:

```text
dev.agenticscheduler.domain.id
dev.agenticscheduler.domain.time
dev.agenticscheduler.domain.event
dev.agenticscheduler.domain.task
dev.agenticscheduler.domain.course
dev.agenticscheduler.domain.exam
dev.agenticscheduler.domain.reminder
dev.agenticscheduler.domain.project
dev.agenticscheduler.domain.planning
```

Do not introduce a new top-level package root or rename canonical concepts without an approved task/ADR.

---

# 4. Module creation policy

Current modules are authoritative until the roadmap/task explicitly introduces more:

```text
:shared:domain
:shared:application
:shared:database
:apps:android
:apps:desktop
:apps:wear
```

D4 explicitly authorized and introduced `:shared:application`. A coding agent may not create another Gradle module merely because it seems cleaner.

A new module requires either:

- an explicit current task instruction, or
- an approved architecture decision.

Package extraction inside an existing module is allowed if it preserves ownership rules and does not create a new public architectural boundary.

---

# 5. Domain IDs

Every synchronizable domain entity uses a strongly typed ID.

Preferred Kotlin shape:

```kotlin
@JvmInline
value class TaskId(val value: String)
```

Rules:

- IDs are supplied to domain entities/factories; entities do not call random generators internally.
- Production generation uses UUIDv7 semantics.
- Canonical persisted/transmitted representation is lowercase standard UUID text.
- Tests use deterministic IDs, never random values unless randomness itself is under test.
- `EventId`, `TaskId`, `FocusBlockId`, etc. are not interchangeable.

Direct calls to random UUID generation from domain business logic are forbidden.

ID generation belongs to an application/infrastructure boundary through an injectable abstraction such as `IdGenerator`.

The exact production UUIDv7 generator implementation remains deferred until the first production creator task explicitly freezes it.

---

# 6. Clock and current time

Domain and Planner code must not scatter direct calls to system time.

Forbidden in deterministic business logic:

```text
Clock.System.now()        // directly inside arbitrary business methods
System.currentTimeMillis()
Instant.now()
```

Time-sensitive operations receive time through an explicit input or an injected `Clock` abstraction at the application boundary.

Tests use a fixed/fake clock.

A recorded `createdAt` or planning reference time therefore has a deterministic source.

---

# 7. Time API

Common/domain code uses multiplatform time semantics. Do not introduce `java.time.*` into `commonMain`.

When concrete date/time types are needed, use Kotlin multiplatform-compatible APIs, with `kotlinx-datetime` as the approved external date/time library unless an ADR changes that choice.

Durations use Kotlin `Duration`, not raw millisecond/second `Long` values in domain APIs.

Raw epoch values are permitted only at serialization/persistence boundaries where explicitly mapped.

All interval semantics continue to follow `DOMAIN_INVARIANTS.md`, including half-open `[start, end)` ranges and Zoned / AllDay / Floating distinctions.

---

# 8. Immutability

Domain value/state objects are immutable by default.

Prefer:

```text
val properties
immutable collections at API boundaries
copy / explicit transition methods
```

over public mutable `var` state.

Mutation is performed by constructing/replacing domain state through explicit operations, not by allowing arbitrary callers to edit fields in place.

Mutable internal implementation details are acceptable inside a pure function/algorithm when not exposed as shared state.

---

# 9. Nullability and state modeling

`null` means absence, not an unnamed business state.

Use nullable fields only when absence is genuinely part of the concept, such as an optional description.

When different states have different semantics, use an enum/sealed hierarchy, for example:

```text
ExamSchedule.Unscheduled
ExamSchedule.DateOnly
ExamSchedule.Exact
```

Do not use combinations of nullable fields to encode hidden state machines.

Do not invent sentinel values such as empty UUID, `-1`, `Long.MAX_VALUE`, or empty string to represent missing domain information.

---

# 10. Enums versus sealed types

Use an enum when:

- states are a closed flat set;
- no variant-specific payload is required.

Use a sealed interface/class when:

- variants carry different payloads;
- variants have meaningfully different valid fields.

Do not replace a domain concept with arbitrary string constants merely to simplify serialization.

---

# 11. Entity construction and validation

An object that can exist in active Domain State must be valid immediately after construction.

Single-object invariants are enforced at construction/factory boundaries.

Examples:

```text
TimeRange start < end
remainingEffort >= 0
required IDs are non-empty/valid
```

Cross-entity invariants belong to deterministic domain services/validators, for example:

```text
Task dependency DAG validation
PlanBranch baseline validation
reference existence checks
```

UI, database constraints, and serializers may duplicate validation defensively, but they are not the source of truth for domain validity.

Do not create invalid objects and rely on a later `validate()` call before use.

---

# 12. Error model

Expected business-rule failures are modeled explicitly and must not be represented as generic runtime crashes.

Examples:

```text
InvalidTimeRange
DependencyCycle
StalePlanBranch
PermissionDenied
InfeasibleSchedule
```

At module/public boundaries, use typed result/error values or a documented domain exception hierarchy according to the API being implemented. Do not introduce a third-party `Either`/functional error library without an explicit dependency decision.

Programming defects/invariant violations may fail fast.

Do not catch `Throwable`/`Exception` and convert every failure to `null` or `false`.

---

# 13. Business defaults

Coding agents must not invent product-semantic defaults.

The following require an explicit specification before a default is introduced:

```text
priority
flexibility
pin state when non-obvious
default reminder
default task estimate
default deadline policy
default overflow policy
default planning profile
default Agent autonomy
default Tool permission
default calendar participation
default AI Provider/model
default history/context retention window
```

If an API requires a value and no approved default exists, require the caller to provide it or mark the task `BLOCKED_BY_DECISION`.

Pure technical defaults with no user/domain semantics may follow established project conventions.

---

# 14. Collections and ordering

Choose collection semantics from domain meaning:

- `List` when order is meaningful.
- `Set` when uniqueness is meaningful and order is not semantic.
- keyed maps only when lookup identity is part of the API.

Do not use collection iteration order as a hidden deterministic tie-breaker unless the ordering is explicitly defined.

Planner and merge outputs that require stable order must define an explicit comparator/tie-break rule.

---

# 15. Equality

Value objects use structural equality.

Entity identity is defined by its typed ID across revisions.

Do not infer logical entity identity from mutable fields such as title, start time, or current location.

Occurrence identity follows the explicit occurrence semantics in `DOMAIN_INVARIANTS.md`.

---

# 16. Persistence boundary

Domain objects must not receive database annotations.

Forbidden in `shared:domain`:

```text
@Entity
@Dao
RoomDatabase
SQL schema annotations
persistence-specific IDs/foreign-key behavior
```

D4 resolved the concrete local persistence baseline:

```text
Room 3.0.3
SQLite KMP 2.7.0
BundledSQLiteDriver
AgenticSchedulerDatabase schema v1 baseline
schema export under shared/database/schemas
no destructive-migration fallback
```

`shared:database` owns persistence representations, migrations, DAOs, and mapping between persistence records and domain objects.

Database schema choices must not redefine domain semantics.

Later schema changes must preserve D4 migration/compatibility rules and add explicit migration coverage.

---

# 17. Repository ownership

D4 resolved repository placement.

Application-facing persistence contracts live in:

```text
:shared:application
dev.agenticscheduler.application.persistence
```

Concrete Room/SQLite implementations live in:

```text
:shared:database
```

Rules:

- repository contracts remain outside `shared:domain`;
- platform UI consumes application contracts/services, not DAOs;
- database records/DAOs do not leak into application/domain APIs;
- moving repository contracts to another module requires an explicit superseding decision.

This item is no longer `DECISION_REQUIRED`; OD-011 records the resolved boundary.

---

# 18. Transactions

Business operations that change multiple entities atomically must expose one application-level transaction boundary.

D4 provides `ApplicationTransactionRunner` in `shared:application` with its Room implementation in `shared:database`.

PlanBranch Apply is always atomic.

Agent multi-tool changes that are defined as one logical transaction must not partially commit.

UI code and LLM adapters do not own transaction boundaries.

Concrete database transaction APIs belong to the persistence layer; higher layers express atomic intent without importing persistence APIs into Domain.

---

# 19. Deletion

Do not rely on database cascade deletion as domain behavior.

Deletion effects on related entities must be explicit operations.

Synchronized entities retain tombstone/deletion semantics until sync-safe compaction.

A local persistence optimization must not destroy information required for convergence/audit.

---

# 20. Serialization

Serialization format is not part of Domain modeling unless the current task explicitly requires it.

Do not add serialization annotations to domain classes merely for convenience.

Wire/sync serialization and schema versioning are separate contracts owned by protocol/sync layers.

If a future decision adopts `kotlinx.serialization`, its annotations may be used only according to that approved mapping strategy; this document does not pre-authorize coupling every domain object to a wire format.

---

# 21. Coroutines and threading

Domain entities/value objects contain no coroutine scope or dispatcher ownership.

Suspend/Flow APIs are introduced only when the operation is genuinely asynchronous/streaming.

Rules:

- UI owns UI lifecycle scopes.
- infrastructure/data implementations own blocking I/O dispatch details.
- callers should not be forced to know a repository's internal dispatcher.
- deterministic Planner/domain pure calculations do not become `suspend` merely for future-proofing.

Do not create global coroutine scopes for application behavior.

---

# 22. Dependency injection

No DI framework is currently frozen.

Therefore:

```text
Koin        NOT AUTHORIZED BY DEFAULT
Dagger/Hilt NOT AUTHORIZED BY DEFAULT
Kodein      NOT AUTHORIZED BY DEFAULT
```

Until an explicit decision is made, use constructor injection/manual composition where dependency injection is required.

A coding agent may not introduce a DI framework during an unrelated feature.

---

# 23. Dependency additions

Every new third-party dependency must have a concrete reason.

Rules:

- add versions centrally where possible;
- do not add a library for functionality trivial to implement safely with the standard library;
- do not add dependencies preemptively for future roadmap work;
- do not replace an existing approved dependency as part of unrelated work;
- check multiplatform/platform compatibility before adding shared dependencies.

High-impact dependencies involving Planner, Sync, Crypto, persistence, serialization, DI, or networking require an explicit task decision or ADR.

---

# 24. Logging and sensitive data

Logging is diagnostic infrastructure, not persistent truth.

Never log plaintext:

```text
API keys / tokens
E2EE keys
provider credentials
raw decrypted attachments
full private schedule payloads by default
raw microphone audio
```

Logs must not become a hidden source of Agent memory or synchronization state.

A logging framework is **DECISION_REQUIRED** before introducing a project-wide dependency; use minimal platform facilities only where necessary until then.

---

# 25. Agent context and history implementation

The application owns Agent continuity.

Provider-side conversation/session IDs may be cached but are never authoritative.

At implementation time preserve these distinct concepts:

```text
AgentThread       durable conversation continuity
AgentMessage      durable message/tool-result record
ContextSummary    lossy compaction artifact
ContextAnchor     local ephemeral UI/session context
AgentAction       structured action/audit fact
ChangeLog         authoritative state-change history
ToolResult        authoritative execution result
```

Do not merge these into a single chat-message table/model.

History tools are read-only. Undo creates compensating history rather than deleting prior history.

Context compaction may remove old text from the active prompt but must not erase authoritative stored history.

---

# 26. Agent write path

No implementation may create a bypass around:

```text
LLM proposal
→ typed Tool Call
→ schema validation
→ domain/planner validation
→ permission evaluation
→ preview/PlanBranch when required
→ atomic execution
→ ToolResult + AgentAction + ChangeLog
→ SyncOperation when synchronized state changed
```

Provider adapters contain no business writes and no hidden side effects.

---

# 27. Tool schemas

Agent Tools use typed, versionable schemas.

A tool must define before being considered complete:

```text
canonical name
purpose
input schema
output/result schema
validation rules
read/write classification
risk level
permission behavior
transaction behavior
history/audit behavior
Undo behavior if applicable
```

Do not expose generic SQL/repository mutation tools to the LLM.

Do not use one ambiguous "execute action" tool carrying arbitrary JSON when specific typed tools are possible.

---

# 28. ContextAssembler behavior

ContextAssembler is application infrastructure, not free-form prompt logic embedded in Provider adapters.

It deterministically assembles a minimum context from approved sources, including when relevant:

```text
current AgentThread
current ContextSummary
recent messages / ToolResults
local ContextAnchor
relevant domain snapshot
relevant recent AgentActions
```

Additional information is obtained through typed read-only search/domain/history tools.

Do not send the whole local database to the model by default.

Do not allow Provider-specific prompt code to define different domain truth rules.

---

# 29. Planner determinism

Planner behavior must remain reproducible.

Any value that can change a scheduling result must be explicit in the input/configuration or deterministic application state.

Forbidden hidden inputs include:

```text
unordered collection iteration
system clock read mid-calculation
random number without explicit seed
LLM preference masquerading as Planner score
platform-specific iteration behavior
```

If stochastic optimization is ever introduced, the random seed becomes part of the reproducible planning input and requires an explicit architecture decision.

---

# 30. UI architecture

Until a dedicated UI architecture decision is made, do not introduce a project-wide MVI/MVVM framework or navigation framework simply to implement a small screen.

Platform UI may keep simple local state during early milestones.

Domain rules do not live in composables/activities/windows.

A platform UI may adapt application/domain state for display but must not redefine entity semantics.

Large UI state/navigation architecture remains `DECISION_REQUIRED` when the first feature exceeds simple screen-local state.

---

# 31. Wear-specific rule

Wear may expose a smaller capability surface than Android/Desktop, but it uses the same domain meanings.

Platform-specific capability checks belong to Wear application/infrastructure code, not `shared:domain`.

Network availability must not be used as the definition of on-device STT capability.

Provider secrets never travel as ordinary workspace/domain SyncOperations.

---

# 32. Tests

Use `kotlin.test` for pure shared/domain tests unless a later test-infrastructure decision adds a justified library.

Tests should be deterministic:

```text
fixed clock
fixed IDs
explicit timezone
explicit locale when relevant
no real network
no dependency on wall-clock date
```

Every invariant implementation requires positive and negative tests where practical.

For Planner/Sync later, deterministic replay/convergence tests are mandatory for affected semantics.

---

# 33. Test fixtures

Fixture values must be explicit and stable.

Avoid random fixtures except property/fuzz tests designed for randomness and seeded for replay.

Use named fixture builders only when they preserve visibility of important semantic fields. Do not create a "magic default entity" that hides critical values such as timezone, flexibility, deadline policy, or IDs.

---

# 34. TODO and placeholder policy

Coding agents must not declare a task complete while required acceptance behavior is replaced by:

```text
TODO()
NotImplementedError
placeholder return
hard-coded success
comment-only implementation
```

A TODO is acceptable only for explicitly out-of-scope future work and should state the milestone/decision that owns it when known.

Do not use TODO as a substitute for escalating a missing architectural decision.

---

# 35. Scope control

A milestone/task defines a positive and negative scope.

Coding agents must not implement later roadmap layers early unless the current task explicitly requires them.

Example for an early pure-Domain task:

```text
MAY:
- add pure domain IDs/value objects/entities
- add pure invariant validation
- add common unit tests

MUST NOT unless the current task authorizes it:
- add persistence/schema work
- add Sync serialization
- add network/API code
- add Agent runtime
- add Planner implementation
- add UI feature work
- add server code
```

A useful future abstraction is not sufficient justification for scope expansion.

---

# 36. Refactoring policy

Small refactors required to implement the task safely are allowed.

Broad unrelated cleanup is not.

Do not rename unrelated public concepts, move modules, replace frameworks, or reform architecture during a feature/fix task without explicit scope.

When a discovered refactor is useful but not required, record it separately rather than bundling it automatically.

---

# 37. Existing code versus specification

Existing code is evidence of an established implementation pattern only when it does not conflict with approved docs.

If code contradicts a frozen invariant/spec:

```text
Do not copy the contradiction.
Do not silently "fix the architecture" across the repo.
Report the discrepancy and limit changes to the authorized task.
```

---

# 38. Spec gaps and escalation classes

When a required decision is missing, classify it.

## LOCAL_REVERSIBLE

Characteristics:

- contained inside one implementation;
- no persisted/wire/public semantic effect;
- easy to change later;
- does not affect another module's contract.

Agent may choose the simplest conventional solution and document it briefly if material.

## CONTRACT_AFFECTING

Affects public API shape, ownership, naming shared across modules, business defaults, or persistence mapping.

```text
STOP disputed portion
→ BLOCKED_BY_DECISION
→ state exact decision needed
```

## ARCHITECTURE_AFFECTING

Affects Domain semantics, Planner determinism, Sync, E2EE, permissions, Agent memory/history, module dependency direction, server/client responsibility, or Wear security behavior.

```text
STOP
→ ADR / explicit decision required
```

## SECURITY_OR_DATA_LOSS

Could expose secrets/plaintext, lose history, corrupt synchronization, or irreversibly delete user data.

```text
STOP immediately
→ do not infer a default
```

---

# 39. Prohibited autonomous decisions

Unless explicitly authorized, a coding agent must not independently:

- change a frozen domain invariant;
- invent a business default;
- add/replace a persistence engine;
- choose a serialization/wire protocol;
- change Sync conflict semantics;
- add Last-Write-Wins behavior;
- choose/replace cryptographic primitives;
- weaken E2EE boundaries;
- introduce a DI framework;
- introduce a global UI architecture framework;
- add a generic LLM-to-database mutation path;
- use provider conversation state as Scheduler memory;
- remove historical/audit records to implement Undo;
- create new Gradle modules;
- upgrade the toolchain/dependency stack;
- change Android/Wear minimum SDK;
- silently implement a later milestone.

---

# 40. Completion rule

A coding task is complete only when:

```text
requested behavior implemented
+ relevant tests pass
+ no known invariant violation
+ no required behavior hidden behind placeholder/TODO
+ docs updated if public/architectural behavior changed
+ all assumptions are either LOCAL_REVERSIBLE or explicitly approved
```

"Build passes" alone is not enough for a semantic task.

---

# Final rule

When uncertainty exists, the goal is not to maximize autonomous completion. The goal is to prevent an unapproved local guess from becoming a permanent architectural fact.

**High-impact ambiguity must remain visible until it is decided.**
