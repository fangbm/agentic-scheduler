package dev.agenticscheduler.database.repository

import androidx.room3.withWriteTransaction
import dev.agenticscheduler.application.persistence.*
import dev.agenticscheduler.database.AgenticSchedulerDatabase
import dev.agenticscheduler.database.mapper.*
import dev.agenticscheduler.domain.academic.*
import dev.agenticscheduler.domain.event.Event
import dev.agenticscheduler.domain.id.*
import dev.agenticscheduler.domain.planning.PlanningProfile
import dev.agenticscheduler.domain.task.*
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map

class RoomApplicationTransactionRunner(private val database: AgenticSchedulerDatabase) : ApplicationTransactionRunner {
    override suspend fun <T> inWriteTransaction(block: suspend () -> T): T = database.withWriteTransaction { block() }
}

class RoomEventRepository(private val database: AgenticSchedulerDatabase) : EventRepository {
    override fun observeAll(): Flow<kotlinx.collections.immutable.ImmutableList<Event>> = database.eventDao().observeAll().map { rows -> rows.map { it.toDomain() }.toImmutableList() }
    override suspend fun get(id: EventId): Event? = database.eventDao().get(id.value)?.toDomain()
    override suspend fun upsert(event: Event) = database.eventDao().upsert(event.toRecord())
}

class RoomTaskRepository(private val database: AgenticSchedulerDatabase) : TaskRepository {
    override fun observeTasks() = database.taskDao().observeAll().map { it.map { row -> row.toDomain() }.toImmutableList() }
    override suspend fun getTask(id: TaskId) = database.taskDao().get(id.value)?.toDomain()
    override suspend fun upsertTask(task: Task) = database.taskDao().upsert(task.toRecord())
    override fun observeFocusBlocks() = database.focusBlockDao().observeAll().map { it.map { row -> row.toDomain() }.toImmutableList() }
    override suspend fun getFocusBlock(id: FocusBlockId) = database.focusBlockDao().get(id.value)?.toDomain()
    override suspend fun upsertFocusBlock(focusBlock: FocusBlock) = database.focusBlockDao().upsert(focusBlock.toRecord())
    override fun observeWorkLogs() = database.workLogDao().observeAll().map { it.map { row -> row.toDomain() }.toImmutableList() }
    override suspend fun getWorkLog(id: WorkLogId) = database.workLogDao().get(id.value)?.toDomain()
    override suspend fun upsertWorkLog(workLog: WorkLog) = database.workLogDao().upsert(workLog.toRecord())
    override fun observeDependencies() = database.taskDependencyDao().observeAll().map { it.map { row -> row.toDomain() }.toImmutableList() }
    override suspend fun getDependency(id: TaskDependencyId) = database.taskDependencyDao().get(id.value)?.toDomain()
    override suspend fun upsertDependency(dependency: TaskDependency) {
        val record = dependency.toRecord()
        check(database.taskDependencyDao().idForPair(record.prerequisiteTaskId, record.dependentTaskId)?.let { it == record.id } != false) {
            "A TaskDependency pair must be unique."
        }
        database.taskDependencyDao().upsert(record)
    }
}

class RoomPlanningProfileRepository(private val database: AgenticSchedulerDatabase) : PlanningProfileRepository {
    override fun observeAll(): Flow<kotlinx.collections.immutable.ImmutableList<PlanningProfile>> = database.planningProfileDao().observeAll().map { it.map { row -> row.toDomain() }.toImmutableList() }
    override suspend fun get(id: PlanningProfileId) = database.planningProfileDao().get(id.value)?.toDomain()
    override suspend fun upsert(profile: PlanningProfile) = database.planningProfileDao().upsert(profile.toRecord())
}

class RoomAcademicRepository(private val database: AgenticSchedulerDatabase) : AcademicRepository {
    override fun observeAcademicYears() = database.academicYearDao().observeAll().map { it.map { row -> row.toDomain() }.toImmutableList() }
    override suspend fun getAcademicYear(id: AcademicYearId) = database.academicYearDao().get(id.value)?.toDomain()
    override suspend fun upsertAcademicYear(value: AcademicYear) = database.academicYearDao().upsert(value.toRecord())

    override fun observeSemesters() = combine(database.semesterDao().observeAll(), database.semesterDao().observeAllWeeks()) { parents, children -> parents.map { parent -> parent.toDomain(children.filter { it.semesterId == parent.id }) }.toImmutableList() }
    override suspend fun getSemester(id: SemesterId) = database.semesterDao().get(id.value)?.let { it.toDomain(database.semesterDao().weeks(it.id)) }
    override suspend fun upsertSemester(value: Semester) = database.withWriteTransaction { database.semesterDao().upsert(value.toRecord()); database.semesterDao().deleteWeeks(value.id.value); database.semesterDao().upsertWeeks(value.weekRecords()) }

    override fun observeCourses() = database.courseDao().observeAll().map { it.map { row -> row.toDomain() }.toImmutableList() }
    override suspend fun getCourse(id: CourseId) = database.courseDao().get(id.value)?.toDomain()
    override suspend fun upsertCourse(value: Course) = database.courseDao().upsert(value.toRecord())

    override fun observePeriodTemplates() = combine(database.periodTemplateDao().observeAll(), database.periodTemplateDao().observeAllPeriods()) { parents, children -> parents.map { parent -> parent.toDomain(children.filter { it.periodTemplateId == parent.id }) }.toImmutableList() }
    override suspend fun getPeriodTemplate(id: PeriodTemplateId) = database.periodTemplateDao().get(id.value)?.let { it.toDomain(database.periodTemplateDao().periods(it.id)) }
    override suspend fun upsertPeriodTemplate(value: PeriodTemplate) = database.withWriteTransaction { database.periodTemplateDao().upsert(value.toRecord()); database.periodTemplateDao().deletePeriods(value.id.value); database.periodTemplateDao().upsertPeriods(value.periodRecords()) }

    override fun observeCourseScheduleRules() = combine(database.courseScheduleRuleDao().observeAll(), database.courseScheduleRuleDao().observeAllWeeks()) { parents, children -> parents.map { parent -> parent.toDomain(children.filter { it.scheduleRuleId == parent.id }) }.toImmutableList() }
    override suspend fun getCourseScheduleRule(id: CourseScheduleRuleId) = database.courseScheduleRuleDao().get(id.value)?.let { it.toDomain(database.courseScheduleRuleDao().weeks(it.id)) }
    override suspend fun upsertCourseScheduleRule(value: CourseScheduleRule) = database.withWriteTransaction { database.courseScheduleRuleDao().upsert(value.toRecord()); database.courseScheduleRuleDao().deleteWeeks(value.id.value); database.courseScheduleRuleDao().upsertWeeks(value.weekRecords()) }

    override fun observeAcademicHolidays() = database.academicHolidayDao().observeAll().map { it.map { row -> row.toDomain() }.toImmutableList() }
    override suspend fun getAcademicHoliday(id: AcademicHolidayId) = database.academicHolidayDao().get(id.value)?.toDomain()
    override suspend fun upsertAcademicHoliday(value: AcademicHoliday) = database.academicHolidayDao().upsert(value.toRecord())
    override fun observeCourseOccurrenceExceptions() = database.courseOccurrenceExceptionDao().observeAll().map { it.map { row -> row.toDomain() }.toImmutableList() }
    override suspend fun getCourseOccurrenceException(id: CourseOccurrenceExceptionId) = database.courseOccurrenceExceptionDao().get(id.value)?.toDomain()
    override suspend fun upsertCourseOccurrenceException(value: CourseOccurrenceException) {
        val record = value.toRecord()
        check(database.courseOccurrenceExceptionDao().idForOccurrence(record.scheduleRuleId, record.academicWeekNumber)?.let { it == record.id } != false) {
            "A CourseOccurrenceException target must be unique."
        }
        database.courseOccurrenceExceptionDao().upsert(record)
    }
    override fun observeExams() = database.examDao().observeAll().map { it.map { row -> row.toDomain() }.toImmutableList() }
    override suspend fun getExam(id: ExamId) = database.examDao().get(id.value)?.toDomain()
    override suspend fun upsertExam(value: Exam) = database.examDao().upsert(value.toRecord())
}
