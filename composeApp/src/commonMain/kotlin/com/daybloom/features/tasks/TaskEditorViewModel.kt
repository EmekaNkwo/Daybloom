package com.daybloom.features.tasks

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.daybloom.domain.models.Priority
import com.daybloom.domain.models.RecurrenceFrequency
import com.daybloom.domain.models.RecurrenceRule
import com.daybloom.domain.models.Subtask
import com.daybloom.domain.models.Task
import com.daybloom.domain.models.TaskCategory
import com.daybloom.domain.models.TaskStatus
import com.daybloom.domain.repositories.TaskRepository
import com.daybloom.domain.services.DateMath
import com.daybloom.domain.usecases.CancelTaskUseCase
import com.daybloom.domain.usecases.CompleteTaskUseCase
import com.daybloom.domain.usecases.CreateTaskUseCase
import com.daybloom.domain.usecases.DeleteTaskUseCase
import com.daybloom.domain.usecases.DuplicateTaskUseCase
import com.daybloom.domain.usecases.ReopenTaskUseCase
import com.daybloom.domain.usecases.RescheduleTaskUseCase
import com.daybloom.domain.usecases.SyncSourceRemindersUseCase
import com.daybloom.domain.usecases.TaskDraft
import com.daybloom.domain.usecases.TaskResult
import com.daybloom.domain.usecases.UpdateTaskUseCase
import com.daybloom.features.common.AppMessage
import com.daybloom.features.common.AppMessages
import com.daybloom.features.common.DraftHandoff
import com.daybloom.features.common.runAction
import com.daybloom.platform.IdGenerator
import com.daybloom.platform.PlatformDateTimeService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime

data class TaskEditorArgs(val taskId: String?, val date: String?, val appointmentId: String?, val prefill: Boolean)

enum class RepeatChoice(val label: String) {
    NONE("Doesn't repeat"), DAILY("Daily"), WEEKDAYS("Weekdays"), WEEKLY("Weekly"), MONTHLY("Monthly"), YEARLY("Yearly"), CUSTOM("Custom")
}

data class TaskForm(
    val title: String = "",
    val description: String = "",
    val category: TaskCategory = TaskCategory.PERSONAL,
    val priority: Priority = Priority.NORMAL,
    val dueDate: LocalDate? = null,
    val dueTime: LocalTime? = null,
    val estimatedMinutes: Int? = null,
    val actualMinutes: Int? = null,
    val repeat: RepeatChoice = RepeatChoice.NONE,
    val weeklyDays: Set<DayOfWeek> = emptySet(),
    val customRule: RecurrenceRule? = null,
    val subtasks: List<Subtask> = emptyList(),
    val notes: String = "",
    val tagsText: String = "",
    val isFlexible: Boolean = true,
    val reminderOffsets: Set<Int> = emptySet(),
    val appointmentId: String? = null,
) {
    fun recurrence(): RecurrenceRule? = when (repeat) {
        RepeatChoice.NONE -> null
        RepeatChoice.DAILY -> RecurrenceRule.daily()
        RepeatChoice.WEEKDAYS -> RecurrenceRule(RecurrenceFrequency.WEEKLY, daysOfWeek = RecurrenceRule.WEEKDAYS)
        RepeatChoice.WEEKLY -> RecurrenceRule(RecurrenceFrequency.WEEKLY, daysOfWeek = weeklyDays.ifEmpty { setOfNotNull(dueDate?.dayOfWeek) })
        RepeatChoice.MONTHLY -> RecurrenceRule.monthly()
        RepeatChoice.YEARLY -> RecurrenceRule(RecurrenceFrequency.YEARLY)
        RepeatChoice.CUSTOM -> customRule
    }

    fun toDraft() = TaskDraft(
        title = title,
        description = description,
        category = category,
        priority = priority,
        dueDate = dueDate,
        dueTime = dueTime,
        estimatedMinutes = estimatedMinutes,
        actualMinutes = actualMinutes,
        recurrence = recurrence(),
        subtasks = subtasks.filter { it.title.isNotBlank() },
        notes = notes,
        tags = tagsText.split(',', ' ', '#').map { it.trim() }.filter { it.isNotEmpty() },
        isFlexible = isFlexible,
        appointmentId = appointmentId,
        reminderOffsetsMinutes = reminderOffsets.sorted(),
    )

    companion object {
        fun from(draft: TaskDraft) = TaskForm(
            title = draft.title,
            description = draft.description,
            category = draft.category,
            priority = draft.priority,
            dueDate = draft.dueDate,
            dueTime = draft.dueTime,
            estimatedMinutes = draft.estimatedMinutes,
            actualMinutes = draft.actualMinutes,
            repeat = repeatFor(draft.recurrence),
            weeklyDays = draft.recurrence?.daysOfWeek.orEmpty(),
            customRule = draft.recurrence,
            subtasks = draft.subtasks,
            notes = draft.notes,
            tagsText = draft.tags.joinToString(", "),
            isFlexible = draft.isFlexible,
            reminderOffsets = draft.reminderOffsetsMinutes.toSet(),
            appointmentId = draft.appointmentId,
        )

        fun repeatFor(rule: RecurrenceRule?): RepeatChoice = when {
            rule == null -> RepeatChoice.NONE
            rule.interval != 1 || rule.until != null || rule.count != null -> RepeatChoice.CUSTOM
            rule.frequency == RecurrenceFrequency.DAILY -> RepeatChoice.DAILY
            rule.frequency == RecurrenceFrequency.WEEKLY && rule.daysOfWeek == RecurrenceRule.WEEKDAYS -> RepeatChoice.WEEKDAYS
            rule.frequency == RecurrenceFrequency.WEEKLY -> RepeatChoice.WEEKLY
            rule.frequency == RecurrenceFrequency.MONTHLY -> RepeatChoice.MONTHLY
            else -> RepeatChoice.YEARLY
        }
    }
}

data class TaskEditorState(
    val loading: Boolean = true,
    val isNew: Boolean = true,
    val existing: Task? = null,
    val form: TaskForm = TaskForm(),
    val errors: Map<String, String> = emptyMap(),
    val saving: Boolean = false,
    val closed: Boolean = false,
    val notFound: Boolean = false,
)

class TaskEditorViewModel(
    private val args: TaskEditorArgs,
    private val tasks: TaskRepository,
    private val createTask: CreateTaskUseCase,
    private val updateTask: UpdateTaskUseCase,
    private val completeTask: CompleteTaskUseCase,
    private val reopenTask: ReopenTaskUseCase,
    private val cancelTask: CancelTaskUseCase,
    private val deleteTask: DeleteTaskUseCase,
    private val duplicateTask: DuplicateTaskUseCase,
    private val reschedule: RescheduleTaskUseCase,
    private val reminderSync: SyncSourceRemindersUseCase,
    private val handoff: DraftHandoff,
    private val ids: IdGenerator,
    private val messages: AppMessages,
    private val clock: PlatformDateTimeService,
) : ViewModel() {
    private val _state = MutableStateFlow(TaskEditorState())
    val state: StateFlow<TaskEditorState> = _state.asStateFlow()

    init {
        viewModelScope.launch { load() }
    }

    private suspend fun load() {
        val id = args.taskId
        if (id == null) {
            val prefilled = if (args.prefill) handoff.takeTask() else null
            val form = prefilled?.let(TaskForm::from) ?: TaskForm(
                dueDate = args.date?.let { runCatching { LocalDate.parse(it) }.getOrNull() } ?: clock.today(),
                appointmentId = args.appointmentId,
            )
            _state.value = TaskEditorState(loading = false, isNew = true, form = form)
            return
        }
        val task = tasks.get(id)
        if (task == null) {
            _state.value = TaskEditorState(loading = false, notFound = true)
            return
        }
        val form = TaskForm.from(
            TaskDraft(
                title = task.title, description = task.description, category = task.category, priority = task.priority,
                dueDate = task.dueDate, dueTime = task.dueTime, estimatedMinutes = task.estimatedMinutes, actualMinutes = task.actualMinutes,
                recurrence = task.recurrence, subtasks = task.subtasks, notes = task.notes, tags = task.tags, isFlexible = task.isFlexible,
                appointmentId = task.appointmentId, reminderOffsetsMinutes = reminderSync.taskOffsets(task),
            ),
        )
        _state.value = TaskEditorState(loading = false, isNew = false, existing = task, form = form)
    }

    fun update(transform: (TaskForm) -> TaskForm) = _state.update { it.copy(form = transform(it.form), errors = emptyMap()) }

    fun addSubtask() = update { it.copy(subtasks = it.subtasks + Subtask(ids.newId(), "", order = it.subtasks.size)) }
    fun editSubtask(id: String, title: String) = update { f -> f.copy(subtasks = f.subtasks.map { if (it.id == id) it.copy(title = title.take(200)) else it }) }
    fun toggleSubtask(id: String) = update { f -> f.copy(subtasks = f.subtasks.map { if (it.id == id) it.copy(isDone = !it.isDone) else it }) }
    fun removeSubtask(id: String) = update { f -> f.copy(subtasks = f.subtasks.filterNot { it.id == id }) }

    fun save() {
        if (_state.value.saving) return
        _state.update { it.copy(saving = true) }
        runAction(messages, "Couldn't save the task.") {
            val draft = _state.value.form.toDraft()
            val existing = _state.value.existing
            val result = if (existing == null) createTask(draft) else updateTask(existing.id, draft)
            when (result) {
                is TaskResult.Success -> {
                    if (existing == null) messages.post("Task added")
                    _state.update { it.copy(saving = false, closed = true) }
                }
                is TaskResult.Invalid -> _state.update { it.copy(saving = false, errors = result.errors) }
                TaskResult.NotFound -> _state.update { it.copy(saving = false, notFound = true) }
            }
        }.invokeOnCompletion { _state.update { s -> s.copy(saving = false) } }
    }

    fun toggleComplete() = withExisting { task ->
        if (task.status == TaskStatus.COMPLETED) {
            reopenTask(task.id)
        } else {
            val outcome = completeTask(task.id)
            val xp = outcome?.xpAwarded ?: 0
            messages.post(AppMessage("“${task.title}” done${if (xp > 0) " · +$xp XP" else ""}", "Undo") { reopenTask(task.id) })
        }
        close()
    }

    fun markNotNeeded() = withExisting { task ->
        cancelTask(task.id)
        messages.post(AppMessage("Marked as no longer needed", "Undo") { reopenTask(task.id) })
        close()
    }

    fun duplicate() = withExisting { task ->
        duplicateTask(task.id)?.let { messages.post("Duplicated “${it.title}”") }
        close()
    }

    fun moveToTomorrow() = withExisting { task ->
        val tomorrow = DateMath.plusDays(clock.today(), 1)
        reschedule(listOf(task.id), tomorrow)
        messages.post("Moved to tomorrow")
        close()
    }

    fun delete() = withExisting { task ->
        deleteTask(task.id)
        messages.post(AppMessage("Task deleted", "Undo") {
            // Restore as a new copy with the same content; XP isn't restored for deleted completions.
            createTask(
                TaskDraft(
                    title = task.title, description = task.description, category = task.category, priority = task.priority,
                    dueDate = task.dueDate, dueTime = task.dueTime, estimatedMinutes = task.estimatedMinutes, recurrence = task.recurrence,
                    subtasks = task.subtasks.map { it.copy(id = "") }, notes = task.notes, tags = task.tags, isFlexible = task.isFlexible,
                    appointmentId = task.appointmentId,
                ),
            )
        })
        close()
    }

    private fun close() = _state.update { it.copy(closed = true) }

    private fun withExisting(block: suspend (Task) -> Unit) {
        val task = _state.value.existing ?: return
        runAction(messages, "Couldn't update the task.") { block(task) }
    }
}
