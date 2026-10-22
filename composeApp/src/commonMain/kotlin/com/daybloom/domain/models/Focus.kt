package com.daybloom.domain.models

import kotlinx.datetime.LocalDate
import kotlin.time.Instant

enum class FocusStatus { RUNNING, PAUSED, COMPLETED, INTERRUPTED }

data class FocusSession(
    val id: String,
    val taskId: String?,
    val category: TaskCategory?,
    val plannedMinutes: Int,
    val date: LocalDate,
    val startedAt: Instant,
    val endedAt: Instant? = null,
    /** Focused time accumulated before the current running segment. */
    val accumulatedSeconds: Long = 0,
    /** Wall-clock start of the current running segment, null while paused or finished. */
    val segmentStartedAt: Instant? = null,
    /** Platform monotonic clock reading at segment start; survives wall-clock changes. */
    val segmentStartedMonotonicMs: Long? = null,
    val pauseCount: Int = 0,
    val status: FocusStatus = FocusStatus.RUNNING,
) {
    val isActive: Boolean get() = status == FocusStatus.RUNNING || status == FocusStatus.PAUSED
    val plannedSeconds: Long get() = plannedMinutes * 60L
}
