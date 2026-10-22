package com.daybloom.gamification

import com.daybloom.domain.models.AchievementCategory

/** Cumulative facts used to evaluate achievements. All counts come from persisted records. */
data class ProgressStats(
    val tasksCompleted: Int = 0,
    val topPrioritiesCompleted: Int = 0,
    val daysPlanned: Int = 0,
    val daysPlannedBeforeTen: Int = 0,
    val focusSessionsCompleted: Int = 0,
    val focusMinutesTotal: Int = 0,
    val longestFocusMinutes: Int = 0,
    val bestHabitStreak: Int = 0,
    val bestWeeklyHabitStreak: Int = 0,
    val activeDaysTotal: Int = 0,
    /** Times the user came back and completed something after 3+ days away. */
    val comebacks: Int = 0,
    /** Months where completions spanned at least 4 categories and at least one rest day was taken. */
    val balancedMonths: Int = 0,
    val appointmentsAttended: Int = 0,
    val appointmentsWithPrepDone: Int = 0,
    val reflectionsWritten: Int = 0,
    /** Most reflections written within a single calendar month. */
    val maxReflectionsInMonth: Int = 0,
    val questsCompleted: Int = 0,
    val restDaysTaken: Int = 0,
)

data class AchievementDefinition(
    val id: String,
    val title: String,
    val description: String,
    val category: AchievementCategory,
    val emoji: String,
    val xpReward: Int,
    val target: Int,
    val measure: (ProgressStats) -> Int,
) {
    fun isMet(stats: ProgressStats): Boolean = measure(stats) >= target
    fun progress(stats: ProgressStats): Int = measure(stats).coerceIn(0, target)
}

/**
 * Central, testable achievement criteria. Deliberately rewards consistency, balance, rest and reflection —
 * never volume of work, late nights or streak-at-all-costs behavior.
 */
object AchievementCatalog {
    val all: List<AchievementDefinition> = listOf(
        AchievementDefinition("first_task", "First Steps", "Complete your first task.", AchievementCategory.FIRST_STEPS, "🌱", 20, 1) { it.tasksCompleted },
        AchievementDefinition("ten_tasks", "Finding Your Rhythm", "Complete 10 tasks.", AchievementCategory.FIRST_STEPS, "🌿", 30, 10) { it.tasksCompleted },
        AchievementDefinition("first_top_priority", "What Matters Most", "Complete a top priority.", AchievementCategory.FIRST_STEPS, "⭐", 25, 1) { it.topPrioritiesCompleted },
        AchievementDefinition("first_plan", "Early Planner", "Plan your day for the first time.", AchievementCategory.PLANNING, "🗓️", 20, 1) { it.daysPlanned },
        AchievementDefinition("morning_planner", "Morning Compass", "Plan your day before 10 AM on 5 days.", AchievementCategory.PLANNING, "🧭", 50, 5) { it.daysPlannedBeforeTen },
        AchievementDefinition("first_focus", "Deep Focus", "Finish your first focus session.", AchievementCategory.FOCUS, "🎯", 20, 1) { it.focusSessionsCompleted },
        AchievementDefinition("focus_ten_hours", "Flow Finder", "Focus for 10 hours in total.", AchievementCategory.FOCUS, "🌊", 80, 600) { it.focusMinutesTotal },
        AchievementDefinition("habit_week", "Gentle Consistency", "Meet a habit's weekly target 2 weeks in a row.", AchievementCategory.CONSISTENCY, "🔁", 50, 2) { it.bestWeeklyHabitStreak },
        AchievementDefinition("active_twenty", "Showing Up", "Make progress on 20 different days.", AchievementCategory.CONSISTENCY, "🌤️", 60, 20) { it.activeDaysTotal },
        AchievementDefinition("comeback", "Welcome Back", "Return after a few days away and complete something.", AchievementCategory.COMEBACK, "🌈", 40, 1) { it.comebacks },
        AchievementDefinition("rest_day", "Rest Is Productive", "Take a planned rest day.", AchievementCategory.BALANCE, "🛋️", 20, 1) { it.restDaysTaken },
        AchievementDefinition("balanced_month", "Balanced Month", "Make progress in 4+ life areas and rest at least once in a month.", AchievementCategory.BALANCE, "⚖️", 100, 1) { it.balancedMonths },
        AchievementDefinition("appointment_ready", "Appointment Ready", "Finish preparation tasks before an appointment.", AchievementCategory.APPOINTMENTS, "📋", 40, 1) { it.appointmentsWithPrepDone },
        AchievementDefinition("show_up", "There For It", "Mark 5 appointments as attended.", AchievementCategory.APPOINTMENTS, "🤝", 40, 5) { it.appointmentsAttended },
        AchievementDefinition("first_reflection", "Looking Back", "Write your first daily reflection.", AchievementCategory.REFLECTION, "📝", 20, 1) { it.reflectionsWritten },
        AchievementDefinition("monthly_reflection", "Monthly Reflection", "Reflect on 8 days within one month.", AchievementCategory.REFLECTION, "🌙", 80, 8) { it.maxReflectionsInMonth },
        AchievementDefinition("quest_five", "Quest Taker", "Complete 5 daily quests.", AchievementCategory.FIRST_STEPS, "🗝️", 40, 5) { it.questsCompleted },
    )

    private val byId = all.associateBy { it.id }

    fun byId(id: String): AchievementDefinition? = byId[id]

    /** Definitions newly met by [stats] that are not already in [unlockedIds]. */
    fun newlyUnlocked(stats: ProgressStats, unlockedIds: Set<String>): List<AchievementDefinition> =
        all.filter { it.id !in unlockedIds && it.isMet(stats) }
}
