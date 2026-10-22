package com.daybloom.domain.services

import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import kotlinx.datetime.daysUntil
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.isoDayNumber
import kotlinx.datetime.minus
import kotlinx.datetime.number
import kotlinx.datetime.plus
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Instant

/** Small calendar helpers shared by the domain services. Weeks start on Monday (ISO 8601). */
object DateMath {
    fun plusDays(date: LocalDate, days: Int): LocalDate = date.plus(days, DateTimeUnit.DAY)

    fun plusMonths(date: LocalDate, months: Int): LocalDate = date.plus(months, DateTimeUnit.MONTH)

    fun startOfWeek(date: LocalDate): LocalDate = date.minus(date.dayOfWeek.isoDayNumber - 1, DateTimeUnit.DAY)

    fun firstOfMonth(date: LocalDate): LocalDate = LocalDate(date.year, date.month.number, 1)

    fun lengthOfMonth(year: Int, month: Int): Int {
        val first = LocalDate(year, month, 1)
        return first.plus(1, DateTimeUnit.MONTH).minus(1, DateTimeUnit.DAY).day
    }

    fun lastOfMonth(date: LocalDate): LocalDate =
        LocalDate(date.year, date.month.number, lengthOfMonth(date.year, date.month.number))

    fun datesBetween(start: LocalDate, endInclusive: LocalDate): List<LocalDate> {
        if (endInclusive < start) return emptyList()
        val days = start.daysUntil(endInclusive)
        return (0..days).map { plusDays(start, it) }
    }

    fun monthDates(anyDayInMonth: LocalDate): List<LocalDate> =
        datesBetween(firstOfMonth(anyDayInMonth), lastOfMonth(anyDayInMonth))

    fun weekDates(anyDayInWeek: LocalDate): List<LocalDate> {
        val start = startOfWeek(anyDayInWeek)
        return (0 until 7).map { plusDays(start, it) }
    }

    fun dayOfWeekIndex(day: DayOfWeek): Int = day.isoDayNumber - 1

    fun toInstant(date: LocalDate, time: LocalTime, zone: TimeZone): Instant =
        LocalDateTime(date, time).toInstant(zone)

    fun localDate(instant: Instant, zone: TimeZone): LocalDate = instant.toLocalDateTime(zone).date

    fun startOfDay(date: LocalDate, zone: TimeZone): Instant = toInstant(date, LocalTime(0, 0), zone)

    fun endOfDayExclusive(date: LocalDate, zone: TimeZone): Instant = startOfDay(plusDays(date, 1), zone)
}
