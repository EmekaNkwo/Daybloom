package com.daybloom.notifications

import kotlinx.coroutines.suspendCancellableCoroutine
import platform.Foundation.NSCalendar
import platform.Foundation.NSCalendarUnitDay
import platform.Foundation.NSCalendarUnitHour
import platform.Foundation.NSCalendarUnitMinute
import platform.Foundation.NSCalendarUnitMonth
import platform.Foundation.NSCalendarUnitSecond
import platform.Foundation.NSCalendarUnitYear
import platform.Foundation.NSDate
import platform.Foundation.NSLog
import platform.Foundation.NSURL
import platform.Foundation.dateWithTimeIntervalSince1970
import platform.UIKit.UIApplication
import platform.UIKit.UIApplicationOpenSettingsURLString
import platform.UserNotifications.UNAuthorizationOptionAlert
import platform.UserNotifications.UNAuthorizationOptionBadge
import platform.UserNotifications.UNAuthorizationOptionSound
import platform.UserNotifications.UNAuthorizationStatusAuthorized
import platform.UserNotifications.UNAuthorizationStatusDenied
import platform.UserNotifications.UNAuthorizationStatusEphemeral
import platform.UserNotifications.UNAuthorizationStatusNotDetermined
import platform.UserNotifications.UNAuthorizationStatusProvisional
import platform.UserNotifications.UNCalendarNotificationTrigger
import platform.UserNotifications.UNMutableNotificationContent
import platform.UserNotifications.UNNotificationRequest
import platform.UserNotifications.UNNotificationSettings
import platform.UserNotifications.UNNotificationSound
import platform.UserNotifications.UNUserNotificationCenter
import kotlin.coroutines.resume

internal object IosNotificationContract {
    const val CATEGORY_REMINDER = "DAYBLOOM_REMINDER"
    const val ACTION_DONE = "DAYBLOOM_DONE"
    const val ACTION_SNOOZE = "DAYBLOOM_SNOOZE"
    const val KEY_REMINDER_ID = "reminderId"
    const val KEY_FIRE_AT = "fireAt"
}

/**
 * iOS delivery through UNUserNotificationCenter. Each occurrence becomes one calendar-trigger request whose
 * identifier is the stable occurrence id (so adding it again replaces it). iOS keeps at most 64 pending
 * requests per app; the reconciler fills a rolling window under [MAX_PENDING] and tops it up on every launch,
 * foreground and notification response.
 */
class IosNotificationScheduler : NotificationScheduler {
    private val center get() = UNUserNotificationCenter.currentNotificationCenter()

    override suspend fun capabilities() = SchedulerCapabilities(
        maxPending = MAX_PENDING,
        exactTiming = true,
        limitation = "iOS holds up to 64 scheduled notifications per app. DayBloom keeps the next two weeks scheduled and refreshes whenever you open the app.",
    )

    override suspend fun schedule(requests: List<NotificationRequest>) {
        // A rejected request is logged rather than thrown: it simply isn't pending with the OS, so the next
        // reconcile sees it missing and schedules it again.
        requests.forEach { request ->
            add(request, immediate = false)?.let { NSLog("%@", "DayBloom: couldn't schedule ${request.id}: $it") }
        }
    }

    /** Schedules one request and throws if iOS rejects it (e.g. notifications not allowed). */
    internal suspend fun scheduleOrThrow(request: NotificationRequest) {
        add(request, immediate = false)?.let { throw IllegalStateException(it) }
    }

    override suspend fun cancel(ids: Collection<String>, removeDelivered: Boolean) {
        if (ids.isEmpty()) return
        center.removePendingNotificationRequestsWithIdentifiers(ids.toList())
        if (removeDelivered) center.removeDeliveredNotificationsWithIdentifiers(ids.toList())
    }

    override suspend fun pendingIds(candidates: Collection<String>): Set<String> =
        suspendCancellableCoroutine { cont ->
            center.getPendingNotificationRequestsWithCompletionHandler { requests ->
                val ids = requests.orEmpty().mapNotNull { (it as? UNNotificationRequest)?.identifier }.toSet()
                cont.resume(ids)
            }
        }

    override suspend fun showNow(request: NotificationRequest) {
        add(request, immediate = true)?.let { throw IllegalStateException(it) }
    }

    /** Adds the request to the notification center; returns iOS's error description, or null on success. */
    private suspend fun add(request: NotificationRequest, immediate: Boolean): String? {
        val content = UNMutableNotificationContent().apply {
            setTitle(request.title)
            setBody(request.body.ifBlank { "Reminder from DayBloom" })
            setSound(UNNotificationSound.defaultSound)
            setCategoryIdentifier(IosNotificationContract.CATEGORY_REMINDER)
            setThreadIdentifier(request.category.channelId)
            setUserInfo(
                mapOf(
                    IosNotificationContract.KEY_REMINDER_ID to request.reminderId,
                    IosNotificationContract.KEY_FIRE_AT to request.fireAt.toEpochMilliseconds().toDouble(),
                ),
            )
        }
        val trigger = if (immediate) {
            null
        } else {
            val date = NSDate.dateWithTimeIntervalSince1970(request.fireAt.toEpochMilliseconds() / 1000.0)
            val units = NSCalendarUnitYear or NSCalendarUnitMonth or NSCalendarUnitDay or
                NSCalendarUnitHour or NSCalendarUnitMinute or NSCalendarUnitSecond
            val components = NSCalendar.currentCalendar.components(units, fromDate = date)
            UNCalendarNotificationTrigger.triggerWithDateMatchingComponents(components, repeats = false)
        }
        val unRequest = UNNotificationRequest.requestWithIdentifier(request.id, content, trigger)
        return suspendCancellableCoroutine { cont ->
            center.addNotificationRequest(unRequest) { error -> cont.resume(error?.localizedDescription) }
        }
    }

    private companion object {
        /** iOS limit is 64; keep headroom for immediately-shown notifications. */
        const val MAX_PENDING = 60
    }
}

class IosNotificationPermissionManager : NotificationPermissionManager {
    private val center get() = UNUserNotificationCenter.currentNotificationCenter()

    override suspend fun status(): NotificationStatus {
        val settings = suspendCancellableCoroutine<UNNotificationSettings?> { cont ->
            center.getNotificationSettingsWithCompletionHandler { cont.resume(it) }
        }
        val state = when (settings?.authorizationStatus) {
            UNAuthorizationStatusAuthorized -> PermissionState.GRANTED
            UNAuthorizationStatusProvisional, UNAuthorizationStatusEphemeral -> PermissionState.PROVISIONAL
            UNAuthorizationStatusNotDetermined -> PermissionState.NOT_DETERMINED
            UNAuthorizationStatusDenied -> PermissionState.DENIED
            else -> PermissionState.DENIED
        }
        return NotificationStatus(state, ExactTimingState.NOT_APPLICABLE)
    }

    override suspend fun requestPermission(): PermissionState {
        suspendCancellableCoroutine { cont ->
            center.requestAuthorizationWithOptions(
                UNAuthorizationOptionAlert or UNAuthorizationOptionSound or UNAuthorizationOptionBadge,
            ) { _, _ -> cont.resume(Unit) }
        }
        return status().permission
    }

    override fun openExactTimingSettings() = openNotificationSettings()

    override fun openNotificationSettings() {
        val url = NSURL.URLWithString(UIApplicationOpenSettingsURLString) ?: return
        UIApplication.sharedApplication.openURL(url, options = emptyMap<Any?, Any>(), completionHandler = null)
    }
}
