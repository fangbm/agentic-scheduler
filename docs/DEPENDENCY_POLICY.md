# Agentic Scheduler — Third-Party Dependency Policy

> Status: **Mandatory / Frozen baseline**  
> Date: 2026-09-11  
> Audience: coding agents, reviewers, maintainers

This policy defines how third-party dependencies may be selected, versioned, reviewed, introduced, and upgraded.

The goal is to give Coding Agents enough freedom to solve local engineering problems while keeping every repository revision reproducible and preventing small implementation choices from becoming hidden architecture decisions.

For dependency selection/versioning specifically, this policy supersedes the older generic wording in:

```text
docs/CODING_AGENT_POLICY.md §14 Dependency rule
docs/IMPLEMENTATION_CONTRACT.md §23 Dependency additions
```

Those documents still govern all non-conflicting architecture and task-scope rules.

---

# 1. Reproducible version rule

**Every third-party dependency proposed or added by a Coding Agent MUST resolve from an exact concrete version in the committed repository state.**

Exact pinning exists for reproducibility. It does **not** mean that the selected version is permanently frozen.

Allowed example:

```toml
someLibrary = "1.4.2"
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

When Gradle Version Catalog can represent the dependency, the selected version belongs in:

```text
gradle/libs.versions.toml
```

Do not hard-code a second version for the same library in an individual module.

BOM-managed artifacts are allowed when the **BOM itself is pinned to an exact version**. Artifacts intentionally controlled by that BOM do not need duplicate explicit versions.

Plugin versions follow the same rule: no dynamic or ranged plugin versions.

A dependency proposal that omits its exact selected version is incomplete.

---

# 2. Current-version source of truth

`gradle/libs.versions.toml` is the canonical source of truth for the **currently selected dependency/plugin versions** whenever the dependency can be represented there.

Architecture/decision documents may record:

```text
library identity
why the library was selected
initially adopted version
compatibility constraints
special upgrade risks
```

They should not be treated as a second manually synchronized registry of every current library version.

When a version changes through an approved PR, the Version Catalog changes with it. Historical decision documents do not need to be rewritten merely because a compatible dependency version advanced, unless the decision itself changed.

If documentation needs to mention a concrete historical adoption version, prefer wording such as:

```text
Initial adopted version: 0.5.2
Current version: see gradle/libs.versions.toml
```

---

# 3. Dependency authorization classes

Every third-party dependency falls into one of three classes.

## APPROVED

The **library choice/role** is already approved at project level.

A Coding Agent may use it in an appropriate module without requesting another dependency decision, while preserving task scope and module ownership.

Approval of a library is not permanent approval of one immutable version number. The current selected version is the version pinned by the repository.

A compatible version update is handled through the normal upgrade rules below; it does not automatically reopen the original architecture decision.

Current example:

```text
org.jetbrains.kotlinx:kotlinx-collections-immutable
Initial adopted version: 0.5.2
Current selected version after implementation: see gradle/libs.versions.toml
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
- exact version is pinned in the committed repository state
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

# 4. Required dependency proposal record

Whenever a Coding Agent introduces a new AGENT_SELECTABLE dependency or proposes a DECISION_REQUIRED dependency, its implementation report / PR description must include:

```text
Dependency: group:artifact
Selected exact version: x.y.z
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

The exact version is part of the proposal because the committed build must be reproducible. It is not a promise that this version will never change.

---

# 5. Version selection rule

When choosing the exact version, the Coding Agent should normally select a current stable release compatible with the repository toolchain and target platforms.

Do not automatically choose the numerically newest version when it:

```text
requires an unrelated Kotlin/Gradle/AGP/SDK upgrade
breaks required KMP targets
is prerelease/RC/beta/alpha without a concrete reason
introduces a known regression relevant to the project
conflicts with an existing BOM/version family
```

If the best compatible version is not the newest stable release, record the reason in the PR when material.

For an experimental library whose releases are stable-tagged but whose API is explicitly experimental, exact pinning remains mandatory and upgrade review should consider API changes.

---

# 6. Upgrade and downgrade rule

Dependency versions are expected to evolve.

A Coding Agent may proactively propose or implement a dependency update through a reviewed PR. Every update must produce an explicit version diff rather than relying on a dynamic selector.

For ordinary compatible patch/minor updates, the Agent may update the pinned version when:

```text
- old version -> exact new version is visible in the diff/report
- relevant release notes/changelog are checked
- repository/toolchain/platform compatibility is checked
- affected tests/builds are run
- the update does not silently alter a persisted/wire/security/public contract
- required unrelated toolchain migrations are not smuggled into the change
```

A dedicated dependency-maintenance PR is preferred when the upgrade is unrelated to the feature currently being implemented. A feature PR may include a necessary compatible upgrade when the relationship is explicit and reviewable.

Additional explicit decision/review is required when an upgrade is materially high-impact, including:

```text
major-version migration with meaningful breaking changes
public API/architecture change
persisted data or wire-format change
Sync/convergence behavior change
cryptography/security-boundary change
required Kotlin/Gradle/AGP/SDK migration with broad impact
replacement of the approved library with a different library/framework
```

A version number changing by itself does not reopen the architecture decision. **Impact determines escalation, not merely semver position.**

Downgrades follow the same visibility/testing rules and should state the reason, such as regression avoidance or compatibility restoration.

---

# 7. Task scope still wins

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

# 8. Review rule

Human review should focus on whether:

```text
- the dependency is actually needed
- its authorization class is correct
- the committed version is exact/reproducible
- an existing approved dependency already solves the problem
- the library creates hidden long-term coupling
- target/platform compatibility is real
- licensing/security/maintenance risk is acceptable
- dependency scope is no broader than necessary
- an upgrade changes contracts or merely advances a compatible implementation
```

The Agent is allowed to make local engineering choices and routine dependency maintenance. It is not allowed to hide architecture choices inside Gradle changes.

---

# Final rule

**Pin versions for reproducibility; update versions through visible reviewed diffs.**

The project approves library roles and architecture choices. It does not permanently freeze ordinary dependency versions unless a specific compatibility/security decision explicitly says otherwise.
