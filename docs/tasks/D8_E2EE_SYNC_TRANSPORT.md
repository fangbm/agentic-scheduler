# Agentic Scheduler — D8 Encrypted Multi-device Sync & Thin Server

> Task ID: **D8-01 / D8-02 / D8-03**  
> Milestone: **D8 — Sync / E2EE / Server**  
> Status: **IMPLEMENTATION IN PROGRESS — D8 COMPLETION GATE (FROZEN SEMANTICS ONLY)**
> Date: 2026-09-12  
> Decision source: `docs/SYNC_SECURITY_DECISIONS.md`

---

# 1. Goal

Transport D7 mutation groups between enrolled devices, converge deterministically under causality/concurrency, expose explicit conflicts, and keep synchronized user content encrypted from the thin self-hosted server.

D8 output:

```text
client SyncEngine
production EncryptedEnvelopeV1 / SyncPayloadV1
semantic merge + SyncConflict
E2EE content/key management
device pairing/recovery/revocation
HTTPS sync transport
:server:sync Ktor/PostgreSQL server
offline queue/catch-up
Wear route-equivalent sync
```

## D8 completion-gate execution scope

This execution pass is limited to acceptance work already authorized by this task and
`docs/SYNC_SECURITY_DECISIONS.md`:

```text
MUST
- migrate the existing D8 implementation to the current main API without changing
  frozen wire, merge, E2EE, recovery, revocation, server, or Wear semantics;
- complete the SYN-009 Android/Wear Keystore, Windows DPAPI, and Linux Secret
  Service/keyring PlatformSecretStore boundary;
- complete AMK, recovery, pairing, and SyncSpace content-key staging and application
  wiring through that boundary;
- execute and record the remaining SYN-019 and section 15 acceptance paths,
  including multi-device, offline, recovery, revocation, Wear, PostgreSQL,
  migration, and adversarial verification.

MUST NOT
- add a protocol version or new D8 semantic rule;
- weaken fail-closed secret handling, E2EE, causal merge, or conflict behavior;
- begin D9 or any excluded milestone scope.
```

A check remains incomplete until its required test has actually executed. In
particular, production platform storage is not accepted merely because a memory test
or a compile task passes.

---

# 2. Required reading

```text
docs/HISTORY_SYNC_DECISIONS.md
docs/SYNC_SECURITY_DECISIONS.md
docs/OPEN_DECISIONS.md
docs/tasks/D7_OPERATION_HISTORY_SYNC_FOUNDATION.md
docs/IMPLEMENTATION_CONTRACT.md
docs/DOMAIN_INVARIANTS.md
this Task Spec
```

D8 implementation must not begin until D7 is COMPLETE.

D8-01a must be rebased and revalidated after the D7.1 HST-002A amendment. D8-01b
and later may begin only after every D7 semantic operation payload uses the
canonical strongly typed image model; no GenericFact compatibility path is
permitted. That prerequisite is complete on the D7.1 integration branch.

OD-012 local database encryption remains a separate production-sensitive-data release gate; it does not block protocol implementation/tests.

---

# 3. Split

```text
D8-01  client SyncEngine + wire codec + semantic merge/conflict
D8-02  E2EE/key lifecycle + pairing/recovery/revocation
D8-03  :server:sync + PostgreSQL transport + Wear relay/direct integration
```

Security-critical D8-02 primitives and test vectors should land before real user content is sent through D8-03.

---

# 4. Trust boundary

Trusted for plaintext:

```text
enrolled local user devices
local decrypted application state
local platform secure-key boundary
```

Not trusted with user content plaintext:

```text
sync server
PostgreSQL
reverse proxy
wake/push infrastructure
future blob storage
```

The server may deny/drop/reorder traffic. It cannot be allowed to silently forge valid encrypted content.

---

# 5. Local-first rule

A valid local D7 mutation commits without network availability.

After commit:

```text
operation journal
→ encrypt when transport worker runs
→ upload idempotently
```

Network failure never rolls back Active State, ChangeLog, Planner result, or MutationId.

Calendar/Planner/Agent deterministic local operations do not require server reachability.

---

# 6. Wire/E2EE

Implement SYN-003 through SYN-009 exactly.

SYN-005A is frozen for D8 v1: active outbound encryption epoch and retained
historical decrypt-only key ring are separate. Key-package rollback protection
applies to durable key-state transitions, never to valid old ciphertext.

Important invariants:

```text
DVV/HLC are encrypted inner payload
outer server-visible metadata is minimal routing/idempotency metadata
AAD binds space/mutation/sender/key epoch
unknown protocol/mutation version never partially applies
Tink owns AEAD/HPKE primitive implementation and nonce handling
keys/credentials never stored as plaintext Room rows
```

Protocol fixtures must include successful decrypt plus tamper/AAD/wrong-key failures.

---

# 7. SyncEngine receive path

Canonical path:

```text
fetch opaque envelope
→ outer version/identity validation
→ key epoch lookup
→ AEAD decrypt + AAD verification
→ inner payload version/schema validation
→ MutationId dedupe
→ DVV relation
→ semantic group comparison
→ apply causally compatible operation OR create/update SyncConflict
→ append local ChangeLog/operation receipt metadata atomically
→ advance local server cursor only after durable handling
```

Server cursor is not advanced past an envelope that has not been durably accepted/quarantined/conflicted.

A `ProtocolUnsupported` quarantine is durable so reconnect does not spin forever on the same unknown payload.

---

# 8. Merge/conflict

Implement SYN-013/SYN-014 literally.

No HLC LWW.

Atomic MutationId grouping is preserved during replication. Concurrent disjoint groups may commute. If two concurrent transaction groups overlap in a conflicting semantic group, the whole groups become conflict participants.

The provisional MutationId tie-break exists only so every replica renders the same temporary branch. It must remain visibly conflicted and inaccessible to automatic Planner/Agent writes until resolution.

---

# 9. Conflict resolution

Expose application capabilities equivalent to:

```text
sync.listConflicts
sync.getConflict
sync.resolveConflict(conflictId, explicit valid resolution)
```

Resolution runs a normal typed local mutation transaction with DVV context observing all competing operations.

No server-side resolution endpoint accepts plaintext chosen values.

---

# 10. Account/device/key lifecycle

Implement SYN-005 through SYN-009.

Required user flows:

```text
first account/device via one-time server invitation
show/export Recovery Secret
pair second device using pending request + 8-digit SAS confirmation
recover new device using Recovery Secret
list enrolled devices
revoke device
rotate keys after revocation
```

Key material never appears in app logs/screens except the intentionally displayed Recovery Secret during explicit backup/reveal flow.

Recovery Secret reveal should require an explicit local user action; do not show it casually on every settings screen.

---

# 11. Server

Create `:server:sync` with dependency pins in SYN-010.

Server responsibilities:

```text
account invitation/device auth
opaque enrollment/key-package relay
opaque encrypted envelope storage
idempotent upload
cursor-based fetch/long-poll
device revocation metadata
recovery-envelope storage
```

Server forbidden responsibilities:

```text
decrypt SyncPayload
inspect DVV/HLC
run Planner
run semantic merge
run Agent
store provider credentials
resolve SyncConflict
```

No user schedule/task text should appear in server DB fixtures, API logs, structured errors, or metrics labels.

---

# 12. Server persistence/config

Use PostgreSQL/JDBC/HikariCP only for D8 server persistence.

Numbered SQL migrations are source-controlled and transactional.

Required operational rules:

```text
DB credentials only through secret/environment injection
TLS required outside trusted loopback/reverse-proxy termination
bounded upload/fetch batch sizes
bounded ciphertext envelope size
request/body timeouts
no plaintext request logging
```

Exact numeric operational limits may be locally tuned in implementation tests without altering semantic protocol, but safe defaults must be committed/configurable.

---

# 13. Wear

Wear uses the same local operation journal, keys, merge semantics, and MutationIds as Android/Desktop.

Nearby phone relay is transport optimization only. Direct server mode and relay mode must pass route-equivalence tests.

Watch offline mutations remain local until either route becomes available.

---

# 14. Local persistence migration

D8 bumps then-current client schema `N -> N+1` for concepts such as:

```text
AccountId / Personal SyncSpace metadata
Device local metadata
key references/epochs (not plaintext keys)
server cursor/transport receipt state
SyncConflict
protocol quarantine
```

D7 operation/history tables are migrated, not recreated.

Server has an independent numbered SQL schema history.

---

# 15. Required tests

```text
two-device sequential convergence
three-device concurrent convergence
concurrent disjoint Event/Task groups merge
same-group Event/Task conflict
Planner grouped FocusBlock operation atomic replication
FocusBlock move vs delete conflict
PlanningProfile per-weekday compatible merge
Academic aggregate concurrent conflict
WorkLog append idempotency
out-of-order delivery
duplicate delivery
unsupported payload quarantine
wrong-key / tampered ciphertext / wrong AAD
server cannot decode payload fixture
pairing SAS success/mismatch
Recovery Secret restore
revocation + key epoch rotation
revoked credential rejected
network outage/reconnect catch-up
server duplicate MutationId mismatch rejection
Watch relay/direct route equivalence
client migration N -> N+1
server migration replay
```

---

# 16. Explicit exclusions

D8 v1 does not add:

```text
multi-SyncSpace local UI/model
object-level LWW
server plaintext semantic merge
physical tombstone compaction
S3/blob transport before Blob Domain exists
external calendar adapters
AgentThread sync before D9-02
provider credential sync as ordinary operation
password/OAuth account system
```

---

# 17. Completion gate

D8 PASS requires SYN-019 plus:

```text
[ ] D7 COMPLETE before integration begins
[ ] dependency/security pins present in version catalog/server build
[ ] no custom cryptographic primitive code
[ ] every conflict is structured/recoverable
[ ] plaintext-leak fixture scan passes
[ ] client/server migrations tested
[ ] repository + server CI green
```

OD-012 remains mandatory before claiming production-sensitive local-data readiness.
