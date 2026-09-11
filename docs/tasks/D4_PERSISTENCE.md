# Agentic Scheduler — D4 Local Persistence Task Spec

> Task ID: **D4-01**  
> Title: **Local Persistence Baseline**  
> Milestone: **D4 — Persistence / Database**  
> Status: **IMPLEMENTED / VERIFIED / COMPLETE**
> Date: 2026-09-11
> Final implementation revision: `a8c7520` (`fix: close D4 persistence contract gaps`)

---

# 1. Goal

Implement the first real local persistence layer for Agentic Scheduler so the authoritative D2 Core Domain and D3 Academic source facts can survive process/device restarts on Android, Wear OS, Windows, and Linux while preserving exactly the same Domain semantics after round-trip reconstruction.

D4 establishes:

```text
application repository ports
+ Room 3 / SQLite KMP database
+ explicit persistence records
+ Domain ↔ persistence mappers
+ deterministic reads
+ atomic writes
+ schema export / migration baseline
```

D4 does **not** implement Planner, Sync, E2EE, Agent history, or Calendar UI.

The milestone proof is:

```text
valid D2/D3 Domain state
        ↓ map
Room/SQLite persisted state
        ↓ close/reopen
Room/SQLite persisted state
        ↓ map
same valid D2/D3 Domain state
```

---

# 2. Why now

D1 established module/toolchain boundaries.

D2 froze the Core Domain model.

D3 froze Academic Domain source facts and deterministic `CourseSession` derivation.

The next roadmap dependency is a durable local state layer before Calendar UI, Planner, Sync, E2EE, Wear offline replication, or Agent Tools can operate on real user-owned state.

D4 must therefore make Domain state persistent **without allowing persistence choices to leak upward and redefine Domain semantics**.

---

# 3. Authoritative baseline documents

Implementation must begin by reading:

```text
[x] docs/READ_FIRST.md
[x] docs/DOMAIN_INVARIANTS.md
[x] docs/IMPLEMENTATION_CONTRACT.md
[x] docs/ARCHITECTURE_DIAGRAMS.md
[x] docs/MODULE_OWNERSHIP.md
[x] docs/UBIQUITOUS_LANGUAGE.md
[x] docs/CODING_AGENT_POLICY.md
[x] docs/DEPENDENCY_POLICY.md
[x] docs/OPEN_DECISIONS.md
[x] docs/PERSISTENCE_DECISIONS.md
[x] docs/tasks/D2_CORE_DOMAIN.md
[x] docs/tasks/D3_ACADEMIC_DOMAIN.md
```

For D4-specific persistence choices, this task spec and `docs/PERSISTENCE_DECISIONS.md` are authoritative over older generic pre-D4 wording.

If implementation discovers a conflict with a higher frozen Domain invariant:

```text
DO NOT GUESS
→ BLOCKED_BY_SPEC_CONFLICT
→ identify the exact conflict
→ continue only independent work
```

---

# 4. Current state / inputs

Current Gradle modules:

```text
:shared:domain
:shared:database
:apps:android
:apps:desktop
:apps:wear
```

`shared:database` currently contains only the KMP module boundary and no persistence dependencies/schema.

D2 authoritative persisted concepts:

```text
Event
Task
FocusBlock
WorkLog
TaskDependency
PlanningProfile
```

D2 value/state structures used inside those entities include:

```text
TimePlacement
ZonedTimeRange
AllDayRange
FloatingTimeRange
Flexibility
PinState
TaskStatus
TaskPriority
TaskEffort
TaskDeadline
Deadline
DeadlinePolicy
OverflowPolicy
```

D3 authoritative persisted concepts:

```text
AcademicYear
Semester
AcademicWeek
Course
PeriodTemplate
AcademicPeriod
CourseScheduleRule
TeachingWeekSet
AcademicHoliday
CourseOccurrenceException
Exam
```

D3 derived, non-persisted concept:

```text
CourseSession
```

`CourseSession` remains derived from:

```text
Semester
+ Course
+ CourseScheduleRule
+ PeriodTemplate
+ AcademicHoliday
+ CourseOccurrenceException
```

---

# 5. D4 decisions resolved by this spec

This task adopts `docs/PERSISTENCE_DECISIONS.md` and resolves the D4 implementation path for:

```text
OD-010 — Concrete Room/KMP database configuration
OD-011 — Application/repository port placement
```

Frozen D4 stack:

```text
Room 3.0.3
SQLite KMP 2.7.0
BundledSQLiteDriver
KSP 2.3.12
kotlinx-coroutines 1.11.0
Room testing 3.0.3
```

Frozen repository/application boundary:

```text
:new :shared:application
        ↓
   :shared:domain

:shared:database
    → implements :shared:application persistence ports
    → maps to/from :shared:domain
```

`OD-012 — Local database encryption at rest` remains pending and does not block milestone implementation/testing, but it remains a production-security gate before treating local sensitive data protection as final.

---

# 6. MUST

D4 implementation MUST:

```text
- create :shared:application exactly as authorized by this task
- keep :shared:domain free of Room/SQLite/application dependencies
- pin all new dependency/plugin versions centrally
- use Room 3.0.3 and BundledSQLiteDriver
- configure Room schema export
- create AgenticSchedulerDatabase schema version 1
- create the exact D4 table inventory
- represent Room records separately from Domain entities
- implement explicit two-way mappers
- reconstruct Domain through normal constructors/factories
- implement application-facing repository ports
- implement concrete Room repository adapters
- implement ApplicationTransactionRunner
- make multi-table representation writes atomic
- make repository collection ordering deterministic
- preserve Instant precision through round trips
- preserve all D2/D3 sealed variants through round trips
- enforce direct database identity relations with foreign keys where specified
- use NO ACTION/RESTRICT behavior rather than cascade-delete business behavior
- establish schema/migration test infrastructure
- add focused persistence tests
- keep all existing D2/D3 tests green
- pass repository-wide CI
```

---

# 7. MAY

The implementer MAY choose local/reversible details that do not alter the frozen contracts, including:

```text
- exact Kotlin file split inside shared:database
- exact Room record class names
- exact DAO interface names
- mapper function/class organization
- private helper names
- exact index names
- whether simple scalar codecs are functions or dedicated internal objects
- whether repository adapters are one class per repository or grouped internally
- test fixture/helper organization
```

The implementation MAY use Room `@Transaction` DAO wrappers or Room 3 KMP write-transaction APIs internally where compatible with the required atomicity.

The implementation MAY use an in-memory Room database for tests that do not need file/schema migration behavior.

---

# 8. MUST NOT

D4 MUST NOT:

```text
- annotate Domain entities with Room annotations
- make shared:domain depend on shared:application or shared:database
- expose DAOs as application repository contracts
- expose Room entities/records outside persistence implementation APIs
- use SQLDelight or another database engine
- add SQLCipher or local encryption implementation
- store authoritative D2/D3 state as opaque JSON/BLOB payloads
- use enum ordinals in persisted representation
- silently truncate Instant/Duration precision
- invent business defaults while mapping legacy/null/corrupt rows
- use destructive migration fallback
- rely on database cascade delete as Domain behavior
- persist CourseSession
- persist a generated CourseSessionId
- create SyncOperation/DVV/HLC/conflict/tombstone tables
- implement Sync
- implement ChangeLog or AgentAction persistence
- implement AgentThread/history/context persistence
- implement Planner/PlanBranch persistence
- implement E2EE
- implement server persistence
- implement Calendar UI
- add serialization annotations to Domain for persistence convenience
- create speculative columns/tables for later milestones
```

---

# 9. Module contract

D4 adds exactly one new Gradle module:

```text
:shared:application
```

## `:shared:application`

Purpose:

```text
application-facing contracts that consume Domain semantics but are independent of concrete infrastructure
```

Allowed dependencies:

```text
:shared:domain
kotlinx-coroutines-core 1.11.0
kotlinx-collections-immutable (repository-selected current version)
```

Forbidden dependencies:

```text
Room
SQLite
Android framework
Compose
Ktor
Sync implementation
crypto implementation
LLM/provider SDKs
```

Canonical package for D4 ports:

```text
dev.agenticscheduler.application.persistence
```

## `:shared:database`

Purpose:

```text
Room schema
DAOs
migrations
record ↔ Domain mapping
repository implementations
transaction implementation
database builders/configuration
```

Allowed D4 dependencies:

```text
:shared:domain
:shared:application
androidx.room3:room3-runtime:3.0.3
androidx.room3:room3-testing:3.0.3 (test only)
androidx.sqlite:sqlite-bundled:2.7.0
kotlinx-coroutines-core:1.11.0
KSP processor androidx.room3:room3-compiler:3.0.3
```

Room Gradle plugin:

```text
id("androidx.room3") version 3.0.3
```

KSP plugin:

```text
id("com.google.devtools.ksp") version 2.3.12
```

Use target-appropriate KSP configurations for the KMP targets supported by the module; do not introduce KAPT.

---

# 10. Canonical application persistence API

Exact package:

```text
dev.agenticscheduler.application.persistence
```

The following public contracts are frozen at semantic/API-shape level.

Imports are omitted below for readability.

## `ApplicationTransactionRunner`

```kotlin
interface ApplicationTransactionRunner {
    suspend fun <T> inWriteTransaction(block: suspend () -> T): T
}
```

The block is one logical application write transaction.

D4 implementation guarantees that database repository calls made through adapters backed by the same `AgenticSchedulerDatabase` participate in the same Room transaction.

D4 does not add audit/sync side effects to this runner yet.

---

## `EventRepository`

```kotlin
interface EventRepository {
    fun observeAll(): Flow<ImmutableList<Event>>
    suspend fun get(id: EventId): Event?
    suspend fun upsert(event: Event)
}
```

---

## `TaskRepository`

```kotlin
interface TaskRepository {
    fun observeTasks(): Flow<ImmutableList<Task>>
    suspend fun getTask(id: TaskId): Task?
    suspend fun upsertTask(task: Task)

    fun observeFocusBlocks(): Flow<ImmutableList<FocusBlock>>
    suspend fun getFocusBlock(id: FocusBlockId): FocusBlock?
    suspend fun upsertFocusBlock(focusBlock: FocusBlock)

    fun observeWorkLogs(): Flow<ImmutableList<WorkLog>>
    suspend fun getWorkLog(id: WorkLogId): WorkLog?
    suspend fun upsertWorkLog(workLog: WorkLog)

    fun observeDependencies(): Flow<ImmutableList<TaskDependency>>
    suspend fun getDependency(id: TaskDependencyId): TaskDependency?
    suspend fun upsertDependency(dependency: TaskDependency)
}
```

D4 does not expose Task deletion semantics.

---

## `PlanningProfileRepository`

```kotlin
interface PlanningProfileRepository {
    fun observeAll(): Flow<ImmutableList<PlanningProfile>>
    suspend fun get(id: PlanningProfileId): PlanningProfile?
    suspend fun upsert(profile: PlanningProfile)
}
```

---

## `AcademicRepository`

```kotlin
interface AcademicRepository {
    fun observeAcademicYears(): Flow<ImmutableList<AcademicYear>>
    suspend fun getAcademicYear(id: AcademicYearId): AcademicYear?
    suspend fun upsertAcademicYear(value: AcademicYear)

    fun observeSemesters(): Flow<ImmutableList<Semester>>
    suspend fun getSemester(id: SemesterId): Semester?
    suspend fun upsertSemester(value: Semester)

    fun observeCourses(): Flow<ImmutableList<Course>>
    suspend fun getCourse(id: CourseId): Course?
    suspend fun upsertCourse(value: Course)

    fun observePeriodTemplates(): Flow<ImmutableList<PeriodTemplate>>
    suspend fun getPeriodTemplate(id: PeriodTemplateId): PeriodTemplate?
    suspend fun upsertPeriodTemplate(value: PeriodTemplate)

    fun observeCourseScheduleRules(): Flow<ImmutableList<CourseScheduleRule>>
    suspend fun getCourseScheduleRule(id: CourseScheduleRuleId): CourseScheduleRule?
    suspend fun upsertCourseScheduleRule(value: CourseScheduleRule)

    fun observeAcademicHolidays(): Flow<ImmutableList<AcademicHoliday>>
    suspend fun getAcademicHoliday(id: AcademicHolidayId): AcademicHoliday?
    suspend fun upsertAcademicHoliday(value: AcademicHoliday)

    fun observeCourseOccurrenceExceptions(): Flow<ImmutableList<CourseOccurrenceException>>
    suspend fun getCourseOccurrenceException(id: CourseOccurrenceExceptionId): CourseOccurrenceException?
    suspend fun upsertCourseOccurrenceException(value: CourseOccurrenceException)

    fun observeExams(): Flow<ImmutableList<Exam>>
    suspend fun getExam(id: ExamId): Exam?
    suspend fun upsertExam(value: Exam)
}
```

Repository write methods persist already-valid Domain values. They do not become owners of cross-entity business validation.

---

# 11. Repository ordering contract

SQLite row order is never treated as deterministic input.

All generic repository collection emissions MUST apply an explicit stable order.

Canonical generic order:

```text
entity typed ID canonical UUID text ascending
```

Nested Domain collections use their frozen Domain order:

```text
Semester.academicWeeks
→ week number ascending

PeriodTemplate.periods
→ period number ascending

TeachingWeekSet.weeks
→ week number ascending
```

Future query APIs may define another semantic order, but they must state it explicitly.

---

# 12. Database identity

Canonical Room database:

```text
AgenticSchedulerDatabase
```

Initial schema version:

```text
1
```

Schema export:

```text
exportSchema = true
shared/database/schemas
```

Canonical persistent filename:

```text
agentic-scheduler.db
```

Common builder configuration is equivalent to:

```kotlin
builder
    .setDriver(BundledSQLiteDriver())
    .setQueryCoroutineContext(Dispatchers.IO)
    // add explicitly registered migrations when versions > 1 exist
    .build()
```

No destructive migration fallback is configured.

Android/Wear obtain the file location through Android application context.

Desktop receives an explicit absolute persistent application-data path from application/platform composition. `shared:database` common code must not choose temp storage or current working directory as production persistence.

---

# 13. Persistence records and mapper rule

Every Room entity is a database representation owned by `shared:database`.

Conceptually:

```text
EventRecord           ↔ Event
TaskRecord            ↔ Task
FocusBlockRecord      ↔ FocusBlock
...
ExamRecord            ↔ Exam
```

The persistence side uses storage primitives.

The Domain side uses typed IDs/value types/sealed variants.

A mapper MUST reconstruct the Domain value through normal Domain constructors/factories.

Example principle:

```text
row.id: String
    ↓ EventId(row.id)
EventId
```

not:

```text
Domain Event annotated directly as @Entity
```

Mapper behavior for unknown/corrupt data:

```text
fail visibly
```

Forbidden mapping behavior:

```text
unknown status -> NORMAL
unknown timezone -> system timezone
invalid UUID -> random new UUID
invalid child row -> silently drop row
missing deadline policy -> choose NORMAL
invalid Duration -> clamp to zero
```

---

# 14. Scalar encoding

D4 must implement the canonical encoding from `PERSISTENCE_DECISIONS.md`.

## IDs

```text
SQLite TEXT
canonical lowercase RFC UUIDv7 representation
```

## Instant

Persist two integer fields:

```text
<name>_epoch_seconds
<name>_nanoseconds
```

Round-trip must preserve the exact `Instant` value.

## TimeZone

```text
TEXT zone ID
```

No system/default timezone substitution.

## LocalDate

```text
TEXT ISO-8601 YYYY-MM-DD
```

## LocalTime

```text
TEXT canonical ISO local time
```

## LocalDateTime

```text
TEXT canonical ISO local date-time
```

## Duration

```text
TEXT canonical ISO duration
```

D4 must not reduce a valid finite Duration to lossy whole milliseconds.

## enums / discriminators

```text
TEXT stable code
```

Enum ordinal persistence is forbidden.

---

# 15. Schema version 1

D4 database version 1 contains exactly these tables:

```text
events
tasks
focus_blocks
work_logs
task_dependencies
planning_profiles
academic_years
semesters
academic_weeks
courses
period_templates
academic_periods
course_schedule_rules
course_rule_teaching_weeks
academic_holidays
course_occurrence_exceptions
exams
```

The semantic columns and variants are frozen by `docs/PERSISTENCE_DECISIONS.md`.

Implementation must not compress multiple listed tables into an opaque serialized aggregate.

---

# 16. Relationship rules

Required direct foreign-key relations:

```text
focus_blocks.task_id -> tasks.id
work_logs.task_id -> tasks.id
task_dependencies.prerequisite_task_id -> tasks.id
task_dependencies.dependent_task_id -> tasks.id

semesters.academic_year_id -> academic_years.id
academic_weeks.semester_id -> semesters.id
courses.semester_id -> semesters.id
academic_periods.period_template_id -> period_templates.id
course_schedule_rules.course_id -> courses.id
course_schedule_rules.period_template_id -> period_templates.id when non-null
course_rule_teaching_weeks.schedule_rule_id -> course_schedule_rules.id
academic_holidays.semester_id -> semesters.id
course_occurrence_exceptions.schedule_rule_id -> course_schedule_rules.id
exams.semester_id -> semesters.id
exams.course_id -> courses.id when non-null
```

Deletion behavior:

```text
NO ACTION / RESTRICT
```

Do not use database cascade deletion to define product behavior.

Required uniqueness:

```text
task_dependencies(prerequisite_task_id, dependent_task_id)
course_occurrence_exceptions(schedule_rule_id, academic_week_number)
```

Composite primary keys:

```text
academic_weeks(semester_id, week_number)
academic_periods(period_template_id, period_number)
course_rule_teaching_weeks(schedule_rule_id, week_number)
```

Every foreign-key child column must be indexed.

Calendar range-query fields should receive explicit indexes appropriate to their variant-specific query path.

Exact index names are LOCAL_REVERSIBLE.

---

# 17. Multi-table Domain values

The following single Domain values map to parent + ordered child rows:

```text
Semester
→ semesters
→ academic_weeks

PeriodTemplate
→ period_templates
→ academic_periods

CourseScheduleRule
→ course_schedule_rules
→ course_rule_teaching_weeks
```

Each upsert is atomic.

Update algorithm must not expose partially replaced child collections.

An implementation may use replace-children semantics inside one transaction when updating these objects.

After readback, canonical Domain ordering must be restored before construction.

---

# 18. Event `TimePlacement` storage

`Event.time` is persisted explicitly by discriminator:

```text
ZONED
ALL_DAY
FLOATING
```

## ZONED

Stores:

```text
start Instant
endExclusive Instant
TimeZone ID
```

## ALL_DAY

Stores:

```text
startDate
endDateExclusive
```

## FLOATING

Stores:

```text
start LocalDateTime
endExclusive LocalDateTime
```

Only the payload columns belonging to the selected variant are populated.

Mapper validation must reject inconsistent persisted combinations rather than guessing a variant.

---

# 19. Task deadline storage

`Task.deadline == null`:

```text
all deadline-specific columns NULL
```

Non-null deadline uses discriminator:

```text
EXACT
DATE_ONLY
```

`EXACT` stores:

```text
Instant
TimeZone
DeadlinePolicy
OverflowPolicy
```

`DATE_ONLY` stores:

```text
LocalDate
DeadlinePolicy
OverflowPolicy
```

No database default may invent either policy.

---

# 20. Course rule storage

`CourseTimeSpec` discriminator:

```text
CLOCK
PERIOD_BASED
```

`CLOCK` stores local start/end times.

`PERIOD_BASED` stores:

```text
periodTemplateId
startPeriod
endPeriodInclusive
```

`TeachingWeekSet` is persisted as normalized rows in:

```text
course_rule_teaching_weeks
```

Readback must feed all stored week numbers through the canonical `TeachingWeekSet.of(...)` factory.

---

# 21. Course occurrence exception storage

Stable occurrence identity remains:

```text
CourseOccurrenceKey(
    scheduleRuleId,
    academicWeekNumber,
)
```

No generated occurrence/session ID is added.

Persist:

```text
exception id
target rule id
target academic week number
disposition
optional exact ZonedTimeRange override
RoomOverride discriminator/payload
```

`RoomOverride` codes:

```text
UNCHANGED
SET
CLEAR
```

For `SET`, a non-blank value must reconstruct successfully.

For `CANCELLED`, mapper/Domain reconstruction must reject persisted time or room overrides that violate the D3 constructor contract.

---

# 22. Exam storage

`ExamSchedule` discriminator:

```text
UNSCHEDULED
DATE_ONLY
EXACT
```

No schedule payload for `UNSCHEDULED`.

`DATE_ONLY` stores a LocalDate.

`EXACT` stores full `ZonedTimeRange` precision and timezone.

D4 persistence does not redefine D3 exam validation precedence.

Repository write/read mapping alone does not replace:

```text
validateExamAgainstSemester(...)
```

Cross-entity semantic validation remains a Domain/application responsibility.

---

# 23. CourseSession non-persistence invariant

D4 MUST NOT persist `CourseSession`.

The authoritative database state is the D3 source facts.

To obtain sessions after a process restart:

```text
load Semester/Course/rules/templates/holidays/exceptions
→ call CourseSessionResolver
→ derive CourseSession values
```

A restart must therefore reproduce the same resolver result from the same persisted source facts.

Persistence tests must include at least one close/reopen case proving this behavior.

---

# 24. Domain validation ownership

Database constraints may defensively protect direct storage integrity.

They do not replace Domain validation.

Examples:

```text
TaskDependency duplicate pair
→ DB uniqueness may defend
→ Domain owns duplicate/cycle semantics

Exam course/semester relationship
→ direct FKs may exist
→ Domain owns same-Semester semantic validation

Semester week ordering
→ child rows are stored
→ Domain Semester constructor owns semantic validity
```

Repository adapters must not invent a competing validation model.

---

# 25. Transaction semantics

D4 has two kinds of atomicity.

## Representation atomicity

One Domain value represented by several rows is saved atomically.

Required for:

```text
Semester
PeriodTemplate
CourseScheduleRule
```

## Application transaction atomicity

`ApplicationTransactionRunner.inWriteTransaction` allows multiple repository writes to commit or roll back together.

Example D4 test:

```text
transaction:
  upsert Task A
  upsert Task B
  throw test exception

expected:
  neither Task A nor Task B is committed
```

No UI, LLM, or provider adapter owns this boundary.

---

# 26. Coroutines / Flow behavior

Room 3 is coroutine-first.

D4 repository reads/writes follow these rules:

```text
- write I/O APIs are suspend
- point reads are suspend
- changing collection observations use Flow
- no LiveData in shared contracts
- no RxJava in shared contracts
- no global CoroutineScope
- database implementation owns database I/O execution details
```

`Flow<ImmutableList<T>>` emissions must not expose mutable storage collections.

---

# 27. Persistence error behavior

Normal absence:

```text
get(id) -> null
```

is allowed when the entity does not exist.

Invalid/corrupt persisted representation is **not** normal absence.

Corrupt mapping must fail visibly rather than becoming `null`.

D4 does not require a third-party functional error type.

Database closed/driver failures may propagate as infrastructure failures; do not blanket-catch every exception and return empty lists.

---

# 28. Migration baseline

D4 creates version `1` and therefore has no production prior schema.

Nevertheless D4 MUST establish the rules later versions depend on:

```text
schema JSON committed
monotonic Room version
no destructive fallback
migrations explicitly registered
migration-test helper configured against exported schemas
```

When version `2+` is introduced later, every supported old version must have a tested path to current.

A future schema-changing PR must not merge merely because Room opens a fresh database successfully.

---

# 29. Security / privacy impact

Classification:

```text
SENSITIVE_LOCAL_DATA
```

D4 local records may contain private schedule/task/academic information.

Forbidden logging:

```text
full Event/Task titles by default
full private schedule rows
raw database dumps
future provider credentials
future E2EE keys
```

D4 does not resolve local database encryption at rest.

`OD-012` remains a required security decision before final production local-data protection posture is declared complete.

No SQLCipher/custom crypto dependency is authorized by D4.

---

# 30. Sync / protocol impact

```text
NONE in D4
```

Persistence schema is **not** the future Sync wire schema.

D4 does not add serialization annotations to Domain.

D4 table/column layout does not freeze SyncOperation encoding or merge policy.

Future Sync may read/write the same authoritative facts through application transactions, but it must obey its own later protocol contracts.

---

# 31. Agent / context impact

```text
NONE
```

D4 does not create:

```text
AgentThread
AgentMessage
ContextSummary
AgentAction
ChangeLog
ToolResult persistence
```

No Agent Tool is introduced.

---

# 32. Platform impact

## Android

```text
Room database builder using application Context
persistent app database path
BundledSQLiteDriver
```

No UI integration required by D4.

## Wear OS

Uses the same Android KMP database target and Domain/repository semantics.

Wear remains a future real local-first node; D4 must not hard-code a phone-only database assumption.

No phone↔watch transport is implemented.

## Desktop (Windows / Linux)

JVM desktop database builder accepts an explicit absolute persistent database path from platform composition.

No temporary directory is the production default.

## Server

```text
NONE
```

PostgreSQL/Ktor server persistence is outside D4.

---

# 33. Dependencies

New D4 exact dependencies/plugins authorized:

```text
androidx.room3:room3-runtime:3.0.3
androidx.room3:room3-compiler:3.0.3
androidx.room3:room3-testing:3.0.3
androidx.sqlite:sqlite-bundled:2.7.0
com.google.devtools.ksp:2.3.12 plugin
androidx.room3:3.0.3 Gradle plugin
org.jetbrains.kotlinx:kotlinx-coroutines-core:1.11.0
```

Already approved/current dependency reused:

```text
org.jetbrains.kotlinx:kotlinx-collections-immutable
```

All exact versions belong in `gradle/libs.versions.toml` when catalog-compatible.

Toolchain versions otherwise remain unchanged.

---

# 34. Required tests — scalar codecs

Positive round-trip coverage:

```text
UUIDv7 typed ID text
Instant with non-zero nanoseconds
UTC TimeZone
non-UTC IANA TimeZone
LocalDate
LocalTime with sub-second value
LocalDateTime
zero Duration
positive Duration
large finite Duration within Domain validity
all persisted enum/discriminator codes
```

Negative coverage:

```text
invalid UUID text rejected
unknown enum/discriminator rejected
invalid timezone ID rejected
invalid ISO date/time/duration rejected
```

No negative case may silently substitute a default.

---

# 35. Required tests — D2 round trips

At minimum:

```text
Event Zoned
Event AllDay
Event Floating
Event each Flexibility
Event each PinState

Task with no deadline
Task Exact deadline
Task DateOnly deadline
Task nullable estimated/remaining combinations
Task all status/priority values through mapping

FocusBlock
WorkLog
TaskDependency
PlanningProfile
```

Round trip definition:

```text
Domain input == Domain output after actual Room insert/read
```

Use deterministic IDs and fixed values.

---

# 36. Required tests — D3 round trips

At minimum:

```text
AcademicYear
Semester with multiple/gapped explicit AcademicWeeks
Course with null code
Course with non-null code
PeriodTemplate with non-consecutive period numbers
CourseScheduleRule ClockTime
CourseScheduleRule PeriodBased
TeachingWeekSet canonical order/dedup preserved
AcademicHoliday NO_EFFECT
AcademicHoliday SUSPEND_TEACHING
CourseOccurrenceException ACTIVE unchanged
CourseOccurrenceException ACTIVE moved time
CourseOccurrenceException RoomOverride.Set
CourseOccurrenceException RoomOverride.Clear
CourseOccurrenceException CANCELLED
Exam Unscheduled
Exam DateOnly
Exam Exact
```

Nested collection reconstruction must preserve canonical Domain equality.

---

# 37. Required tests — persistence constraints

Verify with a real Room database:

```text
TaskDependency duplicate pair rejected defensively
CourseOccurrenceException duplicate occurrence target rejected defensively
required FK reference absence rejected
parent deletion with existing child does not cascade silently
multi-row Semester update is atomic
multi-row PeriodTemplate update is atomic
multi-row CourseScheduleRule update is atomic
```

DB constraints are defensive; tests must not rewrite Domain semantic expectations around them.

---

# 38. Required tests — transaction behavior

Required:

```text
successful ApplicationTransactionRunner block commits all writes
exception in block rolls back all writes
single multi-table aggregate upsert never exposes partial child state
Flow observers see committed state
Flow observers do not see an intermediate partially-updated aggregate
```

No test may depend on timing races or sleeps when deterministic synchronization is available.

---

# 39. Required tests — deterministic ordering

Required:

```text
insert entities in reverse/random fixture order
observe repository collection
assert canonical ID-text order
```

For nested collections:

```text
academic_weeks rows inserted/replaced
→ Semester.academicWeeks ascending

academic_periods rows
→ PeriodTemplate.periods ascending

course_rule_teaching_weeks rows
→ TeachingWeekSet canonical ascending
```

SQLite insertion/rowid order must not affect public output.

---

# 40. Required tests — CourseSession derivation after persistence

Persist one complete valid Academic source-fact fixture containing at minimum:

```text
AcademicYear
Semester + AcademicWeeks
Course
CourseScheduleRule
PeriodTemplate when applicable
AcademicHoliday
CourseOccurrenceException
```

Then:

```text
1. resolve CourseSessions from original Domain fixture
2. persist source facts
3. close database
4. reopen database
5. reload source facts
6. resolve CourseSessions again
7. assert identical CourseSessionResolutionResult
```

This is the D4 proof that derived Academic state remains derived and deterministic across persistence.

No CourseSession table may exist in the exported schema.

---

# 41. Required tests — schema / migration harness

D4 must:

```text
- generate version 1 Room schema JSON
- commit exported schema
- test opening the version 1 file database
- configure Room migration testing against the exported schema location
- verify current schema validity
```

Because D4 starts at v1, a data-transforming migration test is not required yet.

The test infrastructure must be ready so v2 cannot be introduced without migration coverage.

---

# 42. Acceptance criteria

D4 passes only when every item below is true.

```text
[x] :shared:application exists and has the frozen dependency direction
[x] repository ports live in :shared:application, not :shared:domain
[x] :shared:database implements those ports
[x] Room 3.0.3 pinned centrally
[x] SQLite bundled 2.7.0 pinned centrally
[x] KSP 2.3.12 pinned centrally
[x] coroutines 1.11.0 pinned centrally
[x] Room Gradle plugin 3.0.3 configured
[x] schema export points to shared/database/schemas
[x] AgenticSchedulerDatabase version == 1
[x] exact 17-table D4 inventory exists
[x] no CourseSession table exists
[x] no future Sync/Agent/Planner placeholder tables exist
[x] all persistence records are separate from Domain entities
[x] all required Domain↔record mappers exist
[x] no enum ordinal persistence exists
[x] Instant precision round-trips exactly
[x] Duration mapping is not lossy whole-millisecond storage
[x] D2 positive round-trip tests pass
[x] D3 positive round-trip tests pass
[x] all TimePlacement variants pass
[x] all ExamSchedule variants pass
[x] all CourseTimeSpec variants pass
[x] all RoomOverride variants pass
[x] repository list ordering is explicit/deterministic
[x] nested Academic list ordering is canonical
[x] required foreign keys/indexes/unique constraints exist
[x] no cascade delete defines business behavior
[x] ApplicationTransactionRunner commit test passes
[x] ApplicationTransactionRunner rollback test passes
[x] multi-row aggregate writes are atomic
[x] Flow observation test passes
[x] CourseSession re-derivation after DB reopen is identical
[x] exported schema committed
[x] migration-test harness works
[x] fallbackToDestructiveMigration is absent
[x] D2 tests remain green
[x] D3 tests remain green
[x] :shared:application build passes
[x] :shared:database build passes
[x] repository-wide build/test passes
[x] CI green on final D4 revision
```

---

# 43. Verification commands

Expected focused verification after implementation:

```bash
./gradlew :shared:domain:build --no-daemon
./gradlew :shared:application:build --no-daemon
./gradlew :shared:database:build --no-daemon
./gradlew build --no-daemon
```

If Room/KSP target-specific tasks need additional direct verification, the implementer reports the exact commands actually run.

The implementation report must not claim a command passed if it was not run.

---

# 44. Open decisions

For the D4 implementation path defined here:

```text
OD-010 RESOLVED by docs/PERSISTENCE_DECISIONS.md + this task
OD-011 RESOLVED by docs/PERSISTENCE_DECISIONS.md + this task
```

Still pending but not part of D4 implementation acceptance:

```text
OD-012 — Local database encryption at rest
```

D4 MUST NOT silently resolve OD-012 through an implementation dependency choice.

No other unresolved high-impact decision is required to satisfy the D4 acceptance path.

---

# 45. Out of scope / follow-ups

Explicitly deferred:

```text
public delete use-case semantics
soft delete / tombstone semantics
Sync metadata
DVV / HLC persistence
SyncOperation persistence
semantic merge/conflict persistence
ChangeLog / AgentAction persistence
AgentThread/message/context persistence
PlanBranch persistence
Planner score/config persistence beyond existing PlanningProfile scaffold
local database encryption at rest
E2EE
attachments/blobs
server PostgreSQL schema
remote backup
import/export
external calendar mappings
full-text search
semantic/vector search
Calendar UI queries beyond the D4 repository baseline
```

These may extend the schema only through later explicit decisions and migrations.

---

# 46. Completion report format

The coding agent implementing D4 must report:

```text
Implemented:
- dependencies/plugins added with exact versions
- module changes
- schema/tables
- repository ports/implementations
- mapping strategy
- transaction behavior
- schema export/migration harness

Tests/verification:
- exact commands run
- focused test results
- repo build result
- CI link/result

Architecture impact:
- :shared:application introduced as explicitly authorized
- no other architecture changes, or list approved changes

LOCAL_REVERSIBLE assumptions:
- list only local implementation choices

Blocked decisions:
- OD-012 remains pending; no local encryption implemented
- any newly discovered blockers

Out of scope left untouched:
- Sync
- Planner
- Agent history
- E2EE
- UI
- server persistence
```

---

# 47. Final D4 invariant

D4 is complete when the application can persist and reload all currently authoritative D2/D3 local state through application-owned repository contracts without changing Domain meaning, losing precision, relying on unspecified ordering, or introducing future-milestone semantics.

The final architectural shape is:

```text
Platform application
        ↓
:shared:application repository ports / transaction intent
        ↑ implemented by
:shared:database Room/SQLite adapters
        ↕ explicit mapping
:shared:domain authoritative semantics
```

**Persistence stores Domain truth; it does not become Domain truth.**
