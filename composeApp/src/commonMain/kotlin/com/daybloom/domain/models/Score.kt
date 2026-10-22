package com.daybloom.domain.models

import kotlinx.datetime.LocalDate
import kotlin.time.Instant

enum class ScoreDimension(val label: String, val defaultWeight: Double, val description: String) {
    TASK_COMPLETION("Task completion", 0.35, "Planned tasks you finished, weighted by priority"),
    IMPORTANT_TASKS("Important tasks", 0.30, "Top priorities and urgent or high-priority tasks"),
    FOCUS_TIME("Focus time", 0.15, "Focused minutes against your daily focus goal"),
    HABIT_CONSISTENCY("Habit consistency", 0.10, "Scheduled habits you completed"),
    PLANNING_QUALITY("Planning quality", 0.10, "Planning the day realistically and following through"),
}

data class DimensionScore(
    val dimension: ScoreDimension,
    /** 0..100, or null when there is not enough data (excluded and weights renormalized). */
    val score: Int?,
    /** Effective share of the overall score after renormalization, 0..1. Zero when excluded. */
    val effectiveWeight: Double,
    val detail: String,
)

data class DailyScore(
    val date: LocalDate,
    val classification: DayClassification,
    /** 0..100, or null for rest days and days without enough data. */
    val overall: Int?,
    val dimensions: List<DimensionScore>,
    val computedAt: Instant,
) {
    val isScored: Boolean get() = overall != null
}
