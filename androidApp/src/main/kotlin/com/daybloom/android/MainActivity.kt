package com.daybloom.android

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import com.daybloom.app.App
import com.daybloom.notifications.AndroidNotificationPermissionManager
import kotlinx.coroutines.CompletableDeferred

class MainActivity : ComponentActivity() {
    private var pendingPermission: CompletableDeferred<Boolean>? = null

    private val permissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        pendingPermission?.complete(granted)
        pendingPermission = null
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent { App() }
    }

    override fun onStart() {
        super.onStart()
        // Shared code asks for notification permission through this bridge while the Activity is visible.
        AndroidNotificationPermissionManager.requester = {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                true
            } else {
                val deferred = CompletableDeferred<Boolean>()
                pendingPermission = deferred
                permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                deferred.await()
            }
        }
    }

    override fun onStop() {
        AndroidNotificationPermissionManager.requester = null
        pendingPermission?.complete(false)
        pendingPermission = null
        super.onStop()
    }
}
