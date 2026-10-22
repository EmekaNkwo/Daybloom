package com.daybloom.notifications

import android.Manifest
import android.app.AlarmManager
import android.app.Application
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.WorkManagerTestInitHelper
import com.daybloom.TestDatabases
import com.daybloom.domain.models.NotificationCategory
import com.daybloom.domain.models.TaskStatus
import com.daybloom.domain.repositories.ReminderRepository
import com.daybloom.domain.repositories.TaskRepository
import com.daybloom.domain.usecases.CreateTaskUseCase
import com.daybloom.domain.usecases.TaskDraft
import com.daybloom.domain.usecases.TaskResult
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.koin.core.context.stopKoin
import org.koin.mp.KoinPlatform
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAlarmManager
import kotlin.time.Clock
import kotlin.time.Duration.Companion.hours

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class AndroidNotificationsTest {
    @get:Rule val temp = TemporaryFolder()
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val alarms get() = context.getSystemService(AlarmManager::class.java)
    private val shadowAlarms get() = shadowOf(alarms)

    @After fun tearDown() { stopKoin() }

    private fun request(id: String = "r1@1", inHours: Int = 2) =
        NotificationRequest(id, "r1", Clock.System.now() + inHours.hours, "Pay rent", "Due now", NotificationCategory.BILLS)

    @Test
    fun channelsAreCreatedForEveryCategory() {
        NotificationChannels.ensure(context)
        val manager = context.getSystemService(NotificationManager::class.java)
        assertEquals(NotificationCategory.entries.map { it.channelId }.toSet(), manager.notificationChannels.map { it.id }.toSet())
    }

    @Test
    fun schedulingIsIdempotentAndCancelable() = runTest {
        ShadowAlarmManager.setCanScheduleExactAlarms(true)
        val scheduler = AndroidNotificationScheduler(context)
        val req = request()
        scheduler.schedule(listOf(req))
        scheduler.schedule(listOf(req))
        assertEquals("same id replaces instead of duplicating", 1, shadowAlarms.scheduledAlarms.size)
        assertEquals(req.fireAt.toEpochMilliseconds(), shadowAlarms.scheduledAlarms.single().triggerAtTime)
        assertEquals(setOf(req.id), scheduler.pendingIds(listOf(req.id, "other")))
        scheduler.cancel(listOf(req.id))
        assertTrue(shadowAlarms.scheduledAlarms.isEmpty())
        assertTrue(scheduler.pendingIds(listOf(req.id)).isEmpty())
    }

    @Test
    fun exactAlarmCapabilityIsReportedHonestly() = runTest {
        val scheduler = AndroidNotificationScheduler(context)
        ShadowAlarmManager.setCanScheduleExactAlarms(false)
        val restricted = scheduler.capabilities()
        assertFalse(restricted.exactTiming)
        assertNotNull(restricted.limitation)
        scheduler.schedule(listOf(request()))
        assertEquals("falls back to an inexact alarm instead of failing", 1, shadowAlarms.scheduledAlarms.size)
        ShadowAlarmManager.setCanScheduleExactAlarms(true)
        assertTrue(scheduler.capabilities().exactTiming)
    }

    @Test
    @Config(sdk = [33])
    fun permissionStatesOnAndroid13() = runTest {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val manager = AndroidNotificationPermissionManager(context)
        shadowOf(app).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)
        context.getSharedPreferences("daybloom_permissions", Context.MODE_PRIVATE).edit().clear().commit()
        assertEquals(PermissionState.NOT_DETERMINED, manager.status().permission)
        AndroidNotificationPermissionManager.markAsked(context)
        assertEquals(PermissionState.DENIED, manager.status().permission)
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        assertEquals(PermissionState.GRANTED, manager.status().permission)
        assertTrue(Build.VERSION.SDK_INT == 33)
    }

    @Test
    fun alarmReceiverPostsNotificationWithDoneAndSnoozeActions() {
        TestDatabases.startTestKoin(context, temp.root)
        shadowOf(ApplicationProvider.getApplicationContext<Application>()).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        val intent = Intent(context, ReminderAlarmReceiver::class.java)
            .setAction(NotificationContract.ACTION_FIRE)
            .putExtra(NotificationContract.EXTRA_ID, "r1@1")
            .putExtra(NotificationContract.EXTRA_REMINDER_ID, "r1")
            .putExtra(NotificationContract.EXTRA_FIRE_AT, System.currentTimeMillis())
            .putExtra(NotificationContract.EXTRA_TITLE, "Pay rent")
            .putExtra(NotificationContract.EXTRA_BODY, "Due now")
            .putExtra(NotificationContract.EXTRA_CATEGORY, NotificationCategory.BILLS.name)
        ReminderAlarmReceiver().onReceive(context, intent)
        val posted = shadowOf(context.getSystemService(NotificationManager::class.java)).allNotifications.single()
        assertEquals("Pay rent", shadowOf(posted).contentTitle)
        assertEquals(listOf("Done", "Snooze 10 min"), posted.actions.map { it.title.toString() })
        assertEquals(NotificationCategory.BILLS.channelId, posted.channelId)
    }

    @Test
    fun doneActionCompletesTheTaskThroughSharedLogic() {
        TestDatabases.startTestKoin(context, temp.root)
        val koin = KoinPlatform.getKoin()
        val task = runBlocking {
            val result = koin.get<CreateTaskUseCase>()(
                TaskDraft("Call Mum", dueDate = LocalDate(2030, 1, 1), dueTime = LocalTime(10, 0), reminderOffsetsMinutes = listOf(0)),
            )
            (result as TaskResult.Success).task
        }
        val reminder = runBlocking { koin.get<ReminderRepository>().getAll().single() }
        val intent = Intent(context, NotificationActionReceiver::class.java)
            .setAction(NotificationContract.ACTION_RESPOND)
            .putExtra(NotificationContract.EXTRA_ID, "${reminder.id}@1")
            .putExtra(NotificationContract.EXTRA_REMINDER_ID, reminder.id)
            .putExtra(NotificationContract.EXTRA_FIRE_AT, System.currentTimeMillis())
            .putExtra(NotificationContract.EXTRA_ACTION, "DONE")
        NotificationActionReceiver().onReceive(context, intent)
        val completed = waitFor { runBlocking { koin.get<TaskRepository>().get(task.id) }?.status == TaskStatus.COMPLETED }
        assertTrue("the task is completed by the notification action", completed)
    }

    @Test
    fun bootAndTimeChangesEnqueueReconciliation() {
        WorkManagerTestInitHelper.initializeTestWorkManager(context)
        SystemEventReceiver().onReceive(context, Intent(Intent.ACTION_BOOT_COMPLETED))
        val boot = WorkManager.getInstance(context).getWorkInfosForUniqueWork("daybloom-reconcile").get()
        assertEquals(1, boot.size)
        SystemEventReceiver().onReceive(context, Intent(Intent.ACTION_TIMEZONE_CHANGED))
        val afterTz = WorkManager.getInstance(context).getWorkInfosForUniqueWork("daybloom-reconcile").get()
        assertTrue("a newer event replaces the pending one", afterTz.count { it.state == WorkInfo.State.ENQUEUED } == 1)
        SystemEventReceiver().onReceive(context, Intent("some.other.ACTION"))
        assertEquals(afterTz.size, WorkManager.getInstance(context).getWorkInfosForUniqueWork("daybloom-reconcile").get().size)
    }

    private fun waitFor(timeoutMs: Long = 5_000, condition: () -> Boolean): Boolean {
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) {
            org.robolectric.shadows.ShadowLooper.idleMainLooper()
            if (condition()) return true
            Thread.sleep(50)
        }
        return condition()
    }
}
