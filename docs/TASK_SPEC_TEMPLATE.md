# Agentic Scheduler — Task Specification Template

> Use this template for milestone work, coding-agent delegation, and any task large enough to affect more than one file or public behavior.

The purpose of a Task Spec is to make both **what should be done** and **what must not be done** explicit before implementation.

---

# Task

```text
ID: D?-??
Title:
Milestone:
Status: READY | BLOCKED_BY_DECISION | IN_PROGRESS | DONE
Owner:
```

# Goal

Describe the user/system outcome in one short paragraph.

Do not describe implementation details here unless they are part of the required contract.

# Why now

Explain why this task belongs to the current milestone and what later work depends on it.

# Relevant baseline documents

Mark only the documents that materially constrain this task.

```text
[ ] docs/DOMAIN_INVARIANTS.md
[ ] docs/IMPLEMENTATION_CONTRACT.md
[ ] docs/ARCHITECTURE_DIAGRAMS.md
[ ] docs/MODULE_OWNERSHIP.md
[ ] docs/UBIQUITOUS_LANGUAGE.md
[ ] docs/CODING_AGENT_POLICY.md
[ ] relevant ADR:
[ ] other specification:
```

# Current state / inputs

List existing modules, types, files, schemas, or behavior that the implementation starts from.

Include exact identifiers when ambiguity is possible.

# MUST

Everything required for task completion.

```text
- ...
- ...
```

# MAY

Explicitly permitted implementation freedom.

```text
- ...
- ...
```

If something is not listed here but is a purely LOCAL_REVERSIBLE implementation detail under `CODING_AGENT_POLICY.md`, the coding agent may still choose it.

# MUST NOT

Explicit negative scope.

```text
- do not implement later milestone X
- do not change Y public contract
- do not add Z dependency/module
```

Always include meaningful negative scope for milestone tasks.

# Canonical vocabulary

List important project terms that must be used exactly.

```text
Event
Task
FocusBlock
...
```

# Required types / API contracts

If public shape is already decided, write it explicitly enough that an agent cannot invent a competing API.

Example:

```kotlin
@JvmInline
value class TaskId(val value: String)
```

For contracts not yet decided, do **not** put a speculative example. Put them under Open decisions instead.

# Business defaults

List every product-semantic default introduced by this task.

```text
None.
```

is valid and preferable to leaving the section ambiguous.

Coding agents may not invent missing business defaults.

# Validation / invariants

List relevant rules and their owner.

| Rule | Owner | Expected failure |
|---|---|---|
| example | Domain | typed/explicit failure |

# Time / ID / determinism inputs

State which of these affect the task:

```text
Clock:
Timezone:
Locale:
ID generator:
Random seed:
Stable ordering/tie-break:
```

Write `N/A` where not applicable rather than leaving a meaningful field ambiguous.

# Data ownership

State the owner of any new authoritative data.

```text
Current state owner:
History owner:
Persistence owner:
Sync owner:
UI/session-only state:
```

# Persistence impact

Choose one:

```text
NONE
NEW_SCHEMA
MIGRATION_REQUIRED
MAPPING_ONLY
```

If not `NONE`, link the schema/migration decision.

# Sync / protocol impact

Choose one:

```text
NONE
NEW_SYNC_SEMANTICS
WIRE_SCHEMA_CHANGE
MERGE_POLICY_CHANGE
COMPATIBILITY_CHANGE
```

If not `NONE`, specify compatibility and ADR requirements.

# Agent / context impact

Choose all that apply:

```text
NONE
NEW_TOOL
TOOL_SCHEMA_CHANGE
PERMISSION_CHANGE
CONTEXT_ASSEMBLER_CHANGE
HISTORY_CHANGE
SUMMARY/COMPACTION_CHANGE
PROVIDER_ADAPTER_CHANGE
```

For Tool changes, specify read/write classification, risk, permission, transaction, audit, and Undo behavior.

# Security / privacy impact

Choose one:

```text
NONE
SENSITIVE_LOCAL_DATA
E2EE_DATA
SECRET/CREDENTIAL
EXTERNAL_PROVIDER_DATA
PERMISSION_CHANGE
```

State any forbidden plaintext/logging paths.

# Platform impact

```text
Android:
Desktop:
Wear OS:
Server:
```

Use `NONE` explicitly when a platform is unaffected.

# Dependencies

```text
New third-party dependencies: NONE | exact approved dependency(s)
New Gradle modules: NONE | exact approved module(s)
Toolchain changes: NONE | exact approved change
```

Anything not declared here is not authorized merely because it is convenient.

# Acceptance criteria

Use observable pass/fail statements.

```text
[ ] ...
[ ] ...
[ ] CI/build/tests pass
```

Avoid vague criteria such as "works well" or "clean architecture" without observable checks.

# Required tests

Specify concrete categories/cases, especially negative cases.

```text
Positive:
- ...

Negative/invariant:
- ...

Determinism/replay:
- ...
```

# Verification commands

List expected commands where known.

```text
./gradlew ...
```

The implementer reports commands actually run, not assumed results.

# Open decisions

Every unresolved high-impact decision required by this task goes here.

```text
NONE
```

or:

```text
DECISION_REQUIRED: <question>
Impact: CONTRACT_AFFECTING | ARCHITECTURE_AFFECTING | SECURITY_OR_DATA_LOSS
Owner/ADR:
```

A task whose required path contains an unresolved high-impact decision is not `READY`.

# Out of scope / follow-ups

Record attractive but deliberately deferred work so agents do not implement it "while already here".

# Completion report format

```text
Implemented:
- ...

Tests/verification:
- ...

Architecture impact:
- none | details

LOCAL_REVERSIBLE assumptions:
- none | details

Blocked decisions:
- none | details

Out of scope left untouched:
- ...
```

---

# Milestone-specific rule

For each D1/D2/D3/... task, the milestone description must be treated as a **scope fence**, not just a minimum checklist.

If a future milestone feature is not necessary to satisfy the current task, do not implement it early.
