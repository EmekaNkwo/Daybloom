package com.daybloom.data.repositories

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.daybloom.data.database.enumOr
import com.daybloom.data.database.toLocalTimeOrNull
import com.daybloom.data.database.toStorage
import com.daybloom.domain.models.GardenTheme
import com.daybloom.domain.models.QuietHours
import com.daybloom.domain.models.ScoreDimension
import com.daybloom.domain.models.ThemeMode
import com.daybloom.domain.models.UnfinishedTaskPolicy
import com.daybloom.domain.models.UserPreferences
import com.daybloom.domain.repositories.PreferencesRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import okio.IOException

/** DataStore-backed preferences. Corrupt or unreadable files fall back to defaults rather than crashing. */
class DataStorePreferencesRepository(private val store: DataStore<Preferences>) : PreferencesRepository {

    override val preferences: Flow<UserPreferences> = store.data
        .catch { e -> if (e is IOException) emit(emptyPreferences()) else throw e }
        .map { it.toUserPreferences() }

    override suspend fun current(): UserPreferences = preferences.first()

    override suspend fun update(transform: (UserPreferences) -> UserPreferences) {
        store.edit { prefs ->
            val updated = transform(prefs.toUserPreferences())
            prefs.write(updated)
        }
    }

    override suspend fun reset() {
        store.edit { it.clear() }
    }

    private fun Preferences.toUserPreferences(): UserPreferences {
        val defaults = UserPreferences()
        val quietEnabled = this[Keys.QUIET_ENABLED] ?: (defaults.quietHours != null)
        val quietStart = this[Keys.QUIET_START]?.toLocalTimeOrNull()
        val quietEnd = this[Keys.QUIET_END]?.toLocalTimeOrNull()
        val quiet = when {
            !quietEnabled -> null
            quietStart != null && quietEnd != null && quietStart != quietEnd -> QuietHours(quietStart, quietEnd)
            else -> defaults.quietHours
        }
        val dims = this[Keys.SCORE_DIMENSIONS]
            ?.split(',')
            ?.mapNotNull { name -> ScoreDimension.entries.firstOrNull { it.name == name } }
            ?.toSet()
            ?: defaults.enabledScoreDimensions
        return UserPreferences(
            displayName = this[Keys.DISPLAY_NAME] ?: defaults.displayName,
            themeMode = enumOr(this[Keys.THEME], defaults.themeMode),
            reducedMotion = this[Keys.REDUCED_MOTION] ?: defaults.reducedMotion,
            hapticsEnabled = this[Keys.HAPTICS] ?: defaults.hapticsEnabled,
            gamificationEnabled = this[Keys.GAMIFICATION] ?: defaults.gamificationEnabled,
            suggestionsEnabled = this[Keys.SUGGESTIONS] ?: defaults.suggestionsEnabled,
            enabledScoreDimensions = dims,
            dailyFocusGoalMinutes = (this[Keys.FOCUS_GOAL] ?: defaults.dailyFocusGoalMinutes).coerceIn(5, 600),
            defaultAvailableMinutes = (this[Keys.AVAILABLE_MINUTES] ?: defaults.defaultAvailableMinutes).coerceIn(0, 24 * 60),
            quietHours = quiet,
            unfinishedTaskPolicy = enumOr(this[Keys.UNFINISHED_POLICY], defaults.unfinishedTaskPolicy),
            gardenTheme = enumOr(this[Keys.GARDEN_THEME], defaults.gardenTheme),
            lastCelebratedLevel = (this[Keys.LAST_LEVEL] ?: defaults.lastCelebratedLevel).coerceAtLeast(1),
            onboardingComplete = this[Keys.ONBOARDED] ?: defaults.onboardingComplete,
            planningReminderTime = this[Keys.PLANNING_REMINDER]?.toLocalTimeOrNull(),
        )
    }

    private fun androidx.datastore.preferences.core.MutablePreferences.write(p: UserPreferences) {
        this[Keys.DISPLAY_NAME] = p.displayName.take(40)
        this[Keys.THEME] = p.themeMode.name
        this[Keys.REDUCED_MOTION] = p.reducedMotion
        this[Keys.HAPTICS] = p.hapticsEnabled
        this[Keys.GAMIFICATION] = p.gamificationEnabled
        this[Keys.SUGGESTIONS] = p.suggestionsEnabled
        this[Keys.SCORE_DIMENSIONS] = p.enabledScoreDimensions.joinToString(",") { it.name }
        this[Keys.FOCUS_GOAL] = p.dailyFocusGoalMinutes
        this[Keys.AVAILABLE_MINUTES] = p.defaultAvailableMinutes
        this[Keys.QUIET_ENABLED] = p.quietHours != null
        p.quietHours?.let {
            this[Keys.QUIET_START] = it.start.toStorage()
            this[Keys.QUIET_END] = it.end.toStorage()
        }
        this[Keys.UNFINISHED_POLICY] = p.unfinishedTaskPolicy.name
        this[Keys.GARDEN_THEME] = p.gardenTheme.name
        this[Keys.LAST_LEVEL] = p.lastCelebratedLevel
        this[Keys.ONBOARDED] = p.onboardingComplete
        val planning = p.planningReminderTime
        if (planning != null) this[Keys.PLANNING_REMINDER] = planning.toStorage() else remove(Keys.PLANNING_REMINDER)
    }

    private object Keys {
        val DISPLAY_NAME = stringPreferencesKey("display_name")
        val THEME = stringPreferencesKey("theme_mode")
        val REDUCED_MOTION = booleanPreferencesKey("reduced_motion")
        val HAPTICS = booleanPreferencesKey("haptics")
        val GAMIFICATION = booleanPreferencesKey("gamification")
        val SUGGESTIONS = booleanPreferencesKey("suggestions")
        val SCORE_DIMENSIONS = stringPreferencesKey("score_dimensions")
        val FOCUS_GOAL = intPreferencesKey("focus_goal_minutes")
        val AVAILABLE_MINUTES = intPreferencesKey("available_minutes")
        val QUIET_ENABLED = booleanPreferencesKey("quiet_enabled")
        val QUIET_START = stringPreferencesKey("quiet_start")
        val QUIET_END = stringPreferencesKey("quiet_end")
        val UNFINISHED_POLICY = stringPreferencesKey("unfinished_policy")
        val GARDEN_THEME = stringPreferencesKey("garden_theme")
        val LAST_LEVEL = intPreferencesKey("last_celebrated_level")
        val ONBOARDED = booleanPreferencesKey("onboarding_complete")
        val PLANNING_REMINDER = stringPreferencesKey("planning_reminder_time")
    }
}
