package dev.agenticscheduler.planner

import dev.agenticscheduler.domain.planning.DeadlinePolicy
import dev.agenticscheduler.domain.planning.OverflowPolicy
import dev.agenticscheduler.domain.planning.PlanningProfileConfiguration
import dev.agenticscheduler.domain.task.Task
import dev.agenticscheduler.domain.task.TaskPriority
import dev.agenticscheduler.domain.task.TaskStatus
import dev.agenticscheduler.domain.time.ZonedTimeRange
import kotlin.time.Duration
import kotlin.time.Instant
import kotlinx.collections.immutable.toImmutableList
import kotlinx.datetime.TimeZone

/**
 * R6/R7 deterministic Full Replan core (PLN-014, D6R-003/004/006/007/008).
 *
 * One accepted [PlanningState] is the single semantic truth. Each Task is
 * planned as a task-local transaction that produces a [TaskPlanDelta]; commit
 * atomically replaces the accepted state and reject discards the delta whole.
 * Displacement of lower-authority reservations is an explicit finite operation
 * proven inside the same transaction. There is no cleanup-by-TaskId rollback.
 */
internal class FullReplanEngine(
    private val snapshot: PlanningSnapshot,
    private val config: PlanningProfileConfiguration.Configured,
    private val proposalSequence: ProposalSequence,
) {
    private val zone: TimeZone get() = config.timeZone
    private val askAuthorized = snapshot.askOverflowAuthorizedTaskIds.toSet()

    /** Lazy so that constructing the engine never touches an invalid snapshot's bounds. */
    private val futureBounds: Interval by lazy {
        Interval(maxOf(snapshot.horizon.start, snapshot.referenceNow), snapshot.horizon.endExclusive)
    }
    private lateinit var tasksById: Map<dev.agenticscheduler.domain.id.TaskId, Task>
    private lateinit var availability: List<Interval>
    private lateinit var fixedOccupancy: List<Interval>

    fun run(): PlannerResult {
        // Validation must run before any Interval or derived view is built (P0: an
        // out-of-range referenceNow must yield InvalidInput, never a throw).
        SnapshotValidation.invalidReason(snapshot)?.let {
            return PlannerResult.InvalidInput(listOf(it).toImmutableList())
        }
        val resolutionIssues = mutableListOf<PlannerIssue>()
        val availability = TimeResolution.availability(config, futureBounds.start, futureBounds.endExclusive, zone, resolutionIssues)
        val fixed = TimeResolution.fixedOccupancy(
            events = snapshot.events,
            courseSessionRanges = TimeResolution.scheduledCourseSessionRanges(snapshot.courseSessions),
            examRanges = TimeResolution.exactExamRanges(snapshot.exams),
            constraints = snapshot.constraints,
            zone = zone,
            policy = config.allDayEventPolicy,
            issues = resolutionIssues,
        )
        if (availability == null || fixed == null) {
            return PlannerResult.InvalidInput(resolutionIssues.distinct().sortedWith(IssueOrder.comparator).toImmutableList())
        }
        this.availability = availability
        this.fixedOccupancy = fixed

        val normalized = SnapshotNormalization.normalize(snapshot, config)
        tasksById = normalized.tasksById
        var state = normalized.state
        val originalReservationsByTask = normalized.state.reservations.groupBy { it.taskId }
        // Issues are attributed per Task so that the D6R-003 closure can replace a
        // re-planned Task's stale issues with its fresh ones.
        val issuesByTask = LinkedHashMap<dev.agenticscheduler.domain.id.TaskId, List<PlannerIssue>>()
        normalized.state.issues.forEach { issue ->
            val owner = plannerIssueTaskId(issue) ?: return@forEach
            issuesByTask[owner] = (issuesByTask[owner] ?: emptyList()) + issue
        }
        val waiting = normalized.eligibleTasks.sortedWith(::compareTaskServiceOrder).toMutableList()
        val hasAcceptedDelta = mutableSetOf<dev.agenticscheduler.domain.id.TaskId>()

        while (waiting.isNotEmpty()) {
            val selected = waiting.firstOrNull { task ->
                EffortTruth.dependencyEarliestStart(
                    task, snapshot.dependencies, tasksById, state.reservations, snapshot.referenceNow,
                ) != null
            }
            if (selected == null) {
                waiting.forEach { task ->
                    val blocker = EffortTruth.firstBlockingPrerequisite(
                        task, snapshot.dependencies, tasksById, state.reservations, snapshot.referenceNow,
                    )
                    if (blocker != null) {
                        issuesByTask[task.id] = listOf(PlannerIssue.DependencyBlocked(task.id, blocker.id))
                    }
                }
                break
            }
            waiting.remove(selected)
            // D6R-003 closure seeding: the trigger is any Task whose accepted truth
            // (coverage / planned completion) changes across the delta - the planned
            // Task's own self-replan as much as a cross-task displacement.
            val truthBefore = taskTruth(state)
            val outcome = planTask(state, selected)
            val nextState = state.plus(outcome.delta)
            val truthAfter = taskTruth(nextState)
            state = nextState
            issuesByTask[selected.id] = outcome.delta.issues
            hasAcceptedDelta += selected.id

            val truthChanged = snapshot.tasks.map { it.id }
                .filter { truthBefore[it] != truthAfter[it] }
                .toSet()
            // The planned Task's own delta is fresh by construction; everything
            // else whose truth moved - displaced owners included - is invalidated.
            // The planned Task's own truth change seeds the closure so its already
            // planned dependents are invalidated; the Task's own fresh delta is
            // excluded from the invalidation itself.
            val seeds = (truthChanged + outcome.delta.displacedTaskIds)
                .filter { it in hasAcceptedDelta }
                .toSet()
            if (seeds.isNotEmpty()) {
                val invalid = processedDependentClosure(seeds, hasAcceptedDelta) - setOf(selected.id)
                invalid.forEach { invalidated ->
                    // Roll back only the invalidated Task's OWN accepted delta
                    // contribution; the relocation/removal the CURRENT delta just
                    // proved and applied to it is part of this Task's atomic
                    // TaskPlanDelta and survives (D6R-003/004).
                    val restored = invalidationReservations(invalidated, outcome.delta, originalReservationsByTask)
                    state = state.invalidateTask(invalidated, restored)
                    issuesByTask.remove(invalidated)
                    hasAcceptedDelta -= invalidated
                    waiting.removeAll { it.id == invalidated }
                }
                waiting.addAll(invalid.mapNotNull { tasksById[it] })
                waiting.sortWith(::compareTaskServiceOrder)
            }
        }

        return finalize(state, issuesByTask)
    }

    /** The accepted truth of every Task: future coverage and planned completion. */
    private fun taskTruth(state: PlanningState): Map<dev.agenticscheduler.domain.id.TaskId, Pair<Duration, Instant?>> =
        snapshot.tasks.associate { task ->
            val coverage = SnapshotNormalization.futureCoverageOf(state.reservations, task.id, snapshot.referenceNow)
            task.id to (coverage to EffortTruth.plannedCompletion(task, state.reservations, snapshot.referenceNow))
        }

    /** Processes tasks reachable as transitive dependents of the displaced seeds. */
    private fun processedDependentClosure(
        seeds: Set<dev.agenticscheduler.domain.id.TaskId>,
        hasAcceptedDelta: Set<dev.agenticscheduler.domain.id.TaskId>,
    ): Set<dev.agenticscheduler.domain.id.TaskId> {
        val result = seeds.toMutableSet()
        val frontier = ArrayDeque(seeds)
        while (frontier.isNotEmpty()) {
            val current = frontier.removeFirst()
            snapshot.dependencies.filter { it.prerequisiteTaskId == current }.forEach { dependency ->
                val dependent = dependency.dependentTaskId
                if (dependent !in result && dependent in hasAcceptedDelta) {
                    result += dependent
                    frontier.addLast(dependent)
                }
            }
        }
        return result
    }

    /**
     * The reservations an invalidated Task keeps after its rollback: the current
     * delta's proven relocation replacements survive (they are part of the
     * planned Task's atomic TaskPlanDelta), its own earlier delta contributions
     * roll back to the normalized originals, and untouched originals remain.
     * Provenance is the stable reservation source identity (Existing block ID or
     * Proposed proposal key) - no history system is involved (D6R-003/004).
     */
    private fun invalidationReservations(
        taskId: dev.agenticscheduler.domain.id.TaskId,
        delta: TaskPlanDelta,
        originalReservationsByTask: Map<dev.agenticscheduler.domain.id.TaskId, List<Reservation>>,
    ): List<Reservation> {
        val touchedSources = delta.removedReservations.filter { it.taskId == taskId }.map { it.source }.toSet()
        val keptReplacements = delta.addedReservations.filter { it.taskId == taskId && it.source in touchedSources }
        val untouchedOriginals = (originalReservationsByTask[taskId] ?: emptyList())
            .filter { it.source !in touchedSources }
        return untouchedOriginals + keptReplacements
    }

    private fun plannerIssueTaskId(issue: PlannerIssue): dev.agenticscheduler.domain.id.TaskId? = when (issue) {
        is PlannerIssue.UnknownRemainingEffort -> issue.taskId
        is PlannerIssue.NoLegalAvailability -> issue.taskId
        is PlannerIssue.DependencyBlocked -> issue.taskId
        is PlannerIssue.OverflowApprovalRequired -> issue.taskId
        is PlannerIssue.HardDeadlineShortfall -> issue.taskId
        is PlannerIssue.UnscheduledEffort -> issue.taskId
        is PlannerIssue.OverallocatedPlannedEffort -> issue.taskId
        is PlannerIssue.ImmovableConflict, is PlannerIssue.InvalidSnapshot,
        is PlannerIssue.TimeResolutionFailure, PlannerIssue.ProfileUnconfigured,
        -> null
    }

    // ------------------------------------------------------------------ task transaction

    private data class PlanOutcome(val delta: TaskPlanDelta)

    private inner class Tentative(val base: PlanningState) {
        val working = base.reservations.toMutableList()
        val entries = mutableListOf<MutationWithCriteria>()
        val issues = mutableListOf<PlannerIssue>()

        /** Own subjects of this task not yet re-committed by this delta. */
        val pendingSubjects = mutableSetOf<Reservation>()

        /**
         * Free intervals for candidate search. Only [subject] itself is
         * replaceable by its own step; every other reservation — including the
         * task's own still-pending subjects — stays real occupancy so that no
         * candidate can land on a block that may end up preserved in place
         * (D6R-001).
         */
        fun viewFor(subject: Reservation?): List<Interval> =
            freeIntervals(working.filter { it !in pendingSubjects || it !== subject }.map { it.range })

        fun keep(subject: Reservation) {
            pendingSubjects.remove(subject)
        }

        fun replace(old: Reservation, new: Reservation?) {
            working.remove(old)
            pendingSubjects.remove(old)
            if (new != null) working += new
        }

        fun delta(taskId: dev.agenticscheduler.domain.id.TaskId): TaskPlanDelta {
            val removed = base.reservations.filter { it !in working }
            return TaskPlanDelta(
                taskId = taskId,
                removedReservations = removed,
                addedReservations = working.filter { it !in base.reservations },
                entries = entries.toList(),
                issues = issues.toList(),
                // Other Tasks whose accepted reservations this delta displaced or
                // removed: their coverage/completion truth changed, so their
                // accepted work must be invalidated and re-planned (D6R-003).
                displacedTaskIds = removed.map { it.taskId }.filter { it != taskId }.toSet(),
            )
        }
    }

    private fun planTask(base: PlanningState, task: Task): PlanOutcome {
        val tentative = Tentative(base)
        val after = requireNotNull(
            EffortTruth.dependencyEarliestStart(task, snapshot.dependencies, tasksById, base.reservations, snapshot.referenceNow),
        ) { "planTask selected a Task whose dependencies are not ready" }
        val cutoff = EffortTruth.effectiveCutoff(task, zone)
        val overflow = EffortTruth.overflowAuthorized(task, askAuthorized)
        val remaining = requireNotNull(task.effort.remaining)

        val ownSubjects = base.reservations
            .filter { it.taskId == task.id && (it.authority == ReservationAuthority.FLEXIBLE || it.authority == ReservationAuthority.SOFT) }
            .sortedWith(compareBy({ it.range.start }, { it.identityText() }))
        tentative.pendingSubjects.addAll(ownSubjects)

        fun coveredNow(): Duration = tentative.working
            .filter { it.taskId == task.id && it.range.start >= snapshot.referenceNow && it !in tentative.pendingSubjects }
            .fold(Duration.ZERO) { total, reservation -> total + (reservation.range.endExclusive - reservation.range.start) }

        if (remaining - coveredNow() < Duration.ZERO) {
            tentative.issues += PlannerIssue.OverallocatedPlannedEffort(task.id)
        }

        // Phase 1: reuse / move / resize / delete the Task's own movable reservations.
        ownSubjects.forEach { subject ->
            val demand = (remaining - coveredNow()).coerceAtLeast(Duration.ZERO)
            when (subject.authority) {
                ReservationAuthority.FLEXIBLE -> planFlexibleSubject(tentative, task, subject, after, cutoff, overflow)
                ReservationAuthority.SOFT ->
                    if (demand <= Duration.ZERO) {
                        deleteSubject(tentative, subject)
                    } else {
                        planSoftSubject(tentative, task, subject, demand, after, cutoff, overflow)
                    }
                else -> Unit
            }
        }

        if (remaining - coveredNow() < Duration.ZERO) {
            tentative.issues += PlannerIssue.OverallocatedPlannedEffort(task.id)
        }

        // Phase 2: create new SOFT + UNPINNED blocks for remaining demand.
        var demand = (remaining - coveredNow()).coerceAtLeast(Duration.ZERO)
        while (demand > Duration.ZERO) {
            val candidates = generateCandidates(
                tentative = tentative,
                task = task,
                demand = demand,
                fixedDuration = null,
                original = null,
                after = after,
                cutoff = cutoff,
                overflow = overflow,
            )
            val trace = CandidateComparison.decide(candidates) ?: break
            applyCandidate(tentative, trace, task, null)
            demand -= trace.winner.range.endExclusive - trace.winner.range.start
        }

        if (demand > Duration.ZERO) {
            if (task.deadline?.policy == DeadlinePolicy.HARD) {
                tentative.issues += PlannerIssue.HardDeadlineShortfall(task.id, demand)
            } else {
                if (task.deadline?.overflowPolicy == OverflowPolicy.ASK && task.id !in askAuthorized) {
                    tentative.issues += PlannerIssue.OverflowApprovalRequired(task.id)
                }
                tentative.issues += PlannerIssue.UnscheduledEffort(task.id, demand)
                if (tentative.entries.none { it.mutation.taskId == task.id }) {
                    tentative.issues += PlannerIssue.NoLegalAvailability(task.id)
                }
            }
        }

        // D6R-006 local check: HARD satisfaction counts only coverage completed by
        // the cutoff. finalize() revalidates every eligible HARD Task against the
        // final accepted state (authoritative, also covers blocked Tasks).
        if (task.deadline?.policy == DeadlinePolicy.HARD) {
            val satisfied = EffortTruth.coverageCompletedByCutoff(
                tentative.working, task.id, snapshot.referenceNow, cutoff!!,
            )
            if (satisfied < remaining) {
                if (tentative.issues.none { it is PlannerIssue.HardDeadlineShortfall }) {
                    tentative.issues += PlannerIssue.HardDeadlineShortfall(task.id, remaining - satisfied)
                }
            }
        }

        return PlanOutcome(tentative.delta(task.id))
    }

    private fun planFlexibleSubject(
        tentative: Tentative,
        task: Task,
        subject: Reservation,
        after: Instant,
        cutoff: Instant?,
        overflow: Boolean,
    ) {
        val duration = subject.range.endExclusive - subject.range.start
        val candidates = generateCandidates(
            tentative = tentative,
            task = task,
            demand = duration,
            fixedDuration = duration,
            original = subject,
            after = after,
            cutoff = cutoff,
            overflow = overflow,
        )
        val trace = CandidateComparison.decide(candidates)
        if (trace == null) {
            // A FLEXIBLE block is never deleted or resized. Its original placement is
            // preserved even when illegal, and the violation is surfaced (PLN-009).
            tentative.keep(subject)
            tentative.issues += if (subject.range.start < after) {
                dependencyViolationIssue(tentative, task, subject.range.start)
            } else {
                PlannerIssue.NoLegalAvailability(task.id)
            }
        } else {
            applyCandidate(tentative, trace, task, subject)
        }
    }

    private fun planSoftSubject(
        tentative: Tentative,
        task: Task,
        subject: Reservation,
        demand: Duration,
        after: Instant,
        cutoff: Instant?,
        overflow: Boolean,
    ) {
        val candidates = generateCandidates(
            tentative = tentative,
            task = task,
            demand = demand,
            fixedDuration = null,
            original = subject,
            after = after,
            cutoff = cutoff,
            overflow = overflow,
        )
        val trace = CandidateComparison.decide(candidates)
        if (trace == null) {
            deleteSubject(tentative, subject)
        } else {
            applyCandidate(tentative, trace, task, subject)
        }
    }

    private fun deleteSubject(tentative: Tentative, subject: Reservation) {
        val id = requireNotNull(subject.blockId)
        tentative.replace(subject, null)
        tentative.entries += MutationWithCriteria(
            FocusBlockMutation.Delete(id, subject.taskId),
            listOf(PlacementCriterion.CANONICAL_IDENTITY),
        )
    }

    /**
     * Structured issue for an existing block that cannot be placed legally
     * because it would start before a prerequisite's planned completion
     * (PLN-009: surface real-world violations, never create a new one).
     */
    private fun dependencyViolationIssue(
        tentative: Tentative,
        task: Task,
        subjectStart: Instant,
    ): PlannerIssue {
        val conflicting = snapshot.dependencies.filter { it.dependentTaskId == task.id }
            .mapNotNull { tasksById[it.prerequisiteTaskId] }
            .filter { it.status != TaskStatus.COMPLETED }
            .filter { prerequisite ->
                val completion = EffortTruth.plannedCompletion(prerequisite, tentative.working, snapshot.referenceNow)
                    ?: Instant.DISTANT_FUTURE
                completion > subjectStart
            }
            .minByOrNull { it.id.value }
        return if (conflicting != null) {
            PlannerIssue.DependencyBlocked(task.id, conflicting.id)
        } else {
            PlannerIssue.NoLegalAvailability(task.id)
        }
    }

    // ------------------------------------------------------------------ candidate generation

    private fun generateCandidates(
        tentative: Tentative,
        task: Task,
        demand: Duration,
        fixedDuration: Duration?,
        original: Reservation?,
        after: Instant,
        cutoff: Instant?,
        overflow: Boolean,
    ): List<PlacementCandidate> {
        val candidates = mutableListOf<PlacementCandidate>()

        // Preserve the exact original placement when legal (criterion 3, rank 0).
        if (original != null && isPlacementLegal(original.range, task, after, cutoff, overflow)) {
            val duration = original.range.endExclusive - original.range.start
            val coveredByPreserve = fixedDuration != null || duration <= demand
            if (coveredByPreserve && tentative.viewFor(original).any { it.contains(original.range) }) {
                candidates += PlacementCandidate(
                    range = original.range,
                    taskId = task.id,
                    sourceReservation = original,
                    displacements = emptyList(),
                    overflowLegalityRank = overflowRank(cutoff, original.range),
                    overflowLatenessMillis = latenessMillis(cutoff, original.range, demand, fixedDuration),
                    preserveRank = 0,
                    movementMillis = 0L,
                    contextSwitchDelta = 0,
                    preferredDistanceMillis = preferredDistanceMillis(duration),
                    durationMillis = duration.inWholeMilliseconds,
                )
            }
        }

        // No candidate may start at or beyond the horizon end.
        if (after >= futureBounds.endExclusive) return candidates
        val futureWindow = Interval(after, futureBounds.endExclusive)
        val cleanIntervals = tentative.viewFor(original).mapNotNull { clipInterval(it, futureWindow) }
        val displaceable = tentative.working.filter { it !in tentative.pendingSubjects && isDisplaceable(it, task) }
        val displacementIntervals = tentative.viewIgnoringDisplaceable(original, task).mapNotNull { clipInterval(it, futureWindow) }
        // D6R-007: the displaced reservations' own boundaries are accepted-state
        // boundaries. The displacement view removes them from occupancy, so they
        // become interior structural start points of the resulting intervals.
        val displacementBoundaries = displaceable
            .flatMap { reservation ->
                listOf(reservation.range.start, reservation.range.endExclusive) +
                    (tasksById[reservation.taskId]?.deadline?.let { listOf(TimeResolution.effectiveCutoff(it.deadline, zone)) } ?: emptyList())
            }
            .filter { it >= after && it < futureBounds.endExclusive }

        // Clean candidates on genuinely free space (criterion 3, rank 1).
        cleanIntervals.forEach { interval ->
            collectIntervalCandidates(
                interval, task, demand, fixedDuration, original, after, cutoff, overflow,
                tentative, preserveRank = 1, requireDisplacement = false,
                extraBoundaries = emptyList(), output = candidates,
            )
        }
        // Displacement candidates on space held by lower-authority reservations (rank 2).
        displacementIntervals.forEach { interval ->
            collectIntervalCandidates(
                interval, task, demand, fixedDuration, original, after, cutoff, overflow,
                tentative, preserveRank = 2, requireDisplacement = true,
                extraBoundaries = displacementBoundaries.filter { it > interval.start && it < interval.endExclusive },
                output = candidates,
            )
        }

        return candidates.distinctBy {
            Triple(
                it.range,
                it.sourceReservation?.identityText(),
                it.displacements.map { d -> d.displaced.identityText() to d.replacement?.identityText() },
            )
        }
    }

    private fun collectIntervalCandidates(
        interval: Interval,
        task: Task,
        demand: Duration,
        fixedDuration: Duration?,
        original: Reservation?,
        after: Instant,
        cutoff: Instant?,
        overflow: Boolean,
        tentative: Tentative,
        preserveRank: Int,
        requireDisplacement: Boolean,
        extraBoundaries: List<Instant>,
        output: MutableList<PlacementCandidate>,
    ) {
        // D6R-007: structural starts x legal durations. The PLN-013 duration set is
        // computed per structural start from that start's own segment capacity
        // (U = min(R, C, max) changes as the start moves later), never once for the
        // whole interval.
        val baseStarts = CandidateGeneration.structuralStarts(
            CandidateGeneration.StructureInputs(
                interval = interval,
                after = after,
                cutoff = cutoff,
                originalStart = original?.range?.start,
                tentativeBoundaries = extraBoundaries,
            ),
        )
        data class StartDuration(val start: Instant, val duration: Duration)

        val pairs = mutableListOf<StartDuration>()
        fun enumerate(start: Instant, durations: List<Duration>) {
            durations.forEach { duration ->
                if (duration <= interval.endExclusive - start) pairs += StartDuration(start, duration)
            }
        }
        fun segmentDurations(start: Instant): List<Duration> = CandidateGeneration.durationsForSegment(
            demand, interval.endExclusive - start, fixedDuration, config,
        )

        baseStarts.forEach { start -> enumerate(start, segmentDurations(start)) }
        // The clamped original start must be present with its own legal durations so
        // the movement criterion is meaningful (D6R-007).
        original?.range?.start?.let { originalStart ->
            val clampDurations = fixedDuration?.let { listOf(it) }
                ?: CandidateGeneration.durationCandidates(demand, interval.endExclusive - interval.start, config)
            clampDurations.forEach { duration ->
                CandidateGeneration.clampedOriginalStarts(interval, cutoff, originalStart, duration).forEach { start ->
                    enumerate(start, segmentDurations(start))
                }
            }
        }

        pairs.distinctBy { it.start to it.duration }.forEach { (start, duration) ->
            val segment = Interval(start, interval.endExclusive)
            val range = Interval(start, start + duration)
            if (!PlacementLegality.isDeadlineLegal(cutoff, overflow, range)) return@forEach

            val displaced = tentative.working.filter {
                it !in tentative.pendingSubjects && isDisplaceable(it, task) && overlaps(it.range, range)
            }
            if (requireDisplacement && displaced.isEmpty()) return@forEach

            val arrangement = arrangeDisplacements(tentative, task, range, displaced) ?: return@forEach
            // PLN-009: the candidate must respect the dependency boundary computed
            // against the POST-arrangement state - the arrangement may have moved
            // the planning Task's own prerequisite past the candidate, and a
            // prerequisite pushed out of coverage makes the candidate illegal.
            val arrangementDisplaced = arrangement.map { it.displaced }.toSet()
            val postArrangement = tentative.working.filter { it !in arrangementDisplaced } +
                arrangement.mapNotNull { it.replacement } +
                Reservation(task.id, range, ReservationAuthority.NEW, ReservationSource.Proposed(ProposalKey(task.id, Int.MIN_VALUE)), null)
            val postArrangementAfter = EffortTruth.dependencyEarliestStart(
                task, snapshot.dependencies, tasksById, postArrangement, snapshot.referenceNow,
            )
            if (postArrangementAfter == null || range.start < postArrangementAfter) return@forEach
            output += PlacementCandidate(
                range = range,
                taskId = task.id,
                sourceReservation = original,
                displacements = arrangement,
                overflowLegalityRank = overflowRank(cutoff, range),
                overflowLatenessMillis = latenessMillis(cutoff, range, demand, fixedDuration),
                preserveRank = preserveRank,
                movementMillis = original?.let { kotlin.math.abs((range.start - it.range.start).inWholeMilliseconds) } ?: 0L,
                contextSwitchDelta = contextSwitchDelta(task.id, range, contextOccupancy(tentative, arrangement)),
                preferredDistanceMillis = preferredDistanceMillis(duration),
                durationMillis = duration.inWholeMilliseconds,
            )
        }
    }

    /** PLN-013 chunk-rank distance key: absolute distance from the preferred duration. */
    private fun preferredDistanceMillis(duration: Duration): Long =
        kotlin.math.abs((duration - config.preferredFocusBlock).inWholeMilliseconds)

    /**
     * Builds the finite displacement arrangement required by a candidate range.
     * Every displaced FLEXIBLE reservation must receive a proven same-ID,
     * same-duration relocation inside this transaction; displaced SOFT/NEW
     * reservations may fall back to authorized removal after every relocation
     * option (including identity-preserving resize) has been exhausted.
     * The search is a finite depth-first walk with backtracking over each
     * reservation's relocation options in PLN-015 order, so a greedy first
     * choice can never manufacture a false "no arrangement" answer.
     */
    private fun arrangeDisplacements(
        tentative: Tentative,
        planningTask: Task,
        range: Interval,
        displaced: List<Reservation>,
    ): List<Displacement>? {
        if (displaced.isEmpty()) return emptyList()
        val displacedSet = displaced.toSet()
        val ordered = displaced.sortedWith(compareBy({ it.range.start }, { it.identityText() }))
        // D6R-007: the displaced reservations' range boundaries and their Tasks'
        // deadline boundaries shape the arrangement - a later reservation's cutoff
        // can be exactly the slot an earlier one must backtrack into.
        val arrangementBoundaries = ordered.flatMap { reservation ->
            listOf(reservation.range.start, reservation.range.endExclusive) +
                (tasksById[reservation.taskId]?.deadline?.let { listOf(TimeResolution.effectiveCutoff(it.deadline, zone)) } ?: emptyList())
        }.filter { it >= snapshot.referenceNow && it < futureBounds.endExclusive }

        fun dfs(index: Int, taken: List<Interval>, acc: List<Displacement>): List<Displacement>? {
            if (index == ordered.size) return acc
            val reservation = ordered[index]
            val fixedOnly = reservation.authority == ReservationAuthority.FLEXIBLE
            val demand = displacedTaskRemainingDemand(tentative, reservation, displacedSet)
            // Relocation options ranked with the same PLN-015 comparator as
            // placement candidates (deadline class, lateness, movement, chunk
            // rank, ...) — never a hand-rolled same-duration-first shortcut.
            val options = relocationOptions(tentative, reservation, demand, displacedSet, taken, acc, arrangementBoundaries, planningTask.id, range)
                .map { optionRange ->
                    val duration = optionRange.endExclusive - optionRange.start
                    val cutoff = relocationCutoff(reservation)
                    PlacementCandidate(
                        range = optionRange,
                        taskId = reservation.taskId,
                        sourceReservation = reservation,
                        displacements = emptyList(),
                        overflowLegalityRank = overflowRank(cutoff, optionRange),
                        overflowLatenessMillis = latenessMillis(cutoff, optionRange, demand ?: duration, null),
                        preserveRank = if (optionRange == reservation.range) 0 else 1,
                        movementMillis = kotlin.math.abs((optionRange.start - reservation.range.start).inWholeMilliseconds),
                        contextSwitchDelta = contextSwitchDelta(
                            reservation.taskId, optionRange,
                            tentative.working.filter { it !in displacedSet } + acc.mapNotNull { it.replacement },
                        ),
                        preferredDistanceMillis = preferredDistanceMillis(duration),
                        durationMillis = duration.inWholeMilliseconds,
                    )
                }
            val ranked = options.sortedWith(CandidateComparison::compare)
            val comparatorWinner = ranked.firstOrNull()
            for (option in ranked) {
                val replacement = reservation.copy(range = option.range)
                // A criterion trace is only truthful for the comparator winner; a
                // feasibility-driven fallback choice lost the comparison and won
                // only because the winner's subtree was infeasible (D6R-008).
                val criteria = if (option == comparatorWinner) {
                    CandidateComparison.decisionCriteria(option, options)
                } else {
                    listOf(PlacementCriterion.CANONICAL_IDENTITY)
                }
                val next = dfs(index + 1, taken + option.range, acc + Displacement(reservation, replacement, criteria)) ?: continue
                return next
            }
            if (!fixedOnly) {
                // Authorized removal after every relocation option failed.
                return dfs(index + 1, taken, acc + Displacement(reservation, null, listOf(PlacementCriterion.CANONICAL_IDENTITY)))
            }
            return null
        }

        return dfs(0, listOf(range), emptyList())
    }

    private fun relocationCutoff(reservation: Reservation): Instant? =
        tasksById[reservation.taskId]?.let { EffortTruth.effectiveCutoff(it, zone) }

    /** Remaining demand of the displaced Task excluding every reservation displaced in this arrangement. */
    private fun displacedTaskRemainingDemand(
        tentative: Tentative,
        reservation: Reservation,
        displacedSet: Set<Reservation>,
    ): Duration? {
        val displacedTask = tasksById[reservation.taskId] ?: return null
        val remaining = displacedTask.effort.remaining ?: return null
        val otherCoverage = tentative.working
            .filter {
                it.taskId == displacedTask.id && it !in displacedSet &&
                    it.range.start >= snapshot.referenceNow
            }
            .fold(Duration.ZERO) { total, r -> total + (r.range.endExclusive - r.range.start) }
        return (remaining - otherCoverage).takeIf { it > Duration.ZERO }
    }

    /**
     * Complete finite relocation search (D6R-007): every structural start of every
     * free interval (interval start, dependency and deadline boundaries, the
     * clamped original start) is enumerated with the relocation duration and
     * filtered by the displaced Task's deadline legality. The closest position is
     * never treated as the only one: when it is illegal, an earlier legal position
     * in the same interval is still found.
     *
     * A Task whose dependency completion is unresolved (blocked) has no legal
     * relocation at all: any placement could violate the unknown dependency
     * boundary, so the reservation keeps its original range (D6R-002/PLN-009).
     *
     * [resizedDuration] is set for identity-preserving SOFT/NEW resize relocations.
     */
    private fun relocationOptions(
        tentative: Tentative,
        reservation: Reservation,
        resizedDuration: Duration?,
        displacedSet: Set<Reservation>,
        taken: List<Interval>,
        arranged: List<Displacement>,
        extraBoundaries: List<Instant>,
        planningTaskId: dev.agenticscheduler.domain.id.TaskId,
        candidateRange: Interval,
    ): List<Interval> {
        val displacedTask = tasksById[reservation.taskId] ?: return emptyList()
        val cutoff = EffortTruth.effectiveCutoff(displacedTask, zone)
        val overflow = EffortTruth.overflowAuthorized(displacedTask, askAuthorized)
        // The dependency truth is evaluated against the POST-arrangement view:
        // earlier relocations in the same transaction have already moved their own
        // reservations, and this relocation must respect the boundary that results
        // from them (D6R-003). D6R-002/PLN-009: an unresolved dependency boundary
        // means no placement of this Task's blocks can be proven legal - never
        // guess "now".
        val provisional = tentative.working.filter { it !in displacedSet } + arranged.mapNotNull { it.replacement } +
            // The candidate itself counts: a displaced Task that depends on the
            // planning Task must respect the planning Task's post-candidate
            // completion (PLN-009).
            listOf(Reservation(planningTaskId, candidateRange, ReservationAuthority.NEW, ReservationSource.Proposed(ProposalKey(planningTaskId, Int.MIN_VALUE)), null))
        val after = EffortTruth.dependencyEarliestStart(
            displacedTask, snapshot.dependencies, tasksById, provisional, snapshot.referenceNow,
        ) ?: return emptyList()
        val cuts = fixedOccupancy + provisional.map { it.range } + taken
        val space = subtractIntervals(availability, cuts).mapNotNull { clipInterval(it, futureBounds) }

        val options = mutableListOf<Interval>()
        fun enumerate(interval: Interval, duration: Duration) {
            val starts = CandidateGeneration.structuralStarts(
                CandidateGeneration.StructureInputs(
                    interval = interval,
                    after = after,
                    cutoff = cutoff,
                    originalStart = reservation.range.start,
                    tentativeBoundaries = extraBoundaries.filter { it > interval.start && it < interval.endExclusive },
                ),
            ) + CandidateGeneration.clampedOriginalStarts(
                interval, cutoff, reservation.range.start, duration,
            )
            starts.distinct().forEach { start ->
                if (start + duration > interval.endExclusive) return@forEach
                val range = Interval(start, start + duration)
                if (!PlacementLegality.isDeadlineLegal(cutoff, overflow, range)) return@forEach
                options += range
            }
        }

        val sameDuration = reservation.range.endExclusive - reservation.range.start
        val fixedDuration = if (reservation.authority == ReservationAuthority.FLEXIBLE) sameDuration else null
        space.forEach { interval ->
            if (interval.endExclusive - interval.start >= sameDuration) {
                enumerate(interval, sameDuration)
            }
            if (fixedDuration == null && resizedDuration != null && interval.endExclusive - interval.start >= resizedDuration) {
                // Identity-preserving SOFT/NEW resize: PLN-013 durations for the
                // displaced Task's own demand, never enlarging the block.
                CandidateGeneration.durationCandidates(
                    resizedDuration, interval.endExclusive - interval.start, config,
                ).filter { it <= sameDuration }
                    .forEach { duration -> enumerate(interval, duration) }
            }
        }
        return options.distinct()
    }

    // ------------------------------------------------------------------ application

    private fun applyCandidate(
        tentative: Tentative,
        trace: DecisionTrace,
        task: Task,
        subject: Reservation?,
    ) {
        val winner = trace.winner
        val criteria = trace.criteriaForWinner()

        winner.displacements.forEach { displacement ->
            val id = displacement.displaced.blockId
            val replacement = displacement.replacement
            if (replacement == null) {
                tentative.replace(displacement.displaced, null)
                // A displaced planner-created proposal has no Active State ID: the
                // accepted state simply loses the proposal, and netMutations() never
                // emits a Delete for an ID that does not exist.
                if (id != null) {
                    tentative.entries += MutationWithCriteria(
                        FocusBlockMutation.Delete(id, displacement.displaced.taskId),
                        displacement.criteria,
                    )
                }
            } else {
                tentative.replace(displacement.displaced, replacement)
                if (id != null) {
                    val durationChanged = replacement.range.endExclusive - replacement.range.start !=
                        displacement.displaced.range.endExclusive - displacement.displaced.range.start
                    val mutation = if (durationChanged) {
                        FocusBlockMutation.Resize(id, displacement.displaced.taskId, zoned(replacement.range))
                    } else {
                        FocusBlockMutation.Move(id, displacement.displaced.taskId, zoned(replacement.range))
                    }
                    tentative.entries += MutationWithCriteria(mutation, displacement.criteria)
                }
            }
        }

        if (subject == null) {
            val reservation = Reservation(
                taskId = task.id,
                range = winner.range,
                authority = ReservationAuthority.NEW,
                source = ReservationSource.Proposed(proposalSequence.next(task.id)),
                originalRange = null,
            )
            tentative.working += reservation
            tentative.entries += MutationWithCriteria(
                FocusBlockMutation.Create(FocusBlockDraft(task.id, zoned(winner.range))),
                criteria,
            )
        } else {
            if (winner.sourceReservation == subject && winner.range == subject.range) {
                tentative.keep(subject)
                return
            }
            val replacement = subject.copy(range = winner.range)
            tentative.replace(subject, replacement)
            val id = requireNotNull(subject.blockId)
            val durationChanged = winner.range.endExclusive - winner.range.start !=
                subject.range.endExclusive - subject.range.start
            val mutation = if (durationChanged) {
                FocusBlockMutation.Resize(id, subject.taskId, zoned(winner.range))
            } else {
                FocusBlockMutation.Move(id, subject.taskId, zoned(winner.range))
            }
            tentative.entries += MutationWithCriteria(mutation, criteria)
        }
    }

    // ------------------------------------------------------------------ shared views

    private fun freeIntervals(occupancy: List<Interval>): List<Interval> =
        subtractIntervals(availability, fixedOccupancy + occupancy)

    private fun Tentative.viewIgnoringDisplaceable(subject: Reservation?, planningTask: Task): List<Interval> =
        freeIntervals(
            working
                .filter { (it !in pendingSubjects || it !== subject) && !isDisplaceable(it, planningTask) }
                .map { it.range },
        )

    private fun isPlacementLegal(range: Interval, task: Task, after: Instant, cutoff: Instant?, overflow: Boolean): Boolean =
        range.start >= after && PlacementLegality.isDeadlineLegal(cutoff, overflow, range)

    private fun isDisplaceable(reservation: Reservation, planningTask: Task): Boolean =
        // Planner-created proposals carry the same public SOFT + UNPINNED authority
        // they will have once materialized; only FIXED reservations are immune.
        reservation.authority != ReservationAuthority.FIXED &&
            compareTaskServiceOrder(planningTask, tasksById[reservation.taskId] ?: return false) < 0

    private fun overlaps(first: Interval, second: Interval): Boolean =
        first.start < second.endExclusive && second.start < first.endExclusive

    private fun overflowRank(cutoff: Instant?, range: Interval): Int =
        if (cutoff != null && range.endExclusive > cutoff) 1 else 0

    /**
     * Explicit stable semantics of PLN-015 criterion 2: how late the Task's
     * demand would complete if the rest of it landed contiguously after this
     * candidate. Candidates covering the same demand at the same start compare
     * equal here, so the chunk-duration rank decides instead of fragmenting.
     */
    private fun latenessMillis(cutoff: Instant?, range: Interval, demand: Duration, fixedDuration: Duration?): Long {
        if (cutoff == null || range.endExclusive <= cutoff) return 0L
        val reference = if (fixedDuration != null && fixedDuration > demand) fixedDuration else demand
        return ((range.start + reference) - cutoff).inWholeMilliseconds.coerceAtLeast(0L)
    }

    private fun contextOccupancy(tentative: Tentative, arrangement: List<Displacement>): List<Reservation> {
        val displaced = arrangement.map { it.displaced }.toSet()
        return tentative.working.filter { it !in tentative.pendingSubjects && it !in displaced } +
            arrangement.mapNotNull { it.replacement }
    }

    /** PLN-015 v1: only exact adjacency between different-task blocks is a switch. */
    private fun contextSwitchDelta(taskId: dev.agenticscheduler.domain.id.TaskId, candidate: Interval, occupancy: List<Reservation>): Int =
        occupancy.count { reservation ->
            reservation.taskId != taskId &&
                (reservation.range.endExclusive == candidate.start || reservation.range.start == candidate.endExclusive)
        }

    private fun zoned(range: Interval) = ZonedTimeRange(range.start, range.endExclusive, zone)

    /** PLN-008 deterministic service order; never the enum declaration order. */
    private fun compareTaskServiceOrder(first: Task, second: Task): Int = compareValuesBy(
        first, second,
        { deadlineClass(it) },
        { it.deadline?.let { deadline -> TimeResolution.effectiveCutoff(deadline.deadline, zone) } ?: Instant.DISTANT_FUTURE },
        { priorityRank(it) },
        { it.id.value },
    )

    private fun deadlineClass(task: Task): Int = when (task.deadline?.policy) {
        DeadlinePolicy.HARD -> 0
        DeadlinePolicy.NORMAL -> 1
        null -> 2
    }

    private fun priorityRank(task: Task): Int = when (task.priority) {
        TaskPriority.HIGH -> 0
        TaskPriority.NORMAL -> 1
        TaskPriority.LOW -> 2
    }

    // ------------------------------------------------------------------ output canonicalization (R9)

    private fun finalize(state: PlanningState, issuesByTask: Map<dev.agenticscheduler.domain.id.TaskId, List<PlannerIssue>>): PlannerResult {
        PlanningInvariants.check(state, snapshot)
        // D6R-006 authoritative validation over the final accepted state: every
        // eligible HARD Task — planned or dependency-blocked — must be fully
        // covered by its effective cutoff, counting only before-cutoff coverage.
        val hardShortfalls = snapshot.tasks
            .filter { SnapshotNormalization.run { it.isAutomaticPlacementEligible() } && EffortTruth.hasHardDeadline(it) }
            .mapNotNull { task ->
                val remaining = requireNotNull(task.effort.remaining)
                val cutoff = EffortTruth.effectiveCutoff(task, zone)!!
                val satisfied = EffortTruth.coverageCompletedByCutoff(
                    state.reservations, task.id, snapshot.referenceNow, cutoff,
                )
                if (satisfied < remaining) PlannerIssue.HardDeadlineShortfall(task.id, remaining - satisfied) else null
            }
        val issues = (issuesByTask.values.flatten().filterNot { it is PlannerIssue.HardDeadlineShortfall } + hardShortfalls)
            .distinct()
            .sortedWith(IssueOrder.comparator)
            .toImmutableList()
        if (hardShortfalls.isNotEmpty()) {
            return PlannerResult.Infeasible(issues)
        }
        val net = netMutations(state).sortedWith(MutationOrder.comparator).toImmutableList()
        return PlannerResult.Success(
            mutations = net.map { it.mutation }.toImmutableList(),
            issues = issues,
            explanations = net
                .map { PlannerExplanation(it.mutation.taskId, it.mutation, it.criteria.toImmutableList()) }
                .toImmutableList(),
        )
    }

    /**
     * The planner output is the net mutation from the input Active State to the
     * final accepted reservation state, not the internal planning event log: a
     * block displaced twice yields exactly one Move, a displaced-then-resized
     * SOFT block exactly one Resize, and a deleted block exactly one Delete.
     * PlanBranch Apply rejects duplicate target IDs, so coalescing here is what
     * makes every Success actually applicable.
     */
    private fun netMutations(state: PlanningState): List<MutationWithCriteria> {
        // The last planning event that touched a block determines its final range,
        // so its decision criteria explain the net mutation.
        val criteriaByBlockId = HashMap<dev.agenticscheduler.domain.id.FocusBlockId, List<PlacementCriterion>>()
        val criteriaByCreation = HashMap<Triple<dev.agenticscheduler.domain.id.TaskId, Instant, Instant>, List<PlacementCriterion>>()
        state.entries.forEach { entry ->
            when (val mutation = entry.mutation) {
                is FocusBlockMutation.Move -> criteriaByBlockId[mutation.id] = entry.criteria
                is FocusBlockMutation.Resize -> criteriaByBlockId[mutation.id] = entry.criteria
                is FocusBlockMutation.Delete -> criteriaByBlockId[mutation.id] = entry.criteria
                is FocusBlockMutation.Create -> criteriaByCreation[Triple(mutation.draft.taskId, mutation.draft.time.start, mutation.draft.time.endExclusive)] = entry.criteria
            }
        }

        val finalByBlockId = state.reservations.mapNotNull { reservation ->
            reservation.blockId?.let { it to reservation }
        }.toMap()
        val canonical = listOf(PlacementCriterion.CANONICAL_IDENTITY)
        val net = mutableListOf<MutationWithCriteria>()

        snapshot.focusBlocks.forEach { block ->
            when (val final = finalByBlockId[block.id]) {
                null -> net += MutationWithCriteria(
                    FocusBlockMutation.Delete(block.id, block.taskId),
                    criteriaByBlockId[block.id] ?: canonical,
                )
                else -> {
                    val unchanged = final.range.start == block.time.start && final.range.endExclusive == block.time.endExclusive
                    if (!unchanged) {
                        val durationChanged = final.range.endExclusive - final.range.start !=
                            block.time.endExclusive - block.time.start
                        val time = ZonedTimeRange(final.range.start, final.range.endExclusive, zone)
                        val mutation = if (durationChanged) {
                            FocusBlockMutation.Resize(block.id, block.taskId, time)
                        } else {
                            FocusBlockMutation.Move(block.id, block.taskId, time)
                        }
                        net += MutationWithCriteria(mutation, criteriaByBlockId[block.id] ?: canonical)
                    }
                }
            }
        }

        state.reservations.filter { it.blockId == null }.forEach { reservation ->
            val key = Triple(reservation.taskId, reservation.range.start, reservation.range.endExclusive)
            net += MutationWithCriteria(
                FocusBlockMutation.Create(FocusBlockDraft(reservation.taskId, ZonedTimeRange(reservation.range.start, reservation.range.endExclusive, zone))),
                criteriaByCreation[key] ?: canonical,
            )
        }
        return net
    }
}

/** Deterministic per-run proposal identity source (PLN-005: no randomness in the planner). */
internal class ProposalSequence {
    private val counters = mutableMapOf<dev.agenticscheduler.domain.id.TaskId, Int>()

    fun next(taskId: dev.agenticscheduler.domain.id.TaskId): ProposalKey {
        val sequence = counters.getOrDefault(taskId, 0)
        counters[taskId] = sequence + 1
        return ProposalKey(taskId, sequence)
    }
}

/**
 * R9 structural invariant checker. Pre-existing real-world overlap between two
 * untouched input reservations remains a legal fact; any reservation whose range
 * differs from its original range must not overlap anything.
 */
internal object PlanningInvariants {
    fun check(state: PlanningState, snapshot: PlanningSnapshot) {
        val plannerTouched = state.reservations.filter { it.originalRange == null || it.originalRange != it.range }
        plannerTouched.forEach { created ->
            state.reservations.forEach { other ->
                if (other !== created) {
                    require(
                        created.range.endExclusive <= other.range.start || other.range.endExclusive <= created.range.start,
                    ) { "Planner created overlapping reservations: $created vs $other" }
                }
            }
        }
        val knownBlocks = snapshot.focusBlocks.map { it.id }.toSet()
        state.entries.forEach { entry ->
            when (val mutation = entry.mutation) {
                is FocusBlockMutation.Move -> require(mutation.id in knownBlocks) { "Move references unknown block: $mutation" }
                is FocusBlockMutation.Resize -> require(mutation.id in knownBlocks) { "Resize references unknown block: $mutation" }
                is FocusBlockMutation.Delete -> require(mutation.id in knownBlocks) { "Delete references unknown block: $mutation" }
                is FocusBlockMutation.Create -> Unit
            }
        }
        // D6R-003: planner-touched placements must respect the dependency truth of
        // the final accepted state. Untouched original reservations keep their
        // real-world positions even when they predate a satisfied dependency.
        val tasksById = snapshot.tasks.associateBy { it.id }
        plannerTouched.forEach { reservation ->
            val task = tasksById[reservation.taskId] ?: return@forEach
            val floor = EffortTruth.dependencyEarliestStart(
                task, snapshot.dependencies, tasksById, state.reservations, snapshot.referenceNow,
            )
            require(floor != null) { "Planner touched the reservation of a dependency-blocked Task: $reservation" }
            require(reservation.range.start >= floor) {
                "Planner placed $reservation before its dependency boundary $floor"
            }
        }
    }
}

internal object MutationOrder {
    val comparator = compareBy<MutationWithCriteria>(
        { when (it.mutation) {
            is FocusBlockMutation.Create -> 0
            is FocusBlockMutation.Move -> 1
            is FocusBlockMutation.Resize -> 2
            is FocusBlockMutation.Delete -> 3
        } },
        { it.mutation.taskId.value },
        { when (it.mutation) {
            is FocusBlockMutation.Create -> it.mutation.draft.time.start
            is FocusBlockMutation.Move -> it.mutation.time.start
            is FocusBlockMutation.Resize -> it.mutation.time.start
            is FocusBlockMutation.Delete -> Instant.DISTANT_PAST
        } },
        { when (it.mutation) {
            is FocusBlockMutation.Create -> ""
            is FocusBlockMutation.Move -> it.mutation.id.value
            is FocusBlockMutation.Resize -> it.mutation.id.value
            is FocusBlockMutation.Delete -> it.mutation.id.value
        } },
    )
}

internal object IssueOrder {
    private fun rank(issue: PlannerIssue): Int = when (issue) {
        is PlannerIssue.InvalidSnapshot -> 0
        is PlannerIssue.TimeResolutionFailure -> 1
        PlannerIssue.ProfileUnconfigured -> 2
        is PlannerIssue.UnknownRemainingEffort -> 3
        is PlannerIssue.DependencyBlocked -> 4
        is PlannerIssue.OverflowApprovalRequired -> 5
        is PlannerIssue.HardDeadlineShortfall -> 6
        is PlannerIssue.NoLegalAvailability -> 7
        is PlannerIssue.UnscheduledEffort -> 8
        is PlannerIssue.OverallocatedPlannedEffort -> 9
        is PlannerIssue.ImmovableConflict -> 10
    }

    private fun subject(issue: PlannerIssue): String = when (issue) {
        is PlannerIssue.InvalidSnapshot -> issue.reason
        is PlannerIssue.TimeResolutionFailure -> issue.localValue
        is PlannerIssue.UnknownRemainingEffort -> issue.taskId.value
        is PlannerIssue.DependencyBlocked -> "${issue.taskId.value}/${issue.prerequisiteTaskId.value}"
        is PlannerIssue.OverflowApprovalRequired -> issue.taskId.value
        is PlannerIssue.HardDeadlineShortfall -> "${issue.taskId.value}/${issue.remaining}"
        is PlannerIssue.NoLegalAvailability -> issue.taskId.value
        is PlannerIssue.UnscheduledEffort -> "${issue.taskId.value}/${issue.remaining}"
        is PlannerIssue.OverallocatedPlannedEffort -> issue.taskId.value
        is PlannerIssue.ImmovableConflict -> issue.focusBlockId.value
        PlannerIssue.ProfileUnconfigured -> ""
    }

    val comparator = compareBy<PlannerIssue>({ rank(it) }, { subject(it) })
}
