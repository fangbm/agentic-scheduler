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

# AGT-013 — D9-02 synchronized Agent history / APPROVED AMENDMENT

Status: **FROZEN — maintainer approved 2026-09-30**, D9-02-00.
Recorded approval: the seven W1/W2/P1/D1/W4/W5/L1 policies in
`docs/tasks/D9_02_PROTOCOL_FREEZE_PACKET.md`. This is an architectural
policy freeze; exact V3 serializer/fixture and migration implementation
acceptance are separate mandatory gates, not silent discretionary changes.
D9-01's local Agent runtime and Agent-origin **business** SyncPayloadV2
compatibility gate remain unchanged.

Synchronized historical concepts: `AgentThread` metadata, immutable
`AgentMessage`, terminal `AgentToolCall`/`AgentToolResult` snapshots,
finalized `AgentAction`, `TurnFinalized` and causal
`AgentThreadDelete` tombstones. Never synchronize `ContextSummary`,
local permission policy, `ProviderConfig`/credentials/SecretRefs,
provider call/session/cache IDs, or executable remote confirmation state.
Receiving any historical record never executes a Tool or business mutation.

W1 — **separately versioned Agent SyncPayloadV3** holds one immutable typed
`AgentSyncOperation`. Preserve outer `EncryptedEnvelopeV1`, exact v1
AAD, Tink AEAD/key epochs, opaque D8 server and V1/V2 business semantics.
The authenticated outer routing `mutationId` equals the V3 unique UUIDv7
`operationId`, but it is **not** a D7 business MutationRecord. Each
device/SyncSpace persists a **distinct Agent replica UUIDv7, Agent-only
DVV/handled-dot ledger and pending graph**. Never mix Agent dots into D7
business causal context or vice versa; refer to D7 business operations
only by explicit immutable MutationIds. Unknown V3 is whole-ID quarantined
by old D8. After upgrade, backfill quarantined V3 from the earliest
quarantined server cursor minus one, without replaying business effects;
report missing ciphertext/key as incomplete history. Existing D9-01
Agent-origin V2 **business** all-devices-upgraded gate still applies.

W2 — Only final immutable snapshots of ToolCall/ToolResult/AgentAction are
transmitted; pending confirmation remains local to its origin device.
Each completed (including explicitly failed) turn has immutable
`TurnFinalized(turnId, parentTurnIds, ordered manifest, terminal outcome)`.
Stage incoming members invisibly and expose the **whole** verified,
causally eligible turn atomically to Agent transcripts and context.
Concurrent sibling turns are explicit forks: stable UI-only ordering is
permitted, but no merged-thread Provider continuation before approved
explicit reconciliation. HLC, UUID and server cursor never select a
semantic dialogue winner.

P1 — Migrate Room v12 non-destructively to immutable Agent inbox/outbox,
Agent causal/frontier, manifest/staging/projection, tombstone, audit-link,
conflict and backfill state; preserve old local pending confirmations and
audit. Existing local message ordinals are **not** globally unique; never
insert remote ordinal into the unique local (thread_id, ordinal) constraint.
Immutable identity equality uses canonical **versioned typed wire DTO
fields**, not Room rows or ciphertext hashes. Same ID/equal value dedupes;
same ID/different value is an integrity conflict. A separate typed Agent
conflict reference must not silently extend D8's business EntityKind.
Dialogue ancestry determines semantic sequence; HLC/ID are merely stable
presentation tie-breakers for incomparable branches.

D1 — Retain causal `AgentThreadDelete` tombstones and handled-dot/ID
metadata; purge **active** message/call/result/summary content after
accepted deletion, retain sanitized finalized `AgentAction` and committed
D7 audit. Older late appends cannot resurrect; concurrent delete/append
produces an explicit durable semantic conflict and blocks Provider runs
on the disputed thread. Explicit owner resolution supports **keep
deletion** or importing competing content into a **new thread ID**, never
silent revival of the deleted ID. Resolution must observe all conflicting
Agent dots; exact resolution-event DTO must be fixed in canonical fixtures.
References to intentionally deleted raw parents may be marked
`PARENT_REMOVED_BY_TOMBSTONE`; unrelated missing parents remain pending
or fail integrity. Competing raw content stays outside active transcripts
in restricted conflict storage, with OD-012 at-rest caveats. A delete
cannot discard unpublished causal predecessors or silently upload erased
private content after deletion: if a causally complete safe upload is
unavailable, mark remote deletion propagation explicitly pending until
approved consented completion or an independently approved gap mechanism.
OD-032 still forbids physical tombstone/history compaction; deletion is
not retroactive cryptographic erasure of old ciphertext/backups.

W4 — Agent conversation sync is **separately user-controlled per
SyncSpace**, OFF by default and independent of the V2 business-write
upgrade acknowledgement. Initial rollout requires owner acknowledgement
that all active enrolled devices are V3 capable. This is **not**
cryptographic client-version attestation by the opaque server. Without
conversation consent, never project inbound V3 to readable active
conversation; retain only approved protocol/quarantine/backfill state for
later explicit consent. No automatic retrospective upload of D9-01
conversation history: offer distinct opt-in export of **completed,
sanitized** historical turns; origin-device pending confirmations stay
local. Upgrade and new-device recovery use retained encrypted history
and the existing historical decrypt keyring; missing history/key must
surface explicit incomplete recovery.

W5 — Held/unauthorized Agent-origin V2 business mutations **and every
causally dependent D7 business successor** remain pending until eligible;
never skip dots, rewrite DVVs or bypass existing consent. Refactor worker
scheduling so held outbound does **not block independent authenticated
inbound fetch and receive**, which reports status independently. A
completed V3 turn depending on unshared D7 business MutationIds is held
as a **whole**, including final audit/manifest. Unrelated consented V3
turns may progress. Received Agent audit never fabricates a remote
business write: verify referenced facts exclusively in D7 state.

L1 — First alpha carries **one V3 event per envelope** and permits at
most **262144 encoded V3 plaintext UTF-8 bytes** before encryption, also
respecting potentially lower configured server ciphertext/HTTP bounds.
Oversized event is an explicit `AGENT_SYNC_PAYLOAD_TOO_LARGE` failure;
no silent truncation or implicit fragmentation. Preserve exact durable
ciphertext for idempotent retries.

**Implementation prerequisites after this policy freeze:** freeze exact
typed event/manifest/resolution DTO serializations and JSON compatibility
fixtures; specify non-destructive migration and bounded receive paths;
verify old-D8 quarantine then independent V1 processing, historical
backfill, partial-turn barriers, held-outbound/inbound progress, causally
safe deletion and adversarial multi-device E2EE tests. These technical
fixtures cannot silently amend the frozen choices above.

## Approved D9-02 follow-up amendment — C1 / D2

Maintainer review on PR #20 explicitly resolved C1 and D2 on 2026-09-30;
the normative details are recorded in
`docs/tasks/D9_02_PROTOCOL_FREEZE_PACKET.md` under “Maintainer follow-up
amendment — C1 and D2”.

**C1 — Agent clock:** counters begin at 0 and the persisted local counter is
the next value to allocate. Local dot, operation/outbox, and next-counter
update are atomic. A local counter greater than 0 observes its own preceding
counter. Missing per-replica frontier means conceptual -1; only consecutive
durably handled inbound dots and durably authored local dots advance the
Agent frontier. Context dependencies clear only when covered by that
contiguous frontier. Agent causal state remains separate from D7.

**D2 — delete conflict resolution:** the frozen semantic event is
`ThreadDeleteConflictResolved(threadId, participantOperationIds,
resolution, replacementThreadId)`, with unique lexicographically sorted
participant operation IDs, causal observation of every participant dot,
explicit enrolled-device user authorship, persistent original tombstone, and
no LWW for conflicting resolutions. `COPY_CONTENT_TO_NEW_THREAD` creates
only fresh Agent content identities after the resolution is accepted; it
never replays Tool execution or business mutations. D9-02-02 records this
decision. D9-02-03 implements the DTO/Codec and merge/projection within the
D2 scope. OD-012 remains a separate production receive/storage release gate.

---

# AGT-018 — D9-02-05 historical export provenance / FROZEN

Maintainer approval on 2026-10-03 authorizes a non-destructive Room v14→v15
migration, prospective local thread/turn provenance, and a separate explicit
historical export using only the existing frozen V3 events/outbox/transport.

Every pre-v15 thread is `LEGACY_UNVERIFIED`: never infer turns, membership,
ancestry, outcomes, creation title, or `TurnFinalized` from ordinal, timestamp,
adjacency, tail assistant, or any other heuristic. Do not create legacy export
markers. A post-v15 thread is exportable only with a tracked immutable creation
snapshot. Runs durably track a stable turn ID, exact ancestry, ordered actual
message/finalized ToolCall/ToolResult/Action identities, lifecycle and terminal
outcome. Awaiting confirmation stays incomplete; cancellation, crash, exception,
or absent completion write cannot be retroactively finalized. An ancestry gap
taints that descendant lineage for export but does not block local Agent use.

Only provenance-verified finalized turns may be converted to the frozen V3 DTOs.
Exclude ProviderConfig and credentials/SecretRefs, provider call/session/cache
IDs, permission/confirmation/PlanBranch metadata, ContextSummary, pending or
nonterminal calls/actions. Preserve existing stable IDs where the V3 type allows.
No V3 wire changes. Conversation consent alone never exports history; export is
a second explicit user action requiring active enrollment and consent. A
durable per-SyncSpace source-fact mapping stores the first chosen operation ID,
HLC and canonical sanitized event before publication; retries and duplicate
clicks reuse it and the existing Agent outbox. Do not upload directly or create
a second transport. Existing W5 whole-turn D7 dependency holds continue.

Threads deleted before export and their content are ineligible; provenance
metadata never restores deleted history. Existing D1 tombstone rules apply to
history already synchronized. OD-012 remains the independent production local
database protection / sensitive V3 release gate.

## OD-057 — D9-02-05 historical export provenance

```text
Status: RESOLVED FOR D9-02-05
Decision: pre-v15 history is always LEGACY_UNVERIFIED and unexportable;
          prospective v15 provenance only; export requires an explicit
          second user action with durable idempotency mapping; an unverified
          ancestor taints descendants; no wire amendment.
Source: docs/AGENT_DECISIONS.md AGT-018 and maintainer decision 2026-10-03
```

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

D9-03-00 audits these inherited boundaries in `docs/tasks/D9_03_00_WEAR_AGENT_PROVIDER_FREEZE.md`. Its exact provisioning wire/delivery/source-authentication/revision/install and first-alpha capability/permission proposals are **PENDING (OD-058)**, not a new approved amendment. STT remains optional; no Phone Agent proxy or Provider abstraction change is authorized.

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
