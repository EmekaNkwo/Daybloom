package com.daybloom.data.database

import androidx.room.migration.Migration
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL

/**
 * Migration strategy: every schema change bumps [AppDatabase.VERSION], exports a schema JSON to
 * composeApp/schemas, and adds an explicit migration here plus a test in androidUnitTest/MigrationTest.
 * Destructive fallback is never enabled — user data must survive upgrades.
 */
object Migrations {
    /** v2: suggestions can be dismissed or snoozed persistently. */
    val MIGRATION_1_2 = object : Migration(1, 2) {
        override fun migrate(connection: SQLiteConnection) {
            connection.execSQL(
                "CREATE TABLE IF NOT EXISTS `suggestion_dismissals` " +
                    "(`key` TEXT NOT NULL, `until` INTEGER, `dismissedAt` INTEGER NOT NULL, PRIMARY KEY(`key`))",
            )
        }
    }

    val ALL: Array<Migration> = arrayOf(MIGRATION_1_2)
}
