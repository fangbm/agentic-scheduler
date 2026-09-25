# Agentic Scheduler — D9 Agent Runtime Decisions

> Status: **D9-00 FROZEN**  
> Date: 2026-09-12  
> Applies to: D9 Agent runtime, internal typed Tools, context, permissions, provider adapter, retention

D9 implementation remains sequenced after deterministic Planner/history/sync foundations. This document freezes the decisions that were previously left to D9-00.

---

# AGT-001 — Agent module and dependency direction

D9 authorizes:

```text
:shared:agent
```

Dependency direction:

```text
:shared:agent -> :shared:domain
:shared:agent -> :shared:application
:shared:agent -> :shared:planner
:shared:agent -> :shared:sync only for typed IDs/history references where required
```

Agent code does not import Room records/DAOs or server implementation classes.

Platform apps compose provider/network/secure-store implementations into the shared Agent runtime.

---

# AGT-002 — Tool-only mutation path

The LLM has no generic mutation capability.

Illegal:

```text
LLM -> DB / DAO
LLM -> generic repository.write
LLM -> raw SyncOperation
LLM -> arbitrary HTTP side effect
LLM-generated prose parsed as an implicit business mutation
```

Legal:

```text
model tool call
→ typed schema decode
→ Tool validation
→ deterministic application/Planner operation
→ Permission Engine
→ preview/confirmation when required
→ application transaction
→ ToolResult
→ AgentAction
→ D7 ChangeLog/SyncOperation for committed business writes
```

A model saying "done" never overrides a failed ToolResult.

---

# AGT-003 — Permission model and defaults

Permission is local-device security state and is **not** synchronized through ordinary D8 SyncOperations.

The Agent cannot read/write its own permission settings through Tools.

Each capability has one user-owned mode:

```text
ALLOW_DIRECT
REQUIRE_CONFIRMATION
DENY
```

V1 capability classes and default policy:

```text
READ                         ALLOW_DIRECT
PLAN_PREVIEW                 ALLOW_DIRECT
LOW_RISK_CREATE              REQUIRE_CONFIRMATION
SOURCE_FACT_UPDATE           REQUIRE_CONFIRMATION
PLANNING_PROFILE_CHANGE      REQUIRE_CONFIRMATION
SCHEDULE_APPLY               REQUIRE_CONFIRMATION
UNDO                         REQUIRE_CONFIRMATION
BULK_CHANGE                  DENY
DESTRUCTIVE                  DENY
EXTERNAL_SIDE_EFFECT         DENY
```

A user may explicitly relax supported v1 classes from confirmation to direct execution. D9 v1 contains no destructive/external-side-effect Tool, so those classes remain deny-only until a future task introduces a concrete capability and review.

Permission checks occur after schema/domain validation but before committed writes.

`REQUIRE_CONFIRMATION` produces a structured pending preview; denial/dismissal performs no write.

Planner schedule movement uses PlanBranch preview. Ordinary Event/Task/Profile writes use a typed before/after `ToolWritePreview`; PlanBranch is not abused as a generic mutation container.

---

# AGT-004 — Initial internal Tool surface

D9 v1 Tools are internal application contracts. OD-054 remains pending because no MCP/external schema compatibility is promised.

Read/preview Tools:

```text
calendar.list
    input: date/window + display TimeZone
    output: CalendarProjectionResult

task.get
    input: TaskId
    output: Task | NotFound

task.list
    input: optional explicit status filter
    output: canonical Task list

history.timeline
history.getMutation
history.getEntityChanges

planner.previewFullReplan
    input: explicit PlanningSnapshot/request facts supplied through application assembler
    output: applicable PlanBranch | structured infeasible/invalid result

planner.previewLocalReflow
    input: explicit affected IDs/disruption/search window
    output: applicable PlanBranch | structured result
```

Write Tools:

```text
event.create      -> existing D5-02 application command
event.update      -> existing D5-02 application command
task.create       -> existing D5-02 application command
task.update       -> existing D5-02 application command
planningProfile.update -> existing/future explicit application command, never repository direct
planner.applyBranch     -> D6 stale/atomic Apply
history.undo            -> D7 Undo
```

If `planningProfile.update` application operation does not yet exist at D9 start, that Tool remains unavailable until the deterministic application command exists.

No Event/Task delete Tool exists in D9 v1 because no such business delete contract exists.

---

# AGT-005 — Tool schema/result contract

Every Tool freezes:

```text
canonical name
input DTO schema
success result DTO
expected failure variants
capability class
read/write classification
preview behavior
transaction boundary
history/audit mapping
```

Common expected result vocabulary is equivalent to:

```text
Success(payload)
InvalidInput(issues)
NotFound
PermissionDenied
ConfirmationRequired(preview)
Stale
Conflict
Infeasible
InfrastructureFailure(redactedCode)
```

Raw stack traces/exceptions are not model ToolResult payloads.

Tool call IDs are Agent-runtime IDs, not MutationIds. A successful write result references the committed MutationId.

---

# AGT-006 — First provider adapter

D9 v1 uses a provider abstraction and ships the first network adapter as a strict **OpenAI-compatible chat/tool-calling subset**, implemented directly with:

```text
Ktor Client 3.5.2
kotlinx.serialization JSON 1.11.0
SSE streaming where supported
```

No vendor SDK is required for the first adapter.

This is an internal adapter profile, not a claim that all "OpenAI-compatible" servers behave identically.

Provider config explicitly supplies:

```text
baseUrl
model
context capacity/capability metadata
optional SecretRef for API credential
streaming supported?
tool calling supported?
```

There is no hidden production base URL/model default in shared Agent core.

Before enabling write-capable Agent execution, the adapter must pass a capability probe proving it can return structured tool calls. If tool calling is unsupported, the provider is read/chat-only; the runtime never scrapes free-form prose into write Tool calls.

Provider-specific adapters may be added later without changing AgentThread truth or Tool contracts.

---

# AGT-007 — Provider/model/credential ownership

Provider and model selection are application-owned per-device settings.

They are not properties of AgentThread and switching provider/model does not create a new thread.

Non-secret config may be stored locally in Room/settings. Secret material is stored only through the D8 `PlatformSecretStore`; the database stores `SecretRef`.

Provider credentials:

```text
never enter Domain entities
never enter ChangeLog/SyncOperation
never enter AgentMessage/ContextSummary
never appear in logs/ToolResult
never get sent to another device through ordinary D8 sync
```

A provider configured with a credential reference requires an HTTPS base URL;
the adapter rejects plain HTTP before resolving that credential. Explicitly
configured credential-free HTTP endpoints remain possible (for example a
local model); the future UI must not imply that non-loopback HTTP protects
prompt or schedule plaintext in transit.

D9-03 Watch provisioning uses the separately encrypted `ProviderCredentialEnvelope` frozen by OD-042/SYN-018.

---

# AGT-008 — Context authority

Truth precedence is frozen:

```text
1. current Domain/Application state returned by Tools
2. committed ToolResult / ChangeLog / AgentAction facts
3. structured Agent runtime state
4. recent raw conversation
5. ContextSummary
6. retrieved non-authoritative text
```

Lower levels cannot override higher levels.

A summary saying a Task is OPEN cannot override a current `task.get` result saying COMPLETED.

ContextAssembler requests the minimum relevant structured facts; it never dumps the entire local database by default.

---

# AGT-009 — Context budgeting / OD-050

The Agent core budgets using abstract deterministic `BudgetUnit`s supplied by the selected provider adapter. The core does not assume every provider uses the same tokenizer.

Provider adapter contract includes:

```text
maxContextUnits
reservedOutputUnits
measure(serializedMessageOrSchema) -> BudgetUnits
```

If an adapter cannot provide an exact tokenizer, it must provide a documented conservative deterministic meter. The first OpenAI-compatible generic profile may use UTF-8-byte upper-bound accounting and therefore be conservative; provider-specific exact tokenizers may improve utilization without changing priority semantics.

Input budget:

```text
maxInputUnits = maxContextUnits - max(reservedOutputUnits, 20% of maxContextUnits)
```

Mandatory classes, in order:

```text
system safety/runtime instructions
typed Tool schemas needed for this turn
current user command
explicit ContextAnchor
```

If mandatory content alone exceeds input budget, return structured `ContextTooLarge`; do not silently drop Tool schemas or the current command.

Remaining budget is filled deterministically by priority with caps measured against `maxInputUnits`:

```text
current relevant Domain facts               up to 35%
recent ToolResults + AgentActions/ChangeLog up to 20%
recent raw AgentMessages                    up to 25%
ContextSummary                              up to 10%
retrieved history/text                      up to 10%
```

Unused capacity from an earlier class may flow to later classes. Within a class, newest/relevance ordering is deterministic and canonical; authoritative structured facts are never displaced by a lower-priority class.

---

# AGT-010 — Context compaction / OD-051

Raw AgentMessage history remains durable until the retention policy deletes the thread. Compaction only changes hot prompt material.

Persistent compaction is considered when either:

```text
selected raw-message context exceeds its 25% budget class
or
pre-compaction assembled prompt exceeds 80% of maxInputUnits
```

Compaction selects the oldest **closed contiguous prefix** while retaining at minimum:

```text
last 12 messages
all unresolved Tool calls/results
all pending confirmation previews
```

`ContextSummary` stores:

```text
summaryId
threadId
sourceStartMessageId
sourceEndMessageId
summarySchemaVersion
summary text/structured bullets
createdAt
provider/model used for audit only
```

Source range is immutable. A new incremental summary supersedes older hot use but does not rewrite it.

Provider/model change alone does not invalidate a summary. `summarySchemaVersion` or source-history change controls invalidation.

If summary generation fails:

```text
no raw history is deleted
no partial summary is committed
ContextAssembler deterministically omits the oldest low-priority raw messages for that request
```

ContextSummary is derived/non-authoritative and local-only; D9-02 does not synchronize it.

---

# AGT-011 — Agent persistence and retention / OD-053

D9-01 persists distinct concepts:

```text
AgentThread
AgentMessage
AgentToolCall
AgentToolResult
ContextSummary
AgentAction
AgentPermissionPolicy (local only)
ProviderConfig metadata + SecretRef (local only)
```

No generic all-purpose chat-row table.

V1 retention:

```text
no automatic time-based purge
raw thread content remains until explicit user thread deletion
compaction is never deletion
```

Deleting a thread removes local raw `AgentMessage`, ToolCall/ToolResult conversational records, and summaries after the deletion mutation/notification is accepted. `AgentAction` and D7 ChangeLog entries describing real committed business changes remain as audit facts, but they no longer need raw message text.

D9-01 thread deletion is local-only until D9-02 synchronization is enabled.

When D9-02 is enabled, thread deletion becomes a causal `AgentThreadDelete` tombstone. Every active replica purges active raw thread content when it applies that tombstone.

Important privacy limit: historical encrypted operation envelopes/backups may retain old ciphertext until a future approved compaction/retention mechanism. D9 does not claim retroactive cryptographic erasure from a malicious server or already-compromised device. The UI/privacy documentation must not describe thread deletion as guaranteed erasure of every historical encrypted byte.

---

# AGT-012 — AgentAction

`AgentAction` is durable audit of Agent orchestration, separate from ChangeLog.

Minimum structure:

```text
AgentActionId
threadId?
sourceMessageId?
providerConfigId/model metadata
ordered ToolCall ids
ordered ToolResult ids
permission decision
confirmation/PlanBranch references
committed MutationIds
final status
```

AgentAction does not duplicate full Domain before/after payloads already in ChangeLog.

A failed Tool remains failed in AgentAction even if later assistant prose claims success.

D9 write origin extends D7 mutation origin with `AGENT(agentActionId)`.

D9-01 Agent-origin **business mutations** use encrypted inner `SyncPayloadV2`.
The outer D8 envelope remains v1. V1 continues to encode/decode non-Agent
origins; V2 is reserved for `AGENT(agentActionId)`, and an older D8 client
quarantines the whole unknown-version operation. This is not AgentThread or
conversation synchronization; those operation kinds remain D9-02.

An Agent write in an active SyncSpace requires a device-local, user-owned
opt-in that confirms all enrolled devices are upgraded for payload v2. It is
off by default and inaccessible to Agent Tools. Without it, synchronized
Agent writes are unavailable even if Tool permission otherwise allows them.
Local-only writes still obey normal Tool validation and permission rules.

---

# AGT-013 — D9-02 synchronization amendment

D9-02 extends D8 for Agent conversation/history operations only after D9-01
local Agent behavior is stable. The Agent-origin business payload v2 above is
the narrow D9-01 compatibility amendment.

Synchronized Agent concepts:

```text
AgentThread metadata
AgentMessage
AgentToolCall
AgentToolResult
AgentAction
AgentThreadDelete tombstone
```

Not synchronized:

```text
ContextSummary
permission policy
ProviderConfig/API credentials
provider session/cache IDs
```

Merge rules:

```text
AgentMessage / ToolCall / ToolResult / AgentAction
    append-only by immutable ID
    same ID + same value -> dedupe
    same ID + different value -> integrity conflict

AgentThread metadata
    title group
    lifecycle/delete group

AgentThreadDelete concurrent with new message
    -> explicit SyncConflict; no silent resurrection
```

D9-02 bumps protocol/payload schema version and adds explicit compatibility fixtures. D8 implementations must quarantine unknown D9 operation kinds rather than partially applying them.

---

# AGT-014 — Wear Agent gate

Wear can originate Agent requests but remains a real local-first replica.

D9-03 requires:

```text
ProviderCredentialEnvelope via SYN-018
PlatformSecretStore on Wear
network capability probe
STT capability probe
```

STT is optional capability, not assumed from network access. If unavailable, text/other platform input may still use the Agent runtime.

Watch-originated Tool calls use exactly the same permission/application/Planner contracts as Android/Desktop. Watch may impose a stricter local permission policy than the phone.

---

# AGT-015 — Semantic retrieval / external Tool compatibility

OD-052 semantic embedding retrieval is deferred from D9 v1. Structured exact history retrieval is sufficient for the first Agent alpha.

No embedding dependency/index is added in D9-01.

OD-054 remains pending because internal Tool schemas may evolve during alpha. Before MCP/external Tool compatibility is promised, freeze schema versions and compatibility negotiation separately.

---

# AGT-016 — D9 schema migration

D9-01 bumps current local Room schema `N -> N+1` for Agent runtime records.

D9-02 may require a later `N -> N+1` amendment for synchronized Agent tombstone/causal metadata rather than prematurely putting D9-02 columns into D9-01 tables.

No destructive migration fallback.

---

# AGT-017 — D9 completion gate

D9-01 is complete only when:

```text
[ ] fake Provider cannot bypass Tool layer
[ ] model prose cannot become an implicit write
[ ] read Tools perform zero writes
[ ] default permission policy requires confirmation for writes
[ ] denied/dismissed confirmation performs zero writes
[ ] stale PlanBranch cannot be applied through Agent
[ ] ToolResult truth matches transaction truth
[ ] AgentAction references committed MutationIds
[ ] ContextSummary cannot override current Domain facts
[ ] deterministic budget tests cover every priority/cap
[ ] compaction failure preserves raw history
[ ] provider switch preserves AgentThread continuity
[ ] secrets never enter logs/context/sync payloads
[ ] explicit thread deletion behavior passes retention tests
[ ] repository-wide CI is green
```

D9-02 and D9-03 have their own additional sync/Wear gates and do not block the first Android/Desktop Agent alpha.
