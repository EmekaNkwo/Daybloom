package com.daybloom.domain.models

import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime
import kotlin.time.Instant

enum class AppointmentKind(val label: String, val emoji: String) {
    MEETING("Meeting", "🤝"),
    MEDICAL("Medical", "🩺"),
    WORSHIP("Service", "⛪"),
    INTERVIEW("Interview", "🎤"),
    CLASS("Class", "🎓"),
    FAMILY("Family", "🏡"),
    PERSONAL("Personal", "🌿"),
    OTHER("Other", "📌"),
}

/**
 * SCHEDULED appointments stay scheduled after their time passes: attendance is only ever recorded by the user.
 */
enum class AppointmentStatus { SCHEDULED, ATTENDED, MISSED, CANCELLED }

data class Appointment(
    val id: String,
    val title: String,
    val kind: AppointmentKind = AppointmentKind.MEETING,
    val date: LocalDate,
    val startTime: LocalTime,
    val endTime: LocalTime,
    val timeZoneId: String,
    val location: String = "",
    val attendees: List<String> = emptyList(),
    val notes: String = "",
    val recurrence: RecurrenceRule? = null,
    val status: AppointmentStatus = AppointmentStatus.SCHEDULED,
    /** Minutes before start at which to remind, e.g. [60, 10]. */
    val reminderOffsetsMinutes: List<Int> = listOf(15),
    val createdAt: Instant,
    val updatedAt: Instant = createdAt,
) {
    val durationMinutes: Int
        get() = ((endTime.toSecondOfDay() - startTime.toSecondOfDay()) / 60).coerceAtLeast(0)
}

/** A concrete instance of a (possibly recurring) appointment on a given date. */
data class AppointmentOccurrence(
    val appointment: Appointment,
    val date: LocalDate,
    /** Status for this occurrence; recurring series can record attendance per date. */
    val status: AppointmentStatus,
) {
    val key: String get() = "${appointment.id}@$date"
}
