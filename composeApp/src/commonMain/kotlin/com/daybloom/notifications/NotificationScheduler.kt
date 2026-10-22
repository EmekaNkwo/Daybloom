package com.daybloom.notifications

import com.daybloom.domain.models.NotificationCategory
import kotlin.time.Instant

/** A platform-neutral request to show one notification at one instant. */
data class NotificationRequest(
    /** Stable identifier; scheduling the same id twice replaces rather than duplicates. */
    val id: String,
    val reminderId: String,
    val fireAt: Instant,
    val title: String,
    val body: String,
    val category: NotificationCategory,
)

data class SchedulerCapabilities(
    /** Maximum number of pending notifications the OS lets an app hold (iOS: 64). */
    val maxPending: Int,
    /** Whether the platform can deliver at the exact minute (Android exact-alarm access, iOS calendar triggers). */
    val exactTiming: Boolean,
    /** Human-readable limitation to show in the UI, if any. */
    val limitation: String? = null,
)

/**
 * The OS delivery mechanism for reminders. Implementations must be idempotent per request id and must not
 * rely on the app process staying alive.
 */
interface NotificationScheduler {
    suspend fun capabilities(): SchedulerCapabilities

    suspend fun schedule(requests: List<NotificationRequest>)

    /**
     * Cancels pending deliveries. With [removeDelivered] (the default) a notification already on screen is
     * removed too; pass false for occurrences that may have fired, so an unseen notification isn't taken away.
     */
    suspend fun cancel(ids: Collection<String>, removeDelivered: Boolean = true)

    /**
     * Which of [candidates] the OS currently holds, plus any other DayBloom notifications it can enumerate
     * (iOS lists everything pending; Android can only probe the candidates it is given).
     */
    suspend fun pendingIds(candidates: Collection<String>): Set<String>

    /** Show a notification right now (used for snoozed or overdue reminders discovered on launch). */
    suspend fun showNow(request: NotificationRequest)
}
