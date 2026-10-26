package com.daybloom.data.database

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface AppointmentDao {
    @Query("SELECT * FROM appointments ORDER BY date, startTime")
    fun observeAll(): Flow<List<AppointmentEntity>>

    @Query("SELECT * FROM appointments")
    suspend fun getAll(): List<AppointmentEntity>

    @Query("SELECT * FROM appointments WHERE id = :id")
    suspend fun get(id: String): AppointmentEntity?

    @Query("SELECT * FROM appointments WHERE id = :id")
    fun observe(id: String): Flow<AppointmentEntity?>

    @Upsert
    suspend fun upsert(entity: AppointmentEntity)

    @Query("DELETE FROM appointments WHERE id = :id")
    suspend fun delete(id: String)

    @Query("SELECT * FROM appointment_occurrences")
    fun observeOccurrenceStatuses(): Flow<List<AppointmentOccurrenceEntity>>

    @Query("SELECT * FROM appointment_occurrences")
    suspend fun getOccurrenceStatuses(): List<AppointmentOccurrenceEntity>

    @Upsert
    suspend fun upsertOccurrence(entity: AppointmentOccurrenceEntity)

    @Query("DELETE FROM appointments")
    suspend fun deleteAll()
}

@Dao
interface DailyPlanDao {
    @Query("SELECT * FROM daily_plans WHERE date = :date")
    fun observe(date: String): Flow<DailyPlanEntity?>

    @Query("SELECT * FROM daily_plans WHERE date = :date")
    suspend fun get(date: String): DailyPlanEntity?

    @Query("SELECT * FROM daily_plans WHERE date BETWEEN :from AND :to")
    suspend fun getBetween(from: String, to: String): List<DailyPlanEntity>

    @Query("SELECT * FROM daily_plans WHERE date BETWEEN :from AND :to")
    fun observeBetween(from: String, to: String): Flow<List<DailyPlanEntity>>

    @Query("SELECT * FROM daily_plans")
    suspend fun getAll(): List<DailyPlanEntity>

    @Upsert
    suspend fun upsert(entity: DailyPlanEntity)

    @Query("DELETE FROM daily_plans")
    suspend fun deleteAll()
}

@Dao
interface ReminderDao {
    @Query("SELECT * FROM reminders ORDER BY startDate")
    fun observeAll(): Flow<List<ReminderEntity>>

    @Query("SELECT * FROM reminders")
    suspend fun getAll(): List<ReminderEntity>

    @Query("SELECT * FROM reminders WHERE status = 'ACTIVE'")
    suspend fun getActive(): List<ReminderEntity>

    @Query("SELECT * FROM reminders WHERE id = :id")
    suspend fun get(id: String): ReminderEntity?

    @Query("SELECT * FROM reminders WHERE source = :source AND sourceId = :sourceId")
    suspend fun getForSource(source: String, sourceId: String): List<ReminderEntity>

    @Query("SELECT * FROM reminders WHERE source = :source AND sourceId = :sourceId")
    fun observeForSource(source: String, sourceId: String): Flow<List<ReminderEntity>>

    @Upsert
    suspend fun upsert(entity: ReminderEntity)

    @Query("DELETE FROM reminders WHERE id = :id")
    suspend fun delete(id: String)

    @Query("DELETE FROM reminders WHERE source = :source AND sourceId = :sourceId")
    suspend fun deleteForSource(source: String, sourceId: String)

    @Query("SELECT * FROM scheduled_notifications")
    suspend fun getScheduled(): List<ScheduledNotificationEntity>

    @Query("SELECT * FROM scheduled_notifications")
    fun observeScheduled(): Flow<List<ScheduledNotificationEntity>>

    @Upsert
    suspend fun upsertScheduled(entities: List<ScheduledNotificationEntity>)

    @Query("DELETE FROM scheduled_notifications WHERE id IN (:ids)")
    suspend fun deleteScheduled(ids: List<String>)

    @Query("DELETE FROM scheduled_notifications")
    suspend fun deleteAllScheduled()

    @Query("DELETE FROM reminders")
    suspend fun deleteAll()
}

@Dao
interface HabitDao {
    @Query("SELECT * FROM habits WHERE archived = 0 ORDER BY createdAt")
    fun observeActive(): Flow<List<HabitEntity>>

    @Query("SELECT * FROM habits WHERE archived = 1 ORDER BY title COLLATE NOCASE")
    fun observeArchived(): Flow<List<HabitEntity>>

    @Query("SELECT * FROM habits")
    suspend fun getAll(): List<HabitEntity>

    @Query("SELECT * FROM habits WHERE id = :id")
    suspend fun get(id: String): HabitEntity?

    @Upsert
    suspend fun upsert(entity: HabitEntity)

    @Query("DELETE FROM habits WHERE id = :id")
    suspend fun delete(id: String)

    @Query("SELECT * FROM habit_completions")
    fun observeCompletions(): Flow<List<HabitCompletionEntity>>

    @Query("SELECT * FROM habit_completions WHERE date BETWEEN :from AND :to")
    suspend fun getCompletionsBetween(from: String, to: String): List<HabitCompletionEntity>

    @Query("SELECT * FROM habit_completions")
    suspend fun getCompletions(): List<HabitCompletionEntity>

    @Upsert
    suspend fun upsertCompletion(entity: HabitCompletionEntity)

    @Query("DELETE FROM habit_completions WHERE habitId = :habitId AND date = :date")
    suspend fun deleteCompletion(habitId: String, date: String)

    @Query("DELETE FROM habits")
    suspend fun deleteAll()
}

@Dao
interface FocusDao {
    @Query("SELECT * FROM focus_sessions WHERE status IN ('RUNNING','PAUSED') ORDER BY startedAt DESC LIMIT 1")
    fun observeActive(): Flow<FocusSessionEntity?>

    @Query("SELECT * FROM focus_sessions WHERE status IN ('RUNNING','PAUSED') ORDER BY startedAt DESC LIMIT 1")
    suspend fun getActive(): FocusSessionEntity?

    @Query("SELECT * FROM focus_sessions WHERE id = :id")
    suspend fun get(id: String): FocusSessionEntity?

    @Query("SELECT * FROM focus_sessions WHERE date BETWEEN :from AND :to ORDER BY startedAt")
    fun observeBetween(from: String, to: String): Flow<List<FocusSessionEntity>>

    @Query("SELECT * FROM focus_sessions WHERE date BETWEEN :from AND :to ORDER BY startedAt")
    suspend fun getBetween(from: String, to: String): List<FocusSessionEntity>

    @Query("SELECT * FROM focus_sessions")
    suspend fun getAll(): List<FocusSessionEntity>

    @Upsert
    suspend fun upsert(entity: FocusSessionEntity)

    @Query("DELETE FROM focus_sessions")
    suspend fun deleteAll()
}
