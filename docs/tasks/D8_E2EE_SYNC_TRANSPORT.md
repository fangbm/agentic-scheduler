# Agentic Scheduler — D8 Encrypted Multi-device Sync & Thin Server

> Task ID: **D8 draft**  
> Milestone: **D8 — Sync / E2EE / Server**  
> Status: **BLOCKED_BY_DECISION**  
> Date: 2026-09-11

---

# 1. Goal

Transport D7 operations between user devices while preserving client-owned semantic merge and keeping synchronized user content encrypted from server infrastructure.

Intended output:

```text
client SyncEngine
+ production SyncOperation wire protocol
+ semantic merge
+ explicit SyncConflict
+ E2EE envelope
+ device/account key management
+ Ktor thin sync server
+ encrypted operation storage
+ reconnect/catch-up
+ Wear nearby/direct transport integration
```

---

# 2. Required decisions before READY

Existing open decisions:

```text
OD-030  wire encoding/versioning
OD-031  field/group semantic merge matrix
OD-040  content encryption primitives/envelope
OD-041  device pairing / key approval
```

Additional architecture/security decisions must be recorded before D8 implementation:

```text
server/account authentication model
device authentication/session model
sync-space/workspace identity and key scope
server Gradle module boundary
exact Ktor dependency/version
exact PostgreSQL client/driver/version
```

OD-012 local database encryption at rest remains a separate production-user-data gate.

---

# 3. Trust boundary

Trusted for plaintext:

```text
enrolled user devices
local decrypted application state
local key-storage/crypto boundary
```

Not trusted with schedule/task/history plaintext:

```text
sync server
PostgreSQL
push/wake infrastructure
future blob storage
```

AI Providers are a separate later privacy boundary and are not D8 participants.

---

# 4. Server responsibility

Server MAY:

```text
authenticate account/device transport requests
store opaque encrypted envelopes
maintain delivery cursors
signal/wake devices
store non-content account/device metadata
```

Server MUST NOT:

```text
decrypt user schedule/task/history payloads
run Planner
run plaintext semantic merge
decide semantic LWW conflict outcomes
become source of truth for Agent memory
```

Transport order/cursor is not causal truth.

---

# 5. Client receive path

Canonical semantic path:

```text
receive ciphertext
→ authenticate/decrypt
→ protocol/version validation
→ inspect DVV relationship
→ apply causal operations
→ semantic merge concurrent operations
→ compatible merge or explicit SyncConflict
→ atomic local commit
```

HLC may provide stable ordering/debug metadata but does not resolve semantic collisions.

---

# 6. OD-031 merge matrix

Before D8 is READY, define a field/group merge matrix for every entity that actually participates in D8 synchronization.

General rule:

```text
causal update
→ apply in causal order

concurrent changes to compatible semantic groups
→ merge

concurrent collision in the same semantic group
→ SyncConflict
```

No generic object-level Last-Write-Wins fallback.

A future new synchronized entity must add its own merge rule before joining the protocol.

Do not predefine AgentThread merge semantics in D8 if AgentThread does not yet exist.

---

# 7. OD-030 wire protocol

Before production transport, freeze:

```text
serialization dependency + exact version
envelope version field
operation discriminators
unknown-version behavior
unknown-field behavior
batching
idempotency identity
compatibility policy
canonical representation requirements if any
deterministic encoding requirements if hashes/signatures depend on bytes
```

Domain entities do not receive wire-format annotations merely for convenience.

---

# 8. OD-040 crypto gate

A dedicated security decision must specify:

```text
audited crypto library
AEAD primitive
key sizes
nonce strategy
authenticated associated data
envelope versioning
key rotation
recovery behavior
test vectors
platform secure-storage integration boundary
```

No custom cryptography.

No coding agent may choose primitives merely to unblock D8.

---

# 9. Key hierarchy / sync-space identity

The architecture direction refers to:

```text
Account Master Key
→ Workspace / Calendar Key
→ encrypted synchronized payloads
```

D8 must first define the concrete object that a Workspace/Calendar Key belongs to.

Do not invent a hidden default workspace merely because the product currently has one local data set.

The decision must cover at minimum:

```text
sync-space/workspace typed identity
ownership/membership
key scope
lifecycle
multiple-space behavior if supported
```

---

# 10. Device enrollment/revocation

OD-041 must define:

```text
existing-device approval
Recovery Key flow
device identity
key-package exchange
revocation
lost-device behavior
anti-rollback/version checks
optional future QR pairing
```

Master/workspace keys are never stored server-side in plaintext.

---

# 11. Server module

The server technology families are already frozen:

```text
Kotlin + Ktor
PostgreSQL
```

D8 still needs an explicit Task Spec decision for:

```text
exact Gradle module(s)
package root
exact dependency versions
server persistence schema ownership
migration strategy
configuration/secrets boundary
```

Do not introduce a universal configuration framework unless OD-071 is triggered and resolved.

---

# 12. Offline/local-first rule

A valid local mutation commits without network availability.

Network failure:

```text
does not roll back committed local state
does not block Calendar/Planner
queues encrypted transport work
retries idempotently
```

---

# 13. Conflict UX contract

`SyncConflict` must contain structured facts sufficient for deterministic UI resolution.

UI may present/collect the user's resolution, but UI does not invent merge semantics.

Resolving a conflict creates a new ordinary local mutation and therefore a new SyncOperation.

---

# 14. Tombstone compaction

OD-032 must be resolved before physical tombstone compaction.

Until then:

```text
preserve deletion history
```

Do not infer safe compaction from server receipt alone.

---

# 15. Wear

Wear remains a real local-first replica.

Logical route equivalence:

```text
Watch local operation
→ nearby Android phone when available
or
→ server directly when independently connected
```

Both routes transport the same logical encrypted operation semantics.

Provider credentials are not ordinary workspace/domain SyncOperations.

---

# 16. Explicit D8 exclusions

Do not add merely because high-level architecture mentions them:

```text
S3/blob transport before Attachment/Blob Domain exists
external calendar adapters
Agent provider credentials
AgentThread synchronization before AgentThread exists
semantic merge rules for nonexistent entity types
```

---

# 17. Required tests once READY

At minimum:

```text
two-device deterministic convergence
three-device concurrency
compatible semantic merge
same-group collision => SyncConflict
offline edits then reconnect
duplicate delivery idempotency
out-of-order delivery
HLC wall-clock rollback behavior
device revocation
wrong-key rejection
tampered-ciphertext rejection
server protocol fixtures contain no user plaintext
Watch nearby/direct route equivalence
wire compatibility/version tests
local persistence migration for Sync metadata
```

---

# 18. READY gate

D8 remains `BLOCKED_BY_DECISION` until:

```text
[ ] OD-030 resolved
[ ] OD-031 resolved for actual D8 entity set
[ ] OD-040 resolved through security review
[ ] OD-041 resolved through security/product review
[ ] server/account auth model frozen
[ ] device auth/session model frozen
[ ] sync-space/key-scope identity frozen
[ ] server module/dependency versions frozen
[ ] server persistence/configuration boundary frozen
```

OD-032 may remain pending only if physical tombstone compaction stays disabled.

---

# 19. Production security reminder

Even successful D8 E2EE does not resolve OD-012.

Server-side E2EE protects synchronized content from server infrastructure. Local database at-rest protection is a separate threat boundary.
