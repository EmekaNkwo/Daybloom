package com.daybloom.design

import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.daybloom.resources.Res
import com.daybloom.resources.poppins_bold
import com.daybloom.resources.poppins_medium
import com.daybloom.resources.poppins_regular
import com.daybloom.resources.poppins_semibold
import org.jetbrains.compose.resources.Font

/** Poppins (SIL Open Font License, bundled with its licence under composeResources/files/licenses). */
@Composable
private fun poppins(): FontFamily = FontFamily(
    Font(Res.font.poppins_regular, FontWeight.Normal),
    Font(Res.font.poppins_medium, FontWeight.Medium),
    Font(Res.font.poppins_semibold, FontWeight.SemiBold),
    Font(Res.font.poppins_bold, FontWeight.Bold),
)

/**
 * Hierarchy comes from weight contrast more than size: bold titles, regular body, semibold labels.
 * Screen titles use headlineSmall; section titles 18sp bold (SectionHeader); card titles titleMedium;
 * small eyebrow labels inside cards labelSmall (uppercase, tracked).
 * Poppins runs wide, so body sizes stay a step smaller than Material's defaults would suggest and tracking stays at 0.
 */
@Composable
internal fun bloomTypography(): Typography {
    val family = poppins()
    val base = Typography()
    fun TextStyle.poppins() = copy(fontFamily = family)
    return base.copy(
        displayLarge = base.displayLarge.copy(fontWeight = FontWeight.Bold).poppins(),
        displayMedium = base.displayMedium.copy(fontWeight = FontWeight.Bold).poppins(),
        displaySmall = base.displaySmall.copy(fontWeight = FontWeight.Bold, letterSpacing = (-0.5).sp).poppins(),
        headlineLarge = base.headlineLarge.copy(fontWeight = FontWeight.Bold, letterSpacing = (-0.4).sp).poppins(),
        headlineMedium = base.headlineMedium.copy(fontWeight = FontWeight.Bold, letterSpacing = (-0.3).sp).poppins(),
        headlineSmall = base.headlineSmall.copy(fontWeight = FontWeight.Bold, letterSpacing = (-0.2).sp).poppins(),
        titleLarge = base.titleLarge.copy(fontWeight = FontWeight.SemiBold).poppins(),
        titleMedium = base.titleMedium.copy(fontWeight = FontWeight.SemiBold, letterSpacing = 0.sp).poppins(),
        titleSmall = base.titleSmall.copy(fontWeight = FontWeight.SemiBold, letterSpacing = 0.sp).poppins(),
        bodyLarge = base.bodyLarge.copy(fontSize = 15.sp, lineHeight = 24.sp, letterSpacing = 0.sp).poppins(),
        bodyMedium = base.bodyMedium.copy(fontSize = 14.sp, lineHeight = 21.sp, letterSpacing = 0.sp).poppins(),
        bodySmall = base.bodySmall.copy(fontSize = 12.sp, lineHeight = 18.sp, letterSpacing = 0.sp).poppins(),
        labelLarge = base.labelLarge.copy(fontWeight = FontWeight.SemiBold, letterSpacing = 0.1.sp).poppins(),
        labelMedium = base.labelMedium.copy(fontWeight = FontWeight.Medium, letterSpacing = 0.sp).poppins(),
        labelSmall = TextStyle(fontSize = 11.sp, lineHeight = 16.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.8.sp).poppins(),
    )
}
