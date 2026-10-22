package com.daybloom.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.daybloom.design.BloomTheme
import com.daybloom.domain.models.Priority
import com.daybloom.domain.models.TaskCategory

@Composable
fun categoryColor(category: TaskCategory): Color {
    val c = BloomTheme.colors
    return when (category) {
        TaskCategory.WORK -> c.categoryWork
        TaskCategory.PERSONAL -> c.categoryPersonal
        TaskCategory.HEALTH -> c.categoryHealth
        TaskCategory.FINANCE -> c.categoryFinance
        TaskCategory.FAMILY -> c.categoryFamily
        TaskCategory.LEARNING -> c.categoryLearning
        TaskCategory.ERRANDS -> c.categoryErrands
        TaskCategory.OTHER -> c.categoryOther
    }
}

@Composable
fun priorityColor(priority: Priority): Color {
    val c = BloomTheme.colors
    return when (priority) {
        Priority.URGENT -> c.urgent
        Priority.HIGH -> c.high
        Priority.NORMAL -> c.normal
        Priority.LOW -> c.low
    }
}

@Composable
fun CategoryDot(category: TaskCategory, modifier: Modifier = Modifier) {
    Box(modifier.size(8.dp).background(categoryColor(category), CircleShape))
}

/** Small pill with text, tinted by [color]. Priority/category are never conveyed by color alone. */
@Composable
fun Pill(text: String, color: Color, modifier: Modifier = Modifier) {
    Row(
        modifier
            .background(color.copy(alpha = 0.14f), RoundedCornerShape(50))
            .padding(horizontal = 8.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text, style = MaterialTheme.typography.labelSmall, color = color)
    }
}

@Composable
fun PriorityPill(priority: Priority, modifier: Modifier = Modifier) {
    if (priority == Priority.NORMAL) return
    Pill(priority.label, priorityColor(priority), modifier)
}

@Composable
fun XpPill(amount: Int, modifier: Modifier = Modifier) {
    Pill("+$amount XP", BloomTheme.colors.xp, modifier)
}
