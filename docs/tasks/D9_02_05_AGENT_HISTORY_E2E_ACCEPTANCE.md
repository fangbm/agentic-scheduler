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

| Item | Current classification | Required new evidence / current result |
| --- | --- | --- |
| A1 Android/Desktop conversation consent UX | IMPLEMENTED / VERIFIED | Room-backed per-space explicit acknowledgement/ON/OFF, separate V2 choice, old/downgrade/recovery warnings. Android and Desktop actual Compose click tests PASS; durable OFF/no automatic outbox verified. |
| A2 separate historical export | BLOCKED_BY_DECISION | No durable D9-01 completion/membership/ancestry source. Do not infer a turn from a message tail. Source-data decision below remains pending; export/marker/restart acceptance NOT IMPLEMENTED / NOT PASS. |
| B enrolled-server harness | IMPLEMENTED / VERIFIED | Actual Ktor/JDBC/PostgreSQL routes, authenticated invitations/enrollment/credentials, file Room14, platform secure store/Tink and worker/merge/backfill. Route suite and separate HTTPS Desktop↔Android harness executed; no memory relay. |
| C1 old/new + upgrade | IMPLEMENTED / VERIFIED | PASS: whole V3 quarantine then independent V1; upgrade/reopen/backfill twice, same forward cursor/history and no Agent dots in D7. |
| C2 missing historical material | IMPLEMENTED / VERIFIED | PASS: actual 7→8 keyring rotation and old ciphertext recovery; missing secure-store key, absent ciphertext and earlier retention gap remain INCOMPLETE across restart. |
| C3 Android ↔ Desktop offline/reconnect | IMPLEMENTED / VERIFIED | PASS: Windows DPAPI/Desktop ↔ Android35 emulator/Keystore, real HTTPS/CIO relay/PostgreSQL, both actual client process restarts, server process restart, exact durable ciphertext and separate Agent/D7 frontiers. Acceptance-only composition; not production enablement or physical-device proof. |
| C4 reordered/incomplete turn | IMPLEMENTED / VERIFIED | PASS: Tool manifest/result/message/action/call delivery permutation, restart between fragments, duplicate retry; no active transcript or continuation read until sealed. Handled member before absent manifest also blocks the acceptance read boundary. No production Provider integration claimed. |
| C5 sibling/root fork | IMPLEMENTED / VERIFIED | PASS: same-parent and empty-root siblings retain both branches, explicit OPEN fork, continuation blocked; durable derived rows agree immediately on activation and after restart. |
| C6 delete/append/resolutions | IMPLEMENTED / VERIFIED | PASS: real relay delete/concurrent append, fresh text-only COPY identities, concurrent different resolutions, expanded observing KEEP, and restart. Frozen invalid-DVV/identical KEEP convergence cases additionally exercised. Original ID stays tombstoned; no Tool/business replay. |
| C7 deleted audit | IMPLEMENTED / VERIFIED | PASS: late sanitized Action/D7 references retained, matching parent REMOVED with no indefinite record dependency; unrelated missing parent stays pending. Concurrent tombstone releases only matching pre-existing audit record dependencies; causal/D7 barriers remain. |
| C8 held V2/cross-stream | IMPLEMENTED / VERIFIED | PASS: actual relay V2 plus full local suffix held; independent inbound V1 and consented V3 progress; referenced complete turn withheld as a unit; gate release drains once and audit only looks up received D7 facts. |
| C9 consent/export | PARTIAL; export BLOCKED_BY_DECISION | PASS: OFF zero upload/readable V3 projection, explicit acknowledgement, restart OFF, ON leaves populated local legacy history/outbox unchanged. Explicit historical export and export-marker restart idempotence are NOT PASS. |
| C10 migrations/ordinal/restart | VERIFIED BY EXISTING REGRESSION + NEW EVIDENCE | Populated v12→13→14 and v13→14/fresh parity tests retained and re-run; new enrolled two-client test keeps existing ordinal-0 local messages intact while separate V3 history projects. |
| C11 crypto/adversarial | VERIFIED BY EXISTING REGRESSION + NEW EVIDENCE | Frozen Envelope/AAD/AEAD/fixtures/identity/bounds suites re-run; new real relay unknown/mismatched-ID/reused-dot quarantine, whole-turn lower deployment preflight, actual Tink tamper/wrong-key/AAD rejection and exact ciphertext retries. Crypto and wire implementation unchanged. |
| C12 opaque PostgreSQL leak scan | IMPLEMENTED / VERIFIED | PASS: all public tables scanned after real route traffic and platform round trip; message/input/result/title plus actually stored local provider credential/SecretRef, actual device credentials and content-key encodings absent. No payload or credential HTTP logging. |
| D targeted suites + full CI | IN PROGRESS | Exact commands/counts and current head CI tracked in D9_02_COMPLETION_ACCEPTANCE_RECORD.md. Prior CI revealed Linux fixture double-delete cleanup failure; test cleanup corrected, secure-store implementation unchanged. |
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

See `docs/D9_02_COMPLETION_ACCEPTANCE_RECORD.md` for commands, environment,
counts, failures and their corrections, CI links and remaining blockers. The
initial absent Android device was replaced with a dedicated Android35 emulator;
the Windows emulator's non-ASCII path failure was corrected using an isolated
ASCII AVD path. Disposable PostgreSQL databases keep earlier D8 fixtures isolated.
Neither a skipped platform harness test nor compilation counts as E2E PASS.
