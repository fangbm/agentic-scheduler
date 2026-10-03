# D9-03-00 — Wear Agent / Provider Provisioning Contract Freeze

Status: **REVIEW PACKET — frozen inherited boundaries + proposed implementation contracts**.
The proposed contracts below are **BLOCKED_BY_DECISION (OD-058)** until maintainer
sign-off. Creating this packet or passing CI does not approve them or authorize
D9-03-01/runtime implementation.

Baseline: latest `feature/d9-02-agent-sync`,
`6583e61fc3101e5373d537f6b90675430a7aec10` (PR #24 merged).
Prepared: 2026-10-04. D9-02 implementation/E2E acceptance is complete and merged;
OD-012 remains **OPEN**. No production-sensitive V3 composition is enabled.

## 1. Authority and immutable scope

| Authority | Binding consequence |
| --- | --- |
| AGT-006 | Reuse D9-01 `OpenAiCompatibleProvider`, Ktor/JSON profile, explicit configuration and structured Tool probe. No Provider SDK or second Provider abstraction. |
| AGT-007 | Provider/model selection remains per-device application state. Credentials resolve only through `PlatformSecretStore`; credentialed HTTP is rejected before secret access. No secret in prompts/history/Tools/logs/workspace sync. |
| AGT-014 | Watch is a local-first replica and can originate Provider requests; STT is optional; Watch Tools use the same application/Planner/permission contracts. |
| SYN-009 | Wear uses Android Keystore-backed storage. SQLite holds only SecretRef/non-secret metadata. This does not resolve OD-012. |
| SYN-017 | Nearby/direct workspace routes have identical logical semantics; a phone relay does not rewrite/decrypt a relayed envelope. This does not define credential delivery. |
| SYN-018 / OD-042 | Existing target D8 HPKE identity and fixed Tink suite; bind version/target/config/revision; persist highest accepted revision per config; reject rollback; wipe/revocation removes secret and advances the revision barrier before replacement. |
| SYN-006A / SYN-007A | Raw X25519 public key, canonical base64url, HPKE base mode/NO_PREFIX, strict target identity. Active directory supplies immutable enrolled public identities. |
| `D9_AGENT_RUNTIME.md` / AGT-003 / AGT-008 | Typed Tools, local user permissions/confirmation, D7 audit and application-owned context truth remain authoritative. |
| `UBIQUITOUS_LANGUAGE.md` | Use WearCapabilityService, aiEntrySupported, userEnabledAiEntry, effectiveAiEntryEnabled, providerReady, requestReady, WearProviderBinding and WearProviderRuntimeState. |
| AGT-013 / D9-02 completed specs | Agent/V3 and business/D7 causal streams and consents remain separate. Credential provisioning belongs to neither stream. |

The default request path is `Watch -> Provider`, including when the OS routes
Watch network traffic through a paired phone. Phone does not execute the Watch
Agent, rewrite Tools, authorize writes or retain a credential for the Watch in
place of Watch secure storage. A provisioning source may hold its own credential
or an explicitly supplied transient credential; that is separate from Watch
request-time ownership.

The only write path remains:
`Provider -> typed Tool -> validation -> Permission Engine -> existing
Application/Planner operation -> ToolResult + AgentAction + D7 audit`.
No Wear DAO mutation path or remote Tool approval is authorized.

This slice changes documentation and proposed fixture artifacts only. It excludes
D9-03 runtime, D10, new Tools/Planner meanings, D7 causality changes, D9-02 V3 or
workspace Envelope/AAD changes, server Agent, Phone AI proxy, MCP, embeddings,
new Provider SDKs, production V3 enablement and OD-012 implementation.

## 2. Repository audit: what exists and what does not

Audit performed on the baseline above; file references are repository-relative.

| Existing surface | Evidence / consequence |
| --- | --- |
| D8 device directory, enrollment/package and rotation routes | `server/sync/src/main/kotlin/dev/agenticscheduler/server/sync/Application.kt`: authenticated `GET /v1/devices/active` and `GET /v1/rotations/packages`; pairing routes retain PENDING admission semantics. No provider credential mailbox/route exists. |
| D8 transport | `KtorSyncLifecycleTransport.kt` and `KtorSyncTransport.kt` in `shared/application/.../sync` deliver enrollment/rotation/workspace packages. None is a credential provisioning API; do not reuse a SyncSpace cursor, pairing requestId or rotationId. |
| HPKE | `PairingHpke.kt` and Android/Desktop `TinkPairingHpke` accept recipient public key, plaintext and contextInfo. The suite is X25519/HKDF-SHA256/AES-256-GCM, base mode, NO_PREFIX; 32-byte encapsulated key is split from ciphertext. Reuse this primitive boundary, not pairing DTO/admission. |
| Secret storage | `SecureKeyLifecycle.kt` exposes `importSecret/readSecret/delete`. `AndroidKeystoreSecureStore.android.kt` allocates a random reference inside import and writes protected bytes. It has no pre-reserved import slot/orphan enumeration contract. Import-then-DB-crash cleanup is therefore an unresolved contract, not solved by copying pairing cleanup. |
| Local Provider configuration | `shared/agent/.../history/AgentState.kt`: `ProviderConfig` carries id/baseUrl/model/context/output/streaming/tool flags/SecretReference. `OpenAiCompatibleProvider.kt` resolves the secret at request time and has a synthetic Tool probe. |
| Wear app | `WearMainActivity.kt` composes D8 and local agenda reads. `apps/wear/build.gradle.kts` has minSdk 30, no shared Agent dependency or Data Layer dependency. Manifest has no STT integration. No Wear Agent/Provider binding/capability/provisioning implementation exists. |
| Nearby route evidence | `SyncTransportWorkerTest` compares two replay transports for logical receive equivalence. It is not a physical Data Layer adapter or credential delivery/peer authentication test. No MessageClient/DataClient/credential revision implementation was found in platform/shared source. |
| Old diagrams | The older on-device-STT-only entry gate is superseded by AGT-014 and this task. The old Data Layer sequence is conceptual, not an exact delivery/ACK/retention authorization. |

### Required sign-off

All entries below are **PROPOSED**, not approved extensions to OD-042.

| Decision | Required maintainer choice | Recommendation |
| --- | --- | --- |
| C1 | Exact wire/secret representation/AAD bytes/bounds and fixture design (section 3) | Approve the v1 candidate as a separate credential protocol. |
| C2 | Delivery, provisioner authentication, ACK/retry/retention (section 4) | A: authenticated opaque server mailbox + explicit target/source content comparison; defer nearby adapter. |
| C3 | Revision ownership, reservations, wipe/removal/replay (section 5) | Target-owned counter per (targetDeviceId, providerConfigId). |
| C4 | Tracked secure-store import/DB publish/crash cleanup (section 6) | Durable prepared-reference journal before secret import; no cross-store atomicity claim. |
| C5 | WearProviderBinding field set/approval/change rules (section 7) | Separate locally approved non-secret binding; envelope carries no ProviderConfig. |
| C6 | Capability/runtime state and network probe contract (section 8) | Stable entry capability independent from transient request availability; reuse D9-01 synthetic Tool probe. |
| C7 | First-alpha optional STT API/language/permission contract (section 9) | On-device platform path where provable; text input remains available. |
| C8 | First-alpha Watch permission ceiling and context bridge (section 10) | Existing conservative policy with Watch-local confirmation; defer PhoneContextBridge. |

OD-042 stays RESOLVED for crypto principles. OD-058 tracks these implementation
choices as PENDING. No C1–C8 approval is inferred from earlier D9 decisions.

## 3. C1 — exact wire candidate (PROPOSED)

### 3.1 DTOs and identity

`ProviderCredentialEnvelopeV1` has exactly these fields, in emitted order:

```text
providerCredentialEnvelopeVersion: integer = 1
targetDeviceId: canonical lowercase UUIDv7
providerConfigId: canonical lowercase UUIDv7
credentialRevision: integer, 1..9223372036854775807
encapsulatedKeyBase64Url: canonical base64url, decoded length 32
ciphertextBase64Url: canonical base64url, HPKE ciphertext including tag
```

`ProviderCredentialPlaintextV1` has exactly:

```text
providerCredentialEnvelopeVersion: integer = 1
targetDeviceId: canonical lowercase UUIDv7
providerConfigId: canonical lowercase UUIDv7
credentialRevision: integer, 1..9223372036854775807
credentialSecretBase64Url: canonical base64url of credential UTF-8 bytes
```

Inner/outer version, target, config and revision must match exactly. Target must
be the current ACTIVE D8 device; config must be the exact target-local approved
binding/reservation. No account master key, SyncSpace key, DeviceCredential,
SecretRef, model, base URL, history, Tool data or provider session is in plaintext.
Account authorization is transport/local enrollment state, not a new key hierarchy.

Candidate v1 secret representation is a non-empty Bearer token of 1..4096 bytes,
ASCII `0x21..0x7e` (thus strict UTF-8), without whitespace/control characters.
No trimming/normalization or implicit secret type conversion is allowed. This is
a proposed restriction for the existing Bearer adapter, not a universal Provider
credential claim. Other credential shapes require a later reviewed version.

### 3.2 Encoding and exact HPKE context

Strict UTF-8 JSON without BOM; malformed UTF-8/surrogates, duplicate decoded
object keys, missing/unknown fields, stringified/fractional/exponent revisions,
null identities, noncanonical IDs/base64url and out-of-range integers reject.
Emit compact JSON in the listed field order, decimal integers, no optional field
omission, and standard JSON escaping. V1 fixtures use only ASCII. Receivers may
accept legal whitespace/key order, but canonical re-encoding defines retry bytes.

Base64url uses only `[A-Za-z0-9_-]`, no padding/whitespace; decoded bytes re-encode
exactly to the supplied text. No PEM/DER/JWK/Tink protobuf/keyset/prefix on the
wire. Ciphertext contains the HPKE AEAD tag; no separate IV/nonce/tag field.

With `LP(s) = U32BE(length(UTF8(s))) || UTF8(s)`:

```text
ProviderCredentialContextV1 =
    ASCII("agentic-scheduler-provider-credential")
    || 0x00
    || U32BE(providerCredentialEnvelopeVersion)
    || LP(targetDeviceId)
    || LP(providerConfigId)
    || U64BE(credentialRevision)
```

These exact bytes are supplied to the existing Tink HPKE `contextInfo` argument.
Suite remains SYN-006A's base-mode RAW/NO_PREFIX suite. This is a candidate
construction of SYN-018's four bindings, not a change to workspace AAD.

### 3.3 Bounds and rejection behavior

| Candidate boundary | Maximum |
| --- | ---: |
| Decoded credential bytes | 4096 |
| Encoded plaintext UTF-8 bytes | 8192 |
| Decoded HPKE ciphertext bytes (tag included) | 8208 |
| Encapsulated key | Exactly 32 decoded bytes / 43 encoded characters |
| Outer envelope UTF-8 bytes | 16384 |
| Single delivery request/response UTF-8 body | 32768 |

First alpha proposes one credential envelope per delivery, no unbounded batch.
Enforce stream/request limit before buffering; base64 limits before allocation;
plaintext cap after authentication and before parsing/import. HPKE ciphertext
must contain at least the 16-byte tag. These values require C1 sign-off and are
independent of D9-02's 256 KiB V3 plaintext cap and D8 deployment limits.

| Input | Required fail-closed result |
| --- | --- |
| Unknown version | `UNSUPPORTED_CREDENTIAL_ENVELOPE_VERSION`; no decrypt/import/revision advance/activation; ordinary sync continues independently. |
| Malformed/bounds violation | `INVALID_CREDENTIAL_ENVELOPE` / `CREDENTIAL_ENVELOPE_TOO_LARGE`; no side effects. |
| Wrong private key, tamper or wrong context | `CREDENTIAL_AUTHENTICATION_FAILED`; redacted, no plaintext diagnostics. |
| Target/config mismatch | `TARGET_MISMATCH` / `PROVIDER_BINDING_MISMATCH`; no secure-store import. |
| Unapproved source/content comparison | `PROVISIONING_APPROVAL_REQUIRED`; no import/activation. |
| Revision/reservation violation | `ROLLBACK_REJECTED` / `UNRESERVED_REVISION` / `CREDENTIAL_INTEGRITY_CONFLICT`, as section 5. |

Fixtures in [fixtures/d9-03-00/](fixtures/d9-03-00/README.md) are candidate canonical
JSON plus exact AAD/SAS transcript bytes. Their README separates public synthetic
primitive evidence from protocol/runtime acceptance. Approval is required before promoting them to
D9-03-01 codec golden tests. No fixture contains a real credential.

## 4. C2 — delivery/source authentication (BLOCKED_BY_DECISION)

### 4.1 Existing crypto does not authenticate the provisioner

Anyone knowing the recipient public key can produce a valid HPKE base-mode
ciphertext. Target/config/revision context binding prevents substitution of those
fields; it does not prove the sender supplied the credential. Bearer auth proves
sender identity to an honest relay, not to the recipient against a malicious relay.
Data Layer node/app identity is also not D8 DeviceId/enrollment approval.

Neither a successful decrypt nor an untrusted relay's sender wrapper may activate
a binding by itself. No second keypair, new signature suite, HPKE suite change or
silent expansion of server trust is proposed as an implementation shortcut.

Candidate source confirmation reuses the D8 human-comparison pattern: the user
compares an 8-digit code displayed by the selected provisioner and target Watch,
and confirms locally on Watch before import. Proposed transcript:

```text
S = ASCII("agentic-scheduler-provider-provisioning-sas") || 0x00
    || LP(provisionerDeviceId) || LP(targetDeviceId) || LP(providerConfigId)
    || U64BE(credentialRevision)
    || SHA256(canonicalEnvelopeUTF8)
    || SHA256(canonicalWearProviderBindingMetadataUTF8)
```

Apply SYN-006A's exact unbiased `SHA256(S || U32BE(counter))` decimal mapping
and mandatory leading zeros. Comparison binds the encrypted credential, target,
revision, selected source and locally approved endpoint/model/capabilities.
It is an explicit new confirmation transcript requiring C2 approval; the prior
pairing SAS must not be reused as a credential authorization code. Its D8-sized
guess/collision bound and dependence on honest user comparison must be reviewed.
This provisions a credential; it grants no remote Tool-confirmation authority.

### 4.2 Minimal delivery choices

| Choice | Behavior | Tradeoff |
| --- | --- | --- |
| A — recommended | Dedicated authenticated opaque server mailbox; target and source are ACTIVE in the same account; existing directory pins target public identity; explicit comparison above gates target install. Nearby transport deferred. | Reuses current HTTPS/enrollment/authorization infrastructure and queues for offline Watch. Requires a reviewed new opaque route/storage/retention contract; cannot initially provision with the relay unreachable. |
| B | A plus a nearby ciphertext adapter using the identical envelope/reservation/receiver. Nearby node discovery is only routing; authenticated D8 source/target eligibility and content comparison still required. | Nearby transfer can reduce latency/use LAN/Bluetooth, but no current adapter exists; offline peer eligibility/revocation freshness and durable ACK semantics need additional decisions. Not an already-implemented alternative. |

SYN-017 route equivalence applies to workspace operations. If B is later approved,
credential install/replay/conflict semantics must also be route-independent;
transport must never mint revisions, decrypt/rewrap or choose a credential winner.

Candidate A lifecycle, requiring approval:

1. Watch approves a non-secret binding, chooses an ACTIVE provisioner and durably
   reserves a revision. A non-secret request is keyed by
   `(targetDeviceId, providerConfigId, credentialRevision)`.
2. Source fetches the target request and active directory through existing D8
   bearer authorization. It encrypts to the exact listed D8 key only after a local
   user transfer action. Local ciphertext outbox persists exact bytes before send.
3. A new dedicated mailbox stores opaque envelope bytes and routing/idempotency
   metadata. Existing workspace envelopes, enrollment packages, rotation storage,
   cursor and protocol remain untouched.
4. Watch fetches only its account/device mailbox while ACTIVE/authenticated.
   It performs strict identity/HPKE validation, content comparison and section 6 install.
5. Target may report `INSTALLED`/`DUPLICATE` with target/config/revision and
   SHA-256 digest of canonical encrypted envelope, never secret/plaintext hash.
   A receipt or HTTP 2xx never advances target counters or makes providerReady.
   Relay-reported ACK is informational against a malicious relay; only the
   Watch's committed local state is authoritative installation evidence.

Candidate acknowledgement DTO has exactly
`providerCredentialAcknowledgementVersion=1`, `targetDeviceId`,
`providerConfigId`, `credentialRevision`, `envelopeDigestBase64Url` (32-byte
SHA-256 of canonical outer JSON), and `result=INSTALLED|DUPLICATE`. Rejections are
redacted transport errors, not successful acknowledgement objects. This receipt
is informational; it contains no proof that defeats a malicious relay.

Persist any target-local approval only against the exact envelope/binding
digests, selected provisioner and live reservation. After restart, missing or
changed approval requires a new user comparison; an old generic "approved"
boolean cannot authorize a different credential or endpoint.

Lost responses retry exact durable ciphertext. Same mailbox key + exact bytes is
idempotent; changed bytes under that key reject at relay as a delivery conflict.
Receiver same-value semantics remain section 5, even if an independently supplied
authenticated envelope was encrypted with different HPKE randomness. Sender
retains encrypted bytes through retry/receipt policy; a failed send is not success.

Proposed retention: unacknowledged mailbox ciphertext expires after 7 days;
acknowledged rows remove ciphertext and retain only bounded delivery metadata
through that expiry. Expiration is explicit `DELIVERY_EXPIRED`, never installation
or counter success. A returning Watch requests a fresh higher reservation if its
delivery is gone. Source keeps encrypted retry bytes until acknowledged/cancelled/
expired. This numeric lifetime and metadata cleanup are C2 decisions, not D8
history/tombstone compaction.

Revoked devices cannot publish/fetch/ACK. Eligibility is checked in the same server
transaction as each mailbox operation. A stale cached directory alone authorizes
no transfer. Remote revocation cannot physically erase an offline device's secret:
D8 immediately denies access; target-local revocation handling/wipe performs sections 5/6
cleanup once observed. No claim of retroactive erasure is made.

No exact HTTP route or server SQL migration is approved by this packet. C2 sign-off
must authorize A or B, source confirmation, idempotency/ACK trust and retention
before any dedicated server or nearby implementation begins. Server receives
zero provider plaintext; do not log bodies or secret comparison values.

## 5. C3 — credentialRevision ownership candidate

Recommendation: Watch is the sole allocator per
`(targetDeviceId, providerConfigId)`, persisted with the existing D8 identity.
An alternative single source-device allocator is simpler for one phone but needs
an approved authority transfer/cross-source serialization/wipe protocol; it is
not recommended for multiple enrolled provisioners.

Target keeps separate durable `highestReservedRevision`,
`highestAcceptedRevision` and `rejectionFloor` plus the current reservation/source.
Initial values are 0; first explicit reservation is 1.
Next reservation is `max(all three counters) + 1`; exhaustion rejects explicitly.
No wall clock/HLC, DeviceId ordering or server cursor allocates/selects revisions.

Only one reservation is live per config. Reserving a newer one cancels the older
one; the target pins its selected source and locally approved binding metadata.
No source may independently increment, seize or fill another source's reservation.
Multiple enrolled sources therefore serialize through target reservations; an
unrequested credential never replaces an active binding.

| Case | Candidate behavior |
| --- | --- |
| New reserved revision > floor/accepted | Authenticate + local approval + full install; accepted revision advances only in DB publish. |
| Same revision, same canonical credential | Idempotent only if that accepted binding is still active and its secure-store value is available/equal; no new import or counter advance. Exact accepted ciphertext can use its stored digest. Rerandomized ciphertext must decrypt and compare transiently to secure-store bytes. |
| Same revision, different credential | Explicit integrity conflict, preserve accepted value/ref/revision; never LWW. Do not persist a plaintext credential digest in DB. |
| Older than accepted or at/below removal floor | Reject without import; no resurrection after wipe/removal. |
| Higher but unreserved/cancelled/wrong source | Reject; peer/server cannot advance target authority. |
| Missing/corrupted accepted secret on retry | Fail closed `CREDENTIAL_UNAVAILABLE`; require explicit fresh higher-revision replacement, no implicit repair/install. |
| Replacement | Fresh reservation and reference; old ref stays current until successful atomic metadata publish; then durable cleanup of old secret. |
| Delete/binding removal/wipe/revocation observed | Advance floor to `max(counters)+1` and cancel reservations before replacement; disable runtime, clear active reference and schedule secret deletion. Retain revision tombstone even after removing binding metadata. |
| Restart | Restore counters/reservation/journal before any Provider use; never reset to revision 1. |
| Total local state loss | Do not retain the same D8 private identity and silently reset the floor. Require D8 identity recovery with a provable floor or fresh D8 enrollment identity; exact same-identity floor recovery is blocked, not guessed. |

A wipe barrier is not falsely described as an accepted credential. Highest accepted
revision remains an audit counter; rejectionFloor invalidates stale accepted bytes.
This candidate preserves SYN-018's monotonic principle while making crash/removal
behavior explicit. Durable floor retention requires future non-destructive migration.

## 6. C4 — install/cleanup state machine candidate

The required order remains:
`authenticate/decrypt -> validate target/config/revision ->
secure-store import -> persist SecretRef + accepted revision ->
activate WearProviderBinding`.
Plaintext is transient within the platform/application security boundary and is
never passed to a Room record, logger, ToolResult, context or provisioning outbox.

Secure-store and SQLite cannot share an atomic commit. Proposed failure atomicity
means no partial/new binding becomes request-visible, with durable orphan cleanup.

```text
RESERVED -> AUTHENTICATED -> USER_APPROVED
         -> PREPARED_IMPORT -> SECRET_IMPORTED -> METADATA_COMMITTED -> ACTIVE
                              \ failure -> CLEANUP_PENDING -> REJECTED
ACTIVE -> replacement -> PREPARED_IMPORT ... -> ACTIVE(new reference)
ACTIVE/RESERVED -> WIPING -> CLEANUP_PENDING -> REMOVED
```

C4 must approve a minimal tracked-import capability at the existing platform
secret boundary: reserve a unique secret slot/reference, persist only that
reference/install identity in a durable journal, then import into that slot.
The current importSecret API cannot guarantee recovery for a crash immediately
after import but before its generated reference returns. Do not call an
untracked best-effort delete a complete orphan-cleanup contract.

The target serializes config installs/removals. It rechecks reservation, enrollment,
approved binding and floor inside the publish transaction after the external import.
Publish atomically writes current SecretRef, accepted revision and binding state;
activation is derived only from committed metadata plus a readable secret.

| Failure/crash | Required candidate state/recovery |
| --- | --- |
| Decrypt/validation/approval fails | No import/journal publish; old accepted binding untouched. |
| Journal/reference preparation fails | No import; no new active binding. |
| Import fails or crashes | Existing binding remains; restart inspects the prepared slot, removes any uncommitted secret, records completion; retry never guesses installation. |
| Secret stored, DB publish fails/crashes | Old ref/revision remain current; prepared reference is known and cleaned at restart. Cleanup failure is durable/retryable; new ref is never usable. |
| DB publish succeeds, old-secret deletion fails | New ref stays current; persistent cleanup task deletes old ref later, never rolls metadata back to it. |
| Wipe races replacement/Provider request | Persist floor + disable binding + cancel reservations/request use + cleanup references atomically in metadata; late publish recheck rejects. Complete wipe only after secret deletion; failures stay visibly cleanup-pending. |
| Restart with committed metadata, secret missing | providerReady false, no plaintext fallback, explicit higher-revision repair action. |
| Orphan cleanup | Delete only journal-owned prepared/retired provider slots; never enumerate-and-delete unrelated D8 keys/secrets. |
| Binding removed then stale envelope replayed | Retained floor rejects; no implicit binding recreation. |

Actual journal layout/schema migration, tracked secret-store port and recovery
proof are D9-03-01 work only after C4/C3 approval. No SQLite credential plaintext
or exported secure-store keyset is an acceptable fallback.

## 7. C5 — WearProviderBinding candidate

This is local non-secret binding metadata mapped into the existing
`ProviderConfig`, not a second Provider Adapter/Agent model. Proposed transferred
metadata DTO `WearProviderBindingMetadataV1` emits:

```text
bindingVersion = 1
providerConfigId
adapterProfile = "OPENAI_COMPATIBLE_CHAT_TOOLS"
baseUrl
model
maxContextUnits
reservedOutputUnits
streamingSupported
toolCallingSupported
credentialRequired
```

All values are explicit; existing ProviderConfig budget invariants apply. No host/
model/capacity/permission default is invented. The target creates/owns config ID
and explicitly approves this exact metadata. A source may offer a non-secret
suggestion; it is never automatically authoritative. Binding changes invalidate
approval and cannot silently send an installed credential/context to a new origin.
Recommend endpoint/credential-affecting changes require a new reservation/revision.

Local binding also holds `credentialReference: SecretReference?` and revision/
approval/install state. SecretRef never travels in the metadata DTO.
`credentialRequired=true` requires the matching approved, installed credential;
false forbids attaching a credential by inference. Credentialed base URLs require
HTTPS before resolving the secret, exactly as AGT-007. Explicit credential-free
HTTP retains the existing local-model allowance and truthful plaintext-risk UI.

The envelope includes only config identity/revision and credential, not this
metadata. Association is through the locally pinned (target, config, reservation)
and C2 content comparison over both canonical artifacts. A server/nearby metadata
suggestion cannot bypass target approval. Provider secrets cannot enter Provider
prompts; a credential used as an authorization header remains outside the prompt.

## 8. C6 — capability/network/runtime state candidate

Derived invariant formulas, preserving the ubiquitous distinctions:

```text
aiEntrySupported = stable supported platform + at least one supported input path
effectiveAiEntryEnabled = aiEntrySupported && userEnabledAiEntry
providerReady = locally approved valid binding
                && supported existing adapter profile
                && !install/wipe blocked
                && (!credentialRequired || matching secure-store secret available)
requestReady = effectiveAiEntryEnabled && providerReady && networkReachable
```

`WearCapabilityService` owns stable OS/input/language facts; local settings own
`userEnabledAiEntry` (candidate default OFF requires C6 approval); provider
composition owns binding/credential state; network infrastructure owns transient
reachability; UI observes derived state, never edits it to bypass readiness.
STT unsupported or permission denied does not remove a supported text input path.
Transient network failure never toggles aiEntrySupported or user preference.

Candidate `WearProviderRuntimeState` has explicit states:
`ENTRY_UNSUPPORTED, ENTRY_DISABLED, NO_BINDING, BINDING_APPROVAL_REQUIRED,
CREDENTIAL_UNAVAILABLE, INSTALL_BLOCKED, OFFLINE, READY, PROVIDER_UNAVAILABLE`.
providerReady/requestReady remain separate derived values rather than synonyms
for these display reasons. Errors carry redacted codes only; several blockers may
be exposed as typed facts rather than relying on a semantic priority tie-break.

Network recommendation: observe Android default-network callbacks, including
capability changes/loss. For public HTTPS endpoints, INTERNET + VALIDATED gives
a usable-route signal, not a guarantee that a specific Provider/model is healthy.
An explicitly configured local credential-free endpoint must not require public
internet validation; its route/attempt result is modeled separately. READY means
a request may be attempted, not that a Provider response is guaranteed.
No generic invented `/health` endpoint or business-data connectivity ping.

Reuse D9-01's synthetic structured Tool probe at explicit binding validation and
before write-capable execution; send only its fixed probe instruction/schema,
never Domain facts, user text, conversation/context, microphone audio or secret
in the body. Credential, if required, is resolved only into the HTTPS auth header.
Unsupported structured Tools cannot become prose-based writes.

Candidate bounds: one single-flight probe, 10-second total deadline, no automatic
retry of an Agent request/write. Foreground/network-change readiness refresh may
retry a failed synthetic probe with injected bounded 2/4/8/16/30-second backoff;
auth/config/unsupported errors stop until explicit retry/config change.
Success/usable route restores readiness automatically without submitting an old
Agent command. Exact lifecycle, timeout/backoff and local-route check API are C6
choices; no background wake guarantee or D8 sync-loop coupling.

Platform facts are checked against
[Android network-state documentation](https://developer.android.com/develop/connectivity/network-ops/reading-network-state):
VALIDATED describes system-tested internet access and still permits endpoint
failures. This source supports the API distinction, not approval of C6 policy.

## 9. C7 — optional STT candidate

First supported speech path: Watch-local platform on-device recognition, guarded
by API/service/language availability, without changing Wear minSdk 30.

- API 31+: `isOnDeviceRecognitionAvailable` followed by
  `createOnDeviceSpeechRecognizer` only when available.
- API 33+: `checkRecognitionSupport` can inspect the selected intent/language.
  On earlier versions, unproven language support remains unverified rather than
  assuming network implies STT. API 30 has no guaranteed equivalent on-device
  probe; speech may be unsupported while text entry remains supported.
- Capability inspection requests no permission, records no audio and starts no
  recognition/model download. If an API/service requires mic authorization even
  for a richer query, defer that query until a user-triggered grant.
- User taps speech input before requesting RECORD_AUDIO. Denial stays distinct
  from unsupported OS/service/language. Request permission never happens during
  passive capability probe.
- No fallback to generic cloud recognizer/Phone remote microphone service.
  Fail/cancel leaves text and ordinary local calendar/task use available.
  Optional model download requires a separate user action; it is not inferred.

Proposed DTO facts: `onDeviceSttAvailability =
UNSUPPORTED | LANGUAGE_UNVERIFIED | AVAILABLE | TEMPORARILY_UNAVAILABLE`,
`speechPermission = NOT_REQUESTED | GRANTED | DENIED`, explicit selected language
tag, and independent `textInputSupported`. A permission denial is not an
UNSUPPORTED capability fact. Exact DTO/OS adapter remains C7, not runtime code.

API levels and on-device methods are verified in the official
[SpeechRecognizer reference](https://developer.android.com/reference/android/speech/SpeechRecognizer).
Device/emulator recognition behavior must be measured in D9-03-02/03; these docs
do not prove that a specific Watch supplies on-device STT.

## 10. C8 — local permissions and context

First-alpha default reuses AGT-003's actual typed enum:

| Capability | Existing conservative default |
| --- | --- |
| READ, PLAN_PREVIEW | ALLOW_DIRECT |
| LOW_RISK_CREATE, SOURCE_FACT_UPDATE, PLANNING_PROFILE_CHANGE, SCHEDULE_APPLY, UNDO | REQUIRE_CONFIRMATION |
| BULK_CHANGE, DESTRUCTIVE, EXTERNAL_SIDE_EFFECT | DENY |

Recommended Watch first-alpha ceiling: writes cannot be relaxed to ALLOW_DIRECT;
user may tighten to DENY, and confirmation occurs on Watch for the exact local
pending call/normalized preview. This extra ceiling is C8, not inferred approval.
No Phone remote approval, broader capability or new Tool is introduced. D6 stale
PlanBranch revalidation and D7 business audit/origin remain shared contracts.

Watch owns its ContextAnchor and obtains current Domain/Application facts through
existing read Tools; limited screen/hardware does not lower AGT-008 truth priority.
PhoneContextBridge is proposed DEFERRED for the first alpha: no current acceptance
path requires supplemental phone context. If later required, only explicitly
typed/source-labelled supplemental non-authoritative facts may be considered in
a separate decision; phone cannot become runtime/context authority.

Watch is an independent Agent replica. Watch-produced completed turns follow the
existing D9-02 per-SyncSpace conversation consent and exact provenance/export
rules. V2 business-write compatibility acknowledgement remains independent;
credential provisioning requires neither consent and changes neither setting.
A successful business Tool still produces ordinary D7 Agent-origin audit and
journal. Credential/SecretRef/config/permission/session data never enters V3.
OD-012 remains OPEN; D9-03 does not activate production-sensitive V3 composition.

## 11. Required adversarial/failure matrix for later implementation

These are required future tests, not claims of executed acceptance in D9-03-00.

| Case | Required observation |
| --- | --- |
| Wrong HPKE private key | Authentication failure, zero import/metadata activation. |
| Tampered ciphertext / wrong exact context | Reject, redact, no counter advance. |
| AAD target mismatch | Reject even when ciphertext is otherwise structurally valid. |
| providerConfigId inner/outer/local mismatch | Reject before import; no substitute binding. |
| Unknown version / malformed JSON/UTF-8/base64 / bounds | Whole credential rejection; independent workspace sync unaffected. |
| Rollback / future unreserved revision | Floor/reservation reject, no LWW/wall-clock authority. |
| Same revision/same value | Idempotent; ref/revision stable across retry/restart and ciphertext re-randomization. |
| Same revision/different value | Explicit integrity failure, accepted secret survives. |
| Unauthorized provisioner / spoofed relay source | HPKE decrypt alone is insufficient; missing/mismatched local comparison prevents install. |
| Revoked target/source / revocation race | Mailbox auth check rejects; observed local revocation disables binding and cleans secret. |
| Replacement | Higher target-owned revision, new ref published atomically, old secret cleanup durable. |
| Crash after import/before metadata commit | Prepared slot recovered/cleaned, no new active binding, no untracked orphan. |
| Concurrent replacement/wipe or two provisioners | Target reservation serialization; late publish rejects; no credential winner by HLC. |
| Wipe/removal then stale replay | Durable floor survives restart; secret stays deleted; no binding recreation. |
| Local state loss retaining old HPKE identity | Fail closed until approved floor recovery/new D8 enrollment. |
| Lost send/ACK, duplicate delivery, expired delivery | Exact bytes retry; no false target installation; expiry is explicit. |
| Relay/DB/log canary scan | No provider plaintext in server/public tables/logs or SQLite; no plaintext credential hash in DB. |
| Network absent / restored | Entry capability/preference stable, request unavailable then restored; no automatic write replay. |
| STT absent / permission denied / failure | Separate states; text Agent remains available; no remote microphone fallback. |
| Stricter Watch policy write | Deny causes zero business writes. |
| Watch read with Phone Agent absent | Real Watch-originated Provider -> typed read round trip succeeds; no Phone proxy. |
| Confirmed Watch business write | Watch-local preview/confirmation -> existing operation -> matching ToolResult/AgentAction/D7 audit; existing V2 gate applies. |
| Watch V3 vs provisioning | Separate consent/frontier/outbox; no credential leak, no automatic consent/history upload. |

All failure phases require file-backed restart tests, schema migration parity and
real platform secure-store evidence where applicable. Simulated route equivalence
does not replace a physical nearby test if B is approved.

## 12. Implementation slices after sign-off

| Slice | Minimum implementation scope | Gate |
| --- | --- | --- |
| D9-03-01 | Approved credential DTO/codec/fixtures, existing HPKE adapter use, target revision/reservations, tracked secure-store journal/install/replacement/wipe, non-destructive metadata migration, approved dedicated opaque delivery if A chosen. No Agent UI/runtime. | C1–C5 approved, source authenticity/ACK/retention specified, full adversarial/crypto/migration/secure-store tests. |
| D9-03-02 | Map WearProviderBinding to existing ProviderConfig; capability/network/optional on-device STT services; manual platform composition. No new Provider abstraction or Tool. | C6/C7 approved; real Watch/emulator capability and permission behavior, no private probe body. |
| D9-03-03 | Shared Agent runtime/Tool registry composition on Watch, Watch-local confirmation and minimal command UI; real Provider/read/confirmed write, restart/offline/wipe provisioning E2E; optional STT cannot be sole acceptance input. | C8 approved, D9-03-01/02 accepted, AGT/D7/D9-02 boundaries and production OD-012 gate preserved. |

If B is chosen, add a separately reviewed nearby adapter sub-slice after the
mailbox/receiver contract; the current code has no adapter to reuse. Do not hide
that addition inside Agent UI or call replay-transport tests physical E2E.

No new Gradle module is proposed: wire/crypto adapters stay in the current
sync/application infrastructure boundary, local Provider/Tools in shared Agent,
persistence contracts/implementation in application/database, platform services
in apps:wear (and companion UI/transport only where approved), opaque relay in
server:sync only after C2 authorization.

## 13. Maintainer decision record and completion boundary

C1–C8 remain **PENDING / BLOCKED_BY_DECISION (OD-058)**. Approval should name
each chosen contract, any changed field/numeric bound and A/B delivery choice.
Then update this packet, AGT/SYN references and OD-058 together before coding.

D9-03-00 delivers an audited review packet, canonical fixture design and slice
gates; it does not claim every implementation contract is frozen. D9-03-01 is
not started. D10 is not started. Keep its PR Draft and wait for maintainer review.

## 14. Documentation/fixture verification

Executed 2026-10-04: 11 JSON fixture encodings checked for UTF-8/no BOM, canonical
compact bytes, expected duplicate-key rejection, canonical base64url/lengths,
independent AAD/hash/SAS reconstruction and exact one-byte ciphertext tampering.
New relative Markdown links resolve. The persisted public HPKE vector decrypts
through existing Tink 1.23.0/JDK17; its stored tampered ciphertext, wrong-target
AAD and a wrong recipient key fail authentication. No runtime/codec or platform
implementation was added to perform these checks; temporary fixture utilities
were outside the repository. These checks are not Wear/provider/install acceptance
or approval of the candidate transcript. Repository CI is tracked on this Draft
PR separately.
