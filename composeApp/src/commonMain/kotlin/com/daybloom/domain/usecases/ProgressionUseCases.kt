package com.daybloom.domain.usecases

import com.daybloom.domain.models.AppointmentStatus
import com.daybloom.domain.models.DailyScore
import com.daybloom.domain.models.FocusStatus
import com.daybloom.domain.models.Quest
import com.daybloom.domain.models.QuestStatus
import com.daybloom.domain.models.TaskStatus
import com.daybloom.domain.models.XpEntry
import com.daybloom.domain.models.XpReason
import com.daybloom.domain.repositories.AchievementRepository
import com.daybloom.domain.repositories.AppointmentRepository
import com.daybloom.domain.repositories.FocusRepository
import com.daybloom.domain.repositories.HabitRepository
import com.daybloom.domain.repositories.JournalRepository
import com.daybloom.domain.repositories.PlanRepository
import com.daybloom.domain.repositories.QuestRepository
import com.daybloom.domain.repositories.TaskRepository
import com.daybloom.domain.repositories.XpRepository
import com.daybloom.domain.services.AppointmentScheduleService
import com.daybloom.domain.services.DateMath
import com.daybloom.domain.services.StreakCalculator
import com.daybloom.gamification.AchievementCatalog
import com.daybloom.gamification.AchievementDefinition
import com.daybloom.gamification.GardenCalculator
import com.daybloom.gamification.GardenInputs
import com.daybloom.gamification.ProgressStats
import com.daybloom.gamification.QuestCatalog
import com.daybloom.gamification.QuestContext
import com.daybloom.gamification.QuestDayProgress
import com.daybloom.gamification.RewardConfig
import com.daybloom.platform.PlatformDateTimeService
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime
import kotlinx.datetime.toLocalDateTime

/**
 * AwardExperiencePointsUseCase: the only path that writes XP. Validates the reward against [RewardConfig]
 * and relies on the ledger's unique event key for idempotency.
 */
class AwardExperiencePointsUseCase(
    private val xp: XpRepository,
    private val clock: PlatformDateTimeService,
) {
    /** Returns the XP actually granted (0 when the event was already rewarded or capped). */
    suspend fun award(reason: XpReason, eventKey: String, sourceId: String?, date: LocalDate, amount: Int = RewardConfig.amountFor(reason)): Int {
        require(amount >= 0) { "XP awards cannot be negative" }
        if (xp.exists(eventKey)) return 0
        val isTaskReward = reason == XpReason.TASK_COMPLETED || reason == XpReason.TOP_PRIORITY_COMPLETED
        val granted = if (isTaskReward && xp.countRewardedTasksOn(date) >= RewardConfig.MAX_REWARDED_TASKS_PER_DAY) 0 else amount
        // A capped award is still recorded (with 0 XP) so the event stays counted and can never be re-rewarded.
        val recorded = xp.award(XpEntry(eventKey, granted, reason, sourceId, date, clock.now()))
        return if (recorded) granted else 0
    }

    suspend fun revoke(eventKey: String): Boolean = xp.revoke(eventKey)
}

/** Builds cumulative [ProgressStats] and [GardenInputs] from persisted records. */
class CollectProgressStatsUseCase(
    private val tasks: TaskRepository,
    private val plans: PlanRepository,
    private val focus: FocusRepository,
    private val habits: HabitRepository,
    private val appointments: AppointmentRepository,
    private val journal: JournalRepository,
    private val quests: QuestRepository,
    private val xp: XpRepository,
    private val clock: PlatformDateTimeService,
) {
    data class Facts(val stats: ProgressStats, val garden: GardenInputs, val activeDays: Set<LocalDate>, val restDays: Set<LocalDate>)

    suspend operator fun invoke(): Facts {
        val zone = clock.timeZone()
        val today = clock.today()
        val allPlans = plans.getAll()
        val restDays = allPlans.filter { it.isRestDay }.map { it.date }.toSet()
        val realPlans = allPlans.filter { !it.isRestDay && (it.topPriorityTaskIds.isNotEmpty() || it.plannedTaskIds.isNotEmpty()) }
        val plannedEarly = realPlans.count { plan ->
            val created = plan.createdAt.toLocalDateTime(zone)
            created.date < plan.date || (created.date == plan.date && created.time < LocalTime(10, 0))
        }

        val sessions = focus.getAll().filter { it.status == FocusStatus.COMPLETED }
        val completionDates = tasks.completionDates()
        val habitList = habits.getHabits()
        val habitCompletions = habits.getCompletions()
        val activeDays = completionDates + sessions.map { it.date } + habitCompletions.map { it.date }

        val streaks = habitList.map { h ->
            StreakCalculator.habitStreak(
                habit = h,
                completions = habitCompletions.filter { it.habitId == h.id }.map { it.date }.toSet(),
                restDays = restDays,
                today = today,
                startDate = h.createdAt.toLocalDateTime(zone).date,
            )
        }

        val categoriesByMonth = tasks.completionCategories().groupBy { DateMath.firstOfMonth(it.first) }
        val restMonths = restDays.map { DateMath.firstOfMonth(it) }.toSet()
        val balancedMonths = categoriesByMonth.count { (month, list) ->
            list.map { it.second }.distinct().size >= 4 && month in restMonths
        }

        val statuses = appointments.getOccurrenceStatuses()
        val attendedKeys = statuses.filterValues { it == AppointmentStatus.ATTENDED }.keys
        val appointmentList = appointments.getAll()
        val withPrep = attendedKeys.count { key ->
            val id = key.substringBefore('@')
            val date = runCatching { LocalDate.parse(key.substringAfter('@')) }.getOrNull() ?: return@count false
            val appt = appointmentList.firstOrNull { it.id == id } ?: return@count false
            val prep = tasks.getForAppointment(appt.id).filter { it.status != TaskStatus.CANCELLED }
            prep.isNotEmpty() && prep.all { it.completedOn != null && it.completedOn <= date }
        }

        val reflections = journal.getReflections().filter { !it.isEmpty }
        val maxReflectionsInMonth = reflections.groupingBy { DateMath.firstOfMonth(it.date) }.eachCount().values.maxOrNull() ?: 0

        val weeksWithProgress = activeDays.groupBy { DateMath.startOfWeek(it) }
            .count { it.value.size >= GardenCalculator.ACTIVE_DAYS_FOR_WEEK }
        val goalMonths = activeDays.groupBy { DateMath.firstOfMonth(it) }
            .count { it.value.size >= GardenCalculator.ACTIVE_DAYS_FOR_MONTH }

        val tasksCompleted = tasks.countCompleted()
        val topPriorities = xp.countByReason(XpReason.TOP_PRIORITY_COMPLETED)
        val questsCompleted = quests.countCompleted()

        val stats = ProgressStats(
            tasksCompleted = tasksCompleted,
            topPrioritiesCompleted = topPriorities,
            daysPlanned = realPlans.size,
            daysPlannedBeforeTen = plannedEarly,
            focusSessionsCompleted = sessions.size,
            focusMinutesTotal = (sessions.sumOf { it.accumulatedSeconds } / 60).toInt(),
            longestFocusMinutes = (sessions.maxOfOrNull { it.accumulatedSeconds } ?: 0L).toInt() / 60,
            bestHabitStreak = streaks.maxOfOrNull { it.best } ?: 0,
            bestWeeklyHabitStreak = streaks.maxOfOrNull { it.bestWeeks } ?: 0,
            activeDaysTotal = activeDays.size,
            comebacks = StreakCalculator.comebacks(activeDays, restDays),
            balancedMonths = balancedMonths,
            appointmentsAttended = attendedKeys.size,
            appointmentsWithPrepDone = withPrep,
            reflectionsWritten = reflections.size,
            maxReflectionsInMonth = maxReflectionsInMonth,
            questsCompleted = questsCompleted,
            restDaysTaken = restDays.count { it <= today },
        )
        val garden = GardenInputs(
            tasksCompleted = tasksCompleted,
            topPrioritiesCompleted = topPriorities,
            questsCompleted = questsCompleted,
            focusSessionsCompleted = sessions.size,
            progressWeeks = weeksWithProgress,
            goalMonths = goalMonths,
        )
        return Facts(stats, garden, activeDays, restDays)
    }
}

/** EvaluateAchievementUseCase: unlocks every newly met achievement exactly once and grants its XP. */
class EvaluateAchievementUseCase(
    private val achievements: AchievementRepository,
    private val awardXp: AwardExperiencePointsUseCase,
    private val clock: PlatformDateTimeService,
) {
    suspend operator fun invoke(stats: ProgressStats): List<AchievementDefinition> {
        val unlocked = achievements.getUnlocks().map { it.achievementId }.toSet()
        val now = clock.now()
        val today = clock.today()
        return AchievementCatalog.newlyUnlocked(stats, unlocked).filter { def ->
            val isNew = achievements.unlock(def.id, now)
            if (isNew) awardXp.award(XpReason.ACHIEVEMENT, RewardConfig.Keys.achievement(def.id), def.id, today, def.xpReward)
            isNew
        }
    }
}

/** Creates the day's quests once. Later calls are no-ops, so quests can't be re-rolled. */
class EnsureDailyQuestsUseCase(
    private val quests: QuestRepository,
    private val tasks: TaskRepository,
    private val habits: HabitRepository,
) {
    suspend operator fun invoke(date: LocalDate): List<Quest> {
        val existing = quests.getForDate(date)
        if (existing.isNotEmpty()) return existing
        val open = tasks.getOpen()
        val context = QuestContext(
            hasHabits = habits.getHabits().any { it.isScheduledOn(date) },
            hasPostponedTasks = open.any { it.postponeCount > 0 },
            hasOpenTasks = open.isNotEmpty(),
        )
        quests.insertIfAbsent(QuestCatalog.generate(date, context))
        return quests.getForDate(date)
    }
}

/** CompleteDailyQuestUseCase: advances quests from real progress, completes them and pays out once. */
class CompleteDailyQuestUseCase(
    private val quests: QuestRepository,
    private val tasks: TaskRepository,
    private val plans: PlanRepository,
    private val focus: FocusRepository,
    private val habits: HabitRepository,
    private val journal: JournalRepository,
    private val awardXp: AwardExperiencePointsUseCase,
    private val clock: PlatformDateTimeService,
) {
    /** Returns quests newly completed by this evaluation. */
    suspend fun evaluate(date: LocalDate): List<Quest> {
        val dayQuests = quests.getForDate(date).filter { it.status == QuestStatus.ACTIVE }
        if (dayQuests.isEmpty()) return emptyList()
        val progress = dayProgress(date)
        val now = clock.now()
        return dayQuests.mapNotNull { quest ->
            val advanced = QuestCatalog.advance(quest, progress, now)
            if (advanced != quest) quests.update(advanced)
            if (advanced.status == QuestStatus.COMPLETED) {
                awardXp.award(XpReason.DAILY_QUEST, RewardConfig.Keys.quest(quest.id), quest.id, date, quest.rewardXp)
                advanced
            } else {
                null
            }
        }
    }

    suspend fun skip(questId: String, date: LocalDate) {
        quests.getForDate(date).firstOrNull { it.id == questId && it.status == QuestStatus.ACTIVE }?.let {
            quests.update(it.copy(status = QuestStatus.SKIPPED))
        }
    }

    private suspend fun dayProgress(date: LocalDate): QuestDayProgress {
        val plan = plans.get(date)
        val top = plan?.topPriorityTaskIds.orEmpty().toSet()
        val completedToday = tasks.getCompletedBetween(date, date)
        val tomorrow = plans.get(DateMath.plusDays(date, 1))
        val focusMinutes = focus.getBetween(date, date)
            .filter { !it.isActive }
            .sumOf { it.accumulatedSeconds } / 60
        return QuestDayProgress(
            topPrioritiesCompleted = completedToday.count { it.id in top },
            tasksCompleted = completedToday.size,
            focusMinutes = focusMinutes.toInt(),
            habitsCompleted = habits.getCompletionsBetween(date, date).size,
            tomorrowPlanned = tomorrow != null && (tomorrow.topPriorityTaskIds.isNotEmpty() || tomorrow.isRestDay),
            postponedTasksCompleted = completedToday.count { it.postponeCount > 0 },
            breaksTaken = journal.countBreaks(date),
        )
    }
}

data class ProgressUpdate(
    val score: CalculateDailyProductivityScoreUseCase.Result?,
    val completedQuests: List<Quest>,
    val newAchievements: List<AchievementDefinition>,
)

/**
 * Runs after any user activity: recomputes the affected days' scores, advances today's quests and evaluates
 * achievements — in that order, serialized so concurrent events can't double-process.
 */
class ProcessProgressUseCase(
    private val calculateScore: CalculateDailyProductivityScoreUseCase,
    private val ensureQuests: EnsureDailyQuestsUseCase,
    private val completeQuests: CompleteDailyQuestUseCase,
    private val collectStats: CollectProgressStatsUseCase,
    private val evaluateAchievements: EvaluateAchievementUseCase,
    private val clock: PlatformDateTimeService,
) {
    private val mutex = Mutex()

    suspend operator fun invoke(vararg affectedDates: LocalDate?): ProgressUpdate = mutex.withLock {
        val today = clock.today()
        val dates = (affectedDates.filterNotNull() + today).distinct().filter { it <= today }
        var todayScore: CalculateDailyProductivityScoreUseCase.Result? = null
        dates.forEach { date ->
            val result = calculateScore(date)
            if (date == today) todayScore = result
        }
        ensureQuests(today)
        val quests = completeQuests.evaluate(today)
        val achievements = evaluateAchievements(collectStats().stats)
        ProgressUpdate(todayScore, quests, achievements)
    }
}

/** Derives the Bloom Garden from persisted progress. */
class GetGardenStateUseCase(private val collectStats: CollectProgressStatsUseCase) {
    suspend operator fun invoke() = GardenCalculator.calculate(collectStats().garden)
}

/** Day lookups for the score history (used when the snapshot is missing). */
class GetDailyScoreUseCase(private val calculateScore: CalculateDailyProductivityScoreUseCase) {
    suspend operator fun invoke(date: LocalDate): DailyScore = calculateScore(date).current
}

/** "Take a restorative break" quest is self-reported. */
class LogBreakUseCase(
    private val journal: JournalRepository,
    private val ids: com.daybloom.platform.IdGenerator,
    private val clock: PlatformDateTimeService,
    private val process: ProcessProgressUseCase,
) {
    suspend operator fun invoke() {
        val today = clock.today()
        journal.logBreak(ids.newId(), today, clock.now())
        process(today)
    }
}
