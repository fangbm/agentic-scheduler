# Agentic Scheduler — Reviewed Roadmap D5–D9

> Status: **Roadmap Baseline — individual Task Specs remain authoritative**  
> Baseline: D5-01 complete; D5-02 implemented; D6-R1 Planner core rewrite implemented (CI green on branch); D7-D9 specs frozen
> Date: 2026-09-12

---

# Sequence

```text
D5-01 Calendar / Application read surface      COMPLETE
D5-02 Event/Task creation + editing            IMPLEMENTED / VERIFICATION PENDING
 ↓
D6     Deterministic Planner + PlanBranch      CORE REWRITE PASSING (CI green; merge pending)
 ↓
D6.5   Prototype Integration / Dogfood Gate    READY AFTER D6
 ↓
D7     Mutation Journal / History / Undo       SPEC FROZEN — READY AFTER D6
 ↓
D8     E2EE Multi-device Sync + Thin Server    SPEC FROZEN — READY AFTER D7
 ↓
D9-01  Agent Runtime + Typed Tools             SPEC FROZEN — READY AFTER D8
D9-02  Agent history sync amendment            AFTER D9-01
D9-03  Wear Agent/provider provisioning        AFTER D9-01
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
D6-R1   deterministic Planner core rewrite            IMPLEMENTED / VERIFICATION GREEN (CI green; merge pending)
D6-02   PlanBranch / UUIDv7 / persistence outer work  RETAINED / REVERIFIED AGAINST REWRITTEN CORE
```

Authoritative sources:

```text
docs/PLANNER_DECISIONS.md
docs/PLANNER_REWRITE_DECISIONS.md
docs/tasks/D6_DETERMINISTIC_PLANNER.md
docs/tasks/D6_PLANNER_CORE_REWRITE.md
```

D7 implementation must not treat D6 as complete until D6-R1 conformance + repository CI pass and the original D6 task is explicitly closed.

---

# D6.5 — Prototype / Dogfood Gate

After D6 closes, add a deliberately thin integration layer before disappearing into later infrastructure work.

Minimum prototype:

```text
PlanningProfile settings UI
FocusBlock rendering
Full Replan button
PlanBranch preview
Apply / Cancel
basic Planner issue/infeasible display
one Local Reflow entry point
```

D5 already provides Event/Task create/edit and Agenda/Day.

D6.5 owns no new Planner semantics. Its goal is to make the deterministic core usable with real personal data and start dogfooding before D7-D9.

---

# D7 — Mutation / History / Causality

Decision source:

```text
docs/HISTORY_SYNC_DECISIONS.md
```

Status:

```text
D7-00 decisions                      FROZEN
D7-01 mutation coordinator/ChangeLog READY AFTER D6
D7-02 Undo                           READY AFTER D6
D7-03 DVV/HLC/:shared:sync journal   READY AFTER D6
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

D7 has no network/server/E2EE.

---

# D8 — E2EE Multi-device Sync

Decision source:

```text
docs/SYNC_SECURITY_DECISIONS.md
```

Status:

```text
D8-00 protocol/security decisions  FROZEN
D8-01 client SyncEngine/merge      READY AFTER D7
D8-02 E2EE/key lifecycle           READY AFTER D7
D8-03 thin server/Wear transport   READY AFTER D7
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

---

# Production data security reminder

OD-012 remains PENDING.

D5-D9 may be developed/tested on the current local persistence baseline, but the project must not claim production-sensitive local-data readiness until local database at-rest protection is explicitly resolved.