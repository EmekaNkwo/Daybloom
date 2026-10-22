package com.daybloom.app

import androidx.compose.ui.window.ComposeUIViewController
import com.daybloom.di.initKoin
import com.daybloom.domain.usecases.HandleReminderActionUseCase
import com.daybloom.notifications.IosNotificationDelegate
import org.koin.mp.KoinPlatform
import platform.UIKit.UIViewController
import platform.UserNotifications.UNUserNotificationCenter

/** Called once from the Swift app initializer, before any UI or notification can arrive. */
object IosAppBootstrap {
    // UNUserNotificationCenter holds its delegate weakly, so we keep the strong reference here.
    private var notificationDelegate: IosNotificationDelegate? = null

    fun start() {
        if (notificationDelegate != null) return
        initKoin()
        IosNotificationDelegate.registerCategories()
        val delegate = IosNotificationDelegate(KoinPlatform.getKoin().get<HandleReminderActionUseCase>())
        UNUserNotificationCenter.currentNotificationCenter().delegate = delegate
        notificationDelegate = delegate
    }
}

@Suppress("FunctionName", "unused") // Called from Swift.
fun MainViewController(): UIViewController = ComposeUIViewController { App() }
