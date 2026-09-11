package dev.agenticscheduler.database

import androidx.room3.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import kotlinx.coroutines.Dispatchers

/** Opens the caller-selected persistent desktop database; this module never chooses a temp or CWD path. */
fun openDesktopDatabase(absolutePath: String): AgenticSchedulerDatabase =
    Room.databaseBuilder<AgenticSchedulerDatabase>(absolutePath) { AgenticSchedulerDatabaseConstructor.initialize() }
        .setDriver(BundledSQLiteDriver())
        .setQueryCoroutineContext(Dispatchers.IO)
        .build()

internal fun openInMemoryDesktopDatabase(): AgenticSchedulerDatabase =
    Room.inMemoryDatabaseBuilder<AgenticSchedulerDatabase> { AgenticSchedulerDatabaseConstructor.initialize() }
        .setDriver(BundledSQLiteDriver())
        .setQueryCoroutineContext(Dispatchers.IO)
        .build()
