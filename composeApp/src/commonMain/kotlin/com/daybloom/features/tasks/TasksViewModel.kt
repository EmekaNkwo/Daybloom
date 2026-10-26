package com.daybloom.features.tasks

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.daybloom.domain.models.Priority
import com.daybloom.domain.models.Task
import com.daybloom.domain.models.TaskCategory
import com.daybloom.domain.models.TaskFilter
import com.daybloom.domain.models.TaskStatus
import com.daybloom.domain.repositories.TaskRepository
import com.daybloom.domain.usecases.CompleteTaskUseCase
import com.daybloom.domain.usecases.ReopenTaskUseCase
import com.daybloom.features.common.AppMessage
import com.daybloom.features.common.AppMessages
import com.daybloom.features.common.runAction
import com.daybloom.features.common.todayFlow
import com.daybloom.platform.PlatformDateTimeService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.datetime.LocalDate

enum class StatusFilter(val label: String, val statuses: Set<TaskStatus>) {
    OPEN("Open", setOf(TaskStatus.OPEN)),
    DONE("Done", setOf(TaskStatus.COMPLETED)),
    CANCELLED("Not needed", setOf(TaskStatus.CANCELLED)),
    ALL("All", emptySet()),
}

enum class DateFilter(val label: String) { ANY("Any date"), TODAY("Today"), UPCOMING("Upcoming"), OVERDUE("Overdue"), UNDATED("No date") }

data class TaskSection(val title: String, val tasks: List<Task>)

data class TasksState(
    val loading: Boolean = true,
    val today: LocalDate? = null,
    val query: String = "",
    val status: StatusFilter = StatusFilter.OPEN,
    val dateFilter: DateFilter = DateFilter.ANY,
    val categories: Set<TaskCategory> = emptySet(),
    val priorities: Set<Priority> = emptySet(),
    val sections: List<TaskSection> = emptyList(),
    val totalMatches: Int = 0,
) {
    val hasFilters: Boolean get() = query.isNotBlank() || categories.isNotEmpty() || priorities.isNotEmpty() || dateFilter != DateFilter.ANY
}

private data class Filters(
    val status: StatusFilter = StatusFilter.OPEN,
    val date: DateFilter = DateFilter.ANY,
    val categories: Set<TaskCategory> = emptySet(),
    val priorities: Set<Priority> = emptySet(),
)

class TasksViewModel(
    tasks: TaskRepository,
    private val completeTask: CompleteTaskUseCase,
    private val reopenTask: ReopenTaskUseCase,
    private val messages: AppMessages,
    clock: PlatformDateTimeService,
) : ViewModel() {
    private val filters = MutableStateFlow(Filters())

    /**
     * The search text is snapshot state, not part of [state]: the field must update in the same frame as each keystroke.
     * Routing it through the filtered-list pipeline made the field lag behind fast typing and drop or reorder characters.
     */
    var query by mutableStateOf("")
        private set

    val state: StateFlow<TasksState> = combine(tasks.observeAll(), filters, snapshotFlow { query }, clock.todayFlow()) { all, f, q, today ->
        val base = TaskFilter(query = q, categories = f.categories, priorities = f.priorities, statuses = f.status.statuses)
        val matches = all.filter(base::matches).filter { matchesDate(it, f.date, today) }
        TasksState(
            loading = false,
            today = today,
            query = q,
            status = f.status,
            dateFilter = f.date,
            categories = f.categories,
            priorities = f.priorities,
            sections = group(matches, today, f.status),
            totalMatches = matches.size,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), TasksState())

    fun onQueryChange(q: String) {
        query = q.take(100)
    }
    fun setStatus(s: StatusFilter) = filters.update { it.copy(status = s) }
    fun setDateFilter(d: DateFilter) = filters.update { it.copy(date = d) }
    fun toggleCategory(c: TaskCategory) = filters.update { it.copy(categories = it.categories.toggle(c)) }
    fun togglePriority(p: Priority) = filters.update { it.copy(priorities = it.priorities.toggle(p)) }
    fun clearFilters() {
        query = ""
        filters.update { Filters(status = it.status) }
    }

    fun toggle(task: Task, done: Boolean) = runAction(messages, "Couldn't update that task.") {
        if (done) {
            val outcome = completeTask(task.id) ?: return@runAction
            val xpText = if (outcome.xpAwarded > 0) " · +${outcome.xpAwarded} XP" else ""
            messages.post(AppMessage("“${task.title}” done$xpText", "Undo") { reopenTask(task.id) })
        } else {
            reopenTask(task.id)
        }
    }

    private fun matchesDate(task: Task, filter: DateFilter, today: LocalDate): Boolean = when (filter) {
        DateFilter.ANY -> true
        DateFilter.TODAY -> task.dueDate == today
        DateFilter.UPCOMING -> task.dueDate != null && task.dueDate > today
        DateFilter.OVERDUE -> task.isOpen && task.dueDate != null && task.dueDate < today
        DateFilter.UNDATED -> task.dueDate == null
    }

    private fun group(tasks: List<Task>, today: LocalDate, status: StatusFilter): List<TaskSection> {
        if (status == StatusFilter.DONE) {
            return listOf(TaskSection("Completed", tasks.sortedByDescending { it.completedAt }))
        }
        val byPriority = compareBy<Task>({ it.priority.rank }, { it.dueTime?.toSecondOfDay() ?: Int.MAX_VALUE })
        val overdue = tasks.filter { it.isOpen && it.dueDate != null && it.dueDate < today }.sortedWith(compareBy<Task> { it.dueDate }.then(byPriority))
        val dueToday = tasks.filter { it.dueDate == today }.sortedWith(byPriority)
        val upcoming = tasks.filter { it.dueDate != null && it.dueDate > today }.sortedWith(compareBy<Task> { it.dueDate }.then(byPriority))
        val undated = tasks.filter { it.dueDate == null }.sortedWith(byPriority)
        val past = tasks.filter { !it.isOpen && it.dueDate != null && it.dueDate < today }.sortedByDescending { it.dueDate }
        return listOf(
            TaskSection("Overdue", overdue),
            TaskSection("Today", dueToday),
            TaskSection("Upcoming", upcoming),
            TaskSection("Someday", undated),
            TaskSection("Earlier", past),
        ).filter { it.tasks.isNotEmpty() }
    }

    private fun <T> Set<T>.toggle(item: T): Set<T> = if (item in this) this - item else this + item
}
