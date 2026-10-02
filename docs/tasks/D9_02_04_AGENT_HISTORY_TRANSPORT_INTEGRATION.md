# D9-02-04 — Agent history transport integration

Status: implementation / Draft PR; production V3 remains disabled by OD-012.

Authority: AGT-013; protocol draft §8; protocol freeze W1/W4/W5/L1;
completed D9-02-01/02/03 contracts. Baseline: feature/d9-02-agent-sync,
360119e (PR #22 merged).

## Scope and acceptance

- Separate business outbound failures/compatibility holds from authenticated
  inbound progression. Retain unauthorized V2 and its full business causal
  dependency closure; never skip/rewrite dots or mark held operations uploaded.
- Add an explicitly injected V3 transport/receive path, separate Agent outbox,
  per-space default-off user consent and durable exact ciphertext retry.
- Keep one frozen V3 event per unchanged D8 envelope, operation ID binding,
  AAD, AEAD, epochs and 262144-byte encoded plaintext cap.
- Preflight complete finalized turns and hold every member plus manifest until
  referenced D7 facts are durably shared; independent consented turns progress.
- Received audit only looks up local D7 facts. Consent-off receive keeps only
  quarantine/backfill metadata, with no plaintext Agent history projection.
- Preserve separate Agent frontier/backfill and D7 business causal state.
- Verify holds, release, consent, retries, restart, receive dispatch, unchanged
  D8 authentication and independently reported failures with targeted tests and
  the repository CI workflow.

## Existing interface audit

The v13 Agent outbox already persists immutable operations and READY/HELD/
UPLOADED states, but has no ciphertext or conversation-consent storage/port.
D8 ciphertext is attached to a business journal record, so it cannot hold an
Agent operation without manufacturing a D7 mutation. This slice needs isolated
transport persistence, following the explicit SQL extension/migration pattern.
Consent ownership remains outside Agent Tools and background jobs. No D9-01
transcript export or automatic enumeration is introduced.

## Scope fence

No D2 semantic changes, V3 wire changes, new crypto, server changes, production
V3 enablement, D9-02-05 E2E or D9-03 Wear work. OD-012 remains a release gate.
The existing production composition continues to omit the optional V3 path.

## Integration and migration

Room advances v13 → v14 without destructive fallback or changes to existing
table definitions. Fresh installs and migrations use the same explicit SQL
extension and mandatory structural validator. No Room `@Entity` is added.

| New table | Purpose / constraints |
| --- | --- |
| `agent_sync_transport_consent` | Per-space explicit user choice and all-active-devices V3 acknowledgement. Absent row means OFF; enabling without acknowledgement is rejected. |
| `agent_sync_outbound_envelope` | Exact encoded D8 ciphertext envelope, composite `(sync_space_id, operation_id)` primary key, restrictive foreign key to the separate Agent outbox. First durable envelope wins every retry. |

These primary keys provide the required lookup indexes. Existing Agent outbox
state/dependency indexes are reused. Migration preserves v13 immutable facts,
outbox delivery state and all D9-01/D7 records. Migration tests cover populated
v12 → v14, populated v13 → v14 and fresh-install equivalence.

`AgentHistoryOutboundTransport` is optionally injected into the existing worker;
`AgentHistoryReceiveIntegration` is optionally injected into the authenticated
gateway. They reuse the actual D8 codec/key providers and authenticated
`SyncTransport` interface (the Ktor adapter is unchanged). Production
`ActiveSyncRuntimeFactory` / Room composition inject neither. No retrospective
reader of D9-01 local tables exists. The user consent mutator is a separate
capability from the transport reader and Agent persistence port.

Business eligibility checks the frozen **D7 declared DVV context**, authenticated
received dots and acknowledged per-space ciphertext, while retaining every
actual unshared local predecessor in its dependency closure. It does not impose
Agent C1 counter/frontier rules on D7 or manufacture a missing business dot.
In particular, a previously acknowledged higher dot does not bypass an earlier
real held local mutation. The original DVV and journal remain untouched.

V3 finalized units require all manifest members, same thread/turn association,
Tool source/result links and Action links. Every D7 reference must be durably
shared before any member/manifest is published. Incomplete/held ancestor turns
hold their children; independent roots/threads may upload even when the Agent
context causes staging on another device. All events in a ready unit pass size,
current-key and configured ciphertext/request-bound preflight before its entire
exact ciphertext batch is retained atomically. Network interruption may deliver
only part of that already eligible unit; remote staging prevents partial active
projection. Upload acknowledgement marks only that actual envelope uploaded.

Inbound dispatch occurs **after** unchanged D8 authentication. V1/V2 go to the
business engine. Supported consented V3 goes to immutable Agent persistence;
unknown/malformed V3 and consent-off V3 keep whole-envelope quarantine routing
metadata. Consent-off never writes Agent plaintext or active projection. Audit
dependencies clear only for real local D7 facts; no received event executes a
Tool, Provider or business mutation. Retransmissions do not recreate dependencies
on already indexed immutable facts. Integrity failures retain evidence and are
reported without blocking independent business receive or another Agent replica.

The normal forward relay cursor may advance after a V3 receipt is durably staged
or quarantined; this carries no D7 causal fact. Historical replay uses only
`agent_sync_backfill_state`, skips business payloads entirely and never rewinds
the forward cursor. Earliest legacy quarantine is discovered from durable D8
metadata; absent ciphertext/key or unsupported history reports INCOMPLETE.
Recovery does not claim a thread is complete merely because fetch finished.
Recovery refuses to cross a known missing V3 quarantine cursor. Restored
ciphertext can therefore be retried from the retained checkpoint. Checkpoint
writes compare the current recovery state inside the existing write transaction;
a stale in-flight fetch cannot overwrite an earlier quarantine restart generation.

Run results retain separate business upload/failure/held IDs, Agent upload/
failure/held IDs/consent, fetch failure and receive failure. The legacy `applied`
count still means durable gateway handling (including staging/quarantine), not
proof that a business change or complete Agent turn became active.

## Remaining release boundaries

- OD-012 remains OPEN: plaintext Agent persistence in this integration harness
  is not production at-rest readiness. No production V3 upload/receive is enabled.
- D1 conservative hold also applies to unpublished content after local or
  received tombstones. Its causal history is retained without private-content
  upload, dot rewriting or synthetic success. No new gap/compaction mechanism.
- User consent UI/enrollment-version UX, explicit historical export and the
  D9-02-05 enrolled Android/Desktop/server acceptance remain separate slices.
- No new protocol decision is selected. D2 resolution/delete behavior and wire
  fixtures are unchanged; only frozen W2 turn association checks are reused at
  the transport/staging barrier.

## Targeted verification

Executed with JDK 17, ASCII Gradle home `D:\gradle-home-agent` and the repository
Gradle wrapper (invoked via `GradleWrapperMain` on Windows). The forced rerun
completed `BUILD SUCCESSFUL`: **141 tests, zero failures/errors/skips**.

Equivalent wrapper task arguments:

```sh
./gradlew --no-daemon --no-configuration-cache --max-workers=1 \
  :shared:database:desktopTest \
  --tests dev.agenticscheduler.database.AgentHistoryTransportIntegrationTest \
  --tests dev.agenticscheduler.database.AgentSyncPersistenceTest \
  --tests dev.agenticscheduler.database.D8ReplicaAcceptanceTest \
  --tests dev.agenticscheduler.database.PersistenceIntegrationTest \
  :shared:application:desktopTest \
  --tests dev.agenticscheduler.application.sync.SyncTransportWorkerTest \
  --tests dev.agenticscheduler.application.sync.ActiveSyncCatchUpTriggerTest \
  --tests dev.agenticscheduler.application.sync.AuthenticatedSyncEnvelopeCodecTest \
  --tests dev.agenticscheduler.application.sync.KtorSyncTransportTest \
  :shared:sync:desktopTest \
  --tests dev.agenticscheduler.sync.AgentSyncWireProtocolTest \
  --tests dev.agenticscheduler.sync.AgentSyncGoldenFixtureTest \
  --tests dev.agenticscheduler.sync.AgentSyncMergeProjectionTest --rerun-tasks
```

The new transport class has 20 tests (Room + actual Tink + actual business
receive engine, opaque in-memory transport). These are transport/persistence
regressions, not D9-02-05 enrolled-device E2E evidence. The remaining 121 tests
cover prior persistence, D8 receive/relay/AEAD/Ktor, V3 codec/fixtures and D2
projection. Full repository CI remains the PR's four-job workflow (`build`,
`desktop-windows`, `android-keystore`, `wear-keystore`); consult the current
head's checks rather than an older commit's result.

Self-review: Room v14 export has exactly the same Room entity definitions and
identity hash as v13; only the version changes. No D2 wire/merge, Envelope/AAD,
Tink implementation, server, platform production composition or Provider runtime
file changes. Business and Agent state remain in their original separate stores.
