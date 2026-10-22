package com.daybloom.notifications

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.daybloom.domain.models.NotificationCategory
import com.daybloom.shared.R
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Extras and actions shared by the scheduler and the broadcast receivers. */
internal object NotificationContract {
    const val EXTRA_ID = "daybloom.notification.id"
    const val EXTRA_REMINDER_ID = "daybloom.reminder.id"
    const val EXTRA_FIRE_AT = "daybloom.fire_at"
    const val EXTRA_TITLE = "daybloom.title"
    const val EXTRA_BODY = "daybloom.body"
    const val EXTRA_CATEGORY = "daybloom.category"
    const val EXTRA_ACTION = "daybloom.action"

    const val ACTION_FIRE = "com.daybloom.action.REMINDER_FIRE"
    const val ACTION_RESPOND = "com.daybloom.action.REMINDER_RESPOND"

    /** Single notification id; the per-occurrence string tag distinguishes notifications. */
    const val NOTIFICATION_ID = 7001

    fun uriFor(id: String, kind: String): Uri = Uri.parse("daybloom://$kind/${Uri.encode(id)}")
}

object NotificationChannels {
    fun ensure(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        NotificationCategory.entries.forEach { category ->
            val importance = when (category) {
                NotificationCategory.APPOINTMENTS, NotificationCategory.TASKS, NotificationCategory.BILLS -> NotificationManager.IMPORTANCE_HIGH
                NotificationCategory.HABITS, NotificationCategory.GENERAL -> NotificationManager.IMPORTANCE_DEFAULT
            }
            manager.createNotificationChannel(
                NotificationChannel(category.channelId, category.label, importance).apply {
                    description = "DayBloom ${category.label.lowercase()}"
                },
            )
        }
    }
}

/**
 * Android delivery via AlarmManager. Exact alarms are used when the user has granted exact-alarm access;
 * otherwise inexact allow-while-idle alarms are used and the UI says delivery may be a few minutes late.
 * Each occurrence maps to one PendingIntent identified by a URI built from its stable id, so re-scheduling
 * replaces instead of duplicating.
 */
class AndroidNotificationScheduler(private val context: Context) : NotificationScheduler {
    private val alarmManager: AlarmManager = requireNotNull(context.getSystemService(AlarmManager::class.java))

    override suspend fun capabilities(): SchedulerCapabilities {
        val exact = canScheduleExact()
        return SchedulerCapabilities(
            maxPending = MAX_PENDING,
            exactTiming = exact,
            limitation = if (exact) null else "Exact alarms are off, so reminders may arrive a few minutes late. Battery savers can delay them further.",
        )
    }

    override suspend fun schedule(requests: List<NotificationRequest>) = withContext(Dispatchers.Default) {
        val exact = canScheduleExact()
        requests.forEach { request ->
            val pending = firePendingIntent(request, PendingIntent.FLAG_UPDATE_CURRENT) ?: return@forEach
            val at = request.fireAt.toEpochMilliseconds()
            try {
                if (exact) {
                    alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pending)
                } else {
                    alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pending)
                }
            } catch (e: SecurityException) {
                // Exact-alarm access was revoked between the check and the call.
                alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pending)
            }
        }
    }

    override suspend fun cancel(ids: Collection<String>, removeDelivered: Boolean) = withContext(Dispatchers.Default) {
        val notifications = NotificationManagerCompat.from(context)
        ids.forEach { id ->
            existingPendingIntent(id)?.let {
                alarmManager.cancel(it)
                it.cancel()
            }
            if (removeDelivered) notifications.cancel(id, NotificationContract.NOTIFICATION_ID)
        }
    }

    override suspend fun pendingIds(candidates: Collection<String>): Set<String> =
        withContext(Dispatchers.Default) { candidates.filter { existingPendingIntent(it) != null }.toSet() }

    override suspend fun showNow(request: NotificationRequest) {
        ReminderNotificationBuilder.post(context, request.id, request.reminderId, request.fireAt.toEpochMilliseconds(), request.title, request.body, request.category)
    }

    private fun canScheduleExact(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S || alarmManager.canScheduleExactAlarms()

    private fun fireIntent(id: String): Intent =
        Intent(context, ReminderAlarmReceiver::class.java)
            .setAction(NotificationContract.ACTION_FIRE)
            .setData(NotificationContract.uriFor(id, "fire"))

    private fun firePendingIntent(request: NotificationRequest, flags: Int): PendingIntent? {
        val intent = fireIntent(request.id)
            .putExtra(NotificationContract.EXTRA_ID, request.id)
            .putExtra(NotificationContract.EXTRA_REMINDER_ID, request.reminderId)
            .putExtra(NotificationContract.EXTRA_FIRE_AT, request.fireAt.toEpochMilliseconds())
            .putExtra(NotificationContract.EXTRA_TITLE, request.title)
            .putExtra(NotificationContract.EXTRA_BODY, request.body)
            .putExtra(NotificationContract.EXTRA_CATEGORY, request.category.name)
        return PendingIntent.getBroadcast(context, 0, intent, flags or PendingIntent.FLAG_IMMUTABLE)
    }

    private fun existingPendingIntent(id: String): PendingIntent? =
        PendingIntent.getBroadcast(context, 0, fireIntent(id), PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE)

    private companion object {
        /** AlarmManager allows ~500 alarms per app; stay well below it. */
        const val MAX_PENDING = 200
    }
}

/** Builds and posts reminder notifications with Done / Snooze actions. */
internal object ReminderNotificationBuilder {
    fun post(
        context: Context,
        id: String,
        reminderId: String,
        fireAt: Long,
        title: String,
        body: String,
        category: NotificationCategory,
    ) {
        if (!AndroidNotificationPermissionManager.hasPostPermission(context)) return
        NotificationChannels.ensure(context)
        val launch = context.packageManager.getLaunchIntentForPackage(context.packageName)
            ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val contentIntent = launch?.let {
            PendingIntent.getActivity(context, id.hashCode(), it, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        }
        val notification = NotificationCompat.Builder(context, category.channelId)
            .setSmallIcon(R.drawable.ic_stat_daybloom)
            .setContentTitle(title)
            .setContentText(body.ifBlank { "Reminder from DayBloom" })
            .setCategory(if (category == NotificationCategory.APPOINTMENTS) NotificationCompat.CATEGORY_EVENT else NotificationCompat.CATEGORY_REMINDER)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setWhen(fireAt)
            .setShowWhen(true)
            .setContentIntent(contentIntent)
            .setDeleteIntent(responseIntent(context, id, reminderId, fireAt, "DISMISS"))
            .addAction(0, "Done", responseIntent(context, id, reminderId, fireAt, "DONE"))
            .addAction(0, "Snooze 10 min", responseIntent(context, id, reminderId, fireAt, "SNOOZE"))
            .build()
        try {
            NotificationManagerCompat.from(context).notify(id, NotificationContract.NOTIFICATION_ID, notification)
        } catch (e: SecurityException) {
            // Permission was revoked after the check; nothing else to do.
        }
    }

    private fun responseIntent(context: Context, id: String, reminderId: String, fireAt: Long, action: String): PendingIntent {
        val intent = Intent(context, NotificationActionReceiver::class.java)
            .setAction(NotificationContract.ACTION_RESPOND)
            .setData(NotificationContract.uriFor("$id#$action", "respond"))
            .putExtra(NotificationContract.EXTRA_ID, id)
            .putExtra(NotificationContract.EXTRA_REMINDER_ID, reminderId)
            .putExtra(NotificationContract.EXTRA_FIRE_AT, fireAt)
            .putExtra(NotificationContract.EXTRA_ACTION, action)
        return PendingIntent.getBroadcast(context, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }
}

/**
 * Notification permission on Android 13+ requires an Activity to show the system prompt; MainActivity attaches a
 * [requester] while it is alive. Without one, [requestPermission] reports the current state unchanged.
 */
class AndroidNotificationPermissionManager(private val context: Context) : NotificationPermissionManager {
    override suspend fun status(): NotificationStatus = NotificationStatus(permissionState(), exactState())

    override suspend fun requestPermission(): PermissionState {
        if (permissionState() == PermissionState.GRANTED) return PermissionState.GRANTED
        val launcher = requester ?: return permissionState()
        markAsked(context)
        val granted = launcher()
        return if (granted) PermissionState.GRANTED else permissionState()
    }

    override fun openExactTimingSettings() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val intent = Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:${context.packageName}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            runCatching { context.startActivity(intent) }.onFailure { openNotificationSettings() }
        }
    }

    override fun openNotificationSettings() {
        val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
            .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }

    private fun permissionState(): PermissionState = when {
        hasPostPermission(context) -> PermissionState.GRANTED
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !wasAsked(context) -> PermissionState.NOT_DETERMINED
        else -> PermissionState.DENIED
    }

    private fun exactState(): ExactTimingState {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return ExactTimingState.AVAILABLE
        val alarms = context.getSystemService(AlarmManager::class.java)
        return if (alarms?.canScheduleExactAlarms() == true) ExactTimingState.AVAILABLE else ExactTimingState.RESTRICTED
    }

    companion object {
        /** Set by the hosting Activity; suspends until the user answers the system dialog. */
        @Volatile
        var requester: (suspend () -> Boolean)? = null

        private const val PREFS = "daybloom_permissions"
        private const val KEY_ASKED = "notifications_asked"

        fun hasPostPermission(context: Context): Boolean {
            val runtimeGranted = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
            return runtimeGranted && NotificationManagerCompat.from(context).areNotificationsEnabled()
        }

        fun wasAsked(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_ASKED, false)

        fun markAsked(context: Context) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_ASKED, true).apply()
        }

        /** Helper for activities: bridges an ActivityResult callback to a suspend function. */
        fun deferredResult(): CompletableDeferred<Boolean> = CompletableDeferred()
    }
}
