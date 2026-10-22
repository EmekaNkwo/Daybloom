package com.daybloom.domain.services

import com.daybloom.domain.models.DailyScore
import com.daybloom.domain.models.DayClassification
import com.daybloom.domain.models.DimensionScore
import com.daybloom.domain.models.Priority
import com.daybloom.domain.models.ScoreDimension
import com.daybloom.domain.models.TaskStatus
import kotlinx.datetime.LocalDate
import kotlin.math.roundToInt
import kotlin.time.Instant

/** A task as seen by the scoring engine for one day. */
data class ScoredTask(
    val id: String,
    val priority: Priority,
    val status: TaskStatus,
    val isTopPriority: Boolean,
    /** True when the task was completed on or before the scored day. */
    val completedByDay: Boolean,
)

/** Everything the scoring engine needs to know about one day. Built by [com.daybloom.domain.usecases.BuildDayScoreInputUseCase]. */
data class DayScoreInput(
    val date: LocalDate,
    val isRestDay: Boolean,
    val hasPlan: Boolean,
    /** Tasks due that day, planned for that day, or completed that day. Cancelled tasks may be included; they are ignored. */
    val tasks: List<ScoredTask>,
    val focusMinutes: Int,
    val focusGoalMinutes: Int,
    /** Whether the user uses focus sessions at all. A user who never does is not scored on it. */
    val focusTracked: Boolean,
    val habitsScheduled: Int,
    val habitsCompleted: Int,
    val topPrioritiesSet: Int,
    val plannedMinutes: Int,
    val availableMinutes: Int?,
    val acceptedOverCapacity: Boolean,
    /** Any completion, focus or habit recorded that day. */
    val hasActivity: Boolean,
)

/**
 * Transparent 0–100 daily productivity score.
 *
 * Each enabled dimension is scored 0–100, or excluded when there is not enough data for it. The remaining
 * weights are renormalized, and the overall score is the weighted mean of the **rounded** dimension scores that
 * are shown to the user — so the breakdown always reproduces the headline number exactly.
 *
 * Rules (see README "Scoring"):
 * - Priorities matter more than quantity: task completion averages per-priority-tier completion rates, so adding
 *   many trivial tasks cannot move the score more than one tier's weight.
 * - Cancelled tasks are neither successes nor failures.
 * - Rest days and days without data are not scored (null), never zero.
 * - Appointments never count as tasks.
 * - Pure: identical input → identical output.
 */
class ProductivityScoreCalculator(
    private val weights: Map<ScoreDimension, Double> = ScoreDimension.entries.associateWith { it.defaultWeight },
) {

    fun calculate(
        input: DayScoreInput,
        enabled: Set<ScoreDimension>,
        computedAt: Instant,
    ): DailyScore {
        val active = input.tasks.filter { it.status != TaskStatus.CANCELLED }
        val classification = classify(input, active)

        if (classification == DayClassification.REST || classification == DayClassification.NO_DATA) {
            val dims = ScoreDimension.entries.map {
                DimensionScore(it, null, 0.0, if (classification == DayClassification.REST) "Rest day" else "No data")
            }
            return DailyScore(input.date, classification, null, dims, computedAt)
        }

        val raw = ScoreDimension.entries.map { dim ->
            if (dim !in enabled) {
                Triple(dim, null as Int?, "Not tracked")
            } else {
                val (score, detail) = when (dim) {
                    ScoreDimension.TASK_COMPLETION -> taskCompletion(active)
                    ScoreDimension.IMPORTANT_TASKS -> importantTasks(active)
                    ScoreDimension.FOCUS_TIME -> focus(input)
                    ScoreDimension.HABIT_CONSISTENCY -> habits(input)
                    ScoreDimension.PLANNING_QUALITY -> planning(input, active)
                }
                Triple(dim, score?.roundToInt()?.coerceIn(0, 100), detail)
            }
        }

        val weightSum = raw.filter { it.second != null }.sumOf { weightOf(it.first) }
        val dimensions = raw.map { (dim, score, detail) ->
            val share = if (score != null && weightSum > 0) weightOf(dim) / weightSum else 0.0
            DimensionScore(dim, score, share, detail)
        }
        val overall = if (weightSum > 0) {
            dimensions.sumOf { (it.score ?: 0) * it.effectiveWeight }.roundToInt().coerceIn(0, 100)
        } else {
            null
        }
        return DailyScore(input.date, classification, overall, dimensions, computedAt)
    }

    fun classify(input: DayScoreInput, activeTasks: List<ScoredTask> = input.tasks.filter { it.status != TaskStatus.CANCELLED }): DayClassification =
        when {
            input.isRestDay -> DayClassification.REST
            input.hasPlan || activeTasks.isNotEmpty() -> DayClassification.PLANNED
            input.hasActivity || input.habitsCompleted > 0 || input.focusMinutes > 0 -> DayClassification.ACTIVITY_ONLY
            else -> DayClassification.NO_DATA
        }

    private fun weightOf(dim: ScoreDimension): Double = weights[dim] ?: dim.defaultWeight

    /** Mean completion rate across priority tiers present that day, each tier weighted by priority. */
    private fun taskCompletion(tasks: List<ScoredTask>): Pair<Double?, String> {
        if (tasks.isEmpty()) return null to "No tasks planned"
        val tiers = tasks.groupBy { it.priority }
        val weighted = tiers.entries.sumOf { (priority, list) ->
            priority.weight * list.count { it.completedByDay }.toDouble() / list.size
        }
        val totalWeight = tiers.keys.sumOf { it.weight }
        val done = tasks.count { it.completedByDay }
        return (weighted / totalWeight * 100) to "$done of ${tasks.size} tasks done"
    }

    private fun importantTasks(tasks: List<ScoredTask>): Pair<Double?, String> {
        val important = tasks.filter { it.isTopPriority || it.priority.isImportant }
        if (important.isEmpty()) return null to "No top priorities or high-priority tasks"
        fun w(t: ScoredTask) = if (t.isTopPriority) TOP_PRIORITY_WEIGHT else 1.0
        val score = important.filter { it.completedByDay }.sumOf(::w) / important.sumOf(::w) * 100
        val done = important.count { it.completedByDay }
        return score to "$done of ${important.size} important tasks done"
    }

    private fun focus(input: DayScoreInput): Pair<Double?, String> {
        if (input.focusGoalMinutes <= 0) return null to "No focus goal set"
        if (!input.focusTracked && input.focusMinutes == 0) return null to "Focus sessions not used yet"
        val score = (input.focusMinutes.toDouble() / input.focusGoalMinutes * 100).coerceAtMost(100.0)
        return score to "${input.focusMinutes} of ${input.focusGoalMinutes} focus minutes"
    }

    private fun habits(input: DayScoreInput): Pair<Double?, String> {
        if (input.habitsScheduled <= 0) return null to "No habits scheduled"
        val done = input.habitsCompleted.coerceAtMost(input.habitsScheduled)
        return (done.toDouble() / input.habitsScheduled * 100) to "$done of ${input.habitsScheduled} habits"
    }

    /**
     * Planning quality, only scored when a plan exists:
     * 30 for making a plan, 25 for choosing top priorities, 20 for a realistic load, 25 for completing the
     * chosen top priorities.
     */
    private fun planning(input: DayScoreInput, tasks: List<ScoredTask>): Pair<Double?, String> {
        if (!input.hasPlan) return null to "No plan made"
        var score = PLAN_MADE
        if (input.topPrioritiesSet > 0) score += PRIORITIES_CHOSEN
        val available = input.availableMinutes
        score += when {
            available == null -> REALISTIC_LOAD / 2
            input.plannedMinutes <= available -> REALISTIC_LOAD
            input.acceptedOverCapacity -> REALISTIC_LOAD / 2
            else -> REALISTIC_LOAD / 4
        }
        val tops = tasks.filter { it.isTopPriority }
        if (tops.isNotEmpty()) {
            score += FOLLOW_THROUGH * tops.count { it.completedByDay } / tops.size
        }
        val detail = when {
            tops.isEmpty() -> "Plan made, no top priorities chosen"
            else -> "${tops.count { it.completedByDay }} of ${tops.size} top priorities done"
        }
        return score to detail
    }

    companion object {
        const val TOP_PRIORITY_WEIGHT = 2.0
        private const val PLAN_MADE = 30.0
        private const val PRIORITIES_CHOSEN = 25.0
        private const val REALISTIC_LOAD = 20.0
        private const val FOLLOW_THROUGH = 25.0
    }
}

/** Plain-language, guilt-free explanations of a score and how it changed. */
object ScoreExplainer {

    fun summary(score: DailyScore): String = when (score.classification) {
        DayClassification.REST -> "Today is a rest day. Rest is part of the plan."
        DayClassification.NO_DATA -> "Nothing planned yet. Add a task or plan your day to get a score."
        else -> {
            val overall = score.overall
            if (overall == null) {
                "Not enough data to score today yet."
            } else {
                val strongest = score.dimensions.filter { it.score != null }.maxByOrNull { it.score ?: 0 }
                buildString {
                    append(encouragement(overall))
                    strongest?.let { append(" Strongest area: ${it.dimension.label.lowercase()}.") }
                }
            }
        }
    }

    /** The weighted sum behind [DailyScore.overall], e.g. "Task completion 46 × 47% + Important tasks 33 × 40%". */
    fun formula(score: DailyScore): String? {
        if (score.overall == null) return null
        return score.dimensions.filter { it.score != null }.joinToString(" + ") {
            "${it.dimension.label} ${it.score} × ${(it.effectiveWeight * 100).roundToInt()}%"
        }.ifEmpty { null }
    }

    fun change(previous: DailyScore?, current: DailyScore): String? {
        val before = previous?.overall ?: return null
        val after = current.overall ?: return null
        val delta = after - before
        if (delta == 0) return null
        val moved = current.dimensions.mapNotNull { dim ->
            val old = previous.dimensions.firstOrNull { it.dimension == dim.dimension }?.score
            val new = dim.score
            if (old != null && new != null && old != new) Triple(dim.dimension, old, new) else null
        }.maxByOrNull { kotlin.math.abs(it.third - it.second) }
        val direction = if (delta > 0) "rose" else "dipped"
        val reason = moved?.let { " because ${it.first.label.lowercase()} went from ${it.second} to ${it.third}" } ?: ""
        return "Your score $direction ${kotlin.math.abs(delta)} points$reason."
    }

    private fun encouragement(score: Int): String = when {
        score >= 85 -> "A strong day."
        score >= 65 -> "Solid momentum."
        score >= 40 -> "You're making progress."
        else -> "Every small step counts."
    }
}
