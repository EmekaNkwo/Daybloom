package com.daybloom.android

import android.app.Application
import com.daybloom.di.initKoin
import com.daybloom.notifications.NotificationChannels
import org.koin.android.ext.koin.androidContext

class DayBloomApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        NotificationChannels.ensure(this)
        initKoin { androidContext(this@DayBloomApplication) }
    }
}
