package dev.agenticscheduler.database

import androidx.room3.RoomDatabase
import androidx.sqlite.SQLiteDriver
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * OD-012 executable compatibility POC.
 *
 * Room 3's KMP builder accepts only [SQLiteDriver]. SQLCipher's documented
 * Android Room integration supplies a legacy SupportSQLite `SupportFactory`,
 * which cannot be passed to this API. Keeping this assertion in the repository
 * prevents a future dependency-only "migration" from being mistaken for an
 * encrypted Room 3 database.
 */
class Room3SqlCipherCompatibilityPocTest {
    @Test
    fun room3BuilderRequiresKmpSqliteDriverAndHasNoLegacyOpenHelperFactory() {
        val publicMethods = RoomDatabase.Builder::class.java.methods.toList()
        val setDriver = publicMethods.singleOrNull { method ->
            method.name == "setDriver" && method.parameterTypes.contentEquals(arrayOf(SQLiteDriver::class.java))
        }

        assertTrue(setDriver != null, "Room 3 must expose setDriver(SQLiteDriver) for a KMP database driver.")
        assertFalse(
            publicMethods.any { it.name == "openHelperFactory" },
            "Room 3 must not expose the legacy SupportSQLite openHelperFactory integration point.",
        )
    }

    @Test
    fun bundledDriverIsTheCurrentKmpDriverContract() {
        assertEquals(SQLiteDriver::class.java, BundledSQLiteDriver()::class.java.interfaces.single())
    }
}
