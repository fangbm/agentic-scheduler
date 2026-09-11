# Agentic Scheduler — D9 Agent Runtime & Typed Tool Surface

> Task ID: **D9 draft**  
> Milestone: **D9 — Agent / Universal Command**  
> Status: **BLOCKED_BY_DECISION**  
> Date: 2026-09-11

---

# 1. Goal

Add LLM orchestration only after deterministic Domain, Planner, history, and Sync capabilities exist.

D9 intends to establish:

```text
AgentThread / AgentMessage persistence
+ ContextAssembler
+ Provider adapter abstraction
+ typed read/write Tools
+ Permission Engine
+ Preview / PlanBranch routing
+ ToolResult
+ AgentAction
+ history retrieval
```

---

# 2. Hard invariant

There is no legal path:

```text
LLM → database
LLM → DAO
LLM → generic repository.write
LLM → server mutation
```

Legal mutation path:

```text
LLM proposal
→ typed Tool
→ schema validation
→ Domain/Planner validation
→ Permission Engine
→ Preview/PlanBranch when required
→ application transaction
→ ToolResult + AgentAction + ChangeLog + SyncOperation
```

Provider prose never outranks transaction truth.

---

# 3. Recommended split

```text
D9-00  context/permission/provider decisions
D9-01  shared Agent core + Android/Desktop integration
D9-02  E2EE synchronization amendment for AgentThread/history
D9-03  Wear Agent/provider provisioning
```

---

# 4. Proposed module

D9-01 should explicitly create:

```text
:shared:agent
```

Proposed dependencies:

```text
:shared:agent → :shared:domain
:shared:agent → :shared:application
:shared:agent → :shared:planner
```

Agent code must not import Room records/DAOs.

The module creation is not authorized until D9-00 approves it.

---

# 5. Required D9-00 decisions

Existing open decisions:

```text
OD-050  Context window budgeting
OD-051  Context compaction lifecycle
OD-053  AgentThread retention/deletion policy
```

Additional D9-00 decisions must freeze:

```text
Tool permission/autonomy baseline
permission-setting persistence
first Provider adapter transport/dependency
Provider credential secure-storage policy
Provider/model selection ownership
```

OD-052 semantic embedding retrieval may remain deferred for v1.

OD-054 may remain pending while Tools are internal-only and no MCP/external compatibility promise exists.

---

# 6. Agent persistence concepts

Keep these distinct:

```text
AgentThread
AgentMessage
ToolCall record
ToolResult record
ContextSummary
AgentAction
```

Do not collapse them into one generic chat-message table.

Provider conversation/session IDs may be retained only as disposable adapter metadata.

Adding Agent persistence bumps the then-current local schema:

```text
N → N+1
```

with explicit migrations/tests.

---

# 7. Retention/deletion

D9 must not silently mean "store all conversation forever".

OD-053 must define production behavior for:

```text
thread deletion
message/history retention
user-visible controls
what compaction does not delete
synchronized deletion behavior if applicable
```

Compaction is not deletion.

Authoritative ChangeLog/state history must not be erased merely to fit a model context window.

---

# 8. Context authority

Factual authority remains:

```text
1. Current Domain State
2. ToolResult / ChangeLog
3. structured Agent state
4. raw conversation
5. ContextSummary
```

A summary can be stale or wrong and must not override current structured facts.

---

# 9. ContextAssembler

ContextAssembler is application infrastructure, not free-form provider prompt glue.

Potential approved inputs include:

```text
current user command
ContextAnchor
thread metadata
recent AgentMessages
recent ToolResults
relevant Domain snapshot
relevant ChangeLog/AgentAction facts
ContextSummary
retrieved history
```

It sends the minimum approved context rather than the whole local database by default.

Provider-specific adapters must not define different truth precedence rules.

---

# 10. OD-050 budget gate

Before production ContextAssembler, freeze deterministic budget/priority classes for:

```text
system/tool schemas
current command
ContextAnchor
current Domain facts
recent ToolResults
recent AgentActions/ChangeLog
recent raw messages
ContextSummary
retrieved history
```

Provider token limits may parameterize capacity, but factual authority ordering stays provider-independent.

---

# 11. OD-051 compaction gate

Before first persistent production compaction, freeze:

```text
when compaction triggers
immutable source-range tracking
incremental summary update strategy
summary version/invalidation behavior
provider/model-change behavior
failure behavior
```

Compaction may remove text from hot prompt context but cannot destroy authoritative stored history.

---

# 12. Tool contract

Every Tool defines:

```text
canonical name
purpose
input schema
result schema
read/write classification
validation rules
risk level
permission behavior
transaction behavior
history/audit behavior
Undo behavior when applicable
```

Do not expose generic SQL/repository mutation Tools.

Do not expose one arbitrary-JSON `execute` Tool when specific typed capabilities can exist.

---

# 13. Initial read Tools

D9 should build read capabilities before write capabilities.

Potential v1 read surface:

```text
calendar.list
task.get
task.list
course.list
history.timeline
history.getMutation
planner.preview
```

Exact names/schemas are frozen by the future D9-01 implementation spec.

Read-only Tools must have no hidden mutation side effects.

---

# 14. Initial write Tools

Potential write capabilities:

```text
event.create
event.update
task.create
task.update
planner.applyBranch
history.undo
```

A Tool is only eligible when the underlying deterministic application operation already exists and its creation/ID/default semantics are frozen.

D9 does not introduce a business write merely because an LLM might want it.

---

# 15. Permission Engine

The model cannot grant itself permissions.

No default Agent autonomy level may be invented.

D9-00 must define capability categories and onboarding/configuration behavior, for example:

```text
READ
LOW_RISK_WRITE
SCHEDULE_MOVEMENT
BULK_CHANGE
DESTRUCTIVE
EXTERNAL_SIDE_EFFECT
```

The exact categories and defaults remain unresolved until D9-00.

---

# 16. Preview / PlanBranch rule

Preview/PlanBranch is mandatory whenever the approved permission/risk policy requires it.

Expected preview-required classes include at least:

```text
Planner schedule movement
multi-entity/bulk change
high-risk/destructive action
```

A low-risk direct write may skip preview only when the user-owned permission policy explicitly allows it.

---

# 17. Provider adapter

D9-00 must choose whether the first adapter uses:

```text
a Provider SDK
or
a generic HTTP/Ktor client
```

and pin exact dependencies.

Provider adapter owns API mapping/streaming mechanics, not business rules.

Provider session IDs are optional optimization/cache handles only.

Switching Provider must not reset application-owned AgentThread continuity.

---

# 18. Provider credentials

Before real Provider use, freeze:

```text
credential storage owner per platform
secret retrieval boundary
logging/redaction rules
configuration flow
revocation/rotation behavior
```

Provider credentials must never appear in ordinary Domain/Sync payloads or logs.

---

# 19. AgentAction

AgentAction records what the Agent attempted and what actually happened.

It should reference as applicable:

```text
AgentThread
user request / source message
Tool calls
ToolResults
MutationId(s)
permission/preview outcome
Planner DecisionReason references
```

AgentAction does not replace ChangeLog.

Failed Tool execution must remain a failed structured fact even if the model generated success language.

---

# 20. D9-02 Agent synchronization amendment

If AgentThread/AgentMessage/AgentAction later join multi-device Sync, D9-02 must explicitly amend:

```text
wire protocol schema/version
OD-031 merge matrix
E2EE payload definitions
retention/delete synchronization behavior
compatibility/migration tests
```

D8 must not be assumed to have pre-defined merge semantics for future Agent records.

---

# 21. D9-03 Wear gate

Wear Agent/provider provisioning is intentionally separated from D9-01.

Before D9-03:

```text
OD-042 ProviderCredentialEnvelope crypto resolved
Wear secure credential storage frozen
STT capability probe behavior frozen
Provider/network capability behavior tested
```

Network availability is not proof that on-device STT exists.

A Watch-originated request still uses the same typed Tool/Domain/Planner semantics as other clients.

---

# 22. Required tests once READY

At minimum:

```text
fake Provider cannot bypass Tool layer
read Tools cause no writes
denied permission causes no mutation
preview-required action cannot direct-commit
ToolResult reflects transaction failure truthfully
AgentAction references committed mutation
ContextSummary cannot override current Domain state
Provider switch preserves AgentThread continuity
ContextAssembler sends only approved minimum context
credential fixtures/logs contain no secrets
retention/delete behavior tests
protocol-amendment tests if D9-02 is included
Wear unavailable-capability behavior for D9-03
```

---

# 23. READY gate

D9-01 remains `BLOCKED_BY_DECISION` until:

```text
[ ] OD-050 resolved
[ ] OD-051 resolved
[ ] OD-053 resolved
[ ] Tool permission/autonomy baseline frozen
[ ] permission persistence frozen
[ ] first Provider adapter transport/dependency frozen
[ ] Provider credential secure-storage policy frozen
[ ] :shared:agent boundary approved
[ ] initial Tool schemas frozen
```

OD-052 may remain deferred for D9 v1.

OD-054 may remain pending while Tools remain internal-only.

D9-03 additionally requires OD-042.
