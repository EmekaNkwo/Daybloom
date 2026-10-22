package com.daybloom.data.database

import androidx.room.Dao
import androidx.room.Embedded
import androidx.room.Query
import androidx.room.Relation
import androidx.room.Transaction
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

data class TaskWithSubtasks(
    @Embedded val task: TaskEntity,
    @Relation(parentColumn = "id", entityColumn = "taskId")
    val subtasks: List<SubtaskEntity>,
)

@Dao
interface TaskDao {
    @Transaction
    @Query("SELECT * FROM tasks ORDER BY CASE WHEN dueDate IS NULL THEN 1 ELSE 0 END, dueDate, dueTime, createdAt")
    fun observeAll(): Flow<List<TaskWithSubtasks>>

    @Transaction
    @Query("SELECT * FROM tasks WHERE id = :id")
    fun observe(id: String): Flow<TaskWithSubtasks?>

    @Transaction
    @Query("SELECT * FROM tasks WHERE id = :id")
    suspend fun get(id: String): TaskWithSubtasks?

    @Transaction
    @Query("SELECT * FROM tasks WHERE id IN (:ids)")
    suspend fun getByIds(ids: List<String>): List<TaskWithSubtasks>

    @Transaction
    @Query("SELECT * FROM tasks WHERE dueDate = :date OR completedOn = :date OR id IN (:extraIds)")
    fun observeRelevantTo(date: String, extraIds: List<String>): Flow<List<TaskWithSubtasks>>

    @Transaction
    @Query("SELECT * FROM tasks WHERE dueDate = :date OR completedOn = :date OR id IN (:extraIds)")
    suspend fun getRelevantTo(date: String, extraIds: List<String>): List<TaskWithSubtasks>

    @Transaction
    @Query("SELECT * FROM tasks WHERE dueDate BETWEEN :from AND :to")
    suspend fun getDueBetween(from: String, to: String): List<TaskWithSubtasks>

    @Transaction
    @Query("SELECT * FROM tasks WHERE dueDate BETWEEN :from AND :to")
    fun observeDueBetween(from: String, to: String): Flow<List<TaskWithSubtasks>>

    @Transaction
    @Query("SELECT * FROM tasks WHERE completedOn BETWEEN :from AND :to ORDER BY completedAt")
    suspend fun getCompletedBetween(from: String, to: String): List<TaskWithSubtasks>

    @Transaction
    @Query("SELECT * FROM tasks WHERE completedOn BETWEEN :from AND :to ORDER BY completedAt")
    fun observeCompletedBetween(from: String, to: String): Flow<List<TaskWithSubtasks>>

    @Transaction
    @Query("SELECT * FROM tasks WHERE status = 'OPEN'")
    suspend fun getOpen(): List<TaskWithSubtasks>

    @Transaction
    @Query("SELECT * FROM tasks WHERE appointmentId = :appointmentId")
    fun observeForAppointment(appointmentId: String): Flow<List<TaskWithSubtasks>>

    @Transaction
    @Query("SELECT * FROM tasks WHERE appointmentId = :appointmentId")
    suspend fun getForAppointment(appointmentId: String): List<TaskWithSubtasks>

    @Query("SELECT COUNT(*) FROM tasks WHERE seriesId = :seriesId AND dueDate = :date")
    suspend fun countInSeriesOn(seriesId: String, date: String): Int

    @Query("SELECT DISTINCT completedOn FROM tasks WHERE completedOn IS NOT NULL")
    suspend fun completionDates(): List<String>

    @Query("SELECT completedOn AS date, category FROM tasks WHERE completedOn IS NOT NULL")
    suspend fun completionCategories(): List<DateCategoryRow>

    @Query("SELECT COUNT(*) FROM tasks WHERE status = 'COMPLETED'")
    suspend fun countCompleted(): Int

    @Upsert
    suspend fun upsertTask(task: TaskEntity)

    @Upsert
    suspend fun upsertSubtasks(subtasks: List<SubtaskEntity>)

    @Query("DELETE FROM subtasks WHERE taskId = :taskId")
    suspend fun deleteSubtasks(taskId: String)

    @Transaction
    suspend fun replace(task: TaskEntity, subtasks: List<SubtaskEntity>) {
        upsertTask(task)
        deleteSubtasks(task.id)
        if (subtasks.isNotEmpty()) upsertSubtasks(subtasks)
    }

    @Query("DELETE FROM tasks WHERE id = :id")
    suspend fun delete(id: String)

    @Query("DELETE FROM tasks")
    suspend fun deleteAll()
}

data class DateCategoryRow(val date: String, val category: String)
