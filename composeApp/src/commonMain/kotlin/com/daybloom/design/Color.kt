package com.daybloom.design

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

/**
 * Raw palette. Screens never use these directly: they go through [LightBloomScheme]/[DarkBloomScheme]
 * (Material roles) or [BloomColors] (roles Material doesn't cover).
 *
 * Brand: royal indigo. Actions: a clear blue, so the next step always stands out from brand chrome.
 * Surfaces: cool periwinkle backgrounds with white content cards.
 */
internal object BloomPalette {
    val Indigo = Color(0xFF3A2BB5)
    val IndigoBright = Color(0xFF5A48E0)
    val IndigoDeep = Color(0xFF241A86)
    val IndigoMist = Color(0xFFE5E1FF)
    val Lavender = Color(0xFFBDB4FF)

    val Blue = Color(0xFF1E6FE0)
    val BlueSky = Color(0xFF5BB4F8)
    val BlueMist = Color(0xFFDCE8FF)

    val Teal = Color(0xFF0F7F95)

    val Periwinkle = Color(0xFFF2F3FF)
    val Ink = Color(0xFF16163F)
    val InkSoft = Color(0xFF575D82)
    val NavyText = Color(0xFF071A3D)

    val Night = Color(0xFF0E0F27)
    val NightSurface = Color(0xFF1A1C3C)
    val NightSurfaceHigh = Color(0xFF262A52)
    val Moon = Color(0xFFE8E9FF)
    val MoonSoft = Color(0xFFA9AED3)
}

internal val LightBloomScheme: ColorScheme = lightColorScheme(
    primary = BloomPalette.Indigo,
    onPrimary = Color.White,
    primaryContainer = BloomPalette.IndigoMist,
    onPrimaryContainer = Color(0xFF170A6B),
    inversePrimary = BloomPalette.Lavender,
    secondary = Color(0xFF3D63D6),
    onSecondary = Color.White,
    secondaryContainer = BloomPalette.BlueMist,
    onSecondaryContainer = Color(0xFF0B2A6B),
    tertiary = BloomPalette.Teal,
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFCDEFF5),
    onTertiaryContainer = Color(0xFF00313B),
    background = BloomPalette.Periwinkle,
    onBackground = BloomPalette.Ink,
    surface = BloomPalette.Periwinkle,
    onSurface = BloomPalette.Ink,
    surfaceVariant = Color(0xFFE4E6F7),
    onSurfaceVariant = BloomPalette.InkSoft,
    surfaceTint = BloomPalette.Indigo,
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color(0xFFF8F8FF),
    surfaceContainer = Color(0xFFECEEFC),
    surfaceContainerHigh = Color(0xFFE6E8FA),
    surfaceContainerHighest = Color(0xFFDFE2F6),
    inverseSurface = Color(0xFF1E1D4F),
    inverseOnSurface = Color(0xFFF0F0FF),
    outline = Color(0xFF7A7FA3),
    outlineVariant = Color(0xFFD5D8EE),
    scrim = Color(0xFF0B0B2A),
    error = Color(0xFFC62A3B),
    onError = Color.White,
    errorContainer = Color(0xFFFFDDE0),
    onErrorContainer = Color(0xFF410009),
)

internal val DarkBloomScheme: ColorScheme = darkColorScheme(
    primary = BloomPalette.Lavender,
    onPrimary = Color(0xFF22137A),
    primaryContainer = BloomPalette.Indigo,
    onPrimaryContainer = BloomPalette.IndigoMist,
    inversePrimary = BloomPalette.Indigo,
    secondary = Color(0xFFA9C4FF),
    onSecondary = Color(0xFF0B2A6B),
    secondaryContainer = Color(0xFF2B3F86),
    onSecondaryContainer = BloomPalette.BlueMist,
    tertiary = Color(0xFF7FD3E4),
    onTertiary = Color(0xFF00363F),
    tertiaryContainer = Color(0xFF0D4F5C),
    onTertiaryContainer = Color(0xFFCDEFF5),
    background = BloomPalette.Night,
    onBackground = BloomPalette.Moon,
    surface = BloomPalette.Night,
    onSurface = BloomPalette.Moon,
    surfaceVariant = BloomPalette.NightSurfaceHigh,
    onSurfaceVariant = BloomPalette.MoonSoft,
    surfaceTint = BloomPalette.Lavender,
    surfaceContainerLowest = Color(0xFF15173A),
    surfaceContainerLow = Color(0xFF17193A),
    surfaceContainer = BloomPalette.NightSurface,
    surfaceContainerHigh = Color(0xFF212449),
    surfaceContainerHighest = Color(0xFF2B2F58),
    inverseSurface = BloomPalette.Moon,
    inverseOnSurface = Color(0xFF1E1D4F),
    outline = Color(0xFF8C91B8),
    outlineVariant = Color(0xFF353A66),
    scrim = Color.Black,
    error = Color(0xFFFFB3BA),
    onError = Color(0xFF680016),
    errorContainer = Color(0xFF93001F),
    onErrorContainer = Color(0xFFFFDDE0),
)

/** Semantic colors that Material's scheme does not cover: actions, featured surfaces, categories, priorities. */
data class BloomColors(
    /** Primary call-to-action buttons and the add button. */
    val action: Color,
    val onAction: Color,
    /** Call-to-action on top of a featured (indigo) surface. */
    val actionOnFeatured: Color,
    val onActionOnFeatured: Color,
    /** Featured surfaces: a soft indigo gradient used for at most one highlight per screen. */
    val featuredStart: Color,
    val featuredEnd: Color,
    val onFeatured: Color,
    val onFeaturedMuted: Color,
    /** Tint for soft shadows; transparent where depth comes from surface contrast instead (dark theme). */
    val shadow: Color,
    val success: Color,
    val warning: Color,
    val info: Color,
    val scoreLow: Color,
    val scoreMid: Color,
    val scoreHigh: Color,
    val xp: Color,
    val urgent: Color,
    val high: Color,
    val normal: Color,
    val low: Color,
    val categoryWork: Color,
    val categoryPersonal: Color,
    val categoryHealth: Color,
    val categoryFinance: Color,
    val categoryFamily: Color,
    val categoryLearning: Color,
    val categoryErrands: Color,
    val categoryOther: Color,
    val gardenSky: Color,
    val gardenSkyLow: Color,
    val gardenSoil: Color,
    val gardenGrass: Color,
    val gardenStem: Color,
)

internal val LightBloomColors = BloomColors(
    action = BloomPalette.Blue,
    onAction = Color.White,
    actionOnFeatured = BloomPalette.BlueSky,
    onActionOnFeatured = BloomPalette.NavyText,
    featuredStart = BloomPalette.IndigoDeep,
    featuredEnd = BloomPalette.IndigoBright,
    onFeatured = Color.White,
    onFeaturedMuted = Color(0xFFE3DFFF),
    shadow = Color(0xFF3A2BB5),
    success = Color(0xFF1E8A5A),
    warning = Color(0xFF9A5200),
    info = BloomPalette.Blue,
    scoreLow = Color(0xFFE07A55),
    scoreMid = Color(0xFFE5A93A),
    scoreHigh = Color(0xFF22A06B),
    xp = Color(0xFF6E45E8),
    urgent = Color(0xFFD12E45),
    high = Color(0xFFD9701C),
    normal = Color(0xFF3D63D6),
    low = Color(0xFF7D84A8),
    categoryWork = Color(0xFF3D63D6),
    categoryPersonal = Color(0xFFD94C7A),
    categoryHealth = Color(0xFF1E8A5A),
    categoryFinance = Color(0xFFC07A12),
    categoryFamily = Color(0xFFB04FC4),
    categoryLearning = Color(0xFF6E45E8),
    categoryErrands = Color(0xFF0F7F95),
    categoryOther = Color(0xFF7D84A8),
    gardenSky = Color(0xFFE6E3FF),
    gardenSkyLow = Color(0xFFE2F0FF),
    gardenSoil = Color(0xFF8F6A55),
    gardenGrass = Color(0xFF79B98A),
    gardenStem = Color(0xFF3F7D5C),
)

internal val DarkBloomColors = BloomColors(
    action = Color(0xFF6AA8FF),
    onAction = BloomPalette.NavyText,
    actionOnFeatured = BloomPalette.BlueSky,
    onActionOnFeatured = BloomPalette.NavyText,
    featuredStart = Color(0xFF2A1F95),
    featuredEnd = Color(0xFF4A3BC8),
    onFeatured = Color.White,
    onFeaturedMuted = Color(0xFFCFC9FF),
    shadow = Color.Transparent,
    success = Color(0xFF6FD3A0),
    warning = Color(0xFFF3BE6B),
    info = Color(0xFF8DB6FF),
    scoreLow = Color(0xFFF2A07F),
    scoreMid = Color(0xFFF3C96F),
    scoreHigh = Color(0xFF6FD3A0),
    xp = Color(0xFFB7A2FF),
    urgent = Color(0xFFFF8A9A),
    high = Color(0xFFF7AE6B),
    normal = Color(0xFFA9C4FF),
    low = Color(0xFFA9AED3),
    categoryWork = Color(0xFFA9C4FF),
    categoryPersonal = Color(0xFFFF9FC0),
    categoryHealth = Color(0xFF6FD3A0),
    categoryFinance = Color(0xFFF3C96F),
    categoryFamily = Color(0xFFE6A6F2),
    categoryLearning = Color(0xFFB7A2FF),
    categoryErrands = Color(0xFF7FD3E4),
    categoryOther = Color(0xFFA9AED3),
    gardenSky = Color(0xFF26215A),
    gardenSkyLow = Color(0xFF1B2547),
    gardenSoil = Color(0xFF5E4536),
    gardenGrass = Color(0xFF3C6E50),
    gardenStem = Color(0xFF79C29A),
)
