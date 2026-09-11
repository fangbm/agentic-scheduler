# Agentic Scheduler — Ubiquitous Language

> Status: **Frozen Vocabulary v1**  
> Purpose: keep domain, UI, Planner, Sync, Agent, tests, docs, and coding agents speaking the same language.

Canonical names are architectural vocabulary. Synonyms may appear in user-facing localized copy when helpful, but code, schemas, ADRs, task specs, and technical documentation should use the canonical term unless an explicit rename decision is made.

---

# Canonical domain vocabulary

| Canonical term | Meaning | Do not rename to / confuse with |
|---|---|---|
| `Event` | A scheduled item that occupies time | appointment object, calendar item, generic item |
| `Task` | Work that must be completed and may be unscheduled | todo event, task event |
| `FocusBlock` | Planned time allocated to a Task | TaskSlot, ScheduledTask, WorkEvent |
| `WorkLog` | Actual work/execution history | FocusBlock, WorkBlock |
| `Course` | Academic course identity/context | repeating Event, ClassEvent |
| `CourseScheduleRule` | Base academic scheduling rule for a Course | recurrence event |
| `CourseSession` | Deterministically resolved logical occurrence of a Course | ClassEvent, Event occurrence, independent stored event |
| `CourseOccurrenceKey` | Stable identity of one logical Course occurrence: rule + academic week | CourseSessionId, start-time-derived ID |
| `CourseOccurrenceException` | Explicit one-off cancellation/reinstatement/time/room change targeting a CourseOccurrenceKey | editing the base rule, recurrence exception blob |
| `CourseOccurrenceDisposition` | Explicit ACTIVE/CANCELLED intent of a CourseOccurrenceException | CourseSession state inferred from nullable time |
| `RoomOverride` | Tri-state Unchanged/Set/Clear one-off room override | nullable room override |
| `AcademicYear` | Academic-year container | school year string |
| `Semester` | Academic term/semester with explicit timezone and AcademicWeek definitions | calendar category |
| `AcademicWeekNumber` | Positive owner-scoped academic week number | week offset from Semester start |
| `AcademicWeek` | Explicit seven-calendar-day numbered range inside a Semester | inferred `semesterStart + n weeks` |
| `TeachingWeekSet` | Canonical explicit sorted set of AcademicWeekNumbers used by a CourseScheduleRule | ODD/EVEN storage enum, recurrence string |
| `CourseTimeSpec` | Academic wall-clock source time: ClockTime or PeriodBased | ZonedTimeRange stored directly on base rule |
| `AcademicPeriodNumber` | Positive period number within a PeriodTemplate | globally fixed clock time |
| `AcademicPeriod` | Explicit local-time mapping for one academic period number | Event, duration formula |
| `AcademicHoliday` | Semester-scoped academic-calendar date range with explicit teaching effect | generic Event, name-inferred cancellation |
| `AcademicHolidayTeachingEffect` | Structured NO_EFFECT/SUSPEND_TEACHING behavior | inferred holiday behavior |
| `PeriodTemplate` | Mapping from academic period numbers to clock time | period Event |
| `Exam` | First-class academic assessment entity | `Event(type=EXAM)` |
| `ExamSchedule` | Explicit Unscheduled/DateOnly/Exact exam schedule state | nullable start/end |
| `Reminder` | Independent trigger attached to a target/anchor | reminderMinutes field |
| `Project` | Long-running context/grouping entity | calendar, task list |
| `InboxItem` | Captured/unprocessed information with provenance | draft Event |
| `Location` | Structured place/context used by domain/planning | plain display string only |
| `PlanningProfile` | Reusable Planner rule/preference set | calendar mode |
| `Constraint` | Structured Planner restriction/preference | prompt text |
| `TemporaryConstraint` | Constraint with explicit finite lifetime | permanent Preference |
| `Preference` | Soft user planning preference | HARD constraint |
| `Flexibility` | Planner movement authority: HARD/FLEXIBLE/SOFT | priority, pin state |
| `PinState` | Explicit protection against automatic movement | HARD |
| `FreezeHorizon` | Near-term region protected from automatic replan | PinState |
| `DeadlinePolicy` | Semantics of a deadline | priority |
| `OverflowPolicy` | Whether scheduling may exceed configured availability | deadline policy |

---

# Academic vocabulary rules

The Academic Domain uses these distinctions exactly:

```text
AcademicYear
    ↓
Semester
    ↓
Course
    ↓
CourseScheduleRule
    ↓
CourseOccurrenceKey
    ↓
CourseSession
```

`CourseSession` is a derived projection. Do not introduce `CourseSessionId` in D3/D5.

Teaching-week input forms such as:

```text
odd weeks
even weeks
1-16
1,3,5,7
```

may exist at import/UI boundaries later, but the canonical Domain noun is always `TeachingWeekSet`.

Period-based scheduling uses:

```text
PeriodTemplate
└─ AcademicPeriod
```

not a generic recurrence/time formula.

A one-off class change uses `CourseOccurrenceException`; never describe a one-off reschedule in technical contracts as "editing the recurring event".

---

# Calendar/application vocabulary

| Canonical term | Meaning | Do not confuse with |
|---|---|---|
| `CalendarViewport` | Explicit date range + display timezone used to query/project a visible calendar window | system timezone, Planner horizon |
| `CalendarItem` | Application-level read projection of an authoritative source fact for rendering | Domain entity, persisted row |
| `CalendarSourceRef` | Stable reference from a CalendarItem back to Event/FocusBlock/CourseOccurrenceKey/Exam identity | title/start-time identity |
| `CalendarConflict` | Deterministically detected overlap between two concrete occupied Instant ranges | invalid database state, Planner decision |
| `CalendarProjectionIssue` | Structured problem encountered while deriving/projecting calendar state | swallowed error, UI-only warning string |
| `Agenda/Day` | Viewport-bounded first calendar renderer | Week grid, Planner horizon |

Calendar projection is a read/application concern:

```text
Authoritative Domain/application state
→ Calendar projection
→ platform rendering
```

`CalendarItem` never becomes a generic replacement Domain entity.

`CalendarConflict` reports current overlap truth. It does not imply that persistence should reject the state or that Planner may move either item.

---

# Planner vocabulary

| Canonical term | Meaning | Do not confuse with |
|---|---|---|
| `Planner` | Deterministic scheduling engine | LLM Agent |
| `Validator` | Deterministic feasibility/conflict validator | LLM reasoning |
| `Local Reflow` | Minimal-disruption local schedule repair | Full Replan |
| `Full Replan` | Wider-horizon schedule optimization | Local Reflow |
| `PlanBranch` | Proposed schedule changes isolated from Active State | DraftSchedule, temporary calendar |
| `Active State` | Current authoritative local domain schedule state | PlanBranch |
| `ConstraintMatch` | Structured evidence of a matched constraint | natural-language reason |
| `DecisionReason` | Planner-produced structured reason | LLM-invented explanation |
| `ScoreDelta` / `ScoreContribution` | Structured scoring contribution | model confidence |
| `PlanningHorizon` | Time range considered by planning | FreezeHorizon, CalendarViewport |
| `InfeasibleSchedule` | No valid solution under current constraints/policies | Planner crash |

`Local Reflow` and `Full Replan` must always be written as separate operations in code/docs.

---

# Agent vocabulary

| Canonical term | Meaning | Do not rename/confuse with |
|---|---|---|
| `Agent` | Application orchestration layer using an LLM plus Tools | Planner, chatbot |
| `Universal Command` | Context-aware primary command surface | AI Center, Chat page |
| `AgentThread` | Durable application-owned conversation continuity | Provider session |
| `AgentMessage` | Stored user/assistant/tool message record | ChangeLog entry |
| `ContextSummary` | Lossy compacted representation of older thread context | memory source of truth |
| `ContextAnchor` | Device/session-local UI referent state | AgentThread memory |
| `ContextAssembler` | Deterministic application component assembling model context | Provider prompt template |
| `AgentAction` | Structured record of an Agent-initiated logical action | chat reply |
| `Tool Call` | Typed request to an application Tool | arbitrary JSON command |
| `ToolResult` | Authoritative structured execution/read result | Agent narration |
| `AgentPolicy` | Background/proactive Agent policy definition | PlanningProfile |
| `Permission Engine` | Deterministic authority evaluation for Tools | LLM self-approval |
| `Provider Adapter` | Adapter for OpenAI/Anthropic/Gemini/local/etc. | business logic layer |

Never use `AI memory` in a technical contract when the specific concept is `AgentThread`, `ContextSummary`, `ChangeLog`, or retrieved Domain State.

---

# History and audit vocabulary

| Canonical term | Meaning | Do not confuse with |
|---|---|---|
| `ChangeLog` | Authoritative structured record of state changes | Conversation history |
| `AgentAction` | Logical Agent action and its linked operations | SyncOperation |
| `Undo` | New compensating/reversing action that restores state | deleting history |
| `History Tool` | Read-only Tool for querying historical facts | mutation API |
| `Diff` | Structured before/after difference | current state |

Canonical History capabilities may use names such as:

```text
history.search
history.getAction
history.getEntityChanges
history.getDiff
history.timeline
```

Exact Tool identifiers may be versioned later, but `History` means authoritative structured history, not provider chat logs.

---

# Sync vocabulary

| Canonical term | Meaning | Do not rename/confuse with |
|---|---|---|
| `SyncOperation` | Immutable application sync operation | Git commit literally |
| `Operation Log` | Ordered/replayable collection of SyncOperations | database transaction log |
| `DVV` / `Dotted Version Vector` | Causal/concurrency metadata | timestamp ordering |
| `HLC` / `Hybrid Logical Clock` | Stable logical ordering/debug metadata | Last Write Wins authority |
| `Semantic Merge` | Domain-aware merge of concurrent changes | JSON merge |
| `SyncConflict` | Explicit unresolved semantic concurrency conflict | exception/crash |
| `Tombstone` | Replicated deletion marker/history | hard delete |
| `ServerTransport` | Remote sync transport | source of truth |
| `WearTransport` | Nearby Phone↔Wear operation transport | cloud sync |

Git terms such as commit/branch/rebase/merge are analogies unless explicitly discussing Git debug/export tooling. Production runtime objects retain the canonical Scheduler names above.

---

# Wear vocabulary

| Canonical term | Meaning |
|---|---|
| `WearCapabilityService` | Determines stable Watch capability facts |
| `aiEntrySupported` | Device/language capability fact |
| `userEnabledAiEntry` | User preference |
| `effectiveAiEntryEnabled` | Capability + preference result |
| `providerReady` | Valid provider binding and credential availability |
| `requestReady` | AI entry enabled + provider ready + network reachable |
| `WearProviderBinding` | Non-secret Provider/Model binding metadata |
| `WearProviderRuntimeState` | Runtime provider/network availability state |
| `ProviderCredentialEnvelope` | Device-targeted encrypted provider secret provisioning envelope |
| `PhoneContextBridge` | Minimal context supplement from phone to Watch |

Do not call the phone app an `AI proxy` in the default architecture. Watch-originated LLM calls remain Watch-originated even when the OS routes network traffic through the paired phone.

---

# Risk/permission vocabulary

Canonical Tool permission states:

```text
ALWAYS_ALLOW
ALLOW_WITH_PREVIEW
ASK
DENY
```

Canonical risk levels:

```text
LOW
MEDIUM
HIGH
CRITICAL
```

Canonical user-facing autonomy concepts:

```text
Conservative
Assisted
Agentic
```

Do not infer Tool permission directly from the user-facing autonomy label; it resolves through deterministic policy.

---

# Reserved distinctions

The following equations are permanently false unless a future ADR explicitly changes the domain:

```text
Task == Event                         FALSE
Task == FocusBlock                    FALSE
Course == repeating Event             FALSE
CourseSession == Event occurrence     FALSE
CourseSession == authoritative rule   FALSE
Exam == Event(type=EXAM)              FALSE
AcademicHoliday == Event              FALSE
AcademicWeek == fixed week offset     FALSE
Period number == global clock time    FALSE
CalendarItem == Domain entity         FALSE
CalendarConflict == invalid state     FALSE
CalendarViewport == PlanningHorizon   FALSE
PlanBranch == Active State            FALSE
Agent == Planner                      FALSE
AgentThread == Provider conversation  FALSE
Conversation == ChangeLog             FALSE
ToolResult == Agent narration         FALSE
HLC timestamp == conflict authority   FALSE
ContextSummary == authoritative truth FALSE
```

---

# Naming rule for new concepts

Before introducing a new shared architectural/domain noun, check whether an existing canonical term already expresses it.

If a proposed new term is merely a synonym, reuse the canonical term.

If it introduces genuinely new semantics that cross module boundaries, update this document as part of the decision/PR.

A coding agent must not create a competing vocabulary simply because a locally preferred name sounds clearer.
