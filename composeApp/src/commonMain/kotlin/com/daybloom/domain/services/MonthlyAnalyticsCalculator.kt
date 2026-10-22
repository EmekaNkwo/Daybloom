package com.daybloom.domain.services

import com.daybloom.domain.models.AppointmentStatus
import com.daybloom.domain.models.DailyScore
import com.daybloom.domain.models.DayClassification
import com.daybloom.domain.models.Priority
import com.daybloom.domain.models.ScoreDimension
import com.daybloom.domain.models.TaskCategory
import kotlinx.datetime.LocalDate
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** A task that was due in the month (cancelled tasks excluded by the caller). */
data class TaskDueFact(
    val dueDate: LocalDate,
    val priority: Priority,
    val isTopPriority: Boolean,
    val completedOn: LocalDate?,
)

data class CompletionFact(val date: LocalDate, val category: TaskCategory)

data class FocusFact(val date: LocalDate, val minutes: Int, val category: TaskCategory?)

data class AppointmentFact(val date: LocalDate, val status: AppointmentStatus)

data class MonthlyInput(
    val month: LocalDate,
    val today: LocalDate,
    /** One entry per day of the month up to today; days with nothing recorded have classification NO_DATA. */
    val dailyScores: List<DailyScore>,
    val tasksDue: List<TaskDueFact>,
    val completions: List<CompletionFact>,
    val focus: List<FocusFact>,
    val habitsScheduled: Int,
    val habitsCompleted: Int,
    val appointments: List<AppointmentFact>,
    val plannedDays: Set<LocalDate>,
    val activeDays: Set<LocalDate>,
)

data class TrendPoint(val date: LocalDate, val score: Int?, val classification: DayClassification)

data class WeekSummary(
    val weekStart: LocalDate,
    val averageScore: Int?,
    val scoredDays: Int,
    val tasksCompleted: Int,
    val overdueTasks: Int,
)

data class MonthlyInsights(
    val month: LocalDate,
    val averageScore: Int?,
    val scoredDays: Int,
    val restDays: Int,
    val noDataDays: Int,
    /** Calendar days elapsed in the month minus rest days. */
    val eligibleDays: Int,
    /** Scored days as a percentage of eligible days. */
    val consistencyPercent: Int,
    /** Population standard deviation of daily scores; lower = steadier. Null with fewer than 2 scored days. */
    val scoreSpread: Int?,
    val tasksCompleted: Int,
    val importantCompletionPercent: Int?,
    val focusMinutes: Int,
    val habitCompletionPercent: Int?,
    val planningConsistencyPercent: Int,
    /** Attended / (attended + missed). Null when no attendance was recorded — never inferred from elapsed time. */
    val appointmentAttendancePercent: Int?,
    val appointmentsRecorded: Int,
    val trend: List<TrendPoint>,
    val weeks: List<WeekSummary>,
    val categoryDistribution: Map<TaskCategory, Int>,
    val focusByCategory: Map<TaskCategory?, Int>,
    val dimensionAverages: Map<ScoreDimension, Int>,
    val bestDay: LocalDate?,
    val strongestCategory: TaskCategory?,
    val bestStreak: Int,
) {
    val hasEnoughDataForTrends: Boolean get() = scoredDays >= MonthlyAnalyticsCalculator.MIN_DAYS_FOR_TRENDS
}

/**
 * Monthly aggregation (documented in the README under "Monthly analytics"):
 * - Average score = arithmetic mean of overall scores over **scored days**: PLANNED and ACTIVITY_ONLY days with a
 *   score. Low-scoring planned days are always included.
 * - REST and NO_DATA days are excluded from the average and shown separately; they are never treated as zero.
 * - Consistency = scored days ÷ (elapsed days − rest days).
 */
object MonthlyAnalyticsCalculator {
    const val MIN_DAYS_FOR_TRENDS = 5

    fun calculate(input: MonthlyInput, restDays: Set<LocalDate>): MonthlyInsights {
        val monthDays = DateMath.monthDates(input.month).filter { it <= input.today }
        val scoresByDate = input.dailyScores.associateBy { it.date }
        val trend = monthDays.map { d ->
            val s = scoresByDate[d]
            TrendPoint(d, s?.overall, s?.classification ?: DayClassification.NO_DATA)
        }
        val scored = trend.filter { it.score != null && it.classification.isScoredClass() }
        val rest = trend.count { it.classification == DayClassification.REST }
        val noData = trend.count { it.classification == DayClassification.NO_DATA }
        val eligible = (monthDays.size - rest).coerceAtLeast(0)
        val values = scored.mapNotNull { it.score }

        val average = values.takeIf { it.isNotEmpty() }?.average()?.roundToInt()
        val spread = if (values.size >= 2) {
            val mean = values.average()
            sqrt(values.sumOf { (it - mean) * (it - mean) } / values.size).roundToInt()
        } else {
            null
        }

        val due = input.tasksDue.filter { it.dueDate in monthDays }
        val important = due.filter { it.isTopPriority || it.priority.isImportant }
        val importantPct = important.takeIf { it.isNotEmpty() }
            ?.let { list -> percent(list.count { it.completedOn != null }, list.size) }

        val completionsInMonth = input.completions.filter { it.date in monthDays }
        val categoryDistribution = completionsInMonth.groupingBy { it.category }.eachCount()
        val focusInMonth = input.focus.filter { it.date in monthDays }

        val attended = input.appointments.count { it.status == AppointmentStatus.ATTENDED && it.date in monthDays }
        val missed = input.appointments.count { it.status == AppointmentStatus.MISSED && it.date in monthDays }

        val weeks = monthDays.groupBy { DateMath.startOfWeek(it) }.map { (weekStart, days) ->
            val weekScores = days.mapNotNull { d -> scored.firstOrNull { it.date == d }?.score }
            WeekSummary(
                weekStart = weekStart,
                averageScore = weekScores.takeIf { it.isNotEmpty() }?.average()?.roundToInt(),
                scoredDays = weekScores.size,
                tasksCompleted = completionsInMonth.count { it.date in days },
                overdueTasks = due.count { t ->
                    t.dueDate in days && (t.completedOn == null || t.completedOn > t.dueDate) &&
                        t.dueDate < input.today
                },
            )
        }.sortedBy { it.weekStart }

        val dimensionAverages = ScoreDimension.entries.mapNotNull { dim ->
            val dimValues = input.dailyScores
                .filter { it.date in monthDays && it.overall != null }
                .mapNotNull { s -> s.dimensions.firstOrNull { it.dimension == dim }?.score }
            dimValues.takeIf { it.isNotEmpty() }?.let { dim to it.average().roundToInt() }
        }.toMap()

        val bestDay = scored.maxWithOrNull(compareBy<TrendPoint> { it.score ?: 0 }.thenByDescending { it.date })?.date

        return MonthlyInsights(
            month = DateMath.firstOfMonth(input.month),
            averageScore = average,
            scoredDays = scored.size,
            restDays = rest,
            noDataDays = noData,
            eligibleDays = eligible,
            consistencyPercent = if (eligible == 0) 0 else percent(scored.size, eligible),
            scoreSpread = spread,
            tasksCompleted = completionsInMonth.size,
            importantCompletionPercent = importantPct,
            focusMinutes = focusInMonth.sumOf { it.minutes },
            habitCompletionPercent = input.habitsScheduled.takeIf { it > 0 }
                ?.let { percent(input.habitsCompleted.coerceAtMost(it), it) },
            planningConsistencyPercent = if (eligible == 0) 0 else percent(input.plannedDays.count { it in monthDays && it !in restDays }, eligible),
            appointmentAttendancePercent = (attended + missed).takeIf { it > 0 }?.let { percent(attended, it) },
            appointmentsRecorded = attended + missed,
            trend = trend,
            weeks = weeks,
            categoryDistribution = categoryDistribution,
            focusByCategory = focusInMonth.groupBy { it.category }.mapValues { (_, v) -> v.sumOf { it.minutes } },
            dimensionAverages = dimensionAverages,
            bestDay = bestDay,
            strongestCategory = categoryDistribution.maxWithOrNull(compareBy({ it.value }, { -it.key.ordinal }))?.key,
            bestStreak = StreakCalculator.bestActivityStreak(input.activeDays.filter { it in monthDays }.toSet(), restDays),
        )
    }

    private fun DayClassification.isScoredClass() =
        this == DayClassification.PLANNED || this == DayClassification.ACTIVITY_ONLY

    private fun percent(part: Int, whole: Int): Int = if (whole == 0) 0 else (part * 100.0 / whole).roundToInt()
}
