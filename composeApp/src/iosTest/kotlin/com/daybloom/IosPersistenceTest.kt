package com.daybloom

import androidx.room.Room
import com.daybloom.data.database.AppDatabase
import com.daybloom.data.database.buildDayBloomDatabase
import com.daybloom.data.repositories.RoomTaskRepository
import com.daybloom.data.repositories.RoomXpRepository
import com.daybloom.domain.models.Priority
import com.daybloom.domain.models.Subtask
import com.daybloom.domain.models.Task
import com.daybloom.domain.models.XpEntry
import com.daybloom.domain.models.XpReason
import com.daybloom.platform.IosDateTimeService
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDate
import platform.Foundation.NSFileManager
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSUUID
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Instant

/** Runs the production Room setup (bundled SQLite driver, file database) on iOS. */
class IosPersistenceTest {
    private val path = NSTemporaryDirectory() + "daybloom-test-${NSUUID().UUIDString}.db"

    @AfterTest
    fun cleanUp() {
        NSFileManager.defaultManager.removeItemAtPath(path, null)
    }

    private fun open(): AppDatabase = Room.databaseBuilder<AppDatabase>(name = path).buildDayBloomDatabase()

    @Test
    fun tasksAndXpSurviveReopeningTheDatabase() = runTest {
        val task = Task(
            "t1", "Prepare presentation", priority = Priority.HIGH, dueDate = LocalDate(2026, 10, 22),
            subtasks = listOf(Subtask("s1", "Slides", false, 0)), createdAt = Instant.fromEpochMilliseconds(0),
        )
        val first = open()
        RoomTaskRepository(first.taskDao()).save(task)
        val xp = RoomXpRepository(first.xpDao())
        assertTrue(xp.award(XpEntry("task:t1", 10, XpReason.TASK_COMPLETED, "t1", LocalDate(2026, 10, 22), Instant.fromEpochMilliseconds(0))))
        assertFalse(xp.award(XpEntry("task:t1", 10, XpReason.TASK_COMPLETED, "t1", LocalDate(2026, 10, 22), Instant.fromEpochMilliseconds(0))))
        first.close()

        val reopened = open()
        assertEquals(task, RoomTaskRepository(reopened.taskDao()).get("t1"))
        assertEquals(10, RoomXpRepository(reopened.xpDao()).total())
        reopened.close()
    }

    @Test
    fun monotonicClockMovesForwardAndZoneIsAvailable() {
        val clock = IosDateTimeService()
        val a = clock.monotonicMillis()
        val b = clock.monotonicMillis()
        assertTrue(b >= a)
        assertTrue(a > 0)
        assertTrue(clock.timeZone().id.isNotEmpty())
    }
}
