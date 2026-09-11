# D3 — Immutable Collections Amendment and Migration Plan

> Status: **READY FOR IMPLEMENTATION**  
> Milestone: D3 amendment  
> Scope owner: `:shared:domain`  
> Decision source: `docs/IMMUTABLE_COLLECTIONS_DECISION.md`  
> Supersedes conflicting collection/dependency wording in `docs/tasks/D3_ACADEMIC_DOMAIN.md`.

This task is a narrow correction to D3's collection ownership implementation. It does not reopen Academic semantics, resolver behavior, IDs, time handling, DST policy, exception precedence, or persistence boundaries.

Where this file conflicts with the following parts of `D3_ACADEMIC_DOMAIN.md`, **this amendment wins**:

```text
§3  D3 MUST NOT — "add any third-party dependency"
§4  Dependency/tooling — "No new dependency is authorized"
§10 Semester collection type / implementation freedom
§13 TeachingWeekSet stored collection type
§16 PeriodTemplate collection type / implementation freedom
§25 CourseSessionResolutionResult stored collection types
§42 Semester immutable-snapshot test wording
§43 TeachingWeekSet immutable/canonical collection expectations
§44 PeriodTemplate immutable-snapshot test wording
§52 acceptance checklist dependency / immutable-ownership items
```

All unrelated D3 requirements remain unchanged.

---

# 1. Goal

Replace defensive-copy-plus-handwritten-value-semantics workarounds with type-level immutable collection ownership where appropriate.

The target state is:

```text
caller mutable collections
        ↓ explicit conversion at boundary
ImmutableList / other immutable collection
        ↓
valid Domain object
        ↓
generated data-class value semantics are safe where canonicalization cannot be bypassed
```

D3 must still guarantee that a caller cannot mutate an already-validated Domain object by retaining and mutating the original collection.

---

# 2. Dependency change

Add to `gradle/libs.versions.toml`:

```toml
[versions]
kotlinxCollectionsImmutable = "0.5.2"

[libraries]
kotlinx-collections-immutable = {
    module = "org.jetbrains.kotlinx:kotlinx-collections-immutable",
    version.ref = "kotlinxCollectionsImmutable"
}
```

Add to `shared/domain/build.gradle.kts`:

```kotlin
commonMain.dependencies {
    implementation(libs.kotlinx.datetime)
    implementation(libs.kotlinx.collections.immutable)
}
```

Do not add the dependency to UI, database, or platform modules merely for convenience.

Do not upgrade Kotlin, Gradle, AGP, Compose, or kotlinx-datetime as part of this task.

---

# 3. Semester migration

Replace the handwritten ordinary `class` implementation with a `data class` whose retained collection is immutable by type.

Target public shape:

```kotlin
data class Semester(
    val id: SemesterId,
    val academicYearId: AcademicYearId,
    val name: String,
    val startDate: LocalDate,
    val endDateExclusive: LocalDate,
    val timeZone: TimeZone,
    val academicWeeks: ImmutableList<AcademicWeek>,
) {
    init {
        // existing D3 invariants unchanged
    }
}
```

Do not reintroduce a retained `List<AcademicWeek>` field.

Delete handwritten `copy`, `equals`, `hashCode`, and `toString` once the immutable field type makes generated data-class behavior safe.

Construction invariants stay exactly as frozen in D3:

```text
name non-blank
positive Semester range
non-empty weeks
week 1 first
consecutive numbers
already ordered
weeks inside Semester
chronological/non-overlapping ranges
date gaps allowed
```

Call sites that currently own ordinary/mutable lists must convert them before constructing the canonical object, using `toImmutableList()` or an equivalent immutable construction path.

---

# 4. PeriodTemplate migration

Replace the handwritten ordinary `class` implementation with:

```kotlin
data class PeriodTemplate(
    val id: PeriodTemplateId,
    val name: String,
    val periods: ImmutableList<AcademicPeriod>,
) {
    init {
        // existing D3 invariants unchanged
    }
}
```

Delete handwritten `copy`, `equals`, `hashCode`, and `toString` after migration.

Existing invariants remain unchanged:

```text
name non-blank
periods non-empty
strictly ascending unique period numbers
already ordered
chronological times
non-overlap
adjacency/gaps allowed
non-consecutive numbers allowed
```

---

# 5. TeachingWeekSet migration

`TeachingWeekSet` remains a canonicalizing value type with a factory. Do **not** turn it into an unrestricted data class merely because immutable collections are available.

Target semantics:

```kotlin
class TeachingWeekSet private constructor(
    val weeks: ImmutableList<AcademicWeekNumber>,
) {
    companion object {
        fun of(weeks: Collection<AcademicWeekNumber>): TeachingWeekSet
    }

    // value equality/hash/toString remain explicit unless another safe
    // implementation preserves the canonical factory invariant.
}
```

`of(...)` must still:

```text
reject empty input
sort ascending
remove duplicates
store the canonical result as an immutable collection
produce equal values for semantically equivalent input
```

The key rule is that no public `copy(weeks = ...)` path may create an unsorted, duplicate, or empty `TeachingWeekSet`.

---

# 6. Resolver result migration

D3 says public Domain state is immutable. `CourseSessionResolutionResult` stores public collection-valued state, so migrate these fields too rather than fixing only Semester/PeriodTemplate.

Target shape:

```kotlin
sealed interface CourseSessionResolutionResult {
    data class Success(
        val sessions: ImmutableList<CourseSession>,
    ) : CourseSessionResolutionResult

    data class Invalid(
        val issues: ImmutableList<CourseSessionResolutionIssue>,
    ) : CourseSessionResolutionResult
}
```

The resolver may use mutable local lists/builders internally for deterministic computation. Convert only at the result ownership boundary.

All existing ordering and completeness semantics stay unchanged:

```text
Success contains the complete canonical session list
Invalid contains a non-empty deterministic issue list
no partial Success + issues hybrid
input iteration order does not affect output
```

---

# 7. Function input policy

Do not mechanically replace every input `Collection<T>` / `List<T>` in D3 with immutable collection interfaces.

The following style remains desirable for pure resolver/factory inputs when the function does not retain caller-owned collection references:

```kotlin
rules: Collection<CourseScheduleRule>
periodTemplates: Collection<PeriodTemplate>
holidays: Collection<AcademicHoliday>
exceptions: Collection<CourseOccurrenceException>
```

This keeps call sites flexible while ensuring stored Domain state is immutable at ownership boundaries.

---

# 8. D1 / D2 impact

## D1

No D1 architecture/code migration is required.

D1 froze the module boundary: `shared:domain` is pure Kotlin Multiplatform and must not absorb persistence/UI/network concerns. `kotlinx.collections.immutable` is a KMP collection utility and does not violate that ownership boundary.

The repository README should no longer describe the repository as if only the D1 bootstrap exists.

## D2

No D2 Domain type migration is currently required.

D2's task spec correctly prohibited adding a collections framework during the D2 milestone. Keep that historical scope fence intact.

The new dependency is a later explicit decision authorized by this D3 amendment; it does not retroactively change what D2 was allowed to implement.

The D2 dependency-cycle validator may retain local `mutableMapOf` / `MutableList` scratch structures because they are confined to a deterministic pure call and are not exposed as public Domain state.

If a future D2-derived public type gains stored collection-valued state, it should follow `docs/IMMUTABLE_COLLECTIONS_DECISION.md` at that time.

---

# 9. Tests to change/add

Keep existing D3 semantic tests, but change collection-ownership tests from implementation-specific defensive-copy wording to type/behavior guarantees.

Required coverage:

```text
Semester constructed from converted mutable input is unaffected by later input mutation
Semester.copy(...) accepts only immutable canonical collection state and preserves invariants
Semester structural equality/hash/toString remain data-class semantics

PeriodTemplate constructed from converted mutable input is unaffected by later input mutation
PeriodTemplate.copy(...) preserves invariant checks
PeriodTemplate structural equality/hash/toString remain data-class semantics

TeachingWeekSet stores immutable canonical ascending values
TeachingWeekSet equivalent unordered/duplicate inputs remain equal
no public path can construct invalid TeachingWeekSet state

Success.sessions is immutable stored Domain state
Invalid.issues is immutable stored Domain state
resolver ordering remains deterministic
```

Do not test implementation classes of the library. Test the Domain contract.

---

# 10. Files expected to change during implementation

At minimum:

```text
gradle/libs.versions.toml
shared/domain/build.gradle.kts
shared/domain/src/commonMain/kotlin/dev/agenticscheduler/domain/academic/AcademicCalendar.kt
shared/domain/src/commonMain/kotlin/dev/agenticscheduler/domain/academic/PeriodTemplate.kt
shared/domain/src/commonMain/kotlin/dev/agenticscheduler/domain/academic/CourseSchedule.kt
shared/domain/src/commonMain/kotlin/dev/agenticscheduler/domain/academic/CourseSessionResolver.kt
relevant commonTest files
```

If the exact `CourseSessionResolutionResult` declaration lives in another file, change that actual owner instead of moving it solely to match this list.

---

# 11. Non-goals

This amendment does **not** authorize:

```text
Planner implementation
PlanBranch implementation
Sync/DVV/HLC implementation
Room/SQLite/DAO/repository implementation
serialization annotations
new Gradle modules
broad replacement of every Kotlin collection in the repository
persistent collections as a database/log replacement
changes to Academic business semantics
changes to DST policy
changes to resolver precedence or ordering
```

Future milestones may use `PersistentList/Set/Map` where structural sharing is useful, but D3 should not implement future Planner/Sync state early.

---

# 12. Implementation order

Implement in this order to keep the diff reviewable:

1. Add and pin `kotlinx-collections-immutable:0.5.2` in the Version Catalog and `shared:domain` only.
2. Migrate `Semester` and its tests.
3. Migrate `PeriodTemplate` and its tests.
4. Migrate `TeachingWeekSet` storage without weakening its factory invariant.
5. Migrate resolver result stored lists to `ImmutableList`.
6. Update call sites with explicit `toImmutableList()` / immutable constructors.
7. Run focused `:shared:domain` tests.
8. Run repository-wide build.
9. Only after green tests, update the original D3 task spec wording so it directly reflects the implemented canonical shapes and remove the now-obsolete handwritten-class workaround language.

---

# 13. Gate

The amendment passes only if:

```text
[ ] exact dependency 0.5.2 is pinned centrally
[ ] dependency is added only where required
[ ] Semester stored weeks use ImmutableList
[ ] Semester is a data class with generated value semantics
[ ] no caller-owned mutable alias can mutate Semester
[ ] PeriodTemplate stored periods use ImmutableList
[ ] PeriodTemplate is a data class with generated value semantics
[ ] no caller-owned mutable alias can mutate PeriodTemplate
[ ] TeachingWeekSet stored weeks are immutable and canonical
[ ] TeachingWeekSet factory invariant cannot be bypassed
[ ] resolver Success/Invalid stored lists are immutable
[ ] existing D3 semantic invariants remain unchanged
[ ] D2 public contract remains unchanged
[ ] no future Planner/Sync implementation is pulled into D3
[ ] `./gradlew :shared:domain:build --no-daemon` passes
[ ] `./gradlew build --no-daemon` passes
[ ] CI is green
```

---

# 14. Follow-up after implementation

Once the code migration is proven green, fold this amendment into `docs/tasks/D3_ACADEMIC_DOMAIN.md` so there is again one canonical D3 task spec rather than two permanently divergent documents.

The fold-in must update the dependency section, canonical collection field types, tests, and D3 gate together. Do not leave the old "no new third-party dependency" sentence in the canonical D3 document after implementation.
