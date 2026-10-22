package com.daybloom.data.repositories

import com.daybloom.data.database.TaskDao
import com.daybloom.data.database.TaskWithSubtasks
import com.daybloom.data.database.enumOr
import com.daybloom.data.database.subtaskEntities
import com.daybloom.data.database.toDomain
import com.daybloom.data.database.toEntity
import com.daybloom.data.database.toLocalDateOrNull
import com.daybloom.domain.models.Task
import com.daybloom.domain.models.TaskCategory
import com.daybloom.domain.repositories.TaskRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.datetime.LocalDate

class RoomTaskRepository(private val dao: TaskDao) : TaskRepository {
    override fun observeAll(): Flow<List<Task>> = dao.observeAll().map { it.toTasks() }
    override fun observe(id: String): Flow<Task?> = dao.observe(id).map { it?.toDomain() }
    override fun observeRelevantTo(date: LocalDate, extraIds: List<String>): Flow<List<Task>> =
        dao.observeRelevantTo(date.toString(), extraIds).map { it.toTasks() }
    override fun observeDueBetween(from: LocalDate, to: LocalDate): Flow<List<Task>> =
        dao.observeDueBetween(from.toString(), to.toString()).map { it.toTasks() }
    override fun observeCompletedBetween(from: LocalDate, to: LocalDate): Flow<List<Task>> =
        dao.observeCompletedBetween(from.toString(), to.toString()).map { it.toTasks() }
    override fun observeForAppointment(appointmentId: String): Flow<List<Task>> =
        dao.observeForAppointment(appointmentId).map { it.toTasks() }

    override suspend fun get(id: String): Task? = dao.get(id)?.toDomain()
    override suspend fun getByIds(ids: List<String>): List<Task> = if (ids.isEmpty()) emptyList() else dao.getByIds(ids).toTasks()
    override suspend fun getRelevantTo(date: LocalDate, extraIds: List<String>): List<Task> =
        dao.getRelevantTo(date.toString(), extraIds).toTasks()
    override suspend fun getDueBetween(from: LocalDate, to: LocalDate): List<Task> =
        dao.getDueBetween(from.toString(), to.toString()).toTasks()
    override suspend fun getCompletedBetween(from: LocalDate, to: LocalDate): List<Task> =
        dao.getCompletedBetween(from.toString(), to.toString()).toTasks()
    override suspend fun getOpen(): List<Task> = dao.getOpen().toTasks()
    override suspend fun getForAppointment(appointmentId: String): List<Task> = dao.getForAppointment(appointmentId).toTasks()
    override suspend fun existsInSeriesOn(seriesId: String, date: LocalDate): Boolean =
        dao.countInSeriesOn(seriesId, date.toString()) > 0
    override suspend fun completionDates(): Set<LocalDate> = dao.completionDates().mapNotNull { it.toLocalDateOrNull() }.toSet()
    override suspend fun completionCategories(): List<Pair<LocalDate, TaskCategory>> =
        dao.completionCategories().mapNotNull { row -> row.date.toLocalDateOrNull()?.let { it to enumOr(row.category, TaskCategory.OTHER) } }
    override suspend fun countCompleted(): Int = dao.countCompleted()

    override suspend fun save(task: Task) = dao.replace(task.toEntity(), task.subtaskEntities())
    override suspend fun delete(id: String) = dao.delete(id)

    private fun List<TaskWithSubtasks>.toTasks(): List<Task> = mapNotNull { it.toDomain() }
}
