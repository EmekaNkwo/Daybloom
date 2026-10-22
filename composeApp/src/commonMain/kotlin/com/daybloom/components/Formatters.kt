package com.daybloom.components

import com.daybloom.domain.models.displayName
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.datetime.daysUntil
import kotlinx.datetime.number
import kotlin.time.Duration

/** Locale-neutral English formatting used across the UI. Kept in one place so copy stays consistent. */
object Format {
    private val MONTHS = listOf("January", "February", "March", "April", "May", "June", "July", "August", "September", "October", "November", "December")

    fun monthName(date: LocalDate): String = MONTHS[date.month.number - 1]
    fun shortMonth(date: LocalDate): String = monthName(date).take(3)

    /** "Wednesday, October 22" */
    fun longDate(date: LocalDate): String = "${date.dayOfWeek.displayName()}, ${monthName(date)} ${date.day}"

    /** "Wed, Oct 22" */
    fun shortDate(date: LocalDate): String = "${date.dayOfWeek.displayName().take(3)}, ${shortMonth(date)} ${date.day}"

    /** "October 2026" */
    fun monthYear(date: LocalDate): String = "${monthName(date)} ${date.year}"

    /** "Today", "Tomorrow", "Yesterday", or a short date. */
    fun relativeDay(date: LocalDate, today: LocalDate): String = when (today.daysUntil(date)) {
        0 -> "Today"
        1 -> "Tomorrow"
        -1 -> "Yesterday"
        in 2..6 -> date.dayOfWeek.displayName()
        else -> shortDate(date)
    }

    /** "10:30 AM" */
    fun time(time: LocalTime): String {
        val hour = if (time.hour % 12 == 0) 12 else time.hour % 12
        val suffix = if (time.hour < 12) "AM" else "PM"
        return "$hour:${time.minute.toString().padStart(2, '0')} $suffix"
    }

    fun timeRange(start: LocalTime, end: LocalTime) = "${time(start)} – ${time(end)}"

    /** "In 45 minutes", "In 2 h 10 min", "Now", "15 minutes ago" */
    fun relativeTime(target: LocalDateTime, now: LocalDateTime): String {
        val minutes = minutesBetween(now, target)
        return when {
            minutes in -1..1 -> "Now"
            minutes > 0 -> "In ${duration(minutes)}"
            else -> "${duration(-minutes)} ago"
        }
    }

    fun minutesBetween(a: LocalDateTime, b: LocalDateTime): Long {
        val days = a.date.daysUntil(b.date).toLong()
        return days * 24 * 60 + (b.time.toSecondOfDay() - a.time.toSecondOfDay()) / 60
    }

    /** 45 → "45 minutes", 130 → "2 h 10 min" */
    fun duration(minutes: Long): String {
        val h = minutes / 60
        val m = minutes % 60
        return when {
            h == 0L -> "$m minute${if (m == 1L) "" else "s"}"
            m == 0L -> "$h hour${if (h == 1L) "" else "s"}"
            else -> "$h h $m min"
        }
    }

    fun compactMinutes(minutes: Int): String {
        val h = minutes / 60
        val m = minutes % 60
        return when {
            h == 0 -> "${m}m"
            m == 0 -> "${h}h"
            else -> "${h}h ${m}m"
        }
    }

    /** Timer display "24:59" or "1:02:03". */
    fun timer(duration: Duration): String {
        val total = duration.inWholeSeconds.coerceAtLeast(0)
        val h = total / 3600
        val m = (total % 3600) / 60
        val s = total % 60
        val mm = m.toString().padStart(2, '0')
        val ss = s.toString().padStart(2, '0')
        return if (h > 0) "$h:$mm:$ss" else "$m:$ss"
    }

    fun greeting(hour: Int): Pair<String, String> = when (hour) {
        in 5..11 -> "Good morning" to "☀️"
        in 12..16 -> "Good afternoon" to "🌤️"
        in 17..21 -> "Good evening" to "🌇"
        else -> "Hello, night owl" to "🌙"
    }
}
