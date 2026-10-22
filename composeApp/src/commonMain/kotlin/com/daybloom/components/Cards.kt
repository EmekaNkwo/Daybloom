package com.daybloom.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.unit.dp
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import com.daybloom.design.BloomColors
import com.daybloom.design.BloomTheme
import com.daybloom.design.Elevation
import com.daybloom.design.Spacing
import androidx.compose.ui.unit.sp
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Box

/**
 * Soft indigo-tinted shadow. Light theme only: in dark theme the tint is transparent and depth comes from
 * surface contrast, since shadows are invisible on dark backgrounds.
 */
@Composable
fun Modifier.softShadow(shape: Shape, elevation: Dp = Elevation.card): Modifier {
    val tint = BloomTheme.colors.shadow
    if (tint.alpha == 0f) return this
    val shadow = tint.copy(alpha = Elevation.SHADOW_ALPHA)
    return this.shadow(elevation, shape, clip = false, ambientColor = shadow, spotColor = shadow)
}

/**
 * The app's standard content surface: white, generously rounded, softly lifted off the background.
 * Used selectively — lists of tasks sit directly on the background. [tonal] is a quieter, unlifted variant
 * for supporting content such as suggestions.
 */
@Composable
fun BloomCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    tonal: Boolean = false,
    content: @Composable ColumnScope.() -> Unit,
) {
    val shape = MaterialTheme.shapes.large
    val colors = if (tonal) {
        CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.7f))
    } else {
        CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLowest)
    }
    val lifted = if (tonal) modifier.fillMaxWidth() else modifier.fillMaxWidth().softShadow(shape)
    val inner: @Composable ColumnScope.() -> Unit = {
        Column(Modifier.padding(Spacing.cardPadding), verticalArrangement = Arrangement.spacedBy(Spacing.sm), content = content)
    }
    if (onClick != null) {
        Card(onClick = onClick, modifier = lifted, colors = colors, shape = shape, content = inner)
    } else {
        Card(modifier = lifted, colors = colors, shape = shape, content = inner)
    }
}

/**
 * Groups consecutive lazy-list rows into one white rounded container, with hairline dividers between rows
 * (inset to [dividerStart] so they line up with the row text). Apply to each row with its position in the group.
 */
@Composable
fun Modifier.listGroupItem(index: Int, count: Int, dividerStart: Dp = 52.dp): Modifier {
    val first = index == 0
    val last = index == count - 1
    val radius = 20.dp
    val shape = RoundedCornerShape(
        topStart = if (first) radius else 0.dp,
        topEnd = if (first) radius else 0.dp,
        bottomStart = if (last) radius else 0.dp,
        bottomEnd = if (last) radius else 0.dp,
    )
    val divider = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.7f)
    return this
        .padding(horizontal = Spacing.screenGutter)
        .clip(shape)
        .background(MaterialTheme.colorScheme.surfaceContainerLowest)
        .drawBehind {
            if (!last) {
                val y = size.height - 0.5.dp.toPx()
                drawLine(divider, Offset(dividerStart.toPx(), y), Offset(size.width - Spacing.lg.toPx(), y), strokeWidth = 1.dp.toPx())
            }
        }
        .padding(top = if (first) Spacing.xs else 0.dp, bottom = if (last) Spacing.xs else 0.dp)
}

/**
 * The one highlighted surface on a screen (today's score, the garden level, onboarding): an indigo gradient
 * with two faint decorative rings, white content and a stronger soft shadow. Content inside reads
 * [LocalContentColor] as [BloomColors.onFeatured]; use [BloomColors.onFeaturedMuted] for secondary text.
 */
@Composable
fun FeaturedCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = BloomTheme.colors
    val shape = MaterialTheme.shapes.large
    val gradient = Brush.linearGradient(listOf(colors.featuredStart, colors.featuredEnd))
    val ring = colors.onFeatured.copy(alpha = 0.07f)
    val cardColors = CardDefaults.cardColors(containerColor = Color.Transparent, contentColor = colors.onFeatured)
    val lifted = modifier.fillMaxWidth().softShadow(shape, Elevation.featured)
    val inner: @Composable ColumnScope.() -> Unit = {
        Column(
            Modifier
                .background(gradient)
                .drawBehind {
                    drawCircle(ring, radius = size.minDimension * 0.55f, center = Offset(size.width * 0.95f, size.height * 0.05f))
                    drawCircle(ring, radius = size.minDimension * 0.35f, center = Offset(size.width * 0.82f, size.height * 1.05f))
                }
                .padding(Spacing.cardPadding),
            verticalArrangement = Arrangement.spacedBy(Spacing.sm),
        ) {
            CompositionLocalProvider(LocalContentColor provides colors.onFeatured) { content() }
        }
    }
    if (onClick != null) {
        Card(onClick = onClick, modifier = lifted, shape = shape, colors = cardColors, content = inner)
    } else {
        Card(modifier = lifted, shape = shape, colors = cardColors, content = inner)
    }
}

/**
 * Small uppercase label followed by optional action, e.g. "TODAY'S TASKS   See all".
 *
 * Every header has the same height whether or not it has an action, and carries the extra space above it
 * ([spaceAbove]) so a section reads as one group: generous gap before the header, tight gap after it.
 */
@Composable
fun SectionHeader(
    title: String,
    modifier: Modifier = Modifier,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
    spaceAbove: Dp = Spacing.sectionGap,
) {
    Row(
        modifier.fillMaxWidth().padding(top = spaceAbove).heightIn(min = Spacing.minTouchTarget),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            title.uppercase(),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f).semantics { heading() },
        )
        if (actionLabel != null && onAction != null) {
            // Shifted by its own content padding so the label lines up with the gutter edge.
            TextButton(
                onClick = onAction,
                contentPadding = PaddingValues(horizontal = Spacing.sm),
                modifier = Modifier.offset(x = Spacing.sm),
            ) {
                Text(actionLabel, style = MaterialTheme.typography.labelLarge)
            }
        }
    }
}

/**
 * Title row for the top-level tab screens, so Today, Tasks, Calendar, Habits and Bloom share one position,
 * size and action placement.
 */
@Composable
fun ScreenHeader(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    actions: @Composable RowScope.() -> Unit = {},
) {
    Row(
        modifier.fillMaxWidth().padding(top = Spacing.screenTop).heightIn(min = Spacing.minTouchTarget),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Spacing.xxs)) {
            Text(title, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.semantics { heading() })
            if (subtitle != null) {
                Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        // Trailing actions sit flush with the gutter: icon and text buttons both carry ~12dp of inner padding.
        Row(Modifier.offset(x = Spacing.md), verticalAlignment = Alignment.CenterVertically, content = actions)
    }
}

/** Rounded tinted tile holding an emoji or short glyph: the visual anchor for a card or list entry. */
@Composable
fun IconTile(
    glyph: String,
    modifier: Modifier = Modifier,
    size: Dp = 52.dp,
    container: Color = MaterialTheme.colorScheme.primaryContainer,
) {
    Box(
        modifier.size(size).background(container, MaterialTheme.shapes.medium).clearAndSetSemantics {},
        contentAlignment = Alignment.Center,
    ) {
        Text(glyph, fontSize = (size.value * 0.46f).sp)
    }
}
