package com.daybloom.domain

import com.daybloom.domain.models.Priority
import com.daybloom.domain.models.RecurrenceFrequency
import com.daybloom.domain.models.TaskCategory
import com.daybloom.domain.services.CaptureKind
import com.daybloom.domain.services.QuickCaptureParser
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class QuickCaptureParserTest {
    private val parser = QuickCaptureParser()
    /** Thursday 22 October 2026, 08:00. */
    private val now = LocalDateTime(2026, 10, 22, 8, 0)

    @Test
    fun `pay rent tomorrow at 9 AM`() {
        val p = parser.parse("Pay rent tomorrow at 9 AM", now)
        assertEquals("Pay rent", p.title)
        assertEquals(LocalDate(2026, 10, 23), p.date)
        assertEquals(LocalTime(9, 0), p.time)
        assertFalse(p.needsConfirmation)
    }

    @Test
    fun `call Mum on Friday`() {
        val p = parser.parse("Call Mum on Friday", now)
        assertEquals("Call Mum", p.title)
        assertEquals(LocalDate(2026, 10, 23), p.date)
        assertNull(p.time)
        assertEquals(CaptureKind.TASK, p.kind)
    }

    @Test
    fun `doctor appointment next Monday at 2 PM is an appointment that needs confirmation`() {
        val p = parser.parse("Doctor appointment next Monday at 2 PM", now)
        assertEquals("Doctor appointment", p.title)
        assertEquals(LocalDate(2026, 10, 26), p.date)
        assertEquals(LocalTime(14, 0), p.time)
        assertEquals(CaptureKind.APPOINTMENT, p.kind)
        assertTrue(p.needsConfirmation, "\"next <weekday>\" is ambiguous and must be confirmed")
    }

    @Test
    fun `read for 30 minutes tonight`() {
        val p = parser.parse("Read for 30 minutes tonight", now)
        assertEquals("Read", p.title)
        assertEquals(30, p.durationMinutes)
        assertEquals(LocalDate(2026, 10, 22), p.date)
        assertEquals(LocalTime(20, 0), p.time)
    }

    @Test
    fun `next friday offers both interpretations`() {
        val p = parser.parse("Call the dentist next Friday", now)
        assertEquals("Call the dentist", p.title)
        assertEquals(LocalDate(2026, 10, 30), p.date)
        assertTrue(p.ambiguities.single().contains("Oct"))
    }

    @Test
    fun `bare hour without meridiem is flagged`() {
        val p = parser.parse("Gym at 6", now)
        assertEquals(LocalTime(18, 0), p.time)
        assertTrue(p.needsConfirmation)
    }

    @Test
    fun `time already passed today moves to tomorrow with a note`() {
        val p = parser.parse("Stretch at 7:30am", now)
        assertEquals(LocalDate(2026, 10, 23), p.date)
        assertTrue(p.needsConfirmation)
    }

    @Test
    fun `recurrence priority tags and category`() {
        val p = parser.parse("Take vitamins every weekday at 8:15am !! #health #morning", now)
        assertEquals("Take vitamins", p.title)
        assertEquals(RecurrenceFrequency.WEEKLY, p.recurrence?.frequency)
        assertEquals(5, p.recurrence?.daysOfWeek?.size)
        assertEquals(Priority.URGENT, p.priority)
        assertEquals(TaskCategory.HEALTH, p.category)
        assertEquals(listOf("morning"), p.tags)
        assertEquals(LocalTime(8, 15), p.time)
    }

    @Test
    fun `every monday and thursday`() {
        val p = parser.parse("Team sync every Monday and Thursday at 10:00", now)
        assertEquals(setOf(DayOfWeek.MONDAY, DayOfWeek.THURSDAY), p.recurrence?.daysOfWeek)
        assertEquals(LocalDate(2026, 10, 22), p.date, "first occurrence is today, a Thursday")
    }

    @Test
    fun `explicit month day and past dates roll to next year`() {
        assertEquals(LocalDate(2026, 10, 30), parser.parse("Submit report Oct 30", now).date)
        val past = parser.parse("Renew passport on 3 March", now)
        assertEquals(LocalDate(2027, 3, 3), past.date)
        assertTrue(past.needsConfirmation)
    }

    @Test
    fun `ambiguous numeric date asks for confirmation`() {
        val p = parser.parse("Party 11/12", now)
        assertEquals(LocalDate(2026, 12, 11), p.date)
        assertTrue(p.needsConfirmation)
        val unambiguous = parser.parse("Party 25/12", now)
        assertEquals(LocalDate(2026, 12, 25), unambiguous.date)
        assertFalse(unambiguous.needsConfirmation)
    }

    @Test
    fun `durations in hours and words`() {
        assertEquals(90, parser.parse("Deep work for 1.5 hours", now).durationMinutes)
        assertEquals(60, parser.parse("Walk for an hour", now).durationMinutes)
        assertEquals(30, parser.parse("Tidy for half an hour", now).durationMinutes)
    }

    @Test
    fun `plain text keeps its title and has no date`() {
        val p = parser.parse("Buy oat milk", now)
        assertEquals("Buy oat milk", p.title)
        assertNull(p.date)
        assertTrue(p.isValid)
    }

    @Test
    fun `input that is only a date has no valid title`() {
        assertFalse(parser.parse("tomorrow at 9am", now).isValid)
    }

    @Test
    fun `today is friday says today not next week`() {
        val friday = LocalDateTime(2026, 10, 23, 8, 0)
        val p = parser.parse("Call Mum on Friday", friday)
        assertEquals(LocalDate(2026, 10, 23), p.date)
        assertTrue(p.needsConfirmation)
    }
}
