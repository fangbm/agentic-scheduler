# D8 Completion Acceptance Record

> Status: **IN PROGRESS — RECOVERY/REVOCATION LIFECYCLE E2E PASSED; FINAL ACCEPTANCE STILL OPEN**
> Branch: `feature/d8-final-acceptance`  
> Updated: 2026-09-24

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

Client codec + strict wire/fail-closed tests and fresh-device Recovery enrollment application
orchestration are present on this branch. `D8RecoveryLifecyclePostgresAcceptanceTest` now executes
the actual Ktor route pipeline, JDBC/PostgreSQL, a fresh Room replica, Windows DPAPI and Tink
envelope receive: the recovered device imports the complete retained ring and applies historical
epoch-7 ciphertext. This is PASS-level Recovery Secret restore/catch-up evidence.

## Still required before D8 FINAL PASS

- active application bootstrap/enrollment composition using the production secure-store lifecycle;
- Wear direct-versus-phone-relay integration;
- final PostgreSQL migration/security adversarial suite;
- final repository/server CI green after all of the above.

D8 must not be marked complete and D9 must not start until these paths execute successfully.

Current application-level revocation evidence is intentionally narrower than that final E2E:

- ACTIVE initiator validates the complete directory before secure-store staging, creates one
  retryable staged attempt, and publishes its AMK/key-ring metadata only after the server accepts;
- a transient submit retry reuses the exact prepared request bytes for one `rotationId`;
- remaining ACTIVE devices strictly bind relay wrapper to envelope, then catch up through the
  ACTIVE-to-ACTIVE recipient path;
- PostgreSQL V1→V8 numbered migration/replay runs in the CI PostgreSQL job.

`D8RevocationRotationPostgresAcceptanceTest` additionally executes the shared real Ktor route
pipeline with JDBC/PostgreSQL, Room key metadata, Windows DPAPI and Tink HPKE across A/B/C:
A revokes B, C fetches and installs its server-stored package, A/C encrypt at epoch 8, C still
decrypts epoch-7 history, and B's directory/fetch/upload credential paths all return 401. This is
PASS-level revoke/rotate multi-device lifecycle evidence. A fresh recovery device is covered by
the companion Recovery acceptance test above.

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

## Rotation package wire and recipient delivery — RESOLVED

SYN-007B / OD-047 freezes a rotation-specific HPKE package. It is domain-separated from pairing,
binds `rotationId + targetDeviceId + keyEpoch`, and has strict outer/plaintext schemas carrying
the new AMK plus the complete active/historical SyncSpace key ring.

Remaining ACTIVE devices fetch only their own opaque rows through authenticated
`GET /v1/rotations/packages`. The client verifies the transport wrapper against the strict HPKE
envelope before applying it.

ACTIVE apply never reuses pairing activation semantics. Installed/Advanced packages atomically
publish the key ring and new ACTIVE AMK reference while preserving device/enrollment/HPKE/
credential identity. Idempotent/Repaired replay keeps the existing AMK; rollback/integrity errors
fail closed. Exact wire/context and ACTIVE replay tests are present on this branch.

Full revoke/rotate multi-device E2E remains required before D8 FINAL PASS.
