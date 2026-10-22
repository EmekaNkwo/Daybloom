package com.daybloom.domain.models

import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime
import kotlin.time.Instant

data class Subtask(
    val id: String,
    val title: String,
    val isDone: Boolean = false,
    val order: Int = 0,
)

data class Task(
    val id: String,
    val title: String,
    val description: String = "",
    val category: TaskCategory = TaskCategory.PERSONAL,
    val priority: Priority = Priority.NORMAL,
    val dueDate: LocalDate? = null,
    val dueTime: LocalTime? = null,
    val estimatedMinutes: Int? = null,
    val actualMinutes: Int? = null,
    val status: TaskStatus = TaskStatus.OPEN,
    val recurrence: RecurrenceRule? = null,
    /** All occurrences of a recurring task share a series id; for one-off tasks it equals [id]. */
    val seriesId: String = id,
    val subtasks: List<Subtask> = emptyList(),
    val notes: String = "",
    val tags: List<String> = emptyList(),
    /** Flexible tasks may be suggested for moving to another day; fixed ones never are. */
    val isFlexible: Boolean = true,
    /** Preparation task for an appointment. */
    val appointmentId: String? = null,
    /** How many times the due date has been pushed later. Drives the "split it up?" suggestion. */
    val postponeCount: Int = 0,
    val createdAt: Instant,
    val updatedAt: Instant = createdAt,
    val completedAt: Instant? = null,
    /** Local calendar date of completion, fixed at the moment of completion in the user's zone. */
    val completedOn: LocalDate? = null,
    val cancelledAt: Instant? = null,
) {
    val isOpen: Boolean get() = status == TaskStatus.OPEN
    val isCompleted: Boolean get() = status == TaskStatus.COMPLETED
    val isCancelled: Boolean get() = status == TaskStatus.CANCELLED
    val subtaskProgress: Pair<Int, Int> get() = subtasks.count { it.isDone } to subtasks.size
}

data class TaskFilter(
    val query: String = "",
    val categories: Set<TaskCategory> = emptySet(),
    val priorities: Set<Priority> = emptySet(),
    val statuses: Set<TaskStatus> = setOf(TaskStatus.OPEN),
    val date: LocalDate? = null,
) {
    fun matches(task: Task): Boolean {
        if (statuses.isNotEmpty() && task.status !in statuses) return false
        if (categories.isNotEmpty() && task.category !in categories) return false
        if (priorities.isNotEmpty() && task.priority !in priorities) return false
        if (date != null && task.dueDate != date) return false
        val q = query.trim()
        if (q.isNotEmpty()) {
            val haystack = listOf(task.title, task.description, task.notes, task.tags.joinToString(" "))
            if (haystack.none { it.contains(q, ignoreCase = true) }) return false
        }
        return true
    }
}
