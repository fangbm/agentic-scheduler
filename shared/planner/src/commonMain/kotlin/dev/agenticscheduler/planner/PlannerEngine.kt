package dev.agenticscheduler.planner

import dev.agenticscheduler.domain.academic.CourseSessionState
import dev.agenticscheduler.domain.academic.ExamSchedule
import dev.agenticscheduler.domain.event.Event
import dev.agenticscheduler.domain.planning.AllDayEventPolicy
import dev.agenticscheduler.domain.planning.Deadline
import dev.agenticscheduler.domain.planning.DeadlinePolicy
import dev.agenticscheduler.domain.planning.Flexibility
import dev.agenticscheduler.domain.planning.OverflowPolicy
import dev.agenticscheduler.domain.planning.PinState
import dev.agenticscheduler.domain.planning.PlanningProfileConfiguration
import dev.agenticscheduler.domain.task.FocusBlock
import dev.agenticscheduler.domain.task.Task
import dev.agenticscheduler.domain.task.TaskPriority
import dev.agenticscheduler.domain.task.TaskStatus
import dev.agenticscheduler.domain.time.AllDayRange
import dev.agenticscheduler.domain.time.FloatingTimeRange
import dev.agenticscheduler.domain.time.TimePlacement
import dev.agenticscheduler.domain.time.ZonedTimeRange
import kotlin.math.abs
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlinx.collections.immutable.toImmutableList
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.offsetAt
import kotlinx.datetime.plus
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime

/** Pure deterministic implementation of D6 Full Replan and Local Reflow. */
class DeterministicPlanner {
    fun fullReplan(snapshot: PlanningSnapshot): PlannerResult {
        val config = snapshot.profile.configuration as? PlanningProfileConfiguration.Configured
            ?: return invalid(PlannerIssue.ProfileUnconfigured)
        validate(snapshot)?.let { return invalid(it) }
        val issues = mutableListOf<PlannerIssue>()
        val available = availability(snapshot, config, issues) ?: return invalid(*issues.toTypedArray())
        // PLN-014: only future blocks that start inside the requested horizon may be
        // proposed for mutation. Later blocks still contribute authoritative future
        // coverage, but remain untouched by this request.
        val mutable = snapshot.focusBlocks.filter { it.isMutableInside(snapshot) }
        val cleanup = snapshot.focusBlocks.filter { block ->
            val task = snapshot.tasks.firstOrNull { it.id == block.taskId }
            block.isMutableInside(snapshot) && block.flexibility == Flexibility.SOFT && block.pinState == PinState.UNPINNED &&
                (task?.status == TaskStatus.COMPLETED || task?.status == TaskStatus.CANCELLED || task?.effort?.remaining == Duration.ZERO)
        }
        // PLN-014: future mutable blocks are intentionally removed from fixed occupancy and
        // reintroduced only as retained/moved/resized proposals for their own task.
        val occupied = snapshot.focusBlocks.filterNot { it in cleanup || it in mutable }.map { Range(it.time.start, it.time.endExclusive) }.toMutableList()
        occupancy(snapshot, config, issues)?.let(occupied::addAll) ?: return invalid(*issues.toTypedArray())
        val baseFree = subtract(available, occupied)
        val tasks = snapshot.tasks.sortedWith(taskComparator(config.timeZone))
        val byId = tasks.associateBy { it.id }
        val dependencies = snapshot.dependencies.sortedBy { it.id.value }
        val mutations = mutableListOf<FocusBlockMutation>()
        val fixedContext = snapshot.focusBlocks.filterNot { it in cleanup || it in mutable }
            .map { it.taskId to Range(it.time.start, it.time.endExclusive) }
        val coverage = mutableMapOf<dev.agenticscheduler.domain.id.TaskId, Duration>()
        val completion = mutableMapOf<dev.agenticscheduler.domain.id.TaskId, Instant>()

        // Fixed future blocks, including those beyond the requested horizon, count as coverage.
        snapshot.focusBlocks.filter { it.time.start > snapshot.referenceNow && it !in cleanup && it !in mutable }.groupBy { it.taskId }.forEach { (id, blocks) ->
            coverage[id] = blocks.fold(Duration.ZERO) { total, block -> total + (block.time.endExclusive - block.time.start) }
        }
        cleanup.forEach { block ->
            mutations += FocusBlockMutation.Delete(block.id, block.taskId)
            coverage[block.taskId] = (coverage[block.taskId] ?: Duration.ZERO) - (block.time.endExclusive - block.time.start)
        }
        tasks.filter { (it.status == TaskStatus.OPEN || it.status == TaskStatus.IN_PROGRESS) && it.effort.remaining == null }
            .forEach { issues += PlannerIssue.UnknownRemainingEffort(it.id) }

        tasks.filter { it.eligible() }.forEach { task ->
            val remaining = task.effort.remaining
            if (remaining != null && (coverage[task.id] ?: Duration.ZERO) >= remaining) {
                completionFor(remaining, snapshot.focusBlocks.filter {
                    it.taskId == task.id && it.time.start > snapshot.referenceNow && it !in mutable && it !in cleanup
                }.map { Range(it.time.start, it.time.endExclusive) })?.let { completion[task.id] = it }
            }
        }

        val waiting = tasks.filter { it.eligible() }.toMutableList()
        val processed = mutableSetOf<dev.agenticscheduler.domain.id.TaskId>()
        val placed = mutableListOf<Pair<dev.agenticscheduler.domain.id.TaskId, Range>>()
        while (waiting.isNotEmpty()) {
            val ready = waiting.filter { task -> dependencies.filter { it.dependentTaskId == task.id }.all { dep ->
                byId[dep.prerequisiteTaskId]?.status == TaskStatus.COMPLETED || completion.containsKey(dep.prerequisiteTaskId)
            } }
            if (ready.isEmpty()) {
                waiting.forEach { task -> dependencies.firstOrNull { it.dependentTaskId == task.id }?.let { issues += PlannerIssue.DependencyBlocked(task.id, it.prerequisiteTaskId) } }
                break
            }
            val task = ready.first(); waiting.remove(task)
            val after = dependencies.filter { it.dependentTaskId == task.id }.mapNotNull { completion[it.prerequisiteTaskId] }.maxOrNull() ?: snapshot.referenceNow
            // PLN-014: unprocessed mutable blocks are proposals, never occupancy.
            // Task priority therefore decides who receives a contested slot.
            val free = subtract(baseFree, placed.map { it.second })
            var remaining = task.effort.remaining!! - (coverage[task.id] ?: Duration.ZERO)
            if (remaining < Duration.ZERO) { issues += PlannerIssue.OverallocatedPlannedEffort(task.id); remaining = Duration.ZERO }
            mutable.filter { it.taskId == task.id }.sortedBy { it.time.start }.forEach { block ->
                // FLEXIBLE coverage may have changed immediately before a SOFT block.
                // Recompute from the authoritative total before every authority decision.
                remaining = task.effort.remaining!! - (coverage[task.id] ?: Duration.ZERO)
                if (block.flexibility == Flexibility.FLEXIBLE) {
                    val proposed = preservedOrMoved(block, task, after, free, config, snapshot, fixedContext + placed)
                    if (proposed == null) {
                        // Existing state remains real occupancy; never delete/resize FLEXIBLE.
                        val original = Range(block.time.start, block.time.endExclusive)
                        if (placed.any { (_, proposedRange) -> intersect(original, proposedRange) != null }) {
                            // A higher-ranked proposal displaced this immutable-duration block and
                            // no legal relocation exists. Retaining it would create a new overlap.
                            return PlannerResult.Infeasible(listOf(PlannerIssue.NoLegalAvailability(task.id)).toImmutableList())
                        }
                        placed += task.id to original
                        // It remains occupancy for the rest of this run. Without consuming it,
                        // a later Create could overlap the retained real FocusBlock.
                        consume(free, original)
                        coverage[task.id] = (coverage[task.id] ?: Duration.ZERO) + (block.time.endExclusive - block.time.start)
                    } else {
                        if (proposed.start != block.time.start) mutations += FocusBlockMutation.Move(block.id, block.taskId, ZonedTimeRange(proposed.start, proposed.endExclusive, config.timeZone))
                        placed += task.id to proposed; consume(free, proposed)
                        coverage[task.id] = (coverage[task.id] ?: Duration.ZERO) + (proposed.endExclusive - proposed.start)
                    }
                } else if (remaining > Duration.ZERO) {
                    val proposed = preservedOrResized(block, task, remaining, after, free, config, snapshot, fixedContext + placed)
                    if (proposed == null) mutations += FocusBlockMutation.Delete(block.id, block.taskId)
                    else {
                        val unchanged = proposed.start == block.time.start && proposed.endExclusive == block.time.endExclusive
                        if (!unchanged) mutations += if (proposed.endExclusive - proposed.start == block.time.endExclusive - block.time.start) FocusBlockMutation.Move(block.id, block.taskId, ZonedTimeRange(proposed.start, proposed.endExclusive, config.timeZone)) else FocusBlockMutation.Resize(block.id, block.taskId, ZonedTimeRange(proposed.start, proposed.endExclusive, config.timeZone))
                        placed += task.id to proposed; consume(free, proposed)
                        val duration = proposed.endExclusive - proposed.start; coverage[task.id] = (coverage[task.id] ?: Duration.ZERO) + duration; remaining -= duration
                    }
                } else {
                    mutations += FocusBlockMutation.Delete(block.id, block.taskId)
                }
            }
            remaining = task.effort.remaining!! - (coverage[task.id] ?: Duration.ZERO)
            if (remaining < Duration.ZERO) { issues += PlannerIssue.OverallocatedPlannedEffort(task.id); remaining = Duration.ZERO }
            while (remaining > Duration.ZERO) {
                val candidate = candidate(task, remaining, after, free, config, snapshot, fixedContext + placed) ?: break
                mutations += FocusBlockMutation.Create(FocusBlockDraft(task.id, ZonedTimeRange(candidate.start, candidate.endExclusive, config.timeZone)))
                consume(free, candidate)
                remaining -= candidate.endExclusive - candidate.start
                placed += task.id to candidate
            }
            if (remaining > Duration.ZERO) {
                if (task.deadline?.policy == DeadlinePolicy.HARD) issues += PlannerIssue.HardDeadlineShortfall(task.id, remaining)
                else {
                    if (task.deadline?.overflowPolicy == OverflowPolicy.ASK && task.id !in snapshot.askOverflowAuthorizedTaskIds) issues += PlannerIssue.OverflowApprovalRequired(task.id)
                    issues += PlannerIssue.UnscheduledEffort(task.id, remaining)
                }
                if (mutations.none { it.taskId == task.id }) issues += PlannerIssue.NoLegalAvailability(task.id)
            }
            if (remaining == Duration.ZERO) completion[task.id] = plannedCompletion(task, placed.filter { it.first == task.id }.map { it.second }, snapshot, cleanup)
            processed += task.id
        }
        val ordered = issues.distinct().sortedBy { it.toString() }.toImmutableList()
        return if (ordered.any { it is PlannerIssue.HardDeadlineShortfall }) PlannerResult.Infeasible(ordered)
        else {
            val orderedMutations = mutations.sortedWith(mutationComparator()).toImmutableList()
            PlannerResult.Success(orderedMutations, ordered, orderedMutations.map(::explanationFor).toImmutableList())
        }
    }

    fun localReflow(snapshot: PlanningSnapshot, request: LocalReflowRequest): PlannerResult {
        val config = snapshot.profile.configuration as? PlanningProfileConfiguration.Configured ?: return invalid(PlannerIssue.ProfileUnconfigured)
        validate(snapshot)?.let { return invalid(it) }
        val affected = snapshot.focusBlocks.filter { it.id in request.affectedFocusBlockIds ||
            (it.time.start >= snapshot.referenceNow && request.disruptedRanges.any { range -> it.time.overlaps(range) }) }
            .sortedWith(compareBy<FocusBlock>({ it.time.start }, { it.id.value }))
        if (affected.any { it.time.start <= snapshot.referenceNow || !it.isMutable() }) {
            return PlannerResult.Infeasible(affected.filter { it.time.start <= snapshot.referenceNow || !it.isMutable() }.map { PlannerIssue.ImmovableConflict(it.id) }.toImmutableList())
        }
        val issues = mutableListOf<PlannerIssue>()
        val available = availability(snapshot, config, issues) ?: return invalid(*issues.toTypedArray())
        val occupied = snapshot.focusBlocks.filterNot { it in affected }.map { Range(it.time.start, it.time.endExclusive) }.toMutableList()
        occupancy(snapshot, config, issues)?.let(occupied::addAll) ?: return invalid(*issues.toTypedArray())
        val searchStart = maxOf(request.searchWindow.start, snapshot.referenceNow, snapshot.horizon.start)
        val searchEnd = minOf(request.searchWindow.endExclusive, snapshot.horizon.endExclusive)
        if (searchStart >= searchEnd) return invalid(PlannerIssue.InvalidSnapshot("reflow search window does not intersect the future horizon"))
        val search = Range(searchStart, searchEnd)
        val free = subtract(available.mapNotNull { intersect(it, search) }, occupied)
        val moves = mutableListOf<FocusBlockMutation>()
        val provisional = mutableMapOf<dev.agenticscheduler.domain.id.FocusBlockId, Range>()
        affected.forEach { block ->
            val duration = block.time.endExclusive - block.time.start
            val task = snapshot.tasks.firstOrNull { it.id == block.taskId }
            val dependencyCompletion = task?.let { currentTask ->
                dependencyEnd(currentTask, snapshot) { prerequisite ->
                    prerequisiteCompletion(prerequisite, snapshot, provisional)
                }
            }
            val placement = free.mapNotNull { interval ->
                if (interval.endExclusive - interval.start < duration) null else {
                    val latest = interval.endExclusive - duration
                    val start = maxOf(block.time.start.coerceIn(interval.start, latest), dependencyCompletion ?: interval.start)
                    if (start > latest) null else Range(start, start + duration).takeIf { candidate ->
                        task == null || candidate.isLegalFor(task, config, snapshot) { prerequisite ->
                            prerequisiteCompletion(prerequisite, snapshot, provisional)
                        }
                    }
                }
            }.sortedWith(compareBy<Range>({ abs((it.start - block.time.start).inWholeMilliseconds) }, { it.start })).firstOrNull()
                ?: return PlannerResult.Infeasible(listOf(PlannerIssue.NoLegalAvailability(block.taskId)).toImmutableList())
            moves += FocusBlockMutation.Move(block.id, block.taskId, ZonedTimeRange(placement.start, placement.endExclusive, config.timeZone))
            consume(free, placement)
            provisional[block.id] = placement
        }
        val orderedMoves = moves.toImmutableList()
        return PlannerResult.Success(orderedMoves, issues.sortedBy { it.toString() }.toImmutableList(), orderedMoves.map(::explanationFor).toImmutableList())
    }

    private fun validate(snapshot: PlanningSnapshot): PlannerIssue.InvalidSnapshot? = when {
        snapshot.referenceNow < snapshot.horizon.start || snapshot.referenceNow >= snapshot.horizon.endExclusive -> PlannerIssue.InvalidSnapshot("referenceNow must be inside horizon")
        snapshot.tasks.map { it.id }.distinct().size != snapshot.tasks.size -> PlannerIssue.InvalidSnapshot("duplicate TaskId")
        snapshot.dependencies.any { dep -> snapshot.tasks.none { it.id == dep.prerequisiteTaskId } || snapshot.tasks.none { it.id == dep.dependentTaskId } } -> PlannerIssue.InvalidSnapshot("dependency references unknown Task")
        hasCycle(snapshot) -> PlannerIssue.InvalidSnapshot("dependency cycle")
        else -> null
    }

    private fun hasCycle(snapshot: PlanningSnapshot): Boolean {
        val edges = snapshot.dependencies.groupBy { it.prerequisiteTaskId }.mapValues { (_, ds) -> ds.map { it.dependentTaskId } }
        val complete = mutableSetOf<dev.agenticscheduler.domain.id.TaskId>(); val active = mutableSetOf<dev.agenticscheduler.domain.id.TaskId>()
        fun visit(id: dev.agenticscheduler.domain.id.TaskId): Boolean {
            if (id in active) return true
            if (!complete.add(id)) return false
            active += id; val found = edges[id].orEmpty().any(::visit); active -= id
            return found
        }
        return snapshot.tasks.any { visit(it.id) }
    }

    private fun availability(snapshot: PlanningSnapshot, config: PlanningProfileConfiguration.Configured, issues: MutableList<PlannerIssue>): List<Range>? {
        val values = mutableListOf<Range>()
        var date = snapshot.horizon.start.toLocalDateTime(config.timeZone).date
        val finalDate = snapshot.horizon.endExclusive.toLocalDateTime(config.timeZone).date.plus(1, DateTimeUnit.DAY)
        while (date <= finalDate) {
            config.weeklyAvailability.filter { it.dayOfWeek == date.dayOfWeek }.forEach { window ->
                val start = LocalDateTime(date, window.start).safeInstant(config.timeZone)
                val end = LocalDateTime(date, window.endExclusive).safeInstant(config.timeZone)
                if (start == null || end == null) issues += PlannerIssue.TimeResolutionFailure("$date ${window.start}-${window.endExclusive}")
                else intersect(Range(start, end), Range(maxOf(snapshot.horizon.start, snapshot.referenceNow), snapshot.horizon.endExclusive))?.let(values::add)
            }
            date = date.plus(1, DateTimeUnit.DAY)
        }
        return if (issues.any { it is PlannerIssue.TimeResolutionFailure }) null else values.sortedBy { it.start }
    }

    private fun occupancy(snapshot: PlanningSnapshot, config: PlanningProfileConfiguration.Configured, issues: MutableList<PlannerIssue>): List<Range>? {
        val values = mutableListOf<Range>()
        snapshot.events.forEach { event -> event.time.occupancy(config.timeZone, config.allDayEventPolicy, issues)?.let(values::addAll) }
        snapshot.courseSessions.forEach { session -> (session.state as? CourseSessionState.Scheduled)?.time?.let { values += Range(it.start, it.endExclusive) } }
        snapshot.exams.forEach { exam -> (exam.schedule as? ExamSchedule.Exact)?.time?.let { values += Range(it.start, it.endExclusive) } }
        snapshot.constraints.filterIsInstance<PlanningConstraint.UnavailableWindow>().forEach { values += Range(it.time.start, it.time.endExclusive) }
        return if (issues.any { it is PlannerIssue.TimeResolutionFailure }) null else values
    }

    private fun candidate(
        task: Task,
        remaining: Duration,
        after: Instant,
        free: List<Range>,
        config: PlanningProfileConfiguration.Configured,
        snapshot: PlanningSnapshot,
        context: List<Pair<dev.agenticscheduler.domain.id.TaskId, Range>>,
    ): Range? {
        val cutoff = task.deadline?.deadline?.effective(config.timeZone)
        val overflow = task.deadline?.let { it.policy != DeadlinePolicy.HARD && when (it.overflowPolicy) {
            OverflowPolicy.ALLOW -> true; OverflowPolicy.ASK -> task.id in snapshot.askOverflowAuthorizedTaskIds; OverflowPolicy.NEVER -> false
        } } ?: true
        val candidates = free.mapNotNull { intersect(it, Range(after, snapshot.horizon.endExclusive)) }.flatMap { interval ->
            val starts = buildList {
                add(interval.start)
                cutoff?.takeIf { it >= interval.start && it < interval.endExclusive }?.let(::add)
                context.forEach { (_, range) ->
                    range.endExclusive.takeIf { it in interval.start..interval.endExclusive }?.let(::add)
                }
            }.distinct()
            starts.flatMap { start ->
                val segment = Range(start, interval.endExclusive)
                val before = cutoff?.let { intersect(segment, Range(Instant.DISTANT_PAST, it)) }
                val overflowRange = if (overflow && cutoff != null && cutoff < segment.endExclusive) {
                    intersect(segment, Range(maxOf(cutoff, segment.start), segment.endExclusive))
                } else null
                val choices = if (cutoff == null) listOf(segment) else listOfNotNull(before, overflowRange)
                choices.mapNotNull { selected ->
                    chunk(remaining, selected.endExclusive - selected.start, config)?.let { duration -> Range(selected.start, selected.start + duration) }
                }
            }
        }
        return candidates.sortedWith(compareBy<Range>(
            { cutoff != null && it.endExclusive > cutoff },
            { cutoff?.let { boundary -> if (it.endExclusive > boundary) (it.endExclusive - boundary).inWholeMilliseconds else 0L } ?: 0L },
            { contextSwitchDelta(task.id, it, context) },
            { abs(((it.endExclusive - it.start) - config.preferredFocusBlock).inWholeMilliseconds) },
            { -(it.endExclusive - it.start).inWholeMilliseconds },
            { it.start }, { it.endExclusive },
        )).firstOrNull()
    }

    private fun preservedOrMoved(
        block: FocusBlock,
        task: Task,
        after: Instant,
        free: List<Range>,
        config: PlanningProfileConfiguration.Configured,
        snapshot: PlanningSnapshot,
        context: List<Pair<dev.agenticscheduler.domain.id.TaskId, Range>>,
    ): Range? {
        val original = Range(block.time.start, block.time.endExclusive)
        if (original.start >= after && free.any { it.start <= original.start && it.endExclusive >= original.endExclusive } && original.isDeadlineLegalFor(task, config, snapshot)) return original
        val duration = original.endExclusive - original.start
        return free.mapNotNull { interval ->
            if (interval.endExclusive - interval.start < duration) null else {
                val latest = interval.endExclusive - duration
                val start = maxOf(after, block.time.start.coerceIn(interval.start, latest))
                if (start <= latest) Range(start, start + duration).takeIf { it.isDeadlineLegalFor(task, config, snapshot) } else null
            }
        }.sortedWith(compareBy<Range>(
            { abs((it.start - block.time.start).inWholeMilliseconds) },
            { contextSwitchDelta(task.id, it, context) },
            { it.start }, { it.endExclusive },
        )).firstOrNull()
    }

    private fun preservedOrResized(
        block: FocusBlock,
        task: Task,
        remaining: Duration,
        after: Instant,
        free: List<Range>,
        config: PlanningProfileConfiguration.Configured,
        snapshot: PlanningSnapshot,
        context: List<Pair<dev.agenticscheduler.domain.id.TaskId, Range>>,
    ): Range? {
        val original = Range(block.time.start, block.time.endExclusive)
        if (original.start >= after && free.any { it.start <= original.start && it.endExclusive >= original.endExclusive } && original.endExclusive - original.start <= remaining && original.isDeadlineLegalFor(task, config, snapshot)) return original
        return candidate(task, remaining, after, free, config, snapshot, context)
    }

    private fun plannedCompletion(task: Task, proposed: List<Range>, snapshot: PlanningSnapshot, cleanup: List<FocusBlock>): Instant {
        val target = requireNotNull(task.effort.remaining)
        val retained = snapshot.focusBlocks.filter { it.taskId == task.id && it.time.start > snapshot.referenceNow && it !in cleanup && !it.isMutableInside(snapshot) }
            .map { Range(it.time.start, it.time.endExclusive) }
        var covered = Duration.ZERO
        (retained + proposed).sortedBy { it.start }.forEach { range ->
            val duration = range.endExclusive - range.start
            if (covered + duration >= target) return range.start + (target - covered)
            covered += duration
        }
        error("planned completion requested without complete coverage")
    }

    private fun chunk(remaining: Duration, capacity: Duration, config: PlanningProfileConfiguration.Configured): Duration? {
        val upper = minOf(remaining, capacity, config.maximumFocusBlock)
        if (remaining < config.minimumFocusBlock) return if (upper == remaining) remaining else null
        if (upper < config.minimumFocusBlock) return null
        return listOf(upper, minOf(config.preferredFocusBlock, upper), remaining, remaining - config.minimumFocusBlock, config.minimumFocusBlock)
            .distinct().filter { it >= config.minimumFocusBlock && it <= upper }
            .filter { remaining - it == Duration.ZERO || remaining - it >= config.minimumFocusBlock }
            .sortedWith(compareBy<Duration>({ abs((it - config.preferredFocusBlock).inWholeMilliseconds) }, { -it.inWholeMilliseconds })).firstOrNull()
    }

    private fun Task.eligible(): Boolean {
        val remaining = effort.remaining
        return (status == TaskStatus.OPEN || status == TaskStatus.IN_PROGRESS) && remaining != null && remaining > Duration.ZERO
    }
    private fun Range.isLegalFor(
        task: Task,
        config: PlanningProfileConfiguration.Configured,
        snapshot: PlanningSnapshot,
        completion: (Task) -> Instant?,
    ): Boolean {
        if (snapshot.dependencies.filter { it.dependentTaskId == task.id }.any { dependency ->
                val prerequisite = snapshot.tasks.first { it.id == dependency.prerequisiteTaskId }
                prerequisite.status != TaskStatus.COMPLETED && completion(prerequisite) == null
            }) return false
        val dependencyEnd = dependencyEnd(task, snapshot, completion)
        if (dependencyEnd != null && start < dependencyEnd) return false
        return isDeadlineLegalFor(task, config, snapshot)
    }
    private fun dependencyEnd(
        task: Task,
        snapshot: PlanningSnapshot,
        completion: (Task) -> Instant?,
    ): Instant? = snapshot.dependencies.filter { it.dependentTaskId == task.id }.mapNotNull { dependency ->
            val prerequisite = snapshot.tasks.first { it.id == dependency.prerequisiteTaskId }
            if (prerequisite.status == TaskStatus.COMPLETED) snapshot.referenceNow else completion(prerequisite)
        }.maxOrNull()
    private fun Range.isDeadlineLegalFor(task: Task, config: PlanningProfileConfiguration.Configured, snapshot: PlanningSnapshot): Boolean {
        val deadline = task.deadline ?: return true
        val cutoff = deadline.deadline.effective(config.timeZone)
        if (endExclusive <= cutoff) return true
        return deadline.policy != DeadlinePolicy.HARD && when (deadline.overflowPolicy) {
            OverflowPolicy.ALLOW -> true
            OverflowPolicy.ASK -> task.id in snapshot.askOverflowAuthorizedTaskIds
            OverflowPolicy.NEVER -> false
        }
    }
    private fun prerequisiteCompletion(
        task: Task,
        snapshot: PlanningSnapshot,
        provisional: Map<dev.agenticscheduler.domain.id.FocusBlockId, Range> = emptyMap(),
    ): Instant? {
        val required = task.effort.remaining ?: return null
        if (required <= Duration.ZERO || task.status == TaskStatus.CANCELLED || task.status == TaskStatus.COMPLETED) return null
        return completionFor(required, snapshot.focusBlocks.filter { it.taskId == task.id && it.time.start > snapshot.referenceNow }.map { block ->
            provisional[block.id] ?: Range(block.time.start, block.time.endExclusive)
        })
    }
    private fun FocusBlock.isMutable() = pinState == PinState.UNPINNED && flexibility != Flexibility.HARD
    private fun FocusBlock.isMutableInside(snapshot: PlanningSnapshot) =
        time.start > snapshot.referenceNow && time.start < snapshot.horizon.endExclusive && isMutable()
    private fun completionFor(required: Duration, ranges: List<Range>): Instant? {
        var covered = Duration.ZERO
        ranges.sortedBy { it.start }.forEach { range ->
            val duration = range.endExclusive - range.start
            if (covered + duration >= required) return range.start + (required - covered)
            covered += duration
        }
        return null
    }
    /** PLN-015 v1: only exact adjacency between different task blocks is a switch. */
    private fun contextSwitchDelta(
        taskId: dev.agenticscheduler.domain.id.TaskId,
        candidate: Range,
        context: List<Pair<dev.agenticscheduler.domain.id.TaskId, Range>>,
    ): Int = context.count { (otherTaskId, otherRange) ->
            otherTaskId != taskId && (otherRange.endExclusive == candidate.start || otherRange.start == candidate.endExclusive)
        }
    private fun explanationFor(mutation: FocusBlockMutation): PlannerExplanation {
        val criteria = when (mutation) {
            is FocusBlockMutation.Delete -> listOf(PlacementCriterion.PRESERVED_EXISTING_PLACEMENT, PlacementCriterion.CANONICAL_IDENTITY)
            is FocusBlockMutation.Move -> listOf(PlacementCriterion.MINIMAL_MOVEMENT, PlacementCriterion.MINIMAL_CONTEXT_SWITCHES, PlacementCriterion.EARLIER_START, PlacementCriterion.CANONICAL_IDENTITY)
            is FocusBlockMutation.Resize -> listOf(PlacementCriterion.PRESERVED_EXISTING_PLACEMENT, PlacementCriterion.CHUNK_DURATION, PlacementCriterion.CANONICAL_IDENTITY)
            is FocusBlockMutation.Create -> listOf(PlacementCriterion.DEADLINE_LEGALITY, PlacementCriterion.MINIMAL_CONTEXT_SWITCHES, PlacementCriterion.CHUNK_DURATION, PlacementCriterion.EARLIER_START, PlacementCriterion.EARLIER_END, PlacementCriterion.CANONICAL_IDENTITY)
        }
        return PlannerExplanation(mutation.taskId, mutation, criteria.toImmutableList())
    }
    private fun Deadline.effective(zone: TimeZone): Instant = when (this) { is Deadline.Exact -> at; is Deadline.DateOnly -> date.plus(1, DateTimeUnit.DAY).atStartOfDayIn(zone) }
    private fun TimePlacement.occupancy(zone: TimeZone, policy: AllDayEventPolicy, issues: MutableList<PlannerIssue>): List<Range>? = when (this) {
        is ZonedTimeRange -> listOf(Range(start, endExclusive))
        is FloatingTimeRange -> { val start = start.safeInstant(zone); val end = endExclusive.safeInstant(zone); if (start == null || end == null) { issues += PlannerIssue.TimeResolutionFailure("$this"); null } else listOf(Range(start, end)) }
        is AllDayRange -> if (policy == AllDayEventPolicy.NON_BLOCKING) emptyList() else listOf(Range(startDate.atStartOfDayIn(zone), endDateExclusive.atStartOfDayIn(zone)))
        else -> emptyList()
    }
    private fun LocalDateTime.safeInstant(zone: TimeZone): Instant? {
        val selected = toInstant(zone); if (selected.toLocalDateTime(zone) != this) return null
        val offset = zone.offsetAt(selected).totalSeconds
        val alternatives = listOf(zone.offsetAt(selected - 48.hours).totalSeconds, zone.offsetAt(selected + 48.hours).totalSeconds)
        return if (alternatives.any { alternate -> alternate != offset && (selected + (offset - alternate).seconds).toLocalDateTime(zone) == this }) null else selected
    }
    private fun taskComparator(zone: TimeZone) = compareBy<Task>({ when (it.deadline?.policy) { DeadlinePolicy.HARD -> 0; DeadlinePolicy.NORMAL -> 1; null -> 2 } }, { it.deadline?.deadline?.effective(zone) ?: Instant.DISTANT_FUTURE }, { when (it.priority) { TaskPriority.HIGH -> 0; TaskPriority.NORMAL -> 1; TaskPriority.LOW -> 2 } }, { it.id.value })
    private fun mutationComparator() = compareBy<FocusBlockMutation>({ when (it) { is FocusBlockMutation.Create -> 0; is FocusBlockMutation.Move -> 1; is FocusBlockMutation.Resize -> 2; is FocusBlockMutation.Delete -> 3 } }, { it.taskId.value }, { when (it) { is FocusBlockMutation.Create -> it.draft.time.start; is FocusBlockMutation.Move -> it.time.start; is FocusBlockMutation.Resize -> it.time.start; is FocusBlockMutation.Delete -> Instant.DISTANT_PAST } }, { when (it) { is FocusBlockMutation.Create -> ""; is FocusBlockMutation.Move -> it.id.value; is FocusBlockMutation.Resize -> it.id.value; is FocusBlockMutation.Delete -> it.id.value } })
    private fun invalid(vararg issues: PlannerIssue) = PlannerResult.InvalidInput(issues.toList().toImmutableList())
}

private data class Range(val start: Instant, val endExclusive: Instant) { init { require(start < endExclusive) } }
private fun intersect(first: Range, second: Range): Range? = if (first.start < second.endExclusive && second.start < first.endExclusive) Range(maxOf(first.start, second.start), minOf(first.endExclusive, second.endExclusive)) else null
private fun subtract(source: List<Range>, occupied: List<Range>): MutableList<Range> {
    var free = source.sortedBy { it.start }
    occupied.sortedBy { it.start }.forEach { cut ->
        free = free.flatMap { range -> when {
            cut.endExclusive <= range.start || cut.start >= range.endExclusive -> listOf(range)
            cut.start <= range.start && cut.endExclusive >= range.endExclusive -> emptyList()
            cut.start <= range.start -> listOf(Range(cut.endExclusive, range.endExclusive))
            cut.endExclusive >= range.endExclusive -> listOf(Range(range.start, cut.start))
            else -> listOf(Range(range.start, cut.start), Range(cut.endExclusive, range.endExclusive))
        } }
    }
    return free.toMutableList()
}
private fun consume(free: MutableList<Range>, used: Range) { val next = subtract(free, listOf(used)); free.clear(); free.addAll(next) }
