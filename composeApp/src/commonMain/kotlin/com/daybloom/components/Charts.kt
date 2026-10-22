package com.daybloom.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.daybloom.design.Spacing

/**
 * Daily score line. Days without a score (rest, no data) are drawn as gaps, never as zero.
 * Rest days get a soft marker on the baseline.
 */
@Composable
fun ScoreTrendChart(
    points: List<Int?>,
    restIndices: Set<Int>,
    modifier: Modifier = Modifier,
    height: Dp = 140.dp,
    summary: String,
) {
    val line = MaterialTheme.colorScheme.primary
    val grid = MaterialTheme.colorScheme.outlineVariant
    val restColor = MaterialTheme.colorScheme.secondary
    Canvas(modifier.fillMaxWidth().height(height).semantics { contentDescription = summary }) {
        if (points.isEmpty()) return@Canvas
        val stepX = if (points.size > 1) size.width / (points.size - 1) else size.width
        fun y(score: Int) = size.height - (score / 100f) * size.height
        listOf(25, 50, 75).forEach { g ->
            drawLine(grid, Offset(0f, y(g)), Offset(size.width, y(g)), strokeWidth = 1f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 6f)))
        }
        var path: Path? = null
        points.forEachIndexed { i, score ->
            val x = i * stepX
            if (score == null) {
                path?.let { drawPath(it, line, style = Stroke(3.dp.toPx(), cap = StrokeCap.Round)) }
                path = null
            } else {
                val p = path ?: Path().also { it.moveTo(x, y(score)); path = it }
                p.lineTo(x, y(score))
                drawCircle(line, 3.dp.toPx(), Offset(x, y(score)))
            }
            if (i in restIndices) drawCircle(restColor, 3.dp.toPx(), Offset(x, size.height - 2.dp.toPx()))
        }
        path?.let { drawPath(it, line, style = Stroke(3.dp.toPx(), cap = StrokeCap.Round)) }
    }
}

data class BarDatum(val label: String, val value: Float, val display: String, val color: Color? = null)

/** Vertical bars with labels; null-safe for empty input. */
@Composable
fun BarChart(
    data: List<BarDatum>,
    modifier: Modifier = Modifier,
    height: Dp = 120.dp,
    maxValue: Float? = null,
) {
    val barColor = MaterialTheme.colorScheme.secondary
    val track = MaterialTheme.colorScheme.surfaceVariant
    val max = maxValue ?: (data.maxOfOrNull { it.value }?.takeIf { it > 0f } ?: 1f)
    Column(modifier.fillMaxWidth().semantics(mergeDescendants = true) {
        contentDescription = data.joinToString { "${it.label}: ${it.display}" }
    }) {
        Canvas(Modifier.fillMaxWidth().height(height)) {
            if (data.isEmpty()) return@Canvas
            val slot = size.width / data.size
            val barWidth = (slot * 0.55f).coerceAtMost(36.dp.toPx())
            data.forEachIndexed { i, d ->
                val left = i * slot + (slot - barWidth) / 2
                drawRoundRect(track, Offset(left, 0f), Size(barWidth, size.height), CornerRadius(6.dp.toPx()))
                val h = (d.value / max).coerceIn(0f, 1f) * size.height
                if (h > 0f) drawRoundRect(d.color ?: barColor, Offset(left, size.height - h), Size(barWidth, h), CornerRadius(6.dp.toPx()))
            }
        }
        Row(Modifier.fillMaxWidth()) {
            data.forEach { d ->
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(d.display, style = MaterialTheme.typography.labelSmall, maxLines = 1)
                    Text(d.label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                }
            }
        }
    }
}

/** Horizontal share bars, e.g. completions per category. */
@Composable
fun ShareBars(data: List<BarDatum>, modifier: Modifier = Modifier) {
    val total = data.sumOf { it.value.toDouble() }.toFloat().takeIf { it > 0f } ?: 1f
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        data.forEach { d ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                Text(d.label, style = MaterialTheme.typography.bodySmall, modifier = Modifier.width(92.dp), maxLines = 1, overflow = TextOverflow.Ellipsis)
                BloomProgressBar(d.value / total, Modifier.weight(1f), color = d.color ?: MaterialTheme.colorScheme.secondary, height = 8.dp, label = "${d.label} ${d.display}")
                Text(d.display, style = MaterialTheme.typography.labelMedium, modifier = Modifier.width(44.dp))
            }
        }
    }
}
