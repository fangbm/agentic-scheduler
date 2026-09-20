# Agentic Scheduler — Reviewed Roadmap D5–D10

> Status: **Roadmap Baseline — individual Task Specs remain authoritative**  
> Baseline: D5-01 complete; D5-02 implemented/build-verified; D6 complete; D6.5 build/Desktop-verified/Android-surface-and-dialog-touch-verified (full input pending); D7 complete; D8/D9 specs frozen; D10 planned
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
D8     E2EE Multi-device Sync + Thin Server    SPEC FROZEN — READY AFTER D6.5 VERIFICATION
 ↓
D9-01  Agent Runtime + Typed Tools             SPEC FROZEN — READY AFTER D8
D9-02  Agent history sync amendment            AFTER D9-01
D9-03  Wear Agent/provider provisioning        AFTER D9-01
 ↓
D10    Final Product UI / UX                   PLANNED — AFTER D9
```

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
D8-01 client SyncEngine/merge      READY AFTER D6.5 VERIFICATION
D8-02 E2EE/key lifecycle           READY AFTER D6.5 VERIFICATION
D8-03 thin server/Wear transport   READY AFTER D6.5 VERIFICATION
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
D9-01 Android/Desktop Agent core       READY AFTER D8
D9-02 synchronized Agent history       AFTER D9-01
D9-03 Wear Agent/provider provisioning AFTER D9-01
```

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
