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
    private val futureBounds = Interval(
        maxOf(snapshot.horizon.start, snapshot.referenceNow),
        snapshot.horizon.endExclusive,
    )
    private lateinit var tasksById: Map<dev.agenticscheduler.domain.id.TaskId, Task>
    private lateinit var availability: List<Interval>
    private lateinit var fixedOccupancy: List<Interval>

    fun run(): PlannerResult {
        validateSnapshot()?.let { return PlannerResult.InvalidInput(listOf(it).toImmutableList()) }
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
        val waiting = normalized.eligibleTasks.sortedWith(::compareTaskServiceOrder).toMutableList()
        var hardShortfall = false

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
                        state = state.plus(
                            TaskPlanDelta(
                                task.id, emptyList(), emptyList(), emptyList(),
                                listOf(PlannerIssue.DependencyBlocked(task.id, blocker.id)),
                            ),
                        )
                    }
                }
                break
            }
            waiting.remove(selected)
            val outcome = planTask(state, selected)
            state = state.plus(outcome.delta)
            if (outcome.hardShortfall) hardShortfall = true
        }

        return finalize(state, hardShortfall)
    }

    // ------------------------------------------------------------------ snapshot validation

    private fun validateSnapshot(): PlannerIssue.InvalidSnapshot? = when {
        snapshot.referenceNow < snapshot.horizon.start || snapshot.referenceNow >= snapshot.horizon.endExclusive ->
            PlannerIssue.InvalidSnapshot("referenceNow must be inside the horizon")
        snapshot.tasks.map { it.id }.distinct().size != snapshot.tasks.size ->
            PlannerIssue.InvalidSnapshot("duplicate TaskId")
        snapshot.dependencies.any { dependency ->
            snapshot.tasks.none { it.id == dependency.prerequisiteTaskId } ||
                snapshot.tasks.none { it.id == dependency.dependentTaskId }
        } -> PlannerIssue.InvalidSnapshot("dependency references unknown Task")
        hasDependencyCycle() -> PlannerIssue.InvalidSnapshot("dependency cycle")
        else -> null
    }

    private fun hasDependencyCycle(): Boolean {
        val edges = snapshot.dependencies.groupBy({ it.prerequisiteTaskId }, { it.dependentTaskId })
        val complete = mutableSetOf<dev.agenticscheduler.domain.id.TaskId>()
        val active = mutableSetOf<dev.agenticscheduler.domain.id.TaskId>()
        fun visit(id: dev.agenticscheduler.domain.id.TaskId): Boolean {
            if (id in active) return true
            if (!complete.add(id)) return false
            active += id
            val found = edges[id].orEmpty().any(::visit)
            active -= id
            return found
        }
        return snapshot.tasks.any { visit(it.id) }
    }

    // ------------------------------------------------------------------ task transaction

    private data class PlanOutcome(val delta: TaskPlanDelta, val hardShortfall: Boolean)

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

        fun delta(taskId: dev.agenticscheduler.domain.id.TaskId): TaskPlanDelta = TaskPlanDelta(
            taskId = taskId,
            removedReservations = base.reservations.filter { it !in working },
            addedReservations = working.filter { it !in base.reservations },
            entries = entries.toList(),
            issues = issues.toList(),
        )
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

        // D6R-006: HARD satisfaction counts only coverage completed by the cutoff.
        var hardShortfall = false
        if (task.deadline?.policy == DeadlinePolicy.HARD) {
            val satisfied = EffortTruth.coverageCompletedByCutoff(
                tentative.working, task.id, snapshot.referenceNow, cutoff!!,
            )
            if (satisfied < remaining) {
                if (tentative.issues.none { it is PlannerIssue.HardDeadlineShortfall }) {
                    tentative.issues += PlannerIssue.HardDeadlineShortfall(task.id, remaining - satisfied)
                }
                hardShortfall = true
            }
        }

        return PlanOutcome(tentative.delta(task.id), hardShortfall)
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
                val durationRank = if (fixedDuration != null) {
                    0
                } else {
                    rankOf(demand, duration, duration)
                }
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
                    durationRank = durationRank,
                )
            }
        }

        // No candidate may start at or beyond the horizon end.
        if (after >= futureBounds.endExclusive) return candidates
        val futureWindow = Interval(after, futureBounds.endExclusive)
        val cleanIntervals = tentative.viewFor(original).mapNotNull { clipInterval(it, futureWindow) }
        val displacementIntervals = tentative.viewIgnoringDisplaceable(original, task).mapNotNull { clipInterval(it, futureWindow) }

        // Clean candidates on genuinely free space (criterion 3, rank 1).
        cleanIntervals.forEach { interval ->
            collectIntervalCandidates(
                interval, task, demand, fixedDuration, original, after, cutoff, overflow,
                tentative, preserveRank = 1, requireDisplacement = false, output = candidates,
            )
        }
        // Displacement candidates on space held by lower-authority reservations (rank 2).
        displacementIntervals.forEach { interval ->
            collectIntervalCandidates(
                interval, task, demand, fixedDuration, original, after, cutoff, overflow,
                tentative, preserveRank = 2, requireDisplacement = true, output = candidates,
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
        output: MutableList<PlacementCandidate>,
    ) {
        val durations = fixedDuration?.let { listOf(it) }
            ?: CandidateGeneration.durationCandidates(demand, interval.endExclusive - interval.start, config)
        if (durations.isEmpty()) return
        val baseStarts = CandidateGeneration.structuralStarts(
            CandidateGeneration.StructureInputs(
                interval = interval,
                after = after,
                cutoff = cutoff,
                originalStart = original?.range?.start,
                tentativeBoundaries = emptyList(),
            ),
        )
        durations.forEach { duration ->
            val starts = baseStarts + CandidateGeneration.clampedOriginalStarts(
                interval, cutoff, original?.range?.start, duration,
            )
            starts.distinct().forEach { start ->
                val segment = Interval(start, interval.endExclusive)
                if (duration > segment.endExclusive - segment.start) return@forEach
                val range = Interval(start, start + duration)
                if (!PlacementLegality.isDeadlineLegal(cutoff, overflow, range)) return@forEach

                val displaced = tentative.working.filter {
                    it !in tentative.pendingSubjects && isDisplaceable(it, task) && overlaps(it.range, range)
                }
                if (requireDisplacement && displaced.isEmpty()) return@forEach

                val arrangement = arrangeDisplacements(tentative, task, range, displaced) ?: return@forEach
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
                    durationRank = if (fixedDuration != null) 0 else rankOf(demand, duration, segment.endExclusive - segment.start),
                )
            }
        }
    }

    private fun rankOf(demand: Duration, duration: Duration, capacity: Duration): Int {
        val index = CandidateGeneration.durationCandidates(demand, capacity, config).indexOf(duration)
        return if (index >= 0) index else Int.MAX_VALUE
    }

    /**
     * Builds the finite displacement arrangement required by a candidate range.
     * Every displaced FLEXIBLE reservation must receive a proven same-ID,
     * same-duration relocation inside this transaction; displaced SOFT
     * reservations may fall back to authorized removal (D6R-004).
     */
    private fun arrangeDisplacements(
        tentative: Tentative,
        planningTask: Task,
        range: Interval,
        displaced: List<Reservation>,
    ): List<Displacement>? {
        if (displaced.isEmpty()) return emptyList()
        val arrangement = mutableListOf<Displacement>()
        val taken = mutableListOf(range)
        val displacedSet = displaced.toSet()
        displaced.sortedWith(compareBy({ it.range.start }, { it.identityText() })).forEach { reservation ->
            val duration = reservation.range.endExclusive - reservation.range.start
            val relocation = findRelocation(tentative, reservation, duration, displacedSet, taken)
            if (relocation != null) {
                arrangement += Displacement(reservation, relocation)
                taken += relocation.range
            } else if (reservation.authority == ReservationAuthority.SOFT) {
                arrangement += Displacement(reservation, null)
            } else {
                return null
            }
        }
        return arrangement
    }

    private fun findRelocation(
        tentative: Tentative,
        reservation: Reservation,
        duration: Duration,
        displacedSet: Set<Reservation>,
        taken: List<Interval>,
    ): Reservation? {
        val displacedTask = tasksById[reservation.taskId] ?: return null
        val cutoff = EffortTruth.effectiveCutoff(displacedTask, zone)
        val overflow = EffortTruth.overflowAuthorized(displacedTask, askAuthorized)
        val after = EffortTruth.dependencyEarliestStart(
            displacedTask, snapshot.dependencies, tasksById, tentative.working, snapshot.referenceNow,
        ) ?: snapshot.referenceNow
        val cuts = fixedOccupancy +
            tentative.working.filter { it !in displacedSet }.map { it.range } +
            taken
        val space = subtractIntervals(availability, cuts).mapNotNull { clipInterval(it, futureBounds) }
        return space.flatMap { interval ->
            if (interval.endExclusive - interval.start < duration) {
                emptyList()
            } else {
                val latest = interval.endExclusive - duration
                val clamped = reservation.range.start.coerceIn(interval.start, latest)
                val start = maxOf(clamped, after)
                if (start > latest) {
                    emptyList()
                } else {
                    listOf(Interval(start, start + duration))
                }
            }
        }
            .filter { PlacementLegality.isDeadlineLegal(cutoff, overflow, it) }
            .sortedWith(
                compareBy(
                    { kotlin.math.abs((it.start - reservation.range.start).inWholeMilliseconds) },
                    { it.start },
                ),
            )
            .firstOrNull()
            ?.let { relocation -> reservation.copy(range = relocation) }
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
            val id = requireNotNull(displacement.displaced.blockId)
            val replacement = displacement.replacement
            if (replacement == null) {
                tentative.replace(displacement.displaced, null)
                tentative.entries += MutationWithCriteria(
                    FocusBlockMutation.Delete(id, displacement.displaced.taskId),
                    listOf(PlacementCriterion.CANONICAL_IDENTITY),
                )
            } else {
                tentative.replace(displacement.displaced, replacement)
                val durationChanged = replacement.range.endExclusive - replacement.range.start !=
                    displacement.displaced.range.endExclusive - displacement.displaced.range.start
                val mutation = if (durationChanged) {
                    FocusBlockMutation.Resize(id, displacement.displaced.taskId, zoned(replacement.range))
                } else {
                    FocusBlockMutation.Move(id, displacement.displaced.taskId, zoned(replacement.range))
                }
                tentative.entries += MutationWithCriteria(
                    mutation,
                    listOf(PlacementCriterion.MINIMAL_MOVEMENT, PlacementCriterion.CANONICAL_IDENTITY),
                )
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
        (reservation.authority == ReservationAuthority.FLEXIBLE || reservation.authority == ReservationAuthority.SOFT) &&
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

    private fun finalize(state: PlanningState, hardShortfall: Boolean): PlannerResult {
        PlanningInvariants.check(state, snapshot)
        val issues = state.issues
            .distinct()
            .sortedWith(IssueOrder.comparator)
            .toImmutableList()
        if (hardShortfall || issues.any { it is PlannerIssue.HardDeadlineShortfall }) {
            return PlannerResult.Infeasible(issues)
        }
        val ordered = state.entries.sortedWith(MutationOrder.comparator).toImmutableList()
        return PlannerResult.Success(
            mutations = ordered.map { it.mutation }.toImmutableList(),
            issues = issues,
            explanations = ordered
                .map { PlannerExplanation(it.mutation.taskId, it.mutation, it.criteria.toImmutableList()) }
                .toImmutableList(),
        )
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
