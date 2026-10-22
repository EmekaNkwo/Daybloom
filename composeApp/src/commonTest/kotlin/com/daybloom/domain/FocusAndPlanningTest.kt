package com.daybloom.domain

import com.daybloom.domain.models.Appointment
import com.daybloom.domain.models.AppointmentOccurrence
import com.daybloom.domain.models.AppointmentStatus
import com.daybloom.domain.models.FocusSession
import com.daybloom.domain.models.FocusStatus
import com.daybloom.domain.models.Task
import com.daybloom.domain.services.FocusTimerCalculator
import com.daybloom.domain.services.PlanCapacityCalculator
import com.daybloom.domain.services.SuggestionAction
import com.daybloom.domain.services.SuggestionEngine
import com.daybloom.domain.services.SuggestionInput
import com.daybloom.domain.services.Validation
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

class FocusAndPlanningTest {
    private val t0 = Instant.fromEpochMilliseconds(1_800_000_000_000)
    private val session = FocusSession(
        "f", null, null, plannedMinutes = 25, date = LocalDate(2026, 10, 22), startedAt = t0,
        segmentStartedAt = t0, segmentStartedMonotonicMs = 10_000, status = FocusStatus.RUNNING,
    )

    @Test
    fun `monotonic time is used even if the wall clock jumps`() {
        // 10 real minutes pass, but the user moved the wall clock back an hour.
        val elapsed = FocusTimerCalculator.focusedSeconds(session, 10_000 + 10 * 60_000, t0 - 1.hours)
        assertEquals(600, elapsed)
    }

    @Test
    fun `after a reboot the wall clock is used but clamped`() {
        val wall = FocusTimerCalculator.focusedSeconds(session, nowMonotonicMs = 5, now = t0 + 5.minutes)
        assertEquals(300, wall)
        val absurd = FocusTimerCalculator.focusedSeconds(session, nowMonotonicMs = 5, now = t0 + 30.hours)
        assertEquals(session.plannedSeconds + FocusTimerCalculator.GRACE_SECONDS, absurd)
        val negative = FocusTimerCalculator.focusedSeconds(session, nowMonotonicMs = 5, now = t0 - 5.minutes)
        assertEquals(0, negative)
    }

    @Test
    fun `pause and resume accumulate only focused time`() {
        val paused = FocusTimerCalculator.pause(session, 10_000 + 5 * 60_000, t0 + 5.minutes)
        assertEquals(300, paused.accumulatedSeconds)
        assertEquals(1, paused.pauseCount)
        // 20 minutes on a break don't count.
        val resumed = FocusTimerCalculator.resume(paused, 10_000 + 25 * 60_000, t0 + 25.minutes)
        assertEquals(600, FocusTimerCalculator.focusedSeconds(resumed, 10_000 + 30 * 60_000, t0 + 30.minutes))
        val finished = FocusTimerCalculator.finish(resumed, 10_000 + 30 * 60_000, t0 + 30.minutes, completed = false)
        assertEquals(FocusStatus.INTERRUPTED, finished.status)
        assertEquals(600, finished.accumulatedSeconds)
        assertTrue(FocusTimerCalculator.isFinished(session, 10_000 + 26 * 60_000, t0))
    }

    @Test
    fun `a session that ran out records its planned time even if noticed late`() {
        // 25-minute plan; the app only notices 12 minutes after the timer hit zero.
        val late = FocusTimerCalculator.finish(session, 10_000 + 37 * 60_000, t0 + 37.minutes, completed = true)
        assertEquals(FocusStatus.COMPLETED, late.status)
        assertEquals(session.plannedSeconds, late.accumulatedSeconds)
    }

    @Test
    fun `plan capacity counts unestimated tasks with a default`() {
        val cap = PlanCapacityCalculator.calculate(360, listOf(120, null, 240), listOf(30), 30)
        assertEquals(120 + 30 + 240, cap.taskMinutes)
        assertEquals(450, cap.plannedMinutes)
        assertTrue(cap.isOverCapacity)
        assertEquals(90, cap.overByMinutes)
        assertEquals(1, cap.unestimatedTasks)
        val unknown = PlanCapacityCalculator.calculate(null, listOf(600), emptyList(), 0)
        assertTrue(!unknown.isOverCapacity, "without available time, never claim over-capacity")
    }

    private val created = Instant.fromEpochMilliseconds(0)
    private fun task(id: String, due: LocalDate?, postponed: Int = 0) = Task(id, "Task $id", dueDate = due, postponeCount = postponed, createdAt = created)
    private val today = LocalDate(2026, 10, 22)
    private fun input(now: LocalDateTime, tasks: List<Task> = emptyList(), appts: List<AppointmentOccurrence> = emptyList(), plans: List<LocalTime> = emptyList()) =
        SuggestionInput(now, tasks, appts, tomorrowHasPlan = false, recentPlanCreationTimes = plans, hasPlanningReminder = false, dismissedKeys = emptySet())

    @Test
    fun `suggestions only appear when their facts are present`() {
        assertTrue(SuggestionEngine.suggest(input(LocalDateTime(today, LocalTime(16, 0)))).isEmpty())
        val tomorrow = LocalDate(2026, 10, 23)
        val busy = SuggestionEngine.suggest(input(LocalDateTime(today, LocalTime(16, 0)), (1..3).map { task("$it", tomorrow) }))
        assertNotNull(busy.firstOrNull { it.action is SuggestionAction.PlanDay })
        val split = SuggestionEngine.suggest(input(LocalDateTime(today, LocalTime(10, 0)), listOf(task("p", today, postponed = 3))))
        assertTrue(split.any { it.action == SuggestionAction.OpenTask("p") })
    }

    @Test
    fun `gap suggestion uses a real upcoming appointment only`() {
        val appt = Appointment("a", "Project meeting", date = today, startTime = LocalTime(10, 30), endTime = LocalTime(11, 0), timeZoneId = "UTC", createdAt = created)
        val occ = AppointmentOccurrence(appt, today, AppointmentStatus.SCHEDULED)
        val s = SuggestionEngine.suggest(input(LocalDateTime(today, LocalTime(10, 0)), appts = listOf(occ)))
        assertEquals("You have 30 minutes before Project meeting.", s.single().message)
        val during = SuggestionEngine.suggest(input(LocalDateTime(today, LocalTime(10, 40)), appts = listOf(occ)))
        assertTrue(during.isEmpty())
    }

    @Test
    fun `planning pattern needs enough history`() {
        val few = SuggestionEngine.suggest(input(LocalDateTime(today, LocalTime(9, 0)), plans = List(3) { LocalTime(8, 0) }))
        assertTrue(few.none { it.action is SuggestionAction.SetPlanningReminder })
        val enough = SuggestionEngine.suggest(input(LocalDateTime(today, LocalTime(9, 0)), plans = List(6) { LocalTime(8, 30) }))
        val action = enough.mapNotNull { it.action as? SuggestionAction.SetPlanningReminder }.single()
        assertEquals(LocalTime(8, 15), action.time)
    }

    @Test
    fun `evening suggestion respects the unfinished-task policy`() {
        val evening = LocalDateTime(today, LocalTime(19, 0))
        val ask = SuggestionEngine.suggest(input(evening, listOf(task("x", today))))
        assertTrue(ask.any { it.action is SuggestionAction.MoveUnfinished })
        val keep = SuggestionEngine.suggest(input(evening, listOf(task("x", today))).copy(offerMoveUnfinished = false))
        assertTrue(keep.none { it.action is SuggestionAction.MoveUnfinished })
    }

    @Test
    fun `validation messages`() {
        assertNotNull(Validation.title("  "))
        assertNull(Validation.title("Pay rent"))
        assertNotNull(Validation.title("x".repeat(Validation.MAX_TITLE + 1)))
        assertNotNull(Validation.estimate(0))
        assertNotNull(Validation.timeRange(LocalTime(10, 0), LocalTime(9, 0)))
        assertNotNull(Validation.tags(List(11) { "t$it" }))
    }
}
