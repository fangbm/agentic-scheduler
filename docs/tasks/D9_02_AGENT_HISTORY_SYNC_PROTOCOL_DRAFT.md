# D9-02 — Agent conversation/history E2EE sync protocol draft

> Status: **PROPOSAL / REVIEW REQUIRED — NOT FROZEN**
> Branch: `docs/d9-02-protocol-draft` (review against `feature/d9-02-agent-sync`)
> Baseline: merged D9-01 `main`, PR #9 merge `1b273b1`.
> Authority: `docs/AGENT_DECISIONS.md` AGT-011–013; `docs/SYNC_SECURITY_DECISIONS.md` SYN-003/004/012/013/016; `docs/HISTORY_SYNC_DECISIONS.md` HST-001/006/007. The existing frozen decisions take priority over every proposal below.

## 0. Goal and guardrails

Synchronize the D9-01 `AgentThread` metadata, `AgentMessage`, `AgentToolCall`, `AgentToolResult`, final `AgentAction` audit and causal `AgentThreadDelete` across enrolled Android/Desktop replicas using **D8's existing opaque E2EE transport**. Provide deterministic replay and explicit conflicts without transferring Provider authority to the server.

MUST preserve:
- D8 server sees only routing metadata and ciphertext. No plaintext messages, tool arguments, schedule facts, summaries, or credentials in server storage or ordinary logs.
- D8's frozen Tink AES256_GCM, key epochs, `EncryptedEnvelopeV1`, AAD binding, authentication, cursor semantics, and existing v1/v2 business-mutation behavior.
- D7's business `MutationId`, ChangeLog, `SyncOperation` and causal truth. Replicating an Agent audit record does **not** replay its Tool or create a business mutation.
- AGT-013's immutable-ID append/dedupe/conflict rules, thread metadata title/lifecycle groups, and explicit concurrent delete-versus-message conflict.
- Durable thread tombstones; no tombstone/history compaction while OD-032 remains pending.

MUST NOT synchronize `ContextSummary`, local permission policy, `ProviderConfig`, provider credentials, remote provider call/session/cache IDs, secret-store references, or permission to execute pending Tool proposals. Wear Agent provisioning is D9-03; semantic retrieval, MCP, server-side Agent execution and Web Bridge remain out of scope.

**Important:** This draft records choices requiring approval. Do not implement a new wire version, merge rule, deletion resolution, or secret-sharing default until the relevant decisions in section 10 are frozen.

## 1. Existing implementation facts and the compatibility seam

- Current `SyncWireCodec` decodes `SyncPayloadV1` for non-Agent D7 business mutations and `SyncPayloadV2` solely for Agent-origin **business** mutations. V2 is **not** Agent conversation synchronization.
- `EncryptedEnvelopeV1` exposes `syncSpaceId`, `mutationId`, `senderDeviceId`, `keyEpoch` and opaque ciphertext; D8 authenticates that identity using its frozen v1 AAD. The thin server never decodes the inner payload.
- Existing D8 clients quarantine an authenticated unknown inner `payloadVersion` as `ProtocolUnsupported` for the *whole envelope ID*; they never partially apply its contents.
- D8 transport dedupes `(syncSpaceId, envelope.mutationId)`. The server cursor is a delivery position, not causal truth.
- D9-01 `AgentMessage.ordinal` is local sequence state with a unique `(thread_id, ordinal)` database constraint. Different devices may independently allocate the same ordinal: **remote records cannot be inserted as-is**.
- D9-01 `AgentToolCall` and `AgentAction` are locally updated as execution proceeds. AGT-013 requires immutable synchronized ID/value pairs. Transmitting these mutable local rows directly would violate that rule.

## 2. Proposed separately versioned payload (DECISION W1)

Keep `EncryptedEnvelopeV1`, existing D8 AAD/key handling and server HTTP endpoints unchanged. Propose a **separate conversation/history `SyncPayloadV3`** as the next inner payload version; retain V1 and V2 decode/encode semantics unchanged. The inner V3 body is `AgentSyncOperation`, **not** a D7 `SyncOperation` and not a new D7 `EntityMutation`. This version number and exact DTO layout require approval.

Candidate logical shape (illustrative, not a frozen serialized fixture):

| Field | Meaning |
| --- | --- |
| `payloadVersion = 3` | Dispatch exclusively to D9-02 Agent sync handler |
| `operationId` | Unique, immutable UUIDv7 **Agent sync operation ID** |
| `dvv` / `hlc` | Frozen D7/D8 snapshot representations and causal rules |
| `agentMutation` | Exactly one versioned, typed Agent event from section 3 |

Reuse the outer `mutationId` **routing field** for `operationId` to preserve the existing thin-server idempotency contract; this does *not* mean a conversation operation becomes a D7 business `MutationRecord`. Inner `operationId` MUST equal authenticated outer `mutationId`; mismatch is a whole-operation protocol failure.

All business and Agent operations emitted by one device in a SyncSpace MUST share **one per-replica dot allocator and received-causality ledger**. Distinct business and Agent operations must never reuse `(replicaId, dotCounter)`. This requires deliberate integration with the current D8 `SyncEngine` / journal and is not permission to build an unrelated Agent causal clock.

Old-client behavior: an older D8 client authenticates/decrypts V3, durably quarantines it as unsupported, advances its normal server cursor using existing semantics, and never deserializes a partial Agent mutation. New clients must continue to apply V1/V2 normally. Version capability negotiation and enabling new outbound V3 are reviewed in W4.

## 3. Proposed Agent operation vocabulary (DECISION W2)

Typed events below describe **replicated facts**, never executable commands. Their exact DTO fields, immutability and payload limits must be frozen before coding.

| Candidate type | Immutable identity / data | Receive behavior |
| --- | --- | --- |
| `ThreadCreated` | threadId, non-secret creation metadata | Create matching local thread projection or dedupe; conflicting identity => integrity conflict |
| `ThreadTitleSet` | threadId, title, causal context | Merge independent title group; concurrent unequal titles => explicit semantic conflict |
| `MessageAppended` | immutable messageId, threadId, role, content, original author/time | Append by ID; equal canonical value dedupes, differing value => integrity conflict |
| `ToolCallFinalized` | immutable callId, threadId, sourceMessageId, name, normalized proposed input, terminal outcome | Append a sanitized *terminal snapshot* by ID; no remote execution |
| `ToolResultAppended` | immutable resultId, callId, threadId, status, typed result, referenced business MutationIds | Append by ID, validate parent references; never apply referenced business writes |
| `ActionFinalized` | immutable actionId, thread/message/call/result IDs, terminal status, non-secret audit and MutationIds | Sync final audit fact only; business ChangeLog remains authoritative |
| `ThreadDeleted` | threadId, immutable delete operation ID, causal context | Retain tombstone, purge active raw thread projection where causally applicable; concurrent append => explicit conflict |

Proposed publication boundary:
- Local `PROPOSED`, `WAITING_CONFIRMATION`, `RUNNING` and in-progress `AgentAction` rows remain device-local **until** a terminal immutable sync snapshot can be emitted. If cross-device pending confirmation is desired, that is a separate security/permission decision, not an implicit result of this protocol.
- `providerCallId`, `providerConfigId`, selected model/provider session IDs and secret-store pointers must be omitted from synchronized records. Receiver may project safe optional metadata as `null`, but MUST NOT infer a usable local Provider configuration.
- `ToolResult` and `AgentAction` may reference D7 business MutationIds. Missing or locally unsynchronized business mutations MUST NOT cause a remote UI to assert that a business write exists. See W5.
- A remote `ToolCallFinalized` is historical evidence. The receiver MUST NOT automatically run the Tool, approve an operation, apply a PlanBranch, or change device-local permissions.
- `AgentMessage` and Tool payloads can contain private user content; ciphertext protects transport, but known credential/session fields are excluded **before** serialization. No generic claim is made that arbitrary user-entered secrets can always be detected and removed.

## 4. Replay, causal dependencies and projections

Shared receiver proposal:
1. Authenticate/decrypt through the **existing** D8 `AuthenticatedSyncEnvelopeCodec`. Authentication failure does not advance the cursor.
2. Dispatch decrypted V1/V2 to the existing business path; route a supported V3 to an Agent-specific handler using **shared** causal/handled-dot, quarantine, pending and cursor transaction semantics.
3. Validate the whole V3 operation before materialization: version, typed discriminator, outer/inner ID match, dot ownership/reuse, thread and parent IDs, field size limits and excluded secret-bearing fields.
4. Missing causal or typed-parent prerequisites stay in durable pending state; the server cursor is advanced only per the existing durable receive semantics. Bounded retry is required. Never create placeholder successful Tools, fabricated messages or an implicit thread resurrection.
5. Materialize authenticated facts using a transaction covering receive dedupe, conflict state, projection and cursor. No partial application of a malformed or conflicting operation.
6. Deduping compares a **canonical versioned sync DTO**, not the D9-01 local Room row (which contains local ordinal and mutable provider metadata). Same immutable ID + different canonical value => `INTEGRITY` conflict; do not overwrite.

The local `ordinal` field is **not** globally unique across replicas. The projection must retain immutable global IDs and provide a deterministic visible order without treating HLC, UUID, server cursor or arrival order as an authority/LWW rule. Exact schema/order projection and Room v12-to-next migration are decision P1.

Concurrent title edits use AGT-013's title group conflict semantics. Causal later title edits that observe conflicting predecessors may resolve only through a documented explicit resolution path; do not silently choose the latest title.

For `AgentToolResult`, `AgentAction` and their parent references, out-of-order arrival must preserve referential consistency. Business facts continue to synchronize exclusively through existing D7/D8 mutation semantics, never via the Agent audit projection.

## 5. Deletion, privacy and concurrent append (DECISION D1)

Frozen AGT-011/013:
- `ThreadDeleted` is a durable causal tombstone; accepted deletion removes active conversational messages, ToolCall/Result records and summaries while retaining committed `AgentAction`/D7 audit.
- A message causally older than an accepted tombstone cannot resurrect the thread.
- A delete concurrent with a new message produces an explicit durable `SyncConflict`. There is **no silent resurrection** and no timestamp-based winner.

Resolution semantics **remain to be approved**: how an owner explicitly resolves delete-vs-append (including whether preserving the competing message requires an entirely new thread ID), how a competing encrypted candidate is kept without restoring deleted active plaintext, and whether any inbound record after a tombstone is ever legal. Until approved, fail closed on the conflicted thread: no auto-reopen or Agent writes to it.

Deletion must also handle device-offline outbound queues: define how local pending historical content uploads are cancelled/retained and how delete DVV still represents observed history. The receiver must not assume every causally preceding envelope arrives first.

Physical tombstone compaction stays disabled (OD-032). Deletion is **not** guaranteed retroactive removal from historical server ciphertext, backups or previously compromised/offline devices. Do not advertise cryptographic erasure.

## 6. Outbound sequencing and operational safeguards

- Commit an immutable sync event and its outbound intent durably **before** network transmission. Reuse the exact stored envelope on retry to satisfy D8 ciphertext/idempotency semantics; never re-encrypt the same operation ID with different ciphertext after an uncertain upload.
- Coordinate business and Agent causal counters atomically. A failed local append must not reserve an emitted dot without its durable local causal outcome.
- Preserve D8 key epochs, key rotation/decryption ring, server authentication, envelope byte limits and bounded fetch semantics. For oversized plaintext, produce an explicit local unsyncable/size error until a separate fragmentation policy is approved; never truncate user conversation silently.
- A device that lacks a supported V3 peer/version policy must not silently downgrade a conversation to plaintext or reinterpret V3 as a D7 business V2 payload.
- If a finalized AgentAction points to a **local-only or not-yet-eligible** business mutation, defer publication/remote success claims according to an explicitly approved policy (W5). Existing user-owned D8 V2 Agent-write opt-in is still enforced and cannot be changed by an Agent.
- Existing thin server requires no plaintext Agent tables, inference endpoint or new encryption primitive.

## 7. Compatibility and persistence checklist

- Freeze exact V3 discriminators, serializers, semantic normalization rules, wire fixtures and end-to-end cross-version expectations (W1/W2).
- Add only the necessary local schema migration for immutable remote IDs, projection ordering, causal/tombstone/conflict state and outbound records (P1). Preserve every v12 local AgentThread, pending confirmation and existing committed audit record.
- Explicit old D8 receiver fixture: unknown V3 quarantined as a **whole** operation; later recognized V1/V2 business mutations continue to replay without cursor corruption.
- Verify same server relay accepts opaque V3 envelope without ever inspecting the conversation type/content. Run PostgreSQL transport and restart tests.
- Keep `ContextSummary` local-only; regenerate it locally from available authoritative transcript/context as appropriate. Never merge summaries as conversation facts.
- Cross-device newly enrolled replicas need a documented bootstrap/catch-up path and key epoch behavior; do not assume the server will retain messages beyond the frozen retention policy.

## 8. Required protocol and runtime acceptance matrix

| Category | Minimum adversarial evidence |
| --- | --- |
| Wire & security | V1/V2 round-trip unchanged; V3 success on new client; old-client whole-ID quarantine; unknown discriminator; outer/inner ID mismatch; wrong key/tampered AAD; size boundary |
| Idempotency | Duplicate identical ID/value; differing value for same immutable ID; duplicate operation ID with conflicting ciphertext; reused DVV dot with different ID |
| Causality | Mixed D7 business and Agent operations share unique dot stream; shuffled arrival; gaps and durable pending; repeat across reconnect and process restart |
| Merge | Parallel messages with same local ordinal; concurrent unequal titles; explicit delete-vs-append conflict; causally old append after delete never resurrects |
| References | Result before Call; Action before referenced Result; Action before business MutationId; all recover without inventing missing facts |
| Privacy | No ProviderConfig, credentials, providerCallId, local permissions or summary in V3 fixtures/logs; DB preserves committed AgentAction/D7 audit after delete |
| Migration | Upgrade real v12 file without destructive fallback; pristine install matches migrated schema; offline queued records survive app restart |
| Multi-device | Android <-> Desktop enrolled devices, offline edits/reconnect, duplicate/out-of-order fetch, deletion propagation, old D8 client quarantine |
| Reliability | Ciphertext retry identity, interrupted upload, key epoch rotation, server restart/PostgreSQL replay, isolation between SyncSpaces |

## 9. Staged implementation plan after protocol approval

1. **D9-02-00 — Protocol freeze:** resolve section 10 decisions; update AGT-013/SYN-003 amendment and canonical V3 fixtures. No runtime edits before this gate.
2. **D9-02-01 — Typed wire and compatibility:** shared/sync typed event DTOs and codec, V1/V2 regression, V3 unknown-old-client fixtures, AEAD/AAD tests.
3. **D9-02-02 — Persistence/migration:** local immutable outbound Agent event journal, canonical remote projection, unified causal allocator and real-file migration.
4. **D9-02-03 — Merge/tombstones:** idempotency, parent/gap ordering, metadata groups, durable delete conflict and approved explicit resolution.
5. **D9-02-04 — Transport/runtime:** reuse D8 encrypted upload/fetch/receive paths with a typed V3 receiver dispatch. No plaintext server Agent API.
6. **D9-02-05 — End-to-end gate:** Android/Desktop multi-device offline/reconnect, PostgreSQL, old-client compatibility, privacy, CI and documented evidence.

Protocol invariants and tests should land in separate reviewable commits. D9-03 Wear credential transfer remains outside this task.

## 10. Decisions requiring maintainer approval

These proposals are **not** approved merely because this draft exists.

| ID | Review choice required | Safe state before approval |
| --- | --- | --- |
| **W1** | Confirm separate V3 Agent history payload; operation ID carried by frozen V1 outer `mutationId` routing field; exact wire DTO/discriminators and unified dot namespace | No V3 encode or outbound upload |
| **W2** | Confirm terminal immutable ToolCall/Action snapshots, metadata sanitization and cross-device pending-confirmation behavior | No live/pending remote Tool replay; local-only pending states |
| **P1** | Define deterministic UI order for concurrent Agent messages and v12->next Room projection/migration (local ordinal collision); define canonical sync equality | No remote insert into existing unique `(thread_id, ordinal)` table |
| **D1** | Freeze explicit delete-versus-concurrent-append resolution and pending-outbound handling, including deleted candidate privacy | Durable conflict only; never silently resurrect |
| **W4** | Freeze V3 rollout/upgrade eligibility: independent user consent vs D8 active-space policy, old-client quarantine UX, support detection | Outbound V3 disabled |
| **W5** | Freeze how final Agent audit references business mutations not yet synchronized or barred by existing Agent V2 compatibility gate | Block/hold dependent remote audit; no false success claim |
| **L1** | Freeze maximum event text/tool payload size and behavior when a single V3 event exceeds D8 ciphertext limits | Explicit failure; no truncation/fragmentation |

Once these decisions are approved, update the canonical AGT/SYN task/decision docs, then start implementation. A CI-green codec is not sufficient without adversarial multi-device and retention testing.

## 11. Design review questions

- Does the product want conversations shared automatically in an enrolled SyncSpace, or an independent user-controlled switch? No silent change to user privacy or to the existing D9-01 Agent-origin business-write gate.
- Should in-progress proposals remain tied to their originating device until terminal (proposed), or does product scope explicitly require cross-device continuation with a separately specified approval protocol?
- On a delete-vs-append conflict, is restoring the competing content permissible only into a **new** thread identity? Define explicit user consent and the fate of stored conflict candidates.
- For remote audit referencing a business mutation withheld by compatibility policy, should the audit remain local until the mutation is eligible, or may a redacted status fact be shared? Never claim that a missing business write was applied.
