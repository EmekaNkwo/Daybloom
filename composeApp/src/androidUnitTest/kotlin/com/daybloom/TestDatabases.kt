package com.daybloom

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.room.Room
import androidx.sqlite.driver.AndroidSQLiteDriver
import com.daybloom.data.database.AppDatabase
import com.daybloom.data.database.Migrations
import com.daybloom.di.sharedModules
import kotlinx.coroutines.Dispatchers
import okio.Path.Companion.toPath
import org.koin.android.ext.koin.androidContext
import org.koin.core.KoinApplication
import org.koin.core.context.startKoin
import org.koin.dsl.module
import java.io.File

/**
 * Robolectric runs on the host JVM, where the bundled SQLite native library for Android ABIs can't load, so
 * tests use the framework SQLite driver. Schema, DAOs, migrations and mappers are identical to production.
 */
object TestDatabases {
    fun inMemory(context: Context): AppDatabase =
        Room.inMemoryDatabaseBuilder<AppDatabase>(context)
            .setDriver(AndroidSQLiteDriver())
            .setQueryCoroutineContext(Dispatchers.IO)
            .build()

    fun onDisk(context: Context, file: File): AppDatabase =
        Room.databaseBuilder<AppDatabase>(context, file.absolutePath)
            .addMigrations(*Migrations.ALL)
            .setDriver(AndroidSQLiteDriver())
            .setQueryCoroutineContext(Dispatchers.IO)
            .build()

    fun dataStore(dir: File): DataStore<Preferences> =
        PreferenceDataStoreFactory.createWithPath(produceFile = { File(dir, "prefs_${System.nanoTime()}.preferences_pb").absolutePath.toPath() })

    /** The real production Koin graph with only storage swapped for test-friendly instances. */
    fun startTestKoin(context: Context, tempDir: File): KoinApplication = startKoin {
        androidContext(context)
        allowOverride(true)
        modules(
            sharedModules() + module {
                single<AppDatabase> { inMemory(context) }
                single<DataStore<Preferences>> { dataStore(tempDir) }
            },
        )
    }
}
