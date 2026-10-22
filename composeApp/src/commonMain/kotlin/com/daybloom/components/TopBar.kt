package com.daybloom.components

import androidx.compose.foundation.layout.RowScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowBackIos
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import com.daybloom.platform.PlatformInfo
import com.daybloom.platform.PlatformKind

val LocalPlatformInfo = staticCompositionLocalOf { PlatformInfo(PlatformKind.ANDROID, "") }

/**
 * Top bar that follows each platform's convention: centered title with a chevron back button on iOS,
 * start-aligned title with an arrow on Android.
 */
@Composable
fun BloomTopBar(
    title: String,
    onBack: (() -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {},
) {
    val ios = LocalPlatformInfo.current.isIos
    val colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background)
    val nav: @Composable () -> Unit = {
        if (onBack != null) {
            IconButton(onClick = onBack) {
                Icon(if (ios) Icons.AutoMirrored.Filled.ArrowBackIos else Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
            }
        }
    }
    if (ios) {
        CenterAlignedTopAppBar(title = { Text(title, style = MaterialTheme.typography.titleMedium) }, navigationIcon = nav, actions = actions, colors = colors)
    } else {
        TopAppBar(title = { Text(title) }, navigationIcon = nav, actions = actions, colors = colors)
    }
}
