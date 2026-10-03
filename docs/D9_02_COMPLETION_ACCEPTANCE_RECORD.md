# D9-02 Completion Acceptance Record

Status: **D9-02 IMPLEMENTATION AND ACCEPTANCE COMPLETE / MERGED**. PR #24 merged on 2026-10-04 as `6583e61fc3101e5373d537f6b90675430a7aec10`.
Baseline `feature/d9-02-agent-sync` / `4dbe432`; branch
`codex/d9-02-05-agent-history-e2e`. Updated 2026-10-04. Latest accepted
implementation head: `7783a79a9404d23c0e3414c3e8534fe880688783`.

AGT-018 resolves the historical-source decision for this milestone. OD-012 remains
OPEN as the separate production-sensitive local-data release gate. Production
compositions do not inject V3 upload/receive or the continuation reader.

## Executed environment and evidence boundaries

| Environment | Actually exercised |
| --- | --- |
| Windows host, JDK17, ASCII Gradle user home | Kotlin/JVM suites; actual Windows DPAPI secure-store backend; file-backed Room14; Desktop Compose consent clicks. |
| PostgreSQL16, isolated Docker container `temvio-d9-02-05-postgres`, loopback port5545 | Actual JDBC repository, schema migrator, server invitation/bootstrap/authenticated upload/fetch; all public-table canary scans. No pre-existing relay data. |
| Ktor `testApplication` route pipeline | Enrolled-server integration suite `D9AgentHistoryPostgresE2ETest`; real server handlers/SQL/storage/crypto/worker, not a memory relay. This engine alone is not socket/TLS/platform evidence. |
| Android35 Google APIs x86_64 emulator, WHPX; Windows Desktop | Separate real HTTPS socket harness: current CIO server binary, ephemeral pinned localhost certificate, loopback TLS proxy, authenticated Ktor clients, actual Android Keystore and DPAPI, file Room14, V3 worker/receive/projection. |
| Client/server restarts | Three separate Desktop JUnit processes (seed/resume/verify), two Android instrumentation processes with force-stop between seed/resume, actual server process restart with unchanged PostgreSQL. Both clients retry exactly retained ciphertext. |

`EnrolledPlatformReplica` is compiled only as test source. Fixture shared content
key installation is explicit and test-only; this does not replace or prove a new
pairing protocol. Existing D8 pairing/recovery/rotation suites remain separate
evidence. No real Provider API call or production V3 activation is claimed.
TLS trust is limited to the ephemeral acceptance certificate; no permissive trust
manager, plaintext secret file, or credential logging is introduced. Only XML and
instrumentation result text are uploaded by CI, not client DBs/keys/configuration.

## New acceptance results

| Path | Actual result |
| --- | --- |
| Old client V3 quarantine + independent V1; upgraded earliest backfill twice | PASS, real enrolled route/PostgreSQL suite; forward business cursor/history unchanged and no Agent dots in D7. |
| Epoch7 decrypt after epoch8, missing key/ciphertext/earlier retention gap | PASS; absent material yields durable INCOMPLETE, not complete empty history. |
| Reordered Tool turn / manifest-first and member-first / duplicate / restart | PASS; incomplete projection empty and continuation read blocked; sealed complete turn becomes readable atomically. |
| Concurrent same-parent and root sibling turns | PASS; both retained, OPEN fork, continuation blocked; active transition immediately reconciles durable fork rows; restart agrees. |
| Delete/append, COPY, concurrent different resolutions, extended observing resolution | PASS; fresh message-only replacement, original tombstoned, no Tool/D7 replay, durable restart. |
| Identical concurrent KEEP and unobserved participant DVV | PASS; no LWW; invalid resolution stays unhandled; valid decisions converge. |
| Late and pre-existing pending audit after matching tombstone | PASS; sanitized references retained; only matching PARENT_RECORD dependencies release, unrelated missing parent remains pending. |
| Held V2/full suffix, independent inbound V1/V3, dependent complete turn hold/release | PASS; actual relay, no early member publication, authorization drains once, lookup-only inbound audit. |
| Consent/default OFF/owner acknowledgement/explicit historical export | PASS on Desktop and Android 15 emulator. Consent ON leaves outbox empty; separate click queues only a complete verified turn, repeat click after restart reuses the first mapping. v12→v15 preserves populated D9-01/ProviderConfig/permission/summary data and fail-closes the pre-v15 thread as LEGACY_UNVERIFIED. See the 2026-10-03 follow-up below for test totals and remaining CI/PG run. |
| Existing local ordinal0 and remote V3 projection | PASS, two enrolled clients; local ordinal-unique table unchanged after restart. |
| Frozen crypto/wire/bounds | Component suites plus new real route quarantine/preflight and actual Tink tamper/wrong-key checks; same immutable record ID with unequal value is quarantined despite a valid fresh author dot, original record/frontier unchanged after restart. No crypto/wire implementation changes. |
| Actual Android↔Desktop encrypted round trip | PASS local: Desktop seed/resume/verify each 1 test, 0 failures/skips; Android seed/resume each `OK (1 test)` across process restart; equal independent Agent frontiers, empty D7 journal/handled dots, no partial transcript. |
| Opaque server | PASS: actual route Tool/input/result/message/title traffic, local-only provider credential and SecretRef canaries, actual credentials and content-key encodings absent across all public tables. Separate platform scan also PASS. |
| Android consent controls | PASS actual Compose instrumentation clicks: acknowledgement required, explicit ON/OFF, V2 remains OFF, no automatic outbox, durable OFF, incomplete warning. |
| Desktop consent controls / targeted suites / migration rerun | PASS, BUILD SUCCESSFUL in1m47s on `e0adc85`; counts below. |

## Commands

Set `JAVA_HOME` to JDK17, `ANDROID_HOME` to the installed SDK and
`GRADLE_USER_HOME` to an ASCII path. Set `SYNC_TEST_DATABASE_URL/USER/PASSWORD`
to a fresh disposable PostgreSQL database. Do not log credentials.

Windows wrapper equivalent used for every command below:

```powershell
& "$env:JAVA_HOME\bin\java.exe" -classpath gradle\wrapper\gradle-wrapper.jar org.gradle.wrapper.GradleWrapperMain <tasks>
```

```text
--no-daemon --no-configuration-cache --max-workers=1
:shared:sync:desktopTest :shared:application:desktopTest :shared:agent:desktopTest
:shared:database:desktopTest --tests=dev.agenticscheduler.database.*Agent*
:apps:desktop:test --tests=dev.agenticscheduler.desktop.AgentConversationSyncControlsTest
:server:sync:test
```

| Local suite | Tests | Failures/errors | Skipped |
| --- | ---: | ---: | ---: |
| shared sync (codec/fixtures/merge/causality) | 49 | 0 | 0 |
| shared application (D8 + D9 actual PostgreSQL + transport/status/crypto) | 176 | 0 | 1 |
| shared agent | 44 | 0 | 0 |
| database Agent suites (including populated migrations) | 63 | 0 | 0 |
| Desktop consent Compose clicks | 1 | 0 | 0 |
| server sync | 27 | 0 | 0 |
| Total | 360 | 0 | 1 |

The 359 executed tests passed; the one skip is the explicitly phased platform
harness in an ordinary JVM suite. That harness was separately executed across
real platform processes above. AgentSyncPersistenceTest contributes20 passing
tests; AgentHistoryTransportIntegrationTest covers v13→14 separately. Sync's49
tests executed successfully in the preceding `--rerun-tasks` invocation and were
up-to-date in the final combined command; other suites executed in the final run.

The PostgreSQL suite has 18 tests, 0 failures/skips on the final GitHub CI head. Platform harness
is intentionally SKIPPED in ordinary JVM builds without the explicit phase;
this skip is not counted as a passing cross-platform test.

The pre-export E2E suite plus final additional immutable-ID adversarial assertion was verified with
`:shared:application:desktopTest --tests=dev.agenticscheduler.application.sync.D9AgentHistoryPostgresE2ETest
--no-daemon --no-configuration-cache --max-workers=1`: BUILD SUCCESSFUL in 45s,
17 tests, 0 failures/errors/skips. This earlier run predates the new explicit-export
relay case; the final CI head ran all 18 tests successfully.

```powershell
$env:ANDROID_AVD_HOME = 'D:\android-avds-d90205'
test-support\d9-02-05\run-platform-acceptance.ps1 -Python <python.exe> -Avd temvio-d90205-ascii
```

Linux CI runs `bash test-support/d9-02-05/run-platform-acceptance.sh` inside an
unlocked isolated Secret Service session and a dedicated Android emulator. The
script builds/starts the actual server, pins test TLS, executes each platform
phase and scans real PostgreSQL. Repository `build --no-daemon` and all existing
Windows/Android/Wear jobs are retained alongside this added platform job.

## Failures distinguished from PASS

- First route fixture reused globally unique device IDs across test accounts:
  bootstrap409. Fixture IDs now include the account; server rules unchanged.
- Windows default non-ASCII AVD path failed before boot. Independent ASCII AVD
  passed; no emulator startup failure is counted as Android acceptance.
- Re-running fixed-ID older D8 fixtures against the same disposable database
  caused bootstrap409. Re-run them in a new database, without changing D8 tests.
- Windows Java expanded a bare `*Agent*` test filter to `AGENTS.md`. Use the
  qualified `--tests=dev.agenticscheduler.database.*Agent*` filter above.
- CI [37043139149](https://github.com/fangbm/temvio/actions/runs/37043139149),
  head `6de2285`: platform/Windows/Android/Wear jobs passed; Linux build failed
  because the missing-key fixture deleted its key twice. Linux `secret-tool clear`
  reports failure for the second deletion. Remove the deleted reference from
  fixture cleanup; secure-store implementation unchanged.
- New E2E uncovered existing implementation gaps: later manifest unnecessarily
  waited on an already active parent, activation omitted derived conflict refresh,
  and removed audit parents retained an indefinite record dependency. Corrected
  using existing frozen semantics with regression assertions, not new protocol.
- The first explicit-export PostgreSQL run failed because the receiver fixture had
  not enabled its independent W4 conversation consent. The receive path correctly
  quarantined V3; the test now explicitly opts in both enrolled replicas. Run
  [37130156891](https://github.com/fangbm/temvio/actions/runs/37130156891) passed
  with all 18 PostgreSQL tests and the platform acceptance.

## Current CI and remaining gates

Full CI on accepted implementation head
`7783a79a9404d23c0e3414c3e8534fe880688783`:
[37133253426](https://github.com/fangbm/temvio/actions/runs/37133253426) **PASS**.
All five jobs (`build`, `desktop-windows`, `android-keystore`, `wear-keystore`,
and `agent-history-platform-e2e`) completed successfully. This includes actual
Linux Secret Service, the 18-test PostgreSQL E2E suite, and enrolled Desktop↔Android
HTTPS/process-restart acceptance. The code head includes the deletion-provenance
follow-up below. The documentation-only finalization head `bcf7e6dcf746e648406323dd5abea47f3b110b4b`
also passed all five jobs in [37135819980](https://github.com/fangbm/temvio/actions/runs/37135819980)
before PR #24 merged; it changed no implementation files.

## D9-02-05 historical export follow-up (2026-10-03)

Maintainer approval is recorded as AGT-018; OD-057 is RESOLVED FOR D9-02-05.
Room v14→v15 is non-destructive. Every existing D9-01 thread migrates to
`LEGACY_UNVERIFIED` with null creation metadata and no inferred turn/member rows.
New threads and runs record prospective creation, stable turn/ancestry, exact
ordered member snapshots, lifecycle and terminal outcome. Awaiting confirmation,
crash/cancel/exception and absent completion writes remain ineligible. Export is a
separate Android/Desktop owner action; consent alone queues zero V3 facts. A
verified finalized turn is converted through the existing V3 DTOs and its first
source→operation/HLC/event mapping commits atomically with the existing Agent
outbox. Retry/repeated click after restart reuses that mapping. No new wire field or
direct network upload was introduced. Provider/credential/permission/confirmation/
PlanBranch/ContextSummary/cache metadata is not in exported DTOs.

### Local thread deletion provenance follow-up (2026-10-04)

On accepted implementation head
`7783a79a9404d23c0e3414c3e8534fe880688783`, deletion of both tracked and
legacy-unverified threads is verified to purge raw local provenance in the same
transaction as the existing D9-01 conversation deletion. Provenance keeps only
the deleted thread identity/state: `creation_title` and `creation_at_epoch_millis`
are cleared, `agent_local_turn_member` snapshots are deleted before
`agent_local_turn_provenance`, and the thread remains excluded from historical
export. Reopen/restart tests confirm the snapshots do not return. AgentAction/D7
audit behavior remains retained, and already-created V3 export mappings and
outbox operations remain untouched.

Executed on 2026-10-03 (Windows JDK17; `GRADLE_USER_HOME=D:\gradle-home-agent`):

```text
:shared:database:desktopTest --tests dev.agenticscheduler.database.AgentPersistenceTest --tests dev.agenticscheduler.database.AgentSyncPersistenceTest --tests dev.agenticscheduler.database.AgentHistoryExplicitExportTest --tests dev.agenticscheduler.database.AgentRunIntegrationTest --rerun-tasks --no-daemon --no-configuration-cache --max-workers=1
Result: BUILD SUCCESSFUL; 46 tests, 0 failures/errors/skips (`AgentHistoryExplicitExportTest` 3, `AgentPersistenceTest` 9, `AgentRunIntegrationTest` 14, `AgentSyncPersistenceTest` 20). This includes v14→v15 migration/fresh schema parity and deletion-provenance restart/export-exclusion regressions.

:apps:desktop:test --tests dev.agenticscheduler.desktop.AgentConversationSyncControlsTest
Result: 2 tests, 0 failures/errors/skips.

:apps:android:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=dev.agenticscheduler.android.AgentConversationSyncControlsInstrumentedTest
Result: Android 15 emulator `temvio-d90205-ascii`; 2 tests, 0 failures/errors/skips.

:apps:desktop:compileKotlin :apps:android:compileDebugKotlin
Result: BUILD SUCCESSFUL.
:apps:android:compileDebugAndroidTestKotlin
Result: BUILD SUCCESSFUL.
```

The first Windows worker attempts used an incorrect default Gradle home and failed
to start GradleWorkerMain. Pinning JDK17 plus `D:\gradle-home-agent` resolved worker
startup; the tests then executed. A later Android incremental compiler emitted a
classpath snapshot diagnostic and recovered via fallback; the Android build and
instrumentation passed.

The local real-PostgreSQL suite was not run in this follow-up: Docker Desktop's
Linux engine was unavailable and `com.docker.service` is stopped; attempting to
start that service returned access denied. The final pushed head's real PostgreSQL
suite and platform acceptance passed in GitHub Actions run
[37130156891](https://github.com/fangbm/temvio/actions/runs/37130156891), including
all 18 PostgreSQL E2E tests, Desktop seed/resume/verify across client and relay
restarts, Android seed/resume/consent UI, and the opaque public-table canary scan.
All full CI jobs passed. D9-02 implementation acceptance is complete and
PR #24 is merged as `6583e61fc3101e5373d537f6b90675430a7aec10`. OD-012 remains a separate open release gate;
production-sensitive V3 receive/storage/upload remains disabled.

D9-03-00 documentation synchronization changes no D9-02 D2/wire/AAD/crypto/server
semantics or production-sensitive V3 composition. Its contract review is a new
independent slice after this merged baseline; D9-02 acceptance semantics remain unchanged.
