package com.daybloom.notifications

enum class PermissionState { GRANTED, DENIED, NOT_DETERMINED, PROVISIONAL }

enum class ExactTimingState {
    /** Exact delivery available. */
    AVAILABLE,
    /** Android 12+ without exact-alarm access: reminders may arrive a few minutes late. */
    RESTRICTED,
    /** Platform has no such concept. */
    NOT_APPLICABLE,
}

data class NotificationStatus(
    val permission: PermissionState,
    val exactTiming: ExactTimingState,
) {
    val canDeliver: Boolean get() = permission == PermissionState.GRANTED || permission == PermissionState.PROVISIONAL
}

interface NotificationPermissionManager {
    suspend fun status(): NotificationStatus

    /** Shows the system prompt if the OS still allows it; returns the resulting state. */
    suspend fun requestPermission(): PermissionState

    /** Opens the system page where the user can grant exact-alarm access (Android) or app notification settings. */
    fun openExactTimingSettings()

    fun openNotificationSettings()
}
