# Agentic Scheduler — Immutable Collections Decision

> Status: **RESOLVED / FROZEN**  
> Date: 2026-09-11  
> Scope: **project-wide for D3 and every later milestone/module**

This decision freezes the project-wide choice to use Kotlin's immutable/persistent collections where appropriate. It does **not** permanently freeze the library to one version.

All dependency selection/versioning behavior also follows [`docs/DEPENDENCY_POLICY.md`](DEPENDENCY_POLICY.md).

---

# 1. Decision

Use JetBrains/Kotlin's official multiplatform library:

```text
org.jetbrains.kotlinx:kotlinx-collections-immutable
```

Initial adopted version for the D3 migration:

```text
0.5.2
```

After implementation, the **current selected version is defined by `gradle/libs.versions.toml`**, not by this historical decision document.

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

The library is currently experimental and its API is subject to change. Every committed repository state must therefore use an exact pinned version, but compatible future upgrades may be proposed and merged through the normal dependency-review process in `docs/DEPENDENCY_POLICY.md`.

The initially adopted 0.5.2 release supports Kotlin Multiplatform and requires Kotlin stdlib 2.3.0 or newer. The repository Kotlin baseline at adoption time is 2.3.21.

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

The currently selected version is centralized in `gradle/libs.versions.toml` once the dependency is introduced.

Later modules may add:

```kotlin
implementation(libs.kotlinx.collections.immutable)
```

without a new architecture/dependency decision whenever the module needs the library.

Do not:

```text
use a dynamic/ranged version
pin a second conflicting version in an individual module
use a second immutable-collections framework for the same role without a new decision
add the dependency to an unused module merely for symmetry
let library-specific representation define persistence or wire compatibility
```

---

# 7. Upgrade policy

The approved decision is the **library/role**, not a permanent version number.

Routine compatible updates may be proposed by a Coding Agent through a normal reviewed dependency-maintenance PR. The PR must change the exact pinned version explicitly, review relevant release notes, and run affected verification.

Additional explicit architecture/dependency review is required only when the upgrade materially changes project contracts, for example:

```text
breaking public API migration
persisted/wire representation change
security-sensitive behavior change
broad toolchain/platform migration
replacement with a different library/framework
```

Because the library is experimental, upgrades deserve closer compatibility review than an ordinary stable API, but they do not require reopening this decision merely because the version number changed.

See `docs/DEPENDENCY_POLICY.md` for the repository-wide versioning and upgrade rules.
