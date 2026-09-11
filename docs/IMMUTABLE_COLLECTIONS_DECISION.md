# Agentic Scheduler — Immutable Collections Decision

> Status: **RESOLVED / FROZEN**  
> Date: 2026-09-11  
> Scope: `:shared:domain` and later immutable in-memory state that shares Domain semantics

This decision freezes the collection-immutability implementation baseline that was previously described only semantically as "immutable public Domain state".

---

# 1. Decision

Use JetBrains/Kotlin's official multiplatform library:

```text
org.jetbrains.kotlinx:kotlinx-collections-immutable:0.5.2
```

The version is pinned in the Version Catalog when implementation begins.

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

# 4. Future intended use

This dependency is not introduced solely for D3. It is expected to be useful for later immutable in-memory state, especially:

```text
PlanBranch / Preview snapshots
Planner candidate states
Undo / reversible state transitions
Domain snapshots assembled for Agent context
Sync reducer in-memory state
Dependency graph snapshots
other branch/rebase/merge-like state derivation
```

Persistent collections are especially appropriate when many derived states share most of their contents.

This does **not** make a persistent collection the persistence layer. Operation Log, durable snapshots, Sync metadata, and authoritative storage remain owned by their later database/sync milestones.

---

# 5. D1 / D2 compatibility

D1's architectural decision was a pure Kotlin Multiplatform Domain boundary. This library is a pure KMP collection dependency and does not violate that boundary.

D2 explicitly prohibited adding a collections framework **inside the D2 milestone** and authorized no third-party dependency other than `kotlinx-datetime`. That historical scope fence remains correct and must not be rewritten retroactively.

The new dependency is authorized beginning with the explicit D3 amendment in `docs/tasks/D3_IMMUTABLE_COLLECTIONS_AMENDMENT.md`.

D2 production types currently do not expose collection-valued stored public state that requires migration. Its dependency-cycle validator may continue using local mutable collections internally because they do not escape the pure validation call.

---

# 6. Upgrade policy

Because the library is experimental:

```text
pin exact version
no silent upgrades in unrelated work
review changelog before upgrade
run repository-wide tests after upgrade
avoid depending on mutable Builder types in public APIs
avoid assuming serialization format from collection implementation details
```

A future stable 1.0 release may justify a dedicated upgrade task, but must preserve Domain semantics rather than redefining them.
