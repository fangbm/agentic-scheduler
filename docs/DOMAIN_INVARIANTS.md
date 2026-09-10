# Agentic Scheduler — Domain & Agent Invariants

> Status: **Frozen Baseline v1**  
> Scope: Domain, time semantics, Planner boundaries, Agent context/memory, history, Sync-facing semantics.

These invariants define what the system **means**, not merely how the current implementation happens to work. Database schemas, Planner algorithms, Agent tools, synchronization logic, UI layers, and external integrations must preserve them unless an ADR explicitly changes the baseline.

---

## 1. Client-generated immutable identity

Every synchronizable domain entity receives its ID on the client before synchronization.

- IDs never depend on server-side auto-increment values.
- IDs never change after creation.
- The same logical entity has the same ID on Android, Desktop, Wear OS, and Server replicas.
- UUIDv7 or another time-sortable globally unique identifier is the default ID family.
- Domain code should prefer strongly typed IDs such as `TaskId`, `EventId`, and `CourseId` over raw strings.

Example:

```kotlin
@JvmInline
value class TaskId(val value: String)
```

`TaskId` and `EventId` are not interchangeable simply because both are stored as strings.

---

## 2. Domain semantics and sync metadata are separate

Domain entities describe scheduling semantics. Synchronization metadata describes causality and replication.

Fields such as these belong to domain state:

```text
id
title
time
location
status
```

Fields such as these do not become business semantics:

```text
operationId
deviceId
DVV
HLC
syncState
```

`updatedAt` may exist for display/audit purposes, but it must not silently become a Last-Write-Wins conflict policy.

---

## 3. Time semantics are not a Unix timestamp

The system must distinguish at least:

```text
ZONED    — 2026-09-12 15:00 Asia/Singapore
ALL_DAY  — 2026-09-12
FLOATING — local 08:00 independent of a fixed zone
```

The domain layer must not reduce every temporal object to `start: Long` and `end: Long`.

A conceptual model may use:

```text
TimePlacement
├─ ZonedTimeRange
├─ AllDayRange
└─ FloatingTimeRange
```

All-day ranges use an exclusive end date. A one-day all-day item on September 10 is represented as:

```text
[2026-09-10, 2026-09-11)
```

not as `00:00 → 23:59:59`.

---

## 4. All internal time ranges are half-open

Every occupied or available interval uses:

```text
[start, end)
```

Therefore:

```text
10:00–11:00
11:00–12:00
```

are adjacent and do not conflict.

For non-all-day ranges:

```text
start < end
```

Zero-length scheduled items are invalid.

This convention applies to Event, FocusBlock, CourseSession, Exam, FreeWindow, Planner calculations, travel occupancy, and conflict detection.

---

## 5. Recurrence is based on wall-clock semantics

A rule such as:

> Every Monday at 09:00

means Monday at local wall-clock 09:00, not "previous Instant + 7 × 24 hours".

Recurrence therefore preserves the local recurrence semantics and resolves individual occurrences into concrete instants when needed. DST transitions must not silently shift a recurring local-time event by an hour.

---

## 6. Active entities must be semantically valid

The active calendar must not be filled with partially recognized pseudo-entities such as:

```text
Event(title = null, start = null, end = null)
```

Incomplete captured information belongs in Inbox / Draft Capture until enough information exists to form a valid domain entity.

Where incomplete scheduling is itself meaningful, it must be modeled as an explicit state rather than nullable-field ambiguity. Example:

```text
ExamSchedule
├─ Unscheduled
├─ DateOnly
└─ Exact
```

---

## 7. Event != Task

An Event represents something that occupies time.

Examples:

- meeting
- appointment
- activity
- travel

A Task represents work that needs to be completed and may have no assigned time at all.

Never model Task as an Event subclass. UI layers may expose common rendering abstractions, but domain semantics remain separate.

---

## 8. Task != FocusBlock

A Task answers:

> What work remains to be done?

A FocusBlock answers:

> Which interval has the Planner allocated to that work?

One Task may have zero, one, or many FocusBlocks.

```text
Task: Prepare presentation, 4h
├─ FocusBlock Tue 19:00–21:00
└─ FocusBlock Thu 15:00–17:00
```

Moving a FocusBlock must not mutate the Task's identity or meaning.

---

## 9. Task completion and remaining effort are independent

At minimum, Task can independently express:

```text
status
estimatedEffort
remainingEffort
```

with:

```text
remainingEffort >= 0
```

`remainingEffort == 0` does not automatically force `COMPLETED`, and `COMPLETED` does not require the previous estimate to have reached exactly zero.

Completion is a business/user state, not a mathematical derivation.

---

## 10. WorkLog records facts, FocusBlock records plans

```text
FocusBlock = planned work time
WorkLog    = actual work history
```

WorkLog is append-oriented history. Corrections should be represented by explicit correction/tombstone semantics rather than silently rewriting historical truth.

The Planner may use estimates, WorkLogs, and manual corrections to reason about remaining work.

---

## 11. Task dependencies form a DAG

Task dependency graphs must remain acyclic.

A dependency operation that introduces a cycle must be rejected.

The same invariant applies to hierarchical subtask parent relationships.

This is a cross-entity invariant and belongs in domain validation/service logic, not only in individual data classes.

---

## 12. Course != repeating Event

Course is an academic entity with its own semantics.

Conceptually:

```text
AcademicYear
  ↓
Semester
  ↓
Course
  ↓
CourseScheduleRule
  ↓
CourseSession
```

Odd/even weeks, selected teaching weeks, timetable periods, cancellations, room changes, and temporary reschedules are academic semantics rather than generic recurrence hacks.

A one-off course change is represented as an occurrence exception instead of mutating the whole base schedule rule.

---

## 13. CourseSession has stable occurrence identity

A generated class session must retain identity even if its actual time or room changes.

Occurrence identity must therefore not be derived solely from the current start time.

A conceptual stable key may contain:

```text
courseId
scheduleRuleId
occurrenceKey
```

This allows a rescheduled seventh-week class to remain the same logical occurrence.

---

## 14. Academic period numbers are metadata, not time itself

"Period 3" is not globally equivalent to a hard-coded time range.

Period templates are configurable. Conflict and Planner logic operate on resolved real time ranges.

```text
periodIndex  != resolved start/end
```

---

## 15. Exam is a first-class entity

Exam must not collapse into `Event(type = EXAM)`.

Exam may carry independent semantics such as:

```text
courseId
examType
studyTasks
revision planning
schedule state
```

Its schedule may explicitly be `UNSCHEDULED`, `DATE_ONLY`, or `EXACT`.

---

## 16. Reminder is a first-class multi-instance object

An entity may have multiple Reminders.

```text
Target
├─ Reminder -1 day
├─ Reminder -1 hour
└─ Reminder -10 min
```

Do not reduce this to a single nullable `reminderMinutes` field.

Relative Reminders must declare an anchor such as:

```text
EVENT_START
EVENT_END
TASK_DEADLINE
EXAM_START
```

If the anchor ceases to exist, the Reminder must become invalid/pending resolution rather than silently attaching to another timestamp.

---

## 17. Flexibility and PinState are different dimensions

Flexibility describes normal Planner authority:

```text
HARD
FLEXIBLE
SOFT
```

PinState expresses an explicit user decision:

```text
PINNED
UNPINNED
```

Therefore `SOFT + PINNED` is valid and means an ordinarily movable block that the Planner must not currently move.

`PINNED` takes precedence over ordinary automatic movement.

---

## 18. HARD does not mean immutable

`HARD` means the Planner may not automatically move the item.

The user can still edit it. An Agent with appropriate permission may propose a change, normally with confirmation/preview according to policy.

Do not implement HARD as database-level immutability.

---

## 19. Calendar conflicts are valid states

Real life permits conflicting commitments. The domain must be able to store:

```text
Meeting 15:00–16:00
Course  15:30–17:00
```

The system should detect and surface the conflict rather than refuse to persist reality.

The Planner, however, must not create new HARD-HARD conflicts without explicit user authorization.

---

## 20. Project itself does not occupy time

Project is a context/grouping container.

```text
Project
├─ Task
├─ Event
├─ Note
└─ File
```

A Project does not create hidden occupancy or Planner constraints merely by existing. Any project deadline or planning constraint must be explicit.

---

## 21. Inbox preserves capture provenance

Inbox is not a garbage Event table.

Captured information should preserve enough provenance to answer where it came from, for example:

```text
source
capturedAt
raw/extracted content
attachment reference
```

Converting an InboxItem into a Task/Event/Exam must not necessarily destroy the original capture provenance.

---

## 22. Planner constraints are structured

Free text such as "make tonight lighter" is not itself the final Planner representation.

The Agent may interpret it into a structured constraint with fields conceptually like:

```text
scope
constraintType
strength
value
effectiveFrom
effectiveUntil
source
```

Sources should distinguish at least:

```text
USER
PROFILE
AGENT_INTERPRETED
SYSTEM
```

This is necessary for truthful explanations.

---

## 23. Temporary constraints have explicit lifetimes

A statement such as:

> Don't schedule anything tonight

must not silently become a permanent preference.

Temporary constraints carry explicit effective start/end semantics and expire naturally.

---

## 24. PlanningProfile is a rule set, not calendar state

PlanningProfile may describe modes such as:

```text
Study
Work
Normal Week
Exam Week
Vacation
```

Switching profile does not itself rewrite the current calendar. It affects future planning/replanning unless the user explicitly asks for a replan.

---

## 25. PlanBranch != Active Calendar

A PlanBranch is a proposal, not current truth.

Before Apply, branch-only proposed changes must not behave like active calendar changes. They must not trigger ordinary reminders, external-calendar writes, or other active-state side effects.

V1 branches are created from Active State. Branch-of-branch semantics are out of scope unless introduced by ADR.

---

## 26. PlanBranch Apply is atomic

Applying a branch that contains multiple changes is one logical transaction.

```text
move A
delete B
create C
resize D
```

must either all become active or none become active.

A crash/failure halfway through must not leave the active schedule partially applied.

---

## 27. Stale PlanBranch cannot blindly overwrite newer state

A PlanBranch records the base state/revision it depended on.

If relevant active state changes, the branch transitions away from safely applicable DRAFT semantics, e.g.:

```text
DRAFT → STALE → REBASEABLE / CONFLICTED
```

A stale branch must be re-evaluated/rebased, resolved, or discarded before Apply.

---

## 28. Deletion is a semantic operation, not immediate physical disappearance

A synchronized delete produces explicit deletion history/tombstone semantics.

Physical compaction may occur only after safe convergence rules permit it.

Database cascade deletion must never substitute for explicit synchronized domain semantics. Related entity effects must be intentional domain operations.

---

## 29. ContextAnchor is device/session-local

ContextAnchor represents ephemeral UI/session context such as:

```text
selectedEntity
openInspectorEntity
currentCalendarViewport
notificationSourceEntity
currentlyRunningEvent
```

ContextAnchor is **not synchronized**.

A phone's currently selected event must not become the desktop Agent's meaning of "this meeting".

---

## 30. shared:domain depends on no AI, database, network, or UI framework

The dependency direction remains toward Domain:

```text
Database ──────┐
Planner ───────┤
Sync ──────────┤
Agent ─────────┼──> Domain
UI ────────────┘
```

`shared:domain` must not depend on Room, SQLDelight, Ktor, OpenAI/Anthropic SDKs, Android Context, Compose, or Wear APIs.

Pure logic/time libraries may be used where justified.

---

# Agent context and memory invariants

## 31. Provider session != Agent memory

The source of truth for conversational continuity belongs to Agentic Scheduler, not an LLM provider.

OpenAI/Anthropic/Gemini/local-provider session IDs may be used as optional optimization/cache handles, but replacing the Provider must not reset Agent memory.

**Changing LLM Provider must not reset Agent memory.**

The application owns at least the conceptual entities:

```text
AgentThread
AgentMessage
ContextSummary
```

---

## 32. Every Agent request receives continuity context

Normal Universal Command requests are not isolated stateless prompts.

Before every LLM invocation, a ContextAssembler constructs the minimum valid context from application-owned state.

A request such as:

> Don't schedule this on Wednesday evening.

must have access to enough prior thread/UI/domain context to resolve "this" safely or request clarification rather than guessing.

---

## 33. AgentThread and ContextAnchor are separate

`AgentThread` contains durable conversational continuity and may be E2EE-synchronized.

`ContextAnchor` contains transient device/session UI state and is not synchronized.

```text
AgentThread   → syncable under E2EE
ContextAnchor → local-only
```

---

## 34. ChangeLog is a first-class Agent-readable source

The Agent must be able to inspect not only current state, but how it became current.

The Tool Layer must expose read-only history capabilities equivalent to:

```text
history.search
history.getAction
history.getEntityChanges
history.getDiff
history.timeline
```

Exact names may evolve, but the capability is required.

---

## 35. The Agent can inspect its own auditable actions

`AgentAction` is not only UI audit data. It is authoritative historical input the Agent may retrieve later.

A future question such as:

> Why did you move my revision block yesterday?

must be answerable by retrieving the structured historical action and its Planner DecisionReason rather than guessing from current state.

---

## 36. Conversation history != ChangeLog

Conversation records what humans and the Agent said.

ChangeLog records what the system actually changed.

Example:

```text
Conversation:
User: Make this week lighter.
Agent: I adjusted the plan.
```

is distinct from:

```text
AgentAction #104
- FocusBlock A Tue 19:00 → Wed 18:00
- FocusBlock B Tue 21:00 → Thu 16:00
- TemporaryConstraint C created
Planner: LocalReflow
DecisionReason: ...
```

For factual claims about application state, structured Domain/ChangeLog facts outrank conversational prose.

---

## 37. Context-window exhaustion must not cause application amnesia

The system must never rely on sending the complete lifetime conversation to the model.

Context is divided conceptually into:

### Hot context

Recent raw user/assistant messages and recent Tool Calls/Results.

### Warm context

Application-managed summaries of older thread segments.

### Cold context

Authoritative full conversation, AgentAction history, ChangeLog, and domain history retained outside the model context window and retrievable through tools/search.

A finite model context window does not imply finite Scheduler memory.

---

## 38. ContextSummary is not an authoritative fact store

Summaries are lossy context-compaction artifacts.

If a summary conflicts with current Domain State or structured ChangeLog, the structured sources win.

Fact-confidence precedence is frozen as:

```text
Current Domain State
        >
Structured ChangeLog / ToolResult
        >
Structured Agent State
        >
Raw Conversation
        >
Generated ContextSummary
```

This precedence concerns factual authority, not prompt ordering.

---

## 39. ContextSummary records its source coverage

A summary must identify what it summarized.

Conceptually:

```text
ContextSummary
- threadId
- coversFromMessageId
- coversToMessageId
- createdAt
- model/version
- sourceHash
- summaryText
```

Incremental compaction can then combine the current summary with recent raw messages instead of repeatedly summarizing the entire thread.

---

## 40. History retrieval supports structured and semantic search

History lookup has two different modes.

Structured/exact retrieval handles questions such as:

> Who changed this Event last Wednesday?

using filters such as:

```text
entityId
actionType
actor
timeRange
deviceId
```

Semantic retrieval handles questions such as:

> Didn't I previously say I wanted exam weeks to be lighter?

Neither mode replaces the other.

---

## 41. ToolResult is more authoritative than the Agent's narration

An Agent message saying "I moved the meeting" does not prove the write succeeded.

The authoritative execution fact is the ToolResult / transaction result.

Both Tool Call and Tool Result must be retained in the thread/audit model where applicable.

---

## 42. AgentThread continuity may synchronize under E2EE

To preserve cross-device continuity, AgentThread, AgentMessage, and ContextSummary may be E2EE-synchronized as application data.

A discussion started on Android may therefore continue on Desktop without relying on provider-side conversation storage.

ContextAnchor remains local-only.

---

## 43. History tools are read-only

The Agent may inspect history through read-only capabilities.

It must not be able to rewrite historical truth through an API equivalent to `history.rewrite()`.

Corrections, Undo, and compensating changes must be expressed as new explicit operations/actions.

---

## 44. Undo preserves historical truth

Undo changes current state but does not erase the fact that the original action occurred.

```text
#201 Move Event A
#202 Undo #201
```

is correct history.

Deleting `#201` from the audit log is not.

---

## 45. ContextAssembler is deterministic application infrastructure

The model does not get unrestricted access to the entire database and independently decide what to inspect before every turn.

The application first supplies a deterministic minimum context based on the current command and local state, such as:

```text
current AgentThread
current ContextSummary
recent messages/tool results
ContextAnchor
relevant selected/current entities
minimum domain snapshot
```

If more information is required, the model may request it through typed read-only Domain/History/Search tools.

The model therefore receives:

```text
Automatically supplied minimum context
+
LLM-requested additional context through Tools
```

---

# Cross-module architecture invariants

## 46. LLM never writes storage directly

The only legal mutation path is:

```text
LLM proposal
→ typed Tool Call
→ schema validation
→ deterministic domain/planner validation
→ permission evaluation
→ preview/PlanBranch when required
→ transactional Tool execution
→ AgentAction / ChangeLog
→ SyncOperation
```

There is no supported bypass from Provider SDK/LLM output directly to Room/SQLite/Repository/Server storage.

---

## 47. Planner reasoning is authoritative for scheduling decisions

The LLM may interpret intent and verbalize reasons, but it must not fabricate scheduling rationale.

The Planner emits structured explanation data such as:

```text
ConstraintMatch
ScoreContribution / ScoreDelta
DecisionReason
```

Natural-language explanations are derived from those facts.

---

## 48. History and current state have separate responsibilities

Current Domain State answers:

> What is true now?

ChangeLog / AgentAction answers:

> What happened, by whom, and why?

Neither should be overloaded to replace the other.

---

## 49. Sync protocol converges operations, not provider conversations

Provider-side chat/session state is never required for cross-device correctness.

Synchronized application state consists of application-owned encrypted domain/history/thread data and operations.

---

## 50. No platform may weaken the invariant set

Android, Desktop, and Wear may have different UI and capability surfaces, but they must not implement incompatible domain meanings.

Wear may expose fewer operations, but an Event, Task, FocusBlock, AgentAction, and SyncOperation retain the same semantic rules.

---

# Core invariants contributors must remember

If only a short list is remembered, preserve these twelve:

```text
1.  IDs are client-generated and immutable.
2.  Time semantics are richer than Unix timestamps.
3.  Task != Event.
4.  Task != FocusBlock.
5.  Course != repeating Event.
6.  AI never mutates Domain/storage directly.
7.  PlanBranch != Active Calendar.
8.  Sync metadata != Domain semantics.
9.  Provider session != Agent memory.
10. Agent conversations preserve continuity.
11. Agent can inspect authoritative ChangeLog/history.
12. Context compaction never destroys authoritative history.
```

---

# Required architecture consequence

The Agent context pipeline is therefore conceptually fixed as:

```text
User Command
    ↓
ContextAssembler
    ├─ Current AgentThread
    ├─ ContextSummary
    ├─ Recent Messages / Tool Results
    ├─ local ContextAnchor
    ├─ relevant Domain Snapshot
    └─ relevant Recent Actions
            ↓
           LLM
            ↓
      needs more facts?
       ├───────────────┐
       ↓               ↓
  Domain Tools     History Tools
       └───────┬───────┘
               ↓
        Structured Plan
               ↓
  Deterministic Validation
               ↓
      Permission Engine
               ↓
  Preview / PlanBranch if required
               ↓
       Tool Transaction
               ↓
 AgentAction + ChangeLog + ToolResult
               ↓
         SyncOperation
```

The model context window may be finite. **The Scheduler's memory is not.**
