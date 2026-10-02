# D9-02-03 — Agent History Merge / Projection / Conflict Resolution

## Authority and scope

Implement the already approved D2 amendment in
`D9_02_PROTOCOL_FREEZE_PACKET.md` and `AGENT_DECISIONS.md` against the D9-02-02
persistence base. This document records implementation scope; it does not
change the frozen wire fields, security policy, or event semantics.

The work is limited to the shared V3 wire/merge layer, the local Agent-sync
persistence interface and implementation, fixtures, tests, and milestone docs.
It does not enable production send/receive and does not modify Room schema,
`SyncTransportWorker`, D8 Envelope/AAD/crypto, network transport, or server
protocol. OD-012 remains a production release gate.

## Frozen behavior implemented here

- `ThreadDeleteConflictResolved` contains only `threadId`, the unique sorted
  full participant operation-ID component, `resolution`, and nullable
  `replacementThreadId`.
- Local conflict identity is derived from the original thread ID and sorted
  participants. Agent DVV causally observes every named participant.
- Local authoring is exposed through an explicit-user persistence capability,
  separate from the generic Agent sync persistence port.
- The original thread remains tombstoned. KEEP has no replacement. COPY uses
  fresh thread, turn, message, and operation IDs; only text messages and
  message-only finalized turns may be included. Tool/action/business events
  are rejected from the local copy batch.
- Concurrent differing resolutions persist as an open semantic conflict.
  Projection never selects a winner by timestamp. Late content does not clear
  a tombstone or enter active projection.
- Concurrent title writes and sibling turns are exposed as conflicts, with
  no timestamp-selected title and Provider continuation blocked for an open
  semantic conflict.

## Boundaries / follow-up review

- The event has no copy-content manifest. Therefore this phase enforces the
  text-only copy batch at the explicit local authoring boundary. Production
  receive validation and activation remain disabled and require later runtime
  integration review; the wire event itself does not attest human authorship.
- No title-resolution event or title conflict resolution policy was frozen.
  Title conflicts remain explicit and unresolved; no resolution behavior is
  inferred here.
- Exact delete-resolution encoding for a semantic conflict created by
  competing resolutions remains part of the frozen rule “later resolution
  observes the expanded component”; it is represented locally as an explicit
  conflict. Any distinct wire DTO beyond the approved D2 event would require
  a decision.
- OD-012 local-at-rest readiness remains pending for production-sensitive
  Agent history.
