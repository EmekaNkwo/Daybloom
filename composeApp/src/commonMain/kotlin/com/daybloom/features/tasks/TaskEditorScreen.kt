package com.daybloom.features.tasks

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
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.daybloom.components.BloomDatePickerDialog
import com.daybloom.components.BloomTimePickerDialog
import com.daybloom.components.BloomTopBar
import com.daybloom.components.ChoiceChips
import com.daybloom.components.EmptyState
import com.daybloom.components.Format
import com.daybloom.components.LoadingState
import com.daybloom.components.MultiChoiceChips
import com.daybloom.components.SectionHeader
import com.daybloom.design.Spacing
import com.daybloom.domain.models.Priority
import com.daybloom.domain.models.TaskCategory
import com.daybloom.domain.models.TaskStatus
import com.daybloom.domain.models.shortName
import kotlinx.datetime.DayOfWeek
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf
import com.daybloom.components.BloomButtons
import com.daybloom.components.BloomTextField
import com.daybloom.components.rememberHasChanges
import com.daybloom.components.unsavedChangesGuard
import com.daybloom.components.SwitchRow

private val ESTIMATES = listOf(10, 15, 30, 45, 60, 90, 120)
private val REMINDER_OFFSETS = listOf(0 to "At due time", 15 to "15 min before", 60 to "1 hour before", 24 * 60 to "1 day before")

@Composable
fun TaskEditorScreen(
    args: TaskEditorArgs,
    onClose: () -> Unit,
    onStartFocus: (String) -> Unit,
    viewModel: TaskEditorViewModel = koinViewModel(key = "task-${args.taskId}-${args.date}") { parametersOf(args) },
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(state.closed) { if (state.closed) onClose() }
    // Leaving with unsaved edits asks first instead of discarding them silently.
    val requestClose = unsavedChangesGuard(hasChanges = !state.closed && rememberHasChanges(ready = !state.loading, current = state.form), onLeave = onClose)
    var showDate by remember { mutableStateOf(false) }
    var showTime by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    val titleFocus = remember { FocusRequester() }
    LaunchedEffect(state.loading) { if (!state.loading && state.isNew) runCatching { titleFocus.requestFocus() } }

    Scaffold(
        topBar = {
            BloomTopBar(
                title = if (state.isNew) "New task" else "Edit task",
                onBack = requestClose,
                actions = {
                    TextButton(onClick = viewModel::save, enabled = !state.saving && !state.loading) { Text("Save") }
                    val existing = state.existing
                    if (existing != null) {
                        IconButton(onClick = { menu = true }) { Icon(Icons.Outlined.MoreVert, "More actions") }
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            DropdownMenuItem(text = { Text("Start focus session") }, onClick = { menu = false; onStartFocus(existing.id) })
                            DropdownMenuItem(text = { Text("Move to tomorrow") }, onClick = { menu = false; viewModel.moveToTomorrow() })
                            DropdownMenuItem(text = { Text("Duplicate") }, onClick = { menu = false; viewModel.duplicate() })
                            if (existing.status != TaskStatus.CANCELLED) {
                                DropdownMenuItem(text = { Text("No longer needed") }, onClick = { menu = false; viewModel.markNotNeeded() })
                            }
                            DropdownMenuItem(text = { Text("Delete") }, onClick = { menu = false; confirmDelete = true })
                        }
                    }
                },
            )
        },
    ) { padding ->
        when {
            state.loading -> LoadingState(Modifier.padding(padding))
            state.notFound -> EmptyState("🍂", "Task not found", "It may have been deleted.", Modifier.padding(padding), "Go back", onClose)
            else -> {
                val form = state.form
                Column(
                    Modifier.fillMaxSize().padding(padding).imePadding().verticalScroll(rememberScrollState()).padding(horizontal = Spacing.screenGutter),
                    verticalArrangement = Arrangement.spacedBy(Spacing.md),
                ) {
                    state.existing?.let { existing ->
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                            FilledTonalButton(onClick = viewModel::toggleComplete) {
                                Text(if (existing.status == TaskStatus.COMPLETED) "Mark as not done" else "Mark complete")
                            }
                            if (existing.status == TaskStatus.CANCELLED) Text("No longer needed", style = MaterialTheme.typography.labelMedium)
                        }
                    }
                    BloomTextField(
                        value = form.title,
                        onValueChange = { v -> viewModel.update { it.copy(title = v.take(200)) } },
                        label = { Text("Title") },
                        isError = "title" in state.errors,
                        supportingText = state.errors["title"]?.let { { Text(it) } },
                        modifier = Modifier.fillMaxWidth().focusRequester(titleFocus),
                        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                        singleLine = true,
                    )
                    SectionHeader("When")
                    Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                        AssistChip(onClick = { showDate = true }, label = { Text(form.dueDate?.let(Format::shortDate) ?: "No date") })
                        AssistChip(onClick = { showTime = true }, label = { Text(form.dueTime?.let(Format::time) ?: "Any time") })
                    }
                    ChoiceChips(RepeatChoice.entries.filter { it != RepeatChoice.CUSTOM || form.customRule != null }, form.repeat, { if (it == RepeatChoice.CUSTOM) form.customRule?.describe() ?: it.label else it.label }, { c -> viewModel.update { it.copy(repeat = c) } })
                    if (form.repeat == RepeatChoice.WEEKLY) {
                        MultiChoiceChips(DayOfWeek.entries, form.weeklyDays.ifEmpty { setOfNotNull(form.dueDate?.dayOfWeek) }, { it.shortName() }, { d ->
                            viewModel.update { f -> val days = f.weeklyDays.ifEmpty { setOfNotNull(f.dueDate?.dayOfWeek) }; f.copy(weeklyDays = if (d in days && days.size > 1) days - d else days + d) }
                        })
                    }
                    state.errors["recurrence"]?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }

                    SectionHeader("Remind me")
                    MultiChoiceChips(REMINDER_OFFSETS.map { it.first }, form.reminderOffsets, { o -> REMINDER_OFFSETS.first { it.first == o }.second }, { o ->
                        viewModel.update { f -> f.copy(reminderOffsets = if (o in f.reminderOffsets) f.reminderOffsets - o else f.reminderOffsets + o) }
                    })
                    state.errors["reminder"]?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                    if (form.reminderOffsets.isNotEmpty() && form.dueTime == null) {
                        Text("No time set, so reminders count back from 9:00 AM.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }

                    SectionHeader("Priority")
                    ChoiceChips(Priority.entries, form.priority, { it.label }, { p -> viewModel.update { it.copy(priority = p) } })
                    SectionHeader("Category")
                    ChoiceChips(TaskCategory.entries, form.category, { "${it.emoji} ${it.label}" }, { c -> viewModel.update { it.copy(category = c) } })
                    SectionHeader("Estimated time")
                    ChoiceChips(listOf<Int?>(null) + ESTIMATES, form.estimatedMinutes, { it?.let(Format::compactMinutes) ?: "None" }, { m -> viewModel.update { it.copy(estimatedMinutes = m) } })
                    state.errors["estimate"]?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    if (state.existing?.status == TaskStatus.COMPLETED) {
                        BloomTextField(
                            value = form.actualMinutes?.toString().orEmpty(),
                            onValueChange = { v -> viewModel.update { it.copy(actualMinutes = v.filter(Char::isDigit).take(4).toIntOrNull()) } },
                            label = { Text("Actual minutes (optional)") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }

                    SectionHeader("Steps")
                    form.subtasks.forEach { sub ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(checked = sub.isDone, onCheckedChange = { viewModel.toggleSubtask(sub.id) })
                            BloomTextField(
                                value = sub.title,
                                onValueChange = { viewModel.editSubtask(sub.id, it) },
                                placeholder = { Text("Step") },
                                singleLine = true,
                                modifier = Modifier.weight(1f),
                            )
                            IconButton(onClick = { viewModel.removeSubtask(sub.id) }) { Icon(Icons.Filled.Close, "Remove step") }
                        }
                    }
                    TextButton(onClick = viewModel::addSubtask) {
                        Icon(Icons.Filled.Add, null)
                        Text("Add step")
                    }

                    SectionHeader("Details")
                    BloomTextField(
                        value = form.description,
                        onValueChange = { v -> viewModel.update { it.copy(description = v) } },
                        label = { Text("Description") },
                        modifier = Modifier.fillMaxWidth(),
                        minLines = 2,
                    )
                    BloomTextField(
                        value = form.notes,
                        onValueChange = { v -> viewModel.update { it.copy(notes = v) } },
                        label = { Text("Notes") },
                        modifier = Modifier.fillMaxWidth(),
                        minLines = 2,
                    )
                    BloomTextField(
                        value = form.tagsText,
                        onValueChange = { v -> viewModel.update { it.copy(tagsText = v.take(200)) } },
                        label = { Text("Tags (comma separated)") },
                        isError = "tags" in state.errors,
                        supportingText = state.errors["tags"]?.let { { Text(it) } },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    SwitchRow(
                        "Flexible", form.isFlexible, { v -> viewModel.update { it.copy(isFlexible = v) } },
                        subtitle = "Can be suggested for moving to another day",
                    )
                    HorizontalDivider()
                    Button(colors = BloomButtons.primary(), onClick = viewModel::save, enabled = !state.saving, modifier = Modifier.fillMaxWidth()) {
                        Text(if (state.isNew) "Add task" else "Save changes")
                    }
                    Spacer(Modifier.height(Spacing.xl))
                }
                if (showDate) {
                    BloomDatePickerDialog(form.dueDate, onDismiss = { showDate = false }, allowClear = true, onPicked = { d ->
                        showDate = false
                        viewModel.update { it.copy(dueDate = d) }
                    })
                }
                if (showTime) {
                    BloomTimePickerDialog(form.dueTime, onDismiss = { showTime = false }, allowClear = true, onPicked = { t ->
                        showTime = false
                        viewModel.update { it.copy(dueTime = t) }
                    })
                }
                if (confirmDelete) {
                    AlertDialog(
                        onDismissRequest = { confirmDelete = false },
                        title = { Text("Delete this task?") },
                        text = { Text("If it just isn't needed any more, \"No longer needed\" keeps your history tidy instead.") },
                        confirmButton = { TextButton(onClick = { confirmDelete = false; viewModel.delete() }) { Text("Delete") } },
                        dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Keep") } },
                    )
                }
            }
        }
    }
}
