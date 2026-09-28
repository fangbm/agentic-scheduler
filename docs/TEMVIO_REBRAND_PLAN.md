# Temvio — Staged Rebranding and Technical Rename Plan

> Status: PROPOSED / DOCUMENTATION ONLY  
> Date: 2026-09-28  
> Project: fangbm/agentic-scheduler (current repository name when this plan was written)  
> Proposed product name: Temvio (NOT YET CLEARED)  
> Governing principle: brand presentation and repository identity first; technical identity later, through separately gated changes.

## 0. Naming clearance — mandatory gate before public rebrand

**Do not rename the public repository or announce Temvio as the final product identity until this gate passes.**

A preliminary name check surfaced:
- An existing software company named Temvio, described as an engineering-team analytics product, using https://temv.io (see https://www.linkedin.com/company/temvio).
- A previously registered US TEMVIO trademark associated with pharmaceuticals (see https://trademarks.justia.com/873/23/temvio-87323388.html). Its relevance and current legal status require qualified checking, not inference from a search result.
- A separate use of the term at https://temvio.space.

These findings are **not** a legal conclusion, but the software-industry collision is material. Before proceeding, perform a documented search of target-market trademarks, appropriate classes, GitHub repository names, app stores, domains and social handles. Review potential confusion with the existing software product; obtain qualified legal advice if a commercial launch is contemplated. Record available names/handles and the final go/no-go decision. If Temvio does not clear, choose a new brand **before** Phase 1; the technical migration structure below still applies.

Logo studies and this plan are provisional. Do not publish a logo or buy brand-specific assets on the assumption that name ownership is established.

## 1. Objectives and non-negotiable boundaries

- Retain the existing local-first architecture and “Agentic Surface, Deterministic Core” product principle.
- Phase 1 changes what people see and the GitHub repository label, **not** technical identities or application logic.
- Phase 2 separately migrates internal package names, source paths, module/project labels and other approved technical identifiers.
- Existing user data, install/update identity, encrypted sync interoperability, Room schemas and secure-store data take priority over cosmetic consistency.
- Never use a blanket replace-all operation on the old name.
- Do not convert this plan into approval for unrelated D9/D10 work, dependencies or architectural changes.
- Documentation-only changes for this plan may go directly to main as requested; executable rename changes follow normal test/review gates.

### Current technical baseline relevant to the rename

As of this plan, the repo contains:
- apps:android, apps:desktop, apps:wear
- shared:domain, shared:application, shared:planner, shared:sync, shared:database
- server:sync
- Android app ID and namespace: dev.agenticscheduler.android
- Wear app ID and namespace: dev.agenticscheduler.wear
- Desktop entrypoint: dev.agenticscheduler.desktop.MainKt
- Server entrypoint: dev.agenticscheduler.server.sync.ApplicationKt
- Gradle root project name: agentic-scheduler
- Room client schemas through v11 (per current README)
- Encrypted D8 multi-device sync; OD-012 local SQLite encryption remains a separate release gate
- Main CI and CIFleet smoke workflows; Android and desktop dogfood artifacts currently use agentic-scheduler labels.

The authoritative code and current GitHub configuration must be rechecked immediately before either phase; this list is an inventory starting point, not an instruction to alter every occurrence.

## 2. Phase 1 — Brand-facing rename ONLY

**Entry:** Section 0 naming clearance accepted; owner selects final logo concept and final display spelling.  
**Allowed scope:** UI presentation resources; public-facing copy/assets; repository display identity and links. No change to domain, application, persistence, transport or executable naming.

### 2.1 Identity and design assets

1. Select one logo from the exploration set and produce a small brand kit: primary horizontal wordmark, vertical/stacked lockup, icon-only, monochrome and reversed forms.
2. Produce light/dark backgrounds, favicon, Android adaptive icon foreground/background, Wear small-icon legibility checks and desktop icon sizes. Use a common optically consistent mark; do not rely on tiny “AI sparkles” that disappear at watch sizes.
3. Freeze draft palette, minimum padding, typography guidance and approved spelling in a single brand-assets folder; keep editable originals alongside export assets.
4. Retain a mapping to old assets for one-release rollback. Do not destroy existing assets until smoke verification passes.

### 2.2 Product presentation (presentation-only files/resources)

1. Update visible product names only where controlled by non-source-code resources or marketing assets: app launcher labels where resource-based, splash/icon resources, screenshots and documentation. Desktop window titles or About text hard-coded in Kotlin are deferred to Phase 2.
2. Keep all internal class names, package declarations, Android application IDs and existing database/credential identifiers unchanged. A user-visible label is not the same thing as application identity.
3. Make **no Kotlin/Java source edits at all in Phase 1**, including presentation-only string literals hard-coded in source. Update resource-controlled labels/assets only; defer hard-coded text to Phase 2.
4. Preserve old identifiers in existing archives, changelogs, migration notes and technical configuration where rewriting them could invalidate instructions or reproducibility.

### 2.3 Public identity and repository rename

1. Capture a rollback baseline: commit SHA, release/tag refs, README, screenshots, repository configuration, CI/check names, external integrations and clone URL.
2. Update README introduction, repository description/topics, documentation landing pages, badges, project/website links, release-page branding, social previews and screenshots. State “formerly Agentic Scheduler” for a transition period.
3. **After naming clearance**, rename the GitHub repository from fangbm/agentic-scheduler to the approved brand-based slug (candidate fangbm/temvio) using repository settings. Do not rename the account/owner.
4. GitHub typically redirects old repository URLs; do not treat redirects as a replacement for updating workflow references, Git submodules, hard-coded raw URLs, badges, external deployment/webhook settings, external GitHub Apps/connectors, clone remotes and documentation links. Audit these explicitly.
5. Inspect CI/CIFleet integrations and artifact consumers. During Phase 1, change *display-only* labels only when no automation depends on their exact strings. Keep build, module, artifact and environment identifiers stable otherwise.
6. Recheck any published package name, documentation domain, support identity or app-store listing before making public branding changes.

### Phase 1 hard MUST NOT list

- No package/import/source-directory/Gradle namespace changes.
- Do not change settings.gradle.kts rootProject.name or Gradle module paths.
- No Android or Wear applicationId, manifest authorities, permission names, signing or intent/deep-link technical identifiers.
- No database file name, Room entities, schema snapshots, schema version, migrations, table/column names, sync serialization or persisted ID changes.
- No encryption/AAD context, key-store alias, credential envelope, device enrollment or sync protocol renaming.
- No Ktor server route, environment variable, release API contract, server schema or deployed resource changes.
- No broad literal “agentic” search-and-replace; the architecture phrase “Agentic Surface, Deterministic Core” can remain.

### Phase 1 verification / exit gate

- Diff contains only approved docs, branding assets and visible presentation resource files; **no Kotlin/Java source changes**. Repository rename is a separate tracked administrative action.
- The entire existing build/CI matrix remains green: Linux, Windows, Android/Wear relevant tests, server tests and CIFleet smoke where available.
- Android and Wear retain the **same** applicationId. Existing development installs still update as before; old local SQLite state and secure-store data remain readable.
- Desktop and Android dogfood screenshots show the selected brand wherever presentation can be updated through resources/assets. Any hard-coded legacy UI text remains explicitly documented until Phase 2; persistence/sync behavior is unchanged.
- Old GitHub link redirects and new origin URLs are checked; Actions/checks, external apps and self-hosted runner triggering are verified, rather than merely assuming GitHub redirects cover them.
- Record a Phase 1 release/commit and link to selected approved brand assets. Explicitly state: technical migration has **not** happened.

Rollback: revert presentation/resource commits and, if necessary, restore the old repository name after auditing links/webhooks. This rollback must not touch stored data or sync identifiers.

## 3. Phase 2 — Internal naming and namespace migration

**Entry:** Phase 1 accepted, brand name confirmed, current CI green, full code/config search reviewed, compatibility plan approved.  
**Goal:** rename *internal source identity* in a controlled series of technical changes without silently replacing external/install/persistence/protocol identities.

### 3.1 Freeze the target identity matrix first

Before editing code, publish a table mapping **old identifier → proposed new identifier → compatibility classification** for:
- Kotlin package namespaces for shared modules, Android, Wear, Desktop and server
- Gradle rootProject.name and optionally task/module display labels
- Android Gradle namespace versus Android/Wear applicationId (separate decisions)
- Desktop and Ktor server mainClass
- Generated-source/KSP/Room paths, test sources, manifests, resources, native packaging and test runners
- Build artifact IDs/filenames and CI/CIFleet workflow references
- Deep links, custom permissions, manifest authorities and inter-app communication
- Local DB filename, schemas, data-store preferences, keystore/secret-service keys and encrypted payload associated data
- Sync wire discriminators, serialized type markers, server routes, OAuth callback values, device/account identifiers and deployed infra references.

**Suggested internal namespace:** dev.temvio only if the required naming/domain ownership decision is approved. Otherwise use an explicitly approved controlled prefix (for example io.github.fangbm.temvio). Do not infer namespace rights from the product name.

Classify each identifier as:
- INTERNAL_RENAME_NOW: source package names, imports, internal entrypoints, Gradle project label when no external consumers depend on it.
- COMPATIBILITY_ALIAS: public APIs or external references that need old/new simultaneous acceptance for a period.
- PERMANENT_LEGACY: harmless existing persistent IDs and historic names that should stay stable.
- SEPARATE_MIGRATION_REQUIRED: installed app IDs, cryptographic context, DB identity or protocol identity that cannot be altered as a cosmetic refactor.

No implementation agent chooses an unclassified identifier independently.

### 3.2 Controlled implementation sequence

1. Establish full regression baseline, disposable dogfood devices, existing-data snapshots, mixed-version sync fixtures, and exported Room schema baseline. Record pre-change artifact hashes where relevant.
2. Migrate shared package declarations and directories in small groups: domain, application, planner, sync, database. Update imports, tests, generated-source config and reflection/service registrations.
3. Migrate app and server **source namespaces** plus Android/Wear Gradle namespace, Desktop mainClass and server mainClass. Preserve app IDs and external routes unless individually approved below.
4. Update rootProject.name and build metadata that are internal only; adjust CI, CIFleet, scripts, docs, imports, test fixtures and IDE run configurations.
5. Verify clean build, packaged Android/Wear launches, Desktop Linux/Windows starts, server starts, Room KSP generated schemas/identity hashes, and all tests. Run old-data open + encrypted mixed-version sync tests before considering the phase complete.
6. Document every intentional occurrence of the old product name that remains as a stable technical ID, historical string or compatibility alias. No blanket string clean-up.

### 3.3 Identity exceptions and compatibility policy

**Android/Wear applicationId:** Changing this creates a *different app identity* for Android installation/update purposes. Default Phase 2 policy: **keep current application IDs**, even after code packages and Gradle namespaces change. If the owner insists on new application IDs, make that a separate explicit release/data-migration decision; test side-by-side install, backup/import, secure-store behavior, device pairing and store listing consequences. Do not claim an in-place update across distinct IDs.

**SQLite/Room:** A namespace rename is not permission to rename the database file or tables. Retain existing DB filename, schema version and schema snapshots unless an actual intentional schema migration is approved. Inspect Room generated schema outputs, identity hashes, exported schema locations and fully qualified converter references; use measured diffs and migration tests. A pure package refactor should not silently produce a destructive migration.

**Sync/E2EE/security:** Keep all stable wire discriminators, cipher associated data, protocol identifiers, secure-store aliases, server routes and credential handling compatible with already-enrolled devices. Any desired external-name change needs a separately reviewed dual-read/versioned migration with mixed-version tests; never rename cryptographic identifiers by search-and-replace.

**Build/release consumers:** Changes to Gradle task identifiers, CI artifact names, external deployments, OAuth clients or callback URLs are separately audited for exact-string consumers and should not be bundled into a source package refactor without validation.

### Phase 2 verification / exit gate

- All source modules build with the approved namespace matrix; no stale source package declarations remain except intentional compatibility adapters.
- Android, Wear, Desktop and server entrypoints work from clean builds on supported targets. Existing installations retain recognized application IDs.
- Existing pre-migration database and encrypted secure-store material reopen correctly. Room export and any schema diff are understood, documented and migration-tested.
- Old/new client combinations sync successfully over the supported protocol versions. Domain, Planner, Agent Tool validation, undo/history and encrypted relay regression suites pass.
- CI, dogfood packaging and CIFleet runner integrations pass using the new repository location.
- A second review verifies that stable external identifiers have **not** changed unintentionally.
- Keep a technical-rename tag and migration notes. Rollback remains possible with no data conversion, or an explicitly tested backward-compatible migration where conversion was approved.

## 4. Optional later phase — External identity migration

Only if there is a compelling product reason and a separate spec: Android/Wear application IDs, deep-link hosts, OAuth clients, persisted DB naming, wire/protocol identity, secure-store aliases or server route naming. Each requires owner approval, compatibility strategy, rollout/rollback design, and security review. Do not make Phase 2 contingent on completing this optional external-identity migration.

## 5. Ownership, delivery and decision gates

| Item | Owner / review | Evidence |
| --- | --- | --- |
| Final name clearance | Product owner / qualified review if commercial | documented search, accepted brand |
| Logo and brand kit | Design review / product owner | approved editable master and light/dark/small-size exports |
| Phase 1 presentation and repository migration | Product + repository maintainer | limited diff, links/webhook/CI audit, screenshots |
| Phase 2 technical identity matrix | Architecture + maintainer | per-identifier classification and approved target namespace |
| Phase 2 implementation | Module owners | full target matrix + old-data/mixed-client regression |
| Optional external identity | Security/data compatibility review | dedicated spec and explicit owner approval |

**Current action authorized by this document:** record a proposed plan and explore logos. **Not authorized yet:** actual public repository rename, any package/namespace migration, or a declaration that “Temvio” is trademark-clear.
