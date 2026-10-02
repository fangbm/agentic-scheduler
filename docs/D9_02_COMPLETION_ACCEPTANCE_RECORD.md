# D9-02 Completion Acceptance Record

Status: **IN PROGRESS — NOT COMPLETE**. PR #24 remains Draft.
Baseline `feature/d9-02-agent-sync` / `4dbe432`; branch
`codex/d9-02-05-agent-history-e2e`. Updated 2026-10-03.

The missing historical-source decision blocks A2 and the export portion of C9.
OD-012 remains OPEN as the separate production-sensitive local-data release gate.
Production compositions do not inject V3 upload/receive or the continuation reader.

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
| Consent/default OFF/owner acknowledgement/no automatic legacy export | PASS for consent behavior; local ProviderConfig/secret/legacy rows seeded and preserved. Explicit historical export remains BLOCKED_BY_DECISION. |
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

New PostgreSQL suite has 17 tests, 0 failures/skips on Windows. Platform harness
is intentionally SKIPPED in ordinary JVM builds without the explicit phase;
this skip is not counted as a passing cross-platform test.

The final additional immutable-ID adversarial assertion was verified with
`:shared:application:desktopTest --tests=dev.agenticscheduler.application.sync.D9AgentHistoryPostgresE2ETest
--no-daemon --no-configuration-cache --max-workers=1`: BUILD SUCCESSFUL in45s,
17 tests, 0 failures/errors/skips. This separate run does not replace the combined
suite counts above.

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

## Current CI and remaining gates

Full CI on head `cf2d0df`:
[37046317581](https://github.com/fangbm/temvio/actions/runs/37046317581) **PASS**:
`build`, `desktop-windows`, `android-keystore`, `wear-keystore` and
`agent-history-platform-e2e` all completed successfully. This includes actual
Linux Secret Service and enrolled Linux Desktop↔Android HTTPS/process-restart
acceptance. Earlier `37045497723` was cancelled by the documentation push, not a
test failure. The final supplemental adversarial assertion and this evidence
update receive a new complete CI run; the actual final-head result is recorded
in [PR #24 checks](https://github.com/fangbm/temvio/pull/24/checks) and the PR body.

BLOCKED_BY_DECISION: D9-01 has no durable turn completion, complete membership,
ancestry or terminal run outcome. `AgentRunService.handleResponse` persists an
assistant before creating its ToolCall. A tail assistant or terminal call cannot
prove historical completion. Maintainer decision requested: fail closed for
ambiguous legacy records and authorize new local completion/export metadata with
an explicit non-destructive migration, or retain the export blocker. No schema,
new wire model, retrospective reconstruction heuristic, or fabricated export
marker was introduced while awaiting that decision.

No roadmap COMPLETE/implementation-accepted update is authorized by this partial
record. No merge, D9-03/D10 start, D2/wire/AAD/crypto/server semantic change or
production-sensitive V3 composition is included.
