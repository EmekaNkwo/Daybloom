package com.daybloom.notifications

import android.app.AlarmManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationManagerCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.daybloom.domain.models.NotificationCategory
import com.daybloom.domain.usecases.HandleReminderActionUseCase
import com.daybloom.domain.usecases.NotificationAction
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import kotlin.time.Instant

/** Fired by AlarmManager at a reminder's time: posts the notification, then tops up the schedule. */
class ReminderAlarmReceiver : BroadcastReceiver(), KoinComponent {
    private val handleAction: HandleReminderActionUseCase by inject()

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != NotificationContract.ACTION_FIRE) return
        val id = intent.getStringExtra(NotificationContract.EXTRA_ID) ?: return
        val reminderId = intent.getStringExtra(NotificationContract.EXTRA_REMINDER_ID) ?: return
        val fireAt = intent.getLongExtra(NotificationContract.EXTRA_FIRE_AT, System.currentTimeMillis())
        val category = NotificationCategory.entries.firstOrNull { it.name == intent.getStringExtra(NotificationContract.EXTRA_CATEGORY) }
            ?: NotificationCategory.GENERAL
        ReminderNotificationBuilder.post(
            context = context,
            id = id,
            reminderId = reminderId,
            fireAt = fireAt,
            title = intent.getStringExtra(NotificationContract.EXTRA_TITLE).orEmpty(),
            body = intent.getStringExtra(NotificationContract.EXTRA_BODY).orEmpty(),
            category = category,
        )
        goAsync { handleAction(reminderId, Instant.fromEpochMilliseconds(fireAt), NotificationAction.DELIVERED) }
    }
}

/** Handles the Done / Snooze buttons and swipe-to-dismiss on reminder notifications. */
class NotificationActionReceiver : BroadcastReceiver(), KoinComponent {
    private val handleAction: HandleReminderActionUseCase by inject()

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != NotificationContract.ACTION_RESPOND) return
        val id = intent.getStringExtra(NotificationContract.EXTRA_ID) ?: return
        val reminderId = intent.getStringExtra(NotificationContract.EXTRA_REMINDER_ID) ?: return
        val fireAt = Instant.fromEpochMilliseconds(intent.getLongExtra(NotificationContract.EXTRA_FIRE_AT, System.currentTimeMillis()))
        val action = when (intent.getStringExtra(NotificationContract.EXTRA_ACTION)) {
            "DONE" -> NotificationAction.DONE
            "SNOOZE" -> NotificationAction.SNOOZE
            else -> NotificationAction.DISMISS
        }
        NotificationManagerCompat.from(context).cancel(id, NotificationContract.NOTIFICATION_ID)
        goAsync { handleAction(reminderId, fireAt, action) }
    }
}

/**
 * Re-schedules after events that clear or invalidate alarms: reboot, app update, clock or time-zone change and
 * exact-alarm permission changes. Work is handed to WorkManager so it survives the receiver's short lifetime.
 */
class SystemEventReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val reason = when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_LOCKED_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED -> ReconcileReason.SYSTEM_RESET
            Intent.ACTION_TIME_CHANGED, Intent.ACTION_TIMEZONE_CHANGED -> ReconcileReason.TIME_CHANGED
            AlarmManager.ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED -> ReconcileReason.SYSTEM_RESET
            else -> return
        }
        ReconcileWorker.enqueue(context, reason)
    }
}

class ReconcileWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params), KoinComponent {
    private val reconciler: ReminderReconciler by inject()

    override suspend fun doWork(): Result {
        val reason = ReconcileReason.entries.firstOrNull { it.name == inputData.getString(KEY_REASON) } ?: ReconcileReason.APP_START
        return try {
            NotificationChannels.ensure(applicationContext)
            reconciler.reconcile(reason)
            Result.success()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (runAttemptCount < MAX_ATTEMPTS) Result.retry() else Result.failure()
        }
    }

    companion object {
        private const val KEY_REASON = "reason"
        private const val UNIQUE_NAME = "daybloom-reconcile"
        private const val MAX_ATTEMPTS = 3

        fun enqueue(context: Context, reason: ReconcileReason) {
            val request = OneTimeWorkRequestBuilder<ReconcileWorker>()
                .setInputData(workDataOf(KEY_REASON to reason.name))
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(UNIQUE_NAME, ExistingWorkPolicy.REPLACE, request)
        }
    }
}

private val receiverScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

/**
 * Runs [block] off the main thread while keeping the broadcast alive until it finishes. Failures are logged,
 * never thrown: a crashing receiver would take the app process down. The pending result can be null when
 * onReceive isn't dispatched by the system (direct calls), so it's treated as optional.
 */
private fun BroadcastReceiver.goAsync(block: suspend () -> Unit) {
    val pending: BroadcastReceiver.PendingResult? = goAsync()
    receiverScope.launch {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            android.util.Log.e("DayBloom", "Reminder broadcast handling failed", e)
        } finally {
            pending?.finish()
        }
    }
}
