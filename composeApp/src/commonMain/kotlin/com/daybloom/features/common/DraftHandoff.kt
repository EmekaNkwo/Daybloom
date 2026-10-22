package com.daybloom.features.common

import com.daybloom.domain.usecases.AppointmentDraft
import com.daybloom.domain.usecases.TaskDraft

/**
 * Carries a pre-filled draft from quick capture into the full editor ("Edit" on the confirmation step).
 * Drafts are consumed once so a later visit to the editor starts clean.
 */
class DraftHandoff {
    private var task: TaskDraft? = null
    private var appointment: AppointmentDraft? = null

    fun putTask(draft: TaskDraft) {
        task = draft
    }

    fun takeTask(): TaskDraft? = task.also { task = null }

    fun putAppointment(draft: AppointmentDraft) {
        appointment = draft
    }

    fun takeAppointment(): AppointmentDraft? = appointment.also { appointment = null }
}
