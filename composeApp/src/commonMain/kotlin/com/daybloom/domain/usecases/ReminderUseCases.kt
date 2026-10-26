package com.daybloom.domain.usecases

import com.daybloom.domain.models.Appointment
import com.daybloom.domain.models.AppointmentStatus
import com.daybloom.domain.models.Habit
import com.daybloom.domain.models.NotificationCategory
import com.daybloom.domain.models.RecurrenceFrequency
import com.daybloom.domain.models.RecurrenceRule
import com.daybloom.domain.models.Reminder
import com.daybloom.domain.models.ReminderSource
import com.daybloom.domain.models.ReminderStatus
import com.daybloom.domain.models.Task
import com.daybloom.domain.models.TaskStatus
import com.daybloom.domain.repositories.ReminderRepository
import com.daybloom.domain.repositories.TaskRepository
import com.daybloom.domain.services.DateMath
import com.daybloom.domain.services.SuggestionEngine
import com.daybloom.domain.services.Validation
import com.daybloom.notifications.ReconcileReason
import com.daybloom.notifications.ReminderReconciler
import com.daybloom.platform.IdGenerator
import com.daybloom.platform.PlatformDateTimeService
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.isoDayNumber
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.toInstant

/**
 * Keeps reminders that belong to tasks, appointments, habits and the planning routine in step with their
 * owners. Derived reminders use deterministic ids so regenerating them never creates duplicates.
 */
class SyncSourceRemindersUseCase(
    private val reminders: ReminderRepository,
    private val reconciler: ReminderReconciler,
    private val clock: PlatformDateTimeService,
) {
    suspend fun forTask(task: Task, offsetsMinutes: List<Int>) {
        reminders.deleteForSource(ReminderSource.TASK, task.seriesId)
        val due = task.dueDate
        if (due != null && task.status == TaskStatus.OPEN) {
            val base = task.dueTime ?: DEFAULT_TASK_TIME
            offsetsMinutes.distinct().filter { it >= 0 }.forEach { offset ->
                val (date, time) = shift(due, base, offset)
                reminders.save(
                    Reminder(
                        id = taskReminderId(task.seriesId, offset),
                        title = task.title,
                        body = describeOffset(offset, task.dueTime),
                        source = ReminderSource.TASK,
                        sourceId = task.seriesId,
                        category = NotificationCategory.TASKS,
                        startDate = date,
                        times = listOf(time),
                        recurrence = task.recurrence?.let { shiftRule(it, (date.toEpochDays() - due.toEpochDays()).toInt()) },
                        createdAt = clock.now(),
                    ),
                )
            }
        }
        reconcile()
    }

    suspend fun taskOffsets(task: Task): List<Int> =
        reminders.getForSource(ReminderSource.TASK, task.seriesId)
            .mapNotNull { it.id.substringAfterLast(':').toIntOrNull() }
            .sorted()

    suspend fun onTaskCompleted(task: Task) = updateTaskReminders(task) { r ->
        if (task.recurrence == null) r.copy(status = ReminderStatus.COMPLETED)
        else r.copy(acknowledgedThrough = endOfDay(task.dueDate))
    }

    suspend fun onTaskReopened(task: Task) = updateTaskReminders(task) { r ->
        if (task.recurrence == null) r.copy(status = ReminderStatus.ACTIVE)
        else r.copy(acknowledgedThrough = task.dueDate?.let { DateMath.startOfDay(it, zone()) })
    }

    suspend fun onTaskCancelled(task: Task) = updateTaskReminders(task) { r ->
        if (task.recurrence == null) r.copy(status = ReminderStatus.DISMISSED)
        else r.copy(acknowledgedThrough = endOfDay(task.dueDate))
    }

    suspend fun onTaskDeleted(task: Task) {
        if (task.recurrence == null) {
            reminders.deleteForSource(ReminderSource.TASK, task.seriesId)
            reconcile()
        } else {
            onTaskCancelled(task)
        }
    }

    suspend fun onTaskRescheduled(old: Task, updated: Task) {
        if (old.dueDate == updated.dueDate && old.dueTime == updated.dueTime) return
        forTask(updated, taskOffsets(old))
    }

    suspend fun forAppointment(appointment: Appointment) {
        reminders.deleteForSource(ReminderSource.APPOINTMENT, appointment.id)
        if (appointment.status != AppointmentStatus.CANCELLED) {
            appointment.reminderOffsetsMinutes.distinct().filter { it >= 0 }.forEach { offset ->
                val (date, time) = shift(appointment.date, appointment.startTime, offset)
                val where = appointment.location.takeIf { it.isNotBlank() }?.let { " · $it" }.orEmpty()
                reminders.save(
                    Reminder(
                        id = "appt:${appointment.id}:$offset",
                        title = appointment.title,
                        body = "Starts at ${SuggestionEngine.formatTime(appointment.startTime)}$where",
                        source = ReminderSource.APPOINTMENT,
                        sourceId = appointment.id,
                        category = NotificationCategory.APPOINTMENTS,
                        startDate = date,
                        times = listOf(time),
                        recurrence = appointment.recurrence?.let { shiftRule(it, (date.toEpochDays() - appointment.date.toEpochDays()).toInt()) },
                        timeZoneId = appointment.timeZoneId,
                        // An early appointment's reminder must not be pushed past its start by quiet hours.
                        respectQuietHours = false,
                        createdAt = clock.now(),
                    ),
                )
            }
        }
        reconcile()
    }

    suspend fun deleteForAppointment(appointmentId: String) {
        reminders.deleteForSource(ReminderSource.APPOINTMENT, appointmentId)
        reconcile()
    }

    suspend fun forHabit(habit: Habit) {
        reminders.deleteForSource(ReminderSource.HABIT, habit.id)
        val time = habit.reminderTime
        if (time != null && !habit.isPaused && !habit.archived) {
            reminders.save(
                Reminder(
                    id = "habit:${habit.id}",
                    title = "${habit.emoji} ${habit.title}",
                    body = "A gentle nudge for your habit.",
                    source = ReminderSource.HABIT,
                    sourceId = habit.id,
                    category = NotificationCategory.HABITS,
                    startDate = clock.today(),
                    times = listOf(time),
                    recurrence = RecurrenceRule(RecurrenceFrequency.WEEKLY, daysOfWeek = habit.scheduledDays),
                    createdAt = clock.now(),
                ),
            )
        }
        reconcile()
    }

    /** Called when a habit is checked off so today's nudge doesn't arrive after the fact. */
    suspend fun acknowledgeHabitToday(habitId: String, done: Boolean) {
        val r = reminders.get("habit:$habitId") ?: return
        val today = clock.today()
        val ack = if (done) endOfDay(today) else DateMath.startOfDay(today, zone())
        reminders.save(r.copy(acknowledgedThrough = ack, updatedAt = clock.now()))
        reconcile()
    }

    suspend fun setPlanningReminder(time: LocalTime?) {
        reminders.delete(PLANNING_REMINDER_ID)
        if (time != null) {
            reminders.save(
                Reminder(
                    id = PLANNING_REMINDER_ID,
                    title = "Plan your day 🌤️",
                    body = "Pick your top priorities in a minute or two.",
                    source = ReminderSource.CUSTOM,
                    sourceId = PLANNING_REMINDER_ID,
                    category = NotificationCategory.GENERAL,
                    startDate = clock.today(),
                    times = listOf(time),
                    recurrence = RecurrenceRule.daily(),
                    createdAt = clock.now(),
                ),
            )
        }
        reconcile()
    }

    private suspend fun updateTaskReminders(task: Task, transform: (Reminder) -> Reminder) {
        reminders.getForSource(ReminderSource.TASK, task.seriesId)
            .forEach { reminders.save(transform(it).copy(updatedAt = clock.now())) }
        // Always reconcile: even a task without its own reminders changes the daily digests.
        reconcile()
    }

    /** Re-derives the daily task digests after a task change that touches no reminder of its own. */
    suspend fun refreshTaskDigests() = reconcile()

    private suspend fun reconcile() {
        reconciler.reconcile(ReconcileReason.REMINDER_CHANGED)
    }

    private fun zone(): TimeZone = clock.timeZone()

    private fun endOfDay(date: LocalDate?): Instant? = date?.let { DateMath.endOfDayExclusive(it, zone()) }

    companion object {
        val DEFAULT_TASK_TIME = LocalTime(9, 0)
        const val PLANNING_REMINDER_ID = "planning"

        fun taskReminderId(seriesId: String, offset: Int) = "task:$seriesId:$offset"

        /** [time] on [date] minus [offsetMinutes], crossing midnight as needed. Pure local arithmetic. */
        fun shift(date: LocalDate, time: LocalTime, offsetMinutes: Int): Pair<LocalDate, LocalTime> {
            val total = time.toSecondOfDay() / 60 - offsetMinutes
            val dayShift = floorDiv(total, MINUTES_PER_DAY)
            val minuteOfDay = total - dayShift * MINUTES_PER_DAY
            return DateMath.plusDays(date, dayShift) to LocalTime(minuteOfDay / 60, minuteOfDay % 60)
        }

        /** Shifts weekly weekdays when a reminder lands on a different day than its owner. */
        fun shiftRule(rule: RecurrenceRule, dayShift: Int): RecurrenceRule {
            if (dayShift == 0 || rule.daysOfWeek.isEmpty()) return rule
            val shifted = rule.daysOfWeek.map { day ->
                val index = Math.floorMod(day.isoDayNumber - 1 + dayShift, 7)
                DayOfWeek.entries.first { it.isoDayNumber == index + 1 }
            }.toSet()
            return rule.copy(daysOfWeek = shifted, until = rule.until?.let { DateMath.plusDays(it, dayShift) })
        }

        fun describeOffset(offset: Int, dueTime: LocalTime?): String = when {
            offset == 0 && dueTime != null -> "Due now"
            offset == 0 -> "Due today"
            offset < 60 -> "Due in $offset minutes"
            offset % (24 * 60) == 0 -> "Due in ${offset / (24 * 60)} day${if (offset == 24 * 60) "" else "s"}"
            offset % 60 == 0 -> "Due in ${offset / 60} hour${if (offset == 60) "" else "s"}"
            else -> "Due in ${offset / 60} h ${offset % 60} min"
        }

        private const val MINUTES_PER_DAY = 24 * 60
        private fun floorDiv(a: Int, b: Int): Int = Math.floorDiv(a, b)
    }
}

private object Math {
    fun floorDiv(a: Int, b: Int): Int {
        val q = a / b
        return if ((a % b != 0) && ((a < 0) != (b < 0))) q - 1 else q
    }
    fun floorMod(a: Int, b: Int): Int = ((a % b) + b) % b
}

data class ReminderDraft(
    val title: String,
    val body: String = "",
    val source: ReminderSource = ReminderSource.CUSTOM,
    val category: NotificationCategory = NotificationCategory.GENERAL,
    val startDate: LocalDate,
    val times: List<LocalTime>,
    val recurrence: RecurrenceRule? = null,
    val expiresOn: LocalDate? = null,
    val respectQuietHours: Boolean = true,
    /** Pin to a time zone (e.g. a bill due in another country); null follows the device. */
    val timeZoneId: String? = null,
) {
    fun errors(): Map<String, String> = buildMap {
        Validation.title(title)?.let { put("title", it) }
        if (times.isEmpty()) put("times", "Add at least one time")
        if (expiresOn != null && expiresOn < startDate) put("expiresOn", "Expiry must be after the start date")
        if (timeZoneId != null && runCatching { TimeZone.of(timeZoneId) }.isFailure) put("timeZone", "Unknown time zone")
    }
}

/** ScheduleReminderUseCase: creates or updates a standalone reminder and syncs the OS schedule. */
class ScheduleReminderUseCase(
    private val reminders: ReminderRepository,
    private val reconciler: ReminderReconciler,
    private val ids: IdGenerator,
    private val clock: PlatformDateTimeService,
) {
    sealed interface Result {
        data class Saved(val reminder: Reminder) : Result
        data class Invalid(val errors: Map<String, String>) : Result
    }

    suspend operator fun invoke(draft: ReminderDraft, existingId: String? = null): Result {
        val errors = draft.errors()
        if (errors.isNotEmpty()) return Result.Invalid(errors)
        val existing = existingId?.let { reminders.get(it) }
        val now = clock.now()
        if (isOneTimeInThePast(draft, existing, now)) {
            return Result.Invalid(mapOf("times" to "That time has already passed. Pick a later time or date."))
        }
        val reminder = Reminder(
            id = existing?.id ?: ids.newId(),
            title = draft.title.trim(),
            body = draft.body.trim(),
            source = draft.source,
            sourceId = existing?.sourceId,
            category = draft.category,
            startDate = draft.startDate,
            times = draft.times.distinct().sorted(),
            recurrence = draft.recurrence,
            timeZoneId = draft.timeZoneId,
            expiresOn = draft.expiresOn,
            respectQuietHours = draft.respectQuietHours,
            status = ReminderStatus.ACTIVE,
            snoozedUntil = null,
            acknowledgedThrough = null,
            createdAt = existing?.createdAt ?: now,
            updatedAt = now,
        )
        reminders.save(reminder)
        reconciler.reconcile(ReconcileReason.REMINDER_CHANGED)
        return Result.Saved(reminder)
    }

    /**
     * A one-time reminder whose every time is already past would never fire. Only checked when the schedule is
     * new or changed, so notes on an old reminder can still be edited.
     */
    private fun isOneTimeInThePast(draft: ReminderDraft, existing: Reminder?, now: Instant): Boolean {
        if (draft.recurrence != null) return false
        val sameSchedule = existing != null && existing.startDate == draft.startDate &&
            existing.times == draft.times.distinct().sorted() && existing.recurrence == null &&
            existing.timeZoneId == draft.timeZoneId
        if (sameSchedule) return false
        val zone = draft.timeZoneId?.let { TimeZone.of(it) } ?: clock.timeZone()
        return draft.times.all { LocalDateTime(draft.startDate, it).toInstant(zone) <= now }
    }
}

/** RescheduleReminderUseCase: moves a reminder to a new date/time, clearing any snooze. */
class RescheduleReminderUseCase(
    private val reminders: ReminderRepository,
    private val reconciler: ReminderReconciler,
    private val clock: PlatformDateTimeService,
) {
    suspend operator fun invoke(reminderId: String, date: LocalDate, times: List<LocalTime>): Reminder? {
        if (times.isEmpty()) return null
        val r = reminders.get(reminderId) ?: return null
        val updated = r.copy(
            startDate = date,
            times = times.distinct().sorted(),
            snoozedUntil = null,
            acknowledgedThrough = null,
            status = ReminderStatus.ACTIVE,
            updatedAt = clock.now(),
        )
        reminders.save(updated)
        reconciler.reconcile(ReconcileReason.REMINDER_CHANGED)
        return updated
    }
}

enum class NotificationAction { DONE, SNOOZE, DISMISS, DELIVERED }

/**
 * Handles notification actions and in-app snooze/dismiss. Shared by Android receivers and the iOS
 * notification delegate so both platforms behave identically.
 */
class HandleReminderActionUseCase(
    private val reminders: ReminderRepository,
    private val tasks: TaskRepository,
    private val completeTask: CompleteTaskUseCase,
    private val toggleHabit: ToggleHabitUseCase,
    private val reconciler: ReminderReconciler,
    private val clock: PlatformDateTimeService,
) {
    suspend operator fun invoke(reminderId: String, firedAt: Instant?, action: NotificationAction, snoozeMinutes: Int = DEFAULT_SNOOZE_MINUTES) {
        val reminder = reminders.get(reminderId)
        if (reminder == null) {
            reconciler.reconcile(ReconcileReason.NOTIFICATION_HANDLED)
            return
        }
        val now = clock.now()
        val handledThrough = maxOf(firedAt ?: now, reminder.acknowledgedThrough ?: Instant.DISTANT_PAST)
        val isOneOff = reminder.recurrence == null && (firedAt == null || reminder.times.size <= 1)
        val updated = when (action) {
            NotificationAction.DELIVERED -> null
            NotificationAction.SNOOZE -> reminder.copy(
                snoozedUntil = now + snoozeMinutes.coerceIn(1, 24 * 60).minutes,
                acknowledgedThrough = handledThrough,
            )
            NotificationAction.DISMISS -> reminder.copy(
                acknowledgedThrough = handledThrough,
                snoozedUntil = null,
                status = if (isOneOff) ReminderStatus.DISMISSED else reminder.status,
            )
            NotificationAction.DONE -> {
                completeSource(reminder)
                reminders.get(reminderId)?.let { latest ->
                    latest.copy(
                        acknowledgedThrough = maxOf(handledThrough, latest.acknowledgedThrough ?: Instant.DISTANT_PAST),
                        snoozedUntil = null,
                        status = if (isOneOff) ReminderStatus.COMPLETED else latest.status,
                    )
                }
            }
        }
        updated?.let { reminders.save(it.copy(updatedAt = now)) }
        reconciler.reconcile(ReconcileReason.NOTIFICATION_HANDLED)
    }

    private suspend fun completeSource(reminder: Reminder) {
        val sourceId = reminder.sourceId ?: return
        when (reminder.source) {
            ReminderSource.TASK -> {
                val target = tasks.getOpen()
                    .filter { it.seriesId == sourceId }
                    .minByOrNull { it.dueDate ?: LocalDate(9999, 12, 31) }
                target?.let { completeTask(it.id) }
            }
            ReminderSource.HABIT -> toggleHabit(sourceId, clock.today(), done = true)
            else -> Unit
        }
    }

    companion object {
        const val DEFAULT_SNOOZE_MINUTES = 10
    }
}

class DeleteReminderUseCase(
    private val reminders: ReminderRepository,
    private val reconciler: ReminderReconciler,
) {
    suspend operator fun invoke(reminderId: String) {
        reminders.delete(reminderId)
        reconciler.reconcile(ReconcileReason.REMINDER_CHANGED)
    }

    /** Undoes a delete by saving the captured reminder back and rescheduling it. */
    suspend fun restore(reminder: Reminder) {
        reminders.save(reminder)
        reconciler.reconcile(ReconcileReason.REMINDER_CHANGED)
    }
}
