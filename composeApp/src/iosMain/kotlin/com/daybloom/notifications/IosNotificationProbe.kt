package com.daybloom.notifications

import com.daybloom.domain.models.NotificationCategory
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds

/**
 * Plain-typed entry points into [IosNotificationScheduler] for the app-hosted XCTest suite
 * (iosApp/iosAppTests), which can't easily construct Kotlin value types from Swift.
 */
object IosNotificationProbe {
    private val scheduler = IosNotificationScheduler()

    /** Throws if iOS rejects the request, so tests see the real reason instead of a missing pending entry. */
    suspend fun schedule(id: String, title: String, secondsFromNow: Long) {
        scheduler.scheduleOrThrow(
            NotificationRequest(id, "probe", Clock.System.now() + secondsFromNow.seconds, title, "DayBloom test", NotificationCategory.GENERAL),
        )
    }

    suspend fun pendingIds(): List<String> = scheduler.pendingIds(emptyList()).sorted()

    suspend fun cancel(id: String) = scheduler.cancel(listOf(id))

    /** What the reminder reconciler does for a notification that has already fired: forget the alarm, keep what's on screen. */
    suspend fun cancelKeepingDelivered(id: String) = scheduler.cancel(listOf(id), removeDelivered = false)

    suspend fun maxPending(): Int = scheduler.capabilities().maxPending
}
