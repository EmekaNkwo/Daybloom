package com.daybloom.design

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp

object Spacing {
    val xxs = 2.dp
    val xs = 4.dp
    val sm = 8.dp
    val md = 12.dp
    val lg = 16.dp
    val xl = 24.dp
    val xxl = 32.dp

    // Semantic spacing. Related items sit tight (sm–md); sections are separated by noticeably more (sectionGap)
    // so grouping reads from proximity alone.
    val screenGutter = 16.dp
    val cardPadding = 20.dp
    val itemGap = md
    val sectionGap = md
    val screenTop = md
    val fabClearance = 96.dp
    val maxContentWidth = 640.dp
    val minTouchTarget = 48.dp
}

/**
 * Soft, indigo-tinted depth. Cards float slightly off the periwinkle background; featured cards a little more.
 * Interactive elements (buttons, chips) stay flat so depth signals "container", not "pressable".
 */
object Elevation {
    val card = 10.dp
    val featured = 18.dp
    val navigation = 16.dp
    const val SHADOW_ALPHA = 0.14f
}

object Motion {
    const val SHORT_MS = 180
    const val MEDIUM_MS = 320
    const val LONG_MS = 600
}

/**
 * Radii scale with component size: fields and chips are gently rounded, cards generously, sheets and dialogs most.
 * Material reads text fields, menus and snackbars from extraSmall/small, cards from large, sheets/dialogs from
 * extraLarge.
 */
internal val BloomShapes = Shapes(
    extraSmall = RoundedCornerShape(12.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(18.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(32.dp),
)
