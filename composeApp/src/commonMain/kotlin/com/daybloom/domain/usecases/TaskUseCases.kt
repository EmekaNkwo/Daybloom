package com.daybloom.domain.usecases

import com.daybloom.domain.models.Priority
import com.daybloom.domain.models.RecurrenceRule
import com.daybloom.domain.models.Subtask
import com.daybloom.domain.models.Task
import com.daybloom.domain.models.TaskCategory
import com.daybloom.domain.models.TaskStatus
import com.daybloom.domain.models.XpReason
import com.daybloom.domain.repositories.PlanRepository
import com.daybloom.domain.repositories.TaskRepository
import com.daybloom.domain.services.RecurrenceCalculator
import com.daybloom.domain.services.Validation
import com.daybloom.domain.services.ValidationResult
import com.daybloom.gamification.RewardConfig
import com.daybloom.platform.IdGenerator
import com.daybloom.platform.PlatformDateTimeService
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime

/** Everything the task form can edit. */
data class TaskDraft(
    val title: String,
    val description: String = "",
    val category: TaskCategory = TaskCategory.PERSONAL,
    val priority: Priority = Priority.NORMAL,
    val dueDate: LocalDate? = null,
    val dueTime: LocalTime? = null,
    val estimatedMinutes: Int? = null,
    val actualMinutes: Int? = null,
    val recurrence: RecurrenceRule? = null,
    val subtasks: List<Subtask> = emptyList(),
    val notes: String = "",
    val tags: List<String> = emptyList(),
    val isFlexible: Boolean = true,
    val appointmentId: String? = null,
    /** Minutes before the due time to remind; empty for no reminder. Requires a due date. */
    val reminderOffsetsMinutes: List<Int> = emptyList(),
) {
    fun validate(): ValidationResult = Validation.combine(
        "title" to Validation.title(title),
        "description" to Validation.longText(description, "Description"),
        "notes" to Validation.longText(notes, "Notes"),
        "estimate" to Validation.estimate(estimatedMinutes),
        "tags" to Validation.tags(tags),
        "reminder" to if (reminderOffsetsMinutes.isNotEmpty() && dueDate == null) "Add a due date to set a reminder" else null,
        "recurrence" to if (recurrence != null && dueDate == null) "Repeating tasks need a start date" else null,
    )
}

sealed interface TaskResult {
    data class Success(val task: Task) : TaskResult
    data class Invalid(val errors: Map<String, String>) : TaskResult
    data object NotFound : TaskResult
}

data class TaskCompletionOutcome(
    val task: Task,
    val xpAwarded: Int,
    val progress: ProgressUpdate,
    val nextOccurrence: Task?,
)

/** CreateTaskUseCase */
class CreateTaskUseCase(
    private val tasks: TaskRepository,
    private val reminderSync: SyncSourceRemindersUseCase,
    private val process: ProcessProgressUseCase,
    private val ids: IdGenerator,
    private val clock: PlatformDateTimeService,
) {
    suspend operator fun invoke(draft: TaskDraft): TaskResult {
        val validation = draft.validate()
        if (validation is ValidationResult.Invalid) return TaskResult.Invalid(validation.errors)
        val now = clock.now()
        val id = ids.newId()
        val task = Task(
            id = id,
            title = draft.title.trim(),
            description = draft.description.trim(),
            category = draft.category,
            priority = draft.priority,
            dueDate = draft.dueDate,
            dueTime = draft.dueTime,
            estimatedMinutes = draft.estimatedMinutes,
            actualMinutes = draft.actualMinutes,
            recurrence = draft.recurrence,
            seriesId = id,
            subtasks = draft.subtasks.mapIndexed { i, s -> s.copy(id = s.id.ifBlank { ids.newId() }, order = i) },
            notes = draft.notes.trim(),
            tags = draft.tags.map { it.trim().lowercase() }.filter { it.isNotEmpty() }.distinct(),
            isFlexible = draft.isFlexible,
            appointmentId = draft.appointmentId,
            createdAt = now,
        )
        tasks.save(task)
        reminderSync.forTask(task, draft.reminderOffsetsMinutes)
        process(task.dueDate)
        return TaskResult.Success(task)
    }
}

/** Edits an existing task's fields. Status changes go through the dedicated use cases. */
class UpdateTaskUseCase(
    private val tasks: TaskRepository,
    private val reminderSync: SyncSourceRemindersUseCase,
    private val process: ProcessProgressUseCase,
    private val ids: IdGenerator,
    private val clock: PlatformDateTimeService,
) {
    suspend operator fun invoke(taskId: String, draft: TaskDraft): TaskResult {
        val validation = draft.validate()
        if (validation is ValidationResult.Invalid) return TaskResult.Invalid(validation.errors)
        val existing = tasks.get(taskId) ?: return TaskResult.NotFound
        val postponed = existing.dueDate != null && draft.dueDate != null && draft.dueDate > existing.dueDate
        val updated = existing.copy(
            title = draft.title.trim(),
            description = draft.description.trim(),
            category = draft.category,
            priority = draft.priority,
            dueDate = draft.dueDate,
            dueTime = draft.dueTime,
            estimatedMinutes = draft.estimatedMinutes,
            actualMinutes = draft.actualMinutes,
            recurrence = draft.recurrence,
            subtasks = draft.subtasks.mapIndexed { i, s -> s.copy(id = s.id.ifBlank { ids.newId() }, order = i) },
            notes = draft.notes.trim(),
            tags = draft.tags.map { it.trim().lowercase() }.filter { it.isNotEmpty() }.distinct(),
            isFlexible = draft.isFlexible,
            appointmentId = draft.appointmentId,
            postponeCount = existing.postponeCount + if (postponed) 1 else 0,
            updatedAt = clock.now(),
        )
        tasks.save(updated)
        reminderSync.forTask(updated, draft.reminderOffsetsMinutes)
        process(existing.dueDate, updated.dueDate, updated.completedOn)
        return TaskResult.Success(updated)
    }
}

/**
 * CompleteTaskUseCase: completion, score, quests, achievements, XP and recurrence are processed together.
 * Completing an already-completed task is a no-op, so double taps never double-award.
 */
class CompleteTaskUseCase(
    private val tasks: TaskRepository,
    private val plans: PlanRepository,
    private val awardXp: AwardExperiencePointsUseCase,
    private val reminderSync: SyncSourceRemindersUseCase,
    private val process: ProcessProgressUseCase,
    private val ids: IdGenerator,
    private val clock: PlatformDateTimeService,
) {
    suspend operator fun invoke(taskId: String): TaskCompletionOutcome? {
        val task = tasks.get(taskId) ?: return null
        if (task.status == TaskStatus.COMPLETED) return null
        val now = clock.now()
        val today = clock.today()
        val completed = task.copy(
            status = TaskStatus.COMPLETED,
            completedAt = now,
            completedOn = today,
            cancelledAt = null,
            subtasks = task.subtasks,
            updatedAt = now,
        )
        tasks.save(completed)

        val isTopPriority = listOfNotNull(today, task.dueDate).distinct()
            .any { date -> plans.get(date)?.topPriorityTaskIds?.contains(task.id) == true }
        val reason = if (isTopPriority) XpReason.TOP_PRIORITY_COMPLETED else XpReason.TASK_COMPLETED
        val xp = awardXp.award(reason, RewardConfig.Keys.task(task.id), task.id, today)

        val next = createNextOccurrence(completed)
        reminderSync.onTaskCompleted(completed)
        val progress = process(task.dueDate, today)
        return TaskCompletionOutcome(completed, xp, progress, next)
    }

    private suspend fun createNextOccurrence(task: Task): Task? {
        val rule = task.recurrence ?: return null
        val due = task.dueDate ?: return null
        // Anchor on the series' first occurrence so month-end clamping (31st → 28th) never drifts.
        // The count is tracked per occurrence below, so it's excluded from the anchored expansion.
        val seriesStart = tasks.get(task.seriesId)?.dueDate?.takeIf { it <= due } ?: due
        val nextDate = RecurrenceCalculator.nextAfter(rule.copy(count = null), seriesStart, due) ?: return null
        if (tasks.existsInSeriesOn(task.seriesId, nextDate)) return null
        val remainingCount = rule.count?.let { it - 1 }
        if (remainingCount != null && remainingCount < 1) return null
        val now = clock.now()
        val next = task.copy(
            id = ids.newId(),
            dueDate = nextDate,
            status = TaskStatus.OPEN,
            recurrence = if (remainingCount != null) rule.copy(count = remainingCount) else rule,
            subtasks = task.subtasks.map { it.copy(id = ids.newId(), isDone = false) },
            actualMinutes = null,
            postponeCount = 0,
            createdAt = now,
            updatedAt = now,
            completedAt = null,
            completedOn = null,
            cancelledAt = null,
        )
        tasks.save(next)
        return next
    }
}

/**
 * ReopenTaskUseCase (also "undo completion"). Reward policy: the completion XP is revoked; completing again
 * restores it under the same event key, so toggling never gains XP.
 */
class ReopenTaskUseCase(
    private val tasks: TaskRepository,
    private val awardXp: AwardExperiencePointsUseCase,
    private val reminderSync: SyncSourceRemindersUseCase,
    private val process: ProcessProgressUseCase,
    private val clock: PlatformDateTimeService,
) {
    suspend operator fun invoke(taskId: String): Task? {
        val task = tasks.get(taskId) ?: return null
        if (task.status == TaskStatus.OPEN) return task
        val reopened = task.copy(status = TaskStatus.OPEN, completedAt = null, completedOn = null, cancelledAt = null, updatedAt = clock.now())
        tasks.save(reopened)
        awardXp.revoke(RewardConfig.Keys.task(task.id))
        reminderSync.onTaskReopened(reopened)
        process(task.completedOn, task.dueDate)
        return reopened
    }
}

/** Marks a task as no longer relevant. Cancelled tasks don't count against any score. */
class CancelTaskUseCase(
    private val tasks: TaskRepository,
    private val awardXp: AwardExperiencePointsUseCase,
    private val reminderSync: SyncSourceRemindersUseCase,
    private val process: ProcessProgressUseCase,
    private val clock: PlatformDateTimeService,
) {
    suspend operator fun invoke(taskId: String): Task? {
        val task = tasks.get(taskId) ?: return null
        val now = clock.now()
        val cancelled = task.copy(status = TaskStatus.CANCELLED, cancelledAt = now, completedAt = null, completedOn = null, updatedAt = now)
        tasks.save(cancelled)
        if (task.status == TaskStatus.COMPLETED) awardXp.revoke(RewardConfig.Keys.task(task.id))
        reminderSync.onTaskCancelled(cancelled)
        process(task.dueDate, task.completedOn)
        return cancelled
    }
}

class DeleteTaskUseCase(
    private val tasks: TaskRepository,
    private val awardXp: AwardExperiencePointsUseCase,
    private val reminderSync: SyncSourceRemindersUseCase,
    private val process: ProcessProgressUseCase,
) {
    suspend operator fun invoke(taskId: String): Task? {
        val task = tasks.get(taskId) ?: return null
        tasks.delete(taskId)
        if (task.status == TaskStatus.COMPLETED) awardXp.revoke(RewardConfig.Keys.task(task.id))
        reminderSync.onTaskDeleted(task)
        process(task.dueDate, task.completedOn)
        return task
    }
}

class DuplicateTaskUseCase(
    private val tasks: TaskRepository,
    private val process: ProcessProgressUseCase,
    private val ids: IdGenerator,
    private val clock: PlatformDateTimeService,
) {
    suspend operator fun invoke(taskId: String): Task? {
        val task = tasks.get(taskId) ?: return null
        val now = clock.now()
        val id = ids.newId()
        val copy = task.copy(
            id = id,
            seriesId = id,
            title = task.title,
            status = TaskStatus.OPEN,
            subtasks = task.subtasks.map { it.copy(id = ids.newId(), isDone = false) },
            postponeCount = 0,
            actualMinutes = null,
            createdAt = now,
            updatedAt = now,
            completedAt = null,
            completedOn = null,
            cancelledAt = null,
        )
        tasks.save(copy)
        process(copy.dueDate)
        return copy
    }
}

/** Moves one or more tasks to a new date. Moving later counts as a postponement (for gentle suggestions only). */
class RescheduleTaskUseCase(
    private val tasks: TaskRepository,
    private val reminderSync: SyncSourceRemindersUseCase,
    private val process: ProcessProgressUseCase,
    private val clock: PlatformDateTimeService,
) {
    suspend operator fun invoke(taskIds: List<String>, newDate: LocalDate?, newTime: LocalTime? = null, keepTime: Boolean = true): List<Task> {
        val affected = mutableListOf<LocalDate?>()
        val moved = taskIds.mapNotNull { id ->
            val task = tasks.get(id) ?: return@mapNotNull null
            val postponed = task.dueDate != null && newDate != null && newDate > task.dueDate
            val updated = task.copy(
                dueDate = newDate,
                dueTime = if (keepTime) newTime ?: task.dueTime else newTime,
                postponeCount = task.postponeCount + if (postponed) 1 else 0,
                updatedAt = clock.now(),
            )
            tasks.save(updated)
            reminderSync.onTaskRescheduled(task, updated)
            affected += task.dueDate
            updated
        }
        affected += newDate
        process(*affected.toTypedArray())
        return moved
    }
}

class ToggleSubtaskUseCase(
    private val tasks: TaskRepository,
    private val clock: PlatformDateTimeService,
) {
    suspend operator fun invoke(taskId: String, subtaskId: String): Task? {
        val task = tasks.get(taskId) ?: return null
        val updated = task.copy(
            subtasks = task.subtasks.map { if (it.id == subtaskId) it.copy(isDone = !it.isDone) else it },
            updatedAt = clock.now(),
        )
        tasks.save(updated)
        return updated
    }
}
