package com.daybloom.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.daybloom.design.BloomTheme
import com.daybloom.design.Motion

/** Score color band: never red, so a low score reads as "room to grow", not failure. */
@Composable
fun scoreColor(score: Int?): Color {
    val c = BloomTheme.colors
    return when {
        score == null -> MaterialTheme.colorScheme.outline
        score >= 70 -> c.scoreHigh
        score >= 40 -> c.scoreMid
        else -> c.scoreLow
    }
}

@Composable
fun animatedFraction(target: Float): Float {
    val reduced = BloomTheme.reducedMotion
    val value by animateFloatAsState(
        targetValue = target.coerceIn(0f, 1f),
        animationSpec = tween(if (reduced) 0 else Motion.LONG_MS),
        label = "progress",
    )
    return value
}

/** Circular 0–100 score with the number in the middle. A null score shows a dash and its reason. */
@Composable
fun ScoreRing(
    score: Int?,
    modifier: Modifier = Modifier,
    size: Dp = 132.dp,
    stroke: Dp = 12.dp,
    caption: String = "/ 100",
    /** Replaces the number in the middle, e.g. a countdown. */
    centerText: String? = null,
    description: String? = null,
    /** Overrides for use on a featured (indigo) surface; defaults follow the score band and theme. */
    color: Color = scoreColor(score),
    trackColor: Color = MaterialTheme.colorScheme.surfaceVariant,
    captionColor: Color = MaterialTheme.colorScheme.onSurfaceVariant,
) {
    val track = trackColor
    val sweep = animatedFraction((score ?: 0) / 100f)
    val a11y = description ?: if (score != null) "Productivity score $score out of 100" else "No score yet"
    Box(
        modifier.size(size).clearAndSetSemantics {
            contentDescription = a11y
            if (score != null) progressBarRangeInfo = ProgressBarRangeInfo(score.toFloat(), 0f..100f)
        },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(size)) {
            val strokePx = stroke.toPx()
            val inset = strokePx / 2
            val arcSize = Size(this.size.width - strokePx, this.size.height - strokePx)
            drawArc(track, -90f, 360f, false, Offset(inset, inset), arcSize, style = Stroke(strokePx, cap = StrokeCap.Round))
            if (sweep > 0f) {
                drawArc(color, -90f, 360f * sweep, false, Offset(inset, inset), arcSize, style = Stroke(strokePx, cap = StrokeCap.Round))
            }
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                centerText ?: score?.toString() ?: "–",
                style = if (centerText != null) MaterialTheme.typography.displayMedium else MaterialTheme.typography.displaySmall,
                fontWeight = if (centerText != null) FontWeight.Light else FontWeight.Bold,
            )
            Text(caption, style = MaterialTheme.typography.labelMedium, color = captionColor)
        }
    }
}

/** Rounded linear progress bar used for scores, quests, XP and capacity. */
@Composable
fun BloomProgressBar(
    fraction: Float,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.primary,
    height: Dp = 10.dp,
    label: String? = null,
    trackColor: Color = MaterialTheme.colorScheme.surfaceVariant,
) {
    val value = animatedFraction(fraction)
    val track = trackColor
    Canvas(
        modifier
            .fillMaxWidth()
            .height(height)
            .clip(RoundedCornerShape(50))
            .semantics {
                progressBarRangeInfo = ProgressBarRangeInfo(fraction.coerceIn(0f, 1f), 0f..1f)
                if (label != null) contentDescription = label
            },
    ) {
        val radius = CornerRadius(this.size.height / 2)
        drawRoundRect(track, cornerRadius = radius)
        if (value > 0f) drawRoundRect(color, size = Size(this.size.width * value, this.size.height), cornerRadius = radius)
    }
}
