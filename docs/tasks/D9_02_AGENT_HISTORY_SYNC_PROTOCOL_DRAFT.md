# D9-02 — Agent conversation/history E2EE sync protocol

> Status: **REVISED PROPOSAL v0.2 — REVIEW REQUIRED / NOT FROZEN**  
> Basis: D9-01 merged in `fangbm/temvio` at `1b273b1`, with AGT-011–013, SYN-003/004/012/013/016 and HST-001/006/007 as binding prior decisions.  
> Supersedes: D9-02 draft in PR #18; explicitly addresses four review findings. Do not treat this proposal as authorization for new cross-device security or merge policies before the maintainer freezes W1/W2/P1/D1/W4/W5/L1.

## 0. Goal, ownership and exclusions

Synchronize `AgentThread` metadata, `AgentMessage`, completed `AgentToolCall`, completed `AgentToolResult`, finalized `AgentAction` and causal `AgentThreadDelete` between enrolled Android/Desktop replicas. Preserve D8's existing opaque E2EE transport: unchanged `EncryptedEnvelopeV1`, exact AAD and key-epoch handling, Tink AES256_GCM, current authenticated server transport, server cursor and business V1/V2 semantics. The server never sees conversation plaintext, DVVs or tool arguments.

A synchronized Agent record is a historical **fact**, not a Tool invocation, permission grant or business mutation. D7's `MutationId`, business `SyncOperation`, ChangeLog and Planner remain authoritative. Receiving an Agent Tool result or Action must **not** execute its Tool or apply the referenced D7 business write.

Never synchronize `ContextSummary`, device-local permission policy, `ProviderConfig`, API credentials/secret references, provider call/session/cache IDs or execution-capable pending confirmation objects. D9-03 Wear provider provisioning, semantic embeddings, external MCP, server-side Agent execution and Web Bridge are out of scope. Retain thread tombstones and necessary causal history while OD-032 prohibits compaction. Historical encrypted bytes/backups cannot be promised retroactively erased.

## 1. Wire contract and P0 correction: separate Agent causality (W1)

**Proposal:** add separately versioned inner `SyncPayloadV3`, containing one typed `AgentSyncOperation`. Existing V1 = non-Agent D7 business operations and V2 = D9-01 Agent-origin **business** operations, unchanged. V3 is **not** a D7 `SyncOperation` or `EntityMutation`. Existing outer v1 `mutationId` routing value equals the V3 `operationId` (unique UUIDv7) for unchanged server idempotency. The inner and authenticated outer IDs must match. Proposed V3 fields are `payloadVersion=3`, `operationId`, `agentDvv`, `hlc`, and exactly one typed `agentEvent`; freeze byte-level DTO fixtures before implementing.

**Do not use the earlier proposal of one shared business/Agent dot allocator.** Each V3-capable replica has a durable **distinct Agent replica UUIDv7** for its SyncSpace, different from its D7 business `ReplicaId`. Its V3 `agentDvv` contains **only** Agent replica IDs. Existing V1/V2 business DVVs contain **only** D7 business replica IDs. Allocate and retain Agent dots, handled frontier and pending dependencies in a separate Agent causal journal. Reuse pure D7 DVV/HLC algorithms without writing V3 dots into D7's journal, `SyncEngine.missingCausalPrerequisites`, business outbound context or business handled-dot ledger. Lost Agent clock state requires a new Agent replica ID, never a reused counter.

Cross-stream relationships use **explicit referenced D7 business MutationIds**, not mixed DVV components. A V3 Action pointing to a business mutation must await/verify the actual D7 fact under W5; its Agent causal dot alone does not prove the write exists remotely.

**Required mixed-version proof:** new device emits V3 A1, then V1 business B1. An old D8 client quarantines unknown A1 without learning its dot; B1's D7 context excludes A1, so B1 is still eligible for application if its D7 prerequisites are present. This does **not** repeal D9-01's separate all-devices-upgraded opt-in for V2 **business** writes. A genuine unknown/withheld V2 business predecessor can still block a dependent business suffix.

**Delayed upgrade replay:** an old client may have advanced its D8 cursor past quarantined V3. Its quarantine record retains server cursor/unsupported-version detail. After upgrading, a **separate Agent backfill** must retrieve retained encrypted history beginning no later than `earliestQuarantinedV3Cursor - 1`, decrypt V3 with D8's historical keyring and dedupe by Agent operation ID/dot; never rewind or reapply already-committed V1/V2 business effects. A newly enrolled device bootstraps V3 from the approved retained-history starting position. If historical envelopes or epoch keys are unavailable, return an explicit incomplete-recovery state, not an apparently complete transcript. This recovery flow is not already implemented by D8 and is in scope for D9-02.

W4 still controls V3 outbound eligibility and old-device UX. Distinct clocks prevent accidental mixed-version V3 delivery from deadlocking **business causality**; they do not make an old client capable of displaying conversations or eliminate the need for user consent.

## 2. Immutable event vocabulary and P1 correction: sealed turns (W2)

One V3 envelope carries **one immutable typed event**. Canonical comparison uses a normalized versioned Agent-sync DTO, not mutable D9-01 Room rows or ciphertext hashes.

| Event | Immutable contents and interpretation |
| --- | --- |
| `ThreadCreated` | Thread ID plus safe creation metadata; same ID/different value is an integrity conflict. |
| `ThreadTitleSet` | Title-group change with Agent DVV. Concurrent unequal title changes create an explicit semantic conflict. |
| `MessageAppended` | Message ID, thread ID, **turnId**, role, content and origin time; **stage only** until turn completion. |
| `ToolCallFinalized` | Call ID, thread/turn/source message IDs, typed Tool name, normalized inputs, **terminal** status; stage without execution. |
| `ToolResultAppended` | Result ID, call/thread/turn IDs, final status/result and referenced D7 MutationIds; stage without business writes. |
| `ActionFinalized` | Sanitized final Action ID/status/linked IDs and business MutationIds; independent durable audit, not proof of a remote business change. |
| **`TurnFinalized`** | Turn ID, thread ID, **parentTurnIds**, ordered complete manifest of the required message/call/result/action IDs and terminal outcome. |
| `ThreadDeleted` | Immutable, durable causal tombstone; suppress older appends and conflict on concurrent append. |

A turn spans one originating command and its finished Provider/Tool cycle, potentially with multiple Tool steps. A terminal Provider failure may seal an **explicit failed/user-only turn**, not an invented success. Local `PROPOSED`, `WAITING_CONFIRMATION`, `RUNNING` ToolCall/AgentAction states remain on their originating device; synchronize only immutable terminal snapshots.

**Visibility barrier:** incoming messages/calls/results are first stored in a **staging inbox**, not directly in D9-01's live `agent_message`, `agent_tool_call` or `agent_tool_result` tables. A `TurnFinalized` is not visible until all listed immutable members, mandatory Agent DVV prerequisites, source/parent relationships and manifest integrity checks pass. Then atomically project the **entire completed turn** to the active transcript. Prior to that, it is `TURN_INCOMPLETE`: neither `AgentTranscriptAssembler`, ContextAssembler nor new Provider runs may consume it. Pending state must survive restarts, replay, duplicates and out-of-order delivery. Later records cannot modify a sealed turn; unequal same-ID content causes an integrity conflict.

`parentTurnIds` represent the **dialogue ancestry observed by the author**, not merely display order. Two concurrent complete child turns from the same ancestry produce an explicit thread-fork/concurrent-turn conflict. UI may display them in deterministic non-authoritative order, but the merged thread cannot run another Provider until an approved branch/reconciliation policy resolves the fork. HLC, UUID and server cursor are display tie-breakers only, **never semantic winners**. Remote paired tool transcript reconstruction uses safe application call IDs, not originating `providerCallId`.

Validate and exclude provider identity/session metadata and secret references *before* V3 serialization. User-authored text can contain pasted secrets and is E2EE-protected, but no system can promise perfect automatic secret recognition in arbitrary content.

## 3. Receive, dedupe, projection and parent references (P1)

1. Authenticate/decrypt through **existing** D8 AEAD. Wrong key, tampered AAD or failed authentication must not advance the cursor.
2. Dispatch authenticated V1/V2 to existing business `SyncEngine`; dispatch V3 to the new Agent-specific handler **before** legacy business-only `SyncWireCodec.decodePayload`. Old D8 continues durable *whole-envelope-ID* quarantine on unknown V3.
3. Validate V3 version/discriminator, inner/outer ID equality, Agent replica dot ownership, typed payload/size limits and forbidden metadata. Persist Agent dedupe/handled dots, pending state, conflicts, projection and compatible receive cursor outcome transactionally. Agent-dot prerequisites and typed-parent/manifest prerequisites are **different pending reasons**; durable retries are bounded.
4. Equal same-ID canonical event -> idempotent. Same immutable record ID with unequal canonical value -> integrity conflict. New operation ID with a reused Agent dot -> invalid. Event operation ID replay and record entity ID conflict are separate checks. A malformed event is not partially applied.
5. A received Agent Action's business MutationId references are lookup links: `BUSINESS_REFERENCE_PENDING`, D7-history `VERIFIED_LOCALLY`, or an explicitly permitted `SOURCE_ONLY` display state under W5. A reported source success never implies remote Task/Event state exists.

**Deleted audit links:** A live complete turn has **strict** references to all manifest-declared records. `AgentAction` is a separate durable historical audit and has **non-strict** links to raw conversations after an accepted thread tombstone. If a valid tombstone proves a referenced message/call/result was intentionally purged, retain the sanitized Action's immutable IDs, status and MutationIds, and mark that link `PARENT_REMOVED_BY_TOMBSTONE`; do **not** permanently pend the Action, restore raw content or fabricate a business fact. Missing parents **without** a valid matching tombstone remain `PARENT_PENDING` or integrity failures, never casually excused. Keep immutable Agent handled-dot/ID/tombstone metadata after deletion so delayed old events cannot resurrect a thread.

D8 `SyncConflictEntityRef` currently has business-only `EntityKind`; P1/D1 must freeze a typed Agent-thread conflict representation rather than mislabeling Agent threads as business entities or silently changing frozen D8 business wire DTOs.

## 4. Deletion conflict, offline queue and privacy (D1)

An accepted `ThreadDeleted` removes **active** thread/message/call/result/summary projections, retaining finalized `AgentAction`, D7 ChangeLog, Agent handled-dot metadata and durable tombstone. Causally older appends, even if delivered late, cannot resurrect. A concurrent append or completed turn versus deletion produces an explicit **semantic SyncConflict**, never a timestamp-based winner.

Proposed fail-closed initial behavior (requires approval): while a delete/append conflict is open, block new Provider runs on that thread and keep competing content in restricted **local conflict storage**, not the active transcript. Explicit user resolution may choose (a) keep deletion, or (b) if separately approved, import surviving content into a **new thread identity** rather than resurrecting the deleted ID. Freeze the conflict resolution operation, privacy handling of stored candidates and cross-device convergence before implementation. OD-012 remains an independent production at-rest encryption decision; do not claim unapproved disk protection.

Deleting an offline thread cannot silently discard pre-delete queued Agent events whose Agent dots are prerequisites of the delete tombstone. Either deliver causally required pre-delete events in a safe, durable order, or freeze a separate authenticated metadata-only causal-gap mechanism. **Never** renumber committed dots, mark unseen predecessors handled, upload a tombstone with impossible prerequisites or promise retroactive erasure of historical encrypted envelopes/backups. Physical tombstone/history compaction remains disabled by OD-032.

## 5. P0 correction: held V2 must not block inbound; W4/W5

Current `SyncTransportWorker.run` immediately returns before fetching when a local Agent-origin V2 **business** mutation lacks the D9-01 device-local all-devices-upgraded acknowledgement. D9-02 must split **outbound eligibility/scheduling** from **inbound catch-up**. A held V2 is recorded as `OUTBOUND_HELD_COMPATIBILITY`, not an all-direction sync failure; independent authenticated inbound fetch must continue and report its own progress, unless a genuine inbound security/key failure prevents it. This is a **required later runtime change**, not something fixed by this documentation draft.

For D7 business outbound, publish only an operation whose **entire business-DVV dependency closure** is legitimately published/handled in that SyncSpace. An unapproved V2 and **every local business successor depending on its dot** remain durably held; never skip or rewrite committed causal predecessors, bypass the original V2 gate, or label a held operation uploaded. Independently eligible business operations may proceed only with a provably closed business causal history. A cumulative local D7 suffix may necessarily remain held until the owner authorizes V2 or a distinct approved recovery policy is introduced; document this limitation.

Agent V3 uses the **separate Agent DVV**, so an unrelated completed conversation turn with no references to withheld D7 writes can publish if W4 consent/version eligibility permits it. **W5 proposal:** hold a dependent turn's **entire** final audit and visible manifest while referenced D7 mutations remain unshared/unsupported in that space; unrelated turns may progress. Only D7-confirmed facts may be shown as applied on the receiving device. A future `SOURCE_ONLY` redacted audit option would require its own approved wire/UI semantics; emitting a manifest with missing mandatory Action links is not acceptable.

**W4 rollout proposal:** enable V3 conversation sync via a **separate user-owned per-SyncSpace choice**. First release can require acknowledgement that all *active enrolled replicas* are V3-capable; explicitly test device enrollment, revocation and rollback. The opaque server cannot inspect inner payload versions or attest build versions, so do not claim it enforces this policy. An old D8 client may quarantine an accidentally received V3 without breaking independent V1/V2 business causality, but its conversation view is incomplete until upgraded/backfilled. Never equate V3 consent with the existing Agent-origin V2 business write opt-in.

Propose **no automatic retroactive upload** of existing D9-01 local history. Offer separate, explicit opt-in import/export of completed sanitized old turns only, with stable record IDs and durable export markers; retain local pending confirmations. For newly enrolled or upgraded devices, document historical V3 envelope catch-up and keyring failure states. Reuse exactly stored ciphertext on retry: never re-encrypt the same operation ID after an uncertain upload.

## 6. Local persistence and compatibility (P1, W1)

D9-01 Room v12 enforces unique `(thread_id, ordinal)`; two devices may assign the same local ordinal. A **non-destructive v12->next migration** must introduce immutable remote event/turn staging, manifest and visibility state, separate Agent replica clock/handled ledger, delete tombstones, Agent conflict references, audit-parent reference state and V3 historical backfill cursor. Do **not** insert remote local ordinals directly. Materialize local order first by declared causal turn ancestry; use HLC and stable IDs **only for deterministic presentation of semantically concurrent content**, never as dialogue truth. Fresh install schema must match the migrated schema, preserving old local pending confirmations and committed audit.

Freeze exact V3 serializers, normalization, canonical equality, limits and version fixtures first. Keep V1/V2 business semantics unchanged, existing server opaque and E2EE primitive untouched. `ContextSummary`, `ProviderConfig` and local permissions remain device-local. Verified complete remote turns may feed **new local-only derived summaries**, never override authoritative Domain/ChangeLog. Do not conflate Agent conflicts with existing business `EntityKind`.

## 7. Required adversarial acceptance matrix

| Area | Required test |
| --- | --- |
| **P0 mixed-version** | Emit V3 A1 then V1 business B1; old D8 quarantines A1 but **applies B1** with no Agent dot in its DVV. Test genuine V2 business upgrade gate separately. |
| **P0 old-to-new recovery** | Old client advances D8 cursor over quarantined V3; after upgrade backfill from earliest V3 quarantine cursor, dedupe exact Agent history and do **not** duplicate V1/V2 business effects; missing key/history gives explicit incomplete recovery. |
| **P0 held business** | Hold local Agent-origin V2 plus dependent D7 suffix without acknowledgement; inbound independent remote V1 still fetches/applies, unrelated opted-in V3 turn can upload; enabling gate drains suffix exactly once. |
| **P1 incomplete turns** | Assistant message arrives before ToolCall, ToolResult, Action and manifest; no partial Provider transcript or run; complete manifest atomically publishes, including after restart/duplicates. |
| **P1 concurrent turns** | Concurrent sibling turns show deterministic non-authoritative order but prevent another merged-thread Provider run until explicit approved reconciliation. |
| **P1 deleted audit** | Final audit arrives after matching thread tombstone, retains sanitized IDs/MutationIds with removed-parent markers and no raw resurrection or indefinite pending; unmatched parents remain pending/integrity failures. |
| Immutable/causal | Same-ID equal vs unequal DTO; same Agent dot/different operation ID; gap/late delivery; tombstone vs concurrent append, offline pre-delete queue; cross-space isolation. |
| Cross-stream | Action before D7 mutation, withheld V2 dependencies, no invented remote success; title concurrency and explicit conflict resolution. |
| Security & wire | V1/V2 unchanged; V3 old-client whole-ID quarantine; unknown discriminator; outer/inner ID mismatch; AEAD tamper/wrong key/AAD; excluded credentials/provider metadata; bound size. |
| Devices & migration | Real v12 migration, local ordinal collision, Android/Desktop offline/reconnect, historical keyring, PostgreSQL restart, exact ciphertext retry and fresh-install parity. |

## 8. Implementation slices after protocol approval

1. **D9-02-00 protocol freeze:** approve W1/W2/P1/D1/W4/W5/L1, amend canonical AGT-013/SYN-003 and freeze versioned wire fixtures.
2. **D9-02-01 typed wire/clock:** separate V3 Agent DVV and codec, frozen outer v1 encryption, old/new client compatibility tests.
3. **D9-02-02 database:** immutable event inbox/outbox, Agent clock, turn sealing, tombstone/conflict/audit links, backfill, real v12 migration.
4. **D9-02-03 merge/projection:** canonical dedupe, causal/dependency retry, turn fork, title/delete conflicts, accepted explicit resolution and visibility gate.
5. **D9-02-04 transport:** existing AEAD and opaque server; distinct inbound progression despite **held V2**, causally closed business outbound and separate eligible V3.
6. **D9-02-05 E2E:** old/new client upgrade, enrolled Android/Desktop offline/reconnect, PostgreSQL/keyring/migration/privacy and final CI evidence.

No new crypto, plaintext server Agent storage, remote execution or D9-03 credential transfer.

## 9. Decisions still requiring maintainer approval

| ID | Proposed policy | Fail-closed state until approved |
| --- | --- | --- |
| **W1** | V3 event DTO + separate Agent per-space replica DVV/handled frontier; unchanged outer v1/AAD; explicit D7 MutationId references | No V3 production transmission. |
| **W2** | Terminal immutable Tool/Action snapshots + `TurnFinalized` manifest, source-local pending confirmations and explicit concurrent turn conflict | No partial remote transcript projection. |
| **P1** | Immutable staging, deterministic presentation-only ordering, dedicated Agent conflict refs, non-destructive migration | No remote insertion into ordinal-unique live table. |
| **D1** | Durable tombstone, deleted-parent audit, concurrent delete/append resolution and causally safe pre-delete queue | No silent resurrection or unsound queue dropping. |
| **W4** | Independent user-owned V3 sync opt-in, all-active-capable-device rollout, upgrade backfill and explicit historical import | V3 outbound off. |
| **W5** | Hold unsupported V2 plus dependent business closure while permitting independent inbound; hold dependent turns until D7 facts shareable | No V2 bypass or false remote success. |
| **L1** | Bound message/tool/manifest size against existing envelope limits, explicit oversize failure or separately approved bounded fragmentation | No truncation or unbounded events. |

**Implementation status:** This revision fixes the four **protocol-design contradictions**. It does **not** claim the existing `SyncTransportWorker` early-return bug, production codec, Room schema or transcript assembler have already been modified. Those are separately reviewable implementation requirements after the decisions above are frozen.