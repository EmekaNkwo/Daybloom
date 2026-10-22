package com.daybloom.domain

import com.daybloom.domain.models.QuietHours
import com.daybloom.domain.models.RecurrenceRule
import com.daybloom.domain.models.Reminder
import com.daybloom.domain.models.ReminderStatus
import com.daybloom.domain.services.ReminderOccurrenceCalculator
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

class ReminderOccurrenceCalculatorTest {
    private val london = TimeZone.of("Europe/London")
    private val created = Instant.fromEpochMilliseconds(0)
    private val start = LocalDateTime(2026, 10, 22, 8, 0).toInstant(london)

    private fun reminder(
        times: List<LocalTime> = listOf(LocalTime(9, 0)),
        startDate: LocalDate = LocalDate(2026, 10, 22),
        recurrence: RecurrenceRule? = null,
        zone: String? = null,
        quiet: Boolean = true,
    ) = Reminder("r1", "Title", startDate = startDate, times = times, recurrence = recurrence, timeZoneId = zone, respectQuietHours = quiet, createdAt = created)

    private fun local(i: Instant, zone: TimeZone = london) = i.toLocalDateTime(zone)

    @Test
    fun `one-off reminder with two times`() {
        val r = reminder(times = listOf(LocalTime(9, 0), LocalTime(17, 30)))
        val occ = ReminderOccurrenceCalculator.occurrences(r, start, start + 14.days, london, null)
        assertEquals(listOf(LocalTime(9, 0), LocalTime(17, 30)), occ.map { local(it.fireAt).time })
        assertEquals(2, occ.map { it.key }.toSet().size, "keys are unique per occurrence")
    }

    @Test
    fun `daily recurrence fills the window and nothing in the past`() {
        val r = reminder(times = listOf(LocalTime(7, 0)), recurrence = RecurrenceRule.daily())
        // The window is 3 days plus an hour: 25 Oct is the day UK clocks go back, so +72h lands exactly on 07:00 GMT.
        val occ = ReminderOccurrenceCalculator.occurrences(r, start, start + 3.days + 1.hours, london, null)
        // 07:00 today has already passed at 08:00.
        assertEquals(listOf(LocalDate(2026, 10, 23), LocalDate(2026, 10, 24), LocalDate(2026, 10, 25)), occ.map { local(it.fireAt).date })
    }

    @Test
    fun `quiet hours shift into the morning including windows that wrap midnight`() {
        val quiet = QuietHours(LocalTime(22, 0), LocalTime(7, 0))
        val late = reminder(times = listOf(LocalTime(23, 15)))
        val occ = ReminderOccurrenceCalculator.occurrences(late, start, start + 2.days, london, quiet).single()
        assertEquals(LocalDateTime(2026, 10, 23, 7, 0), local(occ.fireAt))
        val early = reminder(times = listOf(LocalTime(6, 0)), startDate = LocalDate(2026, 10, 23))
        assertEquals(LocalDateTime(2026, 10, 23, 7, 0), local(ReminderOccurrenceCalculator.occurrences(early, start, start + 2.days, london, quiet).single().fireAt))
        val exempt = reminder(times = listOf(LocalTime(23, 15)), quiet = false)
        assertEquals(LocalTime(23, 15), local(ReminderOccurrenceCalculator.occurrences(exempt, start, start + 2.days, london, quiet).single().fireAt).time)
    }

    @Test
    fun `acknowledged and snoozed occurrences`() {
        val r = reminder(times = listOf(LocalTime(9, 0)), recurrence = RecurrenceRule.daily())
        val firstFire = LocalDateTime(2026, 10, 22, 9, 0).toInstant(london)
        val acked = r.copy(acknowledgedThrough = firstFire, snoozedUntil = firstFire + 10.minutes)
        val occ = ReminderOccurrenceCalculator.occurrences(acked, start, start + 2.days, london, null)
        assertEquals(listOf(firstFire + 10.minutes, LocalDateTime(2026, 10, 23, 9, 0).toInstant(london)), occ.map { it.fireAt })
    }

    @Test
    fun `expiry and inactive status stop deliveries`() {
        val r = reminder(recurrence = RecurrenceRule.daily()).copy(expiresOn = LocalDate(2026, 10, 23))
        assertEquals(2, ReminderOccurrenceCalculator.occurrences(r, start, start + 10.days, london, null).size)
        assertTrue(ReminderOccurrenceCalculator.occurrences(r.copy(status = ReminderStatus.DISMISSED), start, start + 10.days, london, null).isEmpty())
    }

    @Test
    fun `floating reminders follow the device zone pinned ones keep their zone`() {
        val tokyo = TimeZone.of("Asia/Tokyo")
        val floating = reminder(startDate = LocalDate(2026, 10, 23))
        val pinned = reminder(startDate = LocalDate(2026, 10, 23), zone = "Europe/London")
        val window = start + 3.days
        val floatingInTokyo = ReminderOccurrenceCalculator.occurrences(floating, start, window, tokyo, null).single()
        val pinnedInTokyo = ReminderOccurrenceCalculator.occurrences(pinned, start, window, tokyo, null).single()
        assertEquals(LocalTime(9, 0), local(floatingInTokyo.fireAt, tokyo).time)
        assertEquals(LocalTime(9, 0), local(pinnedInTokyo.fireAt, london).time)
        assertTrue(floatingInTokyo.fireAt != pinnedInTokyo.fireAt)
    }

    @Test
    fun `daylight saving transition keeps wall-clock time`() {
        // UK clocks go back on 25 Oct 2026; a daily 09:00 reminder stays at 09:00 local on both sides.
        val r = reminder(recurrence = RecurrenceRule.daily(), startDate = LocalDate(2026, 10, 24))
        val occ = ReminderOccurrenceCalculator.occurrences(r, start, start + 5.days, london, null)
        assertTrue(occ.all { local(it.fireAt).time == LocalTime(9, 0) })
        val gap = occ[1].fireAt - occ[0].fireAt
        assertEquals(25 * 60L, gap.inWholeMinutes, "the day the clocks go back is 25 hours long")
    }

    @Test
    fun `nonexistent local time during spring forward still produces one delivery`() {
        // 01:30 on 29 March 2026 doesn't exist in London (01:00 → 02:00).
        val r = reminder(times = listOf(LocalTime(1, 30)), startDate = LocalDate(2026, 3, 29))
        val from = LocalDateTime(2026, 3, 28, 12, 0).toInstant(london)
        val occ = ReminderOccurrenceCalculator.occurrences(r, from, from + 2.days, london, null)
        assertEquals(1, occ.size)
    }
}
