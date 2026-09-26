# D8 Completion Acceptance Record

> Status: **REOPENED — PRODUCTION RUNTIME CLOSURE IN PROGRESS**
> Branch: `feature/d8-production-runtime-closure`
> Updated: 2026-09-27

This record distinguishes acceptance evidence actually executed from work still required.
It does not start D9.

## Runtime-closure amendment evidence (OD-048 / OD-049)

| Acceptance path | Evidence | Status |
| --- | --- | --- |
| Recovery enrollment request identity | Server V9 persists the domain-separated immutable request fingerprint. Under the account lock it validates the current proof before completion lookup; same identity returns `200 OK` idempotently without advancing the proof, changed identity returns `409`, and invalid proof returns `401`. Client retry retains the original durable PENDING identity, refreshes bootstrap and rebuilds its proof after an ambiguous response. | IMPLEMENTED — targeted source compilation passed locally; PostgreSQL execution pending CI because the local Docker daemon is unavailable. |
| Foreground idle catch-up | One conflated/single-flight trigger combines startup, committed local writes, foreground/network signals and foreground-only periodic polls: Desktop/Android 60 s and Wear 180 s, each with bounded ±10% jitter. Each cycle catches rotation packages up before ordinary encrypted envelopes; transient failures back off while auth/integrity failures stop automatic retry. | IMPLEMENTED — targeted source compilation passed locally; runtime test execution pending CI because this Windows host cannot launch Gradle test workers. |

The rows above are deliberately not marked PASS until the pull request's PostgreSQL and
four-platform CI evidence has completed successfully.

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

## Executed production/platform acceptance

| Acceptance path | Environment / evidence | Status |
| --- | --- | --- |
| Recovery E2E | Real Ktor + PostgreSQL + JDBC repository + fresh Room + Windows DPAPI/Tink; a fresh device uses Recovery Secret to restore the complete key ring and decrypt historical ciphertext. | PASS |
| Revocation/rotation E2E | A/B/C real server lifecycle; A revokes B, C fetches/applies its rotation package, A/C advance to the new epoch, and B is rejected on directory/fetch/upload credential paths. | PASS |
| Historical ciphertext compatibility | After rotation, retained DECRYPT_ONLY epoch material still decrypts pre-rotation ciphertext. | PASS |
| PostgreSQL plaintext leak scan | Public-table JSON scan contains no known Event title, Recovery Secret, DeviceCredential, or raw content-key encoding. | PASS |
| Three-replica/offline sync | `D8ReplicaAcceptanceTest` covers convergence, offline reconnect/catch-up, duplicate delivery and ordering behavior. | PASS |
| Server test suite | `:server:sync:test --rerun-tasks`. | PASS |
| Android production secure store | Android 16 physical device (PGFM10), `:apps:android:connectedDebugAndroidTest`; cross-instance read, content-key AEAD, pairing HPKE private-key restore, corrupt/wrong-reference fail-closed, and delete semantics. | PASS |
| Wear direct/relay logical-route equivalence | `D8ReplicaAcceptanceTest` runs independent direct and nearby-phone opaque-forwarding routes through real Room/Tink replicas. It compares Active State, cursor, handled dots, mutation history and conflict state per route; the phone forwards the exact encrypted envelope without decrypting or rewriting it. | PASS |
| Final D8 security/adversarial aggregate | `gradlew build --rerun-tasks` on Windows with a fresh Docker PostgreSQL instance (`SYNC_TEST_DATABASE_URL` set). All 360 build, protocol, crypto, lifecycle, database and server test tasks completed successfully. Platform instrumentation evidence remains recorded above. | PASS (local) |

## Previously executed component/system acceptance

The following evidence remains valid for the D8 protocol, crypto, server, and
platform components. It does not prove that a production application binary
constructs and activates the D8 runtime. CI run
[`#36006499698`](https://github.com/fangbm/agentic-scheduler/actions/runs/36006499698)
passed on `cf4913a`: Linux Secret Service + PostgreSQL full build/test, Windows
DPAPI, Android Keystore instrumentation, and Wear Keystore instrumentation all
completed successfully.

## Production runtime closure — required before restoring FINAL PASS

The platform secure-store and lifecycle tests construct the D8 dependencies
directly. The Android, Desktop, and Wear compositions must additionally construct
the production ActiveSyncRuntime when explicit deployment/enrollment configuration
is supplied, use its conflict-aware source-fact read/write boundary, and run
catch-up in this order:

```text
rotation packages
-> ordinary authenticated encrypted envelopes
```

The production server must not expose credential-only device revocation. The only
legal revocation operation is atomic revoke-and-rotate, which publishes the new
AMK, content-key epoch, complete per-device rotation package set, and recovery
envelope together with target-credential invalidation.

Completion requires an integration test that starts at the production runtime
factory/composition rather than hand-assembling a test-only lifecycle graph.

Production runtime configuration has no default endpoint. Android and Wear use
app-owned manifest metadata `dev.agenticscheduler.sync.BASE_URL` and
`dev.agenticscheduler.sync.ACCOUNT_ID`; Desktop uses
`-DagenticScheduler.sync.baseUrl` and `-DagenticScheduler.sync.accountId`.
Both values must be present and the URL must be HTTPS. Missing configuration
leaves the app local-only; incomplete configuration fails closed.

Android/Wear packaging supplies the metadata only from explicit Gradle
properties `-Pd8SyncBaseUrl=...` and `-Pd8SyncAccountId=...`; their defaults
are intentionally blank. This is a deployment seam, not a hidden product
endpoint or an invitation/bootstrap UI.

OD-012 local SQLite encryption remains a separate production-sensitive-data
release gate. It does not block this D8 runtime-closure work.

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

The Recovery acceptance test also scans every PostgreSQL public-table JSON projection after real
recovery/upload traffic. A known Event title, Recovery Secret, DeviceCredential and raw content
key encoding must be absent from every server row. That opaque-server plaintext-leak fixture gate
is PASS for the exercised lifecycle data.

## Fresh-device Recovery enrollment transport — RESOLVED

SYN-005C / OD-045 freezes an unauthenticated `POST /v1/recovery/bootstrap` route that returns
only the current rotating-proof counter and the opaque RecoveryEnvelopeV1 for the supplied
accountId. It grants no enrollment authority.

The counter remains mutable server state and is intentionally not embedded in RecoveryEnvelopeV1.
A stale bootstrap snapshot cannot enroll: `/v1/recovery/enroll` still requires the exact current
RecoverySecret proof and atomically advances the proof counter/hash. Clients retry bootstrap after
an InvalidProof race.

Route, JDBC repository, client transport and contract tests are present on this branch; the
fresh-device restore/catch-up E2E is recorded above.

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

Directory/server/client/migration contract coverage is present on this branch; the full
revoke/rotate E2E is recorded above.

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

The full revoke/rotate multi-device E2E is recorded above.
