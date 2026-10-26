package com.daybloom.features.settings

import com.daybloom.components.InfoTooltip
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.daybloom.components.BloomTimePickerDialog
import com.daybloom.components.BloomTopBar
import com.daybloom.components.ChoiceChips
import com.daybloom.components.Format
import com.daybloom.components.SectionHeader
import com.daybloom.design.Spacing
import com.daybloom.components.BloomCard
import androidx.compose.ui.unit.dp
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.heading
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.background
import com.daybloom.design.BloomTheme
import com.daybloom.components.FeaturedCard
import com.daybloom.domain.models.QuietHours
import com.daybloom.domain.models.ScoreDimension
import com.daybloom.domain.models.ThemeMode
import com.daybloom.domain.models.UnfinishedTaskPolicy
import com.daybloom.domain.models.UserPreferences
import com.daybloom.domain.repositories.PreferencesRepository
import com.daybloom.domain.usecases.ProcessProgressUseCase
import com.daybloom.domain.usecases.ResetAllDataUseCase
import com.daybloom.domain.usecases.SeedDemoDataUseCase
import com.daybloom.domain.usecases.SyncSourceRemindersUseCase
import com.daybloom.features.common.AppMessages
import com.daybloom.features.common.runAction
import com.daybloom.notifications.ReconcileReason
import com.daybloom.notifications.ReminderReconciler
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.datetime.LocalTime
import com.daybloom.notifications.ExactTimingState
import com.daybloom.notifications.NotificationPermissionManager
import kotlinx.coroutines.flow.map
import org.koin.compose.viewmodel.koinViewModel
import com.daybloom.components.BloomButtons
import com.daybloom.components.BloomTextField
import com.daybloom.components.SwitchRow
import androidx.compose.foundation.selection.toggleable
import androidx.compose.ui.semantics.Role

class SettingsViewModel(
    private val preferences: PreferencesRepository,
    private val reset: ResetAllDataUseCase,
    private val seed: SeedDemoDataUseCase,
    private val process: ProcessProgressUseCase,
    private val reconciler: ReminderReconciler,
    private val reminderSync: SyncSourceRemindersUseCase,
    private val permissions: NotificationPermissionManager,
    private val messages: AppMessages,
) : ViewModel() {
    val state: StateFlow<UserPreferences?> = preferences.preferences.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** Android without exact-alarm access may deliver reminders up to an hour late. */
    val onTimeRemindersOff: StateFlow<Boolean> = reconciler.status
        .map { it.status?.exactTiming == ExactTimingState.RESTRICTED }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    fun allowOnTimeReminders() = permissions.openExactTimingSettings()

    fun update(transform: (UserPreferences) -> UserPreferences) = runAction(messages) { preferences.update(transform) }

    /** Score-affecting preferences recompute today's score right away. */
    fun updateScoring(transform: (UserPreferences) -> UserPreferences) = runAction(messages) {
        preferences.update(transform)
        process()
    }

    fun toggleDimension(dimension: ScoreDimension) = updateScoring { p ->
        val dims = if (dimension in p.enabledScoreDimensions) p.enabledScoreDimensions - dimension else p.enabledScoreDimensions + dimension
        if (dims.isEmpty()) p else p.copy(enabledScoreDimensions = dims)
    }

    fun setQuietHours(q: QuietHours?) = runAction(messages) {
        preferences.update { it.copy(quietHours = q) }
        reconciler.reconcile(ReconcileReason.REMINDER_CHANGED)
    }

    fun setPlanningReminder(time: LocalTime?) = runAction(messages) {
        preferences.update { it.copy(planningReminderTime = time) }
        reminderSync.setPlanningReminder(time)
    }

    fun setMorningDigest(time: LocalTime?) = runAction(messages) {
        preferences.update { it.copy(morningDigestTime = time) }
        reconciler.reconcile(ReconcileReason.REMINDER_CHANGED)
    }

    fun setEveningDigest(time: LocalTime?) = runAction(messages) {
        preferences.update { it.copy(eveningDigestTime = time) }
        reconciler.reconcile(ReconcileReason.REMINDER_CHANGED)
    }

    fun loadSample() = runAction(messages, "Couldn't add sample data.") {
        seed()
        messages.post("Sample tasks, an appointment and habits were added")
    }

    fun resetAll() = runAction(messages, "Couldn't reset your data.") {
        reset(keepPreferences = false)
        messages.post("All data was reset")
    }
}

@Composable
fun SettingsScreen(onBack: () -> Unit, onOpenReminders: () -> Unit, viewModel: SettingsViewModel = koinViewModel()) {
    val prefs by viewModel.state.collectAsStateWithLifecycle()
    val onTimeRemindersOff by viewModel.onTimeRemindersOff.collectAsStateWithLifecycle()
    var confirmReset by remember { mutableStateOf(false) }
    var picker by remember { mutableStateOf<String?>(null) }
    Scaffold(topBar = { BloomTopBar("Settings", onBack = onBack) }) { padding ->
        val p = prefs ?: return@Scaffold
        Column(
            Modifier.fillMaxSize().padding(padding).imePadding().verticalScroll(rememberScrollState()).padding(horizontal = Spacing.screenGutter),
            verticalArrangement = Arrangement.spacedBy(Spacing.md),
        ) {
            SectionHeader("You")
            var name by remember(p.displayName) { mutableStateOf(p.displayName) }
            BloomTextField(name, { name = it.take(40) }, Modifier.fillMaxWidth(), label = { Text("Name for greetings") }, singleLine = true,
                trailingIcon = { if (name != p.displayName) TextButton(onClick = { viewModel.update { it.copy(displayName = name) } }) { Text("Save") } })

            SectionHeader("Appearance")
            ChoiceChips(ThemeMode.entries, p.themeMode, { it.name.lowercase().replaceFirstChar(Char::uppercase) }, { m -> viewModel.update { it.copy(themeMode = m) } })
            ToggleRow("Reduce motion", "Skips celebratory and decorative animations.", p.reducedMotion) { v -> viewModel.update { it.copy(reducedMotion = v) } }
            ToggleRow("Haptic feedback", "A gentle tap when you complete things.", p.hapticsEnabled) { v -> viewModel.update { it.copy(hapticsEnabled = v) } }

            SectionHeader("Gamification")
            ToggleRow("XP, quests & Bloom Garden", "Turning this off hides rewards only. Tasks, reminders and insights keep working.", p.gamificationEnabled) { v -> viewModel.update { it.copy(gamificationEnabled = v) } }

            SectionHeader("Productivity score")
            Text("Choose what counts. Unchecked or data-less dimensions are left out and the rest are re-weighted.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            ScoreDimension.entries.forEach { d ->
                val checked = d in p.enabledScoreDimensions
                // At least one dimension must stay on, so the last checked one can't be unticked.
                val canToggle = !(checked && p.enabledScoreDimensions.size == 1)
                // The checkbox row toggles; the ⓘ beside it explains the dimension without crowding the list.
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Row(
                        Modifier.weight(1f).heightIn(min = Spacing.minTouchTarget)
                            .toggleable(checked, enabled = canToggle, role = Role.Checkbox) { viewModel.toggleDimension(d) },
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(Spacing.md),
                    ) {
                        Checkbox(checked, onCheckedChange = null, enabled = canToggle)
                        Text(d.label, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                        Text(
                            "${(d.defaultWeight * 100).toInt()}%",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    InfoTooltip(d.description, subject = d.label)
                }
            }
            Text("Daily focus goal", Modifier.padding(top = Spacing.sm), style = MaterialTheme.typography.bodyLarge)
            ChoiceChips(listOf(25, 45, 60, 90, 120, 180), p.dailyFocusGoalMinutes, { Format.compactMinutes(it) }, { m -> viewModel.updateScoring { it.copy(dailyFocusGoalMinutes = m) } })
            Text("Default available time when planning", style = MaterialTheme.typography.bodyLarge)
            ChoiceChips(listOf(120, 240, 360, 480, 600), p.defaultAvailableMinutes, { Format.compactMinutes(it) }, { m -> viewModel.update { it.copy(defaultAvailableMinutes = m) } })

            SectionHeader("Planning")
            Text("Unfinished tasks", style = MaterialTheme.typography.bodyLarge)
            ChoiceChips(
                UnfinishedTaskPolicy.entries, p.unfinishedTaskPolicy,
                { when (it) { UnfinishedTaskPolicy.ASK -> "Ask me"; UnfinishedTaskPolicy.KEEP_ON_ORIGINAL_DATE -> "Leave them"; UnfinishedTaskPolicy.MOVE_FLEXIBLE_TO_TOMORROW -> "Move flexible ones forward" } },
                { v -> viewModel.update { it.copy(unfinishedTaskPolicy = v) } },
            )
            ToggleRow("Smart suggestions", "Gentle, rule-based nudges from your own tasks and calendar.", p.suggestionsEnabled) { v -> viewModel.update { it.copy(suggestionsEnabled = v) } }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Daily planning reminder", Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                AssistChip(onClick = { picker = "planning" }, label = { Text(p.planningReminderTime?.let(Format::time) ?: "Off") })
            }

            SectionHeader("Notifications")
            ToggleRow("Quiet hours", "Non-urgent reminders wait until quiet hours end. Appointment reminders still arrive.", p.quietHours != null) { on ->
                viewModel.setQuietHours(if (on) QuietHours(LocalTime(22, 0), LocalTime(7, 0)) else null)
            }
            p.quietHours?.let { q ->
                Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm), verticalAlignment = Alignment.CenterVertically) {
                    AssistChip(onClick = { picker = "quietStart" }, label = { Text("From ${Format.time(q.start)}") })
                    AssistChip(onClick = { picker = "quietEnd" }, label = { Text("Until ${Format.time(q.end)}") })
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Morning task summary", style = MaterialTheme.typography.bodyLarge)
                    Text("Lists the tasks you set ahead for the day.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                AssistChip(onClick = { picker = "morningDigest" }, label = { Text(p.morningDigestTime?.let(Format::time) ?: "Off") })
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("End-of-day check-in", style = MaterialTheme.typography.bodyLarge)
                    Text("Lists today's tasks that are still open.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                AssistChip(onClick = { picker = "eveningDigest" }, label = { Text(p.eveningDigestTime?.let(Format::time) ?: "Off") })
            }
            if (onTimeRemindersOff) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("On-time reminders", style = MaterialTheme.typography.bodyLarge)
                        Text("Off — Android may deliver reminders up to an hour late.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    AssistChip(onClick = viewModel::allowOnTimeReminders, label = { Text("Allow") })
                }
            }
            OutlinedButton(onClick = onOpenReminders) { Text("Reminder status & permissions") }

            SectionHeader("Data")
            Text("Everything is stored only on this device. No account, no servers.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            OutlinedButton(onClick = { viewModel.loadSample() }) { Text("Add sample data") }
            HorizontalDivider()
            TextButton(onClick = { confirmReset = true }) { Text("Reset all data…", color = MaterialTheme.colorScheme.error) }
            Spacer(Modifier.height(Spacing.xl))
        }
        val quiet = p.quietHours
        when (picker) {
            "planning" -> BloomTimePickerDialog(p.planningReminderTime ?: LocalTime(8, 0), { picker = null }, { t -> picker = null; viewModel.setPlanningReminder(t) }, allowClear = true)
            "morningDigest" -> BloomTimePickerDialog(p.morningDigestTime ?: LocalTime(8, 0), { picker = null }, { t -> picker = null; viewModel.setMorningDigest(t) }, allowClear = true)
            "eveningDigest" -> BloomTimePickerDialog(p.eveningDigestTime ?: LocalTime(20, 0), { picker = null }, { t -> picker = null; viewModel.setEveningDigest(t) }, allowClear = true)
            "quietStart" -> if (quiet != null) BloomTimePickerDialog(quiet.start, { picker = null }, { t -> picker = null; t?.takeIf { it != quiet.end }?.let { viewModel.setQuietHours(quiet.copy(start = it)) } })
            "quietEnd" -> if (quiet != null) BloomTimePickerDialog(quiet.end, { picker = null }, { t -> picker = null; t?.takeIf { it != quiet.start }?.let { viewModel.setQuietHours(quiet.copy(end = it)) } })
        }
        if (confirmReset) {
            AlertDialog(
                onDismissRequest = { confirmReset = false },
                title = { Text("Reset everything?") },
                text = { Text("This permanently deletes all tasks, appointments, reminders, habits, history, XP and reflections on this device, and cancels scheduled notifications. It can't be undone.") },
                confirmButton = { TextButton(onClick = { confirmReset = false; viewModel.resetAll() }) { Text("Delete everything", color = MaterialTheme.colorScheme.error) } },
                dismissButton = { TextButton(onClick = { confirmReset = false }) { Text("Cancel") } },
            )
        }
    }
}

@Composable
private fun ToggleRow(title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) =
    SwitchRow(title, checked, onChange, subtitle = subtitle)

/**
 * First-run welcome: a name, an explained notification request, and optional sample data.
 * An indigo welcome panel introduces the app; the choices sit on a white card beneath it.
 */
@Composable
fun OnboardingScreen(onDone: (name: String, loadSample: Boolean, enableReminders: Boolean) -> Unit) {
    var name by remember { mutableStateOf("") }
    var sample by remember { mutableStateOf(true) }
    var reminders by remember { mutableStateOf(true) }
    val c = BloomTheme.colors
    Column(
        Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState()).padding(Spacing.screenGutter),
        verticalArrangement = Arrangement.spacedBy(Spacing.lg),
    ) {
        FeaturedCard {
            Column(
                Modifier.fillMaxWidth().padding(vertical = Spacing.xl),
                verticalArrangement = Arrangement.spacedBy(Spacing.sm),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Box(
                    Modifier.size(96.dp).background(c.onFeatured.copy(alpha = 0.14f), CircleShape),
                    contentAlignment = Alignment.Center,
                ) { Text("🌸", fontSize = 52.sp) }
                Text(
                    "Welcome to DayBloom",
                    style = MaterialTheme.typography.headlineMedium,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = Spacing.md).semantics { heading() },
                )
                Text("Make today count. Grow every day.", style = MaterialTheme.typography.bodyLarge, color = c.onFeaturedMuted, textAlign = TextAlign.Center)
            }
        }
        BloomCard {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.lg)) {
                BloomTextField(name, { name = it.take(40) }, Modifier.fillMaxWidth(), label = { Text("What should we call you? (optional)") }, singleLine = true)
                ToggleRow("Allow reminders", "We'll ask your device for permission. Notifications are only for reminders you create.", reminders) { reminders = it }
                ToggleRow("Start with sample data", "A few example tasks, an appointment and habits you can delete any time.", sample) { sample = it }
                Button(
                    colors = BloomButtons.primary(),
                    onClick = { onDone(name, sample, reminders) },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
                ) { Text("Let's begin", style = MaterialTheme.typography.titleSmall) }
            }
        }
        Text(
            "Everything stays on this device — no account needed.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(bottom = Spacing.lg),
        )
    }
}
