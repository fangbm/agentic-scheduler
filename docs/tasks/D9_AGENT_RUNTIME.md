# Agentic Scheduler — D9 Agent Runtime & Typed Tool Surface

> Task ID: **D9-01 / D9-02 / D9-03**  
> Milestone: **D9 — Agent / Universal Command**  
> Status: **D9-01 IN PROGRESS — D8 COMPLETE**
> Date: 2026-09-12  
> Decision source: `docs/AGENT_DECISIONS.md`

---

# 1. Goal

Add LLM orchestration over deterministic capabilities that already exist. The Agent does not become a second business-logic engine.

D9-01 output:

```text
:shared:agent
AgentThread/Message/ToolCall/ToolResult persistence
ContextAssembler + deterministic budget/compaction
provider adapter abstraction + first OpenAI-compatible HTTP adapter
internal typed read/write Tools
local Permission Engine + confirmation previews
AgentAction audit
Android/Desktop Agent surface / universal command entry
```

D9-02 adds synchronized Agent history. D9-03 adds Wear provider provisioning/capability integration.

Implementation progress on this branch: typed permission policy and calendar/task reads,
followed by local Agent state persistence. Room schema v12 and a v11→v12 migration
preserve existing D8 rows; focused fresh-install, migration, and thread-deletion
tests pass. Provider orchestration, context, remaining Tools, audit write flow,
and Android/Desktop surfaces remain open.

The deterministic ContextAssembler and incremental compaction selector/service
are now implemented and pass shared desktop tests. Agent-origin business
mutations now have an inner payload v2 codec and frozen fixture; sync unit
tests pass. Full provider orchestration, Tool writes, AgentAction execution,
and UI acceptance remain open.

The D9 branch now returns the committed MutationId from Event/Task editing
results, supports explicit Agent mutation origin, and fails closed at the
MutationCoordinator unless trusted host wiring authorizes the Agent write.
The authorization checks device-local SyncSpace upgrade acknowledgement when
enrolled; outbound sync independently refuses Agent-origin upload without the
same acknowledgement, including for Agent mutations created before enrollment.
Focused application/database gate tests pass. Structured D7 history
read Tools are implemented. This is infrastructure, not a completed Agent
write/confirmation flow.

The first Ktor OpenAI-compatible adapter now has a strict structured
tool-calling capability probe, request-time secure-store credential resolution,
redacted failures, preservation of assistant ToolCall/tool-result transcript
messages, and SSE delta assembly. Focused mock-provider desktop tests pass.
When a credential reference is configured, the adapter refuses non-HTTPS
provider URLs before reading the secret or sending a request. Explicit
credential-free HTTP model endpoints remain possible; a future UI must warn
when a non-loopback endpoint exposes prompt/schedule plaintext in transit.
The adapter is wired into the bounded persistent run below, but not either UI.

Event/Task editing commands now accept an in-transaction `onCommitted`
callback. The Room integration test links one Agent-origin Task, ToolResult,
AgentAction, ChangeLog, and exact MutationId atomically; an audit callback
failure is verified to roll the Task, journal, and ToolResult back. The actual
Tool confirmation/runtime path still needs to use this seam.

`task.create` is the first typed write Tool on this branch. It requires
explicit priority/effort/deadline values, validates before permission,
produces a normalized before/after preview, rechecks the preview and policy
at confirmation, and returns the exact committed MutationId. Focused Room
tests cover invalid input, denial, unconfirmed/stale preview, and success.
Other write Tools and full provider-to-Tool orchestration remain open.
`task.update` now has a typed preview/commit Tool and a transaction-time
expected-before guard in `TaskEditingService`. Its Room test verifies a
concurrent user edit makes the Agent preview stale with no extra journal
operation, while a fresh confirmed preview returns a committed MutationId.
The bounded provider registry now exposes `task.update` after a successful
structured-call probe. Its local mock covers a stale confirmation after a
concurrent user edit, then a fresh confirmed update with Agent-origin
MutationId/AgentAction linkage. Android/Desktop UI still does not expose it.

Provider call IDs are now retained as adapter metadata on AgentToolCall. A
transcript assembler reconstructs assistant ToolCalls and exactly matching
ToolResults from application-owned records, refusing unresolved/orphaned
history. The Room test shows provider/model switching preserves the same
thread and transcript.

A bounded application-owned Agent run now composes the provider with an
explicit `calendar.list`, `task.get`, `task.list`, `history.timeline`,
`history.getMutation`, `history.getEntityChanges`, `task.create`, and
`task.update` registry, deterministic context budget, and persisted
ToolCall/ToolResult/AgentAction records. Its local Ktor mock test
covers proposal → local read → confirmation → Task/MutationId/history linkage,
plus denial, stale preview, prose-only, unregistered Tool, failed capability
probe, and stale-summary/current-fact ordering. It is still a narrow slice:
remaining AGT-004 Tools, full history cursor support, Android/Desktop composition,
and full acceptance remain open. No synchronized Agent write is enabled in
production before D8 runtime closure and device-local upgrade opt-in.
The latest assistant ToolCall/matching ToolResult is mandatory in a resumed
provider request. An oversized authoritative result now returns
`CONTEXT_TOO_LARGE` before another provider call while retaining raw messages
and ToolResults; the focused Room mock test passes.
The bounded runtime now invokes AGT-010 compaction when measured eligible raw
candidate demand exceeds the 25% class or the assembled prompt exceeds 80%
of its input budget. Measuring demand before the class cap is necessary for
the 25% trigger to be reachable. A saved summary replaces only covered raw
messages in hot context; the Room mock verifies its source range and that
both successful and failed summarization retain every raw message.
`calendar.list` now requires an explicit date window and display timezone,
uses the existing application projection, and serializes Zoned/AllDay/
Floating/DateOnly items plus conflict/issue facts as JSON. Its local provider
mock verifies an all-day item and that the read emits no mutation.

---

# 2. Required reading

```text
docs/AGENT_DECISIONS.md
docs/HISTORY_SYNC_DECISIONS.md
docs/SYNC_SECURITY_DECISIONS.md
docs/OPEN_DECISIONS.md
docs/tasks/D7_OPERATION_HISTORY_SYNC_FOUNDATION.md
docs/tasks/D8_E2EE_SYNC_TRANSPORT.md
docs/tasks/D6_DETERMINISTIC_PLANNER.md
docs/IMPLEMENTATION_CONTRACT.md
this Task Spec
```

D9-01 implementation begins only after D8 is COMPLETE. Agent prototyping may use fake Providers earlier but must not merge production write paths ahead of the gate.

---

# 3. Split

```text
D9-01  local shared Agent core + Android/Desktop universal command
D9-02  AgentThread/history E2EE sync protocol amendment
D9-03  Wear Agent + ProviderCredentialEnvelope + capability probes
```

D9-02/03 are not required for the first Android/Desktop Agent alpha.

---

# 4. Hard boundary

Implement AGT-001/AGT-002 literally.

There is no legal escape hatch where a model response can directly mutate a repository because a typed Tool is inconvenient.

Every write Tool must terminate at an already-defined deterministic application operation. If the underlying operation does not exist, the Tool is unavailable.

---

# 5. Agent persistence

Bump current Room schema `N -> N+1` and persist distinct tables/records for:

```text
AgentThread
AgentMessage
AgentToolCall
AgentToolResult
ContextSummary
AgentAction
AgentPermissionPolicy
ProviderConfig (non-secret metadata + SecretRef)
```

Provider remote conversation/session IDs are disposable adapter metadata and cannot become AgentThread identity.

No destructive migration fallback.

The D8 Room database is already near the JVM method-size limit in Room 3's
generated schema validator. Registering the eight D9 entities as `@Entity`
exceeds that limit. `AgentSchema.kt` therefore owns explicit v12 SQL for the
eight distinct Agent tables in the **same database and transaction**. The
v11→v12 migration creates them for existing installations; the database
creation callback creates them for fresh v12 installations; every open checks
their required columns. The exported Room v12 JSON covers Room-managed D8
tables, and `AgentSchema.kt` is the authoritative catalog for the Agent tables.

---

# 6. Universal command surface

Android/Desktop expose one Agent-first command surface capable of:

```text
ordinary conversational request
read/query request
Tool-backed create/update request
Planner preview request
history/Undo request
```

The UI must render structured Tool/permission/confirmation truth rather than only assistant prose.

Minimum visible states:

```text
thinking/streaming
Tool proposed/running/succeeded/failed
confirmation required
PlanBranch preview
permission denied
stale/conflict/infeasible
provider/network unavailable
```

No new project-wide MVI/DI/navigation framework is required; existing minimal Compose/manual composition remains allowed.

---

# 7. Permission Engine

Implement AGT-003 exactly.

On first use, show the user the effective capability policy. The default must remain conservative: reads/previews direct, writes confirmation, bulk/destructive/external denied.

Permission settings are device-local and Agent-inaccessible.

A confirmation preview captures normalized tool inputs/before-after facts. Confirmation executes against current state and must revalidate; it cannot blind-apply stale preview facts.

Planner confirmation uses D6 PlanBranch stale/apply semantics directly.

---

# 8. Tools

Implement the AGT-004/AGT-005 v1 set.

Start with read/preview Tools, then add writes.

Every Tool has unit tests with a fake application service and at least:

```text
valid success
invalid input
NotFound where relevant
permission denied
confirmation required/direct modes
transaction failure
redacted InfrastructureFailure
```

No arbitrary JSON `execute` Tool.

---

# 9. Provider adapter

Implement AGT-006/AGT-007.

The first adapter is an explicit internal compatibility profile, not a universal standard. Provider capability probe must disable Tool writes when structured tool calling is not supported.

Streaming assistant text and Tool-call deltas must assemble deterministically into stored AgentMessage/AgentToolCall records.

Network/provider errors are structured and do not fabricate Tool success.

Provider API keys/tokens are resolved from `PlatformSecretStore` only at request time and redacted from diagnostics.

---

# 10. ContextAssembler

Implement AGT-008 through AGT-010.

Context selection is deterministic from:

```text
current request + anchor
approved Tool schemas
relevant current Domain/Application facts
recent ToolResults/AgentActions/ChangeLog
recent raw messages
ContextSummary
structured history retrieval
```

No semantic embedding index in D9-01.

Budget and compaction tests must use fake deterministic BudgetMeter/summary provider so CI does not depend on a live LLM.

---

# 11. Retention/privacy

Implement AGT-011.

Thread deletion UI must state the actual semantics. Do not promise global cryptographic erasure that the append-only encrypted sync/history architecture cannot guarantee.

AgentAction/ChangeLog for committed business writes remain audit facts after raw thread deletion.

OD-012 local database encryption remains a production-sensitive-data gate because raw Agent conversations may be locally sensitive.

---

# 12. AgentAction

Create AgentAction before/around execution so failures are also auditable, then finalize its status after Tool execution/confirmation outcome.

A committed write ToolResult references MutationId and AgentAction records that reference. D7 mutation origin uses `AGENT(agentActionId)`.

Agent-origin business mutations use inner payload v2 inside the unchanged D8
outer envelope. V1 is retained for non-Agent origins. Existing D8 clients
quarantine v2 whole operations; D9-01 must not emit one to a SyncSpace unless
the device-local, user-owned all-devices-upgraded opt-in is enabled. The
setting defaults off and is not exposed to Agent Tools. Local-only Agent
writes remain subject to normal confirmation and validation rules.

Do not copy provider secrets or complete system prompts into AgentAction.

---

# 13. D9-02 sync amendment

After D9-01 local behavior is stable, extend sync for conversation/history:

```text
use a separately versioned Agent conversation/history operation contract
add Agent history typed operation discriminators
add Agent semantic merge/tombstone rules from AGT-013
add migration/compatibility fixtures
verify older D8 clients quarantine unknown Agent operations safely
```

ContextSummary, permission policy, ProviderConfig credentials/settings remain device-local.

Thread delete tombstone is retained; OD-032 still controls physical compaction.

---

# 14. D9-03 Wear

Provision provider credentials only through `ProviderCredentialEnvelope`; never ordinary workspace sync.

Wear runtime must handle:

```text
no provider credential
no network
no STT capability
phone relay unavailable
permission stricter than phone
```

without corrupting AgentThread or business state.

Watch-originated Tools use the same D7/D6 application truth as other clients.

---

# 15. Required D9-01 tests

```text
Provider cannot bypass Tool registry
free-form prose cannot write
read Tool causes no mutation
write defaults to confirmation
ALLOW_DIRECT write still validates/rechecks current state
DENY writes nothing
confirmation stale revalidation
PlanBranch stale apply through Agent
Tool transaction failure truth
AgentAction success/failure references
provider switch same AgentThread
capability probe disables unsupported Tool writes
Budget priority/caps
ContextTooLarge mandatory-content path
summary trigger + incremental source ranges
summary failure preserves history
summary cannot override current Domain result
thread delete purges conversational rows but preserves audit mutation history
secret redaction
schema migration N -> N+1
```

D9-02/03 add their own protocol/Wear tests from `AGENT_DECISIONS.md`.

---

# 16. Explicit exclusions

D9-01 does not add:

```text
semantic embedding/vector DB
MCP/external Tool compatibility promise
generic web/browser automation Tool
Event/Task deletion
external calendar writes
provider-owned conversation as source of truth
server-side Agent execution
secret synchronization through ordinary SyncOperation
project-wide MVI/DI framework
```

---

# 17. Completion gate

D9-01 PASS requires AGT-017 plus:

```text
[ ] D8 COMPLETE before production integration
[ ] :shared:agent dependency direction verified
[ ] Android/Desktop universal command surfaces usable
[ ] all write Tools map to existing deterministic application commands
[ ] local permission settings cannot be modified by model
[ ] Provider credentials only exist behind SecretRef/secure store
[ ] repository-wide CI green
```

D9-02 and D9-03 are separately closable subtasks after D9-01.
