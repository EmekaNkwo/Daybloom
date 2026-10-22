package com.daybloom.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.daybloom.TestDatabases
import com.daybloom.data.database.AppDatabase
import com.daybloom.data.database.ReminderEntity
import com.daybloom.data.database.ScoreSnapshotEntity
import com.daybloom.data.database.TaskEntity
import com.daybloom.data.repositories.RoomDataResetRepository
import com.daybloom.data.repositories.RoomQuestRepository
import com.daybloom.data.repositories.RoomReminderRepository
import com.daybloom.data.repositories.RoomScoreRepository
import com.daybloom.data.repositories.RoomTaskRepository
import com.daybloom.data.repositories.RoomXpRepository
import com.daybloom.data.database.RoomTransactionRunner
import com.daybloom.data.serialization.InvalidDataLog
import com.daybloom.domain.models.DailyScore
import com.daybloom.domain.models.DayClassification
import com.daybloom.domain.models.DimensionScore
import com.daybloom.domain.models.Priority
import com.daybloom.domain.models.RecurrenceRule
import com.daybloom.domain.models.Reminder
import com.daybloom.domain.models.ScoreDimension
import com.daybloom.domain.models.Subtask
import com.daybloom.domain.models.Task
import com.daybloom.domain.models.TaskCategory
import com.daybloom.domain.models.TaskStatus
import com.daybloom.domain.models.XpEntry
import com.daybloom.domain.models.XpReason
import com.daybloom.domain.repositories.ScheduledRecord
import com.daybloom.gamification.QuestCatalog
import com.daybloom.gamification.QuestContext
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime
import org.junit.After
import org.junit.Assert.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import org.junit.Assert.assertNull
import kotlin.test.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import kotlin.time.Instant

@RunWith(AndroidJUnit4::class)
class RoomPersistenceTest {
    @get:Rule val temp = TemporaryFolder()
    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var db: AppDatabase
    private val at = Instant.fromEpochMilliseconds(1_790_000_000_000)
    private val day = LocalDate(2026, 10, 22)

    @Before fun setUp() { db = TestDatabases.inMemory(context) }
    @After fun tearDown() { db.close() }

    private fun task(id: String = "t1") = Task(
        id = id, title = "Prepare presentation", description = "Q4 review", category = TaskCategory.WORK, priority = Priority.HIGH,
        dueDate = day, dueTime = LocalTime(15, 0), estimatedMinutes = 90, recurrence = RecurrenceRule.weekly(DayOfWeek.MONDAY),
        subtasks = listOf(Subtask("s1", "Outline", true, 0), Subtask("s2", "Slides", false, 1)),
        notes = "Bring charts", tags = listOf("q4", "team"), createdAt = at,
    )

    @Test
    fun saveAndRestoreTaskWithSubtasks() = runTest {
        val repo = RoomTaskRepository(db.taskDao())
        repo.save(task())
        assertEquals(task(), repo.get("t1"))
        repo.save(task().copy(subtasks = listOf(Subtask("s3", "Rehearse", false, 0))))
        assertEquals(listOf("Rehearse"), repo.get("t1")?.subtasks?.map { it.title })
        assertEquals(1, repo.observeRelevantTo(day, emptyList()).first().size)
    }

    @Test
    fun completionQueriesUseTheStoredLocalDate() = runTest {
        val repo = RoomTaskRepository(db.taskDao())
        repo.save(task().copy(status = TaskStatus.COMPLETED, completedAt = at, completedOn = day))
        repo.save(task("t2").copy(dueDate = LocalDate(2026, 10, 30)))
        assertEquals(listOf("t1"), repo.getCompletedBetween(day, day).map { it.id })
        assertEquals(setOf(day), repo.completionDates())
        assertEquals(1, repo.countCompleted())
        assertTrue(repo.existsInSeriesOn("t2", LocalDate(2026, 10, 30)))
    }

    @Test
    fun restoreRemindersAndScheduledRecords() = runTest {
        val repo = RoomReminderRepository(db.reminderDao())
        val reminder = Reminder("r1", "Pay rent", startDate = day, times = listOf(LocalTime(9, 0), LocalTime(18, 0)), recurrence = RecurrenceRule.monthly(), timeZoneId = "Europe/London", createdAt = at)
        repo.save(reminder)
        assertEquals(reminder, repo.get("r1"))
        repo.recordScheduled(listOf(ScheduledRecord("r1@1", "r1", at, "abc")), at)
        assertEquals(1, repo.getScheduled().size)
        repo.removeScheduled(listOf("r1@1"))
        assertTrue(repo.getScheduled().isEmpty())
    }

    @Test
    fun scoreHistoryRoundTrips() = runTest {
        val repo = RoomScoreRepository(db.scoreDao())
        val score = DailyScore(day, DayClassification.PLANNED, 78, listOf(DimensionScore(ScoreDimension.TASK_COMPLETION, 85, 0.5, "3 of 4")), at)
        repo.save(score)
        assertEquals(score, repo.get(day))
        assertEquals(listOf(score), repo.getBetween(day, day))
    }

    @Test
    fun xpLedgerIgnoresDuplicateEventKeysAtTheDatabaseLevel() = runTest {
        val repo = RoomXpRepository(db.xpDao())
        val entry = XpEntry("task:t1", 10, XpReason.TASK_COMPLETED, "t1", day, at)
        assertTrue(repo.award(entry))
        assertFalse(repo.award(entry.copy(amount = 999)))
        assertEquals(10, repo.total())
        assertTrue(repo.revoke("task:t1"))
        assertEquals(0, repo.total())
    }

    @Test
    fun questsInsertOnlyOnce() = runTest {
        val repo = RoomQuestRepository(db.questDao())
        val quests = QuestCatalog.generate(day, QuestContext(true, true, true))
        repo.insertIfAbsent(quests)
        repo.insertIfAbsent(quests.map { it.copy(title = "changed") })
        assertEquals(quests, repo.getForDate(day))
    }

    @Test
    fun invalidStoredRecordsAreRecoveredNotFatal() = runTest {
        val before = InvalidDataLog.count()
        db.taskDao().upsertTask(
            TaskEntity(
                id = "bad", title = "", description = "", category = "SPACE", priority = "MEGA", dueDate = "31/02/2026", dueTime = "25:99",
                estimatedMinutes = -5, actualMinutes = null, status = "WHATEVER", recurrenceJson = "{not json", seriesId = "bad", notes = "",
                tagsJson = "nope", isFlexible = true, appointmentId = null, postponeCount = -1, createdAt = 0, updatedAt = 0,
                completedAt = null, completedOn = null, cancelledAt = null,
            ),
        )
        val recovered = assertNotNull(RoomTaskRepository(db.taskDao()).get("bad"))
        assertEquals("Untitled task", recovered.title)
        assertEquals(TaskCategory.OTHER, recovered.category)
        assertEquals(Priority.NORMAL, recovered.priority)
        assertEquals(TaskStatus.OPEN, recovered.status)
        assertNull(recovered.dueDate)
        assertNull(recovered.recurrence)
        assertNull(recovered.estimatedMinutes)
        assertTrue(recovered.tags.isEmpty())
        assertEquals(0, recovered.postponeCount)

        db.reminderDao().upsert(ReminderEntity("r-bad", "x", "", "CUSTOM", null, "GENERAL", "2026-10-22", "[\"nope\"]", null, null, null, true, "ACTIVE", null, null, 0, 0))
        assertTrue(RoomReminderRepository(db.reminderDao()).getAll().isEmpty(), "a reminder without valid times is skipped, not crashed on")

        db.scoreDao().upsert(ScoreSnapshotEntity("2026-10-22", "PLANNED", 150, "{broken", 0))
        val score = assertNotNull(RoomScoreRepository(db.scoreDao()).get(day))
        assertEquals(100, score.overall)
        assertTrue(score.dimensions.isEmpty())
        assertTrue(InvalidDataLog.count() > before)
    }

    @Test
    fun resetDeletesAllUserData() = runTest {
        RoomTaskRepository(db.taskDao()).save(task())
        RoomXpRepository(db.xpDao()).award(XpEntry("k", 10, XpReason.TASK_COMPLETED, null, day, at))
        RoomDataResetRepository(db, RoomTransactionRunner(db)).deleteAllData()
        assertNull(RoomTaskRepository(db.taskDao()).get("t1"))
        assertEquals(0, RoomXpRepository(db.xpDao()).total())
    }

    @Test
    fun dataSurvivesClosingAndReopeningTheDatabase() = runTest {
        val file = temp.newFile("restart.db").also { it.delete() }
        val first = TestDatabases.onDisk(context, file)
        RoomTaskRepository(first.taskDao()).save(task())
        RoomXpRepository(first.xpDao()).award(XpEntry("task:t1", 10, XpReason.TASK_COMPLETED, "t1", day, at))
        first.close()
        val reopened = TestDatabases.onDisk(context, file)
        assertEquals(task(), RoomTaskRepository(reopened.taskDao()).get("t1"))
        assertEquals(10, RoomXpRepository(reopened.xpDao()).total())
        reopened.close()
    }
}
