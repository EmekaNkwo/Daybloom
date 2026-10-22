package com.daybloom.data.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface ScoreDao {
    @Query("SELECT * FROM score_snapshots WHERE date = :date")
    fun observe(date: String): Flow<ScoreSnapshotEntity?>

    @Query("SELECT * FROM score_snapshots WHERE date = :date")
    suspend fun get(date: String): ScoreSnapshotEntity?

    @Query("SELECT * FROM score_snapshots WHERE date BETWEEN :from AND :to ORDER BY date")
    fun observeBetween(from: String, to: String): Flow<List<ScoreSnapshotEntity>>

    @Query("SELECT * FROM score_snapshots WHERE date BETWEEN :from AND :to ORDER BY date")
    suspend fun getBetween(from: String, to: String): List<ScoreSnapshotEntity>

    @Upsert
    suspend fun upsert(entity: ScoreSnapshotEntity)

    @Query("DELETE FROM score_snapshots")
    suspend fun deleteAll()
}

@Dao
interface XpDao {
    /** IGNORE on conflict is the idempotency guarantee: a repeated event key is silently dropped. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIfAbsent(entity: XpEntryEntity): Long

    @Query("DELETE FROM xp_ledger WHERE eventKey = :eventKey")
    suspend fun delete(eventKey: String): Int

    @Query("SELECT COALESCE(SUM(amount), 0) FROM xp_ledger")
    fun observeTotal(): Flow<Int>

    @Query("SELECT COALESCE(SUM(amount), 0) FROM xp_ledger")
    suspend fun total(): Int

    @Query("SELECT * FROM xp_ledger ORDER BY createdAt DESC LIMIT :limit")
    fun observeRecent(limit: Int): Flow<List<XpEntryEntity>>

    @Query("SELECT * FROM xp_ledger WHERE date = :date")
    suspend fun getForDate(date: String): List<XpEntryEntity>

    @Query("SELECT COUNT(*) FROM xp_ledger WHERE date = :date AND reason IN ('TASK_COMPLETED','TOP_PRIORITY_COMPLETED') AND amount > 0")
    suspend fun countRewardedTasksOn(date: String): Int

    @Query("SELECT COUNT(*) FROM xp_ledger WHERE reason = :reason")
    suspend fun countByReason(reason: String): Int

    @Query("SELECT COUNT(*) FROM xp_ledger WHERE eventKey = :eventKey")
    suspend fun exists(eventKey: String): Int

    @Query("DELETE FROM xp_ledger")
    suspend fun deleteAll()
}

@Dao
interface AchievementDao {
    @Query("SELECT * FROM achievement_unlocks ORDER BY unlockedAt DESC")
    fun observeAll(): Flow<List<AchievementUnlockEntity>>

    @Query("SELECT * FROM achievement_unlocks")
    suspend fun getAll(): List<AchievementUnlockEntity>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIfAbsent(entity: AchievementUnlockEntity): Long

    @Query("DELETE FROM achievement_unlocks")
    suspend fun deleteAll()
}

@Dao
interface QuestDao {
    @Query("SELECT * FROM quests WHERE date = :date ORDER BY id")
    fun observeForDate(date: String): Flow<List<QuestEntity>>

    @Query("SELECT * FROM quests WHERE date = :date ORDER BY id")
    suspend fun getForDate(date: String): List<QuestEntity>

    /** Quests are generated once per day; later attempts to insert the same ids are ignored. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIfAbsent(entities: List<QuestEntity>)

    @Upsert
    suspend fun update(entity: QuestEntity)

    @Query("SELECT COUNT(*) FROM quests WHERE status = 'COMPLETED'")
    suspend fun countCompleted(): Int

    @Query("SELECT COUNT(*) FROM quests WHERE status = 'COMPLETED'")
    fun observeCompletedCount(): Flow<Int>

    @Query("DELETE FROM quests")
    suspend fun deleteAll()
}

@Dao
interface JournalDao {
    @Query("SELECT * FROM reflections WHERE date = :date")
    fun observeReflection(date: String): Flow<ReflectionEntity?>

    @Query("SELECT * FROM reflections WHERE date = :date")
    suspend fun getReflection(date: String): ReflectionEntity?

    @Query("SELECT * FROM reflections ORDER BY date DESC")
    fun observeReflections(): Flow<List<ReflectionEntity>>

    @Query("SELECT * FROM reflections")
    suspend fun getReflections(): List<ReflectionEntity>

    @Upsert
    suspend fun upsertReflection(entity: ReflectionEntity)

    @Query("DELETE FROM reflections WHERE date = :date")
    suspend fun deleteReflection(date: String)

    @Query("SELECT * FROM manual_wins WHERE date = :date ORDER BY createdAt")
    fun observeWins(date: String): Flow<List<ManualWinEntity>>

    @Upsert
    suspend fun upsertWin(entity: ManualWinEntity)

    @Query("DELETE FROM manual_wins WHERE id = :id")
    suspend fun deleteWin(id: String)

    @Insert
    suspend fun insertBreak(entity: BreakEntity)

    @Query("SELECT COUNT(*) FROM break_log WHERE date = :date")
    suspend fun countBreaks(date: String): Int

    @Query("SELECT COUNT(*) FROM break_log WHERE date = :date")
    fun observeBreakCount(date: String): Flow<Int>

    @Query("DELETE FROM reflections")
    suspend fun deleteAllReflections()

    @Query("DELETE FROM manual_wins")
    suspend fun deleteAllWins()

    @Query("DELETE FROM break_log")
    suspend fun deleteAllBreaks()
}

@Dao
interface SuggestionDao {
    @Query("SELECT `key` FROM suggestion_dismissals WHERE `until` IS NULL OR `until` > :now")
    suspend fun activeKeys(now: Long): List<String>

    @Upsert
    suspend fun upsert(entity: SuggestionDismissalEntity)

    @Query("DELETE FROM suggestion_dismissals")
    suspend fun deleteAll()
}
