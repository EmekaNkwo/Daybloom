package com.daybloom.domain.models

import kotlinx.datetime.LocalDate
import kotlin.time.Instant

data class DailyReflection(
    val date: LocalDate,
    val wentWell: String = "",
    val biggestWin: String = "",
    val moveToTomorrow: String = "",
    /** Private and never used for scoring. */
    val mood: Mood? = null,
    val tomorrowTopPriority: String = "",
    val updatedAt: Instant,
) {
    val isEmpty: Boolean
        get() = wentWell.isBlank() && biggestWin.isBlank() && moveToTomorrow.isBlank() &&
            mood == null && tomorrowTopPriority.isBlank()
}

data class ManualWin(
    val id: String,
    val date: LocalDate,
    val text: String,
    val createdAt: Instant,
)
