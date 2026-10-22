package com.daybloom.domain

import com.daybloom.domain.models.RecurrenceFrequency
import com.daybloom.domain.models.RecurrenceRule
import com.daybloom.domain.services.RecurrenceCalculator
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RecurrenceCalculatorTest {
    private val d = { y: Int, m: Int, day: Int -> LocalDate(y, m, day) }

    @Test
    fun `no rule yields only the start date`() {
        assertEquals(listOf(d(2026, 10, 22)), RecurrenceCalculator.occurrences(null, d(2026, 10, 22)).toList())
    }

    @Test
    fun `daily with interval`() {
        val rule = RecurrenceRule(RecurrenceFrequency.DAILY, interval = 3)
        assertEquals(
            listOf(d(2026, 10, 1), d(2026, 10, 4), d(2026, 10, 7)),
            RecurrenceCalculator.occurrencesBetween(rule, d(2026, 10, 1), d(2026, 10, 1), d(2026, 10, 9)),
        )
    }

    @Test
    fun `weekly on selected weekdays starts on or after the start date`() {
        val rule = RecurrenceRule.weekly(DayOfWeek.MONDAY, DayOfWeek.THURSDAY)
        // 2026-10-22 is a Thursday.
        val dates = RecurrenceCalculator.occurrences(rule, d(2026, 10, 22)).take(4).toList()
        assertEquals(listOf(d(2026, 10, 22), d(2026, 10, 26), d(2026, 10, 29), d(2026, 11, 2)), dates)
    }

    @Test
    fun `biweekly skips alternate weeks`() {
        val rule = RecurrenceRule(RecurrenceFrequency.WEEKLY, interval = 2, daysOfWeek = setOf(DayOfWeek.FRIDAY))
        val dates = RecurrenceCalculator.occurrences(rule, d(2026, 10, 23)).take(3).toList()
        assertEquals(listOf(d(2026, 10, 23), d(2026, 11, 6), d(2026, 11, 20)), dates)
    }

    @Test
    fun `monthly on the 31st clamps to short months without drifting`() {
        val dates = RecurrenceCalculator.occurrences(RecurrenceRule.monthly(), d(2026, 1, 31)).take(4).toList()
        assertEquals(listOf(d(2026, 1, 31), d(2026, 2, 28), d(2026, 3, 31), d(2026, 4, 30)), dates)
    }

    @Test
    fun `yearly on leap day falls back to 28 February`() {
        val dates = RecurrenceCalculator.occurrences(RecurrenceRule(RecurrenceFrequency.YEARLY), d(2028, 2, 29)).take(3).toList()
        assertEquals(listOf(d(2028, 2, 29), d(2029, 2, 28), d(2030, 2, 28)), dates)
    }

    @Test
    fun `until and count bound the series`() {
        val until = RecurrenceRule(RecurrenceFrequency.DAILY, until = d(2026, 10, 3))
        assertEquals(3, RecurrenceCalculator.occurrences(until, d(2026, 10, 1)).count())
        val count = RecurrenceRule(RecurrenceFrequency.DAILY, count = 2)
        assertEquals(2, RecurrenceCalculator.occurrences(count, d(2026, 10, 1)).count())
        assertNull(RecurrenceCalculator.nextAfter(count, d(2026, 10, 1), d(2026, 10, 2)))
    }

    @Test
    fun `occursOn and nextAfter`() {
        val rule = RecurrenceRule.weekly(DayOfWeek.SATURDAY)
        assertTrue(RecurrenceCalculator.occursOn(rule, d(2026, 10, 24), d(2026, 10, 31)))
        assertFalse(RecurrenceCalculator.occursOn(rule, d(2026, 10, 24), d(2026, 10, 30)))
        assertEquals(d(2026, 10, 31), RecurrenceCalculator.nextAfter(rule, d(2026, 10, 24), d(2026, 10, 24)))
    }

    @Test
    fun `invalid rules are rejected`() {
        assertFailsWith<IllegalArgumentException> { RecurrenceRule(RecurrenceFrequency.DAILY, interval = 0) }
        assertFailsWith<IllegalArgumentException> { RecurrenceRule(RecurrenceFrequency.DAILY, count = 0) }
    }
}
