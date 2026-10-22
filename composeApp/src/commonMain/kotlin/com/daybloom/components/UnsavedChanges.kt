package com.daybloom.components

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.backhandler.BackHandler

/**
 * True once [current] differs from the value it had when the screen first became [ready] (finished loading).
 * Editors pass their form so leaving can be guarded without the view model tracking a baseline.
 */
@Composable
fun <T> rememberHasChanges(ready: Boolean, current: T): Boolean {
    var baseline by remember { mutableStateOf<Baseline<T>?>(null) }
    LaunchedEffect(ready) { if (ready && baseline == null) baseline = Baseline(current) }
    val captured = baseline ?: return false
    return captured.value != current
}

private class Baseline<T>(val value: T)

/**
 * Stops unsaved edits from being lost silently. While [hasChanges], the system back gesture asks first;
 * use the returned function for the screen's own back arrow. [onLeave] runs when leaving is confirmed
 * (or immediately when there is nothing to lose).
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun unsavedChangesGuard(hasChanges: Boolean, onLeave: () -> Unit): () -> Unit {
    var asking by rememberSaveable { mutableStateOf(false) }
    BackHandler(enabled = hasChanges) { asking = true }
    if (asking) {
        AlertDialog(
            onDismissRequest = { asking = false },
            title = { Text("Discard changes?") },
            text = { Text("Your edits haven't been saved.") },
            confirmButton = { TextButton(onClick = { asking = false; onLeave() }) { Text("Discard") } },
            dismissButton = { TextButton(onClick = { asking = false }) { Text("Keep editing") } },
        )
    }
    return { if (hasChanges) asking = true else onLeave() }
}
