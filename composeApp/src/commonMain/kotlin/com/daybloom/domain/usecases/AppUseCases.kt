package com.daybloom.domain.usecases

import com.daybloom.domain.models.AppointmentKind
import com.daybloom.domain.models.LevelProgress
import com.daybloom.domain.models.Priority
import com.daybloom.domain.models.RecurrenceRule
import com.daybloom.domain.models.Subtask
import com.daybloom.domain.models.TaskCategory
import com.daybloom.domain.models.UnfinishedTaskPolicy
import com.daybloom.domain.repositories.DataResetRepository
import com.daybloom.domain.repositories.TaskRepository
import com.daybloom.domain.repositories.PreferencesRepository
import com.daybloom.domain.repositories.XpRepository
import com.daybloom.domain.services.DateMath
import com.daybloom.gamification.LevelSystem
import com.daybloom.notifications.ReconcileReason
import com.daybloom.notifications.ReminderReconciler
import com.daybloom.platform.PlatformDateTimeService
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalTime

/**
 * Runs on launch and whenever the app returns to the foreground: restores focus sessions, re-syncs the OS
 * notification schedule with the database, and refreshes today's score and quests.
 */
class AppStartupUseCase(
    private val reconciler: ReminderReconciler,
    private val focus: FocusSessionUseCases,
    private val process: ProcessProgressUseCase,
    private val applyPolicy: ApplyUnfinishedTaskPolicyUseCase,
) {
    suspend operator fun invoke(reason: ReconcileReason = ReconcileReason.APP_START) {
        focus.reconcileActive()
        applyPolicy()
        reconciler.reconcile(reason)
        process()
    }
}

/**
 * Applies the user's configured policy for unfinished tasks. With the default (ASK) nothing moves on its own;
 * the user is offered a suggestion instead. Idempotent: moved tasks are no longer overdue.
 */
class ApplyUnfinishedTaskPolicyUseCase(
    private val tasks: TaskRepository,
    private val preferences: PreferencesRepository,
    private val reschedule: RescheduleTaskUseCase,
    private val clock: PlatformDateTimeService,
) {
    suspend operator fun invoke(): Int {
        if (preferences.current().unfinishedTaskPolicy != UnfinishedTaskPolicy.MOVE_FLEXIBLE_TO_TOMORROW) return 0
        val today = clock.today()
        val movable = tasks.getOpen().filter { t ->
            val due = t.dueDate
            due != null && due < today && t.isFlexible && t.recurrence == null && t.appointmentId == null
        }
        if (movable.isEmpty()) return 0
        return reschedule(movable.map { it.id }, today).size
    }
}

/** User-controlled reset: wipes all records, clears scheduled notifications and restores default preferences. */
class ResetAllDataUseCase(
    private val data: DataResetRepository,
    private val preferences: PreferencesRepository,
    private val reconciler: ReminderReconciler,
) {
    suspend operator fun invoke(keepPreferences: Boolean = false) {
        data.deleteAllData()
        if (!keepPreferences) preferences.reset()
        // With no reminders left, reconciliation cancels every DayBloom notification the OS still holds.
        reconciler.reconcile(ReconcileReason.REMINDER_CHANGED)
    }
}

/** Detects level-ups that haven't been celebrated yet. The celebration is shown once per level. */
class LevelUpUseCase(
    private val xp: XpRepository,
    private val preferences: PreferencesRepository,
) {
    suspend fun pendingCelebration(): LevelProgress? {
        val progress = LevelSystem.progressFor(xp.total())
        val last = preferences.current().lastCelebratedLevel
        return progress.takeIf { it.level.number > last }
    }

    suspend fun markCelebrated(level: Int) {
        preferences.update { it.copy(lastCelebratedLevel = maxOf(it.lastCelebratedLevel, level)) }
    }
}

/** Populates a realistic sample day so the app can be explored (and tested) without manual entry. */
class SeedDemoDataUseCase(
    private val createTask: CreateTaskUseCase,
    private val saveAppointment: SaveAppointmentUseCase,
    private val saveHabit: SaveHabitUseCase,
    private val preferences: PreferencesRepository,
    private val clock: PlatformDateTimeService,
) {
    suspend operator fun invoke() {
        val today = clock.today()
        val tomorrow = DateMath.plusDays(today, 1)
        listOf(
            TaskDraft("Review project proposal", category = TaskCategory.WORK, priority = Priority.HIGH, dueDate = today, estimatedMinutes = 45),
            TaskDraft("Pay electricity bill", category = TaskCategory.FINANCE, priority = Priority.URGENT, dueDate = today, estimatedMinutes = 10, isFlexible = false),
            TaskDraft(
                "Prepare presentation", category = TaskCategory.WORK, priority = Priority.HIGH, dueDate = today, estimatedMinutes = 90,
                subtasks = listOf(Subtask("", "Outline"), Subtask("", "Slides"), Subtask("", "Rehearse")),
            ),
            TaskDraft("Read for 20 minutes", category = TaskCategory.LEARNING, dueDate = today, dueTime = LocalTime(20, 0), estimatedMinutes = 20),
            TaskDraft("Call Mum", category = TaskCategory.FAMILY, dueDate = tomorrow),
            TaskDraft("Water the plants", category = TaskCategory.ERRANDS, priority = Priority.LOW, dueDate = today, recurrence = RecurrenceRule.weekly(DayOfWeek.SATURDAY, DayOfWeek.WEDNESDAY)),
        ).forEach { createTask(it) }

        saveAppointment(
            AppointmentDraft(
                title = "Project meeting",
                kind = AppointmentKind.MEETING,
                date = today,
                startTime = LocalTime(15, 30),
                endTime = LocalTime(16, 15),
                location = "Room 4B",
                reminderOffsetsMinutes = listOf(15),
                newPreparationTasks = listOf("Skim last week's notes"),
            ),
        )
        listOf(
            HabitDraft("Read", "📖", weeklyTarget = 5, category = TaskCategory.LEARNING),
            HabitDraft("Move your body", "🚶", weeklyTarget = 4),
            HabitDraft("Evening review", "🌙", scheduledDays = RecurrenceRule.WEEKDAYS, weeklyTarget = 4, category = TaskCategory.PERSONAL),
        ).forEach { saveHabit(it) }
        preferences.update { it.copy(onboardingComplete = true) }
    }
}
