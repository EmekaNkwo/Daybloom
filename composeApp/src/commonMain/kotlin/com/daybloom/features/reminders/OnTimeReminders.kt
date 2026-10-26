package com.daybloom.features.reminders

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.daybloom.components.BloomCard
import com.daybloom.design.Spacing
import com.daybloom.domain.models.ReminderStatus
import com.daybloom.domain.models.UserPreferences
import com.daybloom.domain.repositories.PreferencesRepository
import com.daybloom.domain.repositories.ReminderRepository
import com.daybloom.features.common.AppMessages
import com.daybloom.features.common.runAction
import com.daybloom.notifications.ExactTimingState
import com.daybloom.notifications.NotificationPermissionManager
import com.daybloom.notifications.NotificationStatus
import com.daybloom.notifications.ReminderReconciler
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import org.koin.compose.viewmodel.koinViewModel

/**
 * Android 12+ withholds exact-alarm access from most apps, and without it the OS may deliver a reminder up to an
 * hour late. This decides when the Today screen should offer to fix that.
 */
object OnTimeRemindersPrompt {
    fun shouldShow(status: NotificationStatus?, preferences: UserPreferences, hasActiveReminders: Boolean): Boolean {
        if (status == null || !status.canDeliver || status.exactTiming != ExactTimingState.RESTRICTED) return false
        if (preferences.exactAlarmPromptDismissed) return false
        val digestsOn = preferences.morningDigestTime != null || preferences.eveningDigestTime != null
        return hasActiveReminders || digestsOn
    }
}

/**
 * Backs the Today-screen prompt. Granting access fires a system broadcast that re-sets every alarm, and the
 * foreground reconcile on return refreshes [ReminderReconciler.status], which hides the card.
 */
class OnTimeRemindersViewModel(
    reconciler: ReminderReconciler,
    reminders: ReminderRepository,
    private val preferences: PreferencesRepository,
    private val permissions: NotificationPermissionManager,
    private val messages: AppMessages,
) : ViewModel() {
    val visible: StateFlow<Boolean> = combine(reconciler.status, preferences.preferences, reminders.observeAll()) { delivery, prefs, all ->
        OnTimeRemindersPrompt.shouldShow(delivery.status, prefs, all.any { it.status == ReminderStatus.ACTIVE })
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    fun allow() = permissions.openExactTimingSettings()

    fun notNow() = runAction(messages) { preferences.update { it.copy(exactAlarmPromptDismissed = true) } }
}

@Composable
fun OnTimeRemindersCard(modifier: Modifier = Modifier, viewModel: OnTimeRemindersViewModel = koinViewModel()) {
    val visible by viewModel.visible.collectAsStateWithLifecycle()
    if (!visible) return
    Column(modifier.padding(horizontal = Spacing.screenGutter)) {
        BloomCard(tonal = true) {
            Text("⏰ Get reminders on time", style = MaterialTheme.typography.titleSmall)
            Text(
                "Android can hold DayBloom's reminders back by up to an hour. Allow alarms & reminders so they arrive exactly when you set them.",
                style = MaterialTheme.typography.bodyMedium,
            )
            FlowRow(Modifier.padding(top = Spacing.xs), horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                FilledTonalButton(onClick = viewModel::allow) { Text("Allow") }
                TextButton(onClick = viewModel::notNow) { Text("Not now") }
            }
        }
    }
}
