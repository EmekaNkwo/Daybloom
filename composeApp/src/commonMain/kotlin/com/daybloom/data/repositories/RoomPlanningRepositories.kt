package com.daybloom.data.repositories

import com.daybloom.data.database.AppointmentDao
import com.daybloom.data.database.AppointmentOccurrenceEntity
import com.daybloom.data.database.DailyPlanDao
import com.daybloom.data.database.FocusDao
import com.daybloom.data.database.HabitCompletionEntity
import com.daybloom.data.database.HabitDao
import com.daybloom.data.database.ReminderDao
import com.daybloom.data.database.ScheduledNotificationEntity
import com.daybloom.data.database.enumOr
import com.daybloom.data.database.toDomain
import com.daybloom.data.database.toEntity
import com.daybloom.data.database.toInstant
import com.daybloom.domain.models.Appointment
import com.daybloom.domain.models.AppointmentStatus
import com.daybloom.domain.models.DailyPlan
import com.daybloom.domain.models.FocusSession
import com.daybloom.domain.models.Habit
import com.daybloom.domain.models.HabitCompletion
import com.daybloom.domain.models.Reminder
import com.daybloom.domain.models.ReminderSource
import com.daybloom.domain.repositories.AppointmentRepository
import com.daybloom.domain.repositories.FocusRepository
import com.daybloom.domain.repositories.HabitRepository
import com.daybloom.domain.repositories.PlanRepository
import com.daybloom.domain.repositories.ReminderRepository
import com.daybloom.domain.repositories.ScheduledRecord
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.datetime.LocalDate
import kotlin.time.Instant

class RoomAppointmentRepository(private val dao: AppointmentDao) : AppointmentRepository {
    override fun observeAll(): Flow<List<Appointment>> = dao.observeAll().map { list -> list.mapNotNull { it.toDomain() } }
    override fun observe(id: String): Flow<Appointment?> = dao.observe(id).map { it?.toDomain() }
    override fun observeOccurrenceStatuses(): Flow<Map<String, AppointmentStatus>> =
        dao.observeOccurrenceStatuses().map { it.toStatusMap() }
    override suspend fun get(id: String): Appointment? = dao.get(id)?.toDomain()
    override suspend fun getAll(): List<Appointment> = dao.getAll().mapNotNull { it.toDomain() }
    override suspend fun getOccurrenceStatuses(): Map<String, AppointmentStatus> = dao.getOccurrenceStatuses().toStatusMap()
    override suspend fun save(appointment: Appointment) = dao.upsert(appointment.toEntity())
    override suspend fun delete(id: String) = dao.delete(id)
    override suspend fun setOccurrenceStatus(appointmentId: String, date: LocalDate, status: AppointmentStatus, at: Instant) =
        dao.upsertOccurrence(AppointmentOccurrenceEntity(appointmentId, date.toString(), status.name, at.toEpochMilliseconds()))

    private fun List<AppointmentOccurrenceEntity>.toStatusMap(): Map<String, AppointmentStatus> =
        associate { "${it.appointmentId}@${it.date}" to enumOr(it.status, AppointmentStatus.SCHEDULED) }
}

class RoomReminderRepository(private val dao: ReminderDao) : ReminderRepository {
    override fun observeAll(): Flow<List<Reminder>> = dao.observeAll().map { list -> list.mapNotNull { it.toDomain() } }
    override fun observeForSource(source: ReminderSource, sourceId: String): Flow<List<Reminder>> =
        dao.observeForSource(source.name, sourceId).map { list -> list.mapNotNull { it.toDomain() } }
    override fun observeScheduled(): Flow<List<ScheduledRecord>> = dao.observeScheduled().map { list -> list.map { it.toRecord() } }
    override suspend fun getAll(): List<Reminder> = dao.getAll().mapNotNull { it.toDomain() }
    override suspend fun getActive(): List<Reminder> = dao.getActive().mapNotNull { it.toDomain() }
    override suspend fun get(id: String): Reminder? = dao.get(id)?.toDomain()
    override suspend fun getForSource(source: ReminderSource, sourceId: String): List<Reminder> =
        dao.getForSource(source.name, sourceId).mapNotNull { it.toDomain() }
    override suspend fun save(reminder: Reminder) = dao.upsert(reminder.toEntity())
    override suspend fun delete(id: String) = dao.delete(id)
    override suspend fun deleteForSource(source: ReminderSource, sourceId: String) = dao.deleteForSource(source.name, sourceId)
    override suspend fun getScheduled(): List<ScheduledRecord> = dao.getScheduled().map { it.toRecord() }
    override suspend fun recordScheduled(records: List<ScheduledRecord>, at: Instant) {
        if (records.isEmpty()) return
        dao.upsertScheduled(
            records.map { ScheduledNotificationEntity(it.id, it.reminderId, it.fireAt.toEpochMilliseconds(), it.contentHash, at.toEpochMilliseconds()) },
        )
    }
    override suspend fun removeScheduled(ids: Collection<String>) {
        if (ids.isNotEmpty()) dao.deleteScheduled(ids.toList())
    }
    override suspend fun clearScheduled() = dao.deleteAllScheduled()

    private fun ScheduledNotificationEntity.toRecord() = ScheduledRecord(id, reminderId, fireAt.toInstant(), contentHash)
}

class RoomPlanRepository(private val dao: DailyPlanDao) : PlanRepository {
    override fun observe(date: LocalDate): Flow<DailyPlan?> = dao.observe(date.toString()).map { it?.toDomain() }
    override fun observeBetween(from: LocalDate, to: LocalDate): Flow<List<DailyPlan>> =
        dao.observeBetween(from.toString(), to.toString()).map { list -> list.mapNotNull { it.toDomain() } }
    override suspend fun get(date: LocalDate): DailyPlan? = dao.get(date.toString())?.toDomain()
    override suspend fun getBetween(from: LocalDate, to: LocalDate): List<DailyPlan> =
        dao.getBetween(from.toString(), to.toString()).mapNotNull { it.toDomain() }
    override suspend fun getAll(): List<DailyPlan> = dao.getAll().mapNotNull { it.toDomain() }
    override suspend fun save(plan: DailyPlan) = dao.upsert(plan.toEntity())
}

class RoomHabitRepository(private val dao: HabitDao) : HabitRepository {
    override fun observeHabits(): Flow<List<Habit>> = dao.observeActive().map { list -> list.map { it.toDomain() } }
    override fun observeCompletions(): Flow<List<HabitCompletion>> = dao.observeCompletions().map { list -> list.mapNotNull { it.toDomain() } }
    override suspend fun getHabits(): List<Habit> = dao.getAll().map { it.toDomain() }
    override suspend fun get(id: String): Habit? = dao.get(id)?.toDomain()
    override suspend fun save(habit: Habit) = dao.upsert(habit.toEntity())
    override suspend fun delete(id: String) = dao.delete(id)
    override suspend fun getCompletions(): List<HabitCompletion> = dao.getCompletions().mapNotNull { it.toDomain() }
    override suspend fun getCompletionsBetween(from: LocalDate, to: LocalDate): List<HabitCompletion> =
        dao.getCompletionsBetween(from.toString(), to.toString()).mapNotNull { it.toDomain() }
    override suspend fun setCompleted(habitId: String, date: LocalDate, done: Boolean, at: Instant) {
        if (done) {
            dao.upsertCompletion(HabitCompletionEntity(habitId, date.toString(), at.toEpochMilliseconds()))
        } else {
            dao.deleteCompletion(habitId, date.toString())
        }
    }
}

class RoomFocusRepository(private val dao: FocusDao) : FocusRepository {
    override fun observeActive(): Flow<FocusSession?> = dao.observeActive().map { it?.toDomain() }
    override fun observeBetween(from: LocalDate, to: LocalDate): Flow<List<FocusSession>> =
        dao.observeBetween(from.toString(), to.toString()).map { list -> list.mapNotNull { it.toDomain() } }
    override suspend fun getActive(): FocusSession? = dao.getActive()?.toDomain()
    override suspend fun get(id: String): FocusSession? = dao.get(id)?.toDomain()
    override suspend fun getBetween(from: LocalDate, to: LocalDate): List<FocusSession> =
        dao.getBetween(from.toString(), to.toString()).mapNotNull { it.toDomain() }
    override suspend fun getAll(): List<FocusSession> = dao.getAll().mapNotNull { it.toDomain() }
    override suspend fun save(session: FocusSession) = dao.upsert(session.toEntity())
}
