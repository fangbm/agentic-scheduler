package dev.agenticscheduler.application.history

import dev.agenticscheduler.sync.*

/**
 * SYN-013/SYN-014 group projection over the frozen typed semantic images.
 * Values remain typed while comparing; no JSON/object timestamp merge is involved.
 */
internal data class SemanticGroupValue(val name: String, val value: Any?)

internal fun EntityMutation.changedSemanticGroups(): List<SemanticGroupValue> = when (this) {
    is EventPut -> changedEventGroups(before, after)
    is TaskPut -> changedTaskGroups(before, after)
    is PlanningProfilePut -> changedProfileGroups(before, after)
    is FocusBlockPut -> changedFocusBlockGroups(before, after)
    is FocusBlockDelete -> listOf(SemanticGroupValue("existence", false))
    is WorkLogAppend -> listOf(SemanticGroupValue("append", after))
    is TaskDependencyPut -> changedWholeGroup(before, after)
    is AcademicYearPut -> changedWholeGroup(before, after)
    is SemesterPut -> changedWholeGroup(before, after)
    is CoursePut -> changedWholeGroup(before, after)
    is PeriodTemplatePut -> changedWholeGroup(before, after)
    is AcademicHolidayPut -> changedWholeGroup(before, after)
    is CourseScheduleRulePut -> changedWholeGroup(before, after)
    is CourseOccurrenceExceptionPut -> changedWholeGroup(before, after)
    is ExamPut -> changedExamGroups(before, after)
}

internal fun conflictingGroupNames(local: EntityMutation, incoming: EntityMutation): List<String> {
    if (local.entityKind != incoming.entityKind || local.entityId != incoming.entityId) return emptyList()
    val localGroups = local.changedSemanticGroups().associateBy(SemanticGroupValue::name)
    val incomingGroups = incoming.changedSemanticGroups().associateBy(SemanticGroupValue::name)
    val directConflicts = (localGroups.keys intersect incomingGroups.keys).filter { group ->
        localGroups.getValue(group).value != incomingGroups.getValue(group).value
    }
    val configurationTransitionConflict = local.entityKind == EntityKind.PLANNING_PROFILE && (
        ("configuration.mode" in localGroups && incomingGroups.keys.any { it.startsWith("configuration.") && it != "configuration.mode" }) ||
            ("configuration.mode" in incomingGroups && localGroups.keys.any { it.startsWith("configuration.") && it != "configuration.mode" })
        )
    return (directConflicts + if (configurationTransitionConflict) listOf("configuration.mode") else emptyList()).distinct().sorted()
}

/** Applies exactly the incoming mutation's changed groups to the current typed image. */
internal fun mergeWithCurrent(current: EntityMutation, incoming: EntityMutation): EntityMutation = when {
    current.entityKind != incoming.entityKind || current.entityId != incoming.entityId -> incoming
    current is EventPut && incoming is EventPut -> incoming.copy(after = mergeEvent(current.after, incoming))
    current is TaskPut && incoming is TaskPut -> incoming.copy(after = mergeTask(current.after, incoming))
    current is PlanningProfilePut && incoming is PlanningProfilePut -> incoming.copy(after = mergeProfile(current.after, incoming))
    current is FocusBlockPut && incoming is FocusBlockPut -> incoming.copy(after = mergeFocusBlock(current.after, incoming))
    current is ExamPut && incoming is ExamPut -> incoming.copy(after = mergeExam(current.after, incoming))
    else -> incoming
}

/** Rebase an explicit resolution onto current state, retaining only its requested semantic groups. */
internal fun rebaseOnCurrent(current: EntityMutation, requested: EntityMutation): EntityMutation = when (val merged = mergeWithCurrent(current, requested)) {
    is EventPut -> merged.copy(before = (current as? EventPut)?.after)
    is TaskPut -> merged.copy(before = (current as? TaskPut)?.after)
    is PlanningProfilePut -> merged.copy(before = (current as? PlanningProfilePut)?.after)
    is FocusBlockPut -> merged.copy(before = (current as? FocusBlockPut)?.after)
    is ExamPut -> merged.copy(before = (current as? ExamPut)?.after)
    else -> merged
}

private fun changedEventGroups(before: EventImage?, after: EventImage): List<SemanticGroupValue> = buildList {
    addIfChanged("title", before?.title, after.title, before == null)
    addIfChanged("time", before?.time, after.time, before == null)
    addIfChanged("flexibility", before?.flexibility, after.flexibility, before == null)
    addIfChanged("pinState", before?.pinState, after.pinState, before == null)
}

private fun changedTaskGroups(before: TaskImage?, after: TaskImage): List<SemanticGroupValue> = buildList {
    addIfChanged("title", before?.title, after.title, before == null)
    addIfChanged("status", before?.status, after.status, before == null)
    addIfChanged("priority", before?.priority, after.priority, before == null)
    addIfChanged("effort", before?.effort, after.effort, before == null)
    addIfChanged("deadline", before?.deadline, after.deadline, before == null)
}

private fun changedFocusBlockGroups(before: FocusBlockImage?, after: FocusBlockImage): List<SemanticGroupValue> = buildList {
    add(SemanticGroupValue("existence", true))
    addIfChanged("taskId", before?.taskId, after.taskId, before == null)
    addIfChanged("time", before?.time, after.time, before == null)
    addIfChanged("authority", before?.let { it.flexibility to it.pinState }, after.flexibility to after.pinState, before == null)
}

private fun changedExamGroups(before: ExamImage?, after: ExamImage): List<SemanticGroupValue> = buildList {
    addIfChanged("reference", before?.let { it.semesterId to it.courseId }, after.semesterId to after.courseId, before == null)
    addIfChanged("title", before?.title, after.title, before == null)
    addIfChanged("schedule", before?.schedule, after.schedule, before == null)
}

private fun changedProfileGroups(before: PlanningProfileImage?, after: PlanningProfileImage): List<SemanticGroupValue> {
    if (before == null) return buildList {
        add(SemanticGroupValue("name", after.name))
        addAll(configurationGroups(after.configuration, forceAll = true))
    }
    val changed = mutableListOf<SemanticGroupValue>()
    if (before.name != after.name) changed += SemanticGroupValue("name", after.name)
    val beforeConfigured = before.configuration as? PlanningProfileConfigurationImage.Configured
    val afterConfigured = after.configuration as? PlanningProfileConfigurationImage.Configured
    if ((beforeConfigured == null) != (afterConfigured == null)) {
        changed += configurationGroups(after.configuration, forceAll = true)
    } else if (beforeConfigured != null && afterConfigured != null) {
        if (beforeConfigured.timeZone != afterConfigured.timeZone) changed += SemanticGroupValue("configuration.timeZone", afterConfigured.timeZone)
        if (beforeConfigured.minimumFocusDuration != afterConfigured.minimumFocusDuration || beforeConfigured.preferredFocusDuration != afterConfigured.preferredFocusDuration || beforeConfigured.maximumFocusDuration != afterConfigured.maximumFocusDuration) {
            changed += SemanticGroupValue("configuration.focusDurations", Triple(afterConfigured.minimumFocusDuration, afterConfigured.preferredFocusDuration, afterConfigured.maximumFocusDuration))
        }
        if (beforeConfigured.allDayEventPolicy != afterConfigured.allDayEventPolicy) changed += SemanticGroupValue("configuration.allDayEventPolicy", afterConfigured.allDayEventPolicy)
        (beforeConfigured.weeklyAvailability.map { it.dayOfWeek } + afterConfigured.weeklyAvailability.map { it.dayOfWeek }).distinct().forEach { day ->
            val previous = beforeConfigured.weeklyAvailability.filter { it.dayOfWeek == day }
            val next = afterConfigured.weeklyAvailability.filter { it.dayOfWeek == day }
            if (previous != next) changed += SemanticGroupValue("configuration.weeklyAvailability.$day", next)
        }
    }
    return changed
}

private fun configurationGroups(value: PlanningProfileConfigurationImage, forceAll: Boolean): List<SemanticGroupValue> = when (value) {
    PlanningProfileConfigurationImage.Unconfigured -> listOf(
        SemanticGroupValue("configuration.mode", "UNCONFIGURED"),
        SemanticGroupValue("configuration.timeZone", null),
        SemanticGroupValue("configuration.focusDurations", null),
        SemanticGroupValue("configuration.allDayEventPolicy", null),
    )
    is PlanningProfileConfigurationImage.Configured -> buildList {
        add(SemanticGroupValue("configuration.mode", "CONFIGURED"))
        if (forceAll) {
            add(SemanticGroupValue("configuration.timeZone", value.timeZone))
            add(SemanticGroupValue("configuration.focusDurations", Triple(value.minimumFocusDuration, value.preferredFocusDuration, value.maximumFocusDuration)))
            add(SemanticGroupValue("configuration.allDayEventPolicy", value.allDayEventPolicy))
            value.weeklyAvailability.map { it.dayOfWeek }.distinct().forEach { day ->
                add(SemanticGroupValue("configuration.weeklyAvailability.$day", value.weeklyAvailability.filter { it.dayOfWeek == day }))
            }
        }
    }
}

private fun <T> changedWholeGroup(before: T?, after: T): List<SemanticGroupValue> =
    if (before == null || before != after) listOf(SemanticGroupValue("whole", after)) else emptyList()

private fun MutableList<SemanticGroupValue>.addIfChanged(name: String, before: Any?, after: Any?, force: Boolean) {
    if (force || before != after) add(SemanticGroupValue(name, after))
}

private fun mergeEvent(current: EventImage, incoming: EventPut): EventImage {
    val names = incoming.changedSemanticGroups().map(SemanticGroupValue::name).toSet()
    return current.copy(
        title = if ("title" in names) incoming.after.title else current.title,
        time = if ("time" in names) incoming.after.time else current.time,
        flexibility = if ("flexibility" in names) incoming.after.flexibility else current.flexibility,
        pinState = if ("pinState" in names) incoming.after.pinState else current.pinState,
    )
}

private fun mergeTask(current: TaskImage, incoming: TaskPut): TaskImage {
    val names = incoming.changedSemanticGroups().map(SemanticGroupValue::name).toSet()
    return current.copy(
        title = if ("title" in names) incoming.after.title else current.title,
        status = if ("status" in names) incoming.after.status else current.status,
        priority = if ("priority" in names) incoming.after.priority else current.priority,
        effort = if ("effort" in names) incoming.after.effort else current.effort,
        deadline = if ("deadline" in names) incoming.after.deadline else current.deadline,
    )
}

private fun mergeFocusBlock(current: FocusBlockImage, incoming: FocusBlockPut): FocusBlockImage {
    val names = incoming.changedSemanticGroups().map(SemanticGroupValue::name).toSet()
    return current.copy(
        taskId = if ("taskId" in names) incoming.after.taskId else current.taskId,
        time = if ("time" in names) incoming.after.time else current.time,
        flexibility = if ("authority" in names) incoming.after.flexibility else current.flexibility,
        pinState = if ("authority" in names) incoming.after.pinState else current.pinState,
    )
}

private fun mergeExam(current: ExamImage, incoming: ExamPut): ExamImage {
    val names = incoming.changedSemanticGroups().map(SemanticGroupValue::name).toSet()
    return current.copy(
        semesterId = if ("reference" in names) incoming.after.semesterId else current.semesterId,
        courseId = if ("reference" in names) incoming.after.courseId else current.courseId,
        title = if ("title" in names) incoming.after.title else current.title,
        schedule = if ("schedule" in names) incoming.after.schedule else current.schedule,
    )
}

private fun mergeProfile(current: PlanningProfileImage, incoming: PlanningProfilePut): PlanningProfileImage {
    val names = incoming.changedSemanticGroups().map(SemanticGroupValue::name).toSet()
    val incomingConfiguration = incoming.after.configuration
    if ("configuration.mode" in names) return current.copy(
        name = if ("name" in names) incoming.after.name else current.name,
        configuration = incoming.after.configuration,
    )
    val currentConfigured = current.configuration as? PlanningProfileConfigurationImage.Configured ?: return incoming.after
    val incomingConfigured = incomingConfiguration as? PlanningProfileConfigurationImage.Configured ?: return incoming.after
    val currentByDay = currentConfigured.weeklyAvailability.groupBy { it.dayOfWeek }
    val incomingByDay = incomingConfigured.weeklyAvailability.groupBy { it.dayOfWeek }
    val availability = (currentByDay.keys + incomingByDay.keys)
        .sortedBy { it.ordinal }
        .flatMap { day ->
            if ("configuration.weeklyAvailability.$day" in names) incomingByDay[day].orEmpty() else currentByDay[day].orEmpty()
        }
    return current.copy(
        name = if ("name" in names) incoming.after.name else current.name,
        configuration = currentConfigured.copy(
            timeZone = if ("configuration.timeZone" in names) incomingConfigured.timeZone else currentConfigured.timeZone,
            weeklyAvailability = availability,
            minimumFocusDuration = if ("configuration.focusDurations" in names) incomingConfigured.minimumFocusDuration else currentConfigured.minimumFocusDuration,
            preferredFocusDuration = if ("configuration.focusDurations" in names) incomingConfigured.preferredFocusDuration else currentConfigured.preferredFocusDuration,
            maximumFocusDuration = if ("configuration.focusDurations" in names) incomingConfigured.maximumFocusDuration else currentConfigured.maximumFocusDuration,
            allDayEventPolicy = if ("configuration.allDayEventPolicy" in names) incomingConfigured.allDayEventPolicy else currentConfigured.allDayEventPolicy,
        ),
    )
}
