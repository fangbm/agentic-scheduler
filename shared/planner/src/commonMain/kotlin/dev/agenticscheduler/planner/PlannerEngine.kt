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
        val mutable = snapshot.focusBlocks.filter { it.time.start >= snapshot.referenceNow && it.isMutable() }
        val cleanup = snapshot.focusBlocks.filter { block ->
            val task = snapshot.tasks.firstOrNull { it.id == block.taskId }
            block.time.start >= snapshot.referenceNow && block.flexibility == Flexibility.SOFT && block.pinState == PinState.UNPINNED &&
                (task?.status == TaskStatus.COMPLETED || task?.status == TaskStatus.CANCELLED || task?.effort?.remaining == Duration.ZERO)
        }
        // Retained blocks are occupancy. A proposal must never overlap a block it has not moved/deleted.
        val occupied = snapshot.focusBlocks.filterNot { it in cleanup }.map { Range(it.time.start, it.time.endExclusive) }.toMutableList()
        occupancy(snapshot, config, issues)?.let(occupied::addAll) ?: return invalid(*issues.toTypedArray())
        val free = subtract(available, occupied)
        val tasks = snapshot.tasks.sortedWith(taskComparator(config.timeZone))
        val byId = tasks.associateBy { it.id }
        val dependencies = snapshot.dependencies.sortedBy { it.id.value }
        val mutations = mutableListOf<FocusBlockMutation>()
        val coverage = mutableMapOf<dev.agenticscheduler.domain.id.TaskId, Duration>()
        val completion = mutableMapOf<dev.agenticscheduler.domain.id.TaskId, Instant>()

        // Retained future mutable blocks are real planned coverage, unless cleanup is authorized.
        snapshot.focusBlocks.filter { it.time.start >= snapshot.referenceNow && it !in cleanup }.groupBy { it.taskId }.forEach { (id, blocks) ->
            coverage[id] = blocks.fold(Duration.ZERO) { total, block -> total + (block.time.endExclusive - block.time.start) }
            completion[id] = blocks.maxOf { it.time.endExclusive }
        }
        cleanup.forEach { block ->
            mutations += FocusBlockMutation.Delete(block.id, block.taskId)
            coverage[block.taskId] = (coverage[block.taskId] ?: Duration.ZERO) - (block.time.endExclusive - block.time.start)
        }
        tasks.filter { (it.status == TaskStatus.OPEN || it.status == TaskStatus.IN_PROGRESS) && it.effort.remaining == null }
            .forEach { issues += PlannerIssue.UnknownRemainingEffort(it.id) }

        val waiting = tasks.filter { it.eligible() }.toMutableList()
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
            var remaining = task.effort.remaining!! - (coverage[task.id] ?: Duration.ZERO)
            if (remaining < Duration.ZERO) { issues += PlannerIssue.OverallocatedPlannedEffort(task.id); remaining = Duration.ZERO }
            while (remaining > Duration.ZERO) {
                val candidate = candidate(task, remaining, after, free, config, snapshot) ?: break
                mutations += FocusBlockMutation.Create(FocusBlockDraft(task.id, ZonedTimeRange(candidate.start, candidate.endExclusive, config.timeZone)))
                consume(free, candidate)
                remaining -= candidate.endExclusive - candidate.start
                completion[task.id] = candidate.endExclusive
            }
            if (remaining > Duration.ZERO) {
                if (task.deadline?.policy == DeadlinePolicy.HARD) issues += PlannerIssue.HardDeadlineShortfall(task.id, remaining)
                else {
                    if (task.deadline?.overflowPolicy == OverflowPolicy.ASK && task.id !in snapshot.askOverflowAuthorizedTaskIds) issues += PlannerIssue.OverflowApprovalRequired(task.id)
                    issues += PlannerIssue.UnscheduledEffort(task.id, remaining)
                }
                if (mutations.none { it.taskId == task.id }) issues += PlannerIssue.NoLegalAvailability(task.id)
            }
            if (remaining == Duration.ZERO && !completion.containsKey(task.id)) completion[task.id] = after
        }
        val ordered = issues.distinct().sortedBy { it.toString() }.toImmutableList()
        return if (ordered.any { it is PlannerIssue.HardDeadlineShortfall }) PlannerResult.Infeasible(ordered)
        else PlannerResult.Success(mutations.sortedWith(mutationComparator()).toImmutableList(), ordered)
    }

    fun localReflow(snapshot: PlanningSnapshot, request: LocalReflowRequest): PlannerResult {
        val config = snapshot.profile.configuration as? PlanningProfileConfiguration.Configured ?: return invalid(PlannerIssue.ProfileUnconfigured)
        validate(snapshot)?.let { return invalid(it) }
        val affected = snapshot.focusBlocks.filter { it.id in request.affectedFocusBlockIds || request.disruptedRanges.any { range -> it.time.overlaps(range) } }
            .sortedWith(compareBy<FocusBlock>({ it.time.start }, { it.id.value }))
        if (affected.any { it.time.start < snapshot.referenceNow || !it.isMutable() }) {
            return PlannerResult.Infeasible(affected.filter { it.time.start < snapshot.referenceNow || !it.isMutable() }.map { PlannerIssue.ImmovableConflict(it.id) }.toImmutableList())
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
        affected.forEach { block ->
            val duration = block.time.endExclusive - block.time.start
            val task = snapshot.tasks.firstOrNull { it.id == block.taskId }
            val placement = free.mapNotNull { interval ->
                if (interval.endExclusive - interval.start < duration) null else {
                    val latest = interval.endExclusive - duration
                    val start = block.time.start.coerceIn(interval.start, latest)
                    Range(start, start + duration).takeIf { candidate -> task == null || candidate.isLegalFor(task, config, snapshot) }
                }
            }.sortedWith(compareBy<Range>({ abs((it.start - block.time.start).inWholeMilliseconds) }, { it.start })).firstOrNull()
                ?: return PlannerResult.Infeasible(listOf(PlannerIssue.NoLegalAvailability(block.taskId)).toImmutableList())
            moves += FocusBlockMutation.Move(block.id, block.taskId, ZonedTimeRange(placement.start, placement.endExclusive, config.timeZone))
            consume(free, placement)
        }
        return PlannerResult.Success(moves.toImmutableList(), issues.sortedBy { it.toString() }.toImmutableList())
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

    private fun candidate(task: Task, remaining: Duration, after: Instant, free: List<Range>, config: PlanningProfileConfiguration.Configured, snapshot: PlanningSnapshot): Range? {
        val cutoff = task.deadline?.deadline?.effective(config.timeZone)
        val overflow = task.deadline?.let { it.policy != DeadlinePolicy.HARD && when (it.overflowPolicy) {
            OverflowPolicy.ALLOW -> true; OverflowPolicy.ASK -> task.id in snapshot.askOverflowAuthorizedTaskIds; OverflowPolicy.NEVER -> false
        } } ?: true
        val eligible = free.mapNotNull { intersect(it, Range(after, snapshot.horizon.endExclusive)) }.sortedWith(compareBy<Range>({ cutoff != null && it.start >= cutoff }, { it.start }))
        eligible.forEach { interval ->
            val before = cutoff?.let { intersect(interval, Range(Instant.DISTANT_PAST, it)) }
            val selected = before ?: if (overflow) interval else null
            if (selected != null) {
                val duration = chunk(remaining, selected.endExclusive - selected.start, config) ?: return@forEach
                val result = Range(selected.start, selected.start + duration)
                if (cutoff == null || result.endExclusive <= cutoff || overflow) return result
            }
        }
        return null
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
    private fun Range.isLegalFor(task: Task, config: PlanningProfileConfiguration.Configured, snapshot: PlanningSnapshot): Boolean {
        val deadline = task.deadline ?: return true
        val cutoff = deadline.deadline.effective(config.timeZone)
        if (endExclusive <= cutoff) return true
        return deadline.policy != DeadlinePolicy.HARD && when (deadline.overflowPolicy) {
            OverflowPolicy.ALLOW -> true
            OverflowPolicy.ASK -> task.id in snapshot.askOverflowAuthorizedTaskIds
            OverflowPolicy.NEVER -> false
        }
    }
    private fun FocusBlock.isMutable() = pinState == PinState.UNPINNED && flexibility != Flexibility.HARD
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
