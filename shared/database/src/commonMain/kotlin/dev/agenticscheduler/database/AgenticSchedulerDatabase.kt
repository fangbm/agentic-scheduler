package dev.agenticscheduler.database

import androidx.room3.Database
import androidx.room3.ConstructedBy
import androidx.room3.RoomDatabase
import androidx.room3.RoomDatabaseConstructor
import dev.agenticscheduler.database.record.*
import dev.agenticscheduler.database.dao.*

@Database(
    entities = [
        EventRecord::class, TaskRecord::class, FocusBlockRecord::class, WorkLogRecord::class, TaskDependencyRecord::class,
        PlanningProfileRecord::class, AcademicYearRecord::class, SemesterRecord::class, AcademicWeekRecord::class,
        CourseRecord::class, PeriodTemplateRecord::class, AcademicPeriodRecord::class, CourseScheduleRuleRecord::class,
        CourseRuleWeekRecord::class, AcademicHolidayRecord::class, CourseOccurrenceExceptionRecord::class, ExamRecord::class,
    ],
    version = 1,
    exportSchema = true,
)
@ConstructedBy(AgenticSchedulerDatabaseConstructor::class)
abstract class AgenticSchedulerDatabase : RoomDatabase() {
    abstract fun eventDao(): EventDao
    abstract fun taskDao(): TaskDao
    abstract fun planningProfileDao(): PlanningProfileDao
    abstract fun academicYearDao(): AcademicYearDao
    abstract fun semesterDao(): SemesterDao
    abstract fun courseDao(): CourseDao
    abstract fun periodTemplateDao(): PeriodTemplateDao
    abstract fun courseScheduleRuleDao(): CourseScheduleRuleDao
    abstract fun academicHolidayDao(): AcademicHolidayDao
    abstract fun courseOccurrenceExceptionDao(): CourseOccurrenceExceptionDao
    abstract fun examDao(): ExamDao
    abstract fun focusBlockDao(): FocusBlockDao
    abstract fun workLogDao(): WorkLogDao
    abstract fun taskDependencyDao(): TaskDependencyDao
}

@Suppress("NO_ACTUAL_FOR_EXPECT")
expect object AgenticSchedulerDatabaseConstructor : RoomDatabaseConstructor<AgenticSchedulerDatabase> {
    override fun initialize(): AgenticSchedulerDatabase
}
