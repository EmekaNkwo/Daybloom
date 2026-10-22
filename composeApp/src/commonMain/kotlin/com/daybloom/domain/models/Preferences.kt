package com.daybloom.domain.models

import kotlinx.datetime.LocalTime

enum class GardenTheme(val label: String) { MEADOW("Meadow"), ZEN("Zen"), MOONLIT("Moonlit") }

data class UserPreferences(
    val displayName: String = "",
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val reducedMotion: Boolean = false,
    val hapticsEnabled: Boolean = true,
    val gamificationEnabled: Boolean = true,
    val suggestionsEnabled: Boolean = true,
    val enabledScoreDimensions: Set<ScoreDimension> = ScoreDimension.entries.toSet(),
    val dailyFocusGoalMinutes: Int = 60,
    val defaultAvailableMinutes: Int = 6 * 60,
    val quietHours: QuietHours? = QuietHours(LocalTime(22, 0), LocalTime(7, 0)),
    val unfinishedTaskPolicy: UnfinishedTaskPolicy = UnfinishedTaskPolicy.ASK,
    val gardenTheme: GardenTheme = GardenTheme.MEADOW,
    val lastCelebratedLevel: Int = 1,
    val onboardingComplete: Boolean = false,
    val planningReminderTime: LocalTime? = null,
)
