package com.daybloom.design

import androidx.compose.runtime.Composable

/**
 * Keeps the platform's status/navigation bar icons legible for the app's own theme, which can differ from the
 * device's (e.g. Dark chosen in Settings on a phone in light mode).
 */
@Composable
internal expect fun SystemBarAppearance(darkTheme: Boolean)
