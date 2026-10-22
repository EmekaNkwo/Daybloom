package com.daybloom.platform

import kotlinx.coroutines.flow.Flow

enum class AppLifecycleEvent { FOREGROUND, BACKGROUND }

/** Process-level lifecycle (not per-screen). Android: ProcessLifecycleOwner. iOS: UIApplication notifications. */
interface AppLifecycleService {
    val events: Flow<AppLifecycleEvent>
}
