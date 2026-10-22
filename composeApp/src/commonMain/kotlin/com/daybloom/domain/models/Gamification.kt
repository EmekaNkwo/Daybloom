package com.daybloom.domain.models

import kotlinx.datetime.LocalDate
import kotlin.time.Instant

enum class XpReason(val label: String) {
    TASK_COMPLETED("Completed a task"),
    TOP_PRIORITY_COMPLETED("Completed a top priority"),
    FOCUS_SESSION("Finished a focus session"),
    HABIT_COMPLETED("Completed a habit"),
    DAILY_QUEST("Completed a daily quest"),
    WEEKLY_QUEST("Completed a weekly quest"),
    ACHIEVEMENT("Unlocked an achievement"),
    PLANNED_DAY("Planned your day"),
    REFLECTION("Reflected on your day"),
}

/**
 * One row per rewarded event. [eventKey] is unique, which is what makes every award idempotent:
 * repeating an action re-uses the same key and is ignored by the ledger.
 */
data class XpEntry(
    val eventKey: String,
    val amount: Int,
    val reason: XpReason,
    val sourceId: String?,
    val date: LocalDate,
    val createdAt: Instant,
)

data class Level(val number: Int, val title: String, val minXp: Int)

data class LevelProgress(
    val totalXp: Int,
    val level: Level,
    val nextLevel: Level?,
) {
    val xpIntoLevel: Int get() = totalXp - level.minXp
    val xpForNextLevel: Int? get() = nextLevel?.let { it.minXp - level.minXp }
    val fraction: Float
        get() = xpForNextLevel?.let { (xpIntoLevel.toFloat() / it).coerceIn(0f, 1f) } ?: 1f
}

enum class QuestType {
    COMPLETE_TOP_PRIORITY,
    FINISH_THREE_TASKS,
    FOCUS_TWENTY_MINUTES,
    COMPLETE_ONE_HABIT,
    PLAN_TOMORROW,
    FINISH_POSTPONED_TASK,
    TAKE_A_BREAK,
}

enum class QuestStatus { ACTIVE, COMPLETED, SKIPPED }

data class Quest(
    /** Stable id derived from date + type so re-generating a day's quests is a no-op. */
    val id: String,
    val date: LocalDate,
    val type: QuestType,
    val title: String,
    val description: String,
    val target: Int,
    val progress: Int = 0,
    val rewardXp: Int,
    val status: QuestStatus = QuestStatus.ACTIVE,
    val completedAt: Instant? = null,
)

enum class AchievementCategory(val label: String) {
    FIRST_STEPS("First Steps"),
    PLANNING("Early Planner"),
    FOCUS("Deep Focus"),
    CONSISTENCY("Consistency"),
    COMEBACK("Comeback"),
    BALANCE("Balanced Month"),
    APPOINTMENTS("Appointment Ready"),
    REFLECTION("Monthly Reflection"),
}

data class AchievementUnlock(
    val achievementId: String,
    val unlockedAt: Instant,
)
