# Agentic Scheduler — Calendar Surface Decisions

> Status: **D5 Frozen Decisions**  
> Applies to: `docs/tasks/D5_CALENDAR_SURFACE.md`  
> Date: 2026-09-11

This document freezes the architecture choices required for the first production calendar/agenda surface. It does **not** authorize Planner, Sync, Agent, reminder, recurrence, external-calendar, or creation/editing work beyond the D5 Task Spec.

---

# CD-001 — Shared semantic projection, platform rendering

```text
Status: RESOLVED
Impact: ARCHITECTURE_AFFECTING / CONTRACT_AFFECTING
```

Decision:

```text
:shared:application owns semantic calendar projection/query logic.
Platform apps own Compose rendering, interaction, accessibility, and lifecycle.
:shared:domain remains UI-free.
:shared:database remains rendering-free.
```

D5 does not create a shared UI Gradle module.

The application layer may expose immutable presentation/projection values whose purpose is to preserve semantic distinctions needed by UI. These are not Domain entities and are not persistence records.

---

# CD-002 — First renderer scope and virtualization

```text
Status: RESOLVED
Impact: CONTRACT / PERFORMANCE
```

The first production renderer is a viewport-bounded **Agenda/Day** surface.

D5 uses platform lazy-list virtualization and does not freeze a Week-grid pixel/layout engine.

```text
Agenda/Day                  D5 AUTHORIZED
Week grid                   DEFERRED
Month grid                  DEFERRED
infinite calendar canvas    DEFERRED
```

Before the first production Week grid, OD-061 must be amended with an explicit large-calendar layout/virtualization strategy.

This decision resolves OD-061 for the D5 Agenda/Day renderer without pre-deciding later Week/Month rendering architecture.

---

# CD-003 — Viewport/time interpretation

```text
Status: RESOLVED
Impact: DETERMINISM / CONTRACT_AFFECTING
```

A calendar viewport explicitly contains:

```text
startDate
endDateExclusive
displayTimeZone
```

No projection code reads system-default timezone implicitly.

For filtering Zoned items, the viewport's exact Instant span is:

```kotlin
val startInstant = startDate.atStartOfDayIn(displayTimeZone)
val endInstantExclusive = endDateExclusive.atStartOfDayIn(displayTimeZone)
```

Use calendar-day boundaries in the explicit timezone; never compute the end as `start + 24h * dayCount`, because DST/calendar transitions may make a local day shorter or longer than 24 hours.

Projection rules:

```text
Zoned item
→ preserve underlying Instant range
→ display/group using viewport displayTimeZone

AllDay item
→ preserve date range
→ do not convert to 00:00/23:59 instants

Floating item
→ preserve LocalDateTime range
→ do not attach viewport/system timezone

Exam DateOnly
→ render as date-only academic item
→ do not invent an exact instant

Exam Unscheduled
→ absent from calendar projection
→ remains available in academic/task-specific surfaces
```

---

# CD-004 — CourseSession remains derived

```text
Status: RESOLVED
Impact: ARCHITECTURE_AFFECTING
```

D5 derives `CourseSession` from the D3 authoritative source facts using the existing deterministic resolver.

D5 MUST NOT:

```text
persist CourseSession
materialize CourseSession as an editable Event
invent CourseSessionId
silently drop CourseSessionResolutionIssue
```

Academic resolution failures are exposed as structured calendar projection issues.

---

# CD-005 — Calendar conflict baseline

```text
Status: RESOLVED
Impact: DOMAIN-FACING / DETERMINISM
```

D5 detects and surfaces overlaps only when both items resolve to concrete occupied instant ranges.

Initial conflict-capable sources:

```text
Event with ZonedTimeRange
FocusBlock
CourseSession
ExamSchedule.Exact
```

Overlap uses the frozen half-open rule:

```text
A.start < B.endExclusive
AND
B.start < A.endExclusive
```

Adjacent ranges do not conflict.

D5 does not invent cross-kind conflict semantics for:

```text
AllDay ↔ Zoned
Floating ↔ Zoned
Floating ↔ AllDay
```

and does not assume that a visible AllDay item blocks an entire day for Planner purposes.

Calendar conflicts are valid current state. D5 reports them; it does not reject persisted reality.

---

# CD-006 — Stable projection ordering

```text
Status: RESOLVED
Impact: DETERMINISM
```

Projection output must define explicit stable ordering.

Source-kind rank for final tie-breaking:

```text
EVENT          0
COURSE_SESSION 1
EXAM           2
FOCUS_BLOCK    3
```

Within projection groups:

```text
All-day/date-only:
  startDate
  endDateExclusive when present
  source-kind rank
  stable source identity

Zoned:
  start Instant
  endExclusive Instant
  source-kind rank
  stable source identity

Floating:
  local start
  local endExclusive
  source-kind rank
  stable source identity
```

CourseSession stable identity uses its frozen occurrence identity (`CourseOccurrenceKey`), not current start time.

Do not use repository emission order, hash-map iteration, or Compose insertion order as a tie-break.

---

# CD-007 — D5 UI architecture remains minimal

```text
Status: RESOLVED FOR D5
Impact: LOCAL / ARCHITECTURE GUARDRAIL
```

D5 remains within the existing OD-060 default:

```text
simple screen-local Compose state
constructor/manual dependency composition
no project-wide MVI framework
no navigation framework introduction
no DI framework introduction
```

OD-060 remains `PENDING` globally and must be resolved only when feature complexity actually requires a larger shared UI state/navigation architecture.

---

# CD-008 — D5-01 is read-oriented

```text
Status: RESOLVED
Impact: SCOPE
```

D5-01 does not create new Domain entities from UI input.

This deliberately kept D5-01 separate from production UUIDv7 generation.

D5-02 subsequently froze and implemented its explicit Event/Task creation-editing path under OD-004 / PLN-019. It does not alter the D5-01 read-surface boundary.

---

# CD-009 — Platform composition may depend on database infrastructure

```text
Status: RESOLVED
Impact: MODULE BOUNDARY / COMPOSITION
```

The D5 application/UI dependency sketch describes semantic ownership, not an artificial prohibition on composing infrastructure.

An existing platform app module MAY depend on `:shared:database` at its application/composition root in order to:

```text
open AgenticSchedulerDatabase
construct Room repository implementations
construct RepositoryCalendarQueryService
pass application-facing services/contracts into UI
```

The boundary is:

```text
platform composition root → database factories/repository implementations    ALLOWED
Compose screen/UI logic   → DAO / Room record / raw database API             FORBIDDEN
```

No new DI module/framework is introduced for this wiring. Use constructor/manual composition under the D5 OD-060 baseline.

This preserves the existing architecture's allowed app→infrastructure composition edge while ensuring calendar semantics and UI state consume `:shared:application` APIs rather than DAOs.

---

# Final D5 invariant

The first calendar UI is a **deterministic view over authoritative local Domain/application state**, not a new source of scheduling truth.
