# Agentic Scheduler — Persistence Decisions

> Status: **D4 Frozen Decisions**  
> Date: 2026-09-11  
> Applies to: D4 local persistence baseline and later persistence work unless superseded by an explicit ADR/task

This document freezes the concrete persistence choices required to implement D4 without allowing database convenience to redefine Domain semantics.

The governing principle remains:

```text
Domain meaning
    ↓ mapped explicitly
Application repository ports
    ↓ implemented by
Room/SQLite persistence
```

Persistence is storage and retrieval infrastructure. It is not a second domain model, a sync protocol, or a place to invent business defaults.

---

# PD-001 — Concrete persistence stack

D4 uses the following exact baseline:

```text
Room                  androidx.room3:room3-* 3.0.3
Room Gradle plugin    androidx.room3 3.0.3
SQLite driver         androidx.sqlite:sqlite-bundled 2.7.0
KSP                   com.google.devtools.ksp 2.3.12
Coroutines            org.jetbrains.kotlinx:kotlinx-coroutines-core 1.11.0
Room testing          androidx.room3:room3-testing 3.0.3
```

Current project Kotlin remains `2.3.21` and JDK/JVM target remains `17`.

Versions are pinned in `gradle/libs.versions.toml`. No dynamic/ranged dependency selectors are allowed.

D4 uses `BundledSQLiteDriver` on every currently supported database target:

```text
Android
Wear OS through the Android target
JVM Desktop (Windows / Linux)
```

Rationale:

- Room 3.x is the KMP-focused Room line.
- `BundledSQLiteDriver` gives the current clients one consistent SQLite implementation.
- D4 does not depend on Android-only SupportSQLite APIs.
- Room code generation uses KSP, not KAPT.

No SQLDelight, Realm, ObjectBox, SQLCipher, SupportSQLite compatibility wrapper, or alternate ORM is authorized by D4.

---

# PD-002 — Database identity and configuration

Canonical database class:

```text
AgenticSchedulerDatabase
```

Canonical persistent file name:

```text
agentic-scheduler.db
```

Initial Room schema version:

```text
1
```

Schema export directory:

```text
shared/database/schemas
```

Exported schema JSON is committed to Git and is part of the compatibility record.

The shared database builder configures:

```text
BundledSQLiteDriver
query coroutine context suitable for blocking database I/O
registered migrations
no destructive migration fallback
```

Platform-specific code owns only acquisition of an absolute database file path / Room builder:

```text
Android / Wear:
Context.getDatabasePath("agentic-scheduler.db")

Desktop:
platform application composition supplies an absolute persistent application-data path
```

Common code must not infer a hidden current working directory, temp directory, or user-home path for production persistence.

Tests may use temporary or in-memory databases when the test does not exercise migrations.

---

# PD-003 — Application/repository boundary

D4 authorizes one new Gradle module:

```text
:shared:application
```

Canonical root package:

```text
dev.agenticscheduler.application
```

The module exists because repository contracts are consumed by application logic and must not be owned by either Domain entities or Room infrastructure.

Dependency direction:

```text
apps:* ----------------------┐
                            ↓
                    :shared:application
                            ↓
                       :shared:domain

:shared:database
    ├─ depends on :shared:application to implement repository ports
    └─ depends on :shared:domain for Domain mapping
```

`shared:application` must not depend on Room, SQLite, Android framework, Compose, Ktor, Sync, crypto, or provider SDKs.

`shared:database` owns:

- Room entities/records;
- DAOs;
- database construction/configuration;
- migration definitions;
- Domain ↔ persistence mappers;
- repository implementations;
- concrete transaction implementation.

DAOs are persistence internals and are not application-facing public contracts.

This decision resolves the placement question tracked by `OD-011`.

---

# PD-004 — Repository port surface

D4 introduces application-facing repository ports for the currently authoritative D2/D3 source facts.

Required repository groups:

```text
EventRepository
TaskRepository
PlanningProfileRepository
AcademicRepository
ApplicationTransactionRunner
```

Repository APIs use Domain IDs and Domain values, never Room records.

Read APIs that expose changing collections use `Flow<ImmutableList<T>>`.

Point reads and writes are suspending where I/O is involved.

D4 repository ports expose persistence, not business operations. In particular, D4 does not introduce high-level delete semantics whose related-entity effects have not yet been frozen.

Required minimum behavior:

```text
EventRepository
- observe all Events
- get Event by EventId
- upsert Event

TaskRepository
- observe/get/upsert Task
- observe/get/upsert FocusBlock
- observe/get/upsert WorkLog
- observe/get/upsert TaskDependency

PlanningProfileRepository
- observe all PlanningProfiles
- get by PlanningProfileId
- upsert PlanningProfile

AcademicRepository
- observe/get/upsert AcademicYear
- observe/get/upsert Semester
- observe/get/upsert Course
- observe/get/upsert PeriodTemplate
- observe/get/upsert CourseScheduleRule
- observe/get/upsert AcademicHoliday
- observe/get/upsert CourseOccurrenceException
- observe/get/upsert Exam
```

`Semester` upsert includes its `academicWeeks` child rows atomically.

`PeriodTemplate` upsert includes its `periods` child rows atomically.

`CourseScheduleRule` upsert includes its canonical teaching-week rows atomically.

Repository collection emissions have deterministic order. Generic `observe all` results use canonical ID text ascending unless a stronger Domain order is part of the object itself. Child collections preserve their frozen semantic order:

```text
Semester.academicWeeks       by AcademicWeekNumber
PeriodTemplate.periods       by AcademicPeriodNumber
TeachingWeekSet.weeks        by AcademicWeekNumber
```

No repository API may rely on unspecified SQLite row order.

---

# PD-005 — Application transaction contract

The application layer owns logical atomic intent.

Canonical contract shape:

```kotlin
interface ApplicationTransactionRunner {
    suspend fun <T> inWriteTransaction(block: suspend () -> T): T
}
```

The Room implementation executes the block in one Room write transaction using Room 3's KMP transaction API.

Repository operations called within the block must participate in the same database transaction rather than independently committing partial state.

D4 does not yet append `ChangeLog`, `AgentAction`, or `SyncOperation`. Later milestones may extend the application transaction orchestration, but they must preserve this atomicity contract.

Saving one Domain value that spans multiple storage tables is also atomic inside the persistence implementation even when no outer application transaction is present.

Examples:

```text
Semester + academic_weeks
PeriodTemplate + academic_periods
CourseScheduleRule + course_rule_teaching_weeks
```

---

# PD-006 — Persistence representation rules

Room records are storage representations, not Domain entities.

Required rules:

```text
- no Room annotations in :shared:domain
- no Domain entity is used directly as a Room @Entity
- Room record fields use explicit storage primitives
- Domain typed IDs persist as canonical lowercase UUID text
- enums/discriminators persist as stable text codes, never enum ordinals
- no opaque JSON/BLOB encoding for D2/D3 authoritative core state
- no Java serialization
- no hidden product defaults during mapping
```

A mapper must reconstruct the public Domain object through its normal constructor/factory so Domain invariants run again on read.

If persisted data cannot reconstruct a valid Domain value, the mapper fails loudly as persistence corruption. It must not silently clamp, coerce, drop, default, or repair fields.

---

# PD-007 — Scalar encoding

Canonical SQLite scalar encoding for D4:

```text
Typed UUIDv7 ID     TEXT, canonical lower-case UUID text
Instant             INTEGER epoch seconds + INTEGER nanosecond adjustment
TimeZone            TEXT canonical zone ID
LocalDate           TEXT ISO-8601 YYYY-MM-DD
LocalTime           TEXT canonical ISO local time
LocalDateTime       TEXT canonical ISO local date-time
Duration            TEXT canonical ISO duration
Enum/discriminator  TEXT explicit stable code
DayOfWeek           TEXT explicit stable name, not ordinal
Int                  INTEGER
Nullable             SQL NULL only when Domain absence is legal
```

Instant values must round-trip without losing sub-millisecond precision.

Duration storage must not truncate a valid finite Domain duration merely to fit milliseconds.

Storage codecs must have positive and negative round-trip tests.

---

# PD-008 — Initial schema inventory

D4 schema version 1 contains exactly the authoritative D2/D3 source-fact tables needed by this milestone:

```text
1.  events
2.  tasks
3.  focus_blocks
4.  work_logs
5.  task_dependencies
6.  planning_profiles
7.  academic_years
8.  semesters
9.  academic_weeks
10. courses
11. period_templates
12. academic_periods
13. course_schedule_rules
14. course_rule_teaching_weeks
15. academic_holidays
16. course_occurrence_exceptions
17. exams
```

D4 MUST NOT create a `course_sessions` table. `CourseSession` remains a deterministic derived projection from D3 source facts.

D4 also MUST NOT create placeholder tables for future:

```text
PlanBranch
SyncOperation
DVV/HLC
SyncConflict
Tombstone metadata
ChangeLog
AgentAction
AgentThread / AgentMessage / ContextSummary
Provider credentials
E2EE keys
attachments/blobs
external calendar mappings
```

Those belong to later milestones and require their own contracts.

---

# PD-009 — Schema shape

The following logical shape is frozen. Exact Kotlin record class names may vary locally, but table/column meaning must not.

## `events`

```text
id PK

title
flexibility
pin_state

time_kind = ZONED | ALL_DAY | FLOATING

zoned_start_epoch_seconds?
zoned_start_nanoseconds?
zoned_end_epoch_seconds?
zoned_end_nanoseconds?
zoned_time_zone?

all_day_start_date?
all_day_end_date_exclusive?

floating_start_local?
floating_end_local_exclusive?
```

Only the payload columns belonging to `time_kind` are populated.

## `tasks`

```text
id PK
title
status
priority

effort_estimated_iso?
effort_completed_iso
effort_remaining_iso?

deadline_kind? = EXACT | DATE_ONLY
deadline_exact_epoch_seconds?
deadline_exact_nanoseconds?
deadline_exact_time_zone?
deadline_date?
deadline_policy?
overflow_policy?
```

All deadline columns are absent when `Task.deadline == null`.

## `focus_blocks`

```text
id PK
task_id FK -> tasks.id
start_epoch_seconds
start_nanoseconds
end_epoch_seconds
end_nanoseconds
time_zone
flexibility
pin_state
```

## `work_logs`

```text
id PK
task_id FK -> tasks.id
start_epoch_seconds
start_nanoseconds
end_epoch_seconds
end_nanoseconds
time_zone
```

## `task_dependencies`

```text
id PK
prerequisite_task_id FK -> tasks.id
dependent_task_id FK -> tasks.id
UNIQUE(prerequisite_task_id, dependent_task_id)
```

The database uniqueness constraint is defensive. Cycle semantics remain owned by Domain validation.

## `planning_profiles`

```text
id PK
name
```

## `academic_years`

```text
id PK
name
start_date
end_date_exclusive
```

## `semesters`

```text
id PK
academic_year_id FK -> academic_years.id
name
start_date
end_date_exclusive
time_zone
```

## `academic_weeks`

```text
semester_id FK -> semesters.id
week_number
start_date
end_date_exclusive
PRIMARY KEY(semester_id, week_number)
```

## `courses`

```text
id PK
semester_id FK -> semesters.id
name
code?
```

## `period_templates`

```text
id PK
name
```

## `academic_periods`

```text
period_template_id FK -> period_templates.id
period_number
start_local_time
end_local_time_exclusive
PRIMARY KEY(period_template_id, period_number)
```

## `course_schedule_rules`

```text
id PK
course_id FK -> courses.id
day_of_week
room?

time_kind = CLOCK | PERIOD_BASED

clock_start_local_time?
clock_end_local_time_exclusive?

period_template_id? FK -> period_templates.id
start_period?
end_period_inclusive?
```

## `course_rule_teaching_weeks`

```text
schedule_rule_id FK -> course_schedule_rules.id
week_number
PRIMARY KEY(schedule_rule_id, week_number)
```

## `academic_holidays`

```text
id PK
semester_id FK -> semesters.id
name
start_date
end_date_exclusive
teaching_effect
```

## `course_occurrence_exceptions`

```text
id PK
schedule_rule_id FK -> course_schedule_rules.id
academic_week_number
disposition

UNIQUE(schedule_rule_id, academic_week_number)

time_override_start_epoch_seconds?
time_override_start_nanoseconds?
time_override_end_epoch_seconds?
time_override_end_nanoseconds?
time_override_time_zone?

room_override_kind = UNCHANGED | SET | CLEAR
room_override_value?
```

For `SET`, `room_override_value` is non-null. For `UNCHANGED` / `CLEAR`, it is null.

For `CANCELLED`, all time override fields are null and room override is `UNCHANGED`.

## `exams`

```text
id PK
semester_id FK -> semesters.id
course_id? FK -> courses.id
title

schedule_kind = UNSCHEDULED | DATE_ONLY | EXACT
schedule_date?
exact_start_epoch_seconds?
exact_start_nanoseconds?
exact_end_epoch_seconds?
exact_end_nanoseconds?
exact_time_zone?
```

Cross-entity rules such as `Exam.courseId` belonging to the same Semester remain Domain validation, not database semantics.

---

# PD-010 — Foreign keys, indexes, and deletion

Foreign keys are defensive persistence integrity, not Domain delete policy.

D4 rules:

```text
- enable/use Room foreign keys for direct identity references
- ON DELETE NO ACTION / RESTRICT semantics
- no cascading delete as business behavior
- every foreign-key child column receives an index
- range-query fields used for calendar reads receive appropriate indexes
- unique constraints mirror already-frozen uniqueness where useful
```

A parent delete must not silently erase related user facts through a database cascade.

D4 does not freeze public delete use cases. Later application tasks must define deletion effects explicitly and execute them transactionally.

Internal child-row replacement during an aggregate upsert is allowed because it is an implementation detail of saving the same Domain value.

---

# PD-011 — Migration policy

Schema versions are monotonically increasing positive integers.

Initial D4 database is version `1`.

For every later schema-changing revision:

```text
- bump the Room schema version
- commit the exported schema
- provide a migration path from every supported prior version
- preserve user data unless an explicit product decision authorizes otherwise
- add a migration test using exported schemas
- verify the final schema against the current Room schema
```

`fallbackToDestructiveMigration` and equivalent destructive fallback behavior are forbidden for production databases.

Room auto-migrations may be used only for mechanical schema changes whose generated behavior is explicitly reviewed and migration-tested.

Manual migrations are required when data transformation, semantic reinterpretation, splitting/combining columns, or non-trivial backfill is needed.

D4 itself introduces version 1, so no v0→v1 production migration exists. D4 must nevertheless establish the export and migration-test harness for later versions.

---

# PD-012 — Testing strategy

Persistence tests run against real Room/SQLite behavior, not mocked DAO behavior, for storage correctness.

Required categories:

```text
mapper unit tests
Room round-trip tests
foreign-key / uniqueness tests
transaction rollback tests
Flow invalidation/observation tests
schema export verification
migration harness test
platform database-builder smoke tests where practical
```

Host JVM Room KMP tests are the default fast database test path. Android instrumentation tests are added only where Android-specific builder/integration behavior is being verified.

Every D2/D3 authoritative Domain shape gets at least one positive round-trip test.

Every sealed/discriminated storage mapping gets variant coverage.

---

# PD-013 — Corruption and compatibility behavior

Persistence must never convert unknown or invalid stored values into plausible Domain state.

Examples that must fail visibly:

```text
unknown enum/discriminator code
invalid UUID text
invalid time variant column combination
invalid negative/zero academic number
invalid period ordering reconstructed from rows
invalid Semester academic-week ordering
invalid RoomOverride payload combination
invalid Exam schedule payload combination
```

No mapper fallback such as `UNKNOWN -> NORMAL`, blank string substitution, current timezone substitution, or dropping malformed child rows is allowed.

Unknown future schema versions are not opened by guessing compatibility.

---

# PD-014 — Local-at-rest security boundary

`OD-012 — Local database encryption at rest` remains unresolved by D4.

Therefore D4 explicitly does **not** authorize:

```text
SQLCipher
custom database encryption
home-grown field encryption
secret-key storage design
silent claim that local SQLite is final production security posture
```

The D4 database may be implemented and tested inside the normal application sandbox for development/milestone work, but production release handling sensitive user data must not treat OD-012 as implicitly resolved.

No database row contents, full schedules, or other private payloads may be logged by default.

---

# PD-015 — Scope fence

D4 persistence is deliberately not Sync.

The following remain out of scope:

```text
DVV
HLC
SyncOperation
semantic merge
conflict records
tombstone compaction
E2EE
server database
remote backup
cloud account model
Agent history
Planner implementation
Calendar UI
notification scheduling
external calendar adapters
```

Future layers may add metadata/tables through explicit schema migrations. D4 must not pre-create speculative columns for them.

---

# Resolution summary

This document is intended to resolve the implementation choices tracked by:

```text
OD-010 — Concrete Room/KMP database configuration
OD-011 — Application/repository port placement
```

`OD-012 — Local database encryption at rest` remains `PENDING` and is explicitly outside the D4 implementation gate.

Once this decision document and the D4 Task Spec are approved, coding agents may implement D4 without independently revisiting Room-vs-SQLite-family choice, repository placement, database schema shape, mapping conventions, or migration baseline.
