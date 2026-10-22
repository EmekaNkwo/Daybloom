package com.daybloom.domain.usecases

import com.daybloom.domain.models.DailyReflection
import com.daybloom.domain.models.DailyScore
import com.daybloom.domain.models.FocusStatus
import com.daybloom.domain.models.ManualWin
import com.daybloom.domain.models.Mood
import com.daybloom.domain.models.TaskStatus
import com.daybloom.domain.models.UnfinishedTaskPolicy
import com.daybloom.domain.models.XpReason
import com.daybloom.domain.repositories.AchievementRepository
import com.daybloom.domain.repositories.AppointmentRepository
import com.daybloom.domain.repositories.FocusRepository
import com.daybloom.domain.repositories.HabitRepository
import com.daybloom.domain.repositories.JournalRepository
import com.daybloom.domain.repositories.PlanRepository
import com.daybloom.domain.repositories.PreferencesRepository
import com.daybloom.domain.repositories.ScoreRepository
import com.daybloom.domain.repositories.TaskRepository
import com.daybloom.domain.services.AppointmentFact
import com.daybloom.domain.services.AppointmentScheduleService
import com.daybloom.domain.services.CompletionFact
import com.daybloom.domain.services.DateMath
import com.daybloom.domain.services.FocusFact
import com.daybloom.domain.services.MonthlyAnalyticsCalculator
import com.daybloom.domain.services.MonthlyInput
import com.daybloom.domain.services.MonthlyInsights
import com.daybloom.domain.services.MonthlyRecap
import com.daybloom.domain.services.MonthlyRecapGenerator
import com.daybloom.domain.services.RecapAchievement
import com.daybloom.domain.services.Suggestion
import com.daybloom.domain.services.SuggestionEngine
import com.daybloom.domain.services.SuggestionInput
import com.daybloom.domain.services.TaskDueFact
import com.daybloom.domain.services.Validation
import com.daybloom.gamification.AchievementCatalog
import com.daybloom.gamification.RewardConfig
import com.daybloom.platform.IdGenerator
import com.daybloom.platform.PlatformDateTimeService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.datetime.LocalDate
import kotlinx.datetime.toLocalDateTime

/**
 * Builds a month's insights from stored history. Missing day snapshots are computed (and persisted) on demand,
 * so a day the app wasn't opened is still classified correctly rather than silently treated as zero.
 */
class GenerateMonthlyInsightsUseCase(
    private val scores: ScoreRepository,
    private val calculateScore: CalculateDailyProductivityScoreUseCase,
    private val tasks: TaskRepository,
    private val plans: PlanRepository,
    private val focus: FocusRepository,
    private val habits: HabitRepository,
    private val appointments: AppointmentRepository,
    private val clock: PlatformDateTimeService,
) {
    suspend operator fun invoke(month: LocalDate): MonthlyInsights = withContext(Dispatchers.Default) {
        val today = clock.today()
        val first = DateMath.firstOfMonth(month)
        val last = minOf(DateMath.lastOfMonth(month), today)
        if (last < first) {
            return@withContext MonthlyAnalyticsCalculator.calculate(emptyInput(month, today), emptySet())
        }
        val stored = scores.getBetween(first, last).associateBy { it.date }
        val daily: List<DailyScore> = DateMath.datesBetween(first, last).map { date ->
            stored[date]?.takeIf { date < today } ?: calculateScore(date).current
        }

        val dueTasks = tasks.getDueBetween(first, last).filter { it.status != TaskStatus.CANCELLED }
        val monthPlans = plans.getBetween(first, last)
        val topIds = monthPlans.flatMap { it.topPriorityTaskIds }.toSet()
        val completed = tasks.getCompletedBetween(first, last)
        val sessions = focus.getBetween(first, last).filter { it.status == FocusStatus.COMPLETED || it.status == FocusStatus.INTERRUPTED }

        val zone = clock.timeZone()
        val habitList = habits.getHabits()
        val days = DateMath.datesBetween(first, last)
        val habitsScheduled = days.sumOf { d ->
            habitList.count { it.isScheduledOn(d) && it.createdAt.toLocalDateTime(zone).date <= d }
        }
        val habitCompletions = habits.getCompletionsBetween(first, last)

        val occurrences = AppointmentScheduleService.occurrencesBetween(
            appointments.getAll(), appointments.getOccurrenceStatuses(), first, last,
        )
        val restDays = monthPlans.filter { it.isRestDay }.map { it.date }.toSet()
        val activeDays = (completed.mapNotNull { it.completedOn } + sessions.map { it.date } + habitCompletions.map { it.date }).toSet()

        val input = MonthlyInput(
            month = first,
            today = today,
            dailyScores = daily,
            tasksDue = dueTasks.mapNotNull { t ->
                t.dueDate?.let { TaskDueFact(it, t.priority, t.id in topIds, t.completedOn) }
            },
            completions = completed.mapNotNull { t -> t.completedOn?.let { CompletionFact(it, t.category) } },
            focus = sessions.map { FocusFact(it.date, (it.accumulatedSeconds / 60).toInt(), it.category) },
            habitsScheduled = habitsScheduled,
            habitsCompleted = habitCompletions.count { c -> habitList.any { it.id == c.habitId && it.scheduledDays.contains(c.date.dayOfWeek) } },
            appointments = occurrences.map { AppointmentFact(it.date, it.status) },
            plannedDays = monthPlans.filter { it.topPriorityTaskIds.isNotEmpty() || it.plannedTaskIds.isNotEmpty() }.map { it.date }.toSet(),
            activeDays = activeDays,
        )
        MonthlyAnalyticsCalculator.calculate(input, restDays)
    }

    private fun emptyInput(month: LocalDate, today: LocalDate) = MonthlyInput(
        month = month, today = today, dailyScores = emptyList(), tasksDue = emptyList(), completions = emptyList(),
        focus = emptyList(), habitsScheduled = 0, habitsCompleted = 0, appointments = emptyList(),
        plannedDays = emptySet(), activeDays = emptySet(),
    )
}

class GenerateMonthlyRecapUseCase(
    private val insights: GenerateMonthlyInsightsUseCase,
    private val achievements: AchievementRepository,
    private val clock: PlatformDateTimeService,
) {
    data class Recap(val recap: MonthlyRecap, val insights: MonthlyInsights)

    suspend operator fun invoke(month: LocalDate): Recap {
        val data = insights(month)
        val zone = clock.timeZone()
        val first = DateMath.firstOfMonth(month)
        val last = DateMath.lastOfMonth(month)
        val unlocked = achievements.getUnlocks()
            .filter { it.unlockedAt.toLocalDateTime(zone).date in first..last }
            .mapNotNull { AchievementCatalog.byId(it.achievementId) }
            .map { RecapAchievement(it.title, it.emoji) }
        return Recap(MonthlyRecapGenerator.generate(data, unlocked), data)
    }
}

class SaveReflectionUseCase(
    private val journal: JournalRepository,
    private val awardXp: AwardExperiencePointsUseCase,
    private val process: ProcessProgressUseCase,
    private val clock: PlatformDateTimeService,
) {
    suspend operator fun invoke(
        date: LocalDate,
        wentWell: String,
        biggestWin: String,
        moveToTomorrow: String,
        mood: Mood?,
        tomorrowTopPriority: String,
    ): String? {
        listOf(wentWell, biggestWin, moveToTomorrow, tomorrowTopPriority).forEach { text ->
            Validation.longText(text, "Reflection")?.let { return it }
        }
        val reflection = DailyReflection(date, wentWell.trim(), biggestWin.trim(), moveToTomorrow.trim(), mood, tomorrowTopPriority.trim(), clock.now())
        if (reflection.isEmpty) {
            journal.deleteReflection(date)
        } else {
            journal.saveReflection(reflection)
            awardXp.award(XpReason.REFLECTION, RewardConfig.Keys.reflection(date.toString()), null, date)
        }
        process(date)
        return null
    }
}

class DeleteReflectionUseCase(private val journal: JournalRepository) {
    suspend operator fun invoke(date: LocalDate) = journal.deleteReflection(date)
}

class ManualWinUseCases(
    private val journal: JournalRepository,
    private val ids: IdGenerator,
    private val clock: PlatformDateTimeService,
) {
    suspend fun add(date: LocalDate, text: String): String? {
        Validation.title(text)?.let { return it }
        journal.saveWin(ManualWin(ids.newId(), date, text.trim(), clock.now()))
        return null
    }

    suspend fun delete(id: String) = journal.deleteWin(id)
}

/** Deterministic suggestions from the user's own records, honoring the global switch and dismissals. */
class GetSuggestionsUseCase(
    private val tasks: TaskRepository,
    private val plans: PlanRepository,
    private val appointments: AppointmentRepository,
    private val preferences: PreferencesRepository,
    private val dismissals: SuggestionDismissalStore,
    private val clock: PlatformDateTimeService,
) {
    suspend operator fun invoke(): List<Suggestion> {
        val prefs = preferences.current()
        if (!prefs.suggestionsEnabled) return emptyList()
        val now = clock.localNow()
        val today = now.date
        val zone = clock.timeZone()
        val recentPlans = plans.getBetween(DateMath.plusDays(today, -14), today).filter { !it.isRestDay }
        return SuggestionEngine.suggest(
            SuggestionInput(
                now = now,
                openTasks = tasks.getOpen(),
                todaysAppointments = AppointmentScheduleService.occurrencesOn(appointments.getAll(), appointments.getOccurrenceStatuses(), today),
                tomorrowHasPlan = plans.get(DateMath.plusDays(today, 1)) != null,
                // Only plans made on the day itself say anything about a morning-planning habit.
                recentPlanCreationTimes = recentPlans.mapNotNull { plan ->
                    plan.createdAt.toLocalDateTime(zone).takeIf { it.date == plan.date }?.time
                },
                hasPlanningReminder = prefs.planningReminderTime != null,
                dismissedKeys = dismissals.activeKeys(clock.now()),
                offerMoveUnfinished = prefs.unfinishedTaskPolicy == UnfinishedTaskPolicy.ASK,
            ),
        )
    }
}

/** Dismissed or snoozed suggestion keys. Backed by the database (schema v2). */
interface SuggestionDismissalStore {
    suspend fun activeKeys(now: kotlin.time.Instant): Set<String>
    /** [until] = null dismisses permanently; otherwise the suggestion is snoozed until then. */
    suspend fun dismiss(key: String, until: kotlin.time.Instant?, now: kotlin.time.Instant)
}
