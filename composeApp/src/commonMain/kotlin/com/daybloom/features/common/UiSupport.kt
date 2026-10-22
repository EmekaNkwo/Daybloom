package com.daybloom.features.common

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.daybloom.platform.PlatformDateTimeService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.launch
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime

/** A transient message shown as a snackbar, optionally with one action (e.g. Undo). */
data class AppMessage(
    val text: String,
    val actionLabel: String? = null,
    val onAction: (suspend () -> Unit)? = null,
)

/** App-wide message bus so any screen's actions can surface feedback in the shared snackbar host. */
class AppMessages {
    private val _events = MutableSharedFlow<AppMessage>(extraBufferCapacity = 16)
    val events: SharedFlow<AppMessage> = _events.asSharedFlow()

    fun post(message: AppMessage) {
        _events.tryEmit(message)
    }

    fun post(text: String) = post(AppMessage(text))
}

/**
 * Launches a user action, turning unexpected failures into a friendly message instead of a crash.
 * Cancellation is always propagated.
 */
fun ViewModel.runAction(
    messages: AppMessages,
    failure: String = "Something went wrong. Please try again.",
    block: suspend () -> Unit,
): Job = viewModelScope.launch {
    try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        println("DayBloom action failed: ${e::class.simpleName}: ${e.message}")
        messages.post(failure)
    }
}

/** Emits the local date-time now, at every minute boundary, and immediately after clock/zone changes. */
fun PlatformDateTimeService.minuteTicks(): Flow<LocalDateTime> = merge(
    flow {
        while (true) {
            val now = localNow()
            emit(now)
            delay((60 - now.second) * 1000L - now.nanosecond / 1_000_000 + 50)
        }
    },
    timeChanges.map { localNow() },
)

fun PlatformDateTimeService.todayFlow(): Flow<LocalDate> = minuteTicks().map { it.date }.distinctUntilChanged()
