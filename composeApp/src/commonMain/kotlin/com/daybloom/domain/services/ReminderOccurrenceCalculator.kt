package com.daybloom.domain.services

import com.daybloom.domain.models.QuietHours
import com.daybloom.domain.models.Reminder
import com.daybloom.domain.models.ReminderOccurrence
import com.daybloom.domain.models.ReminderStatus
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Duration.Companion.days
import kotlin.time.Instant

/**
 * Turns persisted reminder definitions into the concrete deliveries that should be scheduled with the OS
 * inside a finite window. Pure: identical inputs always yield identical, identically-keyed occurrences,
 * which is what makes reconciliation duplicate-free.
 */
object ReminderOccurrenceCalculator {

    fun occurrences(
        reminder: Reminder,
        windowStart: Instant,
        windowEnd: Instant,
        deviceZone: TimeZone,
        quietHours: QuietHours?,
    ): List<ReminderOccurrence> {
        if (reminder.status != ReminderStatus.ACTIVE) return emptyList()
        val zone = reminder.timeZoneId?.let { runCatching { TimeZone.of(it) }.getOrNull() } ?: deviceZone

        val fromDate = windowStart.toLocalDateTime(zone).date
        val toDate = windowEnd.toLocalDateTime(zone).date.let { end ->
            reminder.expiresOn?.let { minOf(it, end) } ?: end
        }
        val firstDate = maxOf(fromDate, reminder.startDate)
        val dates = RecurrenceCalculator.occurrencesBetween(reminder.recurrence, reminder.startDate, firstDate, toDate)

        val scheduled = dates.flatMap { date ->
            reminder.times.distinct().sorted().map { time ->
                val requested = LocalDateTime(date, time)
                val adjusted = if (reminder.respectQuietHours && quietHours != null) {
                    shiftOutOfQuietHours(requested, quietHours)
                } else {
                    requested
                }
                adjusted.toInstant(zone)
            }
        }
        val snoozed = reminder.snoozedUntil?.let { listOf(it) }.orEmpty()

        return (scheduled + snoozed)
            .asSequence()
            .filter { it >= windowStart && it < windowEnd }
            .filter { reminder.acknowledgedThrough == null || it > reminder.acknowledgedThrough }
            .distinct()
            .sorted()
            .map { ReminderOccurrence(reminder.id, it, reminder.title, reminder.body, reminder.category) }
            .toList()
    }

    /** Next delivery strictly after [after], looking ahead at most [lookAheadDays]. */
    fun nextOccurrence(
        reminder: Reminder,
        after: Instant,
        deviceZone: TimeZone,
        quietHours: QuietHours?,
        lookAheadDays: Int = 400,
    ): ReminderOccurrence? {
        val end = after + lookAheadDays.days
        return occurrences(reminder, after, end, deviceZone, quietHours).firstOrNull { it.fireAt > after }
    }

    /** Moves a time that falls inside quiet hours to the moment quiet hours end. */
    fun shiftOutOfQuietHours(requested: LocalDateTime, quietHours: QuietHours): LocalDateTime {
        if (!quietHours.contains(requested.time)) return requested
        val wraps = quietHours.start > quietHours.end
        val endDate = if (wraps && requested.time >= quietHours.start) {
            DateMath.plusDays(requested.date, 1)
        } else {
            requested.date
        }
        return LocalDateTime(endDate, quietHours.end)
    }
}
