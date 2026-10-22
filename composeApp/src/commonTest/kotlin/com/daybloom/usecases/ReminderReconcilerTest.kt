package com.daybloom.usecases

import com.daybloom.domain.models.RecurrenceRule
import com.daybloom.domain.models.ReminderStatus
import com.daybloom.domain.usecases.NotificationAction
import com.daybloom.domain.usecases.ReminderDraft
import com.daybloom.domain.usecases.ScheduleReminderUseCase
import com.daybloom.domain.usecases.TaskDraft
import com.daybloom.domain.usecases.TaskResult
import com.daybloom.notifications.PermissionState
import com.daybloom.notifications.ReconcileReason
import com.daybloom.notifications.SchedulerCapabilities
import com.daybloom.testing.TestGraph
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes

class ReminderReconcilerTest {
    private val today = LocalDate(2026, 10, 22)

    private suspend fun TestGraph.daily(time: LocalTime = LocalTime(18, 0)) =
        assertIs<ScheduleReminderUseCase.Result.Saved>(scheduleReminder(ReminderDraft("Stretch", startDate = today, times = listOf(time), recurrence = RecurrenceRule.daily()))).reminder

    @Test
    fun `schedules a rolling window and re-running is a no-op`() = runTest {
        val g = TestGraph()
        g.daily()
        assertEquals(14, g.scheduler.pending.size)
        val calls = g.scheduler.scheduleCalls.size
        val again = g.reconciler.reconcile(ReconcileReason.APP_START)
        assertEquals(0, again.scheduled)
        assertEquals(calls, g.scheduler.scheduleCalls.size, "nothing re-sent when unchanged")
        assertEquals(14, g.scheduler.pending.size)
    }

    @Test
    fun `editing a reminder cancels stale deliveries and schedules new ones without duplicates`() = runTest {
        val g = TestGraph()
        val r = g.daily(LocalTime(18, 0))
        g.scheduleReminder(ReminderDraft("Stretch", startDate = today, times = listOf(LocalTime(19, 0)), recurrence = RecurrenceRule.daily()), r.id)
        val times = g.scheduler.pending.values.map { it.fireAt.toLocalDateTime(g.clock.zone).time }.toSet()
        assertEquals(setOf(LocalTime(19, 0)), times)
        assertEquals(g.scheduler.pending.keys, g.store.scheduled.value.keys, "persisted record matches the OS")
    }

    @Test
    fun `respects the platform pending limit earliest first`() = runTest {
        val g = TestGraph(scheduler = com.daybloom.testing.FakeNotificationScheduler(SchedulerCapabilities(maxPending = 5, exactTiming = true)))
        g.daily()
        assertEquals(5, g.scheduler.pending.size)
        assertEquals(LocalDate(2026, 10, 22), g.scheduler.pending.values.minOf { it.fireAt }.toLocalDateTime(g.clock.zone).date)
    }

    @Test
    fun `system reset reschedules everything the OS dropped`() = runTest {
        val g = TestGraph()
        g.daily()
        g.scheduler.wipe()
        g.reconciler.reconcile(ReconcileReason.SYSTEM_RESET)
        assertEquals(14, g.scheduler.pending.size)
    }

    @Test
    fun `app start notices alarms missing from the OS`() = runTest {
        val g = TestGraph()
        g.daily()
        g.scheduler.wipe()
        val result = g.reconciler.reconcile(ReconcileReason.APP_START)
        assertEquals(14, result.scheduled)
    }

    @Test
    fun `time zone change recomputes wall-clock deliveries`() = runTest {
        val g = TestGraph()
        g.daily(LocalTime(18, 0))
        g.clock.zone = TimeZone.of("America/New_York")
        g.reconciler.reconcile(ReconcileReason.TIME_CHANGED)
        val local = g.scheduler.pending.values.map { it.fireAt.toLocalDateTime(g.clock.zone).time }.toSet()
        assertEquals(setOf(LocalTime(18, 0)), local)
    }

    @Test
    fun `without permission nothing is scheduled and a later grant schedules everything`() = runTest {
        val g = TestGraph()
        g.permissions.state = PermissionState.DENIED
        g.daily()
        assertTrue(g.scheduler.pending.isEmpty())
        assertTrue(g.reconciler.status.value.lastResult?.permissionGranted == false)
        g.permissions.state = PermissionState.GRANTED
        g.reconciler.reconcile(ReconcileReason.PERMISSION_CHANGED)
        assertEquals(14, g.scheduler.pending.size)
    }

    @Test
    fun `orphaned OS notifications are cancelled`() = runTest {
        val g = TestGraph()
        g.daily()
        g.reset()
        assertTrue(g.scheduler.pending.isEmpty(), "resetting data cancels every DayBloom notification")
    }

    @Test
    fun `only DayBloom occurrence ids are treated as orphans`() = runTest {
        val g = TestGraph()
        g.scheduler.schedule(listOf(com.daybloom.notifications.NotificationRequest("test-notification", "x", g.clock.now(), "t", "b", com.daybloom.domain.models.NotificationCategory.GENERAL)))
        g.scheduler.schedule(listOf(com.daybloom.notifications.NotificationRequest("gone@1800000000000", "gone", g.clock.now(), "t", "b", com.daybloom.domain.models.NotificationCategory.GENERAL)))
        g.reconciler.reconcile(ReconcileReason.APP_START)
        assertEquals(setOf("test-notification"), g.scheduler.pending.keys)
    }

    @Test
    fun `task reminders follow the task and stop once it's done`() = runTest {
        val g = TestGraph()
        val task = assertIs<TaskResult.Success>(g.createTask(TaskDraft("Pay rent", dueDate = LocalDate(2026, 10, 23), dueTime = LocalTime(9, 0), reminderOffsetsMinutes = listOf(0, 60)))).task
        assertEquals(setOf(LocalTime(8, 0), LocalTime(9, 0)), g.scheduler.pending.values.map { it.fireAt.toLocalDateTime(g.clock.zone).time }.toSet())
        g.reschedule(listOf(task.id), LocalDate(2026, 10, 24))
        assertTrue(g.scheduler.pending.values.all { it.fireAt.toLocalDateTime(g.clock.zone).date == LocalDate(2026, 10, 24) })
        g.completeTask(task.id)
        assertTrue(g.scheduler.pending.isEmpty())
        assertTrue(g.store.reminders.value.values.all { it.status == ReminderStatus.COMPLETED })
    }

    @Test
    fun `notification done action completes the task snooze reschedules`() = runTest {
        val g = TestGraph()
        val task = assertIs<TaskResult.Success>(g.createTask(TaskDraft("Call Mum", dueDate = today, dueTime = LocalTime(10, 0), reminderOffsetsMinutes = listOf(0)))).task
        val reminder = g.store.reminders.value.values.single()
        val firing = g.scheduler.pending.values.single()
        val fire = firing.fireAt
        g.clock.setLocal(LocalDateTime(today, LocalTime(10, 0)))
        g.scheduler.pending.remove(firing.id) // the OS consumed the alarm when it fired
        g.handleReminderAction(reminder.id, fire, NotificationAction.SNOOZE)
        val snoozed = g.scheduler.pending.values.single().fireAt
        assertEquals(fire + 10.minutes, snoozed)
        g.handleReminderAction(reminder.id, snoozed, NotificationAction.DONE)
        assertEquals(com.daybloom.domain.models.TaskStatus.COMPLETED, g.tasks.get(task.id)?.status)
        assertTrue(g.scheduler.pending.isEmpty())
    }

    @Test
    fun `appointment reminders ignore quiet hours so early appointments still get a nudge`() = runTest {
        val g = TestGraph()
        g.saveAppointment(
            com.daybloom.domain.usecases.AppointmentDraft("Early flight", date = LocalDate(2026, 10, 23), startTime = LocalTime(6, 30), endTime = LocalTime(7, 30), reminderOffsetsMinutes = listOf(60)),
        )
        val fire = g.scheduler.pending.values.single().fireAt.toLocalDateTime(g.clock.zone)
        assertEquals(LocalTime(5, 30), fire.time)
    }

    @Test
    fun `cancelling an appointment clears its notifications and restoring brings them back`() = runTest {
        val g = TestGraph()
        val appt = (
            g.saveAppointment(
                com.daybloom.domain.usecases.AppointmentDraft("Dentist", date = LocalDate(2026, 10, 23), startTime = LocalTime(18, 0), endTime = LocalTime(19, 0), reminderOffsetsMinutes = listOf(30)),
            ) as com.daybloom.domain.usecases.SaveAppointmentUseCase.Result.Saved
            ).appointment
        assertEquals(1, g.scheduler.pending.size)

        g.cancelAppointment(appt.id)
        assertEquals(com.daybloom.domain.models.AppointmentStatus.CANCELLED, g.appointments.get(appt.id)?.status)
        assertTrue(g.scheduler.pending.isEmpty(), "a cancelled appointment doesn't notify")

        g.cancelAppointment.restore(appt.id)
        assertEquals(com.daybloom.domain.models.AppointmentStatus.SCHEDULED, g.appointments.get(appt.id)?.status)
        assertEquals(1, g.scheduler.pending.size, "restoring reschedules its reminder")
    }

    @Test
    fun `a one-time reminder whose time has already passed is rejected instead of silently never firing`() = runTest {
        val g = TestGraph() // clock: 09:00
        val past = g.scheduleReminder(ReminderDraft("Bins", startDate = today, times = listOf(LocalTime(8, 0))))
        assertTrue(assertIs<ScheduleReminderUseCase.Result.Invalid>(past).errors.containsKey("times"))

        assertIs<ScheduleReminderUseCase.Result.Saved>(g.scheduleReminder(ReminderDraft("Bins", startDate = today, times = listOf(LocalTime(10, 0)))))
        assertIs<ScheduleReminderUseCase.Result.Saved>(
            g.scheduleReminder(ReminderDraft("Stretch", startDate = today, times = listOf(LocalTime(8, 0)), recurrence = RecurrenceRule.daily())),
            "repeating reminders still have future occurrences",
        )
    }

    @Test
    fun `editing an old reminder without changing its schedule still saves`() = runTest {
        val g = TestGraph()
        val saved = assertIs<ScheduleReminderUseCase.Result.Saved>(
            g.scheduleReminder(ReminderDraft("Bins", startDate = today, times = listOf(LocalTime(10, 0)))),
        ).reminder
        g.clock.setLocal(LocalDateTime(2026, 10, 22, 11, 0))
        assertIs<ScheduleReminderUseCase.Result.Saved>(
            g.scheduleReminder(ReminderDraft("Bins", body = "Blue bin this week", startDate = today, times = listOf(LocalTime(10, 0))), saved.id),
        )
    }

    @Test
    fun `a deleted reminder can be restored with its schedule`() = runTest {
        val g = TestGraph()
        val r = g.daily()
        val scheduled = g.scheduler.pending.size
        g.deleteReminder(r.id)
        assertTrue(g.scheduler.pending.isEmpty())
        g.deleteReminder.restore(r)
        assertEquals(r, g.reminders.get(r.id))
        assertEquals(scheduled, g.scheduler.pending.size)
    }

    @Test
    fun `a delivered notification is not cancelled when the reminder is processed afterwards`() = runTest {
        val g = TestGraph() // clock 09:00
        val r = g.daily(LocalTime(10, 0))
        val fired = g.scheduler.pending.values.minBy { it.fireAt }
        // The OS fires the alarm (consuming it) and the app posts the notification, then records the delivery.
        g.clock.setLocal(LocalDateTime(2026, 10, 22, 10, 0, 30))
        g.scheduler.pending.remove(fired.id)
        g.handleReminderAction(r.id, fired.fireAt, NotificationAction.DELIVERED)
        assertTrue(fired.id !in g.scheduler.removedDelivered, "a fired occurrence's notification must stay until the person acts on it")
    }

    @Test
    fun `deleting a reminder cancels a still-waiting past alarm without removing a shown notification`() = runTest {
        val g = TestGraph()
        val r = g.daily(LocalTime(10, 0))
        val lagging = g.scheduler.pending.values.minBy { it.fireAt }
        // Past its time but the inexact alarm hasn't fired yet; then the reminder is deleted.
        g.clock.setLocal(LocalDateTime(2026, 10, 22, 10, 5))
        g.deleteReminder(r.id)
        assertTrue(lagging.id !in g.scheduler.pending, "the waiting alarm is cancelled")
        assertTrue(lagging.id !in g.scheduler.removedDelivered, "but nothing on screen is removed")
    }
}
