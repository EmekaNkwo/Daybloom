package com.daybloom.features.habits

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.daybloom.components.BloomCard
import com.daybloom.components.BloomTimePickerDialog
import com.daybloom.components.BloomTopBar
import com.daybloom.components.ChoiceChips
import com.daybloom.components.CompletionCircle
import com.daybloom.components.EmptyState
import com.daybloom.components.Format
import com.daybloom.components.LoadingState
import com.daybloom.components.MultiChoiceChips
import com.daybloom.components.ScreenHeader
import com.daybloom.components.SectionHeader
import com.daybloom.design.BloomTheme
import com.daybloom.design.Spacing
import com.daybloom.components.IconTile
import com.daybloom.domain.models.Habit
import com.daybloom.domain.models.TaskCategory
import com.daybloom.domain.models.shortName
import com.daybloom.domain.repositories.HabitRepository
import com.daybloom.domain.repositories.PlanRepository
import com.daybloom.domain.services.DateMath
import com.daybloom.domain.services.HabitStreak
import com.daybloom.domain.services.StreakCalculator
import com.daybloom.domain.usecases.ArchiveHabitUseCase
import com.daybloom.domain.usecases.HabitDraft
import com.daybloom.domain.usecases.PauseHabitUseCase
import com.daybloom.domain.usecases.SaveHabitUseCase
import com.daybloom.domain.usecases.ToggleHabitUseCase
import com.daybloom.features.common.AppMessage
import com.daybloom.features.common.AppMessages
import com.daybloom.features.common.runAction
import com.daybloom.features.common.todayFlow
import com.daybloom.platform.PlatformDateTimeService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime
import kotlinx.datetime.toLocalDateTime
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf
import com.daybloom.components.BloomButtons
import com.daybloom.components.BloomTextField
import com.daybloom.components.rememberHasChanges
import com.daybloom.components.unsavedChangesGuard
import com.daybloom.components.SwitchRow

data class HabitCard(
    val habit: Habit,
    val streak: HabitStreak,
    /** Last 7 days, oldest first: (date, scheduled, done). */
    val week: List<Triple<LocalDate, Boolean, Boolean>>,
    val doneToday: Boolean,
)

data class HabitsState(
    val loading: Boolean = true,
    val today: LocalDate? = null,
    val cards: List<HabitCard> = emptyList(),
    val archived: List<Habit> = emptyList(),
)

class HabitsViewModel(
    habits: HabitRepository,
    plans: PlanRepository,
    private val toggle: ToggleHabitUseCase,
    private val pause: PauseHabitUseCase,
    private val archive: ArchiveHabitUseCase,
    private val messages: AppMessages,
    private val clock: PlatformDateTimeService,
) : ViewModel() {
    private val restDays = MutableStateFlow<Set<LocalDate>>(emptySet())

    init {
        viewModelScope.launch { restDays.value = plans.getAll().filter { it.isRestDay }.map { it.date }.toSet() }
    }

    val state: StateFlow<HabitsState> = combine(habits.observeHabits(), habits.observeCompletions(), clock.todayFlow(), restDays, habits.observeArchived()) { list, completions, today, rest, archived ->
        val zone = clock.timeZone()
        val byHabit = completions.groupBy { it.habitId }.mapValues { (_, v) -> v.map { it.date }.toSet() }
        HabitsState(
            loading = false,
            today = today,
            cards = list.map { h ->
                val done = byHabit[h.id].orEmpty()
                HabitCard(
                    habit = h,
                    streak = StreakCalculator.habitStreak(h, done, rest, today, h.createdAt.toLocalDateTime(zone).date),
                    week = (6 downTo 0).map { back ->
                        val d = DateMath.plusDays(today, -back)
                        Triple(d, h.isScheduledOn(d), d in done)
                    },
                    doneToday = today in done,
                )
            },
            archived = archived,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HabitsState())

    fun toggle(habit: Habit, date: LocalDate, done: Boolean) = runAction(messages) { toggle(habit.id, date, done) }

    fun setPaused(habit: Habit, paused: Boolean) = runAction(messages) {
        pause(habit.id, paused)
        messages.post(if (paused) "Paused — streaks and scores won't count it until you resume" else "Welcome back to ${habit.title}")
    }

    fun archive(habit: Habit) = runAction(messages) {
        archive(habit.id)
        messages.post(AppMessage("Archived ${habit.title}", "Undo") { archive.restore(habit.id) })
    }

    fun restore(habit: Habit) = runAction(messages) {
        archive.restore(habit.id)
        messages.post("${habit.title} is back in your habits")
    }
}

@Composable
fun HabitsScreen(contentPadding: PaddingValues, onNewHabit: () -> Unit, onEditHabit: (String) -> Unit, viewModel: HabitsViewModel = koinViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val today = state.today
    if (state.loading || today == null) {
        LoadingState(Modifier.padding(contentPadding))
        return
    }
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = Spacing.screenGutter, end = Spacing.screenGutter,
            top = contentPadding.calculateTopPadding(), bottom = contentPadding.calculateBottomPadding() + Spacing.fabClearance,
        ),
        verticalArrangement = Arrangement.spacedBy(Spacing.itemGap),
    ) {
        item {
            Column(Modifier.padding(bottom = Spacing.sm), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                ScreenHeader("Habits") {
                    TextButton(onClick = onNewHabit) { Text("New habit") }
                }
                Text(
                    "Missed a day? That's fine. Weekly targets and one forgiven miss keep things gentle.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (state.cards.isEmpty()) {
            item { EmptyState("🌱", "No habits yet", "Start small: reading, a walk, a moment of reflection.", actionLabel = "Create a habit", onAction = onNewHabit) }
        }
        items(state.cards, key = { it.habit.id }) { card -> HabitCardView(card, today, viewModel, onEditHabit) }
        if (state.archived.isNotEmpty()) {
            item(key = "archived") { ArchivedHabits(state.archived, viewModel::restore) }
        }
    }
}

/** Archived habits stay out of the way, collapsed by default, but can always be brought back with their history. */
@Composable
private fun ArchivedHabits(archived: List<Habit>, onRestore: (Habit) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Column {
        SectionHeader(
            "Archived (${archived.size})",
            actionLabel = if (expanded) "Hide" else "Show",
            onAction = { expanded = !expanded },
        )
        if (expanded) {
            BloomCard(tonal = true) {
                archived.forEach { h ->
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        IconTile(h.emoji, size = 40.dp)
                        Text(
                            h.title,
                            style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier.weight(1f).padding(horizontal = Spacing.md),
                        )
                        TextButton(
                            onClick = { onRestore(h) },
                            modifier = Modifier.semantics { contentDescription = "Restore ${h.title}" },
                        ) { Text("Restore") }
                    }
                }
            }
        }
    }
}

@Composable
private fun HabitCardView(card: HabitCard, today: LocalDate, viewModel: HabitsViewModel, onEdit: (String) -> Unit) {
    var menu by remember { mutableStateOf(false) }
    val h = card.habit
    BloomCard(onClick = { onEdit(h.id) }) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconTile(h.emoji, size = 48.dp)
            Column(Modifier.weight(1f).padding(start = Spacing.md), verticalArrangement = Arrangement.spacedBy(Spacing.xxs)) {
                Text(h.title, style = MaterialTheme.typography.titleMedium)
                Text(
                    when {
                        h.isPaused -> "Paused"
                        else -> "${card.streak.completedThisWeek}/${h.weeklyTarget} this week" +
                            if (h.streaksEnabled && card.streak.current > 0) " · ${card.streak.current}-day streak" else ""
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (h.isScheduledOn(today)) {
                CompletionCircle(card.doneToday, { viewModel.toggle(h, today, it) }, if (card.doneToday) "Undo ${h.title} today" else "Complete ${h.title} today")
            }
            Box {
                IconButton(onClick = { menu = true }) { Icon(Icons.Outlined.MoreVert, "More for ${h.title}") }
                DropdownMenu(menu, { menu = false }) {
                    DropdownMenuItem({ Text(if (h.isPaused) "Resume" else "Pause") }, { menu = false; viewModel.setPaused(h, !h.isPaused) })
                    DropdownMenuItem({ Text("Archive") }, { menu = false; viewModel.archive(h) })
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.xs), modifier = Modifier.fillMaxWidth().padding(top = Spacing.sm)) {
            card.week.forEach { (date, scheduled, done) ->
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                    Text(date.dayOfWeek.shortName().take(1), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Box(
                        Modifier.size(22.dp).background(
                            when {
                                done -> BloomTheme.colors.success
                                scheduled -> MaterialTheme.colorScheme.surfaceVariant
                                else -> MaterialTheme.colorScheme.surface
                            },
                            CircleShape,
                        ).semantics { contentDescription = "${Format.shortDate(date)}: ${if (done) "done" else if (scheduled) "not done" else "not scheduled"}" },
                    )
                }
            }
        }
        if (h.streaksEnabled && card.streak.bestWeeks > 0) {
            Text("Best: ${card.streak.best} days · ${card.streak.bestWeeks} week${if (card.streak.bestWeeks == 1) "" else "s"} on target", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

private val EMOJIS = listOf("🌱", "📖", "🚶", "🧘", "🙏", "💧", "✍️", "🎧", "🏃", "🌙", "🥗", "🧠")

data class HabitEditorState(
    val loading: Boolean = true,
    val isNew: Boolean = true,
    val draft: HabitDraft = HabitDraft(""),
    val errors: Map<String, String> = emptyMap(),
    val closed: Boolean = false,
)

class HabitEditorViewModel(
    private val habitId: String?,
    private val habits: HabitRepository,
    private val save: SaveHabitUseCase,
    private val messages: AppMessages,
) : ViewModel() {
    private val _state = MutableStateFlow(HabitEditorState())
    val state: StateFlow<HabitEditorState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            val existing = habitId?.let { habits.get(it) }
            _state.value = HabitEditorState(
                loading = false,
                isNew = existing == null,
                draft = existing?.let {
                    HabitDraft(it.title, it.emoji, it.scheduledDays, it.weeklyTarget, it.reminderTime, it.streaksEnabled, it.category)
                } ?: HabitDraft(""),
            )
        }
    }

    fun update(transform: (HabitDraft) -> HabitDraft) = _state.update { it.copy(draft = transform(it.draft), errors = emptyMap()) }

    fun save() = runAction(messages, "Couldn't save the habit.") {
        when (val result = save(_state.value.draft, habitId)) {
            is SaveHabitUseCase.Result.Saved -> _state.update { it.copy(closed = true) }
            is SaveHabitUseCase.Result.Invalid -> _state.update { it.copy(errors = result.errors) }
        }
    }
}

@Composable
fun HabitEditorScreen(habitId: String?, onClose: () -> Unit, viewModel: HabitEditorViewModel = koinViewModel(key = "habit-$habitId") { parametersOf(habitId) }) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(state.closed) { if (state.closed) onClose() }
    // Leaving with unsaved edits asks first instead of discarding them silently.
    val requestClose = unsavedChangesGuard(hasChanges = !state.closed && rememberHasChanges(ready = !state.loading, current = state.draft), onLeave = onClose)
    var pickTime by remember { mutableStateOf(false) }
    Scaffold(topBar = { BloomTopBar(if (state.isNew) "New habit" else "Edit habit", onBack = requestClose, actions = { TextButton(onClick = { viewModel.save() }) { Text("Save") } }) }) { padding ->
        if (state.loading) {
            LoadingState(Modifier.padding(padding))
            return@Scaffold
        }
        val d = state.draft
        Column(
            Modifier.fillMaxSize().padding(padding).imePadding().verticalScroll(rememberScrollState()).padding(horizontal = Spacing.screenGutter),
            verticalArrangement = Arrangement.spacedBy(Spacing.md),
        ) {
            BloomTextField(
                d.title, { v -> viewModel.update { it.copy(title = v.take(80)) } }, Modifier.fillMaxWidth(),
                label = { Text("Habit") }, placeholder = { Text("Read for 10 minutes") },
                isError = "title" in state.errors, supportingText = state.errors["title"]?.let { { Text(it) } }, singleLine = true,
            )
            ChoiceChips(EMOJIS, d.emoji, { it }, { e -> viewModel.update { it.copy(emoji = e) } })
            SectionHeader("Days")
            MultiChoiceChips(DayOfWeek.entries, d.scheduledDays, { it.shortName() }, { day ->
                viewModel.update { dr ->
                    val days = if (day in dr.scheduledDays) dr.scheduledDays - day else dr.scheduledDays + day
                    dr.copy(scheduledDays = days, weeklyTarget = dr.weeklyTarget.coerceIn(1, days.size.coerceAtLeast(1)))
                }
            })
            state.errors["days"]?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            SectionHeader("Weekly target")
            ChoiceChips((1..d.scheduledDays.size.coerceAtLeast(1)).toList(), d.weeklyTarget, { "$it×" }, { t -> viewModel.update { it.copy(weeklyTarget = t) } })
            Text("A target below your scheduled days leaves room for life.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            SectionHeader("Category")
            ChoiceChips(TaskCategory.entries, d.category, { "${it.emoji} ${it.label}" }, { c -> viewModel.update { it.copy(category = c) } })
            SectionHeader("Reminder")
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                AssistChip(onClick = { pickTime = true }, label = { Text(d.reminderTime?.let(Format::time) ?: "No reminder") })
            }
            SwitchRow("Show streaks", d.streaksEnabled, { v -> viewModel.update { it.copy(streaksEnabled = v) } })
            Button(colors = BloomButtons.primary(), onClick = { viewModel.save() }, modifier = Modifier.fillMaxWidth()) { Text(if (state.isNew) "Create habit" else "Save") }
            Spacer(Modifier.height(Spacing.xl))
        }
        if (pickTime) {
            BloomTimePickerDialog(d.reminderTime ?: LocalTime(8, 0), { pickTime = false }, { t -> pickTime = false; viewModel.update { it.copy(reminderTime = t) } }, allowClear = true)
        }
    }
}
