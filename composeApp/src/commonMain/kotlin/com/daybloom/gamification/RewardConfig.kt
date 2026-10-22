package com.daybloom.gamification

import com.daybloom.domain.models.XpReason

/**
 * The single source of truth for every XP value and anti-farming limit in the app.
 *
 * Reward policy:
 * - Every award has a unique event key; the ledger ignores repeats, so no action can be rewarded twice.
 * - Reopening a task (or un-checking a habit) revokes that award; completing it again restores the same award.
 *   Toggling therefore never gains XP.
 * - Quests, achievements, focus sessions, plans and reflections are awarded once and never revoked.
 * - Task XP is capped per day so creating and ticking off trivial tasks cannot farm XP.
 */
object RewardConfig {
    const val TASK_COMPLETED = 10
    const val TOP_PRIORITY_COMPLETED = 20
    const val FOCUS_SESSION = 15
    const val HABIT_COMPLETED = 5
    const val DAILY_QUEST = 30
    const val WEEKLY_QUEST = 100
    const val PLANNED_DAY = 10
    const val REFLECTION = 10

    /** Task-completion awards beyond this count on one day earn 0 XP (the task still completes normally). */
    const val MAX_REWARDED_TASKS_PER_DAY = 15

    /** Focus sessions shorter than this are recorded but not rewarded. */
    const val MIN_REWARDED_FOCUS_MINUTES = 10

    fun amountFor(reason: XpReason): Int = when (reason) {
        XpReason.TASK_COMPLETED -> TASK_COMPLETED
        XpReason.TOP_PRIORITY_COMPLETED -> TOP_PRIORITY_COMPLETED
        XpReason.FOCUS_SESSION -> FOCUS_SESSION
        XpReason.HABIT_COMPLETED -> HABIT_COMPLETED
        XpReason.DAILY_QUEST -> DAILY_QUEST
        XpReason.WEEKLY_QUEST -> WEEKLY_QUEST
        XpReason.PLANNED_DAY -> PLANNED_DAY
        XpReason.REFLECTION -> REFLECTION
        XpReason.ACHIEVEMENT -> 0
    }

    /** Event-key builders. Keys are the idempotency contract with the XP ledger — never change their format. */
    object Keys {
        fun task(taskId: String) = "task:$taskId"
        fun focus(sessionId: String) = "focus:$sessionId"
        fun habit(habitId: String, date: String) = "habit:$habitId:$date"
        fun quest(questId: String) = "quest:$questId"
        fun plan(date: String) = "plan:$date"
        fun reflection(date: String) = "reflection:$date"
        fun achievement(achievementId: String) = "achievement:$achievementId"
    }
}
