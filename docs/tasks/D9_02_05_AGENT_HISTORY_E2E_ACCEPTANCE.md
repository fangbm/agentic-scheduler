# D9-02-05 — Agent History Sync E2E / Completion Gate

Status: IMPLEMENTATION AND ACCEPTANCE COMPLETE / PR #24 MERGED on 2026-10-04 as `6583e61fc3101e5373d537f6b90675430a7aec10`.
Baseline: `feature/d9-02-agent-sync`, `4dbe432` (PR #23 merged). Verified
2026-10-04 against implementation head `7783a79a9404d23c0e3414c3e8534fe880688783`. No production V3 enablement.

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

| Item | Current classification | Required new evidence / current result |
| --- | --- | --- |
| A1 Android/Desktop conversation consent UX | IMPLEMENTED / VERIFIED | Room-backed per-space explicit acknowledgement/ON/OFF, separate V2 choice, old/downgrade/recovery warnings. Android and Desktop actual Compose click tests PASS; durable OFF/no automatic outbox verified. |
| A2 separate historical export | IMPLEMENTED / VERIFIED | AGT-018 authorizes v14→v15 prospective provenance; populated v12→v15 migration preserves real D9-01 records and marks old history `LEGACY_UNVERIFIED`. Desktop persistence tests cover exact member conversion, private metadata exclusion, incomplete/crash turns, tainted ancestry, first-mapping-wins retries across restart, and immutable Agent outbox identities. Local deletion now purges all provenance member snapshots and turn rows, clears creation title/time, and remains excluded after restart; retained AgentAction and already prepared V3 mappings/outbox are preserved. Consent alone queues zero facts. No direct upload path. |
| B enrolled-server harness | IMPLEMENTED / VERIFIED | Actual Ktor/JDBC/PostgreSQL routes, authenticated invitations/enrollment/credentials, file Room14, platform secure store/Tink and worker/merge/backfill. Route suite and separate HTTPS Desktop↔Android harness executed; no memory relay. |
| C1 old/new + upgrade | IMPLEMENTED / VERIFIED | PASS: whole V3 quarantine then independent V1; upgrade/reopen/backfill twice, same forward cursor/history and no Agent dots in D7. |
| C2 missing historical material | IMPLEMENTED / VERIFIED | PASS: actual 7→8 keyring rotation and old ciphertext recovery; missing secure-store key, absent ciphertext and earlier retention gap remain INCOMPLETE across restart. |
| C3 Android ↔ Desktop offline/reconnect | IMPLEMENTED / VERIFIED | PASS: Windows DPAPI/Desktop ↔ Android35 emulator/Keystore, real HTTPS/CIO relay/PostgreSQL, both actual client process restarts, server process restart, exact durable ciphertext and separate Agent/D7 frontiers. Acceptance-only composition; not production enablement or physical-device proof. |
| C4 reordered/incomplete turn | IMPLEMENTED / VERIFIED | PASS: Tool manifest/result/message/action/call delivery permutation, restart between fragments, duplicate retry; no active transcript or continuation read until sealed. Handled member before absent manifest also blocks the acceptance read boundary. No production Provider integration claimed. |
| C5 sibling/root fork | IMPLEMENTED / VERIFIED | PASS: same-parent and empty-root siblings retain both branches, explicit OPEN fork, continuation blocked; durable derived rows agree immediately on activation and after restart. |
| C6 delete/append/resolutions | IMPLEMENTED / VERIFIED | PASS: real relay delete/concurrent append, fresh text-only COPY identities, concurrent different resolutions, expanded observing KEEP, and restart. Frozen invalid-DVV/identical KEEP convergence cases additionally exercised. Original ID stays tombstoned; no Tool/business replay. |
| C7 deleted audit | IMPLEMENTED / VERIFIED | PASS: late sanitized Action/D7 references retained, matching parent REMOVED with no indefinite record dependency; unrelated missing parent stays pending. Concurrent tombstone releases only matching pre-existing audit record dependencies; causal/D7 barriers remain. |
| C8 held V2/cross-stream | IMPLEMENTED / VERIFIED | PASS: actual relay V2 plus full local suffix held; independent inbound V1 and consented V3 progress; referenced complete turn withheld as a unit; gate release drains once and audit only looks up received D7 facts. |
| C9 consent/export | IMPLEMENTED / VERIFIED | Desktop Compose and Android 15 emulator clicks both prove consent ON leaves the Agent outbox empty, explicit export queues 3 facts for a one-message finalized turn, and repeated click queues 0 new facts / reuses all 3 mappings. OFF remains durable; existing V2 setting stays OFF. OD-012 still prevents production-sensitive V3 activation. |
| C10 migrations/ordinal/restart | IMPLEMENTED / VERIFIED | Populated v12→13→14→15 migration and fresh/migrated schema-signature parity pass. Existing v12 user/assistant + Tool/Action/result + Pending Confirmation, ProviderConfig, permission policy and ContextSummary data are byte-for-byte preserved. Legacy provenance has no creation snapshot/turn rows; prospective turn/member/mapping tables are empty. |
| C11 crypto/adversarial | VERIFIED BY EXISTING REGRESSION + NEW EVIDENCE | Frozen Envelope/AAD/AEAD/fixtures/identity/bounds suites re-run; new real relay unknown/mismatched-ID/reused-dot and unequal immutable-record value quarantine, restart preserves original/frontiers, whole-turn lower deployment preflight, actual Tink tamper/wrong-key/AAD rejection and exact ciphertext retries. Crypto and wire implementation unchanged. |
| C12 opaque PostgreSQL leak scan | IMPLEMENTED / VERIFIED | PASS: all public tables scanned after real route traffic and platform round trip; message/input/result/title plus actually stored local provider credential/SecretRef, actual device credentials and content-key encodings absent. No payload or credential HTTP logging. |
| D targeted suites + full CI | PASS | Local targeted database suites PASS 46/46: `AgentHistoryExplicitExportTest` (3), `AgentPersistenceTest` (9), `AgentRunIntegrationTest` (14), `AgentSyncPersistenceTest` (20); Desktop Compose PASS 2/2; Android 15 Compose instrumentation PASS 2/2. Full CI on implementation head `7783a79a9404d23c0e3414c3e8534fe880688783`, run [37133253426](https://github.com/fangbm/temvio/actions/runs/37133253426), passed all five jobs, including the 18-test disposable PostgreSQL E2E suite and the enrolled Desktop↔Android relay-restart acceptance with opaque public-table canary scan. Windows, Android Keystore and Wear Keystore jobs passed. Local PostgreSQL rerun remains unavailable because the Docker Desktop service is stopped and access denied when starting it; CI supplied the real PostgreSQL evidence. Windows Test Worker issue was an incorrect default Gradle home/JDK; explicit JDK17 + `D:\gradle-home-agent` starts workers correctly. |
| Production V3 sensitive receive/storage/upload | BLOCKED BY OD-012 / separate release gate | OPEN; production runtime keeps V3 injection absent. Acceptance-only composition is explicitly isolated. |
| D9-02 COMPLETE / roadmap update | PASS FOR IMPLEMENTATION ACCEPTANCE | All frozen D9-02 implementation and E2E requirements pass. PR #24 is merged as `6583e61fc3101e5373d537f6b90675430a7aec10`. OD-012 remains OPEN as a separate production-sensitive local-data release gate; no production V3 composition is enabled. |

## Source-data audit / resolved decision

D9-01 `AgentState.kt` previously had no durable turn identity, complete membership,
parent ancestry or terminal run outcome. AGT-018 now authorizes prospective local
provenance in Room v15. Every pre-v15 thread is `LEGACY_UNVERIFIED`; no turn,
membership, ancestry, creation snapshot or outcome is inferred from ordinals,
timestamps, adjacency or assistant tails. A pending confirmation, crash, cancellation,
exception or absent completion write remains unexportable. An ancestry gap taints
descendants for export only and never blocks local Agent use. Existing V3 DTOs remain
unchanged; OD-057 is resolved for this milestone and OD-012 remains a separate release gate.

## Scope fence

Minimal functional Android/Desktop controls only; explicit export is a separate
button from consent and only prepares durable Agent outbox records; no direct upload.
No navigation/visual redesign.
No changes to D2, D7 business causality, Envelope/AAD/AEAD, opaque server semantics
or frozen V3 event vocabulary. No Wear provisioning, D9-03, D10, MCP, embeddings
or server Agent. No production-sensitive V3 activation while OD-012 is OPEN.

## Actual execution record

See `docs/D9_02_COMPLETION_ACCEPTANCE_RECORD.md` for commands, environment,
counts, failures and their corrections, CI links and remaining blockers. The
initial absent Android device was replaced with a dedicated Android35 emulator;
the Windows emulator's non-ASCII path failure was corrected using an isolated
ASCII AVD path. Disposable PostgreSQL databases keep earlier D8 fixtures isolated.
Neither a skipped platform harness test nor compilation counts as E2E PASS.
