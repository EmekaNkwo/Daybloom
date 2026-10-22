package com.daybloom.domain.services

import kotlinx.datetime.LocalTime

sealed interface ValidationResult {
    data object Valid : ValidationResult
    data class Invalid(val errors: Map<String, String>) : ValidationResult
}

/** Input validation shared by forms and use cases. Messages are user-facing. */
object Validation {
    const val MAX_TITLE = 200
    const val MAX_TEXT = 5_000
    const val MAX_TAGS = 10
    const val MAX_ESTIMATE_MINUTES = 16 * 60

    fun title(value: String): String? = when {
        value.isBlank() -> "Give it a short title"
        value.length > MAX_TITLE -> "Keep the title under $MAX_TITLE characters"
        else -> null
    }

    fun longText(value: String, label: String): String? =
        if (value.length > MAX_TEXT) "$label is too long" else null

    fun estimate(minutes: Int?): String? = when {
        minutes == null -> null
        minutes <= 0 -> "Estimate must be more than 0 minutes"
        minutes > MAX_ESTIMATE_MINUTES -> "That's longer than a day — try splitting it up"
        else -> null
    }

    fun timeRange(start: LocalTime, end: LocalTime): String? =
        if (end <= start) "End time must be after the start time" else null

    fun tags(tags: List<String>): String? = when {
        tags.size > MAX_TAGS -> "Use at most $MAX_TAGS tags"
        tags.any { it.length > 30 } -> "Tags must be 30 characters or fewer"
        else -> null
    }

    fun combine(vararg checks: Pair<String, String?>): ValidationResult {
        val errors = checks.mapNotNull { (field, error) -> error?.let { field to it } }.toMap()
        return if (errors.isEmpty()) ValidationResult.Valid else ValidationResult.Invalid(errors)
    }
}
