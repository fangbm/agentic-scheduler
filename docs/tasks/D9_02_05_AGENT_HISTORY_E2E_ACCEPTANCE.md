# D9-02-05 — Agent History Sync E2E / Completion Gate

Status: IN PROGRESS / Draft. Baseline: `feature/d9-02-agent-sync`, `4dbe432`
(PR #23 merged). Requested 2026-10-03. No production V3 enablement.

Authority: AGT-013, D9-02 protocol draft and freeze packet W1/W2/P1/D1/W4/W5/L1,
the completed D9-02-01/02/03/04 contracts, and the maintainer's D9-02-05 task.
D8_COMPLETION_ACCEPTANCE_RECORD.md supplies the evidence format, not D9 proof.

## Evidence labels

- IMPLEMENTED / VERIFIED: this slice's implementation and actually executed evidence.
- VERIFIED BY EXISTING REGRESSION: named earlier component/integration evidence;
  never relabel it as new enrolled-device/server E2E.
- NEW E2E EVIDENCE REQUIRED: not yet executed, or not yet implemented.
- BLOCKED BY OD-012 / separate release gate: production sensitive-data composition;
  this is not a reason to skip isolated test/acceptance implementation.

## Acceptance matrix (execution ledger)

| Item | Initial classification | Required new evidence / current result |
| --- | --- | --- |
| A1 Android/Desktop conversation consent UX | NEW E2E EVIDENCE REQUIRED | Default OFF; separate V2/V3 choices; active-device V3 acknowledgement; explicit OFF; old/downgraded/incomplete-history warning; no Tool/background mutator. NOT RUN. |
| A2 separate historical export | NEW E2E EVIDENCE REQUIRED | Explicit user export; completed sanitized turns only; excluded provider/local fields; durable source/export markers and restart dedupe; consent alone does not export. NOT RUN. |
| B enrolled-server harness | NEW E2E EVIDENCE REQUIRED | Real Ktor routes, JDBC/PostgreSQL, authenticated enrollment, file-backed Room, Tink/keyring, actual worker/merge/backfill. NOT RUN. |
| C1 old/new + upgrade | NEW E2E EVIDENCE REQUIRED | V3 whole-ID quarantine then independent V1 apply; Agent-only earliest-cursor backfill; no D7 replay/cursor rewind. NOT RUN. |
| C2 missing historical material | NEW E2E EVIDENCE REQUIRED | Old epoch/key success; missing ciphertext, missing key and retention gap each INCOMPLETE. NOT RUN. |
| C3 Android ↔ Desktop offline/reconnect | NEW E2E EVIDENCE REQUIRED | Two enrolled platform replicas; bidirectional sealed turns; exact ciphertext retry; client/server restart; separate frontiers. NOT RUN. |
| C4 reordered/incomplete turn | NEW E2E EVIDENCE REQUIRED | Relay delivery permutation/duplicates + restart; zero partial active transcript/Provider consumption; atomic completion. NOT RUN. |
| C5 sibling/root fork | NEW E2E EVIDENCE REQUIRED | Both turns retained; OPEN CONCURRENT_TURN_FORK; no semantic winner; continuation blocked. NOT RUN. |
| C6 delete/append/resolutions | NEW E2E EVIDENCE REQUIRED | Durable deletion conflict; KEEP/COPY convergence; new COPY identities, no Tool/business replay; conflicting resolutions and expanded observation. NOT RUN. |
| C7 deleted audit | NEW E2E EVIDENCE REQUIRED | Removed-parent links only for matching tombstone; sanitized audit/D7 refs retained; unrelated missing links pending; no raw resurrection. NOT RUN. |
| C8 held V2/cross-stream | NEW E2E EVIDENCE REQUIRED | V2 + real D7 closure held; independent V1/V3 progress; dependent whole turn held; authorization drains once; lookup-only audit. NOT RUN. |
| C9 consent/export | NEW E2E EVIDENCE REQUIRED | OFF zero outbound/readable inbound; ON zero retrospective export; separate explicit export and restart idempotence. NOT RUN. |
| C10 migrations/ordinal/restart | VERIFIED BY EXISTING REGRESSION | AgentSyncPersistenceTest + AgentHistoryTransportIntegrationTest cover populated v12/v13→v14, fresh parity, pending confirmation/config/policy/audit and durable state. Re-run required; additional cross-device ordinal evidence NOT RUN. |
| C11 crypto/adversarial | VERIFIED BY EXISTING REGRESSION | AuthenticatedSyncEnvelopeCodecTest, V3 codec/fixtures, AgentSyncPersistenceTest and transport integration cover frozen bounds/authentication/identity. New server-layer evidence still REQUIRED / NOT RUN. |
| C12 opaque PostgreSQL leak scan | NEW E2E EVIDENCE REQUIRED | Scan all public tables after real traffic for message/input/result/title/credential/SecretRef/content-key canaries. NOT RUN. |
| D targeted suites + full CI | NEW E2E EVIDENCE REQUIRED | Commands, exact head, executed test counts/skips and current CI links required. NOT RUN. |
| Production V3 sensitive receive/storage/upload | BLOCKED BY OD-012 / separate release gate | OPEN; production runtime keeps V3 injection absent. Acceptance-only composition is explicitly isolated. |
| D9-02 COMPLETE / roadmap update | NEW E2E EVIDENCE REQUIRED | Conditional on every frozen acceptance passing; remain IN PROGRESS until then. |

## Source-data audit / pending decision

D9-01 `AgentState.kt` persists local messages/Tool/Action records, but no durable
turn identity, complete membership, parent ancestry or terminal run outcome.
`AgentRunService.handleResponse` writes an assistant message before writing its
ToolCall, so an assistant tail is not by itself proof of a completed turn.
Legacy export must not infer success or dialogue ancestry from timestamps/ordinals.
The maintainer has been asked about fail-closed legacy handling plus explicit local
completion/export metadata. Pending that answer, the ambiguous legacy reconstruction
part is BLOCKED_BY_DECISION; consent and real-server acceptance proceed independently.
No new wire event or semantic resolution is proposed.

## Scope fence

Minimal functional Android/Desktop controls only; no navigation/visual redesign.
No changes to D2, D7 business causality, Envelope/AAD/AEAD, opaque server semantics
or frozen V3 event vocabulary. No Wear provisioning, D9-03, D10, MCP, embeddings
or server Agent. No production-sensitive V3 activation while OD-012 is OPEN.

## Actual execution record

No new E2E PASS recorded yet. Local environment discovery: Windows host, JDK 17,
Android SDK installed, no adb device connected at initial inspection. PostgreSQL
acceptance uses a disposable database; never use existing production relay data.
The completion record will distinguish JVM/route evidence, platform instrumentation
and true cross-platform execution. Compilation or older CI alone cannot close C3.
