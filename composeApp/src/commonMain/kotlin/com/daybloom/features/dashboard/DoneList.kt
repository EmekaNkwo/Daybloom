package com.daybloom.features.dashboard

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.daybloom.components.BloomCard
import com.daybloom.components.BloomTopBar
import com.daybloom.components.ChoiceChips
import com.daybloom.components.Format
import com.daybloom.components.LoadingState
import com.daybloom.components.SectionHeader
import com.daybloom.design.Spacing
import com.daybloom.domain.models.AppointmentStatus
import com.daybloom.domain.models.DailyReflection
import com.daybloom.domain.models.FocusStatus
import com.daybloom.domain.models.ManualWin
import com.daybloom.domain.models.Mood
import com.daybloom.domain.repositories.AppointmentRepository
import com.daybloom.domain.repositories.FocusRepository
import com.daybloom.domain.repositories.HabitRepository
import com.daybloom.domain.repositories.JournalRepository
import com.daybloom.domain.repositories.TaskRepository
import com.daybloom.domain.services.AppointmentScheduleService
import com.daybloom.domain.usecases.DeleteReflectionUseCase
import com.daybloom.domain.usecases.ManualWinUseCases
import com.daybloom.domain.usecases.SaveReflectionUseCase
import com.daybloom.features.common.AppMessages
import com.daybloom.features.common.runAction
import com.daybloom.platform.PlatformDateTimeService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.datetime.LocalDate
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf
import com.daybloom.components.BloomButtons
import com.daybloom.components.BloomTextField
import androidx.compose.material3.AlertDialog

data class ReflectionForm(
    val wentWell: String = "",
    val biggestWin: String = "",
    val moveToTomorrow: String = "",
    val mood: Mood? = null,
    val tomorrowPriority: String = "",
)

data class DoneListState(
    val loading: Boolean = true,
    val date: LocalDate? = null,
    val wins: List<String> = emptyList(),
    val manualWins: List<ManualWin> = emptyList(),
    val reflection: DailyReflection? = null,
)

class DoneListViewModel(
    dateArg: String,
    tasks: TaskRepository,
    appointments: AppointmentRepository,
    focus: FocusRepository,
    habits: HabitRepository,
    private val journal: JournalRepository,
    private val saveReflection: SaveReflectionUseCase,
    private val deleteReflection: DeleteReflectionUseCase,
    private val winsUseCase: ManualWinUseCases,
    private val messages: AppMessages,
    clock: PlatformDateTimeService,
) : ViewModel() {
    private val date: LocalDate = runCatching { LocalDate.parse(dateArg) }.getOrNull() ?: clock.today()
    val form = MutableStateFlow(ReflectionForm())

    init {
        viewModelScope.launch {
            journal.observeReflection(date).first()?.let { r ->
                form.value = ReflectionForm(r.wentWell, r.biggestWin, r.moveToTomorrow, r.mood, r.tomorrowTopPriority)
            }
        }
    }

    val state: StateFlow<DoneListState> = combine(
        tasks.observeCompletedBetween(date, date),
        combine(appointments.observeAll(), appointments.observeOccurrenceStatuses()) { a, s -> AppointmentScheduleService.occurrencesOn(a, s, date) },
        focus.observeBetween(date, date),
        combine(habits.observeHabits(), habits.observeCompletions()) { h, c -> h to c.filter { it.date == date } },
        combine(journal.observeWins(date), journal.observeReflection(date)) { w, r -> w to r },
    ) { done, occurrences, sessions, (habitList, completions), (manual, reflection) ->
        val wins = buildList {
            done.forEach { add("✓ ${it.title}") }
            occurrences.filter { it.status == AppointmentStatus.ATTENDED }.forEach { add("✓ Attended ${it.appointment.title}") }
            sessions.filter { it.status == FocusStatus.COMPLETED || it.status == FocusStatus.INTERRUPTED }.filter { it.accumulatedSeconds >= 60 }.forEach {
                add("✓ Focused for ${Format.compactMinutes((it.accumulatedSeconds / 60).toInt())}")
            }
            completions.mapNotNull { c -> habitList.firstOrNull { it.id == c.habitId } }.forEach { add("✓ ${it.emoji} ${it.title}") }
        }
        DoneListState(loading = false, date = date, wins = wins, manualWins = manual, reflection = reflection)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DoneListState())

    fun addWin(text: String) = runAction(messages) {
        winsUseCase.add(date, text)?.let { messages.post(it) }
    }

    fun removeWin(id: String) = runAction(messages) { winsUseCase.delete(id) }

    fun updateForm(transform: (ReflectionForm) -> ReflectionForm) = form.update(transform)

    fun save() = runAction(messages, "Couldn't save your reflection.") {
        val f = form.value
        val error = saveReflection(date, f.wentWell, f.biggestWin, f.moveToTomorrow, f.mood, f.tomorrowPriority)
        messages.post(error ?: "Reflection saved — just for you")
    }

    fun delete() = runAction(messages) {
        deleteReflection(date)
        form.value = ReflectionForm()
        messages.post("Reflection deleted")
    }
}

@Composable
fun DoneListScreen(date: String, onBack: () -> Unit, viewModel: DoneListViewModel = koinViewModel(key = "done-$date") { parametersOf(date) }) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val form by viewModel.form.collectAsStateWithLifecycle()
    var newWin by rememberSaveable { mutableStateOf("") }
    var confirmDelete by rememberSaveable { mutableStateOf(false) }
    val d = state.date
    Scaffold(topBar = { BloomTopBar(d?.let { "Done list · ${Format.shortDate(it)}" } ?: "Done list", onBack = onBack) }) { padding ->
        if (state.loading || d == null) {
            LoadingState(Modifier.padding(padding))
            return@Scaffold
        }
        Column(
            Modifier.fillMaxSize().padding(padding).imePadding().verticalScroll(rememberScrollState()).padding(horizontal = Spacing.screenGutter),
            verticalArrangement = Arrangement.spacedBy(Spacing.md),
        ) {
            BloomCard {
                Text("LOOK WHAT YOU DID", style = MaterialTheme.typography.labelSmall)
                if (state.wins.isEmpty() && state.manualWins.isEmpty()) {
                    Text("Nothing logged yet — and that's okay. Rest and small things count too.", style = MaterialTheme.typography.bodyMedium)
                }
                state.wins.forEach { Text(it, style = MaterialTheme.typography.bodyLarge) }
                state.manualWins.forEach { w ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("★ ${w.text}", Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                        IconButton(onClick = { viewModel.removeWin(w.id) }) { Icon(Icons.Filled.Close, "Remove ${w.text}") }
                    }
                }
                if (state.wins.isNotEmpty() || state.manualWins.isNotEmpty()) Text("You made progress today.", style = MaterialTheme.typography.titleSmall)
            }
            BloomTextField(
                newWin, { newWin = it.take(200) }, Modifier.fillMaxWidth(),
                label = { Text("Add a win") }, placeholder = { Text("Helped a friend move") }, singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { if (newWin.isNotBlank()) { viewModel.addWin(newWin); newWin = "" } }),
                trailingIcon = { TextButton(onClick = { if (newWin.isNotBlank()) { viewModel.addWin(newWin); newWin = "" } }) { Text("Add") } },
            )

            SectionHeader("Daily reflection (private)")
            Text("Only you see this. How you feel is never used to score you.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            ReflectionField("What went well?", form.wentWell) { v -> viewModel.updateForm { it.copy(wentWell = v) } }
            ReflectionField("What was my biggest win?", form.biggestWin) { v -> viewModel.updateForm { it.copy(biggestWin = v) } }
            ReflectionField("What needs to move to tomorrow?", form.moveToTomorrow) { v -> viewModel.updateForm { it.copy(moveToTomorrow = v) } }
            SectionHeader("How did today feel?")
            ChoiceChips(listOf<Mood?>(null) + Mood.entries, form.mood, { it?.let { m -> "${m.emoji} ${m.label}" } ?: "Skip" }, { m -> viewModel.updateForm { it.copy(mood = m) } })
            ReflectionField("What is tomorrow's top priority?", form.tomorrowPriority) { v -> viewModel.updateForm { it.copy(tomorrowPriority = v) } }
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                Button(colors = BloomButtons.primary(), onClick = { viewModel.save() }) { Text("Save reflection") }
                if (state.reflection != null) TextButton(onClick = { confirmDelete = true }) { Text("Delete") }
            }
            Spacer(Modifier.height(Spacing.xl))
        }
    }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete this reflection?") },
            text = { Text("Your notes and mood for this day will be removed. This can't be undone.") },
            confirmButton = { TextButton(onClick = { confirmDelete = false; viewModel.delete() }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Keep") } },
        )
    }
}

@Composable
private fun ReflectionField(label: String, value: String, onChange: (String) -> Unit) {
    BloomTextField(value, { onChange(it.take(2_000)) }, Modifier.fillMaxWidth(), label = { Text(label) }, minLines = 2)
}
