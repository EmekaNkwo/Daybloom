package com.daybloom.di

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.room.Room
import com.daybloom.data.database.AppDatabase
import com.daybloom.data.database.buildDayBloomDatabase
import com.daybloom.notifications.IosNotificationPermissionManager
import com.daybloom.notifications.IosNotificationScheduler
import com.daybloom.notifications.NotificationPermissionManager
import com.daybloom.notifications.NotificationScheduler
import com.daybloom.platform.AppLifecycleService
import com.daybloom.platform.IosAppLifecycleService
import com.daybloom.platform.IosDateTimeService
import com.daybloom.platform.IosFileExporter
import com.daybloom.platform.PlatformDateTimeService
import com.daybloom.platform.PlatformFileExporter
import com.daybloom.platform.PlatformInfo
import com.daybloom.platform.PlatformKind
import com.daybloom.platform.documentsDirectory
import okio.Path.Companion.toPath
import org.koin.core.module.Module
import org.koin.dsl.module
import platform.UIKit.UIDevice

actual fun platformModule(): Module = module {
    single<AppDatabase> {
        Room.databaseBuilder<AppDatabase>(name = "${documentsDirectory()}/${AppDatabase.FILE_NAME}").buildDayBloomDatabase()
    }
    single<DataStore<Preferences>> {
        PreferenceDataStoreFactory.createWithPath(produceFile = { "${documentsDirectory()}/daybloom.preferences_pb".toPath() })
    }
    single<PlatformDateTimeService> { IosDateTimeService() }
    single<NotificationScheduler> { IosNotificationScheduler() }
    single<NotificationPermissionManager> { IosNotificationPermissionManager() }
    single<PlatformFileExporter> { IosFileExporter() }
    single<AppLifecycleService> { IosAppLifecycleService() }
    single { PlatformInfo(PlatformKind.IOS, UIDevice.currentDevice.systemVersion) }
}
