package com.daybloom.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.Repeat
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.daybloom.design.BloomTheme
import com.daybloom.design.Motion
import com.daybloom.design.Spacing
import com.daybloom.domain.models.Task
import com.daybloom.domain.models.TaskStatus
import kotlinx.datetime.LocalDate

val LocalHapticsEnabled = androidx.compose.runtime.staticCompositionLocalOf { true }

/** Round completion control with a 48dp touch target. */
@Composable
fun CompletionCircle(
    checked: Boolean,
    onToggle: (Boolean) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    color: Color = BloomTheme.colors.success,
    cancelled: Boolean = false,
) {
    val haptics = LocalHapticFeedback.current
    val hapticsOn = LocalHapticsEnabled.current
    val fill by animateColorAsState(
        if (checked) color else Color.Transparent,
        tween(if (BloomTheme.reducedMotion) 0 else Motion.SHORT_MS),
        label = "check",
    )
    Box(
        modifier
            .size(Spacing.minTouchTarget)
            .toggleable(value = checked, role = Role.Checkbox, enabled = !cancelled) {
                if (it && hapticsOn) haptics.performHapticFeedback(HapticFeedbackType.Confirm)
                onToggle(it)
            }
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .size(24.dp)
                .background(fill, CircleShape)
                .border(2.dp, if (checked) color else MaterialTheme.colorScheme.outline, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            when {
                cancelled -> Icon(Icons.Filled.Close, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.outline)
                checked -> Icon(Icons.Filled.Check, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.surface)
            }
        }
    }
}

@Composable
fun TaskRow(
    task: Task,
    today: LocalDate,
    onToggle: (Boolean) -> Unit,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    isTopPriority: Boolean = false,
    showDate: Boolean = true,
) {
    val done = task.status == TaskStatus.COMPLETED
    val cancelled = task.status == TaskStatus.CANCELLED
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = 64.dp)
            .clickable(onClickLabel = "Open ${task.title}", onClick = onClick)
            .padding(end = Spacing.lg),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CompletionCircle(
            checked = done,
            onToggle = onToggle,
            label = when {
                cancelled -> "${task.title}, no longer needed"
                done -> "Mark ${task.title} as not done"
                else -> "Complete ${task.title}"
            },
            cancelled = cancelled,
        )
        Column(Modifier.weight(1f).padding(vertical = Spacing.md), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                if (isTopPriority) {
                    Icon(Icons.Outlined.Star, contentDescription = "Top priority", Modifier.size(16.dp), tint = BloomTheme.colors.warning)
                }
                Text(
                    task.title,
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    textDecoration = if (done || cancelled) TextDecoration.LineThrough else null,
                    color = if (done || cancelled) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                )
            }
            TaskMeta(task, today, showDate)
        }
    }
}

@Composable
private fun TaskMeta(task: Task, today: LocalDate, showDate: Boolean) {
    val parts = buildList {
        if (showDate) task.dueDate?.let { add(Format.relativeDay(it, today)) }
        task.dueTime?.let { add(Format.time(it)) }
        task.estimatedMinutes?.let { add(Format.compactMinutes(it)) }
        val (doneSubs, totalSubs) = task.subtaskProgress
        if (totalSubs > 0) add("$doneSubs/$totalSubs steps")
        if (task.status == TaskStatus.CANCELLED) add("No longer needed")
    }
    val overdue = task.isOpen && task.dueDate != null && task.dueDate < today
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        CategoryDot(task.category)
        Text(
            (listOf(task.category.label) + parts).joinToString(" · "),
            style = MaterialTheme.typography.bodySmall,
            color = if (overdue) BloomTheme.colors.warning else MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
        )
        if (task.recurrence != null) {
            Icon(Icons.Outlined.Repeat, contentDescription = task.recurrence.describe(), Modifier.size(14.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        PriorityPill(task.priority)
    }
}
