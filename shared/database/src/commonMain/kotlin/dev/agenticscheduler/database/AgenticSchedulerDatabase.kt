package dev.agenticscheduler.database

import androidx.room3.Database
import androidx.room3.ConstructedBy
import androidx.room3.RoomDatabase
import androidx.room3.RoomDatabaseConstructor
import androidx.room3.AutoMigration
import dev.agenticscheduler.database.record.*
import dev.agenticscheduler.database.dao.*

@Database(
    entities = [
        EventRecord::class, TaskRecord::class, FocusBlockRecord::class, WorkLogRecord::class, TaskDependencyRecord::class,
        PlanningProfileRecord::class, PlanningProfileAvailabilityWindowRecord::class, AcademicYearRecord::class, SemesterRecord::class, AcademicWeekRecord::class,
        CourseRecord::class, PeriodTemplateRecord::class, AcademicPeriodRecord::class, CourseScheduleRuleRecord::class,
        CourseRuleWeekRecord::class, AcademicHolidayRecord::class, CourseOccurrenceExceptionRecord::class, ExamRecord::class,
        MutationRecord::class, ChangeLogEntryRecord::class, SyncOperationJournalRecord::class, ReplicaCausalStateRecord::class, FocusBlockTombstoneRecord::class,
        SyncSpaceCursorRecord::class, ProtocolQuarantineRecord::class, SyncConflictRecord::class,
        PendingSyncReceiveRecord::class, HandledReceiveDotRecord::class, SyncSpaceKeyEpochRecord::class, SyncSpaceKeyStateRecord::class, SyncSpaceContentKeyRecord::class, LocalPairingEnrollmentRecord::class,
    ],
    version = 12,
    exportSchema = true,
    autoMigrations = [AutoMigration(from = 1, to = 2), AutoMigration(from = 2, to = 3), AutoMigration(from = 3, to = 4), AutoMigration(from = 4, to = 5), AutoMigration(from = 5, to = 6), AutoMigration(from = 6, to = 7), AutoMigration(from = 7, to = 8), AutoMigration(from = 8, to = 9), AutoMigration(from = 9, to = 10), AutoMigration(from = 10, to = 11)],
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
    abstract fun mutationJournalDao(): MutationJournalDao
    abstract fun syncReceiveDao(): SyncReceiveDao
    abstract fun syncKeyMetadataDao(): SyncKeyMetadataDao
    abstract fun syncKeyRingDao(): SyncKeyRingDao
    abstract fun localPairingEnrollmentDao(): LocalPairingEnrollmentDao
}

@Suppress("NO_ACTUAL_FOR_EXPECT")
expect object AgenticSchedulerDatabaseConstructor : RoomDatabaseConstructor<AgenticSchedulerDatabase> {
    override fun initialize(): AgenticSchedulerDatabase
}
