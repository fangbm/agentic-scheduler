package dev.agenticscheduler.database

import android.content.Context
import androidx.room3.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import kotlinx.coroutines.Dispatchers

const val AgenticSchedulerDatabaseFileName = "agentic-scheduler.db"

/** Android and Wear composition supplies the app context, which owns the persistent file location. */
fun openAndroidDatabase(context: Context): AgenticSchedulerDatabase =
    Room.databaseBuilder<AgenticSchedulerDatabase>(context, AgenticSchedulerDatabaseFileName) { AgenticSchedulerDatabaseConstructor.initialize() }
        .setDriver(BundledSQLiteDriver())
        .setQueryCoroutineContext(Dispatchers.IO)
        .build()
