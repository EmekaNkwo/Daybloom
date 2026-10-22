package com.daybloom.domain.services

import com.daybloom.domain.models.AppointmentOccurrence
import com.daybloom.domain.models.AppointmentStatus
import com.daybloom.domain.models.Task
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime

sealed interface SuggestionAction {
    data class PlanDay(val date: LocalDate) : SuggestionAction
    data class OpenTask(val taskId: String) : SuggestionAction
    data class StartFocus(val minutes: Int) : SuggestionAction
    data class SetPlanningReminder(val time: LocalTime) : SuggestionAction
    data class MoveUnfinished(val taskIds: List<String>, val to: LocalDate) : SuggestionAction
}

data class Suggestion(
    /** Stable key used for dismiss/snooze; includes the facts it's based on so new facts can resurface it. */
    val key: String,
    val title: String,
    val message: String,
    val actionLabel: String,
    val action: SuggestionAction,
)

data class SuggestionInput(
    val now: LocalDateTime,
    val openTasks: List<Task>,
    val todaysAppointments: List<AppointmentOccurrence>,
    val tomorrowHasPlan: Boolean,
    /** Local times at which recent daily plans (last 14 days) were first created. */
    val recentPlanCreationTimes: List<LocalTime>,
    val hasPlanningReminder: Boolean,
    val dismissedKeys: Set<String>,
    /** Only when the unfinished-task policy is "ask" — never moves anything on its own. */
    val offerMoveUnfinished: Boolean = true,
)

/**
 * Deterministic suggestions derived only from the user's own records. Each rule states the facts it needs and
 * stays silent without them — it never invents free time or patterns.
 */
object SuggestionEngine {
    const val TASKS_DUE_TOMORROW_THRESHOLD = 3
    const val POSTPONE_THRESHOLD = 3
    const val MIN_PLANS_FOR_PATTERN = 5
    const val MIN_GAP_MINUTES = 15
    const val MAX_GAP_MINUTES = 120
    const val EVENING_HOUR = 18
    const val AFTERNOON_HOUR = 15

    fun suggest(input: SuggestionInput): List<Suggestion> = listOfNotNull(
        upcomingGap(input),
        planTomorrow(input),
        moveUnfinished(input),
    ).plus(splitPostponed(input)).plus(listOfNotNull(planningReminder(input)))
        .filter { it.key !in input.dismissedKeys }

    private fun planTomorrow(input: SuggestionInput): Suggestion? {
        if (input.tomorrowHasPlan || input.now.hour < AFTERNOON_HOUR) return null
        val tomorrow = DateMath.plusDays(input.now.date, 1)
        val due = input.openTasks.count { it.dueDate == tomorrow }
        if (due < TASKS_DUE_TOMORROW_THRESHOLD) return null
        return Suggestion(
            key = "plan-tomorrow:$tomorrow",
            title = "Tomorrow is filling up",
            message = "You have $due tasks due tomorrow. Plan your day?",
            actionLabel = "Plan tomorrow",
            action = SuggestionAction.PlanDay(tomorrow),
        )
    }

    private fun splitPostponed(input: SuggestionInput): List<Suggestion> =
        input.openTasks
            .filter { it.postponeCount >= POSTPONE_THRESHOLD && it.subtasks.isEmpty() }
            .sortedByDescending { it.postponeCount }
            .take(1)
            .map { task ->
                Suggestion(
                    key = "split:${task.id}:${task.postponeCount}",
                    title = "Make it smaller?",
                    message = "\"${task.title}\" has been postponed ${task.postponeCount} times. Split it into smaller tasks?",
                    actionLabel = "Split task",
                    action = SuggestionAction.OpenTask(task.id),
                )
            }

    private fun upcomingGap(input: SuggestionInput): Suggestion? {
        val nowMinutes = input.now.time.toSecondOfDay() / 60
        val upcoming = input.todaysAppointments
            .filter { it.status == AppointmentStatus.SCHEDULED && it.date == input.now.date }
            .map { it to it.appointment.startTime.toSecondOfDay() / 60 }
            .filter { (_, start) -> start > nowMinutes }
            .minByOrNull { it.second } ?: return null
        // Only a real gap: no appointment is in progress right now.
        val busyNow = input.todaysAppointments.any {
            val s = it.appointment.startTime.toSecondOfDay() / 60
            val e = it.appointment.endTime.toSecondOfDay() / 60
            it.status == AppointmentStatus.SCHEDULED && nowMinutes in s until e
        }
        if (busyNow) return null
        val gap = upcoming.second - nowMinutes
        if (gap !in MIN_GAP_MINUTES..MAX_GAP_MINUTES) return null
        val focusMinutes = listOf(60, 45, 25, 15).first { it <= gap - 5 || it == 15 }
        return Suggestion(
            key = "gap:${upcoming.first.key}",
            title = "A window before ${upcoming.first.appointment.title}",
            message = "You have $gap minutes before ${upcoming.first.appointment.title}.",
            actionLabel = "Focus $focusMinutes min",
            action = SuggestionAction.StartFocus(focusMinutes),
        )
    }

    private fun planningReminder(input: SuggestionInput): Suggestion? {
        if (input.hasPlanningReminder) return null
        val times = input.recentPlanCreationTimes
        if (times.size < MIN_PLANS_FOR_PATTERN) return null
        val morning = times.filter { it.hour < 11 }
        if (morning.size * 10 < times.size * 7) return null // at least 70% in the morning
        val typicalMinute = morning.map { it.toSecondOfDay() / 60 }.sorted()[morning.size / 2]
        val reminderMinute = ((typicalMinute - 15) / 15 * 15).coerceAtLeast(6 * 60)
        val time = LocalTime(reminderMinute / 60, reminderMinute % 60)
        return Suggestion(
            key = "planning-reminder",
            title = "You plan in the mornings",
            message = "You've planned ${morning.size} of your last ${times.size} days in the morning. Schedule a planning reminder at ${formatTime(time)}?",
            actionLabel = "Add reminder",
            action = SuggestionAction.SetPlanningReminder(time),
        )
    }

    private fun moveUnfinished(input: SuggestionInput): Suggestion? {
        if (!input.offerMoveUnfinished || input.now.hour < EVENING_HOUR) return null
        val today = input.now.date
        val unfinished = input.openTasks.filter { it.dueDate == today && it.isFlexible && it.recurrence == null }
        if (unfinished.isEmpty()) return null
        val tomorrow = DateMath.plusDays(today, 1)
        val n = unfinished.size
        return Suggestion(
            key = "move:$today",
            title = "Wrap up today",
            message = "Would you like to move $n unfinished flexible task${if (n == 1) "" else "s"} to tomorrow?",
            actionLabel = "Move to tomorrow",
            action = SuggestionAction.MoveUnfinished(unfinished.map { it.id }, tomorrow),
        )
    }

    fun formatTime(time: LocalTime): String {
        val h = time.hour % 12 == 0
        val hour = if (h) 12 else time.hour % 12
        val suffix = if (time.hour < 12) "AM" else "PM"
        return if (time.minute == 0) "$hour $suffix" else "$hour:${time.minute.toString().padStart(2, '0')} $suffix"
    }
}
