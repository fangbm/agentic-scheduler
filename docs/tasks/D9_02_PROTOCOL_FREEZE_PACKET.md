# D9-02-00 — Protocol freeze packet

> Status: **MAINTAINER SIGN-OFF REQUIRED — PROPOSALS, NOT FROZEN**
> Companion to [D9-02 protocol draft v0.2](D9_02_AGENT_HISTORY_SYNC_PROTOCOL_DRAFT.md).
> Scope: choose the seven cross-cutting decisions before adding production V3 wire, Room migration, or changing D8 worker behavior. Canonical AGT-013/SYN-003 and other frozen D7/D8 decisions remain authoritative until an explicit approved amendment lands.

## Why this gate exists

A successful draft review is not a frozen protocol. D9-02 affects cross-device private history, irreversible thread deletion, old-client compatibility and the existing business sync worker. A broad instruction to continue development does not itself select security, migration or product defaults. This packet proposes a concrete first-alpha decision set for **one maintainer approval** and identifies implementation and test consequences.

## Seven proposed decisions

### W1 — inner payload and causal namespace

**Recommend:** inner `SyncPayloadV3` holds one immutable typed `AgentSyncOperation`. Preserve the frozen `EncryptedEnvelopeV1`, its exact AAD, Tink AEAD, key epochs, routing fields and opaque server. Authenticated outer `mutationId` equals V3 `operationId` (unique UUIDv7 across both streams). V1/V2 remain D7 business payloads, including the existing D9-01 Agent-origin **business** V2 opt-in.

Persist a **distinct Agent replica identity, DVV and handled-dot frontier per SyncSpace**; never insert Agent dots in the D7 business journal or vice versa. D7 business references inside Agent records are **typed immutable MutationId references**, not cross-stream DVV components. New inner dispatch must preserve the legacy whole-ID quarantine path.

**Proof required:** new V3 A1 followed by independent V1 B1; an old D8 client quarantines A1 yet can apply B1. Verify Agent duplicate/dot reuse and encrypted outer/inner ID mismatch. Do not weaken the existing old-client restriction on V2 business mutations.

### W2 — terminal immutable events and complete turns

**Recommend:** synchronize only immutable final ToolCall/ToolResult/AgentAction snapshots. No remote replay/execution, no remote approvals and no provider credentials/session IDs. Add immutable `TurnFinalized` manifest with `turnId`, `parentTurnIds`, ordered member IDs and terminal success/failure. Stage inbound events; atomically expose a turn to the existing Agent transcript/runtime only after every required immutable member and parent is verified. A locally pending confirmation stays local.

**Fork behavior for initial release:** concurrently completed child turns of the same ancestor are retained, displayed with an explicit conflict and blocked from further *merged-thread* Provider execution; no timestamp-derived dialogue winner. An explicit branch reconciliation UX is a separate acceptance item before claiming complete multi-device continuation.

**Proof required:** shuffled and duplicated Message/Call/Result/Action/manifest arrival; no partial transcript or premature Provider run, including across restart; explicit failed/user-only terminal turn.

### P1 — persistence, canonical equality and projection

**Recommend:** add non-destructive v12-to-next local schema for Agent immutable inbound/outbound events, distinct Agent clock, dedupe/dot ledger, turn manifests, tombstones, typed Agent conflict refs, deleted-parent audit-link state and historical-backfill cursor. Keep source-local live/pending confirmations intact. Remote local `ordinal` cannot be inserted directly into the existing unique `(thread_id, ordinal)` constraint.

Canonical equality compares **explicit versioned typed DTO fields** (including exact message content and ordered IDs), not Room rows or ciphertext hashes; define canonical JSON fixtures before coding. Project turn order from declared ancestry; use HLC plus immutable ID **only** as stable UI tie-breakers for incomparable concurrent branches, never Provider-dialogue order. Do not silently extend D8's business-only `EntityKind` with Agent-thread names.

**Proof required:** existing v12 populated file migration, fresh-install parity, local pending confirmations, two devices issuing the same ordinal, same record ID/equal vs unequal values and a reused Agent DVV dot.

### D1 — deletion and concurrent content

**Recommend:** keep durable Agent thread tombstones and the handled-dot/operation-ID records needed for safe replay. Accepted deletion removes *active* raw thread, messages, calls, results and summaries; finalized sanitized AgentAction and committed D7 audit remain. A causally older append cannot resurrect. A concurrent delete/append remains a durable explicit semantic conflict; **block runs on the disputed thread**.

For first-alpha resolution, propose owner-explicit **keep deletion** or **import competing text into a newly identified thread**, never silently resurrecting the deleted thread ID. The exact causal resolution event must observe all conflicting Agent dots; candidate text stays outside the active transcript in restricted local conflict storage until resolved. Do not claim OD-012 at-rest protection has been solved by E2EE. A delete cannot discard pre-delete queued events if its DVV still references them; approval must choose a causally complete upload sequence or define an authenticated gap mechanism.

After accepted deletion, a late authentic finalized AgentAction may retain IDs of deliberately purged parents with `PARENT_REMOVED_BY_TOMBSTONE` and no restored plaintext. Unrelated missing parents remain pending/integrity failures.

**Proof required:** delete vs concurrent message/turn; delayed old events; offline pre-delete queue; late Action finalization; explicit resolution convergence and no transcript resurrection.

### W4 — rollout, explicit consent and catch-up

**Recommend:** separate **user-controlled per-SyncSpace** conversation-sync opt-in, independent from the existing Agent-origin V2 business-write gate. Outbound V3 OFF by default until the owner acknowledges all active enrolled devices are updated; the current opaque server cannot inspect V3 or cryptographically attest client versions, so do not imply otherwise. Newly enrolling an older client and app downgrade must refuse unsafe V3 sync or show a clear incomplete-history state.

**Recommend:** no silent retrospective upload of D9-01 local conversations. Offer a separate explicit opt-in export of completed sanitized historical turns. Existing old D8 devices quarantine accidental V3 but keep business sync independent; after upgrade, backfill V3 from the earliest retained relevant quarantine cursor without reapplying business operations. Preserve existing historical keyring requirements. Missing history/key means **incomplete recovery**, never an apparently complete thread.

**Approval detail:** specify whether a capable receive-only device that has not opted into rendering conversations may retain encrypted V3 for a later local opt-in without materializing it.

### W5 — held outbound business writes and audit dependencies

**Recommend:** keep the original D9-01 V2 all-devices-upgraded opt-in mandatory. Refactor D8 worker so `OUTBOUND_HELD_COMPATIBILITY` does **not** prevent independent authenticated inbound fetch; track outbound and inbound outcomes separately. Only upload a D7 business operation when its *full local business-DVV dependency closure* is safe for that SyncSpace. Hold an unauthorized V2 **and** dependent business suffix. Never skip/rewrite its dot, mark it uploaded or claim full synchronization while held.

An Agent V3 turn with **no** dependency on held business writes may upload if W4 permits it. Hold an entire dependent finalized turn (all required records plus manifest) until its referenced D7 business mutation is durably shared; independent turns can progress. Incoming AgentAction never performs business writes and never claims a remote business fact until verified against local D7 state. No implicit source-only success display.

**Proof required:** withheld V2 plus dependent D7 suffix; remote independent V1 still fetches; independent approved V3 completes; later authorization drains held queue once; dependent V3 is not released prematurely.

### L1 — bounded payloads and explicit oversized failure

**Recommend for first alpha:** one V3 event per envelope and a **256 KiB cap on encoded V3 plaintext UTF-8 bytes** before encryption, additionally enforcing the receiving server's negotiated/configured ciphertext and HTTP body limits. Reject oversize records explicitly (`AGENT_SYNC_PAYLOAD_TOO_LARGE`), with **no silent truncation and no implicit fragmentation**. Do not claim success for an unpublishable completed turn. The current server default is 1,048,576 ciphertext bytes and 1,200,000 request bytes; lower deployment limits must remain authoritative.

**Proof required:** byte-accurate boundaries including multibyte UTF-8, escaped JSON, ciphertext/base64 expansion, reduced server limits and restart/retry behavior. Fragmentation, attachment storage or snapshot compaction would be separately approved future work.

## Cross-cutting invariants

- The server remains opaque and does not execute an Agent. ProviderConfig, credentials, secret-store references, local permission settings, provider call IDs and ContextSummary never enter synchronized DTOs.
- Existing D7 MutationIds/ChangeLog and business SyncOperation are authoritative for tasks, events, Planner/Undo. Agent audit references never synthesize those facts.
- Historical encrypted content remains on the server/backups under existing D8 retention; thread deletion is **not** guaranteed retroactive cryptographic erasure.
- Unknown V3 on older D8 quarantines the **entire envelope ID**; no silent fallback to V2 or plaintext.
- No producer/receiver begins production V3 emission or new deletion policy until canonical AGT-013/SYN-003 amendments and wire fixtures are approved.

## Dependency-aware work after sign-off

1. Amend `AGENT_DECISIONS.md` AGT-013 and `SYNC_SECURITY_DECISIONS.md` SYN-003 to precisely freeze approved decisions; publish canonical V3 JSON and old-D8 quarantine fixtures.
2. Implement and test independent Agent wire/DVV and old-client quarantine-then-business regression without changing D7 V1/V2.
3. Add non-destructive Room migration, event inbox/outbox, staged/complete turn projection and independent backfill.
4. Add Agent semantic/integrity conflicts, retained tombstones and explicit approved resolution with privacy/causal tests.
5. Integrate existing D8 authenticated transport and implement the separately tested **held outbound versus independent inbound** runtime fix.
6. Complete Android/Desktop offline/reconnect, PostgreSQL, old-client upgrade/backfill, key epochs, source-only local state and full CI acceptance.

### Sign-off register

| Decision | State | Maintainer response |
| --- | --- | --- |
| W1 | PROPOSED | Approve / Amend / Reject |
| W2 | PROPOSED | Approve / Amend / Reject |
| P1 | PROPOSED | Approve / Amend / Reject |
| D1 | PROPOSED | Approve / Amend / Reject |
| W4 | PROPOSED | Approve / Amend / Reject |
| W5 | PROPOSED | Approve / Amend / Reject |
| L1 | PROPOSED | Approve / Amend / Reject |

A maintainer may explicitly approve the seven proposed choices as a single **D9-02-00 protocol freeze** decision; any amended choice needs its exact replacement recorded before production implementation. This packet is not, by itself, such approval.
