package com.daybloom.notifications

import com.daybloom.domain.models.ReminderOccurrence
import com.daybloom.domain.repositories.PreferencesRepository
import com.daybloom.domain.repositories.ReminderRepository
import com.daybloom.domain.repositories.ScheduledRecord
import com.daybloom.domain.services.ReminderOccurrenceCalculator
import com.daybloom.platform.PlatformDateTimeService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.time.Duration.Companion.days
import kotlin.time.Instant

enum class ReconcileReason {
    /** App launched or returned to the foreground. */
    APP_START,
    /** A reminder (or something that owns one) was created, edited or deleted. */
    REMINDER_CHANGED,
    /** Device time or time zone changed: every wall-clock trigger must be recomputed. */
    TIME_CHANGED,
    /** Device rebooted or app updated: the OS dropped our alarms. */
    SYSTEM_RESET,
    /** A notification was delivered or acted on: top up the scheduling window. */
    NOTIFICATION_HANDLED,
    /** Notification permission changed. */
    PERMISSION_CHANGED,
}

data class ReconcileResult(
    val scheduled: Int,
    val cancelled: Int,
    val unchanged: Int,
    val totalPending: Int,
    val permissionGranted: Boolean,
    val at: Instant,
)

/** What the reminders screen shows about delivery — honest about permission and timing limits. */
data class ReminderDeliveryStatus(
    val status: NotificationStatus? = null,
    val capabilities: SchedulerCapabilities? = null,
    val lastResult: ReconcileResult? = null,
)

/**
 * Keeps the OS notification schedule in sync with the persisted reminder definitions.
 *
 * The database is the source of truth. Each run computes the deliveries that should exist in a rolling window
 * (bounded by the platform's pending-notification limit), diffs them against what was previously handed to
 * the OS (and what the OS reports), then schedules only what is missing or changed and cancels only what is
 * stale. Occurrence ids are deterministic, so re-running never duplicates a notification.
 */
class ReminderReconciler(
    private val reminders: ReminderRepository,
    private val scheduler: NotificationScheduler,
    private val permissions: NotificationPermissionManager,
    private val preferences: PreferencesRepository,
    private val clock: PlatformDateTimeService,
) {
    private val mutex = Mutex()
    private val _status = MutableStateFlow(ReminderDeliveryStatus())
    val status: StateFlow<ReminderDeliveryStatus> = _status.asStateFlow()

    suspend fun reconcile(reason: ReconcileReason): ReconcileResult = mutex.withLock {
        val now = clock.now()
        val notificationStatus = permissions.status()
        val capabilities = scheduler.capabilities()
        val persisted = reminders.getScheduled()

        if (!notificationStatus.canDeliver) {
            // Without permission nothing can be delivered; clear our record so a later grant schedules afresh.
            if (persisted.isNotEmpty()) {
                scheduler.cancel(persisted.map { it.id })
                reminders.clearScheduled()
            }
            val result = ReconcileResult(0, persisted.size, 0, 0, false, now)
            _status.value = ReminderDeliveryStatus(notificationStatus, capabilities, result)
            return result
        }

        val desired = desiredOccurrences(now, capabilities.maxPending)
        val desiredById = desired.associateBy { it.key }
        val persistedById = persisted.associateBy { it.id }

        val forceAll = reason == ReconcileReason.TIME_CHANGED || reason == ReconcileReason.SYSTEM_RESET
        val osPending = if (forceAll) emptySet() else scheduler.pendingIds(persistedById.keys + desiredById.keys)

        // Occurrences whose time has passed have fired, or (inexact alarms) are about to. Their notification may be
        // on screen, and cancelling the id would remove it, so they're dropped from our records without being
        // cancelled. Only when their reminder is gone is a still-waiting alarm cancelled — never a shown notification.
        val pastRecords = persisted.filter { it.fireAt <= now }
        val fired = pastRecords.map { it.id }.toSet()
        val activeIds = reminders.getActive().map { it.id }.toSet()
        val firedOrphans = pastRecords.filter { it.reminderId !in activeIds }.map { it.id }.toSet()
        val stale = persisted.filter { record ->
            val wanted = desiredById[record.id]
            record.id !in fired && (wanted == null || contentHash(wanted) != record.contentHash)
        }.map { it.id }.toSet()
        // Orphans: the OS holds a reminder occurrence we no longer want (e.g. after a data reset). Only ids in the
        // occurrence-key format are touched, so other notifications the app shows are left alone.
        val orphans = osPending.filter { isOccurrenceKey(it) && it !in desiredById && it !in persistedById }.toSet()

        val toSchedule = desired.filter { occ ->
            val record = persistedById[occ.key]
            forceAll || record == null || record.contentHash != contentHash(occ) || occ.key !in osPending
        }

        val toCancel = stale + orphans
        if (toCancel.isNotEmpty()) scheduler.cancel(toCancel)
        if (firedOrphans.isNotEmpty()) scheduler.cancel(firedOrphans, removeDelivered = false)
        if (forceAll) {
            val superseded = persistedById.keys - desiredById.keys - fired
            if (superseded.isNotEmpty()) scheduler.cancel(superseded)
        }
        if (toSchedule.isNotEmpty()) scheduler.schedule(toSchedule.map { it.toRequest() })

        reminders.removeScheduled(stale + (persistedById.keys - desiredById.keys))
        reminders.recordScheduled(
            toSchedule.map { ScheduledRecord(it.key, it.reminderId, it.fireAt, contentHash(it)) },
            now,
        )

        val result = ReconcileResult(
            scheduled = toSchedule.size,
            cancelled = toCancel.size,
            unchanged = desired.size - toSchedule.size,
            totalPending = desired.size,
            permissionGranted = true,
            at = now,
        )
        _status.value = ReminderDeliveryStatus(notificationStatus, capabilities, result)
        result
    }

    /** Refreshes permission/capability info without touching the schedule. */
    suspend fun refreshStatus() {
        _status.value = _status.value.copy(status = permissions.status(), capabilities = scheduler.capabilities())
    }

    private suspend fun desiredOccurrences(now: Instant, maxPending: Int): List<ReminderOccurrence> {
        val quietHours = preferences.current().quietHours
        val windowEnd = now + WINDOW_DAYS.days
        return reminders.getActive()
            .flatMap { ReminderOccurrenceCalculator.occurrences(it, now, windowEnd, clock.timeZone(), quietHours) }
            .sortedBy { it.fireAt }
            .take(maxPending.coerceAtLeast(1))
    }

    private fun ReminderOccurrence.toRequest() = NotificationRequest(key, reminderId, fireAt, title, body, category)

    companion object {
        /** How far ahead deliveries are handed to the OS. Re-filled on every app start and notification. */
        const val WINDOW_DAYS = 14

        /** Occurrence keys look like "reminderId@epochMillis" (see [ReminderOccurrence.key]). */
        fun isOccurrenceKey(id: String): Boolean = id.substringAfterLast('@', "").toLongOrNull() != null

        fun contentHash(occurrence: ReminderOccurrence): String =
            listOf(occurrence.title, occurrence.body, occurrence.category.name, occurrence.fireAt.toEpochMilliseconds())
                .joinToString("|")
                .hashCode()
                .toString(16)
    }
}
