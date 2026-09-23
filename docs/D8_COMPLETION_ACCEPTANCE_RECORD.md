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

Implementation and cross-replica acceptance are present on this branch. CI #311 passed on the
current amendment head.

### RecoveryEnvelopeV1

Resolved by SYN-005B / OD-044. V1 now freezes strict outer/plaintext wire schemas,
HMAC-SHA256 domain-separated key derivation, Tink AES-256-GCM, exact AAD, complete retained
historical key ring, strict binding validation and fixed derivation/AAD vectors.

Client codec + strict wire/fail-closed tests are present on this branch; Recovery enrollment
and complete revoke/rotate E2E still need application composition around the new contract.

## Still required before D8 FINAL PASS

- full Recovery Secret restore and revocation/AMK rotation E2E using RecoveryEnvelopeV1;
- active application bootstrap/enrollment composition using the production secure-store lifecycle;
- Wear direct-versus-phone-relay integration;
- final PostgreSQL migration/security adversarial suite;
- final repository/server CI green after all of the above.

D8 must not be marked complete and D9 must not start until these paths execute successfully.

## Fresh-device Recovery enrollment transport — RESOLVED

SYN-005C / OD-045 freezes an unauthenticated `POST /v1/recovery/bootstrap` route that returns
only the current rotating-proof counter and the opaque RecoveryEnvelopeV1 for the supplied
accountId. It grants no enrollment authority.

The counter remains mutable server state and is intentionally not embedded in RecoveryEnvelopeV1.
A stale bootstrap snapshot cannot enroll: `/v1/recovery/enroll` still requires the exact current
RecoverySecret proof and atomically advances the proof counter/hash. Clients retry bootstrap after
an InvalidProof race.

Route, JDBC repository, client transport and contract tests are present on this branch. Full
fresh-device restore/catch-up E2E remains required before D8 FINAL PASS.

## BLOCKED_BY_DECISION — remaining-device rotation package discovery

SYN-007 requires one HPKE package for every remaining active device, and the server correctly
rejects an atomic rotation request whose package IDs do not exactly match that set. However, the
authenticated client has no frozen device-directory route or response contract from which it can
obtain the remaining active devices' current HPKE public keys. Local enrollment metadata contains
only the calling device's key pair, while the server currently exposes only pending enrollments.

A decision is required for the authenticated active-device directory used solely to construct a
rotation package set: its response shape, treatment of revoked devices, and the source of each
recipient's HPKE public key. Do not attempt a local-only rotation, reuse stale pending-enrollment
records, or submit an incomplete package set.
