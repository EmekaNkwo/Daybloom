package com.daybloom.domain.usecases

import com.daybloom.domain.models.DailyScore
import com.daybloom.domain.models.FocusStatus
import com.daybloom.domain.models.TaskStatus
import com.daybloom.domain.repositories.AppointmentRepository
import com.daybloom.domain.repositories.FocusRepository
import com.daybloom.domain.repositories.HabitRepository
import com.daybloom.domain.repositories.PlanRepository
import com.daybloom.domain.repositories.PreferencesRepository
import com.daybloom.domain.repositories.ScoreRepository
import com.daybloom.domain.repositories.TaskRepository
import com.daybloom.domain.services.AppointmentScheduleService
import com.daybloom.domain.services.DayScoreInput
import com.daybloom.domain.services.PlanCapacityCalculator
import com.daybloom.domain.services.ProductivityScoreCalculator
import com.daybloom.domain.services.ScoredTask
import com.daybloom.platform.PlatformDateTimeService
import kotlinx.datetime.LocalDate
import kotlinx.datetime.toLocalDateTime

/** Gathers persisted facts about one day into the pure scoring engine's input. */
class BuildDayScoreInputUseCase(
    private val tasks: TaskRepository,
    private val plans: PlanRepository,
    private val focus: FocusRepository,
    private val habits: HabitRepository,
    private val appointments: AppointmentRepository,
    private val preferences: PreferencesRepository,
    private val clock: PlatformDateTimeService,
) {
    suspend operator fun invoke(date: LocalDate): DayScoreInput {
        val plan = plans.get(date)
        val topIds = plan?.topPriorityTaskIds.orEmpty().toSet()
        val dayTasks = tasks.getRelevantTo(date, plan?.plannedTaskIds.orEmpty() + topIds)
        val scored = dayTasks.map { t ->
            ScoredTask(
                id = t.id,
                priority = t.priority,
                status = t.status,
                isTopPriority = t.id in topIds,
                completedByDay = t.status == TaskStatus.COMPLETED && t.completedOn != null && t.completedOn <= date,
            )
        }
        val sessions = focus.getAll()
        val focusMinutes = sessions
            .filter { it.date == date && it.status != FocusStatus.RUNNING && it.status != FocusStatus.PAUSED }
            .sumOf { it.accumulatedSeconds } / 60
        val focusTracked = sessions.any { it.date <= date }

        val zone = clock.timeZone()
        val habitList = habits.getHabits().filter { h ->
            h.isScheduledOn(date) && h.createdAt.toLocalDateTime(zone).date <= date
        }
        val completions = habits.getCompletionsBetween(date, date).map { it.habitId }.toSet()
        val habitsCompleted = habitList.count { it.id in completions }

        val occurrences = AppointmentScheduleService.occurrencesOn(appointments.getAll(), appointments.getOccurrenceStatuses(), date)
        val capacity = PlanCapacityCalculator.calculate(
            availableMinutes = plan?.availableMinutes,
            taskEstimates = dayTasks.filter { it.status != TaskStatus.CANCELLED }.map { it.estimatedMinutes },
            appointmentMinutes = occurrences.map { it.appointment.durationMinutes },
            breakMinutes = plan?.breakMinutes ?: 0,
        )
        val prefs = preferences.current()
        return DayScoreInput(
            date = date,
            isRestDay = plan?.isRestDay == true,
            hasPlan = plan != null && !plan.isRestDay &&
                (plan.topPriorityTaskIds.isNotEmpty() || plan.plannedTaskIds.isNotEmpty() || plan.availableMinutes != null),
            tasks = scored,
            focusMinutes = focusMinutes.toInt(),
            focusGoalMinutes = prefs.dailyFocusGoalMinutes,
            focusTracked = focusTracked,
            habitsScheduled = habitList.size,
            habitsCompleted = habitsCompleted,
            topPrioritiesSet = topIds.size,
            plannedMinutes = capacity.plannedMinutes,
            availableMinutes = plan?.availableMinutes,
            acceptedOverCapacity = plan?.acceptedOverCapacity == true,
            hasActivity = dayTasks.any { it.completedOn == date } || focusMinutes > 0 || habitsCompleted > 0,
        )
    }
}

/** CalculateDailyProductivityScore: recomputes and persists the snapshot for a day. */
class CalculateDailyProductivityScoreUseCase(
    private val buildInput: BuildDayScoreInputUseCase,
    private val scores: ScoreRepository,
    private val preferences: PreferencesRepository,
    private val clock: PlatformDateTimeService,
    private val calculator: ProductivityScoreCalculator = ProductivityScoreCalculator(),
) {
    data class Result(val previous: DailyScore?, val current: DailyScore)

    suspend operator fun invoke(date: LocalDate): Result {
        val previous = scores.get(date)
        val input = buildInput(date)
        val score = calculator.calculate(input, preferences.current().enabledScoreDimensions, clock.now())
        val unchanged = previous != null && previous.overall == score.overall &&
            previous.classification == score.classification &&
            previous.dimensions.map { it.score } == score.dimensions.map { it.score }
        if (!unchanged) scores.save(score)
        return Result(previous, if (unchanged) previous ?: score else score)
    }
}
