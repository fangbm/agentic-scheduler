# D8 Completion Acceptance Record

> Status: **IN PROGRESS — DECISION BLOCKERS RESOLVED; FINAL E2E STILL OPEN**  
> Branch: `feature/d8-final-acceptance`  
> Updated: 2026-09-23

This record distinguishes acceptance evidence actually executed from work still required.
It does not start D9.

## Executed before the decision amendments

| Acceptance path | Evidence | Status |
| --- | --- | --- |
| Existing-device pairing approval | ACTIVE local device + account-matching remote pending request + exact SAS; mismatch cannot export a package. | PASS |
| Three-device concurrent convergence | Three Room replicas receive the same Tink AES-GCM envelopes in different orders and converge on one N-way conflict/projection/frontier. | PASS |
| Conflict participant replay | Re-delivery reproduces the current OPEN conflict without creating another component; ordinary handled mutations remain Duplicate. | PASS |
| Offline/reconnect catch-up | Local write commits offline, retains exact ciphertext, retries verbatim, then another replica catches up through SyncTransportWorker. | PASS |

## Previously BLOCKED_BY_DECISION — now resolved

### Cross-replica conflict resolution

Resolved by SYN-015A / OD-034. V1 now carries explicit
`MutationOrigin.ConflictResolution(conflictId)`. Remote clearing requires the explicit marker,
same SyncSpace, complete causal dominance and conflict-scoped typed mutations. Ordinary
causally-later edits cannot clear an OPEN conflict.

Implementation and cross-replica acceptance are present on this branch; final CI evidence is
recorded only after the current head is green.

### RecoveryEnvelopeV1

Resolved by SYN-005B / OD-044. V1 now freezes strict outer/plaintext wire schemas,
HMAC-SHA256 domain-separated key derivation, Tink AES-256-GCM, exact AAD, complete retained
historical key ring, strict binding validation and fixed derivation/AAD vectors.

Client codec + strict wire/fail-closed tests are present on this branch; Recovery enrollment
and complete revoke/rotate E2E still need application composition around the new contract.

## Still required before D8 FINAL PASS

- green CI on the current resolution/recovery amendment head;
- full Recovery Secret restore and revocation/AMK rotation E2E using RecoveryEnvelopeV1;
- active application bootstrap/enrollment composition using the production secure-store lifecycle;
- Wear direct-versus-phone-relay integration;
- final PostgreSQL migration/security adversarial suite;
- final repository/server CI green after all of the above.

D8 must not be marked complete and D9 must not start until these paths execute successfully.
