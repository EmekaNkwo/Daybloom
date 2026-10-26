package com.daybloom.usecases

import com.daybloom.domain.models.RecurrenceRule
import com.daybloom.domain.models.UserPreferences
import com.daybloom.domain.usecases.ReminderDraft
import com.daybloom.features.reminders.OnTimeRemindersPrompt
import com.daybloom.notifications.ExactTimingState
import com.daybloom.notifications.NotificationStatus
import com.daybloom.notifications.PermissionState
import com.daybloom.notifications.ReconcileReason
import com.daybloom.notifications.SchedulerCapabilities
import com.daybloom.testing.FakeNotificationScheduler
import com.daybloom.testing.TestGraph
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class OnTimeRemindersTest {
    private val restricted = NotificationStatus(PermissionState.GRANTED, ExactTimingState.RESTRICTED)
    private val digestsOff = UserPreferences(morningDigestTime = null, eveningDigestTime = null)

    @Test
    fun `granting exact alarms re-sets alarms that were scheduled inexactly`() = runTest {
        val scheduler = FakeNotificationScheduler(SchedulerCapabilities(maxPending = 64, exactTiming = false))
        val g = TestGraph(scheduler = scheduler)
        g.scheduleReminder(ReminderDraft("Stretch", startDate = LocalDate(2026, 10, 22), times = listOf(LocalTime(18, 0)), recurrence = RecurrenceRule.daily()))
        assertEquals(14, scheduler.pending.size)

        scheduler.caps = scheduler.caps.copy(exactTiming = true)
        val result = g.reconciler.reconcile(ReconcileReason.APP_START)

        assertEquals(14, result.scheduled, "every pending alarm is handed to the OS again, now as exact")
        assertEquals(0, g.reconciler.reconcile(ReconcileReason.APP_START).scheduled, "and it settles afterwards")
    }

    @Test
    fun `prompt shows when reminders would be late and there is something to remind about`() {
        assertTrue(OnTimeRemindersPrompt.shouldShow(restricted, UserPreferences(), hasActiveReminders = false), "digests are on by default")
        assertTrue(OnTimeRemindersPrompt.shouldShow(restricted, digestsOff, hasActiveReminders = true))
    }

    @Test
    fun `prompt stays hidden when it can't help or was dismissed`() {
        val available = restricted.copy(exactTiming = ExactTimingState.AVAILABLE)
        val notApplicable = restricted.copy(exactTiming = ExactTimingState.NOT_APPLICABLE)
        val denied = restricted.copy(permission = PermissionState.DENIED)
        assertFalse(OnTimeRemindersPrompt.shouldShow(null, UserPreferences(), hasActiveReminders = true), "status unknown yet")
        assertFalse(OnTimeRemindersPrompt.shouldShow(available, UserPreferences(), hasActiveReminders = true))
        assertFalse(OnTimeRemindersPrompt.shouldShow(notApplicable, UserPreferences(), hasActiveReminders = true), "iOS")
        assertFalse(OnTimeRemindersPrompt.shouldShow(denied, UserPreferences(), hasActiveReminders = true), "notifications themselves are off")
        assertFalse(OnTimeRemindersPrompt.shouldShow(restricted, digestsOff, hasActiveReminders = false), "nothing to deliver")
        assertFalse(OnTimeRemindersPrompt.shouldShow(restricted, UserPreferences(exactAlarmPromptDismissed = true), hasActiveReminders = true))
    }
}
