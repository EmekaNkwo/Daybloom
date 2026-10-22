package com.daybloom.app

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.daybloom.domain.models.LevelProgress
import com.daybloom.domain.models.UserPreferences
import com.daybloom.domain.repositories.PreferencesRepository
import com.daybloom.domain.repositories.XpRepository
import com.daybloom.domain.usecases.AppStartupUseCase
import com.daybloom.domain.usecases.LevelUpUseCase
import com.daybloom.domain.usecases.SeedDemoDataUseCase
import com.daybloom.features.common.AppMessage
import com.daybloom.features.common.AppMessages
import com.daybloom.features.common.runAction
import com.daybloom.gamification.LevelSystem
import com.daybloom.notifications.NotificationPermissionManager
import com.daybloom.notifications.PermissionState
import com.daybloom.notifications.ReconcileReason
import com.daybloom.platform.AppLifecycleEvent
import com.daybloom.platform.AppLifecycleService
import com.daybloom.platform.PlatformDateTimeService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class AppUiState(
    val loaded: Boolean = false,
    val preferences: UserPreferences = UserPreferences(),
    val celebration: LevelProgress? = null,
)

/** App-level orchestration: preferences/theme, startup and foreground recovery, level-up celebrations. */
class AppViewModel(
    private val preferences: PreferencesRepository,
    private val xp: XpRepository,
    private val startup: AppStartupUseCase,
    private val levelUp: LevelUpUseCase,
    private val seedDemo: SeedDemoDataUseCase,
    private val permissions: NotificationPermissionManager,
    private val messagesBus: AppMessages,
    lifecycle: AppLifecycleService,
    clock: PlatformDateTimeService,
) : ViewModel() {
    private val celebration = MutableStateFlow<LevelProgress?>(null)

    val state: StateFlow<AppUiState> = combine(preferences.preferences, celebration) { prefs, celebrate ->
        AppUiState(loaded = true, preferences = prefs, celebration = celebrate.takeIf { prefs.gamificationEnabled })
    }.stateIn(viewModelScope, SharingStarted.Eagerly, AppUiState())

    val messages: SharedFlow<AppMessage> = messagesBus.events

    init {
        runAction(messagesBus, "Couldn't refresh reminders. They'll sync next time you open DayBloom.") { startup() }
        lifecycle.events
            .filter { it == AppLifecycleEvent.FOREGROUND }
            .drop(1) // the first foreground is the launch itself, handled above
            .onEach { runAction(messagesBus) { startup(ReconcileReason.APP_START) } }
            .launchIn(viewModelScope)
        clock.timeChanges
            .onEach { runAction(messagesBus) { startup(ReconcileReason.TIME_CHANGED) } }
            .launchIn(viewModelScope)
        xp.observeTotal()
            .map { LevelSystem.progressFor(it).level.number }
            .distinctUntilChanged()
            .onEach { viewModelScope.launch { celebration.value = levelUp.pendingCelebration() } }
            .launchIn(viewModelScope)
    }

    fun dismissCelebration() {
        val level = celebration.value?.level?.number ?: return
        celebration.update { null }
        viewModelScope.launch { levelUp.markCelebrated(level) }
    }

    fun completeOnboarding(name: String, loadSample: Boolean, enableReminders: Boolean) = runAction(messagesBus) {
        preferences.update { it.copy(displayName = name.trim().take(40), onboardingComplete = true) }
        if (enableReminders) {
            val result = permissions.requestPermission()
            if (result == PermissionState.GRANTED) startup(ReconcileReason.PERMISSION_CHANGED)
        }
        if (loadSample) seedDemo()
    }
}
