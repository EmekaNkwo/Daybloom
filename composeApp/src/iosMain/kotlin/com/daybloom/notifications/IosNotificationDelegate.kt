package com.daybloom.notifications

import com.daybloom.domain.usecases.HandleReminderActionUseCase
import com.daybloom.domain.usecases.NotificationAction
import kotlinx.cinterop.ObjCSignatureOverride
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import platform.UserNotifications.UNNotification
import platform.UserNotifications.UNNotificationAction
import platform.UserNotifications.UNNotificationActionOptionNone
import platform.UserNotifications.UNNotificationCategory
import platform.UserNotifications.UNNotificationCategoryOptionCustomDismissAction
import platform.UserNotifications.UNNotificationDefaultActionIdentifier
import platform.UserNotifications.UNNotificationDismissActionIdentifier
import platform.UserNotifications.UNNotificationPresentationOptionBanner
import platform.UserNotifications.UNNotificationPresentationOptionList
import platform.UserNotifications.UNNotificationPresentationOptionSound
import platform.UserNotifications.UNNotificationPresentationOptions
import platform.UserNotifications.UNNotificationResponse
import platform.UserNotifications.UNUserNotificationCenter
import platform.UserNotifications.UNUserNotificationCenterDelegateProtocol
import platform.darwin.NSObject
import kotlin.time.Instant

/**
 * Receives notification presentations and responses. Responses are routed to the same shared use case Android
 * uses, then iOS is told we're done (it may suspend the app right after).
 */
class IosNotificationDelegate(
    private val handleAction: HandleReminderActionUseCase,
) : NSObject(), UNUserNotificationCenterDelegateProtocol {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    @ObjCSignatureOverride
    override fun userNotificationCenter(
        center: UNUserNotificationCenter,
        willPresentNotification: UNNotification,
        withCompletionHandler: (UNNotificationPresentationOptions) -> Unit,
    ) {
        withCompletionHandler(UNNotificationPresentationOptionBanner or UNNotificationPresentationOptionList or UNNotificationPresentationOptionSound)
    }

    @ObjCSignatureOverride
    override fun userNotificationCenter(
        center: UNUserNotificationCenter,
        didReceiveNotificationResponse: UNNotificationResponse,
        withCompletionHandler: () -> Unit,
    ) {
        val info = didReceiveNotificationResponse.notification.request.content.userInfo
        val reminderId = info[IosNotificationContract.KEY_REMINDER_ID] as? String
        val fireAtMs = (info[IosNotificationContract.KEY_FIRE_AT] as? Double)?.toLong()
        val action = when (didReceiveNotificationResponse.actionIdentifier) {
            IosNotificationContract.ACTION_DONE -> NotificationAction.DONE
            IosNotificationContract.ACTION_SNOOZE -> NotificationAction.SNOOZE
            UNNotificationDismissActionIdentifier -> NotificationAction.DISMISS
            UNNotificationDefaultActionIdentifier -> NotificationAction.DELIVERED
            else -> NotificationAction.DELIVERED
        }
        if (reminderId == null) {
            withCompletionHandler()
            return
        }
        scope.launch {
            try {
                handleAction(reminderId, fireAtMs?.let { Instant.fromEpochMilliseconds(it) }, action)
            } finally {
                withCompletionHandler()
            }
        }
    }

    companion object {
        fun registerCategories() {
            val done = UNNotificationAction.actionWithIdentifier(IosNotificationContract.ACTION_DONE, "Done", UNNotificationActionOptionNone)
            val snooze = UNNotificationAction.actionWithIdentifier(IosNotificationContract.ACTION_SNOOZE, "Snooze 10 min", UNNotificationActionOptionNone)
            val category = UNNotificationCategory.categoryWithIdentifier(
                identifier = IosNotificationContract.CATEGORY_REMINDER,
                actions = listOf(done, snooze),
                intentIdentifiers = emptyList<String>(),
                options = UNNotificationCategoryOptionCustomDismissAction,
            )
            UNUserNotificationCenter.currentNotificationCenter().setNotificationCategories(setOf(category))
        }
    }
}
