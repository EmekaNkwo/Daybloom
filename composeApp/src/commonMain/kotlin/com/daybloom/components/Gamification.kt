package com.daybloom.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.scale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.daybloom.design.BloomTheme
import com.daybloom.design.Spacing
import androidx.compose.ui.text.font.FontWeight
import com.daybloom.domain.models.LevelProgress

@Composable
fun LevelSummary(progress: LevelProgress, modifier: Modifier = Modifier, onFeatured: Boolean = false) {
    val c = BloomTheme.colors
    val accent = if (onFeatured) c.actionOnFeatured else c.xp
    val badge = if (onFeatured) c.onFeatured.copy(alpha = 0.16f) else c.xp.copy(alpha = 0.14f)
    val badgeText = if (onFeatured) c.onFeatured else c.xp
    val muted = if (onFeatured) c.onFeaturedMuted else MaterialTheme.colorScheme.onSurfaceVariant
    val track = if (onFeatured) c.onFeatured.copy(alpha = 0.18f) else MaterialTheme.colorScheme.surfaceVariant
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.md)) {
            Box(
                Modifier.size(if (onFeatured) 48.dp else 40.dp).background(badge, MaterialTheme.shapes.small),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    "${progress.level.number}",
                    style = if (onFeatured) MaterialTheme.typography.titleLarge else MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = badgeText,
                )
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Spacing.xxs)) {
                Text(progress.level.title, style = if (onFeatured) MaterialTheme.typography.titleMedium else MaterialTheme.typography.titleSmall)
                val next = progress.nextLevel
                Text(
                    if (next != null) "${progress.xpIntoLevel} / ${progress.xpForNextLevel} XP to ${next.title}" else "${progress.totalXp} XP · Highest level",
                    style = MaterialTheme.typography.bodySmall,
                    color = muted,
                )
            }
        }
        BloomProgressBar(progress.fraction, color = accent, trackColor = track, height = 8.dp, label = "Level progress")
    }
}

@Composable
fun AchievementBadge(emoji: String, title: String, unlocked: Boolean, progressText: String, modifier: Modifier = Modifier) {
    Column(
        modifier.semantics(mergeDescendants = true) {
            contentDescription = "$title, ${if (unlocked) "unlocked" else "locked, $progressText"}"
        },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Box(
            Modifier.size(56.dp).background(
                if (unlocked) MaterialTheme.colorScheme.tertiaryContainer else MaterialTheme.colorScheme.surfaceVariant,
                CircleShape,
            ),
            contentAlignment = Alignment.Center,
        ) {
            Text(emoji, fontSize = 26.sp, modifier = Modifier.alpha(if (unlocked) 1f else 0.35f))
        }
        Text(title, style = MaterialTheme.typography.labelMedium, textAlign = TextAlign.Center, maxLines = 2)
        if (!unlocked) Text(progressText, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Short, skippable level-up celebration. With reduced motion it appears without the bounce. */
@Composable
fun LevelUpDialog(progress: LevelProgress, onDismiss: () -> Unit) {
    val reduced = BloomTheme.reducedMotion
    val scale = remember { Animatable(if (reduced) 1f else 0.4f) }
    LaunchedEffect(Unit) { if (!reduced) scale.animateTo(1f, spring(dampingRatio = 0.45f, stiffness = 300f)) }
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text("Keep growing") } },
        title = { Text("Level ${progress.level.number}!", modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center) },
        text = {
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                Text("🌸", fontSize = 64.sp, modifier = Modifier.scale(scale.value))
                Text("You've reached ${progress.level.title}.", style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
                Text("Small steps, steady growth.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
    )
}
