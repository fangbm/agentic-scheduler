# Agentic Scheduler — Module & Responsibility Ownership

> Status: **Frozen Ownership Baseline v1**  
> Purpose: make it unambiguous which layer owns each rule, state transition, side effect, and dependency.

This document is about **ownership**, not just directory names. If a coding agent does not know which layer should implement behavior, use this document before inventing a new service/module.

---

# 1. Current module baseline

```text
:shared:domain
:shared:application
:shared:database
:apps:android
:apps:desktop
:apps:wear
```

D4 explicitly introduced `:shared:application`. No other Gradle module exists by default. Future modules from the architecture/roadmap are conceptual until a Task Spec explicitly creates them.

---

# 2. Dependency direction

Current concrete dependency direction:

```text
Platform Apps
    │
    ├───────────────┐
    ↓               ↓
:shared:application   :shared:database
    │               │
    ↓               ├──────────────→ :shared:application
:shared:domain       │
                    └──────────────→ :shared:domain
```

Future Agent/Planner/Sync modules, when explicitly created, remain higher-level consumers of Domain/Application contracts rather than dependencies of `shared:domain`.

`shared:domain` is at the bottom of dependency direction.

It never imports:

```text
UI
Android framework
Wear APIs
Database framework
Networking
Provider SDKs
Sync transport
Crypto implementation
```

---

# 3. Ownership matrix

| Concern | Owning layer/module | May validate/observe | Must not own |
|---|---|---|---|
| Entity semantics | `shared:domain` | UI, DB defensively | DB/UI/LLM |
| Single-entity invariants | `shared:domain` | DB defensively | UI-only checks |
| Cross-entity invariants | Domain service / future shared logic | Planner/DB may call | LLM |
| ID type definitions | `shared:domain` | all layers use | platform UI |
| Production ID generation | application/infrastructure | Domain receives ID | entity constructor reading randomness |
| Time value semantics | `shared:domain` | DB maps | platform-local ad hoc types |
| Current clock source | application/infrastructure | Planner receives value | arbitrary business methods |
| Task dependency cycle validation | Domain service | UI may pre-check | DB cascade |
| Application-facing repository contracts | `shared:application` | apps/future services consume | `shared:domain` / platform UI |
| Application transaction contract | `shared:application` | database implements | UI/provider |
| Planner feasibility | Planner | Agent/UI consume | LLM/provider |
| Planner scoring | Planner | explanation layer reads | LLM hidden preference |
| DecisionReason | Planner | Agent verbalizes | LLM invents |
| PlanBranch lifecycle rules | Planner/application domain logic | UI renders | UI state machine alone |
| Atomic PlanBranch Apply | application transaction boundary | DB implements transaction | UI/provider |
| Persistence schema | `shared:database` | Domain mapping | `shared:domain` |
| Domain↔persistence mapping | `shared:database` | tests | UI |
| DB migrations | `shared:database` | app startup orchestrates | Domain |
| Repository implementation | `shared:database` / data infrastructure | application consumes | Domain entity |
| SyncOperation creation | Sync/application layer | transaction can coordinate | UI/provider |
| DVV/HLC | Sync layer | audit/debug reads | Domain business meaning |
| Semantic merge | Sync + domain merge policy | UI resolves conflicts | generic JSON merge |
| Tombstone lifecycle | Sync/persistence | Domain delete initiates | DB cascade alone |
| E2EE encrypt/decrypt | crypto/infrastructure boundary | Sync transports ciphertext | server plaintext logic |
| Key storage | platform security infrastructure | crypto consumes | Domain |
| AgentThread | Agent/application persistence | ContextAssembler reads | Provider session |
| ContextAnchor | platform/session UI layer | ContextAssembler reads | Sync |
| ContextSummary | Agent context subsystem | ContextAssembler reads | authoritative state |
| ContextAssembler | Agent application layer | Provider receives output | Provider adapter |
| Provider adapter | Agent infrastructure | maps provider API | business rules |
| Tool schema | Agent Tool layer | Provider invokes | arbitrary provider JSON |
| Tool permission | Permission Engine | UI configures | LLM |
| Tool execution | application Tool layer | Agent requests | Provider direct writes |
| AgentAction | Agent/audit layer | history/UI reads | chat-only transcript |
| ChangeLog | history/audit layer | Agent history tools read | Provider memory |
| Undo | application/domain operation layer | UI/Agent invokes | deleting history |
| History retrieval | read-only history/search layer | Agent consumes | mutation layer |
| Android lifecycle/integrations | `apps:android` | shared interfaces | Domain |
| Desktop lifecycle/integrations | `apps:desktop` | shared interfaces | Domain |
| Wear capability probing | `apps:wear` / Wear infrastructure | Agent runtime reads | Domain |
| Wear provider secure storage | Wear platform infrastructure | Wear adapter reads | workspace sync entity |
| Phone↔Wear nearby transport | Wear/Android sync infrastructure | Sync engine uses | Domain |
| UI formatting/localization | platform/shared UI | Domain values input | Domain semantics |

---

# 4. Validation ownership

Validation is deliberately layered.

## Domain construction validation

Owns rules that make one object nonsensical if violated.

Examples:

```text
start < end
remainingEffort >= 0
valid strongly typed ID representation
```

## Domain service validation

Owns rules requiring multiple domain objects.

Examples:

```text
Task dependency graph is acyclic
referenced Course exists when required
PlanBranch dependencies are coherent
```

## Planner validation

Owns scheduling feasibility.

Examples:

```text
can 4h of remaining work fit before deadline?
would this proposal create forbidden occupancy?
does this violate FreezeHorizon?
```

## Permission Engine

Owns whether a valid proposed action is authorized.

A valid action can still be denied by policy.

## Persistence validation

May add defensive constraints, but persistence errors do not define business semantics.

## UI validation

May prevent obvious bad input early for UX, but successful UI validation never bypasses domain validation.

---

# 5. State ownership

## Authoritative current schedule state

Owned by local application/domain persistence.

## Proposed schedule state

Owned by `PlanBranch`, isolated from Active State.

## Historical state changes

Owned by ChangeLog / SyncOperation / AgentAction according to their semantics.

## Conversation continuity

Owned by AgentThread/AgentMessage/ContextSummary.

## Current UI referent

Owned by ContextAnchor and kept local to device/session.

## Provider API session/cache state

Owned only as provider infrastructure optimization. It is disposable and non-authoritative.

---

# 6. Side-effect ownership

| Side effect | Owner |
|---|---|
| write active domain state | application transaction + persistence |
| create SyncOperation | sync integration at successful transaction boundary |
| create AgentAction | Agent action/audit layer |
| append ChangeLog | history/audit integration at transaction boundary |
| send Provider request | Provider Adapter |
| send push/wake signal | sync/server infrastructure |
| encrypt user payload | client crypto boundary |
| schedule platform notification | platform reminder infrastructure |
| call external Calendar API | external-calendar adapter |
| provision Watch provider secret | dedicated device provisioning path |

No UI composable/activity or LLM adapter directly owns a multi-entity business transaction.

---

# 7. Transaction ownership

A logical operation defines its atomic boundary above the concrete database API.

Conceptually:

```text
Application command
    ↓
Domain/Planner validation
    ↓
ApplicationTransactionRunner
    ├─ persist domain changes
    ├─ append ChangeLog when that milestone exists
    ├─ append AgentAction if applicable
    └─ emit SyncOperation(s) when Sync exists
```

D4 established the concrete Room transaction implementation in `shared:database`; higher layers express atomic intent through `shared:application` and do not import Room APIs.

The LLM, UI, and Provider Adapter cannot split or reorder a declared atomic operation.

---

# 8. Agent ownership

The Agent orchestrates intent; it does not become the owner of deterministic rules.

```text
LLM/Agent owns:
- language interpretation
- deciding which typed read tools may help
- proposing typed actions
- user-facing explanation composition

LLM/Agent does not own:
- calendar arithmetic
- recurrence expansion
- conflict truth
- Task dependency validity
- permissions
- Planner scoring truth
- transaction semantics
- sync conflict resolution policy
```

---

# 9. Context ownership

ContextAssembler may pull from multiple sources, but it must preserve their identity and authority.

```text
Current Domain State       authoritative current fact
ToolResult / ChangeLog     authoritative execution/history fact
AgentThread                conversation continuity
ContextSummary             lossy helper
ContextAnchor              local ephemeral context
```

Do not create a single untyped `memory` blob that hides these distinctions.

---

# 10. Database mapping rule

D4 established persistence types as storage representations, not the domain model itself.

Canonical direction:

```text
DatabaseRecord
    ↕ mapper
DomainEntity
```

The mapper may encode storage details, foreign keys, normalized tables, or serialization details without leaking those mechanics into `shared:domain`.

A coding agent must not decide that "less boilerplate" justifies annotating Domain entities with persistence concerns.

---

# 11. Sync merge ownership

The Sync layer detects causality/concurrency using DVV and orders/debugs with HLC.

Domain-aware merge policy decides whether concurrent field/group changes are compatible.

Example:

```text
location changed on device A
notes changed on device B
→ semantic merge may succeed
```

```text
startTime changed differently on A and B
→ explicit SyncConflict
```

Neither HLC recency nor database update time automatically resolves semantic conflicts.

---

# 12. External adapter ownership

Google Calendar, Outlook, CalDAV, ICS, LLM Providers, server transports, and Wear transport are adapters around the internal model.

External schemas do not become internal Domain definitions.

Mapping loss/unsupported fields must be explicit rather than silently changing Domain semantics to match an external provider.

---

# 13. Platform ownership

Platform code owns capability and integration facts that cannot be portable.

Examples:

```text
Android Notification access
Quick Settings Tile
Share Target
Desktop tray/global shortcut
Wear STT capability probe
Wear secure credential store
Wear Data Layer transport
```

Shared Domain consumes structured results/interfaces, not Android/Wear framework objects.

---

# 14. New responsibility placement algorithm

When adding behavior, choose its owner in this order:

```text
Does it define what a domain concept means?
→ Domain

Does it orchestrate repositories/transactions/application queries?
→ Application

Does it decide schedule feasibility/optimization?
→ Planner

Does it persist/migrate/map data?
→ Database/Data

Does it replicate/merge across devices?
→ Sync

Does it encrypt/manage cryptographic material?
→ Crypto infrastructure

Does it interpret language/orchestrate Tools?
→ Agent

Does it adapt a vendor/platform API?
→ Adapter/platform module

Does it only render/collect user interaction?
→ UI
```

If the answer crosses multiple categories and creates a new long-lived boundary, mark `ARCHITECTURE_AFFECTING` and require a decision rather than inventing a new `Manager` class.

---

# 15. Generic bucket prohibition

Do not solve ownership uncertainty by creating ambiguous global classes/packages such as:

```text
Utils
Manager
CommonService
AppHelper
GlobalState
Misc
DataManager
AIManager
```

Use a name that states the owned semantic responsibility. If no precise owner/name exists, that is evidence that a design decision is still missing.

---

# Final ownership invariant

**Every authoritative fact, validation rule, transaction, and side effect has one primary owner. Other layers may observe or defensively validate it, but they do not redefine it.**
