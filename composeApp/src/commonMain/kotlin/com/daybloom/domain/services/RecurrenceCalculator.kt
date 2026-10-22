package com.daybloom.domain.services

import com.daybloom.domain.models.RecurrenceFrequency
import com.daybloom.domain.models.RecurrenceRule
import kotlinx.datetime.LocalDate
import kotlinx.datetime.daysUntil
import kotlinx.datetime.number

/**
 * Expands recurrence rules into concrete dates. Pure and deterministic.
 *
 * Monthly rules on the 29th–31st fall on the last day of shorter months; yearly rules on 29 February fall on
 * 28 February in non-leap years. Occurrences are always computed from the series start (never by
 * accumulating), so clamping in one month never shifts later months.
 */
object RecurrenceCalculator {
    /** Hard stop for runaway iteration (e.g. a daily rule started decades ago). */
    private const val MAX_ITERATIONS = 20_000

    /** All occurrence dates of [rule] starting at [start], in ascending order, honoring until/count. */
    fun occurrences(rule: RecurrenceRule?, start: LocalDate): Sequence<LocalDate> {
        if (rule == null) return sequenceOf(start)
        val raw = when (rule.frequency) {
            RecurrenceFrequency.DAILY -> generateIndexed { k -> DateMath.plusDays(start, k * rule.interval) }
            RecurrenceFrequency.WEEKLY -> weekly(rule, start)
            RecurrenceFrequency.MONTHLY -> generateIndexed { k -> clampedMonthOffset(start, k * rule.interval) }
            RecurrenceFrequency.YEARLY -> generateIndexed { k -> clampedMonthOffset(start, k * rule.interval * 12) }
        }
        val bounded = raw.take(MAX_ITERATIONS).takeWhile { rule.until == null || it <= rule.until }
        return if (rule.count != null) bounded.take(rule.count) else bounded
    }

    /** Occurrence dates within [from]..[to] inclusive. */
    fun occurrencesBetween(rule: RecurrenceRule?, start: LocalDate, from: LocalDate, to: LocalDate): List<LocalDate> {
        if (to < from) return emptyList()
        return occurrences(rule, start).dropWhile { it < from }.takeWhile { it <= to }.toList()
    }

    fun occursOn(rule: RecurrenceRule?, start: LocalDate, date: LocalDate): Boolean =
        occurrencesBetween(rule, start, date, date).isNotEmpty()

    /** First occurrence strictly after [after], or null when the series has ended. */
    fun nextAfter(rule: RecurrenceRule?, start: LocalDate, after: LocalDate): LocalDate? =
        occurrences(rule, start).firstOrNull { it > after }

    private fun generateIndexed(dateAt: (Int) -> LocalDate): Sequence<LocalDate> =
        generateSequence(0) { it + 1 }.map(dateAt)

    private fun weekly(rule: RecurrenceRule, start: LocalDate): Sequence<LocalDate> {
        val days = rule.daysOfWeek.ifEmpty { setOf(start.dayOfWeek) }
            .map { DateMath.dayOfWeekIndex(it) }
            .sorted()
        val firstWeek = DateMath.startOfWeek(start)
        return generateSequence(0) { it + 1 }
            .flatMap { weekIndex ->
                val weekStart = DateMath.plusDays(firstWeek, weekIndex * 7 * rule.interval)
                days.asSequence().map { DateMath.plusDays(weekStart, it) }
            }
            .filter { it >= start }
    }

    private fun clampedMonthOffset(start: LocalDate, months: Int): LocalDate {
        val totalMonths = start.year * 12 + (start.month.number - 1) + months
        val year = totalMonths / 12
        val month = totalMonths % 12 + 1
        val day = minOf(start.day, DateMath.lengthOfMonth(year, month))
        return LocalDate(year, month, day)
    }

    /** Number of whole days from [a] to [b]; negative when [b] is earlier. */
    fun daysBetween(a: LocalDate, b: LocalDate): Int = a.daysUntil(b)
}
