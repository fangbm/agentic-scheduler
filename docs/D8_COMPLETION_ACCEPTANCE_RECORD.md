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

## Remaining-device rotation package discovery — RESOLVED

SYN-007A / OD-046 freezes a durable HPKE public identity for every ACTIVE device and one
authenticated `GET /v1/devices/active` directory. The response is the exact non-revoked device
set for the authenticated account, sorted by deviceId, with only canonical HPKE public keys.

Initial bootstrap, approved pairing, and Recovery enrollment now all persist that identity on the
server. Server migration V8 backfills approved pre-final pairing rows when possible; an ACTIVE
device whose key cannot be recovered makes the directory fail closed with
`DEVICE_DIRECTORY_INCOMPLETE` rather than being omitted.

The rotation client must exclude only the revoke target and package every other directory entry.
The atomic revoke/rotate transaction independently recomputes the remaining ACTIVE device IDs and
rejects a stale/incomplete package set, so membership races require refetch/rebuild rather than
directory versioning.

Directory/server/client/migration contract coverage is present on this branch. Full revoke/rotate
E2E remains required before D8 FINAL PASS.

## BLOCKED_BY_DECISION — rotation package wire and recipient delivery

SYN-007A resolves which devices receive a rotation package, but not the package's byte-level
contract or its recipient lifecycle. `ClientRotationPackage` currently carries opaque bytes only;
the existing `KeyPackageEnvelopeV1` binds a pairing `requestId` and is admitted only by a local
PENDING enrollment, so it cannot be repurposed for an already ACTIVE device by treating
`rotationId` as a request ID. The server stores rotation packages but exposes no route for a
remaining device to fetch and atomically apply its own package.

A frozen decision is required for the rotation package envelope/plaintext/AAD, package retrieval
binding, and ACTIVE-device install semantics. It must preserve one package per remaining device,
bind the `rotationId` and recipient identity, and publish the new AMK/key ring atomically on the
recipient. Do not reuse the pairing envelope or add an unbound blob fetch.
