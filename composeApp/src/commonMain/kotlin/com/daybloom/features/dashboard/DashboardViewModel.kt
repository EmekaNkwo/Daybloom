package com.daybloom.features.dashboard

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.daybloom.domain.models.AppointmentOccurrence
import com.daybloom.domain.models.AppointmentStatus
import com.daybloom.domain.models.DailyPlan
import com.daybloom.domain.models.DailyScore
import com.daybloom.domain.models.GardenTheme
import com.daybloom.domain.models.Habit
import com.daybloom.domain.models.LevelProgress
import com.daybloom.domain.models.Quest
import com.daybloom.domain.models.QuestStatus
import com.daybloom.domain.models.QuestType
import com.daybloom.domain.models.Task
import com.daybloom.domain.models.TaskStatus
import com.daybloom.domain.repositories.AppointmentRepository
import com.daybloom.domain.repositories.HabitRepository
import com.daybloom.domain.repositories.PlanRepository
import com.daybloom.domain.repositories.PreferencesRepository
import com.daybloom.domain.repositories.QuestRepository
import com.daybloom.domain.repositories.ReminderRepository
import com.daybloom.domain.repositories.ScoreRepository
import com.daybloom.domain.repositories.TaskRepository
import com.daybloom.domain.repositories.XpRepository
import com.daybloom.domain.services.AppointmentScheduleService
import com.daybloom.domain.services.DateMath
import com.daybloom.domain.services.ScoreExplainer
import com.daybloom.domain.services.Suggestion
import com.daybloom.domain.services.SuggestionAction
import com.daybloom.domain.usecases.CompleteDailyQuestUseCase
import com.daybloom.domain.usecases.CompleteTaskUseCase
import com.daybloom.domain.usecases.GetGardenStateUseCase
import com.daybloom.domain.usecases.GetSuggestionsUseCase
import com.daybloom.domain.usecases.LogBreakUseCase
import com.daybloom.domain.usecases.ReopenTaskUseCase
import com.daybloom.domain.usecases.RescheduleTaskUseCase
import com.daybloom.domain.usecases.SuggestionDismissalStore
import com.daybloom.domain.usecases.SyncSourceRemindersUseCase
import com.daybloom.domain.usecases.ToggleHabitUseCase
import com.daybloom.features.common.AppMessage
import com.daybloom.features.common.AppMessages
import com.daybloom.features.common.minuteTicks
import com.daybloom.features.common.runAction
import com.daybloom.gamification.GardenState
import com.daybloom.gamification.LevelSystem
import com.daybloom.platform.PlatformDateTimeService
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant

data class HabitToday(val habit: Habit, val done: Boolean)

data class UpcomingReminder(val title: String, val at: LocalDateTime)

data class DashboardState(
    val loading: Boolean = true,
    val now: LocalDateTime? = null,
    val displayName: String = "",
    val score: DailyScore? = null,
    val scoreSummary: String = "",
    val scoreFormula: String? = null,
    val scoreChange: String? = null,
    val plan: DailyPlan? = null,
    val topPriorities: List<Task> = emptyList(),
    val tasks: List<Task> = emptyList(),
    val tasksDone: Int = 0,
    val tasksRemaining: Int = 0,
    val nextAppointment: AppointmentOccurrence? = null,
    val reminders: List<UpcomingReminder> = emptyList(),
    val habits: List<HabitToday> = emptyList(),
    val level: LevelProgress = LevelSystem.progressFor(0),
    val quests: List<Quest> = emptyList(),
    val garden: GardenState? = null,
    val suggestions: List<Suggestion> = emptyList(),
    val gamificationEnabled: Boolean = true,
    val gardenTheme: GardenTheme = GardenTheme.MEADOW,
)

private data class DayData(
    val date: LocalDate,
    val plan: DailyPlan?,
    val tasks: List<Task>,
    val score: DailyScore?,
)

class DashboardViewModel(
    private val tasks: TaskRepository,
    private val plans: PlanRepository,
    private val scores: ScoreRepository,
    private val appointments: AppointmentRepository,
    private val habits: HabitRepository,
    private val quests: QuestRepository,
    private val reminders: ReminderRepository,
    private val xp: XpRepository,
    private val preferences: PreferencesRepository,
    private val completeTask: CompleteTaskUseCase,
    private val reopenTask: ReopenTaskUseCase,
    private val toggleHabit: ToggleHabitUseCase,
    private val questUseCase: CompleteDailyQuestUseCase,
    private val logBreak: LogBreakUseCase,
    private val garden: GetGardenStateUseCase,
    private val suggestions: GetSuggestionsUseCase,
    private val dismissals: SuggestionDismissalStore,
    private val reschedule: RescheduleTaskUseCase,
    private val reminderSync: SyncSourceRemindersUseCase,
    private val messages: AppMessages,
    private val clock: PlatformDateTimeService,
) : ViewModel() {

    private val refreshSuggestions = MutableStateFlow(0)
    private val ticks = clock.minuteTicks()
    private val today: Flow<LocalDate> = ticks.map { it.date }.distinctUntilChanged()

    private val dayData: Flow<DayData> = today.flatMapLatest { date ->
        plans.observe(date).flatMapLatest { plan ->
            val ids = plan?.let { it.plannedTaskIds + it.topPriorityTaskIds }.orEmpty()
            combine(tasks.observeRelevantTo(date, ids), scores.observe(date)) { dayTasks, score ->
                DayData(date, plan, dayTasks, score)
            }
        }
    }

    private val appointmentsToday = combine(today, appointments.observeAll(), appointments.observeOccurrenceStatuses()) { date, all, statuses ->
        AppointmentScheduleService.occurrencesBetween(all, statuses, date, DateMath.plusDays(date, 1))
    }

    private val habitsToday = combine(today, habits.observeHabits(), habits.observeCompletions()) { date, list, completions ->
        val done = completions.filter { it.date == date }.map { it.habitId }.toSet()
        list.filter { it.isScheduledOn(date) }.map { HabitToday(it, it.id in done) }
    }

    /** Recomputed when progress changes (XP total moves on every reward/revocation), not on recomposition. */
    private val gardenState: Flow<GardenState?> = xp.observeTotal()
        .mapLatest { garden() as GardenState? }
        .onStart { emit(null) }

    private val suggestionList: Flow<List<Suggestion>> = combine(dayData, refreshSuggestions, ticks.map { it.hour }.distinctUntilChanged()) { _, _, _ -> Unit }
        .mapLatest { suggestions() }
        .onStart { emit(emptyList()) }

    val state: StateFlow<DashboardState> = combine(
        combine(dayData, ticks, preferences.preferences) { d, now, prefs -> Triple(d, now, prefs) },
        combine(appointmentsToday, habitsToday, xp.observeTotal(), today.flatMapLatest { quests.observeForDate(it) }) { a, h, x, q -> Quad(a, h, x, q) },
        reminders.observeScheduled(),
        gardenState,
        suggestionList,
    ) { (day, now, prefs), (appts, habitList, totalXp, questList), scheduled, gardenValue, suggestionValue ->
        val top = day.plan?.topPriorityTaskIds.orEmpty()
        val active = day.tasks.filter { it.status != TaskStatus.CANCELLED && (it.dueDate == day.date || it.id in top || it.id in day.plan?.plannedTaskIds.orEmpty() || it.completedOn == day.date) }
        val sorted = active.sortedWith(
            compareBy<Task>({ it.status == TaskStatus.COMPLETED }, { it.id !in top }, { it.priority.rank }, { it.dueTime ?: LocalTime(23, 59) }),
        )
        val zone = clock.timeZone()
        val nowInstant = clock.now()
        DashboardState(
            loading = false,
            now = now,
            displayName = prefs.displayName,
            score = day.score,
            scoreSummary = day.score?.let { ScoreExplainer.summary(it) } ?: "Add a task or plan your day to see your score.",
            scoreFormula = day.score?.let { ScoreExplainer.formula(it) },
            plan = day.plan,
            topPriorities = top.mapNotNull { id -> day.tasks.firstOrNull { it.id == id } },
            tasks = sorted,
            tasksDone = sorted.count { it.status == TaskStatus.COMPLETED },
            tasksRemaining = sorted.count { it.status == TaskStatus.OPEN },
            nextAppointment = appts.firstOrNull { occ ->
                occ.status == AppointmentStatus.SCHEDULED && LocalDateTime(occ.date, occ.appointment.endTime) > now
            },
            reminders = scheduled
                .filter { it.fireAt > nowInstant && it.fireAt < nowInstant + 24.hours }
                .sortedBy { it.fireAt }
                .take(3)
                .mapNotNull { record -> reminderTitle(record.reminderId)?.let { UpcomingReminder(it, record.fireAt.toLocalDateTime(zone)) } },
            habits = habitList,
            level = LevelSystem.progressFor(totalXp),
            quests = questList.filter { it.status != QuestStatus.SKIPPED },
            garden = gardenValue,
            suggestions = suggestionValue,
            gamificationEnabled = prefs.gamificationEnabled,
            gardenTheme = prefs.gardenTheme,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DashboardState())

    private val titleCache = mutableMapOf<String, String>()

    private suspend fun reminderTitle(reminderId: String): String? =
        titleCache[reminderId] ?: reminders.get(reminderId)?.title?.also { titleCache[reminderId] = it }

    fun toggleTask(task: Task, done: Boolean) = runAction(messages, "Couldn't update that task.") {
        if (done) {
            val outcome = completeTask(task.id) ?: return@runAction
            val xpText = if (outcome.xpAwarded > 0) " · +${outcome.xpAwarded} XP" else ""
            messages.post(AppMessage("Nice work — “${task.title}” done$xpText", "Undo") { reopenTask(task.id) })
            outcome.progress.completedQuests.forEach { messages.post("Quest complete: ${it.title} · +${it.rewardXp} XP") }
            outcome.progress.newAchievements.forEach { messages.post("Achievement unlocked: ${it.emoji} ${it.title}") }
        } else {
            reopenTask(task.id)
        }
    }

    fun toggleHabit(habit: Habit, done: Boolean) = runAction(messages, "Couldn't update that habit.") {
        toggleHabit(habit.id, clock.today(), done)
    }

    fun skipQuest(quest: Quest) = runAction(messages) { questUseCase.skip(quest.id, quest.date) }

    fun completeManualQuest(quest: Quest) = runAction(messages) {
        if (quest.type == QuestType.TAKE_A_BREAK) logBreak()
    }

    fun dismissSuggestion(suggestion: Suggestion, snoozeHours: Int? = null) = runAction(messages) {
        val until: Instant? = snoozeHours?.let { clock.now() + it.hours }
        dismissals.dismiss(suggestion.key, until, clock.now())
        refreshSuggestions.value++
    }

    /** Executes actions that don't need navigation. Returns false when the UI should navigate instead. */
    fun applySuggestion(suggestion: Suggestion): Boolean {
        when (val action = suggestion.action) {
            is SuggestionAction.MoveUnfinished -> runAction(messages) {
                val moved = reschedule(action.taskIds, action.to)
                dismissals.dismiss(suggestion.key, null, clock.now())
                refreshSuggestions.value++
                messages.post("Moved ${moved.size} task${if (moved.size == 1) "" else "s"} to tomorrow")
            }
            is SuggestionAction.SetPlanningReminder -> runAction(messages) {
                preferences.update { it.copy(planningReminderTime = action.time) }
                reminderSync.setPlanningReminder(action.time)
                dismissals.dismiss(suggestion.key, null, clock.now())
                refreshSuggestions.value++
                messages.post("Planning reminder set")
            }
            else -> return false
        }
        return true
    }
}

private data class Quad<A, B, C, D>(val a: A, val b: B, val c: C, val d: D)
