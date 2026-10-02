# D9-02-02 — Agent History Sync Persistence / Migration

Status: implementation for review; local persistence only. Authority: the D9-02 freeze packet, AGT-013, SYN-003B, and the V3 DTOs in `shared:sync`.

## Migration

Room-managed schema advances from v12 to v13. `AgentSyncMigration12To13` creates the isolated Agent sync SQL catalog on the same Room connection. Fresh-install callbacks create and validate the same catalog. The migration has no destructive fallback and does not rewrite v12 rows. The catalog remains explicit SQL outside the Room `@Entity` list to avoid growing Room's generated validator. Room's exported v13 schema continues to describe Room-managed tables; `AgentSyncSchema` is the extension catalog and validation source.

## New tables and indexes

| Table | Purpose |
| --- | --- |
| `agent_sync_operation_identity` | Immutable canonical V3 DTO, operation identity, typed immutable-record identity, and unique per-SyncSpace Agent dot binding. |
| `agent_sync_inbox` | Inbound operation state (`PENDING`, `HANDLED`). |
| `agent_sync_outbox` | Outbound operation state (`READY`, `HELD`, `UPLOADED`); no transport consumes it in this phase. |
| `agent_sync_space_state` | Per-SyncSpace Agent replica identity and local Agent counter. |
| `agent_sync_dvv_frontier` | Separate per-replica Agent DVV contiguous handled frontier. |
| `agent_sync_handled_dot` | Inbound handled-dot ledger with unique dot and operation binding. |
| `agent_sync_pending_dependency` | Durable Agent-dot, parent-record, business-MutationId, turn-member, and parent-turn dependencies. |
| `agent_sync_turn_stage` | `TurnFinalized` manifest metadata and incomplete/verified/tombstoned staging state. |
| `agent_sync_turn_member` | Ordered manifest members and whether the matching inbound immutable event is handled. |
| `agent_sync_active_turn_projection` | Separate activation index for complete turns; it stores references only and does not write to the D9-01 Agent transcript. |
| `agent_sync_thread_tombstone` | Immutable causal delete facts; tombstones remove staged activation and block its reactivation. |
| `agent_sync_conflict` | Durable integrity/conflict metadata and the typed conflicting candidate, with a closed typed Agent entity kind; this row is never part of an active transcript. |
| `agent_sync_audit_parent_link` | Typed Action-to-parent audit references, owning thread identity, and `PARENT_PENDING` / `PARENT_VERIFIED` / `PARENT_REMOVED_BY_TOMBSTONE` state. |
| `agent_sync_backfill_state` | Independent V3 historical cursor, quarantine restart cursor, and recovery state. It does not share or update the D8 business cursor. |

Indexes cover immutable record uniqueness, inbox/outbox scheduling, dependency lookups, staged/active turns by thread, tombstones by thread, typed conflicts, and audit-parent resolution.

## Invariants and boundaries

- Persistence accepts the already-frozen `SyncPayloadV3` / `AgentSyncOperation`; it does not define another wire model.
- Same operation/record identity with an equal typed operation dedupes. A different value or a dot rebound is recorded as an integrity conflict and rejected.
- A turn remains staged until each manifest member is represented by a handled inbound immutable event, its Agent links validate, and pending prerequisites clear. Only then can the separate active-turn reference be added. No remote ordinal is written to `agent_message`.
- A tombstone removes active-turn references for its thread. An append that is not causally before the tombstone is retained in typed conflict storage; no resolution event is synthesized and no conflict history is discarded.
- D7 `SyncSpaceCursor`, business DVV, `MutationRecord`, and `HandledReceiveDot` remain separate and are not advanced by Agent operations.
- Backfill recovery retains the minimum relevant quarantine cursor for the open recovery generation. If a lower quarantine cursor is discovered, the Agent cursor restarts at `max(0, earliestQuarantinedCursor - 1)` (bounded by the prior cursor), and recovery returns to `RUNNING`. Clearing or increasing the earliest cursor cannot discard it before the generation reaches `COMPLETE`; a completed generation can be cleared before a later recovery starts. These transitions affect only `agent_sync_backfill_state`, never the D8 business cursor.
- Inbound `ThreadDeleted` is stored as an immutable pending fact while causal dependencies remain. Its tombstone, active-turn removal, staged-turn `TOMBSTONED` state, and audit-parent removal markers are committed together only when the operation becomes handled. A tombstoned staged turn is terminal.
- `TurnFinalized` remains `INCOMPLETE` until its own inbound inbox row is `HANDLED` with a handled-dot record, in addition to all member/link/dependency checks. The extension validator compares the complete explicit table/index DDL catalog because these objects are outside Room's generated validator.
- ProviderConfig, local permissions, ContextSummary, and pending confirmations remain in their D9-01 stores and are not copied into sync tables.
- No `SyncTransportWorker`, production receive dispatch, V3 upload, server, Envelope, AAD, V1/V2 or encryption code is changed.

## Blocked / follow-up decisions

- C1 is resolved by the maintainer follow-up amendment in `D9_02_PROTOCOL_FREEZE_PACKET.md`: counter origin 0, next-counter semantics, atomic local dot/outbox allocation, implicit -1 frontier, contiguous advancement, and covered dependency release are implemented in this slice. Local authored contexts use only the persisted contiguous frontier and include their own preceding dot; overflow is rejected. Inbound gaps remain ineligible until every context component is covered, with staged turns reevaluated after release.
- D2 resolution semantics are frozen in the same amendment and summarized in `AGENT_DECISIONS.md`. `ThreadDeleteConflictResolved` wire DTO/Codec, conflict-component merge and projection remain D9-02-03 implementation; no resolution event is implemented here.
- OD-012 local database encryption at rest remains unresolved. This work does not claim that locally persisted V3 payloads receive encryption-at-rest protection; production receive/storage remains disabled.
- This slice records a minimal turn-completion/activation gate. Cross-device merge, complete projection ordering, sibling conflict resolution, and Provider continuation policy are not implemented here.
