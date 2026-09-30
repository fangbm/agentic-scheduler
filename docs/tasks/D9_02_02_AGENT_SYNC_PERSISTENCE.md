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
- ProviderConfig, local permissions, ContextSummary, and pending confirmations remain in their D9-01 stores and are not copied into sync tables.
- No `SyncTransportWorker`, production receive dispatch, V3 upload, server, Envelope, AAD, V1/V2 or encryption code is changed.

## Blocked / follow-up decisions

- `BLOCKED_BY_DECISION`: exact wire encoding and owner resolution semantics for deletion-conflict resolution remain out of scope for D9-02-03.
- `BLOCKED_BY_DECISION`: the frozen V3 DTO permits Agent counters `>= 0`, but the freeze packet does not set the first counter or contiguous-frontier advancement rule. The immutable handled-dot ledger is persisted; frontier reads and Agent-dot dependency resolution fail closed until that rule is frozen. Non-empty Agent DVV contexts therefore remain pending.
- OD-012 local database encryption at rest remains unresolved. This work does not claim that locally persisted V3 payloads receive encryption-at-rest protection; production receive/storage remains disabled.
- This slice records a minimal turn-completion/activation gate. Cross-device merge, complete projection ordering, sibling conflict resolution, and Provider continuation policy are not implemented here.
