# Agentic Scheduler — Reviewed Roadmap D5–D10 + Post-project Hackathon

> Status: **Roadmap Baseline — individual Task Specs remain authoritative**  
> Baseline: D5-01 complete; D5-02 implemented/build-verified; D6 complete; D6.5 build/Desktop-verified/Android-surface-and-dialog-touch-verified (full input pending); D7 complete; D8 complete; D9 specs frozen and ready; D10 planned; post-project DGX Spark hackathon fork planned
> Date: 2026-09-20

---

# Sequence

```text
D5-01 Calendar / Application read surface      COMPLETE
D5-02 Event/Task creation + editing            IMPLEMENTED / VERIFICATION PENDING
 ↓
D6     Deterministic Planner + PlanBranch      COMPLETE / MERGED / CI GREEN
 ↓
D6.5   Prototype Integration / Dogfood Gate    IMPLEMENTED / DESKTOP VERIFIED / ANDROID TOUCH SURFACE VERIFIED / FULL INPUT PENDING
 ↓
D7     Mutation Journal / History / Undo       IMPLEMENTED / VERIFIED / COMPLETE
 ↓
D8     E2EE Multi-device Sync + Thin Server    COMPLETE — SYN-019 ACCEPTANCE + FOUR-PLATFORM CI PASSED
 ↓
D9-01  Agent Runtime + Typed Tools             IN PROGRESS
D9-02  Agent history sync amendment            AFTER D9-01
D9-03  Wear Agent/provider provisioning        AFTER D9-01
 ↓
D10    Final Product UI / UX                   PLANNED — AFTER D9
 ↓
DGX-H   DGX Spark Server-Agent Hackathon Fork   OPTIONAL — ONLY AFTER MAIN PRODUCT COMPLETION
```

`DGX-H` is explicitly outside the main product dependency chain. It starts from the final completed D10 baseline and may change the trust/runtime architecture for hackathon goals without redefining the contracts of the completed Local-first/E2EE product.

Main product dependency chain remains:

```text
Domain semantics
→ persistence
→ observable calendar
→ deterministic planning
→ dogfood prototype
→ auditable mutations/history
→ encrypted synchronization
→ LLM orchestration
→ final integrated product UI / UX
```

Documentation may be frozen ahead of implementation; implementation order remains gated by completed predecessor contracts.

---

# D5

D5-01 Calendar projection/Agenda-Day is complete.

D5-02 Event/Task creation/editing is present on current main. Its remaining work is verification/polish, not a separate feature merge.

---

# D6

Current split:

```text
D6-00   Planner semantic decisions                    COMPLETE
D6-00A  Planner rewrite clarifications                COMPLETE / FROZEN
D6-R1   deterministic Planner core rewrite            COMPLETE / MERGED / CI GREEN
D6-02   PlanBranch / UUIDv7 / persistence outer work  COMPLETE / REVERIFIED AGAINST REWRITTEN CORE
```

Authoritative sources:

```text
docs/PLANNER_DECISIONS.md
docs/PLANNER_REWRITE_DECISIONS.md
docs/tasks/D6_DETERMINISTIC_PLANNER.md
docs/tasks/D6_PLANNER_CORE_REWRITE.md
```

D6 is now a closed predecessor for D6.5. Later milestones must consume its public semantics rather than reopen Planner policy during UI/infrastructure work.

---

# D6.5 — Prototype / Dogfood Gate

D6.5 is a deliberately thin functional integration layer. It exists to expose real integration seams and make the deterministic Planner usable with personal data before D7-D9 infrastructure work.

Minimum prototype:

```text
PlanningProfile settings UI
FocusBlock rendering
Full Replan entry point
PlanBranch preview
Apply / Cancel
basic structured PlannerIssue / Infeasible display
one Local Reflow entry point
```

Platform target for the interactive dogfood flow:

```text
Android   required
Desktop   required
Wear OS   keep the existing read surface valid; FocusBlock may render there,
          but no new planning-control experience is required in D6.5
```

D5 already provides Event/Task create/edit and Agenda/Day.

D6.5 owns no new Planner semantics and introduces no D7/D8/D9 behavior. UI writes continue through the existing application / PlanBranch transaction boundary; platform UI does not write DAO records directly.

D6.5 is intentionally **not** the final visual-design milestone. It may reuse existing Compose components, temporary layout, current navigation, typography and spacing. It MUST NOT expand into:

```text
full navigation redesign
final design system
pixel-perfect styling against the concept boards
complete responsive/adaptive polish
final motion/animation system
full accessibility polish pass
D10 cross-platform visual consistency work
```

The previous Agentic Scheduler UI concept boards are therefore a D10 visual/product reference, not a D6.5 acceptance target.

D6.5 acceptance is functional: real-data Full Replan -> Preview -> Apply/Cancel works end-to-end, structured failures remain visible, one Local Reflow flow works, and Android/Desktop are usable enough for dogfooding.

Build evidence recorded 2026-09-20:

```text
.\gradlew.bat build --no-daemon                                  PASS
.\gradlew.bat :apps:android:assembleDebug :apps:desktop:createDistributable --no-daemon  PASS
```

The Android debug APK and Desktop distributable were produced. Desktop dogfood passed interactively: profile setup, task creation, valid/invalid Full Replan preview, Apply/Cancel, FocusBlock rendering, and Local Reflow preview. The stable API 36 emulator launched Android `MainActivity`, rendered the Agenda/Day + Planner dogfood surface, and responded to a New Task touch action; full Android Planner input smoke remains pending.

---

# D7 — Mutation / History / Causality

Decision source:

```text
docs/HISTORY_SYNC_DECISIONS.md
```

Status:

```text
D7-00 decisions                      FROZEN
D7-01 mutation coordinator/ChangeLog IMPLEMENTED / VERIFIED
D7-02 Undo                           IMPLEMENTED / VERIFIED
D7-03 DVV/HLC/:shared:sync journal   IMPLEMENTED / VERIFIED
```

Core frozen outcomes:

```text
one logical transaction = one MutationId
ordered typed entity mutation group
Active State + ChangeLog + SyncOperation + causal state atomic
explicit limited Undo compensation
FocusBlock-only delete/tombstone v1
DVV causal truth; HLC ordering metadata only
:shared:sync approved
```

D7 is complete on current main and has no network/server/E2EE.

---

# D8 — E2EE Multi-device Sync

Decision source:

```text
docs/SYNC_SECURITY_DECISIONS.md
```

Status:

```text
D8-00 protocol/security decisions  FROZEN
D8-01 client SyncEngine/merge      COMPLETE
D8-02 E2EE/key lifecycle           COMPLETE
D8-03 thin server/Wear transport   COMPLETE
D8 completion gate                 COMPLETE / SYN-019 + FOUR-PLATFORM CI PASSED
```

Frozen baseline includes:

```text
one visible Personal SyncSpace v1
JSON wire v1 via kotlinx.serialization 1.11.0
Tink 1.23.0 AES-256-GCM content encryption
Tink HPKE X25519/HKDF-SHA256/AES-256-GCM pairing
existing-device approval + 8-digit SAS or Recovery Secret
revocation rotates AMK + SyncSpace key epoch
client semantic merge + explicit SyncConflict; no LWW
Ktor 3.5.2 thin server + PostgreSQL pgjdbc 42.7.13 + HikariCP 7.1.0
server stores opaque encrypted envelopes only
```

Current D8 persistence baselines are client Room schema v11
(outbound eligibility plus ciphertext retry metadata) and server SQL schema v8
(opaque relay, bootstrap, enrollment credential hashes, rotating recovery proof,
atomic rotation, recovery/revocation metadata, and durable ACTIVE-device HPKE identities).
The completion gate migrated and verified the D8 implementation against the current
`main` API, including production platform secret storage, AMK/recovery/pairing/content-key
staging, and the frozen SYN-019 multi-device, offline, recovery, revocation, Wear,
PostgreSQL, migration and adversarial acceptance suite. It did not alter frozen protocol
semantics. D9 may now begin; OD-012 local SQLite encryption remains a separate release gate.

OD-032 tombstone physical compaction remains pending because compaction is disabled.

OD-012 local database encryption remains a separate production-sensitive-data gate.

---

# D9 — Agent Runtime

Decision source:

```text
docs/AGENT_DECISIONS.md
```

Status:

```text
D9-00 decisions                        FROZEN
D9-01 Android/Desktop Agent core       IN PROGRESS
D9-02 synchronized Agent history       AFTER D9-01
D9-03 Wear Agent/provider provisioning AFTER D9-01
```

The D9 review branch now includes the local Agent state repository and Room
schema v12 migration. Its focused migration/retention tests pass. Full D9-01
Tool, Provider, ContextAssembler, audit, and UI acceptance remains open.

Frozen D9-01 baseline:

```text
:shared:agent
LLM -> typed Tool only
reads/previews direct by default; writes require confirmation
permission policy is device-local and Agent-inaccessible
first provider = strict OpenAI-compatible tool-calling subset over Ktor
provider/model settings device-local; secrets in PlatformSecretStore
provider-independent truth/context authority
explicit deterministic context budget
persistent non-authoritative compaction summaries
no automatic raw-thread purge
AgentAction audit linked to MutationIds
no embeddings/MCP requirement for first alpha
```

D9-02 explicitly amends D8; D8 does not pre-invent AgentThread merge behavior.

---

# D10 — Final Product UI / UX

D10 turns the completed product capabilities from D5-D9 into the final coherent cross-platform product experience. It is the first milestone whose acceptance explicitly includes final visual language and complete product-level interaction polish.

## Visual/product reference

The previously approved Agentic Scheduler concept boards are the visual and interaction north star. The final implementation should preserve their shared direction rather than reproduce one screenshot mechanically:

```text
clean, crisp modern visual language
calm spacing and rounded surfaces
light and dark themes
clear hierarchy with restrained shadows/elevation
Agent capability visible from major surfaces instead of buried in settings
consistent Event / Task / Course / Exam / FocusBlock visual vocabulary
preview-and-control interaction for Agent/Planner changes
```

Reference-board product structure to carry into D10:

### Desktop — Windows / Linux

```text
persistent sidebar/navigation for Today, Calendar, Tasks, Courses, Exams,
Planner, Agent/Focus, Analytics/Insights and Settings

global Agent / command bar available from primary surfaces

Today dashboard with schedule, tasks and contextual suggestions
Day/Week/Month calendar views with colored semantic blocks
context/detail panel for selected schedule entities
Planner / PlanBranch preview and control surfaces
History / Undo and Sync/security surfaces from D7/D8
Agent conversation, suggestion and confirmation surfaces from D9
```

The calendar/command interaction should support the concept-board pattern where a selected schedule item exposes contextual actions such as discussing it with the Agent, finding a better time, rescheduling, creating related work, or explaining conflicts — but every action must route through the typed capabilities and permissions defined by earlier milestones.

### Android

```text
adaptive mobile Today / Calendar / Tasks / Agent / More navigation
prominent compact Agent command entry
schedule and task views optimized for touch
Insights / proactive suggestion cards
Planner previews and confirmations that remain understandable on a narrow screen
complete settings, History/Undo, Sync/pairing and provider surfaces
```

### Wear OS

```text
fast Today / upcoming schedule
next-item and heads-up surfaces
compact local actions appropriate to the watch
Agent voice entry only when local STT capability is available and enabled
```

When Wear local STT is unsupported, the Agent entry remains hidden and its setting remains unavailable with an explanatory reason. When STT is supported, the entry may be shown and the user may disable it. Wear remains an offline-capable node rather than a remote-display-only client.

## D10 MUST

```text
final shared design system / tokens for color, typography, spacing, shape and elevation
final light + dark theme behavior
complete Android and Desktop adaptive/responsive layouts
final Wear layouts for the supported Wear feature set
coherent navigation and information architecture across all product surfaces
final creation/editing, calendar, task, course, exam, planner, focus, history,
sync/security, Agent and settings UX
clear loading/empty/error/conflict/offline states
keyboard/mouse quality on Desktop and touch quality on Android
accessibility semantics, focus order, scalable text and contrast review
consistent animation/motion where it improves comprehension
visual regression/screenshot coverage for key surfaces where practical
```

## D10 MUST NOT

```text
change Domain semantics to make a screen easier to implement
redefine Planner legality/ranking/authority
replace D7 mutation/history truth with UI-local history
replace D8 merge/security truth with presentation heuristics
allow the LLM to bypass D9 typed Tool/permission/confirmation rules
introduce a second competing source of truth for schedule or sync state
```

D10 may add presentation-only models/state and platform-specific layout code, but earlier milestone contracts remain authoritative.

---

# Post-project — DGX Spark Server-Agent Hackathon Fork

> Status: **PLANNED / OPTIONAL — create only after the main D5–D10 product is complete**
> Intended branch: `hackathon/dgx-spark-server-agent`

This is an isolated hackathon architecture fork, not D11 and not a replacement for the completed main product. Create it from the final D10 completion commit/tag so the Local-first/E2EE product remains preserved on `main`.

The hackathon goal is to optimize for a DGX Spark hosted demonstration with a browser UI and centralized Agent execution:

```text
WebUI + existing Android/Desktop clients
              ↓ HTTPS + authenticated API
        configured serverBaseUrl
              ↓
server-authoritative schedule/application state
              ↓
server-side Agent + typed Tool execution
              ↓
DGX Spark model runtime / server-side model endpoint
              ↓
server commits schedule mutations
              ↓
revision/update stream
              ↓
WebUI + clients refresh/apply server state
```

## Deliberate trust-model change

The hackathon branch explicitly **drops D8 E2EE for its server-authoritative path**.

Unlike the main product:

```text
main product:
client owns plaintext truth
server stores opaque encrypted envelopes
E2EE protects synchronized user content from the server

DGX hackathon fork:
server receives and stores plaintext schedule/Agent context
server runs LLM requests
server executes accepted schedule mutations
clients receive the resulting authoritative state/changes
```

This is intentional and must be visible in the branch documentation/UI. Dropping E2EE does **not** mean dropping transport or access security:

```text
TLS/HTTPS remains mandatory outside explicit local development
every non-loopback server requires authentication
account/workspace authorization is enforced server-side
server/model credentials never ship to clients
plaintext prompts/schedules are not written to normal request logs
audit records identify Agent-triggered schedule mutations
```

No commit from this fork may silently weaken the E2EE guarantees of `main`. Merging this architecture back to the main product would require a separate trust-model/security review.

## DGX-H milestone split

```text
DGX-00  branch + server-authoritative architecture contract
DGX-01  authoritative schedule API + server persistence
DGX-02  DGX Spark model runtime + server-side Agent/tool execution
DGX-03  WebUI
DGX-04  Android/Desktop remote-server mode
DGX-05  end-to-end demo hardening / deployment
```

### DGX-00 — Architecture fork

Create `hackathon/dgx-spark-server-agent` from the completed D10 baseline.

Freeze these fork-only rules before implementation:

```text
server is authoritative for synchronized schedule state
client setting contains serverBaseUrl, not model credentials
server owns model endpoint/model selection configuration
LLM never writes SQL/database records directly
LLM writes still go through typed application Tools/mutation services
server-generated schedule changes retain MutationId/audit linkage where practical
first hackathon version may support online writes only
clients may keep read caches, but must not invent a second authoritative write path
D8 E2EE transport is disabled/removed only for this fork's centralized path
```

Keeping the existing typed Tool/mutation boundary is important even though execution moves to the server: it preserves deterministic validation, Planner legality, auditability, and prevents the model from becoming a direct database writer.

### DGX-01 — Server-authoritative scheduler

Add a plaintext authenticated server API and authoritative persistence adapter.

Minimum capabilities:

```text
bootstrap/fetch current schedule state
create/edit/delete supported schedule entities through application services
Planner/PlanBranch operations needed by the Agent
history/audit fetch
revision or cursor based incremental updates
client reconnect/catch-up
```

Prefer reusing `:shared:domain`, Planner semantics, typed mutation vocabulary, and validation rules on the JVM server. The server persistence adapter may use PostgreSQL, but HTTP handlers and LLM code must not write tables directly.

For the first hackathon implementation, offline client writes may be disabled. This avoids recreating D8's full multi-writer conflict protocol inside a short-lived centralized fork.

### DGX-02 — DGX Spark model deployment and Agent execution

Run or connect the server to the model runtime on DGX Spark. The exact inference engine remains an implementation choice for the fork, but the application-facing model adapter should keep a small OpenAI-compatible/tool-calling style boundary where practical.

Configuration belongs server-side, for example conceptually:

```text
MODEL_BASE_URL
MODEL_NAME
MODEL_API_KEY / local-runtime credential when applicable
```

Clients configure only the Agentic Scheduler server address and their authentication/session material.

Agent request flow:

```text
client/WebUI command
→ scheduler server
→ assemble authoritative context
→ server-side LLM request
→ typed Tool calls
→ validate/preview/confirm according to fork UX
→ application mutation transaction
→ authoritative state commit
→ mutation/result pushed or fetched by clients
```

The LLM must not receive a general-purpose SQL/database tool.

### DGX-03 — WebUI

Add a browser client using the same authenticated server API as native clients.

Initial WebUI scope:

```text
Today / Agenda
Calendar
Tasks
Planner preview
Agent chat/command entry
Agent mutation confirmation/result
basic History/audit
server/model health indicator
settings/session/logout
```

The WebUI should not introduce a separate schedule implementation or business-rule engine. Domain truth remains on the server.

### DGX-04 — Native remote-server mode

Android/Desktop gain a fork-specific server configuration flow:

```text
serverBaseUrl
authenticate/enroll
fetch authoritative state
submit user commands/ordinary edits to server
receive revision/update notifications
refresh local read cache/UI
```

For v1 of the hackathon fork:

```text
online writes are allowed
offline read cache is optional
offline writes may be explicitly unavailable
local D8 E2EE sync is not used for this remote-server mode
```

Wear can remain out of scope unless the hackathon demo specifically benefits from it.

### DGX-05 — Demo/deployment gate

Before the hackathon demo, verify at minimum:

```text
fresh server deployment on DGX Spark environment
model process starts and health checks pass
WebUI can connect from another device
Android/Desktop can connect using only serverBaseUrl + auth
LLM request -> typed schedule mutation -> durable server commit -> client update works end-to-end
unauthenticated callers cannot read or mutate schedule data
cross-account/workspace access is denied
server restart preserves authoritative schedule state
model failure does not partially commit a schedule mutation
duplicate/retried requests do not accidentally duplicate committed mutations
normal logs contain no plaintext schedule/prompt bodies
```

The hackathon branch may trade the main product's offline-first/E2EE properties for centralized simplicity and model throughput, but it should preserve the project's typed semantic, Planner, mutation, and audit boundaries wherever possible.

---
# Cross-milestone schema rule

Future implementation Task Specs use:

```text
current schema N -> N+1
```

and substitute an exact number only when the immediately preceding merged schema is known.

No milestone may overwrite another milestone's migration or use destructive fallback.

---

# Cross-milestone new-concept rule

Every new durable/synchronizable concept defines before production use:

```text
typed identity
owner/module
persistence mapping
local-only vs synchronized
merge policy before joining Sync
retention/deletion semantics
canonical vocabulary
```

D6 keeps request Constraints and PlanBranch session/local-only.

D7 makes mutation history durable.

D8 owns encrypted transport and merge.

D9 owns conversation/Agent orchestration and only later amends D8 for Agent records.

D10 should add presentation state, not new durable semantic truth, unless a separately reviewed earlier-layer contract explicitly requires it.

---

# Production data security reminder

OD-012 remains PENDING.

D5-D10 may be developed/tested on the current local persistence baseline, but the project must not claim production-sensitive local-data readiness until local database at-rest protection is explicitly resolved.
