package com.daybloom

import android.Manifest
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.daybloom.data.database.AppDatabase
import com.daybloom.data.database.buildDayBloomDatabase
import com.daybloom.data.repositories.RoomTaskRepository
import com.daybloom.domain.models.NotificationCategory
import com.daybloom.domain.models.Task
import com.daybloom.notifications.AndroidNotificationPermissionManager
import com.daybloom.notifications.AndroidNotificationScheduler
import com.daybloom.notifications.NotificationChannels
import com.daybloom.notifications.NotificationRequest
import com.daybloom.notifications.PermissionState
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.LocalDate
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.time.Clock
import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant

/**
 * Runs on a real Android device or emulator: the production SQLite driver, real AlarmManager PendingIntents,
 * real notification channels and posting, and the runtime notification permission.
 */
@RunWith(AndroidJUnit4::class)
class DeviceIntegrationTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context: Context = instrumentation.targetContext
    private val dbName = "device-test-${System.nanoTime()}.db"

    @After
    fun cleanUp() {
        context.deleteDatabase(dbName)
    }

    /** Grant only: revoking a runtime permission kills the process (the denied path is covered by Robolectric tests). */
    private fun grantNotifications() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        instrumentation.uiAutomation.grantRuntimePermission(context.packageName, Manifest.permission.POST_NOTIFICATIONS)
    }

    @Test
    fun bundledSqliteDatabasePersistsAcrossReopen() = runBlocking {
        val path = context.getDatabasePath(dbName).absolutePath
        val task = Task("t1", "Persist me", dueDate = LocalDate(2026, 10, 22), createdAt = Instant.fromEpochMilliseconds(0))
        val first = Room.databaseBuilder<AppDatabase>(context, path).buildDayBloomDatabase()
        RoomTaskRepository(first.taskDao()).save(task)
        first.close()
        val reopened = Room.databaseBuilder<AppDatabase>(context, path).buildDayBloomDatabase()
        assertEquals(task, RoomTaskRepository(reopened.taskDao()).get("t1"))
        reopened.close()
    }

    @Test
    fun alarmsAreRegisteredAndCancelledWithTheSystem() = runBlocking {
        val scheduler = AndroidNotificationScheduler(context)
        val id = "device-${System.nanoTime()}@1"
        val request = NotificationRequest(id, "device", Clock.System.now() + 3.hours, "Device test", "", NotificationCategory.GENERAL)
        scheduler.schedule(listOf(request))
        scheduler.schedule(listOf(request))
        assertEquals(setOf(id), scheduler.pendingIds(listOf(id)))
        scheduler.cancel(listOf(id))
        assertTrue(scheduler.pendingIds(listOf(id)).isEmpty())
    }

    @Test
    fun channelsAndPostedNotificationsAreVisibleToTheSystem() = runBlocking {
        grantNotifications()
        NotificationChannels.ensure(context)
        val manager = context.getSystemService(NotificationManager::class.java)
        assertTrue(NotificationCategory.entries.all { manager.getNotificationChannel(it.channelId) != null })

        val id = "device-post-${System.nanoTime()}"
        AndroidNotificationScheduler(context).showNow(
            NotificationRequest(id, "device", Clock.System.now(), "Shown now", "From the device test", NotificationCategory.TASKS),
        )
        Thread.sleep(500)
        val posted = manager.activeNotifications.firstOrNull { it.tag == id }
        assertTrue("notification is posted with its occurrence tag", posted != null)
        assertEquals(listOf("Done", "Snooze 10 min"), posted?.notification?.actions?.map { it.title.toString() })
        AndroidNotificationScheduler(context).cancel(listOf(id))
    }

    @Test
    fun grantedPermissionIsReportedFromTheSystem() = runBlocking {
        grantNotifications()
        assertEquals(PermissionState.GRANTED, AndroidNotificationPermissionManager(context).status().permission)
    }
}
