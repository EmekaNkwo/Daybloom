package com.daybloom.design

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import com.daybloom.domain.models.ThemeMode

val LocalBloomColors = staticCompositionLocalOf { LightBloomColors }

/** True when the user (or the OS) asked for reduced motion; animations should be skipped or shortened. */
val LocalReducedMotion = staticCompositionLocalOf { false }

/** True when gamification visuals (XP, garden, quests) should be shown. Task management never depends on it. */
val LocalGamificationEnabled = staticCompositionLocalOf { true }

object BloomTheme {
    val colors: BloomColors
        @Composable @ReadOnlyComposable get() = LocalBloomColors.current
    val reducedMotion: Boolean
        @Composable @ReadOnlyComposable get() = LocalReducedMotion.current
}

@Composable
fun DayBloomTheme(
    themeMode: ThemeMode = ThemeMode.SYSTEM,
    reducedMotion: Boolean = false,
    gamificationEnabled: Boolean = true,
    content: @Composable () -> Unit,
) {
    val dark = when (themeMode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
    SystemBarAppearance(dark)
    CompositionLocalProvider(
        LocalBloomColors provides if (dark) DarkBloomColors else LightBloomColors,
        LocalReducedMotion provides reducedMotion,
        LocalGamificationEnabled provides gamificationEnabled,
    ) {
        MaterialTheme(
            colorScheme = if (dark) DarkBloomScheme else LightBloomScheme,
            typography = bloomTypography(),
            shapes = BloomShapes,
            content = content,
        )
    }
}
