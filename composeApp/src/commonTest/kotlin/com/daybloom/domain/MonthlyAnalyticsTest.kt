package com.daybloom.domain

import com.daybloom.domain.models.AppointmentStatus
import com.daybloom.domain.models.DailyScore
import com.daybloom.domain.models.DayClassification
import com.daybloom.domain.models.Priority
import com.daybloom.domain.models.TaskCategory
import com.daybloom.domain.services.AppointmentFact
import com.daybloom.domain.services.CompletionFact
import com.daybloom.domain.services.MonthlyAnalyticsCalculator
import com.daybloom.domain.services.MonthlyInput
import com.daybloom.domain.services.MonthlyRecapGenerator
import com.daybloom.domain.services.TaskDueFact
import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

class MonthlyAnalyticsTest {
    private val at = Instant.fromEpochMilliseconds(0)
    private fun day(d: Int) = LocalDate(2026, 10, d)
    private fun score(d: Int, value: Int?, c: DayClassification) = DailyScore(day(d), c, value, emptyList(), at)

    private fun input(scores: List<DailyScore>, today: Int = 10, completions: List<CompletionFact> = emptyList()) = MonthlyInput(
        month = day(1), today = day(today), dailyScores = scores, tasksDue = emptyList(), completions = completions,
        focus = emptyList(), habitsScheduled = 0, habitsCompleted = 0, appointments = emptyList(), plannedDays = emptySet(),
        activeDays = completions.map { it.date }.toSet(),
    )

    @Test
    fun `average uses scored days only and keeps low planned days`() {
        val scores = listOf(
            score(1, 90, DayClassification.PLANNED),
            score(2, 20, DayClassification.PLANNED), // a hard day still counts
            score(3, null, DayClassification.REST),
            score(4, null, DayClassification.NO_DATA),
            score(5, 70, DayClassification.ACTIVITY_ONLY),
        )
        val i = MonthlyAnalyticsCalculator.calculate(input(scores, today = 5), restDays = setOf(day(3)))
        assertEquals(60, i.averageScore)
        assertEquals(3, i.scoredDays)
        assertEquals(1, i.restDays)
        assertEquals(1, i.noDataDays)
        assertEquals(4, i.eligibleDays)
        assertEquals(75, i.consistencyPercent)
    }

    @Test
    fun `untracked days are never zeros`() {
        val i = MonthlyAnalyticsCalculator.calculate(input(listOf(score(1, 80, DayClassification.PLANNED)), today = 10), emptySet())
        assertEquals(80, i.averageScore)
        assertEquals(9, i.noDataDays)
        assertEquals(10, i.trend.size)
        assertNull(i.trend[5].score)
    }

    @Test
    fun `trends need enough data`() {
        val few = MonthlyAnalyticsCalculator.calculate(input(listOf(score(1, 80, DayClassification.PLANNED))), emptySet())
        assertFalse(few.hasEnoughDataForTrends)
        val recap = MonthlyRecapGenerator.generate(few, emptyList())
        assertNotNull(recap.dataNote)
        assertNull(recap.suggestion, "no improvement suggestion without enough data")
    }

    @Test
    fun `category distribution attendance only from recorded statuses important completion`() {
        val base = input(
            (1..6).map { score(it, 60 + it, DayClassification.PLANNED) },
            completions = listOf(CompletionFact(day(1), TaskCategory.WORK), CompletionFact(day(2), TaskCategory.WORK), CompletionFact(day(2), TaskCategory.HEALTH)),
        ).copy(
            appointments = listOf(
                AppointmentFact(day(2), AppointmentStatus.ATTENDED),
                AppointmentFact(day(3), AppointmentStatus.SCHEDULED), // past but unrecorded: not counted either way
                AppointmentFact(day(4), AppointmentStatus.MISSED),
            ),
            tasksDue = listOf(
                TaskDueFact(day(1), Priority.HIGH, false, day(1)),
                TaskDueFact(day(2), Priority.NORMAL, true, null),
                TaskDueFact(day(3), Priority.LOW, false, null),
            ),
        )
        val i = MonthlyAnalyticsCalculator.calculate(base, emptySet())
        assertEquals(mapOf(TaskCategory.WORK to 2, TaskCategory.HEALTH to 1), i.categoryDistribution)
        assertEquals(TaskCategory.WORK, i.strongestCategory)
        assertEquals(50, i.appointmentAttendancePercent)
        assertEquals(2, i.appointmentsRecorded)
        assertEquals(50, i.importantCompletionPercent)
        assertTrue(i.hasEnoughDataForTrends)
        assertEquals(day(6), i.bestDay)
    }

    @Test
    fun `shareable recap excludes private details by default`() {
        val i = MonthlyAnalyticsCalculator.calculate(input((1..6).map { score(it, 70, DayClassification.PLANNED) }), emptySet())
        val recap = MonthlyRecapGenerator.generate(i, emptyList())
        val publicText = MonthlyRecapGenerator.shareableText(recap, i, includePrivate = false, privateLines = listOf("Completed: Secret project"))
        assertFalse(publicText.contains("Secret project"))
        val privateText = MonthlyRecapGenerator.shareableText(recap, i, includePrivate = true, privateLines = listOf("Completed: Secret project"))
        assertTrue(privateText.contains("Secret project"))
    }
}
