package com.daybloom.gamification

import com.daybloom.domain.models.Quest
import com.daybloom.domain.models.QuestStatus
import com.daybloom.domain.models.QuestType
import kotlinx.datetime.LocalDate

data class QuestTemplate(
    val type: QuestType,
    val title: String,
    val description: String,
    val target: Int,
    val rewardXp: Int = RewardConfig.DAILY_QUEST,
)

/** Facts about the user that decide which quests make sense on a given day. */
data class QuestContext(
    val hasHabits: Boolean,
    val hasPostponedTasks: Boolean,
    val hasOpenTasks: Boolean,
)

/** Progress facts for one day, used to advance quests. */
data class QuestDayProgress(
    val topPrioritiesCompleted: Int,
    val tasksCompleted: Int,
    val focusMinutes: Int,
    val habitsCompleted: Int,
    val tomorrowPlanned: Boolean,
    val postponedTasksCompleted: Int,
    val breaksTaken: Int,
)

object QuestCatalog {
    const val QUESTS_PER_DAY = 3

    val templates: Map<QuestType, QuestTemplate> = listOf(
        QuestTemplate(QuestType.COMPLETE_TOP_PRIORITY, "Complete your top priority", "Finish one of today's top priorities.", 1),
        QuestTemplate(QuestType.FINISH_THREE_TASKS, "Finish three tasks", "Complete any three tasks today.", 3),
        QuestTemplate(QuestType.FOCUS_TWENTY_MINUTES, "Focus for 20 minutes", "Spend 20 minutes in focus mode.", 20),
        QuestTemplate(QuestType.COMPLETE_ONE_HABIT, "Complete one habit", "Check off any habit today.", 1),
        QuestTemplate(QuestType.PLAN_TOMORROW, "Plan tomorrow", "Choose tomorrow's top priorities.", 1),
        QuestTemplate(QuestType.FINISH_POSTPONED_TASK, "Finish a postponed task", "Complete a task you've moved before.", 1),
        QuestTemplate(QuestType.TAKE_A_BREAK, "Take a restorative break", "Step away for a real break, then mark it here.", 1),
    ).associateBy { it.type }

    fun questId(date: LocalDate, type: QuestType) = "$date:${type.name}"

    /**
     * Deterministic for a given date and context. The caller persists the result with insert-if-absent, so once a
     * day's quests exist they never change, even if the context does — quests cannot be re-rolled for more XP.
     */
    fun generate(date: LocalDate, context: QuestContext): List<Quest> {
        val eligible = templates.values.filter { template ->
            when (template.type) {
                QuestType.COMPLETE_ONE_HABIT -> context.hasHabits
                QuestType.FINISH_POSTPONED_TASK -> context.hasPostponedTasks
                QuestType.COMPLETE_TOP_PRIORITY, QuestType.FINISH_THREE_TASKS -> context.hasOpenTasks
                else -> true
            }
        }.sortedBy { it.type.ordinal }
        if (eligible.isEmpty()) return emptyList()

        val seed = date.toEpochDays()
        val chosen = mutableListOf<QuestTemplate>()
        // Always lead with the most meaningful quest available, then rotate the rest by date.
        eligible.firstOrNull { it.type == QuestType.COMPLETE_TOP_PRIORITY }?.let { chosen += it }
        val rest = eligible.filter { it !in chosen }
        val offset = floorMod(seed, rest.size.coerceAtLeast(1).toLong()).toInt()
        val rotated = rest.drop(offset) + rest.take(offset)
        chosen += rotated.take(QUESTS_PER_DAY - chosen.size)

        return chosen.map { t ->
            Quest(
                id = questId(date, t.type),
                date = date,
                type = t.type,
                title = t.title,
                description = t.description,
                target = t.target,
                rewardXp = t.rewardXp,
            )
        }
    }

    fun progressFor(quest: Quest, day: QuestDayProgress): Int {
        val value = when (quest.type) {
            QuestType.COMPLETE_TOP_PRIORITY -> day.topPrioritiesCompleted
            QuestType.FINISH_THREE_TASKS -> day.tasksCompleted
            QuestType.FOCUS_TWENTY_MINUTES -> day.focusMinutes
            QuestType.COMPLETE_ONE_HABIT -> day.habitsCompleted
            QuestType.PLAN_TOMORROW -> if (day.tomorrowPlanned) 1 else 0
            QuestType.FINISH_POSTPONED_TASK -> day.postponedTasksCompleted
            QuestType.TAKE_A_BREAK -> day.breaksTaken
        }
        return value.coerceIn(0, quest.target)
    }

    /** Returns the updated quest. Completed and skipped quests are terminal. */
    fun advance(quest: Quest, day: QuestDayProgress, now: kotlin.time.Instant): Quest {
        if (quest.status != QuestStatus.ACTIVE) return quest
        val progress = progressFor(quest, day)
        return if (progress >= quest.target) {
            quest.copy(progress = progress, status = QuestStatus.COMPLETED, completedAt = now)
        } else {
            quest.copy(progress = progress)
        }
    }
}

private fun floorMod(a: Long, b: Long): Long = ((a % b) + b) % b
