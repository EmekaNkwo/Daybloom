package com.daybloom.features.appointments

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.daybloom.domain.models.AppointmentOccurrence
import com.daybloom.domain.models.Task
import com.daybloom.domain.models.TaskStatus
import com.daybloom.domain.repositories.AppointmentRepository
import com.daybloom.domain.repositories.PlanRepository
import com.daybloom.domain.repositories.TaskRepository
import com.daybloom.domain.services.AppointmentScheduleService
import com.daybloom.domain.services.DateMath
import com.daybloom.domain.usecases.CompleteTaskUseCase
import com.daybloom.domain.usecases.ReopenTaskUseCase
import com.daybloom.features.common.AppMessages
import com.daybloom.features.common.runAction
import com.daybloom.features.common.todayFlow
import com.daybloom.platform.PlatformDateTimeService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.datetime.LocalDate

enum class CalendarMode(val label: String) { AGENDA("Agenda"), DAY("Day"), WEEK("Week"), MONTH("Month") }

data class CalendarDay(
    val date: LocalDate,
    val appointments: List<AppointmentOccurrence>,
    val tasks: List<Task>,
    val isRestDay: Boolean,
) {
    val isEmpty: Boolean get() = appointments.isEmpty() && tasks.isEmpty()
}

data class CalendarState(
    val loading: Boolean = true,
    val today: LocalDate? = null,
    val mode: CalendarMode = CalendarMode.AGENDA,
    val selected: LocalDate? = null,
    /** Days of the visible range (agenda: 14 days; day: 1; week: 7; month: whole month). */
    val days: List<CalendarDay> = emptyList(),
) {
    val selectedDay: CalendarDay? get() = days.firstOrNull { it.date == selected }
}

class CalendarViewModel(
    private val appointments: AppointmentRepository,
    private val tasks: TaskRepository,
    private val plans: PlanRepository,
    private val completeTask: CompleteTaskUseCase,
    private val reopenTask: ReopenTaskUseCase,
    private val messages: AppMessages,
    clock: PlatformDateTimeService,
) : ViewModel() {
    private val mode = MutableStateFlow(CalendarMode.AGENDA)
    private val selected = MutableStateFlow<LocalDate?>(null)

    val state: StateFlow<CalendarState> = combine(clock.todayFlow(), mode, selected) { today, m, sel -> Triple(today, m, sel ?: today) }
        .flatMapLatest { (today, m, sel) ->
            val (from, to) = range(m, sel)
            combine(
                appointments.observeAll(),
                appointments.observeOccurrenceStatuses(),
                tasks.observeDueBetween(from, to),
                plans.observeBetween(from, to),
            ) { appts, statuses, dueTasks, rangePlans ->
                val occurrences = AppointmentScheduleService.occurrencesBetween(appts, statuses, from, to, includeCancelled = true)
                val rest = rangePlans.filter { it.isRestDay }.map { it.date }.toSet()
                val days = DateMath.datesBetween(from, to).map { d ->
                    CalendarDay(
                        date = d,
                        appointments = occurrences.filter { it.date == d },
                        tasks = dueTasks.filter { it.dueDate == d && it.status != TaskStatus.CANCELLED }.sortedBy { it.status == TaskStatus.COMPLETED },
                        isRestDay = d in rest,
                    )
                }
                CalendarState(loading = false, today = today, mode = m, selected = sel, days = days)
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), CalendarState())

    fun setMode(m: CalendarMode) = mode.update { m }
    fun select(date: LocalDate) = selected.update { date }

    fun shift(forward: Boolean) {
        val current = state.value.selected ?: return
        val step = if (forward) 1 else -1
        selected.value = when (mode.value) {
            CalendarMode.DAY, CalendarMode.AGENDA -> DateMath.plusDays(current, step * if (mode.value == CalendarMode.AGENDA) 14 else 1)
            CalendarMode.WEEK -> DateMath.plusDays(current, step * 7)
            CalendarMode.MONTH -> DateMath.plusMonths(current, step)
        }
    }

    fun goToToday() = selected.update { null }

    fun toggleTask(task: Task, done: Boolean) = runAction(messages) {
        if (done) completeTask(task.id) else reopenTask(task.id)
    }

    private fun range(m: CalendarMode, sel: LocalDate): Pair<LocalDate, LocalDate> = when (m) {
        CalendarMode.AGENDA -> sel to DateMath.plusDays(sel, 13)
        CalendarMode.DAY -> sel to sel
        CalendarMode.WEEK -> DateMath.startOfWeek(sel).let { it to DateMath.plusDays(it, 6) }
        CalendarMode.MONTH -> DateMath.firstOfMonth(sel) to DateMath.lastOfMonth(sel)
    }
}
