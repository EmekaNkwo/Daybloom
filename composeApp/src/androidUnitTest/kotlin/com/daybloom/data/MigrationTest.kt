package com.daybloom.data

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.driver.AndroidSQLiteDriver
import androidx.sqlite.execSQL
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.daybloom.data.database.AppDatabase
import com.daybloom.data.database.Migrations
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MigrationTest {
    @get:Rule val temp = TemporaryFolder()

    @Test
    fun migrate1To2KeepsDataAndMatchesTheExportedSchema() {
        val file = temp.newFile("migration.db").also { it.delete() }
        val helper = MigrationTestHelper(
            instrumentation = InstrumentationRegistry.getInstrumentation(),
            file = file,
            driver = AndroidSQLiteDriver(),
            databaseClass = AppDatabase::class,
        )
        helper.createDatabase(1).apply {
            execSQL(
                "INSERT INTO tasks (id, title, description, category, priority, dueDate, dueTime, estimatedMinutes, actualMinutes, status, " +
                    "recurrenceJson, seriesId, notes, tagsJson, isFlexible, appointmentId, postponeCount, createdAt, updatedAt, completedAt, completedOn, cancelledAt) " +
                    "VALUES ('t1', 'Kept across upgrade', '', 'WORK', 'HIGH', '2026-10-22', NULL, 30, NULL, 'OPEN', NULL, 't1', '', '[]', 1, NULL, 0, 1, 1, NULL, NULL, NULL)",
            )
            execSQL("INSERT INTO xp_ledger (eventKey, amount, reason, sourceId, date, createdAt) VALUES ('task:x', 10, 'TASK_COMPLETED', 'x', '2026-10-22', 1)")
            close()
        }
        // Validates every table, column and index against schemas/…/2.json.
        val migrated = helper.runMigrationsAndValidate(2, listOf(Migrations.MIGRATION_1_2))
        migrated.prepare("SELECT title FROM tasks WHERE id = 't1'").use { stmt ->
            stmt.step()
            assertEquals("Kept across upgrade", stmt.getText(0))
        }
        migrated.prepare("SELECT SUM(amount) FROM xp_ledger").use { stmt ->
            stmt.step()
            assertEquals(10L, stmt.getLong(0))
        }
        migrated.execSQL("INSERT INTO suggestion_dismissals (`key`, `until`, dismissedAt) VALUES ('k', NULL, 1)")
        migrated.close()
    }
}
