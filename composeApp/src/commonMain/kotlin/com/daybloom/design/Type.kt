package com.daybloom.design

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * Hierarchy comes from weight contrast more than size: bold titles, regular body, semibold labels.
 * Screen titles use headlineSmall; card titles titleMedium; section labels labelSmall (uppercase, tracked).
 */
internal val BloomTypography: Typography = Typography().let { base ->
    base.copy(
        displayLarge = base.displayLarge.copy(fontWeight = FontWeight.Bold),
        displayMedium = base.displayMedium.copy(fontWeight = FontWeight.Bold),
        displaySmall = base.displaySmall.copy(fontWeight = FontWeight.Bold, letterSpacing = (-0.5).sp),
        headlineLarge = base.headlineLarge.copy(fontWeight = FontWeight.Bold, letterSpacing = (-0.4).sp),
        headlineMedium = base.headlineMedium.copy(fontWeight = FontWeight.Bold, letterSpacing = (-0.3).sp),
        headlineSmall = base.headlineSmall.copy(fontWeight = FontWeight.Bold, letterSpacing = (-0.2).sp),
        titleLarge = base.titleLarge.copy(fontWeight = FontWeight.SemiBold),
        titleMedium = base.titleMedium.copy(fontWeight = FontWeight.SemiBold, letterSpacing = 0.sp),
        titleSmall = base.titleSmall.copy(fontWeight = FontWeight.SemiBold, letterSpacing = 0.sp),
        bodyLarge = base.bodyLarge.copy(lineHeight = 24.sp, letterSpacing = 0.2.sp),
        bodyMedium = base.bodyMedium.copy(lineHeight = 21.sp),
        bodySmall = base.bodySmall.copy(lineHeight = 18.sp),
        labelLarge = base.labelLarge.copy(fontWeight = FontWeight.SemiBold, letterSpacing = 0.2.sp),
        labelMedium = base.labelMedium.copy(fontWeight = FontWeight.Medium),
        labelSmall = TextStyle(fontSize = 11.sp, lineHeight = 16.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.9.sp),
    )
}
