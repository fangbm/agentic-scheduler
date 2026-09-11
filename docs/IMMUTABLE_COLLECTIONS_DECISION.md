# Agentic Scheduler — Immutable Collections Decision

> Status: **RESOLVED / FROZEN**  
> Date: 2026-09-11  
> Scope: **project-wide for D3 and every later milestone/module**

This decision freezes the project-wide collection-immutability implementation baseline that was previously described only semantically as "immutable public Domain state".

---

# 1. Decision

Use JetBrains/Kotlin's official multiplatform library:

```text
org.jetbrains.kotlinx:kotlinx-collections-immutable:0.5.2
```

The version is pinned in the Version Catalog when implementation begins.

**From D3 onward, this dependency is pre-authorized for all repository code and all future modules.** A task, contributor, or Coding Agent does not need a new dependency decision or ADR merely to use `kotlinx.collections.immutable` where it is technically appropriate.

This authorization includes, without being limited to:

```text
shared:domain
shared:database
future application/core modules
Planner code
PlanBranch / Preview state
Sync in-memory state and reducers
Agent context/snapshot state
Android/Desktop/Wear application code
server-side Kotlin modules
future tests and utilities
```

A module should still declare the dependency only when that module actually uses it. Project-wide authorization does not mean every module must depend on it.

The library is currently experimental and its API is subject to change. The repository therefore pins an exact version; unrelated work must not silently upgrade or downgrade it.

The selected release supports Kotlin Multiplatform and requires Kotlin stdlib 2.3.0 or newer. The repository Kotlin baseline is 2.3.21.

---

# 2. Public Domain collection rule

For **stored public Domain state**, use collection types that make immutability part of the type contract when aliasing or later mutation would otherwise violate invariants.

Preferred API types:

```text
ImmutableList<T>
ImmutableSet<T>
ImmutableMap<K, V>
```

Use `PersistentList`, `PersistentSet`, or `PersistentMap` when persistent-update operations / structural sharing are intentionally part of the state transition implementation.

Do not expose mutable builders as Domain state.

Ordinary `List`, `Set`, `Map`, and `Collection` remain valid for:

- function inputs that are consumed/validated and not retained by reference;
- local temporary results inside deterministic pure algorithms;
- implementation details whose mutability does not escape;
- APIs where the collection is not retained as authoritative Domain state and the current task explicitly freezes the ordinary Kotlin collection type.

At a storage/ownership boundary, convert external collections explicitly, for example:

```kotlin
input.toImmutableList()
input.toPersistentList()
```

The resulting Domain object must not retain an alias to caller-owned mutable collection state.

---

# 3. `data class` rule

A `data class` is preferred when immutable collection types make the generated `copy`, `equals`, `hashCode`, and `toString` semantics correct by construction.

Do not use a generated `copy()` when it can bypass required canonicalization or validation.

Therefore:

- `Semester` and `PeriodTemplate` may return to `data class` once their collection-valued fields are typed as immutable collections;
- `TeachingWeekSet` keeps a validated/canonicalizing factory because arbitrary `copy(weeks = ...)` must not bypass sort/dedup/non-empty invariants;
- ordinary scalar/value objects continue to use `data class` where appropriate.

---

# 4. Project-wide usage rule

Later code may freely choose immutable/persistent collection types from this approved library when they improve correctness, state isolation, snapshotting, structural sharing, or API clarity.

Typical uses include:

```text
PlanBranch / Preview snapshots
Planner candidate states
Undo / reversible state transitions
Domain snapshots assembled for Agent context
Sync reducer in-memory state
Dependency graph snapshots
UI/application immutable state
server-side immutable state
other branch/rebase/merge-like state derivation
```

Persistent collections are especially appropriate when many derived states share most of their contents.

The following remain engineering rules rather than authorization barriers:

```text
use the simplest appropriate collection type
avoid converting every local List/Map mechanically
prefer immutable public state when ownership matters
use builders/mutable scratch collections locally when that is clearer or faster
keep explicit deterministic ordering where semantics require it
```

This dependency does **not** become a persistence layer. Operation Log, durable snapshots, Sync metadata, database records, and authoritative durable storage remain owned by their appropriate persistence/sync modules.

---

# 5. D1 / D2 compatibility

D1's architectural decision was a pure Kotlin Multiplatform Domain boundary. This library is a pure KMP collection dependency and does not violate that boundary.

D2 explicitly prohibited adding a collections framework **inside the D2 milestone** and authorized no third-party dependency other than `kotlinx-datetime`. That historical scope fence remains correct and must not be rewritten retroactively.

The new dependency is first introduced by the explicit D3 amendment in `docs/tasks/D3_IMMUTABLE_COLLECTIONS_AMENDMENT.md`. From that point forward it is a **project-level approved dependency**, not a D3-only exception.

D2 production types currently do not expose collection-valued stored public state that requires migration. Its dependency-cycle validator may continue using local mutable collections internally because they do not escape the pure validation call.

Future work may use this dependency while extending or interacting with D1/D2-era code without reopening the old milestone task specs, provided the frozen D1/D2 semantics remain unchanged.

---

# 6. Dependency declaration policy

The version is centralized in `gradle/libs.versions.toml`.

Later modules may add:

```kotlin
implementation(libs.kotlinx.collections.immutable)
```

without a new architecture/dependency decision whenever the module needs the library.

Do not:

```text
pin a different version in an individual module
use a second immutable-collections framework for the same role without a new decision
add the dependency to an unused module merely for symmetry
let library-specific representation define persistence or wire compatibility
```

---

# 7. Upgrade policy

Because the library is experimental:

```text
pin exact version
no silent upgrades in unrelated work
review changelog before upgrade
run repository-wide tests after upgrade
avoid depending on mutable Builder types in public APIs
avoid assuming serialization format from collection implementation details
```

A future stable 1.0 release may justify a dedicated upgrade task, but must preserve Domain and cross-module semantics rather than redefining them.
