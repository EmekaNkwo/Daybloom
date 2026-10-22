package com.daybloom.platform

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.SystemClock
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import kotlinx.datetime.TimeZone
import java.io.File
import java.io.IOException
import kotlin.time.Clock
import kotlin.time.Instant

class AndroidDateTimeService(private val context: Context) : PlatformDateTimeService {
    override fun now(): Instant = Clock.System.now()
    override fun timeZone(): TimeZone = TimeZone.currentSystemDefault()

    /** elapsedRealtime keeps counting during deep sleep and ignores wall-clock changes. */
    override fun monotonicMillis(): Long = SystemClock.elapsedRealtime()

    override val timeChanges: Flow<Unit> = callbackFlow {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                trySend(Unit)
            }
        }
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_TIME_CHANGED)
            addAction(Intent.ACTION_TIMEZONE_CHANGED)
            addAction(Intent.ACTION_DATE_CHANGED)
        }
        ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        awaitClose { context.unregisterReceiver(receiver) }
    }
}

class AndroidAppLifecycleService : AppLifecycleService {
    override val events: Flow<AppLifecycleEvent> = callbackFlow {
        val observer = object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) {
                trySend(AppLifecycleEvent.FOREGROUND)
            }

            override fun onStop(owner: LifecycleOwner) {
                trySend(AppLifecycleEvent.BACKGROUND)
            }
        }
        val lifecycle = ProcessLifecycleOwner.get().lifecycle
        lifecycle.addObserver(observer)
        awaitClose { lifecycle.removeObserver(observer) }
    }.flowOn(Dispatchers.Main.immediate)
}

class AndroidFileExporter(private val context: Context) : PlatformFileExporter {
    override suspend fun shareText(fileName: String, mimeType: String, content: String): ExportResult =
        withContext(Dispatchers.IO) {
            try {
                val dir = File(context.cacheDir, EXPORT_DIR).apply { mkdirs() }
                val safeName = fileName.replace(Regex("[^A-Za-z0-9._-]"), "_").take(80)
                val file = File(dir, safeName).apply { writeText(content) }
                val uri = FileProvider.getUriForFile(context, "${context.packageName}.exports", file)
                val send = Intent(Intent.ACTION_SEND).apply {
                    type = mimeType
                    putExtra(Intent.EXTRA_STREAM, uri)
                    putExtra(Intent.EXTRA_TEXT, content)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                val chooser = Intent.createChooser(send, "Share recap").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                withContext(Dispatchers.Main) { context.startActivity(chooser) }
                ExportResult.Shared(file.name)
            } catch (e: IOException) {
                ExportResult.Failed("Couldn't write the file: ${e.message}")
            } catch (e: IllegalArgumentException) {
                ExportResult.Failed("Sharing isn't available right now.")
            }
        }

    private companion object {
        const val EXPORT_DIR = "exports"
    }
}
