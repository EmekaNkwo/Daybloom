package com.daybloom.domain.models

import kotlinx.datetime.LocalDate
import kotlin.time.Instant

data class DailyPlan(
    val date: LocalDate,
    /** Up to three task ids, in order. */
    val topPriorityTaskIds: List<String> = emptyList(),
    /** Tasks the user explicitly planned for this day, in addition to tasks due that day. */
    val plannedTaskIds: List<String> = emptyList(),
    val availableMinutes: Int? = null,
    val breakMinutes: Int = 0,
    val energy: EnergyLevel? = null,
    /** A planned rest day is excluded from averages and never scored as a failure. */
    val isRestDay: Boolean = false,
    /** The user saw the over-capacity warning and chose to keep the plan. */
    val acceptedOverCapacity: Boolean = false,
    val notes: String = "",
    val createdAt: Instant,
    val updatedAt: Instant = createdAt,
) {
    init {
        require(topPriorityTaskIds.size <= MAX_TOP_PRIORITIES) { "At most $MAX_TOP_PRIORITIES top priorities" }
    }

    companion object {
        const val MAX_TOP_PRIORITIES = 3
    }
}

/**
 * How a calendar day is treated by analytics. Untracked days are never silently scored as zero,
 * and low-scoring planned days are never dropped.
 */
enum class DayClassification(val label: String) {
    /** A plan exists or tasks were due — scored. */
    PLANNED("Planned day"),
    /** No plan or due tasks, but activity (completions, focus, habits) was recorded — scored. */
    ACTIVITY_ONLY("Unplanned activity"),
    /** The user marked the day as rest — not scored, not counted against consistency. */
    REST("Rest day"),
    /** Nothing planned and nothing recorded — not scored. */
    NO_DATA("No data"),
}
