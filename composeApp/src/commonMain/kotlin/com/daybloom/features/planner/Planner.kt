package com.daybloom.features.planner

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.StarOutline
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.daybloom.components.BloomCard
import com.daybloom.components.BloomProgressBar
import com.daybloom.components.BloomTopBar
import com.daybloom.components.ChoiceChips
import com.daybloom.components.Format
import com.daybloom.components.LoadingState
import com.daybloom.components.PriorityPill
import com.daybloom.components.SectionHeader
import com.daybloom.design.BloomTheme
import com.daybloom.design.Spacing
import androidx.compose.foundation.layout.heightIn
import com.daybloom.components.listGroupItem
import androidx.compose.foundation.layout.offset
import com.daybloom.domain.models.DailyPlan
import com.daybloom.domain.models.EnergyLevel
import com.daybloom.domain.models.Task
import com.daybloom.domain.models.TaskStatus
import com.daybloom.domain.repositories.PlanRepository
import com.daybloom.domain.repositories.PreferencesRepository
import com.daybloom.domain.repositories.TaskRepository
import com.daybloom.domain.services.PlanCapacity
import com.daybloom.domain.services.PlanCapacityCalculator
import com.daybloom.domain.usecases.CalculatePlanCapacityUseCase
import com.daybloom.domain.usecases.PlanDraft
import com.daybloom.domain.usecases.RescheduleTaskUseCase
import com.daybloom.domain.usecases.SaveDailyPlanUseCase
import com.daybloom.domain.usecases.SavePlanResult
import com.daybloom.features.common.AppMessages
import com.daybloom.features.common.runAction
import com.daybloom.platform.PlatformDateTimeService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.datetime.LocalDate
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf
import com.daybloom.components.BloomButtons
import com.daybloom.components.rememberHasChanges
import com.daybloom.components.unsavedChangesGuard
import com.daybloom.components.SwitchRow

private val AVAILABLE_OPTIONS = listOf(120, 180, 240, 300, 360, 420, 480, 540, 600)
private val BREAK_OPTIONS = listOf(0, 15, 30, 45, 60, 90)

data class PlannerState(
    val loading: Boolean = true,
    val date: LocalDate? = null,
    val today: LocalDate? = null,
    val candidates: List<Task> = emptyList(),
    val overdue: List<Task> = emptyList(),
    val planned: Set<String> = emptySet(),
    val top: List<String> = emptyList(),
    val availableMinutes: Int? = null,
    val breakMinutes: Int = 0,
    val energy: EnergyLevel? = null,
    val isRestDay: Boolean = false,
    val capacity: PlanCapacity? = null,
    val overCapacityPrompt: PlanCapacity? = null,
    val message: String? = null,
    val closed: Boolean = false,
)

class PlannerViewModel(
    private val dateArg: String,
    private val tasks: TaskRepository,
    private val plans: PlanRepository,
    private val preferences: PreferencesRepository,
    private val capacity: CalculatePlanCapacityUseCase,
    private val savePlan: SaveDailyPlanUseCase,
    private val reschedule: RescheduleTaskUseCase,
    private val messages: AppMessages,
    private val clock: PlatformDateTimeService,
) : ViewModel() {
    private val _state = MutableStateFlow(PlannerState())
    val state: StateFlow<PlannerState> = _state.asStateFlow()

    init {
        viewModelScope.launch { load() }
    }

    private suspend fun load() {
        val today = clock.today()
        val date = runCatching { LocalDate.parse(dateArg) }.getOrNull() ?: today
        val plan: DailyPlan? = plans.get(date)
        val open = tasks.getOpen()
        val plannedIds = plan?.plannedTaskIds.orEmpty() + plan?.topPriorityTaskIds.orEmpty()
        val planned = tasks.getByIds(plannedIds).filter { it.status != TaskStatus.CANCELLED }
        val candidates = (open.filter { it.dueDate == date || it.dueDate == null } + planned)
            .distinctBy { it.id }
            .sortedWith(compareBy({ it.dueDate == null }, { it.priority.rank }))
        val overdue = open.filter { it.dueDate != null && it.dueDate < date && it.id !in plannedIds }
        val defaultSelection = plan?.plannedTaskIds?.toSet() ?: candidates.filter { it.dueDate == date }.map { it.id }.toSet()
        _state.value = PlannerState(
            loading = false,
            date = date,
            today = today,
            candidates = candidates,
            overdue = overdue,
            planned = defaultSelection + plan?.topPriorityTaskIds.orEmpty(),
            top = plan?.topPriorityTaskIds.orEmpty(),
            availableMinutes = plan?.availableMinutes ?: preferences.preferences.first().defaultAvailableMinutes,
            breakMinutes = plan?.breakMinutes ?: 0,
            energy = plan?.energy,
            isRestDay = plan?.isRestDay ?: false,
        )
        recalc()
    }

    fun togglePlanned(task: Task) {
        _state.update { s ->
            val planned = if (task.id in s.planned) s.planned - task.id else s.planned + task.id
            s.copy(planned = planned, top = s.top.filter { it in planned })
        }
        recalc()
    }

    fun toggleTop(task: Task) {
        _state.update { s ->
            when {
                task.id in s.top -> s.copy(top = s.top - task.id)
                s.top.size >= DailyPlan.MAX_TOP_PRIORITIES -> s.copy(message = "You can choose up to three top priorities")
                else -> s.copy(top = s.top + task.id, planned = s.planned + task.id)
            }
        }
        recalc()
    }

    fun setAvailable(minutes: Int?) { _state.update { it.copy(availableMinutes = minutes) }; recalc() }
    fun setBreaks(minutes: Int) { _state.update { it.copy(breakMinutes = minutes) }; recalc() }
    fun setEnergy(energy: EnergyLevel?) = _state.update { it.copy(energy = energy) }
    fun setRestDay(rest: Boolean) = _state.update { it.copy(isRestDay = rest) }
    fun consumeMessage() = _state.update { it.copy(message = null) }

    /** Unfinished tasks only move with explicit confirmation (this button). */
    fun pullOverdue() {
        val s = _state.value
        val date = s.date ?: return
        runAction(messages) {
            val moved = reschedule(s.overdue.map { it.id }, date)
            _state.update { st -> st.copy(overdue = emptyList(), candidates = (st.candidates + moved).distinctBy { it.id }, planned = st.planned + moved.map { it.id }) }
            recalc()
        }
    }

    fun save(keepDespiteOverCapacity: Boolean = false) {
        val s = _state.value
        val date = s.date ?: return
        runAction(messages, "Couldn't save your plan.") {
            val draft = PlanDraft(date, s.top, s.planned.toList(), s.availableMinutes, s.breakMinutes, s.energy, s.isRestDay)
            when (val result = savePlan(draft, keepDespiteOverCapacity)) {
                is SavePlanResult.Saved -> {
                    messages.post(if (s.isRestDay) "Rest day saved. Enjoy it 🌿" else "Plan saved")
                    _state.update { it.copy(closed = true, overCapacityPrompt = null) }
                }
                is SavePlanResult.OverCapacity -> _state.update { it.copy(overCapacityPrompt = result.capacity) }
                is SavePlanResult.Invalid -> _state.update { it.copy(message = result.message) }
            }
        }
    }

    fun dismissOverCapacity() = _state.update { it.copy(overCapacityPrompt = null) }

    private fun recalc() {
        val s = _state.value
        val date = s.date ?: return
        viewModelScope.launch {
            val cap = capacity(date, s.planned.toList(), s.availableMinutes, s.breakMinutes)
            _state.update { it.copy(capacity = cap) }
        }
    }
}

@Composable
fun PlannerScreen(date: String, onClose: () -> Unit, viewModel: PlannerViewModel = koinViewModel(key = "plan-$date") { parametersOf(date) }) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(state.closed) { if (state.closed) onClose() }
    // Leaving with unsaved edits asks first instead of discarding them silently.
    val requestClose = unsavedChangesGuard(hasChanges = !state.closed && rememberHasChanges(
        ready = !state.loading,
        current = listOf(state.planned, state.top, state.availableMinutes, state.breakMinutes, state.energy, state.isRestDay),
    ), onLeave = onClose)
    val d = state.date
    Scaffold(topBar = {
        BloomTopBar(
            title = d?.let { if (it == state.today) "Plan today" else "Plan ${Format.shortDate(it)}" } ?: "Plan",
            onBack = requestClose,
            actions = { TextButton(onClick = { viewModel.save() }, enabled = !state.loading) { Text("Save") } },
        )
    }) { padding ->
        if (state.loading || d == null) {
            LoadingState(Modifier.padding(padding))
            return@Scaffold
        }
        LazyColumn(Modifier.fillMaxSize().padding(padding), verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            item {
                SwitchRow(
                    "Rest day", state.isRestDay, viewModel::setRestDay, Modifier.padding(horizontal = Spacing.screenGutter),
                    subtitle = "Rest days are never scored. Recharging counts.", titleStyle = MaterialTheme.typography.titleMedium,
                )
            }
            if (!state.isRestDay) {
                item { CapacityCard(state, viewModel) }
                item {
                    SectionHeader("Top priorities · ${state.top.size}/3", Modifier.padding(horizontal = Spacing.screenGutter))
                    Text(
                        "Tap the star on up to three tasks that matter most today.",
                        Modifier.padding(horizontal = Spacing.screenGutter),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                // One grouped white container; a day's candidates are few, so a single item is fine.
                if (state.candidates.isNotEmpty()) {
                    item(key = "candidates") {
                        Column {
                            state.candidates.forEachIndexed { i, task ->
                                PlanRow(
                                    task, task.id in state.planned, task.id in state.top, viewModel,
                                    Modifier.listGroupItem(i, state.candidates.size, dividerStart = 56.dp),
                                )
                            }
                        }
                    }
                }
                if (state.candidates.isEmpty()) {
                    item { Text("No open tasks for this day yet. Add some with +.", Modifier.padding(Spacing.screenGutter), style = MaterialTheme.typography.bodyMedium) }
                }
                if (state.overdue.isNotEmpty()) {
                    item {
                        Column(Modifier.padding(horizontal = Spacing.screenGutter), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                            SectionHeader("From earlier days · ${state.overdue.size}")
                            Text(
                                "These are still open. They stay where they are unless you choose to bring them here.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            state.overdue.take(5).forEach { Text("• ${it.title}", style = MaterialTheme.typography.bodyMedium) }
                            TextButton(onClick = viewModel::pullOverdue, modifier = Modifier.offset(x = -Spacing.md)) { Text("Move them to ${Format.relativeDay(d, state.today ?: d)}") }
                        }
                    }
                }
            }
            item {
                Button(colors = BloomButtons.primary(), onClick = { viewModel.save() }, modifier = Modifier.fillMaxWidth().padding(Spacing.screenGutter)) { Text("Save plan") }
                Spacer(Modifier.height(Spacing.xl))
            }
        }
        state.overCapacityPrompt?.let { cap ->
            AlertDialog(
                onDismissRequest = viewModel::dismissOverCapacity,
                title = { Text("Your plan looks a little full") },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                        Text("Available time: ${PlanCapacityCalculator.formatMinutes(cap.availableMinutes ?: 0)}")
                        Text("Planned: ${PlanCapacityCalculator.formatMinutes(cap.plannedMinutes)}")
                        if (cap.unestimatedTasks > 0) Text("${cap.unestimatedTasks} task(s) without an estimate count as ${PlanCapacityCalculator.DEFAULT_ESTIMATE_MINUTES} min each.", style = MaterialTheme.typography.bodySmall)
                        Text("It's your call — you know your day best.", style = MaterialTheme.typography.bodySmall)
                    }
                },
                confirmButton = { TextButton(onClick = { viewModel.save(keepDespiteOverCapacity = true) }) { Text("Keep my plan") } },
                dismissButton = { TextButton(onClick = viewModel::dismissOverCapacity) { Text("Adjust plan") } },
            )
        }
        state.message?.let { msg ->
            AlertDialog(onDismissRequest = viewModel::consumeMessage, confirmButton = { TextButton(onClick = viewModel::consumeMessage) { Text("OK") } }, text = { Text(msg) })
        }
    }
}

@Composable
private fun CapacityCard(state: PlannerState, viewModel: PlannerViewModel) {
    val cap = state.capacity
    Column(Modifier.padding(horizontal = Spacing.screenGutter)) {
        BloomCard {
            Text("AVAILABLE TIME", style = MaterialTheme.typography.labelSmall)
            ChoiceChips(listOf<Int?>(null) + AVAILABLE_OPTIONS, state.availableMinutes, { it?.let(Format::compactMinutes) ?: "Not sure" }, viewModel::setAvailable)
            Text("BREAKS", style = MaterialTheme.typography.labelSmall)
            ChoiceChips(BREAK_OPTIONS, state.breakMinutes, { if (it == 0) "None" else Format.compactMinutes(it) }, viewModel::setBreaks)
            Text("ENERGY", style = MaterialTheme.typography.labelSmall)
            ChoiceChips(listOf<EnergyLevel?>(null) + EnergyLevel.entries, state.energy, { it?.label ?: "Skip" }, viewModel::setEnergy)
            if (cap != null) {
                val over = cap.isOverCapacity
                Text(
                    "Planned ${Format.compactMinutes(cap.plannedMinutes)}" + (cap.availableMinutes?.let { " of ${Format.compactMinutes(it)}" } ?: ""),
                    style = MaterialTheme.typography.titleSmall,
                    color = if (over) BloomTheme.colors.warning else MaterialTheme.colorScheme.onSurface,
                )
                if (cap.availableMinutes != null) {
                    BloomProgressBar(cap.loadFraction, color = if (over) BloomTheme.colors.warning else MaterialTheme.colorScheme.secondary, label = "Plan load")
                }
                if (over) Text("That's ${Format.compactMinutes(cap.overByMinutes)} more than your available time.", style = MaterialTheme.typography.bodySmall)
                if (cap.appointmentMinutes > 0) Text("Includes ${Format.compactMinutes(cap.appointmentMinutes)} of appointments.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun PlanRow(task: Task, planned: Boolean, top: Boolean, viewModel: PlannerViewModel, modifier: Modifier = Modifier) {
    Row(
        modifier.fillMaxWidth().heightIn(min = 64.dp).clickable(role = Role.Checkbox) { viewModel.togglePlanned(task) }.padding(horizontal = Spacing.sm)
            .semantics(mergeDescendants = true) { stateDescription = if (planned) "Planned" else "Not planned" },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(planned, { viewModel.togglePlanned(task) })
        Column(Modifier.weight(1f).padding(vertical = Spacing.sm), verticalArrangement = Arrangement.spacedBy(Spacing.xxs)) {
            Text(task.title, style = MaterialTheme.typography.bodyLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    listOfNotNull(task.dueDate?.let { "Due ${Format.shortDate(it)}" } ?: "No date", task.estimatedMinutes?.let(Format::compactMinutes)).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                PriorityPill(task.priority)
            }
        }
        IconButton(onClick = { viewModel.toggleTop(task) }, modifier = Modifier.semantics { contentDescription = if (top) "Remove ${task.title} from top priorities" else "Make ${task.title} a top priority" }) {
            Icon(if (top) Icons.Filled.Star else Icons.Outlined.StarOutline, null, tint = if (top) BloomTheme.colors.warning else MaterialTheme.colorScheme.outline)
        }
    }
}
