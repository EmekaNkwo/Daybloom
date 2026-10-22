package com.daybloom.domain.models

import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime
import kotlin.time.Instant

data class Habit(
    val id: String,
    val title: String,
    val emoji: String = "🌱",
    /** Days the habit is scheduled. All seven days = daily. */
    val scheduledDays: Set<DayOfWeek> = DayOfWeek.entries.toSet(),
    /** Gentle target per week; may be lower than the number of scheduled days. */
    val weeklyTarget: Int = scheduledDays.size,
    val reminderTime: LocalTime? = null,
    val streaksEnabled: Boolean = true,
    /** While paused the habit is not scheduled and never affects scores or streaks. */
    val pausedSince: LocalDate? = null,
    val category: TaskCategory = TaskCategory.HEALTH,
    val createdAt: Instant,
    val archived: Boolean = false,
) {
    val isPaused: Boolean get() = pausedSince != null

    fun isScheduledOn(date: LocalDate): Boolean = !archived && !isPaused && date.dayOfWeek in scheduledDays
}

data class HabitCompletion(
    val habitId: String,
    val date: LocalDate,
    val completedAt: Instant,
)
