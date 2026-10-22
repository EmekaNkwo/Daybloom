package com.daybloom.domain.usecases

import com.daybloom.domain.models.Appointment
import com.daybloom.domain.models.AppointmentKind
import com.daybloom.domain.models.AppointmentStatus
import com.daybloom.domain.models.DailyPlan
import com.daybloom.domain.models.EnergyLevel
import com.daybloom.domain.models.RecurrenceRule
import com.daybloom.domain.models.TaskStatus
import com.daybloom.domain.models.XpReason
import com.daybloom.domain.repositories.AppointmentRepository
import com.daybloom.domain.repositories.PlanRepository
import com.daybloom.domain.repositories.TaskRepository
import com.daybloom.domain.services.AppointmentScheduleService
import com.daybloom.domain.services.PlanCapacity
import com.daybloom.domain.services.PlanCapacityCalculator
import com.daybloom.domain.services.Validation
import com.daybloom.gamification.RewardConfig
import com.daybloom.platform.IdGenerator
import com.daybloom.platform.PlatformDateTimeService
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime

data class PlanDraft(
    val date: LocalDate,
    val topPriorityTaskIds: List<String>,
    val plannedTaskIds: List<String>,
    val availableMinutes: Int?,
    val breakMinutes: Int = 0,
    val energy: EnergyLevel? = null,
    val isRestDay: Boolean = false,
    val notes: String = "",
)

sealed interface SavePlanResult {
    data class Saved(val plan: DailyPlan, val capacity: PlanCapacity) : SavePlanResult
    /** The plan exceeds available time; the user decides whether to adjust or keep it. Nothing was saved. */
    data class OverCapacity(val capacity: PlanCapacity) : SavePlanResult
    data class Invalid(val message: String) : SavePlanResult
}

class CalculatePlanCapacityUseCase(
    private val tasks: TaskRepository,
    private val appointments: AppointmentRepository,
) {
    suspend operator fun invoke(date: LocalDate, taskIds: List<String>, availableMinutes: Int?, breakMinutes: Int): PlanCapacity {
        val planned = tasks.getByIds(taskIds).filter { it.status != TaskStatus.CANCELLED }
        val occurrences = AppointmentScheduleService.occurrencesOn(appointments.getAll(), appointments.getOccurrenceStatuses(), date)
        return PlanCapacityCalculator.calculate(
            availableMinutes = availableMinutes,
            taskEstimates = planned.filter { it.status == TaskStatus.OPEN }.map { it.estimatedMinutes },
            appointmentMinutes = occurrences.map { it.appointment.durationMinutes },
            breakMinutes = breakMinutes,
        )
    }
}

/** Saves the day's plan. Warns about over-capacity but always lets the user keep their plan. */
class SaveDailyPlanUseCase(
    private val plans: PlanRepository,
    private val capacity: CalculatePlanCapacityUseCase,
    private val awardXp: AwardExperiencePointsUseCase,
    private val process: ProcessProgressUseCase,
    private val clock: PlatformDateTimeService,
) {
    suspend operator fun invoke(draft: PlanDraft, keepDespiteOverCapacity: Boolean = false): SavePlanResult {
        if (draft.topPriorityTaskIds.distinct().size > DailyPlan.MAX_TOP_PRIORITIES) {
            return SavePlanResult.Invalid("Choose up to ${DailyPlan.MAX_TOP_PRIORITIES} top priorities")
        }
        if ((draft.availableMinutes ?: 0) !in 0..(24 * 60)) return SavePlanResult.Invalid("Available time must be within a day")
        val allPlanned = (draft.topPriorityTaskIds + draft.plannedTaskIds).distinct()
        val load = capacity(draft.date, allPlanned, draft.availableMinutes, draft.breakMinutes)
        if (!draft.isRestDay && load.isOverCapacity && !keepDespiteOverCapacity) return SavePlanResult.OverCapacity(load)

        val now = clock.now()
        val existing = plans.get(draft.date)
        val plan = DailyPlan(
            date = draft.date,
            topPriorityTaskIds = draft.topPriorityTaskIds.distinct(),
            plannedTaskIds = allPlanned,
            availableMinutes = draft.availableMinutes,
            breakMinutes = draft.breakMinutes.coerceAtLeast(0),
            energy = draft.energy,
            isRestDay = draft.isRestDay,
            acceptedOverCapacity = load.isOverCapacity && keepDespiteOverCapacity,
            notes = draft.notes.trim(),
            createdAt = existing?.createdAt ?: now,
            updatedAt = now,
        )
        plans.save(plan)
        if (!plan.isRestDay && plan.topPriorityTaskIds.isNotEmpty()) {
            awardXp.award(XpReason.PLANNED_DAY, RewardConfig.Keys.plan(plan.date.toString()), null, clock.today())
        }
        process(plan.date)
        return SavePlanResult.Saved(plan, load)
    }
}

/** Marks a day as a planned rest day (or clears it) without touching its other plan details. */
class SetRestDayUseCase(
    private val plans: PlanRepository,
    private val process: ProcessProgressUseCase,
    private val clock: PlatformDateTimeService,
) {
    suspend operator fun invoke(date: LocalDate, rest: Boolean) {
        val now = clock.now()
        val plan = plans.get(date)?.copy(isRestDay = rest, updatedAt = now)
            ?: DailyPlan(date = date, isRestDay = rest, createdAt = now)
        plans.save(plan)
        process(date)
    }
}

data class AppointmentDraft(
    val title: String,
    val kind: AppointmentKind = AppointmentKind.MEETING,
    val date: LocalDate,
    val startTime: LocalTime,
    val endTime: LocalTime,
    val location: String = "",
    val attendees: List<String> = emptyList(),
    val notes: String = "",
    val recurrence: RecurrenceRule? = null,
    val reminderOffsetsMinutes: List<Int> = listOf(15),
    /** Preparation tasks to create alongside (titles). */
    val newPreparationTasks: List<String> = emptyList(),
) {
    fun errors(): Map<String, String> = buildMap {
        Validation.title(title)?.let { put("title", it) }
        Validation.timeRange(startTime, endTime)?.let { put("time", it) }
        Validation.longText(notes, "Notes")?.let { put("notes", it) }
        if (attendees.size > 50) put("attendees", "Too many attendees")
        if (reminderOffsetsMinutes.any { it < 0 || it > 7 * 24 * 60 }) put("reminders", "Reminders must be within a week before")
    }
}

/** Creates or edits an appointment, its reminders and any new preparation tasks. */
class SaveAppointmentUseCase(
    private val appointments: AppointmentRepository,
    private val createTask: CreateTaskUseCase,
    private val reminderSync: SyncSourceRemindersUseCase,
    private val process: ProcessProgressUseCase,
    private val ids: IdGenerator,
    private val clock: PlatformDateTimeService,
) {
    sealed interface Result {
        data class Saved(val appointment: Appointment) : Result
        data class Invalid(val errors: Map<String, String>) : Result
    }

    suspend operator fun invoke(draft: AppointmentDraft, existingId: String? = null): Result {
        val errors = draft.errors()
        if (errors.isNotEmpty()) return Result.Invalid(errors)
        val existing = existingId?.let { appointments.get(it) }
        val now = clock.now()
        val appointment = Appointment(
            id = existing?.id ?: ids.newId(),
            title = draft.title.trim(),
            kind = draft.kind,
            date = draft.date,
            startTime = draft.startTime,
            endTime = draft.endTime,
            timeZoneId = existing?.timeZoneId ?: clock.timeZone().id,
            location = draft.location.trim(),
            attendees = draft.attendees.map { it.trim() }.filter { it.isNotEmpty() },
            notes = draft.notes.trim(),
            recurrence = draft.recurrence,
            status = existing?.status ?: AppointmentStatus.SCHEDULED,
            reminderOffsetsMinutes = draft.reminderOffsetsMinutes.distinct().sortedDescending(),
            createdAt = existing?.createdAt ?: now,
            updatedAt = now,
        )
        appointments.save(appointment)
        draft.newPreparationTasks.map { it.trim() }.filter { it.isNotEmpty() }.forEach { title ->
            createTask(TaskDraft(title = title, dueDate = draft.date, appointmentId = appointment.id, isFlexible = false))
        }
        reminderSync.forAppointment(appointment)
        process(existing?.date, appointment.date)
        return Result.Saved(appointment)
    }
}

/** Records attendance for one occurrence. Only the user decides; elapsed time never implies attendance. */
class SetAppointmentAttendanceUseCase(
    private val appointments: AppointmentRepository,
    private val process: ProcessProgressUseCase,
    private val clock: PlatformDateTimeService,
) {
    suspend operator fun invoke(appointmentId: String, date: LocalDate, status: AppointmentStatus) {
        appointments.setOccurrenceStatus(appointmentId, date, status, clock.now())
        process(date)
    }
}

class CancelAppointmentUseCase(
    private val appointments: AppointmentRepository,
    private val reminderSync: SyncSourceRemindersUseCase,
    private val process: ProcessProgressUseCase,
    private val clock: PlatformDateTimeService,
) {
    suspend operator fun invoke(appointmentId: String) = setStatus(appointmentId, AppointmentStatus.CANCELLED)

    /** Undoes a cancellation: the appointment is scheduled again and its reminders come back. */
    suspend fun restore(appointmentId: String) = setStatus(appointmentId, AppointmentStatus.SCHEDULED)

    private suspend fun setStatus(appointmentId: String, status: AppointmentStatus) {
        val appt = appointments.get(appointmentId) ?: return
        val updated = appt.copy(status = status, updatedAt = clock.now())
        appointments.save(updated)
        reminderSync.forAppointment(updated)
        process(appt.date)
    }
}

class DeleteAppointmentUseCase(
    private val appointments: AppointmentRepository,
    private val reminderSync: SyncSourceRemindersUseCase,
    private val process: ProcessProgressUseCase,
) {
    suspend operator fun invoke(appointmentId: String) {
        val appt = appointments.get(appointmentId) ?: return
        appointments.delete(appointmentId)
        reminderSync.deleteForAppointment(appointmentId)
        process(appt.date)
    }
}
