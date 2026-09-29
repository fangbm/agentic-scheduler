# Agentic Scheduler — D9 Agent Runtime & Typed Tool Surface

> Task ID: **D9-01 / D9-02 / D9-03**  
> Milestone: **D9 — Agent / Universal Command**  
> Status: **D9-01 FINAL CI PENDING — PR Draft — D8 COMPLETE**
> Date: 2026-09-12  
> Acceptance evidence updated: 2026-09-29
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

Current implementation inventory on this branch (D9-01 has completed its
code/test review and representative live acceptance; final branch CI and merge
remain pending):

- Shared Agent state/persistence and bounded provider-run orchestration are
  present, including persisted messages, ToolCalls, ToolResults, AgentActions,
  transcript reconstruction, context assembly/budgeting, and compaction.
- The typed Tool implementation now includes calendar/task/history reads;
  Event create/update; Task create/update; Full Replan and Local Reflow
  previews; PlanBranch apply; PlanningProfile update; and history Undo.
  These Tools route through existing application/Planner operations and
  confirmation/preview paths. Their presence does not establish completion of
  the full AGT-004 acceptance matrix.
- Agent-origin writes retain the D8 inner-payload-v2 compatibility gate and
  device-local all-devices-upgraded opt-in. This implementation does not
  authorize synchronized Agent writes by default. Agent-triggered D7 Undo
  keeps its compensating `Undo` origin while using that same trusted gate;
  a missing acknowledgement cannot use Undo as a compatibility bypass.
- Android and Desktop contain in-progress Universal Command surfaces wired to
  the persistent Agent run and confirmation flow. Cross-platform
  usability/acceptance is open. Android restores a durable
  `WAITING_CONFIRMATION` call when its conversation is selected, rather than
  relying only on in-memory dialog state. Session-local PlanBranch proposals
  are scoped to their AgentThread.
- Verification status: PR #9 head `a96cc04` passed all four CI jobs (Linux
  build/tests, Windows Desktop, Android Keystore, and Wear Keystore) in run
  [`36523454147`](https://github.com/fangbm/agentic-scheduler/actions/runs/36523454147).
  This run includes the supported Compose desktop regression assertion for
  retaining a PlanBranch preview across a calendar recomposition.
- Android 16 physical-device read-only Provider E2E was exercised with
  DeepSeek Flash. The credential was resolved from Android secure storage,
  requests used HTTPS at `api.deepseek.com`, and the structured-tool capability
  probe succeeded. The Agent made a structured `history.timeline` call
  (`limit: 20`), persisted/displayed the Tool call and Tool result (`[]`), sent
  that result back to the model, and received a final response reporting no
  changes. No business data mutation was made. This is read-only runtime
  evidence only; it does not establish write preview, user confirmation, or
  write execution acceptance.
- Android 16 physical-device write acceptance was subsequently completed with
  DeepSeek Flash. An initial response containing multiple proposed Tool calls
  was rejected by the runtime and produced no business write. A later single
  `task.create` proposal displayed a confirmation preview for
  `D9 confirmation execution test`: `LOW` priority, five-minute estimated and
  remaining effort, and no deadline. After explicit user confirmation, the
  app persisted the Task as `OPEN` / `LOW` with five-minute estimated and
  remaining effort and no deadline. The ToolResult was `SUCCESS`; the linked
  AgentAction was `SUCCEEDED` with `REQUIRE_CONFIRMATION`. The same MutationId
  linked that action to a `MutationRecord` with origin `AGENT:<action>`, a
  `ChangeLog` `TaskPut`, and a sync journal entry. No active SyncSpace was
  present, so this write remained local and was not synchronized to a server.
  This verifies the Android provider → typed Tool → preview → explicit
  confirmation → application write → audit/journal path for this Task create.
  It does not establish Desktop visible UI acceptance or broader write-tool
  acceptance.
- Other Android device evidence covers the provider configuration surface,
  missing-field error, responsive action rows, and thread-deletion confirmation
  content.
- Windows Desktop visible acceptance was exercised on 2026-09-29 with a real
  HTTPS DeepSeek Flash provider credential held by the Desktop secret-store
  path. A structured `task.list` round trip rendered the authoritative Task
  result. A confirmation-required `task.create` showed its normalized
  before/after facts and, after confirmation, produced the expected Task.
  A permission-denied Event create and an invalid Event range produced no
  write; the latter exposed `time:INVALID_RANGE`. A controlled Provider
  `HTTP_400` was surfaced as a structured unavailable error, after which a
  restored Provider completed a fresh structured read. Deleting a disposable
  conversation removed it from the local conversation list while the previously
  committed Task remained visible.
- Desktop also exercised durable confirmation and Planner behavior. A pending
  `task.create` confirmation survived an application restart; denying it then
  reported no write and left no task with that title. Full Replan Preview was
  exercised with both Cancel and Apply. After the source Task changed, Apply
  rejected the retained proposal with `PlanBranch is stale; preview again
  before Apply.` No Provider secret, credential value, or sensitive transcript
  is recorded in this evidence.
- The four final representative paths were exercised on Android 16 on
  2026-09-29 with the configured real HTTPS DeepSeek Flash Provider and the
  visible local Planner UI. A confirmation-gated `task.update` completed
  against the current Task and a separate supported `history.undo` restored
  the prior state; a confirmation-gated `event.update` and its supported Undo
  were also exercised. Local Reflow produced a real session-scoped PlanBranch
  from the current persisted FocusBlock, including the stale-branch retry
  behavior before an actionable preview was produced. A
  `planningProfile.update` proposal displayed its confirmation preview and
  completed after explicit confirmation. The evidence contains no Provider
  credential, secret, or sensitive transcript.
- The Android Provider form now makes credential removal and replacement
  mutually exclusive: selecting removal clears and disables the credential
  field. It also rejects credentialed HTTP URLs before resolving or importing
  a credential. This is local UI validation; end-to-end Android/Provider
  acceptance remains covered by the broader D9-01 gate.

The recorded evidence is intentionally representative rather than a claim that
every Typed Tool was manually exercised against a live Provider. The complete
AGT-017 deterministic matrix is reviewed below, and the final four required
live representative paths are now recorded above. Agent-origin synchronized
writes remain disabled without the D8 all-devices-upgraded acknowledgement;
D9-02/D9-03 remain out of scope. The only remaining D9-01 release gate is a
green repository-wide CI run on the final rebased PR head, followed by review
and merge.

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

# 15A. AGT-017 review record — 2026-09-29

This is a code-and-test review record, not a replacement for the required
visible Android/Desktop acceptance.  It keeps the completion gate honest by
separating deterministic/fake-Provider evidence from an exercised Provider
and platform UI path.

| AGT-017 criterion | Deterministic evidence | Review result |
| --- | --- | --- |
| Fake Provider cannot bypass the Tool layer | `AgentRunIntegrationTest` — `prose unknown tools and failed capability probe cannot bypass registry` | Covered |
| Model prose cannot create an implicit write | Same test; it leaves Tasks and history empty | Covered |
| Read Tools perform zero writes | `TaskReadToolsTest`, `HistoryReadToolsTest`, `CalendarListToolTest`, and provider-registry integration cases | Covered |
| Default write policy requires confirmation | `AgentPermissionPolicyTest`; typed write Tool tests | Covered |
| Denied/dismissed confirmation performs zero writes | `AgentRunIntegrationTest` — `denied and stale model proposals never write` | Covered |
| Stale PlanBranch cannot apply through Agent | `AgentRunIntegrationTest` — cross-thread rejection; Planner Tool stale tests | Covered |
| ToolResult truth matches transaction truth | `AgentPersistenceTest` and Task/Event provider-to-Room integrations | Covered |
| AgentAction references committed MutationIds | Task/Event provider-to-Room integrations and Planner Apply callback test | Covered |
| ContextSummary cannot override current facts | `ContextAssemblerTest` plus current-fact integration assertion | Covered |
| Deterministic budget caps/priorities | `ContextAssemblerTest` mandatory/cap ordering cases | Covered |
| Compaction failure preserves raw history | `ContextCompactionTest` and runtime compaction integration | Covered |
| Provider switch preserves AgentThread continuity | `AgentPersistenceTest` — provider switch transcript/tool pairing | Covered |
| Secrets never enter logs/context/sync payloads | `OpenAiCompatibleProviderTest` credential/HTTP/redaction cases | Covered |
| Thread deletion preserves committed audit history | `AgentPersistenceTest` fresh-v12 retention case | Covered |
| Repository CI is green | CI `36533149712` passed for commit `5835a40`; any later code/test commit must re-run CI | Pending rerun after later commit |

Focused local execution on 2026-09-29 also added two missing Provider-registry
integration assertions:

```text
same AgentThread Full Replan preview -> confirmation -> Planner Apply callback
Local Reflow Provider call -> typed registry -> session-scoped PlanBranch, zero writes
```

Both are in `AgentRunIntegrationTest` and passed locally with the database
desktop test target.  The Planner Apply fixture proves runtime routing,
confirmation, AgentAction and ToolResult wiring; the existing D6 application
and Desktop manual path remain the proof of the real Planner transaction.

## Typed Tool acceptance matrix

The required Tool unit matrix is present for the complete D9-01 surface.  The
following distinguishes its implementation/automated coverage from live
Provider/platform exercise; an untested live row must not be inferred from a
passing fake Provider test.

| Tool | Typed/automated coverage | Real Provider + visible platform evidence |
| --- | --- | --- |
| `calendar.list`, `task.get`, `task.list` | Unit + Provider-registry integration | Desktop `task.list` exercised; remaining read variants not individually live-tested |
| `history.timeline`, `history.getMutation`, `history.getEntityChanges` | Unit + Provider-registry integration | Android `history.timeline` exercised; remaining variants not individually live-tested |
| `event.create`, `task.create` | Unit + Provider-to-Room confirmation integration | Desktop confirmed Event/Task create exercised |
| `event.update`, `task.update` | Unit/Room coverage; Task Update runtime stale/commit integration | Android real-Provider confirmation-gated Task Update; separate Event Update also confirmed |
| Full Replan, Local Reflow preview | Unit + Provider-registry integration | Desktop Full Replan exercised; Android Local Reflow produced a real session-scoped PlanBranch and handled stale-preview retry |
| `planner.applyBranch` | Unit + Provider-registry confirmation integration | Desktop preview, apply and stale rejection exercised |
| `planningProfile.update` | Unit/Room coverage | Android real-Provider proposal, visible confirmation, and confirmed update exercised |
| `history.undo` | Unit/Room coverage plus unsupported runtime result | Android supported Undo exercised after confirmed Task Update; separate Event Update Undo also exercised |

The four required live representative paths (`event.update` or `task.update`,
Local Reflow, PlanningProfile update, and supported Undo) are complete. The
AGT-017 code/test review and live representative acceptance are therefore
complete; final repository CI on the rebased PR head remains required before
marking the PR Ready. This does not relax AGT-017, the independent OD-012
production-data gate, or the separate D9-02/D9-03 scope gates.

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
