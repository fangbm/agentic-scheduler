# D9-02-01 — V3 Agent history wire DTO proposal

> Status: **Draft implementation for review; production V3 emission is not enabled.**
> Authority: AGT-013, SYN-003B and [`D9_02_PROTOCOL_FREEZE_PACKET.md`](D9_02_PROTOCOL_FREEZE_PACKET.md).

This slice adds a V3-only `:shared:sync` JSON codec. It does not alter D8's
V1/V2 payload DTOs, `EncryptedEnvelopeV1`, AAD, crypto, server API or sync
runtime. Each V3 payload carries one immutable typed Agent event. Its
`operationId` is an outer-routing `MutationId`, not a D7 business history
record. Agent record IDs, Agent replica IDs, Agent DVV and Agent HLC use
separate types; business references remain typed D7 `MutationId` values.

The encoder emits compact kotlinx.serialization JSON with defaults enabled,
`type` as the sealed discriminator, declared property order, sorted Agent DVV
context and ordered turn members. Decoding ignores additive object keys and
rejects unsupported versions/event discriminators or malformed DTOs as whole
payload outcomes. The authenticated outer ID is supplied to the codec and
must equal `operation.operationId`. Encoded plaintext is limited to 262144
UTF-8 bytes and oversize encode fails as `AGENT_SYNC_PAYLOAD_TOO_LARGE`.

Golden samples live in `shared/sync/src/desktopTest/resources/agent-sync-v3/`.
They pin every currently defined event discriminator (`ThreadCreated`,
`ThreadTitleSet`, `MessageAppended`, `ToolCallFinalized`,
`ToolResultAppended`, `ActionFinalized`, `TurnFinalized`, `ThreadDeleted`),
including failed turns, ordered ancestry/members, structured tool inputs/results,
typed D7 MutationId links, nullable standalone audit linkage, plus unknown event,
unknown manifest member and prohibited provider metadata failure samples. The
legacy D8 decoder remains unchanged: it classifies V3 as unsupported, which
`SyncEngine` durably whole-envelope-quarantines; independent V1 business payload
receive remains available.

## Action/turn association proposal for review

`ActionFinalized.turnId` is nullable to preserve standalone sanitized audit
records. If it is non-null, `threadId` and `sourceMessageId` are required. A
`TurnFinalized` may list only turn-member Actions: before exposing the manifest,
the receiver verifies the Action's `turnId` and `threadId` match the manifest,
the source message is a manifest member in that same thread/turn, and every
linked ToolCall/ToolResult is also a member belonging to that turn and source
call. A standalone Action has `turnId = null` and must not be listed in any
turn manifest. `AgentTurnLinkValidator` provides this pure typed check; the
later runtime projection must call it before making a turn visible. This
proposal prevents a remote audit record from borrowing another turn's
transcript or tool result.

Agent authoring-clock consistency is also explicit: `AgentHlcSnapshot.replicaId`
must equal `AgentDvvSnapshot.dot.replicaId`; if the author replica appears in
the DVV context, its observed counter must be strictly less than the new dot.
These are structural Dotted Version Vector constraints and reject malformed
author attribution without changing D7 or any frozen cryptographic policy.

## Deliberate boundary

This PR is not production V3 transmission. Exact serializer/fixture review is
still a mandatory gate in the freeze packet. In particular, AGT-013 requires
the deletion-resolution event encoding to be fixed in canonical fixtures;
that event is not defined here because its exact cross-device semantics and
wire fields are not frozen. It remains `BLOCKED_BY_DECISION` for a later
reviewed amendment. The D9-02 runtime, per-SyncSpace durable Agent clock,
database migration, worker dispatch, inbound staging and V3 opt-in are also
outside D9-02-01.
