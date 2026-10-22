package com.daybloom.domain.services

data class PlanCapacity(
    val availableMinutes: Int?,
    val taskMinutes: Int,
    val appointmentMinutes: Int,
    val breakMinutes: Int,
    /** Planned tasks with no estimate; counted using [PlanCapacityCalculator.DEFAULT_ESTIMATE_MINUTES]. */
    val unestimatedTasks: Int,
) {
    val plannedMinutes: Int get() = taskMinutes + appointmentMinutes + breakMinutes
    val overByMinutes: Int get() = availableMinutes?.let { (plannedMinutes - it).coerceAtLeast(0) } ?: 0
    val isOverCapacity: Boolean get() = overByMinutes > 0
    val loadFraction: Float
        get() = availableMinutes?.takeIf { it > 0 }?.let { plannedMinutes.toFloat() / it } ?: 0f
}

object PlanCapacityCalculator {
    const val DEFAULT_ESTIMATE_MINUTES = 30

    fun calculate(
        availableMinutes: Int?,
        taskEstimates: List<Int?>,
        appointmentMinutes: List<Int>,
        breakMinutes: Int,
    ): PlanCapacity = PlanCapacity(
        availableMinutes = availableMinutes,
        taskMinutes = taskEstimates.sumOf { it ?: DEFAULT_ESTIMATE_MINUTES },
        appointmentMinutes = appointmentMinutes.sum(),
        breakMinutes = breakMinutes.coerceAtLeast(0),
        unestimatedTasks = taskEstimates.count { it == null },
    )

    fun formatMinutes(minutes: Int): String {
        val h = minutes / 60
        val m = minutes % 60
        return when {
            h == 0 -> "$m minutes"
            m == 0 -> "$h hour${if (h == 1) "" else "s"}"
            else -> "$h hour${if (h == 1) "" else "s"} $m minutes"
        }
    }
}
