package com.daybloom.components

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.daybloom.design.BloomTheme
import com.daybloom.design.Motion
import com.daybloom.design.Spacing

/**
 * Friendly placeholder for empty lists and screens: the glyph sits in a soft tinted circle, then a short title,
 * an explanation of what to do, and (optionally) the action that fixes it.
 */
@Composable
fun EmptyState(
    emoji: String,
    title: String,
    message: String,
    modifier: Modifier = Modifier,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
    badgeColor: Color = MaterialTheme.colorScheme.primaryContainer,
) {
    Column(
        modifier.fillMaxWidth().padding(vertical = Spacing.xxl, horizontal = Spacing.xl),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(Spacing.sm),
    ) {
        Box(
            Modifier.size(88.dp).background(badgeColor, CircleShape).clearAndSetSemantics {},
            contentAlignment = Alignment.Center,
        ) {
            Text(emoji, fontSize = 40.sp)
        }
        Text(
            title,
            style = MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = Spacing.sm),
        )
        Text(message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
        if (actionLabel != null && onAction != null) {
            FilledTonalButton(onClick = onAction, modifier = Modifier.padding(top = Spacing.sm)) { Text(actionLabel) }
        }
    }
}

/**
 * Skeleton of the screen's usual shape (a featured block, then rows) so loading feels like content arriving,
 * not an error. Pulses gently; holds still with reduced motion.
 */
@Composable
fun LoadingState(modifier: Modifier = Modifier, label: String = "Loading") {
    val reduced = BloomTheme.reducedMotion
    val pulse = if (reduced) {
        0.75f
    } else {
        val transition = rememberInfiniteTransition(label = "skeleton")
        val value by transition.animateFloat(
            initialValue = 0.55f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(Motion.LONG_MS * 2), RepeatMode.Reverse),
            label = "skeleton-alpha",
        )
        value
    }
    val block = MaterialTheme.colorScheme.surfaceContainerHigh
    Column(
        modifier
            .fillMaxSize()
            .padding(horizontal = Spacing.screenGutter, vertical = Spacing.xl)
            .alpha(pulse)
            .semantics { contentDescription = label },
        verticalArrangement = Arrangement.spacedBy(Spacing.md),
    ) {
        Box(Modifier.fillMaxWidth(0.55f).height(28.dp).background(block, MaterialTheme.shapes.small))
        Box(Modifier.fillMaxWidth(0.35f).height(16.dp).background(block, MaterialTheme.shapes.small))
        Box(Modifier.padding(top = Spacing.sm).fillMaxWidth().height(160.dp).background(block, MaterialTheme.shapes.large))
        repeat(3) {
            Row(Modifier.padding(top = Spacing.sm), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(28.dp).background(block, CircleShape))
                Column(Modifier.padding(start = Spacing.md).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                    Box(Modifier.fillMaxWidth(0.7f).height(14.dp).background(block, MaterialTheme.shapes.small))
                    Box(Modifier.fillMaxWidth(0.4f).height(12.dp).background(block, MaterialTheme.shapes.small))
                }
            }
        }
    }
}

/** Something failed: a calm explanation plus a clear way to try again. */
@Composable
fun ErrorState(message: String, modifier: Modifier = Modifier, onRetry: (() -> Unit)? = null) {
    Column(
        modifier.fillMaxWidth().padding(vertical = Spacing.xxl, horizontal = Spacing.xl),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(Spacing.sm),
    ) {
        Box(
            Modifier.size(88.dp).background(MaterialTheme.colorScheme.errorContainer, CircleShape).clearAndSetSemantics {},
            contentAlignment = Alignment.Center,
        ) {
            Text("🌧️", fontSize = 40.sp)
        }
        Text("Something went wrong", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = Spacing.sm))
        Text(message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
        if (onRetry != null) {
            Button(onClick = onRetry, colors = BloomButtons.primary(), modifier = Modifier.padding(top = Spacing.sm)) { Text("Try again") }
        }
    }
}
