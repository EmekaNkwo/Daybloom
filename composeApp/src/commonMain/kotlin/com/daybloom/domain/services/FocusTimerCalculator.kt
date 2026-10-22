package com.daybloom.domain.services

import com.daybloom.domain.models.FocusSession
import com.daybloom.domain.models.FocusStatus
import kotlin.time.Instant

/**
 * Elapsed-time math for focus sessions.
 *
 * A running segment is measured with the platform monotonic clock, which ignores wall-clock and time-zone
 * changes. If the monotonic reading went backwards (device rebooted), the wall clock is used instead, clamped
 * to [0, planned + grace] so a changed clock can never produce negative or absurd focus time.
 */
object FocusTimerCalculator {
    /** Extra time allowed beyond the plan before a forgotten session stops counting. */
    const val GRACE_SECONDS = 15 * 60L

    fun focusedSeconds(session: FocusSession, nowMonotonicMs: Long, now: Instant): Long {
        if (session.status != FocusStatus.RUNNING) return session.accumulatedSeconds
        val segment = segmentSeconds(session, nowMonotonicMs, now)
        return (session.accumulatedSeconds + segment).coerceAtMost(session.plannedSeconds + GRACE_SECONDS)
    }

    fun remainingSeconds(session: FocusSession, nowMonotonicMs: Long, now: Instant): Long =
        (session.plannedSeconds - focusedSeconds(session, nowMonotonicMs, now)).coerceAtLeast(0)

    fun isFinished(session: FocusSession, nowMonotonicMs: Long, now: Instant): Boolean =
        remainingSeconds(session, nowMonotonicMs, now) == 0L

    private fun segmentSeconds(session: FocusSession, nowMonotonicMs: Long, now: Instant): Long {
        val monoStart = session.segmentStartedMonotonicMs
        if (monoStart != null && nowMonotonicMs >= monoStart) {
            return (nowMonotonicMs - monoStart) / 1000
        }
        val wallStart = session.segmentStartedAt ?: return 0
        val wall = (now - wallStart).inWholeSeconds
        return wall.coerceIn(0, session.plannedSeconds + GRACE_SECONDS)
    }

    fun pause(session: FocusSession, nowMonotonicMs: Long, now: Instant): FocusSession {
        if (session.status != FocusStatus.RUNNING) return session
        return session.copy(
            accumulatedSeconds = focusedSeconds(session, nowMonotonicMs, now),
            segmentStartedAt = null,
            segmentStartedMonotonicMs = null,
            pauseCount = session.pauseCount + 1,
            status = FocusStatus.PAUSED,
        )
    }

    fun resume(session: FocusSession, nowMonotonicMs: Long, now: Instant): FocusSession {
        if (session.status != FocusStatus.PAUSED) return session
        return session.copy(segmentStartedAt = now, segmentStartedMonotonicMs = nowMonotonicMs, status = FocusStatus.RUNNING)
    }

    fun finish(session: FocusSession, nowMonotonicMs: Long, now: Instant, completed: Boolean): FocusSession {
        if (!session.isActive) return session
        val focused = focusedSeconds(session, nowMonotonicMs, now)
        return session.copy(
            // A session that ran out is credited with its plan: time after the timer hit zero (e.g. the app
            // only noticed later) wasn't focus time.
            accumulatedSeconds = if (completed) focused.coerceAtMost(session.plannedSeconds) else focused,
            segmentStartedAt = null,
            segmentStartedMonotonicMs = null,
            endedAt = now,
            status = if (completed) FocusStatus.COMPLETED else FocusStatus.INTERRUPTED,
        )
    }
}
