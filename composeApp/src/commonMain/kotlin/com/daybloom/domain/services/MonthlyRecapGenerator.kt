package com.daybloom.domain.services

import com.daybloom.domain.models.ScoreDimension
import kotlinx.datetime.LocalDate
import kotlinx.datetime.number

data class RecapAchievement(val title: String, val emoji: String)

data class MonthlyRecap(
    val month: LocalDate,
    val headline: String,
    val highlights: List<String>,
    /** One improvement idea, or null when the data can't support one. */
    val suggestion: String?,
    val dataNote: String?,
    val achievements: List<RecapAchievement>,
)

/**
 * Builds the monthly recap from real numbers only. When there are fewer than
 * [MonthlyAnalyticsCalculator.MIN_DAYS_FOR_TRENDS] scored days, it says more data is needed rather than
 * inventing patterns.
 */
object MonthlyRecapGenerator {

    fun generate(insights: MonthlyInsights, achievements: List<RecapAchievement>): MonthlyRecap {
        val monthName = monthName(insights.month)
        val highlights = buildList {
            insights.averageScore?.let { add("Average score $it across ${insights.scoredDays} scored days") }
            if (insights.tasksCompleted > 0) add("${insights.tasksCompleted} tasks completed")
            if (insights.focusMinutes > 0) add("${formatHours(insights.focusMinutes)} of focus")
            insights.habitCompletionPercent?.let { add("Habits completed $it% of scheduled days") }
            if (insights.restDays > 0) add("${insights.restDays} planned rest day${if (insights.restDays == 1) "" else "s"}")
            if (insights.hasEnoughDataForTrends) {
                insights.strongestCategory?.let { add("Strongest category: ${it.label}") }
                insights.bestDay?.let { add("Best day: ${monthName} ${it.day}") }
                if (insights.bestStreak >= 2) add("Best streak: ${insights.bestStreak} days in a row")
                add("Showed up on ${insights.consistencyPercent}% of available days")
            }
        }
        val headline = when {
            insights.scoredDays == 0 && insights.tasksCompleted == 0 -> "$monthName is just getting started."
            !insights.hasEnoughDataForTrends -> "A few days of $monthName in the books."
            (insights.averageScore ?: 0) >= 75 -> "$monthName was a month of real momentum."
            else -> "You kept moving forward in $monthName."
        }
        val dataNote = if (!insights.hasEnoughDataForTrends) {
            "Patterns need at least ${MonthlyAnalyticsCalculator.MIN_DAYS_FOR_TRENDS} scored days; " +
                "this month has ${insights.scoredDays}. Keep going and trends will appear here."
        } else {
            null
        }
        return MonthlyRecap(
            month = insights.month,
            headline = headline,
            highlights = highlights,
            suggestion = if (insights.hasEnoughDataForTrends) suggestion(insights) else null,
            dataNote = dataNote,
            achievements = achievements,
        )
    }

    private fun suggestion(insights: MonthlyInsights): String? {
        val weakest = insights.dimensionAverages.minByOrNull { it.value } ?: return null
        if (weakest.value >= 80) return "Everything looks balanced. Consider protecting one more rest day next month."
        return when (weakest.key) {
            ScoreDimension.TASK_COMPLETION -> "Plans ran fuller than the days allowed. Try planning one less task per day next month."
            ScoreDimension.IMPORTANT_TASKS -> "Top priorities were the hardest to finish. Try doing your first priority before checking messages."
            ScoreDimension.FOCUS_TIME -> "Focus time was lighter than your goal. A single 25-minute session most days would close the gap."
            ScoreDimension.HABIT_CONSISTENCY -> "Habits slipped on busy days. Consider lowering a weekly target so it fits real weeks."
            ScoreDimension.PLANNING_QUALITY -> "Plans often exceeded available time. Try leaving a buffer when you plan."
        }
    }

    /** Plain-text recap for sharing. Task titles, notes and reflections are never included unless [includePrivate]. */
    fun shareableText(recap: MonthlyRecap, insights: MonthlyInsights, includePrivate: Boolean, privateLines: List<String> = emptyList()): String =
        buildString {
            appendLine("🌸 DayBloom — ${monthName(recap.month)} ${recap.month.year} recap")
            appendLine(recap.headline)
            appendLine()
            recap.highlights.forEach { appendLine("• $it") }
            if (recap.achievements.isNotEmpty()) {
                appendLine()
                appendLine("Achievements: " + recap.achievements.joinToString(", ") { "${it.emoji} ${it.title}" })
            }
            recap.suggestion?.let { appendLine(); appendLine("Next month: $it") }
            recap.dataNote?.let { appendLine(); appendLine(it) }
            if (includePrivate && privateLines.isNotEmpty()) {
                appendLine()
                privateLines.forEach { appendLine("– $it") }
            }
            appendLine()
            append("Scored days: ${insights.scoredDays} · Rest days: ${insights.restDays}")
        }

    fun monthName(date: LocalDate): String = MONTHS[date.month.number - 1]

    fun formatHours(minutes: Int): String {
        val h = minutes / 60
        val m = minutes % 60
        return when {
            h == 0 -> "$m min"
            m == 0 -> "$h h"
            else -> "$h h $m min"
        }
    }

    private val MONTHS = listOf(
        "January", "February", "March", "April", "May", "June",
        "July", "August", "September", "October", "November", "December",
    )
}
