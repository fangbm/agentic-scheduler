# Agentic Scheduler — Third-Party Dependency Policy

> Status: **Mandatory / Frozen baseline**  
> Date: 2026-09-11  
> Audience: coding agents, reviewers, maintainers

This policy defines how third-party dependencies may be selected, versioned, reviewed, and introduced.

The goal is to give Coding Agents enough freedom to solve local engineering problems without turning a small implementation choice into an unreviewed architecture decision.

---

# 1. Non-negotiable version rule

**Every third-party dependency proposed or added by a Coding Agent MUST specify an exact concrete version at the time of the proposal/commit.**

The version is part of the dependency decision, not a later cleanup step.

Allowed example:

```toml
kotlinxCollectionsImmutable = "0.5.2"
```

Forbidden examples:

```text
+
1.+
latest
latest.release
latest.integration
[1.0,2.0)
(,2.0]
any other dynamic/ranged selector
```

When Gradle Version Catalog can represent the dependency, the exact version belongs in:

```text
gradle/libs.versions.toml
```

Do not hard-code a second version for the same library in an individual module.

BOM-managed artifacts are allowed when the **BOM itself is pinned to an exact version**. Artifacts intentionally controlled by that BOM do not need duplicate explicit versions.

Plugin versions follow the same rule: no dynamic or ranged plugin versions.

---

# 2. Dependency authorization classes

Every third-party dependency falls into one of three classes.

## APPROVED

Already selected and approved at project level.

A Coding Agent may use it in an appropriate module without requesting another dependency decision, while preserving task scope and module ownership.

The repository's Version Catalog / approved decision source defines the exact authorized version.

If the Agent wants a different version, that is an **upgrade proposal**, not ordinary use.

Current example:

```text
org.jetbrains.kotlinx:kotlinx-collections-immutable:0.5.2
```

---

## AGENT_SELECTABLE

A Coding Agent may select and add the dependency in its implementation PR without obtaining approval beforehand **only when the dependency is local, reversible, and low-impact**.

All of the following should be true:

```text
- solves a current in-scope requirement
- limited blast radius
- does not define Domain/business semantics
- does not define persisted or wire representation
- does not define a security boundary
- does not introduce a project-wide architecture/framework
- does not replace an already approved project-level solution
- can be removed/replaced without a migration
- platform/KMP compatibility has been checked where relevant
- exact version is pinned
```

Human review of the PR remains the approval point for merging the new dependency.

The Agent must not interpret AGENT_SELECTABLE as permission to add speculative libraries for future work.

---

## DECISION_REQUIRED

The Agent may research candidates and recommend a concrete library **with an exact proposed version**, but must not add the dependency until an explicit project decision/task/ADR authorizes it.

This class includes dependencies that materially affect:

```text
persistence / ORM / database engine
serialization or production wire protocol
Sync / CRDT / causality / merge infrastructure
cryptography / E2EE / secret storage
DI framework
project-wide UI/state/navigation architecture
Planner / solver architecture
LLM/provider abstraction with cross-module coupling
server/network framework replacement
cross-module logging framework
anything that changes persisted compatibility or public architecture
```

The proposal must remain visible as `BLOCKED_BY_DECISION` for the affected implementation path until approved.

---

# 3. Required dependency proposal record

Whenever a Coding Agent introduces a new AGENT_SELECTABLE dependency or proposes a DECISION_REQUIRED dependency, its implementation report / PR description must include:

```text
Dependency: group:artifact
Exact version: x.y.z
Authorization class: AGENT_SELECTABLE | DECISION_REQUIRED
Scope/module: where it will be used
Current requirement: why it is needed now
Why stdlib/current approved dependencies are insufficient
Alternatives considered: brief comparison when non-trivial
Platform/KMP compatibility: applicable targets
Public API impact: none / describe
Persistence/wire impact: none / describe
Security impact: none / describe
License: identify it when introducing a new library
Removal/migration difficulty: low / medium / high
```

A proposal without an exact version is incomplete and must not be merged.

---

# 4. Version selection rule

When choosing the exact version, the Coding Agent should normally select a current stable release compatible with the repository toolchain and target platforms.

Do not automatically choose the numerically newest version when it:

```text
requires an unrelated Kotlin/Gradle/AGP/SDK upgrade
breaks required KMP targets
is prerelease/RC/beta/alpha without a concrete reason
introduces a known regression relevant to the project
conflicts with an existing BOM/version family
```

If the best compatible version is not the newest stable release, record the reason in the PR.

For an experimental library whose releases are stable-tagged but whose API is explicitly experimental, pinning an exact version is mandatory and upgrade review should consider API changes.

---

# 5. Upgrade and downgrade rule

Changing an existing dependency version is a deliberate change.

A Coding Agent may update an AGENT_SELECTABLE dependency in an in-scope maintenance/change PR when:

```text
- the exact target version is stated
- changelog/release notes relevant to the project are reviewed
- compatibility is checked
- affected tests/builds are run
- the change does not force unrelated architecture/toolchain migration
```

For APPROVED project-level dependencies, major or contract-affecting upgrades require an explicit dependency decision/review. Small compatible updates may be proposed in a dedicated maintenance PR but must still pin the exact target version.

Never silently change dependency versions while implementing an unrelated feature.

---

# 6. Task scope still wins

Dependency freedom does not override milestone/task scope.

Examples:

```text
D3 may use an approved immutable collection library
    != D3 may implement Planner state early

A UI task may add a small local AGENT_SELECTABLE utility
    != the Agent may introduce a project-wide navigation architecture
```

A dependency may be technically allowed while the feature that would use it is still out of scope.

---

# 7. Existing approved versions

At the time this policy is introduced, repository versions already include:

```text
Kotlin                         2.3.21
Android Gradle Plugin          9.4.0
Compose Multiplatform          1.10.3
AndroidX Activity Compose      1.13.0
AndroidX Compose BOM           2026.08.00
Wear Compose                   1.6.2
kotlinx-datetime               0.8.0
kotlinx-collections-immutable  0.5.2   (authorized by the D3 amendment; add to catalog during implementation)
```

`gradle/libs.versions.toml` is the executable source of truth for versions already present there. This section is documentation for Coding Agents and must be updated when a frozen/approved version changes materially.

---

# 8. Review rule

Human review should focus on whether:

```text
- the dependency is actually needed
- its authorization class is correct
- the exact version is pinned
- an existing approved dependency already solves the problem
- the library creates hidden long-term coupling
- target/platform compatibility is real
- licensing/security/maintenance risk is acceptable
- dependency scope is no broader than necessary
```

The Agent is allowed to make local engineering choices. It is not allowed to hide architecture choices inside Gradle changes.
