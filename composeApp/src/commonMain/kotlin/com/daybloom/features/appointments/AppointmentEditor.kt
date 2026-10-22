package com.daybloom.features.appointments

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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
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
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.daybloom.components.BloomCard
import com.daybloom.components.BloomDatePickerDialog
import com.daybloom.components.BloomTimePickerDialog
import com.daybloom.components.BloomTopBar
import com.daybloom.components.ChoiceChips
import com.daybloom.components.EmptyState
import com.daybloom.components.Format
import com.daybloom.components.LoadingState
import com.daybloom.components.MultiChoiceChips
import com.daybloom.components.SectionHeader
import com.daybloom.components.TaskRow
import com.daybloom.design.Spacing
import com.daybloom.features.common.AppMessage
import kotlinx.datetime.LocalDateTime
import com.daybloom.domain.models.Appointment
import com.daybloom.domain.models.AppointmentKind
import com.daybloom.domain.models.AppointmentStatus
import com.daybloom.domain.models.RecurrenceFrequency
import com.daybloom.domain.models.RecurrenceRule
import com.daybloom.domain.models.Task
import com.daybloom.domain.repositories.AppointmentRepository
import com.daybloom.domain.repositories.TaskRepository
import com.daybloom.domain.usecases.AppointmentDraft
import com.daybloom.domain.usecases.CancelAppointmentUseCase
import com.daybloom.domain.usecases.CompleteTaskUseCase
import com.daybloom.domain.usecases.DeleteAppointmentUseCase
import com.daybloom.domain.usecases.ReopenTaskUseCase
import com.daybloom.domain.usecases.SaveAppointmentUseCase
import com.daybloom.domain.usecases.SetAppointmentAttendanceUseCase
import com.daybloom.features.common.AppMessages
import com.daybloom.features.common.DraftHandoff
import com.daybloom.features.common.runAction
import com.daybloom.platform.IdGenerator
import com.daybloom.platform.PlatformDateTimeService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf
import com.daybloom.components.BloomButtons
import com.daybloom.components.BloomTextField
import com.daybloom.components.rememberHasChanges
import com.daybloom.components.unsavedChangesGuard

data class AppointmentEditorArgs(val appointmentId: String?, val date: String?, val prefill: Boolean)

private val OFFSETS = listOf(0 to "At start", 10 to "10 min", 15 to "15 min", 30 to "30 min", 60 to "1 hour", 24 * 60 to "1 day")

enum class AppointmentRepeat(val label: String) { NONE("Once"), DAILY("Daily"), WEEKLY("Weekly"), MONTHLY("Monthly") }

data class AppointmentForm(
    val title: String = "",
    val kind: AppointmentKind = AppointmentKind.MEETING,
    val date: LocalDate,
    val start: LocalTime = LocalTime(9, 0),
    val end: LocalTime = LocalTime(10, 0),
    val location: String = "",
    val attendees: String = "",
    val notes: String = "",
    val repeat: AppointmentRepeat = AppointmentRepeat.NONE,
    val offsets: Set<Int> = setOf(15),
    val newPrepTasks: List<Pair<String, String>> = emptyList(),
) {
    fun toDraft() = AppointmentDraft(
        title = title,
        kind = kind,
        date = date,
        startTime = start,
        endTime = end,
        location = location,
        attendees = attendees.split(',').map { it.trim() }.filter { it.isNotEmpty() },
        notes = notes,
        recurrence = when (repeat) {
            AppointmentRepeat.NONE -> null
            AppointmentRepeat.DAILY -> RecurrenceRule.daily()
            AppointmentRepeat.WEEKLY -> RecurrenceRule(RecurrenceFrequency.WEEKLY)
            AppointmentRepeat.MONTHLY -> RecurrenceRule.monthly()
        },
        reminderOffsetsMinutes = offsets.sorted(),
        newPreparationTasks = newPrepTasks.map { it.second },
    )
}

data class AppointmentEditorState(
    val loading: Boolean = true,
    val existing: Appointment? = null,
    val occurrenceDate: LocalDate? = null,
    val occurrenceStatus: AppointmentStatus = AppointmentStatus.SCHEDULED,
    val form: AppointmentForm? = null,
    val prepTasks: List<Task> = emptyList(),
    val errors: Map<String, String> = emptyMap(),
    val closed: Boolean = false,
    val notFound: Boolean = false,
    val today: LocalDate? = null,
    /** Attendance can only be recorded once the occurrence has started. */
    val occurrenceStarted: Boolean = false,
)

class AppointmentEditorViewModel(
    private val args: AppointmentEditorArgs,
    private val appointments: AppointmentRepository,
    private val tasks: TaskRepository,
    private val save: SaveAppointmentUseCase,
    private val setAttendance: SetAppointmentAttendanceUseCase,
    private val cancel: CancelAppointmentUseCase,
    private val delete: DeleteAppointmentUseCase,
    private val completeTask: CompleteTaskUseCase,
    private val reopenTask: ReopenTaskUseCase,
    private val handoff: DraftHandoff,
    private val ids: IdGenerator,
    private val messages: AppMessages,
    private val clock: PlatformDateTimeService,
) : ViewModel() {
    private val _state = MutableStateFlow(AppointmentEditorState())
    val state: StateFlow<AppointmentEditorState> = _state.asStateFlow()

    init {
        viewModelScope.launch { load() }
    }

    private suspend fun load() {
        val date = args.date?.let { runCatching { LocalDate.parse(it) }.getOrNull() } ?: clock.today()
        val id = args.appointmentId
        if (id == null) {
            val draft = if (args.prefill) handoff.takeAppointment() else null
            val form = draft?.let {
                AppointmentForm(
                    title = it.title, kind = it.kind, date = it.date, start = it.startTime, end = it.endTime,
                    repeat = when (it.recurrence?.frequency) {
                        RecurrenceFrequency.DAILY -> AppointmentRepeat.DAILY
                        RecurrenceFrequency.WEEKLY -> AppointmentRepeat.WEEKLY
                        RecurrenceFrequency.MONTHLY -> AppointmentRepeat.MONTHLY
                        else -> AppointmentRepeat.NONE
                    },
                )
            } ?: AppointmentForm(date = date)
            _state.value = AppointmentEditorState(loading = false, form = form, today = clock.today())
            return
        }
        val appt = appointments.get(id)
        if (appt == null) {
            _state.value = AppointmentEditorState(loading = false, notFound = true)
            return
        }
        val status = appointments.getOccurrenceStatuses()["${appt.id}@$date"] ?: AppointmentStatus.SCHEDULED
        _state.value = AppointmentEditorState(
            loading = false,
            existing = appt,
            occurrenceDate = date,
            occurrenceStatus = if (appt.status == AppointmentStatus.CANCELLED) AppointmentStatus.CANCELLED else status,
            form = AppointmentForm(
                title = appt.title, kind = appt.kind, date = appt.date, start = appt.startTime, end = appt.endTime,
                location = appt.location, attendees = appt.attendees.joinToString(", "), notes = appt.notes,
                repeat = when (appt.recurrence?.frequency) {
                    RecurrenceFrequency.DAILY -> AppointmentRepeat.DAILY
                    RecurrenceFrequency.WEEKLY -> AppointmentRepeat.WEEKLY
                    RecurrenceFrequency.MONTHLY, RecurrenceFrequency.YEARLY -> AppointmentRepeat.MONTHLY
                    null -> AppointmentRepeat.NONE
                },
                offsets = appt.reminderOffsetsMinutes.toSet(),
            ),
            today = clock.today(),
            occurrenceStarted = LocalDateTime(date, appt.startTime) <= clock.localNow(),
        )
        tasks.observeForAppointment(appt.id).onEach { list -> _state.update { it.copy(prepTasks = list) } }.launchIn(viewModelScope)
    }

    fun update(transform: (AppointmentForm) -> AppointmentForm) =
        _state.update { s -> s.copy(form = s.form?.let(transform), errors = emptyMap()) }

    fun addPrepTask() = update { it.copy(newPrepTasks = it.newPrepTasks + (ids.newId() to "")) }
    fun editPrepTask(id: String, title: String) = update { f -> f.copy(newPrepTasks = f.newPrepTasks.map { if (it.first == id) id to title.take(200) else it }) }
    fun removePrepTask(id: String) = update { f -> f.copy(newPrepTasks = f.newPrepTasks.filterNot { it.first == id }) }

    fun save() {
        val form = _state.value.form ?: return
        runAction(messages, "Couldn't save the appointment.") {
            when (val result = save(form.toDraft(), _state.value.existing?.id)) {
                is SaveAppointmentUseCase.Result.Saved -> {
                    messages.post(if (_state.value.existing == null) "Appointment added" else "Appointment updated")
                    _state.update { it.copy(closed = true) }
                }
                is SaveAppointmentUseCase.Result.Invalid -> _state.update { it.copy(errors = result.errors) }
            }
        }
    }

    fun recordAttendance(status: AppointmentStatus) {
        val appt = _state.value.existing ?: return
        val date = _state.value.occurrenceDate ?: return
        runAction(messages) {
            setAttendance(appt.id, date, status)
            _state.update { it.copy(occurrenceStatus = status) }
            if (status == AppointmentStatus.ATTENDED) messages.post("Marked as attended")
        }
    }

    fun cancelAppointment() = _state.value.existing?.let { appt ->
        runAction(messages) {
            cancel(appt.id)
            messages.post(AppMessage("Appointment cancelled", "Undo") { cancel.restore(appt.id) })
            _state.update { it.copy(closed = true) }
        }
    }

    fun restoreAppointment() = _state.value.existing?.let { appt ->
        runAction(messages) {
            cancel.restore(appt.id)
            messages.post("Appointment restored")
            _state.update { it.copy(closed = true) }
        }
    }

    fun deleteAppointment() = _state.value.existing?.let { appt ->
        runAction(messages) {
            delete(appt.id)
            messages.post("Appointment deleted")
            _state.update { it.copy(closed = true) }
        }
    }

    fun togglePrep(task: Task, done: Boolean) = runAction(messages) { if (done) completeTask(task.id) else reopenTask(task.id) }
}

@Composable
fun AppointmentEditorScreen(
    args: AppointmentEditorArgs,
    onClose: () -> Unit,
    onOpenTask: (String) -> Unit,
    viewModel: AppointmentEditorViewModel = koinViewModel(key = "appt-${args.appointmentId}-${args.date}") { parametersOf(args) },
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(state.closed) { if (state.closed) onClose() }
    // Leaving with unsaved edits asks first instead of discarding them silently.
    val requestClose = unsavedChangesGuard(hasChanges = !state.closed && rememberHasChanges(ready = !state.loading && state.form != null, current = state.form), onLeave = onClose)
    var picker by remember { mutableStateOf<String?>(null) }
    var confirmDelete by remember { mutableStateOf(false) }
    Scaffold(topBar = {
        BloomTopBar(if (state.existing == null) "New appointment" else "Appointment", onBack = requestClose, actions = {
            TextButton(onClick = viewModel::save, enabled = state.form != null) { Text("Save") }
        })
    }) { padding ->
        val form = state.form
        when {
            state.loading -> LoadingState(Modifier.padding(padding))
            state.notFound || form == null -> EmptyState("🍂", "Appointment not found", "It may have been deleted.", Modifier.padding(padding), "Go back", onClose)
            else -> Column(
                Modifier.fillMaxSize().padding(padding).imePadding().verticalScroll(rememberScrollState()).padding(horizontal = Spacing.screenGutter),
                verticalArrangement = Arrangement.spacedBy(Spacing.md),
            ) {
                val existing = state.existing
                val occurrenceDate = state.occurrenceDate
                if (existing != null && existing.status == AppointmentStatus.CANCELLED) {
                    BloomCard(tonal = true) {
                        Text("This appointment is cancelled", style = MaterialTheme.typography.titleSmall)
                        Text("It no longer sends reminders. Restore it to bring it back.", style = MaterialTheme.typography.bodySmall)
                        FilledTonalButton(onClick = viewModel::restoreAppointment) { Text("Restore appointment") }
                    }
                }
                if (existing != null && occurrenceDate != null && state.occurrenceStatus != AppointmentStatus.CANCELLED) {
                    BloomCard(tonal = true) {
                        Text("${Format.longDate(occurrenceDate)} · ${Format.timeRange(existing.startTime, existing.endTime)}", style = MaterialTheme.typography.titleSmall)
                        Text(
                            when (state.occurrenceStatus) {
                                AppointmentStatus.ATTENDED -> "You attended this."
                                AppointmentStatus.MISSED -> "Marked as missed — that's okay."
                                else -> if (state.occurrenceStarted) "Attendance is only recorded when you say so." else "You can record attendance once it starts."
                            },
                            style = MaterialTheme.typography.bodySmall,
                        )
                        val canRecord = state.occurrenceStarted || state.occurrenceStatus != AppointmentStatus.SCHEDULED
                        if (canRecord) Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                            FilledTonalButton(onClick = { viewModel.recordAttendance(AppointmentStatus.ATTENDED) }) { Text("Attended") }
                            OutlinedButton(onClick = { viewModel.recordAttendance(AppointmentStatus.MISSED) }) { Text("Missed") }
                            if (state.occurrenceStatus != AppointmentStatus.SCHEDULED) {
                                TextButton(onClick = { viewModel.recordAttendance(AppointmentStatus.SCHEDULED) }) { Text("Clear") }
                            }
                        }
                    }
                }
                BloomTextField(
                    form.title, { v -> viewModel.update { it.copy(title = v.take(200)) } }, Modifier.fillMaxWidth(),
                    label = { Text("Title") }, isError = "title" in state.errors, supportingText = state.errors["title"]?.let { { Text(it) } },
                    singleLine = true, keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                )
                ChoiceChips(AppointmentKind.entries, form.kind, { "${it.emoji} ${it.label}" }, { k -> viewModel.update { it.copy(kind = k) } })
                SectionHeader("When")
                Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                    AssistChip(onClick = { picker = "date" }, label = { Text(Format.shortDate(form.date)) })
                    AssistChip(onClick = { picker = "start" }, label = { Text(Format.time(form.start)) })
                    Text("to", Modifier.align(Alignment.CenterVertically))
                    AssistChip(onClick = { picker = "end" }, label = { Text(Format.time(form.end)) })
                }
                state.errors["time"]?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                ChoiceChips(AppointmentRepeat.entries, form.repeat, { it.label }, { r -> viewModel.update { it.copy(repeat = r) } })
                SectionHeader("Remind me before")
                MultiChoiceChips(OFFSETS.map { it.first }, form.offsets, { o -> OFFSETS.first { it.first == o }.second }, { o ->
                    viewModel.update { f -> f.copy(offsets = if (o in f.offsets) f.offsets - o else f.offsets + o) }
                })
                SectionHeader("Details")
                BloomTextField(form.location, { v -> viewModel.update { it.copy(location = v.take(200)) } }, Modifier.fillMaxWidth(), label = { Text("Location") }, singleLine = true)
                BloomTextField(form.attendees, { v -> viewModel.update { it.copy(attendees = v.take(500)) } }, Modifier.fillMaxWidth(), label = { Text("Attendees (comma separated)") })
                BloomTextField(form.notes, { v -> viewModel.update { it.copy(notes = v) } }, Modifier.fillMaxWidth(), label = { Text("Notes") }, minLines = 2)

                SectionHeader("Preparation")
                val today = state.today
                if (today != null) {
                    state.prepTasks.forEach { t -> TaskRow(t, today, onToggle = { viewModel.togglePrep(t, it) }, onClick = { onOpenTask(t.id) }) }
                }
                form.newPrepTasks.forEach { (id, title) ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        BloomTextField(title, { viewModel.editPrepTask(id, it) }, Modifier.weight(1f), placeholder = { Text("Preparation task") }, singleLine = true)
                        IconButton(onClick = { viewModel.removePrepTask(id) }) { Icon(Icons.Filled.Close, "Remove") }
                    }
                }
                TextButton(onClick = viewModel::addPrepTask) { Icon(Icons.Filled.Add, null); Text("Add preparation task") }

                Button(colors = BloomButtons.primary(), onClick = viewModel::save, modifier = Modifier.fillMaxWidth()) { Text(if (existing == null) "Add appointment" else "Save changes") }
                if (existing != null) {
                    Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                        if (existing.status != AppointmentStatus.CANCELLED) OutlinedButton(onClick = { viewModel.cancelAppointment() }) { Text("Cancel appointment") }
                        TextButton(onClick = { confirmDelete = true }) { Text("Delete") }
                    }
                }
                Spacer(Modifier.height(Spacing.xl))
            }
        }
        val f = state.form
        if (f != null) {
            when (picker) {
                "date" -> BloomDatePickerDialog(f.date, { picker = null }, { d -> picker = null; d?.let { date -> viewModel.update { it.copy(date = date) } } })
                "start" -> BloomTimePickerDialog(f.start, { picker = null }, { t ->
                    picker = null
                    t?.let { start ->
                        val length = (f.end.toSecondOfDay() - f.start.toSecondOfDay()).coerceAtLeast(15 * 60)
                        val endSec = (start.toSecondOfDay() + length).coerceAtMost(23 * 3600 + 59 * 60)
                        viewModel.update { it.copy(start = start, end = LocalTime.fromSecondOfDay(endSec)) }
                    }
                })
                "end" -> BloomTimePickerDialog(f.end, { picker = null }, { t -> picker = null; t?.let { end -> viewModel.update { it.copy(end = end) } } })
            }
        }
        if (confirmDelete) {
            AlertDialog(
                onDismissRequest = { confirmDelete = false },
                title = { Text("Delete this appointment?") },
                text = { Text("This removes every occurrence and its reminders. Preparation tasks are kept.") },
                confirmButton = { TextButton(onClick = { confirmDelete = false; viewModel.deleteAppointment() }) { Text("Delete") } },
                dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Keep") } },
            )
        }
    }
}
