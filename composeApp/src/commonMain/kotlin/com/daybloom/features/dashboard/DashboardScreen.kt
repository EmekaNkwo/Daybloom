package com.daybloom.features.dashboard

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Insights
import androidx.compose.material.icons.outlined.NotificationsNone
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.daybloom.components.BloomCard
import com.daybloom.components.BloomProgressBar
import com.daybloom.components.EmptyState
import com.daybloom.components.Format
import com.daybloom.components.GardenCanvas
import com.daybloom.components.LevelSummary
import com.daybloom.components.LoadingState
import com.daybloom.components.ScoreRing
import com.daybloom.components.ScreenHeader
import com.daybloom.components.SectionHeader
import com.daybloom.components.TaskRow
import com.daybloom.components.XpPill
import com.daybloom.design.LocalGamificationEnabled
import com.daybloom.design.Spacing
import com.daybloom.components.IconTile
import androidx.compose.ui.text.style.TextOverflow
import com.daybloom.components.listGroupItem
import androidx.compose.foundation.lazy.itemsIndexed
import com.daybloom.design.BloomTheme
import com.daybloom.components.FeaturedCard
import com.daybloom.components.BloomButtons
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Button
import com.daybloom.domain.models.DayClassification
import com.daybloom.domain.models.QuestStatus
import com.daybloom.domain.models.QuestType
import com.daybloom.domain.services.Suggestion
import com.daybloom.domain.services.SuggestionAction
import org.koin.compose.viewmodel.koinViewModel

/** Navigation callbacks the dashboard needs. Kept as one object so the screen stays decoupled from NavController. */
data class DashboardNavigation(
    val openTask: (String) -> Unit,
    val openTasks: () -> Unit,
    val openPlanner: (String) -> Unit,
    val openAppointment: (String, String) -> Unit,
    val openFocus: (Int?) -> Unit,
    val openInsights: () -> Unit,
    val openReminders: () -> Unit,
    val openDoneList: (String) -> Unit,
    val openGarden: () -> Unit,
    val openHabits: () -> Unit,
    val openSettings: () -> Unit,
)

@Composable
fun DashboardScreen(nav: DashboardNavigation, contentPadding: PaddingValues, viewModel: DashboardViewModel = koinViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val now = state.now
    if (state.loading || now == null) {
        LoadingState(Modifier.padding(contentPadding), "Loading today")
        return
    }
    val today = now.date
    val gamification = LocalGamificationEnabled.current && state.gamificationEnabled

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            top = contentPadding.calculateTopPadding(),
            bottom = contentPadding.calculateBottomPadding() + Spacing.fabClearance,
        ),
    ) {
        // No blanket gap: each block adds its own top spacing so task rows can sit together as one list.
        val block = Modifier.padding(top = Spacing.itemGap)
        item { Header(state, nav) }
        item { ScoreCard(state, nav, block) }

        items(state.suggestions, key = { it.key }) { suggestion ->
            SuggestionCard(
                modifier = block,
                suggestion = suggestion,
                onApply = {
                    if (!viewModel.applySuggestion(suggestion)) {
                        when (val a = suggestion.action) {
                            is SuggestionAction.PlanDay -> nav.openPlanner(a.date.toString())
                            is SuggestionAction.OpenTask -> nav.openTask(a.taskId)
                            is SuggestionAction.StartFocus -> nav.openFocus(a.minutes)
                            else -> Unit
                        }
                    }
                },
                onLater = { viewModel.dismissSuggestion(suggestion, snoozeHours = 3) },
                onDismiss = { viewModel.dismissSuggestion(suggestion) },
            )
        }

        state.nextAppointment?.let { occ ->
            item {
                Column(block.padding(horizontal = Spacing.screenGutter)) {
                    SectionHeader("Next appointment")
                    BloomCard(onClick = { nav.openAppointment(occ.appointment.id, occ.date.toString()) }) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.lg)) {
                            IconTile(occ.appointment.kind.emoji)
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Spacing.xxs)) {
                                Text(occ.appointment.title, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                val start = kotlinx.datetime.LocalDateTime(occ.date, occ.appointment.startTime)
                                Text(
                                    "${if (occ.date == today) "" else Format.relativeDay(occ.date, today) + " · "}${Format.time(occ.appointment.startTime)} · ${Format.relativeTime(start, now)}",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.primary,
                                    fontWeight = FontWeight.Medium,
                                )
                                if (occ.appointment.location.isNotBlank()) {
                                    Text(occ.appointment.location, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                    }
                }
            }
        }

        item {
            SectionHeader(
                "Today's tasks",
                block.padding(horizontal = Spacing.screenGutter),
                actionLabel = "All tasks",
                onAction = nav.openTasks,
            )
        }
        if (state.tasks.isEmpty()) {
            item {
                EmptyState(
                    emoji = if (state.plan?.isRestDay == true) "🛋️" else "🌱",
                    title = if (state.plan?.isRestDay == true) "Enjoy your rest day" else "A clear day",
                    message = if (state.plan?.isRestDay == true) "Nothing is expected of you today." else "Tap + to capture something, or plan your day.",
                    actionLabel = if (state.plan?.isRestDay == true) null else "Plan today",
                    onAction = { nav.openPlanner(today.toString()) },
                )
            }
        } else {
            itemsIndexed(state.tasks, key = { _, it -> it.id }) { i, task ->
                TaskRow(
                    task = task,
                    today = today,
                    isTopPriority = state.topPriorities.any { it.id == task.id },
                    showDate = task.dueDate != today,
                    onToggle = { viewModel.toggleTask(task, it) },
                    onClick = { nav.openTask(task.id) },
                    modifier = Modifier.listGroupItem(i, state.tasks.size).padding(start = Spacing.xs),
                )
            }
        }

        if (state.reminders.isNotEmpty()) {
            item {
                Column(block.padding(horizontal = Spacing.screenGutter), verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                    SectionHeader("Upcoming reminders", actionLabel = "Manage", onAction = nav.openReminders)
                    state.reminders.forEach { r ->
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.md)) {
                            Icon(Icons.Outlined.NotificationsNone, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(r.title, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                            Text(
                                (if (r.at.date == today) "" else Format.relativeDay(r.at.date, today) + " ") + Format.time(r.at.time),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }

        if (state.habits.isNotEmpty()) {
            item {
                Column(block) {
                    SectionHeader(
                        "Habits · ${state.habits.count { it.done }}/${state.habits.size}",
                        Modifier.padding(horizontal = Spacing.screenGutter),
                        actionLabel = "All habits",
                        onAction = nav.openHabits,
                    )
                    LazyRow(contentPadding = PaddingValues(horizontal = Spacing.screenGutter), horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                        items(state.habits, key = { it.habit.id }) { h ->
                            FilterChip(
                                selected = h.done,
                                onClick = { viewModel.toggleHabit(h.habit, !h.done) },
                                label = { Text("${h.habit.emoji} ${h.habit.title}") },
                                leadingIcon = if (h.done) ({ Icon(Icons.Outlined.CheckCircle, null) }) else null,
                            )
                        }
                    }
                }
            }
        }

        if (gamification) {
            item {
                Column(block.padding(horizontal = Spacing.screenGutter)) {
                    SectionHeader("Daily quests")
                    BloomCard {
                        LevelSummary(state.level)
                        Spacer(Modifier.height(Spacing.xs))
                        state.quests.forEach { quest ->
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                                Text(if (quest.status == QuestStatus.COMPLETED) "✅" else "🎯")
                                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                                    Text(quest.title, style = MaterialTheme.typography.bodyMedium)
                                    if (quest.status == QuestStatus.ACTIVE && quest.target > 1) {
                                        BloomProgressBar(quest.progress.toFloat() / quest.target, height = 6.dp, label = "${quest.progress} of ${quest.target}")
                                    }
                                }
                                when {
                                    quest.status == QuestStatus.COMPLETED -> XpPill(quest.rewardXp)
                                    quest.type == QuestType.TAKE_A_BREAK -> TextButton(onClick = { viewModel.completeManualQuest(quest) }) { Text("I did") }
                                    else -> TextButton(onClick = { viewModel.skipQuest(quest) }) { Text("Skip") }
                                }
                            }
                        }
                        if (state.quests.isEmpty()) Text("Quests appear once you've added a task or habit.", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            state.garden?.let { garden ->
                item {
                    Column(block.padding(horizontal = Spacing.screenGutter)) {
                        SectionHeader("Bloom Garden", actionLabel = "Visit", onAction = nav.openGarden)
                        BloomCard(onClick = nav.openGarden) {
                            GardenCanvas(garden, state.gardenTheme, height = 120.dp, maxPlants = 6)
                            Text(garden.nextMilestone, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }

        item {
            // Wraps instead of squeezing the longer label onto two lines on narrow screens.
            FlowRow(
                block.fillMaxWidth().padding(horizontal = Spacing.screenGutter).padding(top = Spacing.sectionGap),
                horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                verticalArrangement = Arrangement.spacedBy(Spacing.sm),
            ) {
                OutlinedButton(onClick = { nav.openDoneList(today.toString()) }, modifier = Modifier.weight(1f)) { Text("Done list & reflection", maxLines = 1) }
                OutlinedButton(onClick = { nav.openFocus(null) }, modifier = Modifier.weight(1f)) { Text("Focus", maxLines = 1) }
            }
        }
    }
}

@Composable
private fun Header(state: DashboardState, nav: DashboardNavigation) {
    val now = state.now ?: return
    val (greeting, emoji) = Format.greeting(now.hour)
    val toDo = state.tasksRemaining
    ScreenHeader(
        title = "$greeting${state.displayName.takeIf { it.isNotBlank() }?.let { ", $it" } ?: ""} $emoji",
        // Non-breaking spaces keep "6 to do" together when the subtitle wraps.
        subtitle = Format.longDate(now.date) + if (toDo > 0) " · $toDo\u00A0to\u00A0do" else "",
        modifier = Modifier.padding(horizontal = Spacing.screenGutter).padding(bottom = Spacing.xs),
    ) {
        // ScreenHeader shifts actions 12dp for plain icon buttons; tonal ones show a 40dp container, so pull back
        // 8dp to put that container's edge on the gutter.
        Row(Modifier.offset(x = -Spacing.sm), horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
            HeaderIconButton(Icons.Outlined.Insights, "Insights", nav.openInsights)
            HeaderIconButton(Icons.Outlined.Settings, "Settings", nav.openSettings)
        }
    }
}

/** Tinted square-ish icon button for screen-level actions, echoing the rounded tiles of the featured card. */
@Composable
private fun HeaderIconButton(icon: ImageVector, label: String, onClick: () -> Unit) {
    FilledTonalIconButton(
        onClick = onClick,
        shape = MaterialTheme.shapes.small,
        colors = IconButtonDefaults.filledTonalIconButtonColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.primary,
        ),
    ) { Icon(icon, contentDescription = label) }
}

/** Today's score: the screen's one featured surface. */
@Composable
private fun ScoreCard(state: DashboardState, nav: DashboardNavigation, modifier: Modifier = Modifier) {
    val today = state.now?.date ?: return
    var expanded by rememberSaveable { mutableStateOf(false) }
    val score = state.score
    val c = BloomTheme.colors
    val track = c.onFeatured.copy(alpha = 0.18f)
    Column(modifier.padding(horizontal = Spacing.screenGutter)) {
        FeaturedCard {
            Text("TODAY'S PRODUCTIVITY", style = MaterialTheme.typography.labelSmall, color = c.onFeaturedMuted)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.lg)) {
                ScoreRing(
                    score?.overall,
                    caption = when (score?.classification) {
                        DayClassification.REST -> "rest day"
                        null, DayClassification.NO_DATA -> "no plan yet"
                        else -> "/ 100"
                    },
                    color = c.actionOnFeatured,
                    trackColor = track,
                    captionColor = c.onFeaturedMuted,
                )
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                    Text("${state.tasksDone} done", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Text("${state.tasksRemaining} remaining", style = MaterialTheme.typography.bodyMedium, color = c.onFeaturedMuted)
                    val total = state.tasksDone + state.tasksRemaining
                    if (total > 0) {
                        BloomProgressBar(state.tasksDone.toFloat() / total, color = c.actionOnFeatured, trackColor = track, height = 8.dp, label = "Plan completion")
                    }
                }
            }
            Spacer(Modifier.height(Spacing.xs))
            Text(state.scoreSummary, style = MaterialTheme.typography.bodyMedium, color = c.onFeaturedMuted)
            // Actions get their own row so they never squeeze beside the ring on narrow screens or large text.
            FlowRow(
                Modifier.padding(top = Spacing.xs),
                horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                verticalArrangement = Arrangement.spacedBy(Spacing.xs),
                itemVerticalAlignment = Alignment.CenterVertically,
            ) {
                Button(onClick = { nav.openPlanner(today.toString()) }, colors = BloomButtons.onFeatured()) {
                    Text(if (state.plan == null) "Plan my day" else "Edit plan", maxLines = 1)
                }
                if (score?.overall != null) {
                    TextButton(
                        onClick = { expanded = !expanded },
                        colors = ButtonDefaults.textButtonColors(contentColor = c.onFeatured),
                    ) {
                        Text(if (expanded) "Hide breakdown" else "Why this score?", maxLines = 1)
                    }
                }
            }
            if (score?.overall != null) {
                AnimatedVisibility(expanded) {
                    Column(verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
                        score.dimensions.forEach { d ->
                            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                                Row {
                                    Text(d.dimension.label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                                    Text(
                                        d.score?.let { "$it / 100 · ${(d.effectiveWeight * 100).toInt()}%" } ?: "Not counted",
                                        style = MaterialTheme.typography.labelMedium,
                                    )
                                }
                                if (d.score != null) {
                                    BloomProgressBar(d.score / 100f, color = c.actionOnFeatured, trackColor = track, height = 6.dp, label = "${d.dimension.label} ${d.score}")
                                }
                                Text(d.detail, style = MaterialTheme.typography.bodySmall, color = c.onFeaturedMuted)
                            }
                        }
                        state.scoreFormula?.let {
                            Text("How it adds up: $it.", style = MaterialTheme.typography.bodySmall)
                        }
                        Text(
                            "Dimensions without enough data aren't counted, and the rest are re-weighted.",
                            style = MaterialTheme.typography.bodySmall,
                            color = c.onFeaturedMuted,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SuggestionCard(
    suggestion: Suggestion,
    onApply: () -> Unit,
    onLater: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.padding(horizontal = Spacing.screenGutter)) {
        BloomCard(tonal = true) {
            Text("💡 ${suggestion.title}", style = MaterialTheme.typography.titleSmall)
            Text(suggestion.message, style = MaterialTheme.typography.bodyMedium)
            FlowRow(
                Modifier.padding(top = Spacing.xs),
                horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
                verticalArrangement = Arrangement.Center,
            ) {
                FilledTonalButton(onClick = onApply) { Text(suggestion.actionLabel) }
                Spacer(Modifier.width(Spacing.xs))
                TextButton(onClick = onLater) { Text("Later") }
                TextButton(onClick = onDismiss) { Text("Dismiss") }
            }
        }
    }
}
