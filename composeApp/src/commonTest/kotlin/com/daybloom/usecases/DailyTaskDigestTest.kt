package com.daybloom.usecases

import com.daybloom.domain.models.NotificationCategory
import com.daybloom.domain.usecases.NotificationAction
import com.daybloom.domain.usecases.TaskDraft
import com.daybloom.domain.usecases.TaskResult
import com.daybloom.notifications.DailyTaskDigest
import com.daybloom.notifications.NotificationRequest
import com.daybloom.notifications.ReconcileReason
import com.daybloom.testing.TestGraph
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.datetime.toLocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DailyTaskDigestTest {
    private val today = LocalDate(2026, 10, 22) // the fake clock starts at 09:00 on this day
    private val tomorrow = LocalDate(2026, 10, 23)
    private val morning = LocalTime(8, 0)
    private val evening = LocalTime(20, 0)

    private suspend fun digestGraph(): TestGraph = TestGraph().also { g ->
        g.preferences.update { it.copy(morningDigestTime = morning, eveningDigestTime = evening) }
    }

    private suspend fun TestGraph.task(title: String, due: LocalDate?, time: LocalTime? = null) =
        assertIs<TaskResult.Success>(createTask(TaskDraft(title, dueDate = due, dueTime = time))).task

    private fun TestGraph.digests(reminderId: String): List<NotificationRequest> =
        scheduler.pending.values.filter { it.reminderId == reminderId }.sortedBy { it.fireAt }

    private fun TestGraph.localOf(request: NotificationRequest): LocalDateTime = request.fireAt.toLocalDateTime(clock.zone)

    @Test
    fun `tasks set ahead for a day get a morning heads-up on that day`() = runTest {
        val g = digestGraph()
        g.task("Pay rent", tomorrow)
        g.task("Call Mum", tomorrow, LocalTime(14, 0))

        val morningDigest = g.digests(DailyTaskDigest.MORNING_ID).single()
        assertEquals(LocalDateTime(tomorrow, morning), g.localOf(morningDigest))
        assertEquals("2 tasks planned for today", morningDigest.title)
        assertTrue("Call Mum" in morningDigest.body && "Pay rent" in morningDigest.body, morningDigest.body)
        assertEquals(NotificationCategory.DIGEST, morningDigest.category)
    }

    @Test
    fun `a morning digest is never scheduled in the past or for tasks added on the day`() = runTest {
        val g = digestGraph()
        g.task("Water plants", today) // created today at 09:00, after the 08:00 digest anyway
        assertTrue(g.digests(DailyTaskDigest.MORNING_ID).isEmpty())
    }

    @Test
    fun `open tasks for a day get an end-of-day nudge listing what's left`() = runTest {
        val g = digestGraph()
        g.task("Pay rent", today)
        g.task("Call Mum", today)

        val eveningDigest = g.digests(DailyTaskDigest.EVENING_ID).single()
        assertEquals(LocalDateTime(today, evening), g.localOf(eveningDigest))
        assertEquals("2 tasks still open today", eveningDigest.title)
    }

    @Test
    fun `completing tasks updates the evening nudge and finishing everything withdraws it`() = runTest {
        val g = digestGraph()
        val rent = g.task("Pay rent", today)
        val mum = g.task("Call Mum", today)

        g.completeTask(rent.id)
        val updated = g.digests(DailyTaskDigest.EVENING_ID).single()
        assertEquals("1 task still open today", updated.title)
        assertEquals("Call Mum", updated.body.substringBefore(" ·"))

        g.completeTask(mum.id)
        assertTrue(g.digests(DailyTaskDigest.EVENING_ID).isEmpty(), "nothing left to nag about")
    }

    @Test
    fun `moving a task to another day moves its digests too`() = runTest {
        val g = digestGraph()
        val rent = g.task("Pay rent", today)
        g.reschedule(listOf(rent.id), LocalDate(2026, 10, 25))

        val evenings = g.digests(DailyTaskDigest.EVENING_ID)
        assertEquals(listOf(LocalDate(2026, 10, 25)), evenings.map { g.localOf(it).date })
        assertEquals(listOf(LocalDate(2026, 10, 25)), g.digests(DailyTaskDigest.MORNING_ID).map { g.localOf(it).date })
    }

    @Test
    fun `long lists are summarised`() = runTest {
        val g = digestGraph()
        listOf("A", "B", "C", "D", "E").forEach { g.task(it, tomorrow) }
        val body = g.digests(DailyTaskDigest.MORNING_ID).single().body
        assertEquals("A, B, C and 2 more", body)
    }

    @Test
    fun `undated tasks and far-future tasks don't produce digests`() = runTest {
        val g = digestGraph()
        g.task("Someday", null)
        g.task("Next month", LocalDate(2026, 11, 22))
        assertTrue(g.scheduler.pending.isEmpty())
    }

    @Test
    fun `turning digests off cancels them`() = runTest {
        val g = digestGraph()
        g.task("Pay rent", tomorrow)
        assertEquals(2, g.scheduler.pending.size)
        g.preferences.update { it.copy(morningDigestTime = null, eveningDigestTime = null) }
        g.reconciler.reconcile(ReconcileReason.REMINDER_CHANGED)
        assertTrue(g.scheduler.pending.isEmpty())
    }

    @Test
    fun `a delivered digest is handled without a stored reminder`() = runTest {
        val g = digestGraph()
        g.task("Pay rent", today)
        val firing = g.digests(DailyTaskDigest.EVENING_ID).single()
        g.clock.setLocal(LocalDateTime(today, evening))
        g.scheduler.pending.remove(firing.id)
        g.handleReminderAction(firing.reminderId, firing.fireAt, NotificationAction.DELIVERED)
        assertNull(g.store.reminders.value[firing.reminderId])
        assertTrue(g.scheduler.removedDelivered.none { it == firing.id }, "the shown digest stays on screen")
    }

    @Test
    fun `resetting data cancels pending digests even when the OS can't list them`() = runTest {
        val g = TestGraph(scheduler = com.daybloom.testing.FakeNotificationScheduler(enumeratesAll = false))
        g.preferences.update { it.copy(morningDigestTime = morning, eveningDigestTime = evening) }
        g.task("Pay rent", tomorrow)
        assertEquals(2, g.scheduler.pending.size)
        g.reset()
        assertTrue(g.scheduler.pending.isEmpty(), "no digest may fire for tasks that no longer exist")
    }

    @Test
    fun `body formatting is pure and stable`() {
        assertEquals("1 task still open today", DailyTaskDigest.eveningTitle(1))
        assertEquals("3 tasks planned for today", DailyTaskDigest.morningTitle(3))
        assertEquals("A and B", DailyTaskDigest.summarise(listOf("A", "B")))
    }
}
