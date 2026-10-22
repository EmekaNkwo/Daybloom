package com.daybloom.domain.services

import com.daybloom.domain.models.Habit
import kotlinx.datetime.LocalDate

data class HabitStreak(
    /** Consecutive scheduled days completed, forgiving one missed day per [StreakCalculator.GRACE_WINDOW]. */
    val current: Int,
    val best: Int,
    /** Consecutive weeks (Mon–Sun) in which the weekly target was met; the current week counts once met. */
    val currentWeeks: Int,
    val bestWeeks: Int,
    val completedThisWeek: Int,
    val weeklyTarget: Int,
)

/**
 * Gentle streaks:
 * - Only scheduled days count; unscheduled days, paused periods and rest days are skipped, never "missed".
 * - Today being incomplete never breaks a streak (the day isn't over).
 * - One missed scheduled day within any rolling window of [GRACE_WINDOW] scheduled days is forgiven.
 * - A weekly streak (meeting the weekly target) is offered alongside, so missing one day rarely matters.
 */
object StreakCalculator {
    const val GRACE_WINDOW = 7

    fun habitStreak(
        habit: Habit,
        completions: Set<LocalDate>,
        restDays: Set<LocalDate>,
        today: LocalDate,
        /** The habit's creation date (or earliest completion, if earlier) in the user's zone. */
        startDate: LocalDate,
    ): HabitStreak {
        val start = minOf(startDate, completions.minOrNull() ?: startDate)
        val scheduled = DateMath.datesBetween(start, today)
            .filter { it.dayOfWeek in habit.scheduledDays && it !in restDays }
            .filter { habit.pausedSince == null || it < habit.pausedSince }

        val (current, best) = dailyStreaks(scheduled, completions, today)
        val weekly = weeklyStreaks(habit, completions, start, today)
        val weekStart = DateMath.startOfWeek(today)
        val thisWeek = completions.count { it >= weekStart && it <= today }
        return HabitStreak(current, best, weekly.first, weekly.second, thisWeek, habit.weeklyTarget)
    }

    private fun dailyStreaks(scheduled: List<LocalDate>, completions: Set<LocalDate>, today: LocalDate): Pair<Int, Int> {
        var run = 0
        var best = 0
        var missesInWindow = ArrayDeque<Int>() // indices of forgiven misses
        scheduled.forEachIndexed { index, date ->
            val done = date in completions
            when {
                done -> run++
                date == today -> Unit // still time today
                else -> {
                    while (missesInWindow.isNotEmpty() && index - missesInWindow.first() >= GRACE_WINDOW) {
                        missesInWindow.removeFirst()
                    }
                    if (run > 0 && missesInWindow.isEmpty()) {
                        missesInWindow.addLast(index) // forgiven: streak continues but doesn't grow
                    } else {
                        run = 0
                        missesInWindow = ArrayDeque()
                    }
                }
            }
            best = maxOf(best, run)
        }
        return run to best
    }

    private fun weeklyStreaks(habit: Habit, completions: Set<LocalDate>, start: LocalDate, today: LocalDate): Pair<Int, Int> {
        val target = habit.weeklyTarget.coerceAtLeast(1)
        val thisWeek = DateMath.startOfWeek(today)
        var week = DateMath.startOfWeek(start)
        var run = 0
        var best = 0
        while (week <= thisWeek) {
            val end = DateMath.plusDays(week, 6)
            val pausedWholeWeek = habit.pausedSince != null && habit.pausedSince <= week
            val count = completions.count { it >= week && it <= end }
            when {
                pausedWholeWeek -> Unit
                count >= target -> run++
                week == thisWeek -> Unit // week still in progress
                else -> run = 0
            }
            best = maxOf(best, run)
            week = DateMath.plusDays(week, 7)
        }
        return run to best
    }

    /**
     * Consecutive days with any recorded progress ending today or yesterday. Rest days are skipped.
     */
    fun activityStreak(activeDays: Set<LocalDate>, restDays: Set<LocalDate>, today: LocalDate): Int {
        var day = if (today in activeDays) today else DateMath.plusDays(today, -1)
        var count = 0
        var guard = 0
        while (guard++ < 3660) {
            when {
                day in activeDays -> count++
                day in restDays -> Unit
                else -> return count
            }
            day = DateMath.plusDays(day, -1)
        }
        return count
    }

    /** Longest run of consecutive active days (rest days bridge but don't count). */
    fun bestActivityStreak(activeDays: Set<LocalDate>, restDays: Set<LocalDate>): Int {
        if (activeDays.isEmpty()) return 0
        val first = activeDays.min()
        val last = activeDays.max()
        var best = 0
        var run = 0
        DateMath.datesBetween(first, last).forEach { d ->
            when {
                d in activeDays -> { run++; best = maxOf(best, run) }
                d in restDays -> Unit
                else -> run = 0
            }
        }
        return best
    }

    /** Number of times the user returned after [gapDays]+ inactive (non-rest) days. */
    fun comebacks(activeDays: Set<LocalDate>, restDays: Set<LocalDate>, gapDays: Int = 3): Int {
        val sorted = activeDays.sorted()
        return sorted.zipWithNext().count { (a, b) ->
            val gap = DateMath.datesBetween(DateMath.plusDays(a, 1), DateMath.plusDays(b, -1)).count { it !in restDays }
            gap >= gapDays
        }
    }
}
