package com.daybloom.domain.services

import com.daybloom.domain.models.Appointment
import com.daybloom.domain.models.AppointmentOccurrence
import com.daybloom.domain.models.AppointmentStatus
import kotlinx.datetime.LocalDate

/**
 * The single place appointments are expanded into dated occurrences. Calendar views, the daily plan,
 * reminders and monthly reports all use it, so an appointment appears identically everywhere.
 *
 * Occurrence status comes only from what the user recorded; a past occurrence with nothing recorded stays
 * SCHEDULED (it is never assumed attended).
 */
object AppointmentScheduleService {

    fun occurrencesBetween(
        appointments: List<Appointment>,
        recordedStatuses: Map<String, AppointmentStatus>,
        from: LocalDate,
        to: LocalDate,
        includeCancelled: Boolean = false,
    ): List<AppointmentOccurrence> =
        appointments.asSequence()
            .filter { includeCancelled || it.status != AppointmentStatus.CANCELLED }
            .flatMap { appt ->
                RecurrenceCalculator.occurrencesBetween(appt.recurrence, appt.date, from, to).asSequence().map { date ->
                    val recorded = recordedStatuses["${appt.id}@$date"]
                    val status = when {
                        appt.status == AppointmentStatus.CANCELLED -> AppointmentStatus.CANCELLED
                        recorded != null -> recorded
                        appt.recurrence == null && appt.status != AppointmentStatus.SCHEDULED -> appt.status
                        else -> AppointmentStatus.SCHEDULED
                    }
                    AppointmentOccurrence(appt, date, status)
                }
            }
            .filter { includeCancelled || it.status != AppointmentStatus.CANCELLED }
            .sortedWith(compareBy({ it.date }, { it.appointment.startTime }))
            .toList()

    fun occurrencesOn(
        appointments: List<Appointment>,
        recordedStatuses: Map<String, AppointmentStatus>,
        date: LocalDate,
    ): List<AppointmentOccurrence> = occurrencesBetween(appointments, recordedStatuses, date, date)
}
