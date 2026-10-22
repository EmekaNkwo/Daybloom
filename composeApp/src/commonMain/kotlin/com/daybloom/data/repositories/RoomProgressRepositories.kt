package com.daybloom.data.repositories

import com.daybloom.data.database.AchievementDao
import com.daybloom.data.database.AchievementUnlockEntity
import com.daybloom.data.database.AppDatabase
import com.daybloom.data.database.BreakEntity
import com.daybloom.data.database.JournalDao
import com.daybloom.data.database.QuestDao
import com.daybloom.data.database.ScoreDao
import com.daybloom.data.database.TransactionRunner
import com.daybloom.data.database.XpDao
import com.daybloom.data.database.toDomain
import com.daybloom.data.database.toEntity
import com.daybloom.data.database.toInstant
import com.daybloom.domain.models.AchievementUnlock
import com.daybloom.domain.models.DailyReflection
import com.daybloom.domain.models.DailyScore
import com.daybloom.domain.models.ManualWin
import com.daybloom.domain.models.Quest
import com.daybloom.domain.models.XpEntry
import com.daybloom.domain.models.XpReason
import com.daybloom.domain.repositories.AchievementRepository
import com.daybloom.domain.repositories.DataResetRepository
import com.daybloom.domain.repositories.JournalRepository
import com.daybloom.domain.repositories.QuestRepository
import com.daybloom.domain.repositories.ScoreRepository
import com.daybloom.domain.repositories.XpRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.datetime.LocalDate
import kotlin.time.Instant

class RoomScoreRepository(private val dao: ScoreDao) : ScoreRepository {
    override fun observe(date: LocalDate): Flow<DailyScore?> = dao.observe(date.toString()).map { it?.toDomain() }
    override fun observeBetween(from: LocalDate, to: LocalDate): Flow<List<DailyScore>> =
        dao.observeBetween(from.toString(), to.toString()).map { list -> list.mapNotNull { it.toDomain() } }
    override suspend fun get(date: LocalDate): DailyScore? = dao.get(date.toString())?.toDomain()
    override suspend fun getBetween(from: LocalDate, to: LocalDate): List<DailyScore> =
        dao.getBetween(from.toString(), to.toString()).mapNotNull { it.toDomain() }
    override suspend fun save(score: DailyScore) = dao.upsert(score.toEntity())
}

class RoomXpRepository(private val dao: XpDao) : XpRepository {
    override suspend fun award(entry: XpEntry): Boolean = dao.insertIfAbsent(entry.toEntity()) != -1L
    override suspend fun revoke(eventKey: String): Boolean = dao.delete(eventKey) > 0
    override fun observeTotal(): Flow<Int> = dao.observeTotal()
    override fun observeRecent(limit: Int): Flow<List<XpEntry>> = dao.observeRecent(limit).map { list -> list.mapNotNull { it.toDomain() } }
    override suspend fun total(): Int = dao.total()
    override suspend fun countRewardedTasksOn(date: LocalDate): Int = dao.countRewardedTasksOn(date.toString())
    override suspend fun countByReason(reason: XpReason): Int = dao.countByReason(reason.name)
    override suspend fun exists(eventKey: String): Boolean = dao.exists(eventKey) > 0
}

class RoomAchievementRepository(private val dao: AchievementDao) : AchievementRepository {
    override fun observeUnlocks(): Flow<List<AchievementUnlock>> =
        dao.observeAll().map { list -> list.map { AchievementUnlock(it.achievementId, it.unlockedAt.toInstant()) } }
    override suspend fun getUnlocks(): List<AchievementUnlock> =
        dao.getAll().map { AchievementUnlock(it.achievementId, it.unlockedAt.toInstant()) }
    override suspend fun unlock(achievementId: String, at: Instant): Boolean =
        dao.insertIfAbsent(AchievementUnlockEntity(achievementId, at.toEpochMilliseconds())) != -1L
}

class RoomQuestRepository(private val dao: QuestDao) : QuestRepository {
    override fun observeForDate(date: LocalDate): Flow<List<Quest>> =
        dao.observeForDate(date.toString()).map { list -> list.mapNotNull { it.toDomain() } }
    override fun observeCompletedCount(): Flow<Int> = dao.observeCompletedCount()
    override suspend fun getForDate(date: LocalDate): List<Quest> = dao.getForDate(date.toString()).mapNotNull { it.toDomain() }
    override suspend fun insertIfAbsent(quests: List<Quest>) = dao.insertIfAbsent(quests.map { it.toEntity() })
    override suspend fun update(quest: Quest) = dao.update(quest.toEntity())
    override suspend fun countCompleted(): Int = dao.countCompleted()
}

class RoomJournalRepository(private val dao: JournalDao) : JournalRepository {
    override fun observeReflection(date: LocalDate): Flow<DailyReflection?> = dao.observeReflection(date.toString()).map { it?.toDomain() }
    override fun observeReflections(): Flow<List<DailyReflection>> = dao.observeReflections().map { list -> list.mapNotNull { it.toDomain() } }
    override fun observeWins(date: LocalDate): Flow<List<ManualWin>> = dao.observeWins(date.toString()).map { list -> list.mapNotNull { it.toDomain() } }
    override fun observeBreakCount(date: LocalDate): Flow<Int> = dao.observeBreakCount(date.toString())
    override suspend fun getReflection(date: LocalDate): DailyReflection? = dao.getReflection(date.toString())?.toDomain()
    override suspend fun getReflections(): List<DailyReflection> = dao.getReflections().mapNotNull { it.toDomain() }
    override suspend fun saveReflection(reflection: DailyReflection) = dao.upsertReflection(reflection.toEntity())
    override suspend fun deleteReflection(date: LocalDate) = dao.deleteReflection(date.toString())
    override suspend fun saveWin(win: ManualWin) = dao.upsertWin(win.toEntity())
    override suspend fun deleteWin(id: String) = dao.deleteWin(id)
    override suspend fun logBreak(id: String, date: LocalDate, at: Instant) =
        dao.insertBreak(BreakEntity(id, date.toString(), at.toEpochMilliseconds()))
    override suspend fun countBreaks(date: LocalDate): Int = dao.countBreaks(date.toString())
}

class RoomDataResetRepository(
    private val db: AppDatabase,
    private val transactions: TransactionRunner,
) : DataResetRepository {
    override suspend fun deleteAllData() = transactions.inTransaction {
        db.taskDao().deleteAll()
        db.appointmentDao().deleteAll()
        db.reminderDao().deleteAllScheduled()
        db.reminderDao().deleteAll()
        db.dailyPlanDao().deleteAll()
        db.habitDao().deleteAll()
        db.focusDao().deleteAll()
        db.scoreDao().deleteAll()
        db.xpDao().deleteAll()
        db.achievementDao().deleteAll()
        db.questDao().deleteAll()
        db.journalDao().deleteAllReflections()
        db.journalDao().deleteAllWins()
        db.journalDao().deleteAllBreaks()
        db.suggestionDao().deleteAll()
    }
}

class RoomSuggestionDismissalStore(private val dao: com.daybloom.data.database.SuggestionDao) :
    com.daybloom.domain.usecases.SuggestionDismissalStore {
    override suspend fun activeKeys(now: Instant): Set<String> = dao.activeKeys(now.toEpochMilliseconds()).toSet()
    override suspend fun dismiss(key: String, until: Instant?, now: Instant) = dao.upsert(
        com.daybloom.data.database.SuggestionDismissalEntity(key, until?.toEpochMilliseconds(), now.toEpochMilliseconds()),
    )
}
