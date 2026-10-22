package com.daybloom.domain.usecases

import com.daybloom.domain.models.FocusSession
import com.daybloom.domain.models.FocusStatus
import com.daybloom.domain.models.Habit
import com.daybloom.domain.models.TaskCategory
import com.daybloom.domain.models.XpReason
import com.daybloom.domain.repositories.FocusRepository
import com.daybloom.domain.repositories.HabitRepository
import com.daybloom.domain.repositories.TaskRepository
import com.daybloom.domain.services.FocusTimerCalculator
import com.daybloom.domain.services.Validation
import com.daybloom.gamification.RewardConfig
import com.daybloom.platform.IdGenerator
import com.daybloom.platform.PlatformDateTimeService
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime

data class HabitDraft(
    val title: String,
    val emoji: String = "🌱",
    val scheduledDays: Set<DayOfWeek> = DayOfWeek.entries.toSet(),
    val weeklyTarget: Int = scheduledDays.size,
    val reminderTime: LocalTime? = null,
    val streaksEnabled: Boolean = true,
    val category: TaskCategory = TaskCategory.HEALTH,
) {
    fun errors(): Map<String, String> = buildMap {
        Validation.title(title)?.let { put("title", it) }
        if (scheduledDays.isEmpty()) put("days", "Pick at least one day")
        if (weeklyTarget !in 1..7) put("target", "Weekly target must be between 1 and 7")
    }
}

class SaveHabitUseCase(
    private val habits: HabitRepository,
    private val reminderSync: SyncSourceRemindersUseCase,
    private val process: ProcessProgressUseCase,
    private val ids: IdGenerator,
    private val clock: PlatformDateTimeService,
) {
    sealed interface Result {
        data class Saved(val habit: Habit) : Result
        data class Invalid(val errors: Map<String, String>) : Result
    }

    suspend operator fun invoke(draft: HabitDraft, existingId: String? = null): Result {
        val errors = draft.errors()
        if (errors.isNotEmpty()) return Result.Invalid(errors)
        val existing = existingId?.let { habits.get(it) }
        val habit = Habit(
            id = existing?.id ?: ids.newId(),
            title = draft.title.trim(),
            emoji = draft.emoji.ifBlank { "🌱" }.take(8),
            scheduledDays = draft.scheduledDays,
            weeklyTarget = draft.weeklyTarget.coerceAtMost(draft.scheduledDays.size),
            reminderTime = draft.reminderTime,
            streaksEnabled = draft.streaksEnabled,
            pausedSince = existing?.pausedSince,
            category = draft.category,
            createdAt = existing?.createdAt ?: clock.now(),
            archived = existing?.archived ?: false,
        )
        habits.save(habit)
        reminderSync.forHabit(habit)
        process(clock.today())
        return Result.Saved(habit)
    }
}

/** Checks a habit on or off for a date. XP follows the same revoke-on-undo policy as tasks. */
class ToggleHabitUseCase(
    private val habits: HabitRepository,
    private val awardXp: AwardExperiencePointsUseCase,
    private val reminderSync: SyncSourceRemindersUseCase,
    private val process: ProcessProgressUseCase,
    private val clock: PlatformDateTimeService,
) {
    suspend operator fun invoke(habitId: String, date: LocalDate, done: Boolean) {
        val habit = habits.get(habitId) ?: return
        if (date > clock.today()) return // can't complete the future
        habits.setCompleted(habit.id, date, done, clock.now())
        val key = RewardConfig.Keys.habit(habit.id, date.toString())
        if (done) awardXp.award(XpReason.HABIT_COMPLETED, key, habit.id, date) else awardXp.revoke(key)
        if (date == clock.today()) reminderSync.acknowledgeHabitToday(habit.id, done)
        process(date)
    }
}

/** Pause mode: a paused habit is unscheduled, so it never lowers scores or breaks streaks. */
class PauseHabitUseCase(
    private val habits: HabitRepository,
    private val reminderSync: SyncSourceRemindersUseCase,
    private val process: ProcessProgressUseCase,
    private val clock: PlatformDateTimeService,
) {
    suspend operator fun invoke(habitId: String, paused: Boolean) {
        val habit = habits.get(habitId) ?: return
        val updated = habit.copy(pausedSince = if (paused) clock.today() else null)
        habits.save(updated)
        reminderSync.forHabit(updated)
        process(clock.today())
    }
}

class ArchiveHabitUseCase(
    private val habits: HabitRepository,
    private val reminderSync: SyncSourceRemindersUseCase,
    private val process: ProcessProgressUseCase,
    private val clock: PlatformDateTimeService,
) {
    suspend operator fun invoke(habitId: String) = setArchived(habitId, true)

    /** Undoes an archive: the habit returns to the list with its history and reminder intact. */
    suspend fun restore(habitId: String) = setArchived(habitId, false)

    private suspend fun setArchived(habitId: String, archived: Boolean) {
        val habit = habits.get(habitId) ?: return
        val updated = habit.copy(archived = archived)
        habits.save(updated)
        reminderSync.forHabit(updated)
        process(clock.today())
    }
}

/** Starting, pausing, resuming and finishing focus sessions. Finishing never completes the linked task. */
class FocusSessionUseCases(
    private val focus: FocusRepository,
    private val tasks: TaskRepository,
    private val awardXp: AwardExperiencePointsUseCase,
    private val process: ProcessProgressUseCase,
    private val ids: IdGenerator,
    private val clock: PlatformDateTimeService,
) {
    suspend fun start(plannedMinutes: Int, taskId: String?): FocusSession {
        require(plannedMinutes in MIN_MINUTES..MAX_MINUTES) { "Focus sessions must be $MIN_MINUTES–$MAX_MINUTES minutes" }
        focus.getActive()?.let { finish(it.id, completed = false) }
        val task = taskId?.let { tasks.get(it) }
        val now = clock.now()
        val session = FocusSession(
            id = ids.newId(),
            taskId = task?.id,
            category = task?.category,
            plannedMinutes = plannedMinutes,
            date = clock.today(),
            startedAt = now,
            segmentStartedAt = now,
            segmentStartedMonotonicMs = clock.monotonicMillis(),
            status = FocusStatus.RUNNING,
        )
        focus.save(session)
        return session
    }

    suspend fun pause(sessionId: String): FocusSession? = update(sessionId) {
        FocusTimerCalculator.pause(it, clock.monotonicMillis(), clock.now())
    }

    suspend fun resume(sessionId: String): FocusSession? = update(sessionId) {
        FocusTimerCalculator.resume(it, clock.monotonicMillis(), clock.now())
    }

    /** [completed] = the timer ran out; false = the user stopped early (interrupted). */
    suspend fun finish(sessionId: String, completed: Boolean): FocusSession? {
        val finished = update(sessionId) { FocusTimerCalculator.finish(it, clock.monotonicMillis(), clock.now(), completed) }
            ?: return null
        val minutes = (finished.accumulatedSeconds / 60).toInt()
        if (finished.status == FocusStatus.COMPLETED && minutes >= RewardConfig.MIN_REWARDED_FOCUS_MINUTES) {
            awardXp.award(XpReason.FOCUS_SESSION, RewardConfig.Keys.focus(finished.id), finished.taskId, finished.date)
        }
        process(finished.date)
        return finished
    }

    /**
     * Called when the app returns to the foreground or restarts: a running session whose time ran out while
     * the app was away is completed; everything else is left as-is and keeps counting from its anchors.
     */
    suspend fun reconcileActive(): FocusSession? {
        val active = focus.getActive() ?: return null
        val now = clock.now()
        val mono = clock.monotonicMillis()
        return if (active.status == FocusStatus.RUNNING && FocusTimerCalculator.isFinished(active, mono, now)) {
            finish(active.id, completed = true)
        } else {
            active
        }
    }

    private suspend fun update(sessionId: String, transform: (FocusSession) -> FocusSession): FocusSession? {
        val session = focus.get(sessionId) ?: return null
        val updated = transform(session)
        if (updated != session) focus.save(updated)
        return updated
    }

    companion object {
        const val MIN_MINUTES = 1
        const val MAX_MINUTES = 180
        val PRESETS = listOf(15, 25, 45, 60)
    }
}
