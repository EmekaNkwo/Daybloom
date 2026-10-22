package com.daybloom.features.focus

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.daybloom.components.BarDatum
import com.daybloom.components.BloomCard
import com.daybloom.components.BloomTopBar
import com.daybloom.components.ChoiceChips
import com.daybloom.components.Format
import com.daybloom.components.ScoreRing
import com.daybloom.components.SectionHeader
import com.daybloom.components.ShareBars
import com.daybloom.design.Spacing
import com.daybloom.domain.models.FocusSession
import com.daybloom.domain.models.FocusStatus
import com.daybloom.domain.models.Task
import com.daybloom.domain.repositories.FocusRepository
import com.daybloom.domain.repositories.TaskRepository
import com.daybloom.domain.services.DateMath
import com.daybloom.domain.services.FocusTimerCalculator
import com.daybloom.domain.usecases.CompleteTaskUseCase
import com.daybloom.domain.usecases.FocusSessionUseCases
import com.daybloom.features.common.AppMessages
import com.daybloom.features.common.runAction
import com.daybloom.platform.PlatformDateTimeService
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf
import kotlin.time.Duration.Companion.seconds
import com.daybloom.components.BloomButtons

data class FocusArgs(val taskId: String?, val minutes: Int?)

data class FocusStats(
    val todayMinutes: Int,
    val completedToday: Int,
    val interruptedToday: Int,
    val pausesToday: Int,
    val weekByCategory: List<BarDatum>,
)

data class FocusState(
    val active: FocusSession? = null,
    val remainingSeconds: Long = 0,
    val focusedSeconds: Long = 0,
    val linkedTask: Task? = null,
    val openTasks: List<Task> = emptyList(),
    val stats: FocusStats? = null,
    /** Set right after a session linked to a task finishes: offer (never force) completing it. */
    val offerComplete: Task? = null,
)

class FocusViewModel(
    args: FocusArgs,
    private val focus: FocusRepository,
    private val tasks: TaskRepository,
    private val sessions: FocusSessionUseCases,
    private val completeTask: CompleteTaskUseCase,
    private val messages: AppMessages,
    private val clock: PlatformDateTimeService,
) : ViewModel() {
    val selectedMinutes = MutableStateFlow(args.minutes?.coerceIn(FocusSessionUseCases.MIN_MINUTES, FocusSessionUseCases.MAX_MINUTES) ?: 25)
    val selectedTaskId = MutableStateFlow(args.taskId)
    private val offer = MutableStateFlow<Task?>(null)

    private val ticker = flow {
        while (true) {
            emit(Unit)
            delay(1.seconds)
        }
    }

    val state: StateFlow<FocusState> = combine(
        focus.observeActive().flatMapLatest { active -> combine(ticker, kotlinx.coroutines.flow.flowOf(active)) { _, a -> a } },
        tasks.observeAll(),
        selectedTaskId,
        offer,
        focus.observeBetween(DateMath.plusDays(clock.today(), -6), clock.today()),
    ) { active, allTasks, selectedId, offered, recent ->
        val now = clock.now()
        val mono = clock.monotonicMillis()
        val remaining = active?.let { FocusTimerCalculator.remainingSeconds(it, mono, now) } ?: 0
        val today = clock.today()
        val todays = recent.filter { it.date == today && !it.isActive }
        val byCategory = recent.filter { !it.isActive }
            .groupBy { it.category }
            .map { (cat, list) ->
                val minutes = (list.sumOf { it.accumulatedSeconds } / 60).toInt()
                BarDatum(cat?.label ?: "No task", minutes.toFloat(), Format.compactMinutes(minutes))
            }
            .sortedByDescending { it.value }
        FocusState(
            active = active,
            remainingSeconds = remaining,
            focusedSeconds = active?.let { FocusTimerCalculator.focusedSeconds(it, mono, now) } ?: 0,
            linkedTask = (active?.taskId ?: selectedId)?.let { id -> allTasks.firstOrNull { it.id == id } },
            openTasks = allTasks.filter { it.isOpen },
            stats = FocusStats(
                todayMinutes = (todays.sumOf { it.accumulatedSeconds } / 60).toInt(),
                completedToday = todays.count { it.status == FocusStatus.COMPLETED },
                interruptedToday = todays.count { it.status == FocusStatus.INTERRUPTED },
                pausesToday = todays.sumOf { it.pauseCount },
                weekByCategory = byCategory,
            ),
            offerComplete = offered,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), FocusState())

    private var finishing = false

    init {
        // Completes a running session when its time is up while this screen is open. If the app is closed,
        // AppStartupUseCase reconciles it on the next launch instead.
        viewModelScope.launch {
            focus.observeActive().collectLatest { active ->
                while (active != null && active.status == FocusStatus.RUNNING) {
                    if (FocusTimerCalculator.isFinished(active, clock.monotonicMillis(), clock.now())) {
                        finishInternal(active, completed = true)
                        break
                    }
                    delay(1.seconds)
                }
            }
        }
    }

    fun start() = runAction(messages, "Couldn't start the session.") {
        sessions.start(selectedMinutes.value, selectedTaskId.value)
    }

    fun pause() = state.value.active?.let { a -> runAction(messages) { sessions.pause(a.id) } }
    fun resume() = state.value.active?.let { a -> runAction(messages) { sessions.resume(a.id) } }
    fun stop() = state.value.active?.let { a -> finishInternal(a, completed = false) }

    fun completeLinkedTask() {
        val task = offer.value ?: return
        offer.value = null
        runAction(messages) { completeTask(task.id)?.let { messages.post("“${task.title}” done") } }
    }

    fun dismissOffer() { offer.value = null }

    private fun finishInternal(session: FocusSession, completed: Boolean) {
        if (finishing) return
        finishing = true
        viewModelScope.launch {
            try {
                val done = sessions.finish(session.id, completed)
                val minutes = ((done?.accumulatedSeconds ?: 0) / 60).toInt()
                messages.post(if (completed) "Focus session complete · ${Format.compactMinutes(minutes)} 🎯" else "Session saved · ${Format.compactMinutes(minutes)} focused")
                session.taskId?.let { id -> tasks.get(id)?.takeIf { it.isOpen }?.let { offer.value = it } }
            } finally {
                finishing = false
            }
        }
    }
}

@Composable
fun FocusScreen(args: FocusArgs, onClose: () -> Unit, viewModel: FocusViewModel = koinViewModel(key = "focus-${args.taskId}-${args.minutes}") { parametersOf(args) }) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val minutes by viewModel.selectedMinutes.collectAsStateWithLifecycle()
    var taskMenu by remember { mutableStateOf(false) }
    Scaffold(topBar = { BloomTopBar("Focus", onBack = onClose) }) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(horizontal = Spacing.screenGutter),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(Spacing.md),
        ) {
            val active = state.active
            if (active != null) {
                val fraction = 1f - state.remainingSeconds.toFloat() / active.plannedSeconds.coerceAtLeast(1)
                ScoreRing(
                    score = (fraction * 100).toInt(),
                    size = 220.dp,
                    stroke = 14.dp,
                    caption = if (active.status == FocusStatus.PAUSED) "paused" else "remaining",
                    centerText = Format.timer(state.remainingSeconds.seconds),
                    description = "${(state.remainingSeconds + 59) / 60} minutes remaining",
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                )
                state.linkedTask?.let { Text("Working on: ${it.title}", style = MaterialTheme.typography.bodyLarge) }
                Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                    if (active.status == FocusStatus.RUNNING) FilledTonalButton(onClick = { viewModel.pause() }) { Text("Pause") }
                    else Button(colors = BloomButtons.primary(), onClick = { viewModel.resume() }) { Text("Resume") }
                    OutlinedButton(onClick = { viewModel.stop() }) { Text("Stop") }
                }
                Text(
                    "Focused ${Format.timer(state.focusedSeconds.seconds)} · ${active.pauseCount} pause${if (active.pauseCount == 1) "" else "s"}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text("The timer keeps counting if you leave the app.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                Spacer(Modifier.height(Spacing.sm))
                Text("${minutes} min", style = MaterialTheme.typography.displayMedium, fontWeight = FontWeight.Light)
                ChoiceChips(FocusSessionUseCases.PRESETS, minutes.takeIf { it in FocusSessionUseCases.PRESETS }, { "$it min" }, { viewModel.selectedMinutes.value = it })
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { viewModel.selectedMinutes.value = (minutes - 5).coerceAtLeast(5) }) { Icon(Icons.Filled.Remove, "Five minutes less") }
                    Text("Custom", style = MaterialTheme.typography.labelLarge)
                    IconButton(onClick = { viewModel.selectedMinutes.value = (minutes + 5).coerceAtMost(FocusSessionUseCases.MAX_MINUTES) }) { Icon(Icons.Filled.Add, "Five minutes more") }
                }
                Column {
                    TextButton(onClick = { taskMenu = true }) { Text(state.linkedTask?.let { "Task: ${it.title}" } ?: "Link a task (optional)") }
                    DropdownMenu(taskMenu, { taskMenu = false }) {
                        DropdownMenuItem({ Text("No task") }, { taskMenu = false; viewModel.selectedTaskId.value = null })
                        state.openTasks.take(30).forEach { t ->
                            DropdownMenuItem({ Text(t.title) }, { taskMenu = false; viewModel.selectedTaskId.value = t.id })
                        }
                    }
                }
                Button(colors = BloomButtons.primary(), onClick = { viewModel.start() }, modifier = Modifier.fillMaxWidth()) { Text("Start focusing") }
            }

            state.offerComplete?.let { task ->
                BloomCard(tonal = true) {
                    Text("Is “${task.title}” finished?", style = MaterialTheme.typography.titleSmall)
                    Text("Only mark it done if it really is — focus time counts either way.", style = MaterialTheme.typography.bodySmall)
                    Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                        Button(colors = BloomButtons.primary(), onClick = { viewModel.completeLinkedTask() }) { Text("Mark done") }
                        TextButton(onClick = { viewModel.dismissOffer() }) { Text("Not yet") }
                    }
                }
            }

            state.stats?.let { stats ->
                SectionHeader("Today")
                BloomCard {
                    Text("${Format.compactMinutes(stats.todayMinutes)} focused", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "${stats.completedToday} completed · ${stats.interruptedToday} stopped early · ${stats.pausesToday} pause${if (stats.pausesToday == 1) "" else "s"}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (stats.weekByCategory.isNotEmpty()) {
                    SectionHeader("Last 7 days by category")
                    ShareBars(stats.weekByCategory)
                }
            }
            Spacer(Modifier.height(Spacing.xl))
        }
    }
}
