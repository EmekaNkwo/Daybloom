package com.daybloom.data.database

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/*
 * Storage conventions (keep stable across migrations):
 * - Dates are ISO-8601 strings (yyyy-MM-dd) so they sort and compare correctly in SQL.
 * - Times of day are ISO strings (HH:mm).
 * - Instants are epoch milliseconds.
 * - Enums are stored by name; unknown names fall back to safe defaults when read (see Mappers.kt).
 * - Lists/sets/recurrence are JSON via kotlinx.serialization.
 * No platform objects or OS notification handles are ever stored.
 */

@Entity(
    tableName = "tasks",
    indices = [Index("dueDate"), Index("status"), Index("seriesId"), Index("completedOn"), Index("appointmentId")],
)
data class TaskEntity(
    @PrimaryKey val id: String,
    val title: String,
    val description: String,
    val category: String,
    val priority: String,
    val dueDate: String?,
    val dueTime: String?,
    val estimatedMinutes: Int?,
    val actualMinutes: Int?,
    val status: String,
    val recurrenceJson: String?,
    val seriesId: String,
    val notes: String,
    val tagsJson: String,
    val isFlexible: Boolean,
    val appointmentId: String?,
    val postponeCount: Int,
    val createdAt: Long,
    val updatedAt: Long,
    val completedAt: Long?,
    val completedOn: String?,
    val cancelledAt: Long?,
)

@Entity(
    tableName = "subtasks",
    foreignKeys = [ForeignKey(TaskEntity::class, ["id"], ["taskId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("taskId")],
)
data class SubtaskEntity(
    @PrimaryKey val id: String,
    val taskId: String,
    val title: String,
    val isDone: Boolean,
    val sortOrder: Int,
)

@Entity(tableName = "appointments", indices = [Index("date")])
data class AppointmentEntity(
    @PrimaryKey val id: String,
    val title: String,
    val kind: String,
    val date: String,
    val startTime: String,
    val endTime: String,
    val timeZoneId: String,
    val location: String,
    val attendeesJson: String,
    val notes: String,
    val recurrenceJson: String?,
    val status: String,
    val reminderOffsetsJson: String,
    val createdAt: Long,
    val updatedAt: Long,
)

/** Per-occurrence attendance for (possibly recurring) appointments. */
@Entity(
    tableName = "appointment_occurrences",
    primaryKeys = ["appointmentId", "date"],
    foreignKeys = [ForeignKey(AppointmentEntity::class, ["id"], ["appointmentId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("date")],
)
data class AppointmentOccurrenceEntity(
    val appointmentId: String,
    val date: String,
    val status: String,
    val updatedAt: Long,
)

@Entity(tableName = "reminders", indices = [Index("source", "sourceId"), Index("status")])
data class ReminderEntity(
    @PrimaryKey val id: String,
    val title: String,
    val body: String,
    val source: String,
    val sourceId: String?,
    val category: String,
    val startDate: String,
    val timesJson: String,
    val recurrenceJson: String?,
    val timeZoneId: String?,
    val expiresOn: String?,
    val respectQuietHours: Boolean,
    val status: String,
    val snoozedUntil: Long?,
    val acknowledgedThrough: Long?,
    val createdAt: Long,
    val updatedAt: Long,
)

/**
 * What the app believes is currently scheduled with the OS. The id is the stable occurrence key; platform
 * adapters derive their own OS identifiers from it.
 */
@Entity(tableName = "scheduled_notifications", indices = [Index("reminderId"), Index("fireAt")])
data class ScheduledNotificationEntity(
    @PrimaryKey val id: String,
    val reminderId: String,
    val fireAt: Long,
    val contentHash: String,
    val scheduledAt: Long,
)

@Entity(tableName = "daily_plans")
data class DailyPlanEntity(
    @PrimaryKey val date: String,
    val topPriorityIdsJson: String,
    val plannedTaskIdsJson: String,
    val availableMinutes: Int?,
    val breakMinutes: Int,
    val energy: String?,
    val isRestDay: Boolean,
    val acceptedOverCapacity: Boolean,
    val notes: String,
    val createdAt: Long,
    val updatedAt: Long,
)

@Entity(tableName = "habits")
data class HabitEntity(
    @PrimaryKey val id: String,
    val title: String,
    val emoji: String,
    /** Bit i set = ISO weekday i+1 scheduled. */
    val scheduledDaysMask: Int,
    val weeklyTarget: Int,
    val reminderTime: String?,
    val streaksEnabled: Boolean,
    val pausedSince: String?,
    val category: String,
    val createdAt: Long,
    val archived: Boolean,
)

@Entity(
    tableName = "habit_completions",
    primaryKeys = ["habitId", "date"],
    foreignKeys = [ForeignKey(HabitEntity::class, ["id"], ["habitId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("date")],
)
data class HabitCompletionEntity(
    val habitId: String,
    val date: String,
    val completedAt: Long,
)

@Entity(tableName = "focus_sessions", indices = [Index("date"), Index("status"), Index("taskId")])
data class FocusSessionEntity(
    @PrimaryKey val id: String,
    val taskId: String?,
    val category: String?,
    val plannedMinutes: Int,
    val date: String,
    val startedAt: Long,
    val endedAt: Long?,
    val accumulatedSeconds: Long,
    val segmentStartedAt: Long?,
    val segmentStartedMonotonicMs: Long?,
    val pauseCount: Int,
    val status: String,
)

@Entity(tableName = "score_snapshots")
data class ScoreSnapshotEntity(
    @PrimaryKey val date: String,
    val classification: String,
    val overall: Int?,
    val dimensionsJson: String,
    val computedAt: Long,
)

@Entity(tableName = "xp_ledger", indices = [Index("date"), Index("reason")])
data class XpEntryEntity(
    @PrimaryKey val eventKey: String,
    val amount: Int,
    val reason: String,
    val sourceId: String?,
    val date: String,
    val createdAt: Long,
)

@Entity(tableName = "achievement_unlocks")
data class AchievementUnlockEntity(
    @PrimaryKey val achievementId: String,
    val unlockedAt: Long,
)

@Entity(tableName = "quests", indices = [Index("date")])
data class QuestEntity(
    @PrimaryKey val id: String,
    val date: String,
    val type: String,
    val title: String,
    val description: String,
    val target: Int,
    val progress: Int,
    val rewardXp: Int,
    val status: String,
    val completedAt: Long?,
)

@Entity(tableName = "reflections")
data class ReflectionEntity(
    @PrimaryKey val date: String,
    val wentWell: String,
    val biggestWin: String,
    val moveToTomorrow: String,
    val mood: String?,
    val tomorrowTopPriority: String,
    val updatedAt: Long,
)

@Entity(tableName = "manual_wins", indices = [Index("date")])
data class ManualWinEntity(
    @PrimaryKey val id: String,
    val date: String,
    val text: String,
    val createdAt: Long,
)

@Entity(tableName = "break_log", indices = [Index("date")])
data class BreakEntity(
    @PrimaryKey val id: String,
    val date: String,
    val createdAt: Long,
)

/** Added in schema v2: suggestions the user dismissed (until = null) or snoozed (until = epoch ms). */
@Entity(tableName = "suggestion_dismissals")
data class SuggestionDismissalEntity(
    @PrimaryKey val key: String,
    val until: Long?,
    val dismissedAt: Long,
)
