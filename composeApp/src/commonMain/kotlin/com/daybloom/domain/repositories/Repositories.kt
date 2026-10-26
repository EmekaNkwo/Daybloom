package com.daybloom.domain.repositories

import com.daybloom.domain.models.AchievementUnlock
import com.daybloom.domain.models.Appointment
import com.daybloom.domain.models.AppointmentStatus
import com.daybloom.domain.models.DailyPlan
import com.daybloom.domain.models.DailyReflection
import com.daybloom.domain.models.DailyScore
import com.daybloom.domain.models.FocusSession
import com.daybloom.domain.models.Habit
import com.daybloom.domain.models.HabitCompletion
import com.daybloom.domain.models.ManualWin
import com.daybloom.domain.models.Quest
import com.daybloom.domain.models.Reminder
import com.daybloom.domain.models.ReminderSource
import com.daybloom.domain.models.Task
import com.daybloom.domain.models.TaskCategory
import com.daybloom.domain.models.UserPreferences
import com.daybloom.domain.models.XpEntry
import kotlinx.coroutines.flow.Flow
import kotlinx.datetime.LocalDate
import kotlin.time.Instant

interface TaskRepository {
    fun observeAll(): Flow<List<Task>>
    fun observe(id: String): Flow<Task?>
    /** Tasks due on [date], completed on [date], or explicitly listed (planned) in [extraIds]. */
    fun observeRelevantTo(date: LocalDate, extraIds: List<String>): Flow<List<Task>>
    fun observeDueBetween(from: LocalDate, to: LocalDate): Flow<List<Task>>
    fun observeCompletedBetween(from: LocalDate, to: LocalDate): Flow<List<Task>>
    fun observeForAppointment(appointmentId: String): Flow<List<Task>>
    suspend fun get(id: String): Task?
    suspend fun getByIds(ids: List<String>): List<Task>
    suspend fun getRelevantTo(date: LocalDate, extraIds: List<String>): List<Task>
    suspend fun getDueBetween(from: LocalDate, to: LocalDate): List<Task>
    suspend fun getCompletedBetween(from: LocalDate, to: LocalDate): List<Task>
    suspend fun getOpen(): List<Task>
    suspend fun getForAppointment(appointmentId: String): List<Task>
    suspend fun existsInSeriesOn(seriesId: String, date: LocalDate): Boolean
    suspend fun completionDates(): Set<LocalDate>
    suspend fun completionCategories(): List<Pair<LocalDate, TaskCategory>>
    suspend fun countCompleted(): Int
    suspend fun save(task: Task)
    suspend fun delete(id: String)
}

interface AppointmentRepository {
    fun observeAll(): Flow<List<Appointment>>
    fun observe(id: String): Flow<Appointment?>
    /** Recorded per-occurrence statuses keyed by "appointmentId@date". */
    fun observeOccurrenceStatuses(): Flow<Map<String, AppointmentStatus>>
    suspend fun get(id: String): Appointment?
    suspend fun getAll(): List<Appointment>
    suspend fun getOccurrenceStatuses(): Map<String, AppointmentStatus>
    suspend fun save(appointment: Appointment)
    suspend fun delete(id: String)
    suspend fun setOccurrenceStatus(appointmentId: String, date: LocalDate, status: AppointmentStatus, at: Instant)
}

/** What the app has handed to the OS scheduler, persisted so it can be reconciled. */
data class ScheduledRecord(
    val id: String,
    val reminderId: String,
    val fireAt: Instant,
    val contentHash: String,
)

interface ReminderRepository {
    fun observeAll(): Flow<List<Reminder>>
    fun observeForSource(source: ReminderSource, sourceId: String): Flow<List<Reminder>>
    fun observeScheduled(): Flow<List<ScheduledRecord>>
    suspend fun getAll(): List<Reminder>
    suspend fun getActive(): List<Reminder>
    suspend fun get(id: String): Reminder?
    suspend fun getForSource(source: ReminderSource, sourceId: String): List<Reminder>
    suspend fun save(reminder: Reminder)
    suspend fun delete(id: String)
    suspend fun deleteForSource(source: ReminderSource, sourceId: String)
    suspend fun getScheduled(): List<ScheduledRecord>
    suspend fun recordScheduled(records: List<ScheduledRecord>, at: Instant)
    suspend fun removeScheduled(ids: Collection<String>)
    suspend fun clearScheduled()
}

interface PlanRepository {
    fun observe(date: LocalDate): Flow<DailyPlan?>
    fun observeBetween(from: LocalDate, to: LocalDate): Flow<List<DailyPlan>>
    suspend fun get(date: LocalDate): DailyPlan?
    suspend fun getBetween(from: LocalDate, to: LocalDate): List<DailyPlan>
    suspend fun getAll(): List<DailyPlan>
    suspend fun save(plan: DailyPlan)
}

interface HabitRepository {
    fun observeHabits(): Flow<List<Habit>>
    fun observeArchived(): Flow<List<Habit>>
    fun observeCompletions(): Flow<List<HabitCompletion>>
    suspend fun getHabits(): List<Habit>
    suspend fun get(id: String): Habit?
    suspend fun save(habit: Habit)
    suspend fun delete(id: String)
    suspend fun getCompletions(): List<HabitCompletion>
    suspend fun getCompletionsBetween(from: LocalDate, to: LocalDate): List<HabitCompletion>
    suspend fun setCompleted(habitId: String, date: LocalDate, done: Boolean, at: Instant)
}

interface FocusRepository {
    fun observeActive(): Flow<FocusSession?>
    fun observeBetween(from: LocalDate, to: LocalDate): Flow<List<FocusSession>>
    suspend fun getActive(): FocusSession?
    suspend fun get(id: String): FocusSession?
    suspend fun getBetween(from: LocalDate, to: LocalDate): List<FocusSession>
    suspend fun getAll(): List<FocusSession>
    suspend fun save(session: FocusSession)
}

interface ScoreRepository {
    fun observe(date: LocalDate): Flow<DailyScore?>
    fun observeBetween(from: LocalDate, to: LocalDate): Flow<List<DailyScore>>
    suspend fun get(date: LocalDate): DailyScore?
    suspend fun getBetween(from: LocalDate, to: LocalDate): List<DailyScore>
    suspend fun save(score: DailyScore)
}

interface XpRepository {
    /** Returns true if the entry was recorded, false if an entry with the same event key already existed. */
    suspend fun award(entry: XpEntry): Boolean
    /** Returns true if an entry was removed. */
    suspend fun revoke(eventKey: String): Boolean
    fun observeTotal(): Flow<Int>
    fun observeRecent(limit: Int): Flow<List<XpEntry>>
    suspend fun total(): Int
    suspend fun countRewardedTasksOn(date: LocalDate): Int
    suspend fun countByReason(reason: com.daybloom.domain.models.XpReason): Int
    suspend fun exists(eventKey: String): Boolean
}

interface AchievementRepository {
    fun observeUnlocks(): Flow<List<AchievementUnlock>>
    suspend fun getUnlocks(): List<AchievementUnlock>
    /** Returns true if newly unlocked. */
    suspend fun unlock(achievementId: String, at: Instant): Boolean
}

interface QuestRepository {
    fun observeForDate(date: LocalDate): Flow<List<Quest>>
    fun observeCompletedCount(): Flow<Int>
    suspend fun getForDate(date: LocalDate): List<Quest>
    suspend fun insertIfAbsent(quests: List<Quest>)
    suspend fun update(quest: Quest)
    suspend fun countCompleted(): Int
}

interface JournalRepository {
    fun observeReflection(date: LocalDate): Flow<DailyReflection?>
    fun observeReflections(): Flow<List<DailyReflection>>
    fun observeWins(date: LocalDate): Flow<List<ManualWin>>
    fun observeBreakCount(date: LocalDate): Flow<Int>
    suspend fun getReflection(date: LocalDate): DailyReflection?
    suspend fun getReflections(): List<DailyReflection>
    suspend fun saveReflection(reflection: DailyReflection)
    suspend fun deleteReflection(date: LocalDate)
    suspend fun saveWin(win: ManualWin)
    suspend fun deleteWin(id: String)
    suspend fun logBreak(id: String, date: LocalDate, at: Instant)
    suspend fun countBreaks(date: LocalDate): Int
}

interface PreferencesRepository {
    val preferences: Flow<UserPreferences>
    suspend fun current(): UserPreferences
    suspend fun update(transform: (UserPreferences) -> UserPreferences)
    suspend fun reset()
}

/** Wipes all user data (user-controlled reset). Preferences are reset separately. */
interface DataResetRepository {
    suspend fun deleteAllData()
}
