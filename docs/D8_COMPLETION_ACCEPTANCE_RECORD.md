# D8 Completion Acceptance Record

> Status: **IN PROGRESS — FROZEN ACCEPTANCE ONLY**  
> Branch: `feature/d8-final-acceptance`  
> Updated: 2026-09-23

This record distinguishes acceptance evidence actually executed from work that
cannot safely be completed without an approved D8 decision. It does not amend
SYN-003 through SYN-019.

## Executed on this branch

| Acceptance path | Evidence | Status |
| --- | --- | --- |
| Existing-device pairing approval | `PairingApprovalServiceTest`: an ACTIVE local device approves only an account-matching remote pending request after an exact SAS comparison; pending/mismatched/SAS-mutated requests cannot export a package. | PASS |
| Three-device concurrent convergence | `D8ReplicaAcceptanceTest`: three independent Room databases receive the same real Tink AES-GCM envelopes in three different orders; they converge on one canonical N-way conflict, its provisional projection, all handled dots and cursor. | PASS |
| Duplicate delivery | The same three-device test redelivers a conflict participant and requires the same durable OPEN conflict outcome, with no second component; ordinary handled operations remain `Duplicate`. | PASS |
| Offline/reconnect catch-up | `D8ReplicaAcceptanceTest`: a local Event write commits while the relay is unavailable, retains its first ciphertext, uploads exactly that ciphertext after reconnect, and a second Room replica catches up through `SyncTransportWorker`. | PASS |

Local verification for the two replica-harness paths:

```text
./gradlew :shared:database:desktopTest \
  --tests 'dev.agenticscheduler.database.D8ReplicaAcceptanceTest' \
  --no-daemon --max-workers=1 --rerun-tasks
```

The command completed successfully on 2026-09-23 with two passing tests.

## BLOCKED_BY_DECISION

### Cross-replica conflict-resolution recognition

SYN-013 requires a resolution to causally dominate its participants and clear
the conflict. The v1 `SyncOperation` emitted by
`SyncConflictResolutionService` is intentionally an ordinary typed mutation
and carries no `conflictId` or resolution marker. A receiving replica therefore
cannot distinguish it from a permitted ordinary edit of a non-conflicted group
on the same entity that happens to observe the participants. Clearing every
matching OPEN conflict would silently turn such an edit into a resolution.

Required decision: freeze the interoperable resolution-recognition rule (and,
if necessary, its v1 wire representation/compatibility plan) so a remote
replica can clear exactly the intended component without weakening D8-P04.

### Recovery Secret envelope

SYN-005/SYN-007 require a Recovery Secret-protected recovery envelope, but the
frozen documents do not define a client `RecoveryEnvelopeV1` encryption/KDF
format, AAD, plaintext key-ring schema, or canonical serialization. The server
correctly treats the envelope as opaque, but a client restore or revocation
rotation E2E cannot be implemented safely without inventing those security
semantics.

Required decision: freeze the RecoveryEnvelopeV1 cryptographic and wire
contract before implementing Recovery Secret restore or complete AMK/recovery
envelope rotation acceptance.

## Still required after the decisions above

- full conflict-resolution convergence E2E;
- Recovery Secret restore and revocation/AMK rotation E2E;
- active application bootstrap/enrollment composition using the production
  secure-store lifecycle;
- Wear direct-versus-phone-relay integration (the repository currently has no
  platform relay adapter to execute);
- final PostgreSQL migration/security adversarial suite and green repository
  CI on the final head.

D8 must not be marked complete until these paths have executed successfully.
