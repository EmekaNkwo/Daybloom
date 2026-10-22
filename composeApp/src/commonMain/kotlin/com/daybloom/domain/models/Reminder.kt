package com.daybloom.domain.models

import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime
import kotlin.time.Instant

enum class ReminderSource(val label: String) {
    TASK("Task"),
    APPOINTMENT("Appointment"),
    HABIT("Habit"),
    BILL("Bill"),
    EVENT("Personal event"),
    CUSTOM("Custom"),
}

/** Maps to Android notification channels and iOS notification categories. */
enum class NotificationCategory(val channelId: String, val label: String) {
    TASKS("daybloom_tasks", "Task reminders"),
    APPOINTMENTS("daybloom_appointments", "Appointments"),
    HABITS("daybloom_habits", "Habits"),
    BILLS("daybloom_bills", "Bills & payments"),
    GENERAL("daybloom_general", "General reminders"),
}

enum class ReminderStatus { ACTIVE, COMPLETED, DISMISSED, EXPIRED }

/**
 * The persisted definition of a reminder. The database is the source of truth; platform notifications are a
 * delivery mechanism reconciled from these records by [com.daybloom.notifications.ReminderReconciler].
 */
data class Reminder(
    val id: String,
    val title: String,
    val body: String = "",
    val source: ReminderSource = ReminderSource.CUSTOM,
    val sourceId: String? = null,
    val category: NotificationCategory = NotificationCategory.GENERAL,
    val startDate: LocalDate,
    /** One or more times of day at which the reminder fires on each occurrence date. */
    val times: List<LocalTime>,
    val recurrence: RecurrenceRule? = null,
    /** Wall-clock times are interpreted in this zone; a null zone follows the device ("floating" time). */
    val timeZoneId: String? = null,
    val expiresOn: LocalDate? = null,
    val respectQuietHours: Boolean = true,
    val status: ReminderStatus = ReminderStatus.ACTIVE,
    val snoozedUntil: Instant? = null,
    /** Occurrences at or before this instant were handled (done/dismissed) and must not be re-delivered. */
    val acknowledgedThrough: Instant? = null,
    val createdAt: Instant,
    val updatedAt: Instant = createdAt,
) {
    init {
        require(times.isNotEmpty()) { "A reminder needs at least one time" }
    }
}

data class QuietHours(val start: LocalTime, val end: LocalTime) {
    /** Handles windows that wrap past midnight, e.g. 22:00–07:00. */
    fun contains(time: LocalTime): Boolean =
        if (start <= end) time >= start && time < end else time >= start || time < end
}

/** A single planned delivery. Its [key] is stable so re-scheduling the same occurrence never duplicates it. */
data class ReminderOccurrence(
    val reminderId: String,
    val fireAt: Instant,
    val title: String,
    val body: String,
    val category: NotificationCategory,
) {
    val key: String get() = "$reminderId@${fireAt.toEpochMilliseconds()}"
}
