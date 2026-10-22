package com.daybloom.data.database

import androidx.room.ConstructedBy
import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.RoomDatabaseConstructor
import androidx.room.immediateTransaction
import androidx.room.useWriterConnection
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO

@Database(
    entities = [
        TaskEntity::class,
        SubtaskEntity::class,
        AppointmentEntity::class,
        AppointmentOccurrenceEntity::class,
        ReminderEntity::class,
        ScheduledNotificationEntity::class,
        DailyPlanEntity::class,
        HabitEntity::class,
        HabitCompletionEntity::class,
        FocusSessionEntity::class,
        ScoreSnapshotEntity::class,
        XpEntryEntity::class,
        AchievementUnlockEntity::class,
        QuestEntity::class,
        ReflectionEntity::class,
        ManualWinEntity::class,
        BreakEntity::class,
        SuggestionDismissalEntity::class,
    ],
    version = AppDatabase.VERSION,
    exportSchema = true,
)
@ConstructedBy(AppDatabaseConstructor::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun taskDao(): TaskDao
    abstract fun appointmentDao(): AppointmentDao
    abstract fun dailyPlanDao(): DailyPlanDao
    abstract fun reminderDao(): ReminderDao
    abstract fun habitDao(): HabitDao
    abstract fun focusDao(): FocusDao
    abstract fun scoreDao(): ScoreDao
    abstract fun xpDao(): XpDao
    abstract fun achievementDao(): AchievementDao
    abstract fun questDao(): QuestDao
    abstract fun journalDao(): JournalDao
    abstract fun suggestionDao(): SuggestionDao

    companion object {
        const val VERSION = 2
        const val FILE_NAME = "daybloom.db"
    }
}

// Room generates the actual implementations for each platform.
@Suppress("NO_ACTUAL_FOR_EXPECT", "KotlinNoActualForExpect")
expect object AppDatabaseConstructor : RoomDatabaseConstructor<AppDatabase> {
    override fun initialize(): AppDatabase
}

/** Applies the shared configuration to a platform-created builder. Used once per process via DI. */
fun RoomDatabase.Builder<AppDatabase>.buildDayBloomDatabase(): AppDatabase =
    addMigrations(*Migrations.ALL)
        .setDriver(BundledSQLiteDriver())
        .setQueryCoroutineContext(Dispatchers.IO)
        .build()

/** Runs [block] in a single write transaction so multi-table updates are all-or-nothing. */
interface TransactionRunner {
    suspend fun <T> inTransaction(block: suspend () -> T): T
}

class RoomTransactionRunner(private val database: AppDatabase) : TransactionRunner {
    override suspend fun <T> inTransaction(block: suspend () -> T): T =
        database.useWriterConnection { transactor -> transactor.immediateTransaction { block() } }
}
