package com.daybloom.di

import android.content.Context
import android.os.Build
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.room.Room
import com.daybloom.data.database.AppDatabase
import com.daybloom.data.database.buildDayBloomDatabase
import com.daybloom.notifications.AndroidNotificationPermissionManager
import com.daybloom.notifications.AndroidNotificationScheduler
import com.daybloom.notifications.NotificationPermissionManager
import com.daybloom.notifications.NotificationScheduler
import com.daybloom.platform.AndroidAppLifecycleService
import com.daybloom.platform.AndroidDateTimeService
import com.daybloom.platform.AndroidFileExporter
import com.daybloom.platform.AppLifecycleService
import com.daybloom.platform.PlatformDateTimeService
import com.daybloom.platform.PlatformFileExporter
import com.daybloom.platform.PlatformInfo
import com.daybloom.platform.PlatformKind
import okio.Path.Companion.toPath
import org.koin.android.ext.koin.androidContext
import org.koin.core.module.Module
import org.koin.dsl.module

actual fun platformModule(): Module = module {
    single<AppDatabase> {
        val context: Context = androidContext()
        Room.databaseBuilder<AppDatabase>(context, context.getDatabasePath(AppDatabase.FILE_NAME).absolutePath)
            .buildDayBloomDatabase()
    }
    single<DataStore<Preferences>> {
        val context: Context = androidContext()
        PreferenceDataStoreFactory.createWithPath(produceFile = { context.filesDir.resolve(PREFERENCES_FILE).absolutePath.toPath() })
    }
    single<PlatformDateTimeService> { AndroidDateTimeService(androidContext()) }
    single<NotificationScheduler> { AndroidNotificationScheduler(androidContext()) }
    single<NotificationPermissionManager> { AndroidNotificationPermissionManager(androidContext()) }
    single<PlatformFileExporter> { AndroidFileExporter(androidContext()) }
    single<AppLifecycleService> { AndroidAppLifecycleService() }
    single { PlatformInfo(PlatformKind.ANDROID, Build.VERSION.RELEASE.orEmpty()) }
}

private const val PREFERENCES_FILE = "daybloom.preferences_pb"
