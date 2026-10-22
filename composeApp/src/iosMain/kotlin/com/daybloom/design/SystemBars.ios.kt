package com.daybloom.design

import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.uikit.LocalUIViewController
import platform.UIKit.UIUserInterfaceStyle

@Composable
internal actual fun SystemBarAppearance(darkTheme: Boolean) {
    val controller = LocalUIViewController.current
    SideEffect {
        // The status bar follows the window's interface style, so match it to the app's theme.
        controller.view.window?.overrideUserInterfaceStyle =
            if (darkTheme) UIUserInterfaceStyle.UIUserInterfaceStyleDark else UIUserInterfaceStyle.UIUserInterfaceStyleLight
    }
}
