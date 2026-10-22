package com.daybloom.domain.models

import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import kotlinx.serialization.Serializable

enum class RecurrenceFrequency { DAILY, WEEKLY, MONTHLY, YEARLY }

/**
 * A deliberately small recurrence model (a subset of RFC 5545 RRULE) that covers the patterns the app
 * offers in its UI and quick-capture parser. Evaluated by [com.daybloom.domain.services.RecurrenceCalculator].
 */
@Serializable
data class RecurrenceRule(
    val frequency: RecurrenceFrequency,
    val interval: Int = 1,
    /** For WEEKLY rules: which weekdays. Empty means "same weekday as the start date". */
    val daysOfWeek: Set<DayOfWeek> = emptySet(),
    val until: LocalDate? = null,
    val count: Int? = null,
) {
    init {
        require(interval >= 1) { "Recurrence interval must be at least 1" }
        require(count == null || count >= 1) { "Recurrence count must be positive" }
    }

    fun describe(): String {
        val unit = when (frequency) {
            RecurrenceFrequency.DAILY -> if (interval == 1) "Every day" else "Every $interval days"
            RecurrenceFrequency.WEEKLY -> {
                val base = if (interval == 1) "Every week" else "Every $interval weeks"
                if (daysOfWeek.isEmpty()) base
                else if (daysOfWeek.size == 7) "Every day"
                else if (daysOfWeek == WEEKDAYS) "Every weekday"
                else "$base on " + daysOfWeek.sortedBy { it.ordinal }.joinToString(", ") { it.shortName() }
            }
            RecurrenceFrequency.MONTHLY -> if (interval == 1) "Every month" else "Every $interval months"
            RecurrenceFrequency.YEARLY -> if (interval == 1) "Every year" else "Every $interval years"
        }
        return when {
            until != null -> "$unit until $until"
            count != null -> "$unit, $count times"
            else -> unit
        }
    }

    companion object {
        val WEEKDAYS = setOf(
            DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY, DayOfWeek.FRIDAY,
        )
        fun daily() = RecurrenceRule(RecurrenceFrequency.DAILY)
        fun weekly(vararg days: DayOfWeek) = RecurrenceRule(RecurrenceFrequency.WEEKLY, daysOfWeek = days.toSet())
        fun monthly() = RecurrenceRule(RecurrenceFrequency.MONTHLY)
    }
}

fun DayOfWeek.shortName(): String = name.take(3).lowercase().replaceFirstChar { it.uppercase() }
fun DayOfWeek.displayName(): String = name.lowercase().replaceFirstChar { it.uppercase() }
