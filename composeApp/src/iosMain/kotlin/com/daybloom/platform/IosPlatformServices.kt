package com.daybloom.platform

import kotlinx.cinterop.BetaInteropApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.withContext
import kotlinx.datetime.TimeZone
import platform.Foundation.NSFileManager
import platform.Foundation.NSNotificationCenter
import platform.Foundation.NSOperationQueue
import platform.Foundation.NSString
import platform.Foundation.NSSystemClockDidChangeNotification
import platform.Foundation.NSSystemTimeZoneDidChangeNotification
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSTimeZone
import platform.Foundation.NSURL
import platform.Foundation.NSUTF8StringEncoding
import platform.Foundation.create
import platform.Foundation.resetSystemTimeZone
import platform.Foundation.writeToURL
import platform.UIKit.UIActivityViewController
import platform.UIKit.UIApplication
import platform.UIKit.UIApplicationDidBecomeActiveNotification
import platform.UIKit.UIApplicationDidEnterBackgroundNotification
import platform.UIKit.UIApplicationSignificantTimeChangeNotification
import platform.UIKit.UIViewController
import platform.UIKit.UIWindow
import platform.UIKit.UIWindowScene
import platform.UIKit.popoverPresentationController
import platform.posix.CLOCK_MONOTONIC
import platform.posix.clock_gettime_nsec_np
import kotlin.time.Clock
import kotlin.time.Instant

class IosDateTimeService : PlatformDateTimeService {
    override fun now(): Instant = Clock.System.now()
    override fun timeZone(): TimeZone = TimeZone.currentSystemDefault()

    /** Darwin's CLOCK_MONOTONIC keeps counting while the device sleeps and ignores wall-clock changes. */
    override fun monotonicMillis(): Long = (clock_gettime_nsec_np(CLOCK_MONOTONIC.toUInt()) / 1_000_000u).toLong()

    override val timeChanges: Flow<Unit> = callbackFlow {
        val center = NSNotificationCenter.defaultCenter
        val names = listOf(
            NSSystemTimeZoneDidChangeNotification,
            NSSystemClockDidChangeNotification,
            UIApplicationSignificantTimeChangeNotification,
        )
        val observers = names.map { name ->
            center.addObserverForName(name, null, NSOperationQueue.mainQueue) { _ ->
                // NSTimeZone caches the system zone; reset so the new zone is picked up.
                NSTimeZone.resetSystemTimeZone()
                trySend(Unit)
            }
        }
        awaitClose { observers.forEach { center.removeObserver(it) } }
    }
}

class IosAppLifecycleService : AppLifecycleService {
    override val events: Flow<AppLifecycleEvent> = callbackFlow {
        val center = NSNotificationCenter.defaultCenter
        val active = center.addObserverForName(UIApplicationDidBecomeActiveNotification, null, NSOperationQueue.mainQueue) { _ ->
            trySend(AppLifecycleEvent.FOREGROUND)
        }
        val background = center.addObserverForName(UIApplicationDidEnterBackgroundNotification, null, NSOperationQueue.mainQueue) { _ ->
            trySend(AppLifecycleEvent.BACKGROUND)
        }
        awaitClose {
            center.removeObserver(active)
            center.removeObserver(background)
        }
    }
}

class IosFileExporter : PlatformFileExporter {
    @OptIn(BetaInteropApi::class)
    override suspend fun shareText(fileName: String, mimeType: String, content: String): ExportResult =
        withContext(Dispatchers.Main) {
            val safeName = fileName.replace(Regex("[^A-Za-z0-9._-]"), "_").take(80)
            val url = NSURL.fileURLWithPath(NSTemporaryDirectory() + safeName)
            val text = NSString.create(string = content)
            val written = text.writeToURL(url, atomically = true, encoding = NSUTF8StringEncoding, error = null)
            if (!written) return@withContext ExportResult.Failed("Couldn't write the file.")
            val presenter = topViewController() ?: return@withContext ExportResult.Failed("Nothing to present the share sheet from.")
            val sheet = UIActivityViewController(activityItems = listOf(url), applicationActivities = null)
            // iPad requires an anchor for the popover.
            sheet.popoverPresentationController?.sourceView = presenter.view
            presenter.presentViewController(sheet, animated = true, completion = null)
            ExportResult.Shared(safeName)
        }

    private fun topViewController(): UIViewController? {
        val window = UIApplication.sharedApplication.connectedScenes
            .filterIsInstance<UIWindowScene>()
            .flatMap { scene -> scene.windows.filterIsInstance<UIWindow>() }
            .firstOrNull { it.isKeyWindow() }
        var top = window?.rootViewController
        while (top?.presentedViewController != null) top = top.presentedViewController
        return top
    }
}

internal fun documentsDirectory(): String {
    val url = NSFileManager.defaultManager.URLsForDirectory(
        directory = platform.Foundation.NSDocumentDirectory,
        inDomains = platform.Foundation.NSUserDomainMask,
    ).firstOrNull() as? NSURL
    return requireNotNull(url?.path) { "Documents directory unavailable" }
}
