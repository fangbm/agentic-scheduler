# Agentic Scheduler — D8 Sync / E2EE / Server Decisions

> Status: **D8-00 FROZEN**  
> Date: 2026-09-12  
> Applies to: D8 encrypted multi-device synchronization and thin self-hosted server

This document freezes the protocol/security/product choices required for D8. D8 implementation remains sequenced after D7, but the choices below are no longer open architecture guesses.

Current dependency pins were selected against stable upstream releases available on 2026-09-12. A security advisory may require an explicit patch-level upgrade before implementation; do not silently downgrade.

---

# SYN-001 — Sync space v1

D8 introduces typed identities:

```text
AccountId
SyncSpaceId
DeviceId
```

V1 creates exactly one **visible Personal Sync Space** when an account is initialized.

All existing local source facts are assigned to that Personal space during D8 migration. This is an explicit v1 product rule, not a hidden workspace inferred by the server.

Domain entities do not gain a `workspaceId` field in D8 v1. The local installation has one active Personal space. Multiple spaces/accounts per local installation are deferred and require a future scoping decision/migration.

A SyncSpace owns one active content-key epoch.

---

# SYN-002 — Synchronized source-fact set

D8 synchronizes D7 typed operations for:

```text
Event
Task
PlanningProfile
FocusBlock
WorkLog
TaskDependency
AcademicYear
Semester (+ AcademicWeek aggregate state)
Course
PeriodTemplate (+ period aggregate state)
AcademicHoliday
CourseScheduleRule (+ teaching-week aggregate state)
CourseOccurrenceException
Exam
```

Derived/session-local concepts do not synchronize:

```text
CourseSession
CalendarProjection
PlanBranch
request PlanningConstraint
ContextSummary
```

ChangeLog is reconstructed/appended from received SyncOperations; it is not a second independently merged network object.

D9 adds Agent records only through the D9-02 protocol amendment.

---

# SYN-003 — Wire format v1 / OD-030

Production transport uses:

```text
org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0
UTF-8 JSON
outer envelope version = 1
inner payload version = 1
```

Outer envelope concept:

```text
EncryptedEnvelopeV1
- envelopeVersion = 1
- syncSpaceId
- mutationId
- senderDeviceId
- keyEpoch
- ciphertextBase64Url
```

DVV, HLC, origin, and typed entity mutation payloads are encrypted inside `SyncPayloadV1` rather than exposed to the server.

Unknown additive outer JSON fields are ignored. Unknown `envelopeVersion` is rejected without decrypt attempt.

After decrypt, unknown inner `payloadVersion` or unknown mutation discriminator quarantines the **entire MutationId** as `ProtocolUnsupported`; no partial apply.

No Domain class receives serialization annotations merely for transport. Dedicated sync DTOs map to/from Domain/application values.

Wire JSON byte ordering is not a security primitive and is not signed/hashed. Idempotency uses typed IDs, not ciphertext hashes.

---

# SYN-004 — E2EE primitive / OD-040

Client content cryptography uses Google Tink Java/Android **1.23.0**.

Sync payload encryption uses Tink `AES256_GCM` AEAD with library-managed nonces.

No custom nonce generation and no custom crypto primitive implementation.

Authenticated associated data is the exact UTF-8 string:

```text
agentic-scheduler-sync|v1|<syncSpaceId>|<mutationId>|<senderDeviceId>|<keyEpoch>
```

On decrypt, every AAD component is compared with the parsed outer envelope before payload application.

Tamper/authentication failure produces a structured transport/security error and no local state change.

---

# SYN-005 — Key hierarchy and epochs

V1 hierarchy:

```text
Recovery Secret (user-held 256-bit random secret)
        ↓ protects recovery envelope
Account Master Key (AMK, random 256-bit)
        ↓ wraps active SyncSpace keys
SyncSpace Key epoch N (random 256-bit AES-GCM key)
        ↓ encrypts SyncPayloadV1
```

AMK and SyncSpace keys never appear server-side in plaintext.

Each device stores its current AMK/key material only through the platform secure-key boundary.

A Recovery Secret is generated once on first-account setup, displayed/exportable to the user, and never uploaded in plaintext. The server may store only an authenticated encrypted recovery envelope containing the current AMK/key-epoch metadata.

Recovery Secret encoding is a lossless human-copyable base64url/grouped representation with checksum; it remains 256 bits of random entropy. Do not replace it with a user password or low-entropy PIN.

Key epochs are monotonic integers. A device that has observed epoch `N` rejects a **key-state/package transition** that attempts to move it to an older epoch.

### SYN-005A — D8 v1 active epoch and historical decrypt ring (approved amendment)

The active encryption epoch and historical decryption keys are distinct:

```text
new outgoing payloads       -> activeEncryptionEpoch only
installed key epoch < active -> DECRYPT_ONLY
incoming historical envelope -> decrypt normally when its retained key exists
incoming epoch > active      -> missing future key; do not advance receive cursor
```

An old authenticated envelope is not a key-state rollback. D8 v1 retains all
legitimately installed historical SyncSpace keys as `DECRYPT_ONLY` and performs
no historical-key GC. Future retention/GC requires an explicit causal-stability,
snapshot and recovery decision.

Pairing and recovery packages carry the active key plus the historical
decrypt-only key ring required to consume retained server envelopes. Revocation
rotates only future encryption material: it does not promise to erase material
the revoked device legitimately possessed before rotation.

Installing an epoch is one durable atomic operation:

```text
no current state                              -> INSTALL
new epoch > active epoch                      -> ADVANCED
same epoch + same key identity                -> IDEMPOTENT
same epoch + different key identity           -> INTEGRITY_ERROR
new epoch < active epoch                      -> REJECTED_ROLLBACK
```

The local durable model is a `sync_space_key_state` active epoch plus one
`sync_space_content_key` row per epoch. Rows contain only opaque secure-store
references and an `ACTIVE` or `DECRYPT_ONLY` usage marker; they never contain
key bytes.

Every content-key row also carries a non-secret `ContentKeyIdentity`, frozen as
`base64url-no-padding(SHA-256(raw AES-256 content-key bytes))`. This identity
is calculated by the platform secure-key implementation while it imports or
generates the key, and is the only value used to decide whether an epoch is
idempotently replayed. A `SecretReference` is a local opaque handle and must
never be used as a key identity. Complete key-package installs validate every
overlapping historical epoch, atomically repair missing historical keys, and
return which imported references were adopted versus reused so unused imports
are deleted after the transaction commits.

A malicious server can always withhold newer data from a newly recovered device; D8 does not claim global freshness/transparency guarantees against a server that suppresses all newer state.

---

# SYN-006 — Device crypto identity / pairing / OD-041

Every device creates a Tink HPKE key pair for receiving device-specific key packages.

Pairing encryption uses Tink's recommended HPKE profile:

```text
DHKEM_X25519_HKDF_SHA256
HKDF_SHA256
AES_256_GCM
```

Enrollment flow:

```text
new device generates DeviceId + HPKE key pair
→ server stores a PENDING enrollment request with public key
→ new device displays the exact 8-digit SAS defined by SYN-006A
→ an existing enrolled device fetches the request and user compares the code
→ existing device explicitly approves
→ existing device HPKE-encrypts current key package to new-device public key
→ server relays opaque package
→ new device decrypts, validates identities/version/key epoch, then becomes ACTIVE
```

The 8-digit code is an out-of-band human MITM check, not an encryption key.

The key-package AAD binds:

```text
accountId
requestId
targetDeviceId
keyPackageVersion
keyEpoch
```

Future QR pairing may encode the same request identity/public key/SAS data without changing key-package semantics.

Recovery enrollment is also supported: possession of the Recovery Secret decrypts the current recovery envelope, after which the new device registers as an enrolled device and rotates its server credential.

Implementation boundary: the v1 HPKE key package intentionally excludes
`DeviceCredential`. The exact one-time credential handoff/registration proof for
secondary pairing and recovery is tracked as `OD-043` in
`docs/OPEN_DECISIONS.md`; clients and the server must not infer it from the
opaque package relay.

### SYN-006A — D8 v1 pairing wire and SAS contract (approved amendment)

SYN-006A freezes the cross-device byte-level contract. Implementations must not
persist or transmit Tink keyset/protobuf serialization as the pairing protocol.
The HPKE suite is fixed by `keyPackageVersion = 1`; v1 does not negotiate KEM,
KDF, AEAD, output-prefix type, or alternate suites.

#### SYN-006A-1 — HPKE public-key wire encoding

The canonical v1 public-key field is:

```text
hpkePublicKeyBase64Url =
    base64url-no-padding(
        RFC9180.SerializePublicKey(X25519 public key)
    )
```

For `DHKEM_X25519_HKDF_SHA256`, `SerializePublicKey` is the raw 32-byte X25519
public key. Therefore v1 requires:

```text
decoded public-key length = exactly 32 bytes
base64url alphabet only
no '=' padding
canonical re-encode must equal the received input
wire text length = exactly 43 characters
```

The following are not valid pairing public-key wire encodings:

```text
Tink Keyset proto
Tink HpkePublicKey protobuf serialization
PEM
DER
JWK
Tink output prefix / key id
```

The v1 suite is exactly:

```text
DHKEM_X25519_HKDF_SHA256
HKDF_SHA256
AES_256_GCM
HPKE base mode
RAW / NO_PREFIX
```

#### SYN-006A-2 — Key-package envelope and plaintext schema

The opaque server-relayed outer envelope is strict UTF-8 JSON:

```json
{
  "keyPackageVersion": 1,
  "accountId": "...",
  "requestId": "...",
  "targetDeviceId": "...",
  "keyEpoch": 8,
  "encapsulatedKeyBase64Url": "...",
  "ciphertextBase64Url": "..."
}
```

`encapsulatedKeyBase64Url` is base64url-without-padding of the 32-byte HPKE
encapsulated X25519 key. `ciphertextBase64Url` is base64url-without-padding of
the HPKE ciphertext, including its AEAD authentication tag. V1 does not expose
or transmit a separate nonce, IV, or tag field.

The decrypted plaintext is strict UTF-8 JSON:

```json
{
  "keyPackageVersion": 1,
  "accountId": "...",
  "requestId": "...",
  "targetDeviceId": "...",
  "keyEpoch": 8,
  "accountMasterKeyBase64Url": "...",
  "syncSpace": {
    "syncSpaceId": "...",
    "activeEpoch": 8,
    "activeKeyBase64Url": "...",
    "historicalKeys": [
      {
        "keyEpoch": 6,
        "keyBase64Url": "..."
      },
      {
        "keyEpoch": 7,
        "keyBase64Url": "..."
      }
    ]
  }
}
```

The decoded AMK, active SyncSpace key, and every historical SyncSpace key are
exactly 32 bytes. `RecoverySecret` and `DeviceCredential` never appear in this
package.

`historicalKeys` has these v1 rules:

```text
keyEpoch values are unique
all historical keyEpoch values < activeEpoch
sender emits entries in ascending keyEpoch order
sender MUST include its complete locally retained DECRYPT_ONLY ring
```

The recipient does not infer omitted epochs from numeric continuity. Package
completeness is a sender/package-builder responsibility. `ContentKeyIdentity`
is not transmitted; the recipient derives it from each received raw key as
`base64url-no-padding(SHA-256(raw AES-256 content-key bytes))` before invoking
the durable key-ring installer.

Before any secure-store import, the recipient validates all of:

```text
plaintext.keyPackageVersion == envelope.keyPackageVersion == 1
plaintext.accountId         == envelope.accountId
plaintext.requestId         == envelope.requestId
plaintext.targetDeviceId    == envelope.targetDeviceId
plaintext.keyEpoch          == envelope.keyEpoch
plaintext.keyEpoch          == plaintext.syncSpace.activeEpoch
requestId                   == current local PENDING enrollment request
targetDeviceId              == current device
```

Any mismatch rejects the whole package, imports no partial key material, and
leaves the device PENDING. V1 key-package JSON is strict: duplicate object keys,
unexpected fields, malformed/noncanonical base64url, duplicate historical
epochs, invalid lengths, and unsupported versions are rejected rather than
silently ignored. Future schema changes require a new `keyPackageVersion`.

The exact HPKE `contextInfo` / AAD byte string is length-prefixed, not delimiter
concatenated. Define:

```text
LP(s) = U32BE(length(UTF8(s))) || UTF8(s)
```

Then:

```text
KeyPackageContextV1 =
    ASCII("agentic-scheduler-key-package")
    || 0x00
    || LP(accountId)
    || LP(requestId)
    || LP(targetDeviceId)
    || U32BE(keyPackageVersion)
    || U64BE(keyEpoch)
```

These exact bytes are supplied as the Tink HPKE/hybrid `contextInfo` value.

#### SYN-006A-3 — Exact 8-digit SAS mapping

SAS input uses the canonical raw 32-byte X25519 public key, not its base64url
text. Reuse the `LP(s)` definition above and define:

```text
S =
    ASCII("agentic-scheduler-pairing-sas")
    || 0x00
    || LP(accountId)
    || LP(requestId)
    || LP(deviceId)
    || hpkePublicKeyRaw32
```

The unbiased deterministic decimal mapping is:

```text
M     = 100000000
LIMIT = 18446744073700000000

for counter = 0, 1, 2, ...:
    digest = SHA-256(S || U32BE(counter))
    x = U64BE(digest[0..7])
    if x >= LIMIT:
        continue
    sasNumber = x mod M
    stop
```

`LIMIT` is the largest multiple of `100000000` below `2^64`, so rejection avoids
modulo bias. The canonical protocol SAS is the base-10 representation of
`sasNumber`, left-padded with ASCII `0` to exactly eight digits:

```text
regex: [0-9]{8}
42 -> "00000042"
```

Leading zeros are mandatory protocol data. A UI may visually group the eight
digits, for example `0000 0042`, but comparison and transport use the ungrouped
eight-character value.

Cross-platform implementations must include this frozen test vector:

```text
accountId = "acct-1"
requestId = "req-1"
deviceId  = "device-1"

hpkePublicKeyRaw32 =
00 01 02 03 04 05 06 07
08 09 0a 0b 0c 0d 0e 0f
10 11 12 13 14 15 16 17
18 19 1a 1b 1c 1d 1e 1f

hpkePublicKeyBase64Url =
AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8

counter = 0
SHA-256(S || U32BE(counter)) =
1eabe35e9ae2c9157d72294828e5f9ff191c6de708942dd147e5ab3df625d770

SAS = "20345109"
```

---

# SYN-007 — Revocation and key rotation

Device revocation immediately invalidates its server DeviceCredential.

For future-content cryptographic exclusion, revocation also performs a new key epoch:

```text
new random AMK
+ new random active SyncSpace key
+ HPKE packages for every remaining active device
+ new recovery envelope protected by the same Recovery Secret
```

A revoked device may retain plaintext/ciphertext/key material it legitimately possessed before revocation. D8 does not claim retroactive erasure.

No new payload may be encrypted with a revoked epoch after rotation commits locally.

---

# SYN-008 — Server/account authentication

D8 v1 is self-host friendly and does not introduce passwords/OAuth as a hidden dependency.

Account bootstrap uses a server-admin generated one-time invitation token:

```text
256 random bits
base64url encoded
short expiration
single use
server stores only SHA-256(token)
```

After enrollment each device receives an independent random 256-bit `DeviceCredential` bearer secret. The server stores only `SHA-256(DeviceCredential)` and account/device metadata.

The credential is transport authorization, not an E2EE key.

All non-loopback production transport requires TLS. A reverse proxy is allowed, but forwarded headers are trusted only when explicitly configured.

DeviceCredential is revocable/rotatable and stored in platform secure storage. Agent/provider code never receives it.

---

# SYN-009 — Platform secure-key boundary

Define one application/infrastructure interface equivalent to:

```text
PlatformSecretStore
PlatformKeyMaterialStore
```

Required platform backends:

```text
Android / Wear OS -> Android Keystore-backed protection
Windows Desktop   -> Windows DPAPI-backed protection
Linux Desktop     -> Secret Service / desktop keyring-backed protection
```

Database rows store secret references/metadata only, never plaintext AMK, SyncSpace keys, Recovery Secret, DeviceCredential, or provider credentials.

The exact JNI/JNA/D-Bus adapter is an implementation detail behind this boundary; replacing the native bridge does not change the security contract.

OD-012 remains a separate gate for encrypting the entire local SQLite database at rest. Secure key storage does not imply that ordinary local schedule/chat rows are encrypted.

---

# SYN-010 — Server module and dependency baseline

D8 authorizes:

```text
:server:sync     Kotlin/JVM 17
```

Pinned baseline:

```text
Ktor server/client family           3.5.2
PostgreSQL JDBC                     42.7.13 or newer security-fix patch in 42.7.x
HikariCP                            7.1.0
kotlinx.serialization JSON          1.11.0
Tink Java/Android                   1.23.0
```

Server database access is JDBC + HikariCP; do not add a second ORM merely for D8.

Server schema migrations are explicit numbered SQL resources applied transactionally at startup/admin migration time. No destructive fallback. A server whose database schema is newer than the binary refuses to start writes.

Server secrets come from environment/secret injection. Non-secret server settings use Ktor/ApplicationConfig or environment overrides. No checked-in secret config and no project-wide universal configuration framework.

This resolves OD-071 for D8/D9 needs; unrelated future config systems require a new decision only if they exceed this boundary.

---

# SYN-011 — Thin server data model

Server may persist only transport/account metadata and opaque encrypted bytes.

Conceptual tables:

```text
account
account_invitation_hash
device
device_credential_hash
device_enrollment_request
device_key_package
sync_space
sync_space_membership
encrypted_operation_envelope
recovery_envelope
```

`encrypted_operation_envelope` minimum columns:

```text
sync_space_id
server_cursor BIGINT monotonic per space or globally
mutation_id UNIQUE within space
sender_device_id
key_epoch
ciphertext bytes
received_at server metadata
```

No plaintext title, task text, timetable, ChangeLog payload, DVV, HLC, Agent message, or Planner result is stored by the server.

Server cursor is delivery position only; it is never causal truth.

---

# SYN-012 — Transport behavior

V1 uses ordinary authenticated HTTPS request/response transport. WebSocket/push is optional wake optimization, not semantic infrastructure.

Required capabilities equivalent to:

```text
upload envelope batch
fetch envelopes after server cursor
optional bounded long-poll
register/fetch/approve device enrollment
fetch key package / recovery envelope
device revoke
```

Upload idempotency:

```text
same (spaceId, mutationId) + same envelope metadata/ciphertext -> success/idempotent
same (spaceId, mutationId) + different envelope             -> integrity conflict/reject
```

Clients may receive duplicates and out-of-order operations. The SyncEngine deduplicates by MutationId and uses DVV after decrypt.

Network failure never rolls back a committed local D7 mutation.

---

# SYN-013 — Semantic merge / OD-031

Causal rule:

```text
remote causally after local facts -> apply
remote causally before/equal      -> ignore/idempotent
concurrent disjoint semantic groups -> merge
concurrent same semantic group with equal normalized value -> coalesce
concurrent same semantic group with different value -> SyncConflict
```

No object-level or field-level timestamp LWW exists.

One MutationId is an atomic replication group. If any child mutation in two concurrent groups semantically conflicts, the groups are treated as conflicting units; do not partially apply only the convenient children.

For unresolved conflict, clients converge on a deterministic **provisional display branch** chosen by lexicographically smallest MutationId. This tie-break does not resolve the conflict and is not semantic LWW:

```text
SyncConflict remains durable/visible
write Tools/Planner may not automatically modify the conflicted entity/group
user resolution emits a new MutationId whose DVV observes all competing dots
that resolution causally dominates and clears the conflict
```

Server never runs this merge.

---

# SYN-014 — V1 semantic merge groups

## Event

```text
title
time placement as one group
flexibility
pinState
```

Different groups merge; differing concurrent writes to the same group conflict.

## Task

```text
title
status
priority
effort = estimated + completed + remaining as one invariant group
deadline = deadline + deadlinePolicy + overflowPolicy as one group
```

## FocusBlock

```text
time placement
flexibility + pinState authority group
existence/tombstone
```

Concurrent move vs move conflicts if ranges differ. Concurrent delete vs any differing put conflicts. Causally later delete wins by causality, not timestamp.

## PlanningProfile

```text
name
configured/unconfigured mode
timeZone
focus duration triple (min/preferred/max) as one group
allDayEventPolicy
weeklyAvailability per DayOfWeek (seven independent groups)
```

Concurrent configured/unconfigured transition conflicts with concurrent configuration-field edits.

## WorkLog

Append-only by WorkLogId. Same ID + same normalized value deduplicates. Same ID + different value is an integrity conflict.

## TaskDependency

Whole dependency endpoints are one group. Same ID with different endpoints conflicts.

## Academic source facts

For D8 v1 each persisted academic aggregate is one conservative semantic group:

```text
AcademicYear
Semester (+ weeks)
Course
PeriodTemplate (+ periods)
AcademicHoliday
CourseScheduleRule (+ teaching weeks)
CourseOccurrenceException
```

Concurrent differing writes to the same aggregate conflict. This intentionally prefers explicit conflict over guessing fine-grained academic merge semantics.

## Exam

```text
title
schedule
```

---

# SYN-014A — Typed semantic normalization and Exam reference group

> Status: **D7.1 AMENDMENT — FROZEN before D8 receive implementation**

D8 semantic merge consumes the canonical strong semantic images from HST-002A;
it never interprets `Map<String, String>` or runtime-defined field schemas.
Normalized equality is image equality after the frozen collection
canonicalization rules.

Exam adds one structural reference group:

```text
reference = semesterId + courseId
title
schedule
```

`schedule` is always one sealed value. Concurrent `DateOnly` and `Exact`
values therefore conflict rather than being field-spliced. Academic aggregates
remain conservative whole-aggregate groups; this amendment does not introduce
finer-grained academic merge.

---

# SYN-015 — Conflict resolution UX contract

`SyncConflict` stores structured references to:

```text
conflictId
syncSpaceId
entity identities/groups
competing MutationIds + DVVs
normalized candidate values
provisional MutationId
common causal ancestry when available
status OPEN / RESOLVED
resolutionMutationId?
```

UI may select one candidate or construct a new valid value. Resolution always goes through the normal typed application mutation path.

No "dismiss and silently keep local" operation exists.

---

# SYN-016 — Tombstone compaction / OD-032

Physical tombstone/history compaction remains **disabled** in D8 v1.

FocusBlock tombstones and the operations required to interpret them are retained.

Server receipt, device count, or elapsed time alone is not proof that deletion history is safe to erase.

OD-032 remains PENDING until an explicit causal-stability/retention design exists.

---

# SYN-017 — Wear route equivalence

Wear is a full local replica.

Logical operation semantics are identical whether transport is:

```text
Watch -> nearby Android relay -> server/other replicas
Watch -> server directly
```

Phone relay cannot decrypt/rewrite an envelope solely because it relays it; it handles the same account's normal client logic only when acting as an enrolled replica.

Provider credentials remain outside ordinary SyncOperations.

---

# SYN-018 — ProviderCredentialEnvelope / OD-042

D9-03 credential provisioning reuses the D8 device HPKE public key.

Credential envelope encryption uses the same Tink HPKE profile as SYN-006 and binds AAD:

```text
providerCredentialEnvelopeVersion
targetDeviceId
providerConfigId
credentialRevision
```

Only the target device decrypts. The target persists the highest accepted `credentialRevision` per provider config and rejects rollback.

A credential envelope is never a workspace SyncOperation and is never written to ChangeLog.

Revocation/wipe deletes the target device's secure-store secret and increments credential revision before any replacement envelope.

---

# SYN-019 — D8 completion gate

D8 implementation is complete only when:

```text
[ ] two- and three-device deterministic convergence tests pass
[ ] duplicate/out-of-order delivery is idempotent
[ ] semantic-group compatible merges pass
[ ] same-group conflict is explicit and convergent
[ ] conflict resolution causally dominates all candidates
[ ] offline local mutation never depends on server availability
[ ] wrong-key/tamper/AAD mismatch fails closed
[ ] pairing SAS mismatch prevents approval
[ ] recovery flow restores keys without server plaintext access
[ ] revocation rotates AMK + SyncSpace key epoch
[ ] server fixtures contain no user plaintext/DVV/HLC
[ ] Watch relay/direct transport yields equivalent logical state
[ ] wire v1 compatibility/unknown-version tests pass
[ ] local and server migrations pass
[ ] repository/server CI is green
```

OD-012 local database encryption remains a separate production-sensitive-data release gate.
