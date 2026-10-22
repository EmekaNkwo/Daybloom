package com.daybloom.platform

import kotlinx.coroutines.flow.Flow
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Instant

/**
 * Time source for all shared code. Domain services take explicit dates/instants; only use cases and
 * view models read the clock, which keeps calculations deterministic and testable.
 */
interface PlatformDateTimeService {
    fun now(): Instant
    fun timeZone(): TimeZone

    /**
     * Milliseconds from a monotonic clock that keeps counting while the device sleeps and is unaffected by
     * wall-clock or time-zone changes. Resets on reboot.
     */
    fun monotonicMillis(): Long

    /** Emits whenever the device time zone or wall clock is changed by the user or the network. */
    val timeChanges: Flow<Unit>

    fun localNow(): LocalDateTime = now().toLocalDateTime(timeZone())
    fun today(): LocalDate = localNow().date
}
