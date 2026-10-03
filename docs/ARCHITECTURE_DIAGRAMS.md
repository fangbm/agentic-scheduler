# Agentic Scheduler — Deterministic Architecture Diagrams

> Status: **Architecture Baseline**  
> Companion document: [`DOMAIN_INVARIANTS.md`](./DOMAIN_INVARIANTS.md)

This document defines the intended runtime boundaries and allowed data flows for Agentic Scheduler.

The diagrams are deliberately strict. They are not merely illustrative UI sketches: arrows represent allowed architectural dependencies or runtime flows. A missing shortcut is usually intentional.

---

# 1. System-wide architecture

```mermaid
flowchart TB
    U[User]

    subgraph Clients[Local-first Clients]
        A[Android App]
        D[Windows / Linux Desktop]
        W[Wear OS App]
    end

    subgraph Shared[Shared Application Core]
        DOMAIN[Domain Model]
        CAL[Calendar / Academic Logic]
        TASK[Task / Project Logic]
        PLANNER[Deterministic Planner]
        AGENT[Agent Runtime]
        CONTEXT[Context Assembler]
        HISTORY[History / ChangeLog]
        SYNC[Sync Engine]
        CRYPTO[E2EE / Key Layer]
        DB[Local Database]
    end

    subgraph ExternalAI[External AI Providers]
        OAI[OpenAI / Compatible]
        ANT[Anthropic]
        GEM[Gemini]
        LOCAL[Local / Self-hosted Model]
    end

    subgraph Server[Thin Sync Server]
        AUTH[Auth / Device Management]
        SYNCAPI[Encrypted Sync API]
        PUSH[Push / Wake Signals]
        BLOB[Encrypted Blob Store]
        EXT[External Calendar Adapters]
    end

    U --> A
    U --> D
    U --> W

    A --> AGENT
    D --> AGENT
    W --> AGENT

    A --> DOMAIN
    D --> DOMAIN
    W --> DOMAIN

    AGENT --> CONTEXT
    CONTEXT --> DOMAIN
    CONTEXT --> HISTORY
    CONTEXT --> AGENT

    AGENT --> OAI
    AGENT --> ANT
    AGENT --> GEM
    AGENT --> LOCAL

    AGENT --> PLANNER
    AGENT --> DOMAIN
    PLANNER --> DOMAIN
    CAL --> DOMAIN
    TASK --> DOMAIN

    DOMAIN --> DB
    HISTORY --> DB
    PLANNER --> DB

    DB --> SYNC
    HISTORY --> SYNC
    SYNC --> CRYPTO
    CRYPTO --> SYNCAPI

    A <--> SYNC
    D <--> SYNC
    W <--> SYNC

    A <-. nearby Data Layer .-> W

    SYNCAPI --> AUTH
    SYNCAPI --> PUSH
    SYNCAPI --> BLOB
    SYNCAPI --> EXT
```

## Meaning

The client is the primary execution environment.

- Domain semantics live on clients.
- Planner execution lives on clients.
- Agent execution lives primarily on clients.
- E2EE occurs before synchronized user content leaves the trusted client boundary.
- The server transports/stores encrypted application data and coordinates devices.
- AI Providers never become the source of truth for either schedule state or Agent memory.

---

# 2. Dependency direction

Compile-time/module dependencies should remain approximately directional:

```mermaid
flowchart LR
    UI[Platform UI]
    AG[Agent]
    PL[Planner]
    SE[Search]
    SY[Sync]
    DA[Database]
    DO[Domain]

    UI --> DO
    UI --> AG
    UI --> PL
    UI --> DA

    AG --> DO
    AG --> PL
    AG --> SE

    PL --> DO
    SE --> DO
    SY --> DO
    DA --> DO
```

## Forbidden dependency inversions

```text
Domain -> Room / SQLDelight             FORBIDDEN
Domain -> Ktor                          FORBIDDEN
Domain -> Compose                       FORBIDDEN
Domain -> Android Context               FORBIDDEN
Domain -> OpenAI / Anthropic SDK        FORBIDDEN
Planner -> UI                           FORBIDDEN
Sync -> Platform UI                     FORBIDDEN
Provider adapter -> business rules      FORBIDDEN
```

Platform-specific adapters may implement interfaces owned by higher-level application modules, but Domain semantics must not depend on those implementations.

---

# 3. Universal Command / Agent runtime path

This is the normal path for every conversational Agent command.

```mermaid
flowchart TD
    INPUT[User Command]
    ANCHOR[Local ContextAnchor]
    THREAD[AgentThread]
    SUMMARY[ContextSummary]
    RECENT[Recent Messages + ToolResults]
    SNAPSHOT[Relevant Domain Snapshot]
    RECENTACTIONS[Relevant Recent AgentActions]

    CA[Deterministic Context Assembler]
    LLM[Selected LLM Provider]
    NEED{Need more facts?}
    READTOOLS[Typed Read-only Tools]
    TOOLRESULTS[Structured ToolResults]
    PLAN[Structured Agent Plan]
    VALIDATE[Schema + Domain Validation]
    PLANVALIDATE[Planner / Feasibility Validation]
    PERMISSION[Permission Engine]
    RISK{Preview required?}
    BRANCH[PlanBranch / Preview]
    EXEC[Atomic Tool Transaction]
    ACTION[AgentAction]
    CHANGE[ChangeLog]
    SYNCOP[SyncOperation]
    RESPONSE[User-facing Explanation]

    INPUT --> CA
    ANCHOR --> CA
    THREAD --> CA
    SUMMARY --> CA
    RECENT --> CA
    SNAPSHOT --> CA
    RECENTACTIONS --> CA

    CA --> LLM
    LLM --> NEED
    NEED -- yes --> READTOOLS
    READTOOLS --> TOOLRESULTS
    TOOLRESULTS --> LLM
    NEED -- no --> PLAN
    LLM --> PLAN

    PLAN --> VALIDATE
    VALIDATE --> PLANVALIDATE
    PLANVALIDATE --> PERMISSION
    PERMISSION --> RISK

    RISK -- yes --> BRANCH
    BRANCH -->|Apply| EXEC
    BRANCH -->|Discard| RESPONSE

    RISK -- no --> EXEC

    EXEC --> ACTION
    EXEC --> CHANGE
    EXEC --> SYNCOP
    ACTION --> RESPONSE
    CHANGE --> RESPONSE
```

## Hard invariant

There is **no legal edge**:

```text
LLM -> Database
LLM -> Repository.write
LLM -> Server mutation
```

The only write path is:

```text
LLM proposal
→ typed Tool
→ validation
→ Planner/Domain rules
→ permission
→ optional PlanBranch
→ atomic transaction
→ audit/history
→ sync operation
```

---

# 4. Context and long-term Agent memory

The LLM context window is temporary. Scheduler-owned memory is persistent.

```mermaid
flowchart TB
    subgraph Hot[Hot Context]
        HM[Recent AgentMessages]
        HT[Recent Tool Calls / ToolResults]
        HA[Current ContextAnchor]
    end

    subgraph Warm[Warm Context]
        CS[ContextSummary]
        RS[Recent structured Agent state]
    end

    subgraph Cold[Cold / Authoritative History]
        AT[Full AgentThread]
        AA[AgentAction Log]
        CL[ChangeLog]
        DH[Domain History / Sync Operations]
    end

    QUERY[New User Command]
    ASM[Context Assembler]
    LLM[LLM]
    HR[History Retrieval]
    SR[Semantic Retrieval]
    XR[Structured / Exact Retrieval]

    HM --> ASM
    HT --> ASM
    HA --> ASM
    CS --> ASM
    RS --> ASM
    QUERY --> ASM
    ASM --> LLM

    LLM --> HR
    HR --> SR
    HR --> XR

    SR --> AT
    SR --> AA
    SR --> CL

    XR --> AT
    XR --> AA
    XR --> CL
    XR --> DH

    SR --> LLM
    XR --> LLM
```

## Consequence

A conversation can exceed any individual model context window without becoming stateless.

Older material is compacted into `ContextSummary`, while authoritative historical records remain retrievable.

Changing Provider:

```text
OpenAI -> Claude -> Gemini -> Local Model
```

must not reset the Scheduler's Agent memory.

---

# 5. Factual authority hierarchy

When sources disagree, the following precedence is used for factual claims about the application:

```mermaid
flowchart TB
    DS[1. Current Domain State]
    CH[2. Structured ChangeLog / ToolResult]
    AS[3. Structured Agent State]
    RC[4. Raw Conversation]
    SU[5. Generated ContextSummary]

    DS --> CH --> AS --> RC --> SU
```

This is a **truth-authority ordering**, not necessarily the order in which prompt text is serialized.

Examples:

- If the Agent said "I moved the meeting" but ToolResult says the transaction failed, the meeting was **not moved**.
- If ContextSummary says an Event was cancelled but current Domain State says it exists and ChangeLog shows only a reschedule, the summary is wrong.

---

# 6. History / ChangeLog architecture

```mermaid
flowchart LR
    UC[User Command]
    AP[Agent Plan]
    TX[Tool Transaction]
    TR[ToolResult]
    AA[AgentAction]
    CL[ChangeLog Entries]
    SO[SyncOperations]
    DB[(Local DB)]

    UC --> AP
    AP --> TX
    TX --> TR
    TX --> AA
    TX --> CL
    TX --> SO

    TR --> DB
    AA --> DB
    CL --> DB
    SO --> DB

    HS[history.search] --> DB
    HA[history.getAction] --> DB
    HE[history.getEntityChanges] --> DB
    HD[history.getDiff] --> DB
    HT[history.timeline] --> DB
```

History APIs are read-only from the Agent's perspective.

Undo is modeled as a new action:

```text
#201 move Event A
#202 undo #201
```

not as deletion of `#201`.

---

# 7. Deterministic Planner architecture

```mermaid
flowchart TD
    REQUEST[Planning Request]
    STATE[Domain Snapshot]
    PROFILE[PlanningProfile]
    CONSTRAINTS[Constraints]
    TASKS[Tasks / Remaining Effort]
    OCC[Fixed Occupancy]
    TRAVEL[Location / Travel]
    FREEZE[PinState / Freeze Horizon]

    VALID[Validator]
    MODE{Planning mode}
    LOCAL[Local Reflow]
    FULL[Full Scheduler / Optimizer]
    FEAS[Feasibility Result]
    EXPLAIN[ConstraintMatch + ScoreDelta + DecisionReason]
    BRANCH[PlanBranch]

    REQUEST --> VALID
    STATE --> VALID
    PROFILE --> VALID
    CONSTRAINTS --> VALID
    TASKS --> VALID
    OCC --> VALID
    TRAVEL --> VALID
    FREEZE --> VALID

    VALID --> MODE
    MODE -- Local disruption --> LOCAL
    MODE -- Wider horizon --> FULL

    LOCAL --> FEAS
    FULL --> FEAS

    LOCAL --> EXPLAIN
    FULL --> EXPLAIN

    FEAS --> BRANCH
    EXPLAIN --> BRANCH
```

The LLM may produce the human-readable explanation, but only from the structured explanation facts emitted by the Planner.

---

# 8. PlanBranch lifecycle

```mermaid
stateDiagram-v2
    [*] --> DRAFT
    DRAFT --> APPLIED: valid base + apply
    DRAFT --> DISCARDED: user discards
    DRAFT --> STALE: relevant base state changed

    STALE --> REBASEABLE: deterministic re-evaluation possible
    STALE --> CONFLICTED: semantic conflict found
    STALE --> DISCARDED

    REBASEABLE --> DRAFT: successfully rebased
    REBASEABLE --> CONFLICTED: rebase finds unresolved conflict
    REBASEABLE --> DISCARDED

    CONFLICTED --> DRAFT: conflicts resolved / regenerated
    CONFLICTED --> DISCARDED

    APPLIED --> [*]
    DISCARDED --> [*]
```

Apply is atomic. A PlanBranch never becomes partially active.

PlanBranch-only data has no ordinary active-calendar side effects before Apply.

---

# 9. Local-first mutation and synchronization path

```mermaid
flowchart LR
    UI[User / Agent Tool]
    TX[Local Atomic Transaction]
    DS[(Domain State)]
    OP[SyncOperation]
    LOG[(Operation Log)]
    MERGE[Semantic Merge Engine]
    CONFLICT[SyncConflict]
    ENC[E2EE Encrypt]
    SERVER[Server Sync API]
    OTHER[Other Device]

    UI --> TX
    TX --> DS
    TX --> OP
    OP --> LOG

    LOG --> ENC
    ENC --> SERVER
    SERVER --> OTHER

    OTHER --> MERGE
    DS --> MERGE
    MERGE -->|compatible| DS
    MERGE -->|semantic collision| CONFLICT
```

The server is a transport/storage participant; it does not decide plaintext domain merge semantics.

---

# 10. Sync causality model

```mermaid
flowchart TB
    OP1[Operation A\nDevice Phone]
    OP2[Operation B\nDevice Desktop]
    DVV[DVV / Causal Context]
    HLC[HLC Stable Ordering]
    CMP{Relationship}
    SEQ[Sequential change]
    CON[Concurrent change]
    SEM[Semantic Merge]
    OK[Auto-merge]
    CF[Explicit SyncConflict]

    OP1 --> DVV
    OP2 --> DVV
    OP1 --> HLC
    OP2 --> HLC

    DVV --> CMP
    HLC --> CMP

    CMP -->|causal ancestor| SEQ
    CMP -->|neither dominates| CON

    CON --> SEM
    SEM -->|different compatible fields| OK
    SEM -->|same semantic field collision| CF
```

HLC does **not** authorize Last-Write-Wins for semantic conflicts. It provides stable ordering metadata where ordering is needed.

---

# 11. E2EE data boundary

```mermaid
flowchart LR
    subgraph TrustedClient[Trusted Client Boundary]
        PLAIN[Plain Domain / Thread / History Data]
        KEYS[Account / Workspace / Device Keys]
        ENC[Encrypt]
        DEC[Decrypt]
        CTX[AI Context Filter]
    end

    subgraph UntrustedInfra[Infrastructure not trusted with plaintext]
        API[Sync Server]
        PG[(PostgreSQL)]
        OBJ[(S3 / MinIO)]
    end

    subgraph Provider[Optional AI Provider]
        LLM[LLM API]
    end

    PLAIN --> ENC
    KEYS --> ENC
    ENC --> API
    API --> PG
    API --> OBJ

    PG --> API
    OBJ --> API
    API --> DEC
    KEYS --> DEC
    DEC --> PLAIN

    PLAIN --> CTX
    CTX -->|only allowed minimum context| LLM
```

The AI Provider privacy boundary is separate from the Sync Server privacy boundary. Sending allowed context to an explicitly configured AI Provider does not imply the Sync Server receives plaintext.

---

# 12. Wear OS runtime architecture

```mermaid
flowchart TD
    TEXT[Available Watch text input]
    VOICE[Explicit Watch speech input]
    STT{Optional on-device STT + user permission available?}
    SPEECH[Transcript]
    UNAVAILABLE[Speech unavailable; text remains available]
    WAG[Watch Agent Runtime]
    READY{AI entry enabled + Provider ready + network reachable?}
    WAIT[Structured Provider / network unavailable state]
    LLM[Configured LLM Provider]
    TOOL[Existing typed Tool]
    VALID[Shared deterministic validation + Permission Engine]
    APP[Existing Application / Planner operation]
    AUDIT[ToolResult + AgentAction + D7 audit]

    TEXT --> WAG
    VOICE --> STT
    STT -- no --> UNAVAILABLE
    STT -- yes --> SPEECH
    SPEECH --> WAG
    WAG --> READY
    READY -- no --> WAIT
    READY -- yes --> LLM
    LLM --> TOOL
    TOOL --> VALID
    VALID --> APP
    APP --> AUDIT
```

AGT-014 supersedes the former STT-only AI entry gate. Canonical state distinctions:

```text
aiEntrySupported        stable platform + available input capability
userEnabledAiEntry      local user preference
effectiveAiEntryEnabled capability + preference
providerReady           approved binding + required credential availability
requestReady            entry enabled + provider ready + network reachable
```

Network loss changes request availability, not stable AI capability. Watch-originated
Provider requests remain Watch-originated when the OS uses paired-phone networking.
Core local calendar/task functions remain available without AI. Exact first-alpha
capability/probe/policy contracts are proposed in D9-03-00 / OD-058; no runtime is
implied by this diagram.

---

# 13. Wear Provider provisioning

```mermaid
sequenceDiagram
    participant Source as Explicitly chosen provisioner
    participant Crypto as Existing D8 target HPKE
    participant Delivery as Credential delivery contract OD-058 PENDING
    participant Watch
    participant KS as Watch PlatformSecretStore
    participant Metadata as Watch binding / revision metadata

    Source->>Crypto: Encrypt credential for existing target D8 public identity
    Crypto->>Delivery: ProviderCredentialEnvelope ciphertext
    Delivery->>Watch: Deliver opaque envelope
    Watch->>Watch: Authenticate/decrypt + validate target/config/revision + user approval
    Watch->>KS: Import credential behind SecretRef
    Watch->>Metadata: Atomically publish SecretRef + accepted revision
    Metadata-->>Watch: Activate approved WearProviderBinding
```

SYN-018 freezes target HPKE/profile and anti-rollback boundaries. The earlier Data
Layer sequence was conceptual: it did not authorize a nearby sender identity,
ACK/retention or secure-store/DB crash contract. D9-03-00 records concrete alternatives
and blocks those implementation choices on OD-058. PlatformSecretStore import and
SQLite publish are not a single distributed transaction; recovery is a pending
explicit contract. ProviderConfig metadata is separately locally approved; no
credential travels in ordinary workspace sync and no second crypto hierarchy is created.

---

# 14. Phone ↔ Watch offline synchronization

```mermaid
sequenceDiagram
    participant P as Phone Local DB
    participant PS as Phone Sync Engine
    participant DL as Wear Data Layer
    participant WS as Watch Sync Engine
    participant W as Watch Local DB

    Note over P,W: Internet unavailable on both devices

    P->>PS: Commit Event mutation + SyncOperation
    PS->>DL: Encrypted/encoded operation batch
    DL->>WS: Nearby delivery
    WS->>WS: Causality + semantic validation
    WS->>W: Apply operation

    W->>WS: User completes Task
    WS->>DL: New Watch SyncOperation
    DL->>PS: Nearby delivery
    PS->>PS: Merge / validate
    PS->>P: Apply Task completion

    Note over P,W: Phone later regains internet
    PS->>PS: Upload accumulated operations to server
```

Watch is an operation-producing replica, not merely a remote UI.

---

# 15. External calendar adapter boundary

```mermaid
flowchart LR
    DOMAIN[Internal Domain]
    MAP[External Mapping Layer]
    ICS[ICS]
    CALDAV[CalDAV]
    GOOGLE[Google Calendar]
    OUTLOOK[Outlook]

    DOMAIN <--> MAP
    MAP <--> ICS
    MAP <--> CALDAV
    MAP <--> GOOGLE
    MAP <--> OUTLOOK
```

External providers are adapters. Their object models do not define the internal Event/Course/Task semantics.

Lossy mapping must be explicit where an external system cannot represent an internal concept.

---

# 16. Domain object relationships

```mermaid
classDiagram
    class Calendar
    class Event
    class Course
    class CourseScheduleRule
    class CourseSession
    class Exam
    class Task
    class FocusBlock
    class WorkLog
    class Project
    class Reminder
    class PlanningProfile
    class Constraint
    class PlanBranch
    class AgentThread
    class AgentMessage
    class AgentAction
    class SyncOperation

    Calendar "1" --> "*" Event
    Calendar "1" --> "*" Course
    Calendar "1" --> "*" Exam

    Course "1" --> "*" CourseScheduleRule
    CourseScheduleRule "1" --> "*" CourseSession
    Course "1" --> "*" Exam

    Project "0..1" --> "*" Task
    Project "0..1" --> "*" Event

    Task "1" --> "0..*" FocusBlock
    Task "1" --> "0..*" WorkLog
    Task "0..*" --> "0..*" Task : dependency DAG

    Event "1" --> "0..*" Reminder
    Task "1" --> "0..*" Reminder
    Exam "1" --> "0..*" Reminder

    PlanningProfile "1" --> "*" Constraint

    PlanBranch --> Event : proposed changes
    PlanBranch --> FocusBlock : proposed changes

    AgentThread "1" --> "*" AgentMessage
    AgentAction --> SyncOperation
```

This diagram expresses semantic relationships, not a final relational database schema.

---

# 17. Explicit forbidden shortcuts

These flows are intentionally unsupported:

```mermaid
flowchart LR
    LLM[LLM Provider]
    DB[(Local DB)]
    SERVER[(Server DB)]
    DOMAIN[Domain]
    SUMMARY[ContextSummary]
    HISTORY[Authoritative History]
    GIT[Git]
    SYNC[Production Sync]

    LLM -. FORBIDDEN .-> DB
    LLM -. FORBIDDEN .-> SERVER
    SUMMARY -. cannot override .-> HISTORY
    SERVER -. cannot define .-> DOMAIN
    GIT -. not runtime protocol .-> SYNC
```

Additional forbidden assumptions:

```text
latest updatedAt wins                         NO
LLM explanation is authoritative              NO
Provider conversation ID is Agent memory       NO
Course is just RRULE Event                     NO
FocusBlock completion automatically completes Task  NO
No network means no local scheduling           NO
Wear is only a phone remote                     NO
```

---

# 18. End-to-end example: "Move Friday meeting and find two hours for PPT"

```mermaid
sequenceDiagram
    participant U as User
    participant C as ContextAssembler
    participant L as LLM
    participant R as Read Tools
    participant P as Planner
    participant PE as Permission Engine
    participant B as PlanBranch
    participant T as Tool Transaction
    participant H as History / ChangeLog
    participant S as Sync Engine

    U->>C: "Move Friday meeting to 3pm, find 2h for PPT, keep evening free"
    C->>C: Add Thread + Summary + ContextAnchor + domain snapshot
    C->>L: Context + command
    L->>R: Search Friday meeting / PPT task if required
    R-->>L: Structured entities + IDs
    L->>P: Structured requested changes + constraints
    P->>P: Validate time, conflict, effort, availability
    P-->>L: Feasible proposal + DecisionReasons
    L->>PE: Typed plan
    PE->>B: High-impact multi-item plan requires preview
    B-->>U: Proposed changes + explanations
    U->>B: Apply
    B->>T: Atomic application
    T->>H: AgentAction + ToolResults + ChangeLog
    T->>S: SyncOperations
    T-->>U: Applied successfully
```

At no point does the LLM write a database record directly.

---

# 19. End-to-end example: context window has compacted old conversation

```mermaid
sequenceDiagram
    participant U as User
    participant C as ContextAssembler
    participant SUM as ContextSummary
    participant L as LLM
    participant HS as History Search
    participant CL as ChangeLog

    U->>C: "Why did you move that revision block three days ago?"
    C->>SUM: Load compact summary
    C->>L: Recent context + summary + command
    L->>HS: Search relevant actions from ~3 days ago
    HS->>CL: Structured search by semantic/entity/time filters
    CL-->>HS: AgentAction #82 + diff + DecisionReason
    HS-->>L: Authoritative structured history
    L-->>U: Explanation grounded in #82
```

The old raw message does not need to remain inside the current model context window for the system to remember what happened.

---

# 20. Architecture review checklist

A proposed architectural change should be rejected or require an ADR if it violates any of the following without explicitly changing the baseline:

1. Does it make Domain depend on UI, database, network, or AI SDK code?
2. Does it allow LLM output to bypass typed Tools/validation/permissions?
3. Does it treat Provider session storage as Scheduler memory?
4. Does it discard authoritative history when compacting context?
5. Does it turn ChangeLog into mutable conversational text?
6. Does it collapse Task into Event or FocusBlock into Task?
7. Does it model Course as only a generic recurrence rule?
8. Does it use timestamps alone as domain time semantics?
9. Does it make Sync use Last-Write-Wins for semantic conflicts?
10. Does it let PlanBranch overwrite newer active state without revalidation?
11. Does it require the Server to read user schedule plaintext?
12. Does it make core calendar/planning functions unavailable when offline?
13. Does it reduce Wear OS to a display-only remote?
14. Does it create incompatible domain meanings across platforms?

If the answer to any item is yes, stop and write an ADR before implementation.

---

# Final architecture rule

The complete product can be summarized as:

```text
Agentic Surface
      ↓
Application-owned Context & Memory
      ↓
Typed Tool Boundary
      ↓
Deterministic Domain + Planner
      ↓
Local Transaction + Audit History
      ↓
Local-first Sync + E2EE
      ↓
Other Devices / Thin Server
```

**The LLM is replaceable. The user's time model, history, and memory are not.**
