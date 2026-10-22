package com.daybloom.data.database

import com.daybloom.data.serialization.InvalidDataLog
import com.daybloom.data.serialization.StorageJson
import com.daybloom.domain.models.Appointment
import com.daybloom.domain.models.AppointmentKind
import com.daybloom.domain.models.AppointmentStatus
import com.daybloom.domain.models.DailyPlan
import com.daybloom.domain.models.DailyReflection
import com.daybloom.domain.models.DailyScore
import com.daybloom.domain.models.DayClassification
import com.daybloom.domain.models.DimensionScore
import com.daybloom.domain.models.EnergyLevel
import com.daybloom.domain.models.FocusSession
import com.daybloom.domain.models.FocusStatus
import com.daybloom.domain.models.Habit
import com.daybloom.domain.models.HabitCompletion
import com.daybloom.domain.models.ManualWin
import com.daybloom.domain.models.Mood
import com.daybloom.domain.models.NotificationCategory
import com.daybloom.domain.models.Priority
import com.daybloom.domain.models.Quest
import com.daybloom.domain.models.QuestStatus
import com.daybloom.domain.models.QuestType
import com.daybloom.domain.models.Reminder
import com.daybloom.domain.models.ReminderSource
import com.daybloom.domain.models.ReminderStatus
import com.daybloom.domain.models.ScoreDimension
import com.daybloom.domain.models.Subtask
import com.daybloom.domain.models.Task
import com.daybloom.domain.models.TaskCategory
import com.daybloom.domain.models.TaskStatus
import com.daybloom.domain.models.XpEntry
import com.daybloom.domain.models.XpReason
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime
import kotlinx.datetime.isoDayNumber
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlin.time.Instant

// region primitives

internal inline fun <reified E : Enum<E>> enumOr(name: String?, fallback: E): E =
    name?.let { n -> enumValues<E>().firstOrNull { it.name == n } } ?: run {
        if (name != null) InvalidDataLog.record("unknown ${E::class.simpleName} '$name'")
        fallback
    }

internal fun String.toLocalDateOrNull(): LocalDate? =
    runCatching { LocalDate.parse(this) }.onFailure { InvalidDataLog.record("bad date '$this'") }.getOrNull()

internal fun String.toLocalTimeOrNull(): LocalTime? =
    runCatching { LocalTime.parse(this) }.onFailure { InvalidDataLog.record("bad time '$this'") }.getOrNull()

internal fun LocalTime.toStorage(): String =
    "${hour.toString().padStart(2, '0')}:${minute.toString().padStart(2, '0')}"

internal fun Long.toInstant(): Instant = Instant.fromEpochMilliseconds(this)

internal fun daysToMask(days: Set<DayOfWeek>): Int = days.fold(0) { acc, d -> acc or (1 shl (d.isoDayNumber - 1)) }

internal fun maskToDays(mask: Int): Set<DayOfWeek> =
    DayOfWeek.entries.filter { mask and (1 shl (it.isoDayNumber - 1)) != 0 }.toSet()

// endregion

// region tasks

fun TaskWithSubtasks.toDomain(): Task? {
    val t = task
    if (t.title.isBlank()) {
        InvalidDataLog.record("task ${t.id} without title")
    }
    return Task(
        id = t.id,
        title = t.title.ifBlank { "Untitled task" },
        description = t.description,
        category = enumOr(t.category, TaskCategory.OTHER),
        priority = enumOr(t.priority, Priority.NORMAL),
        dueDate = t.dueDate?.toLocalDateOrNull(),
        dueTime = t.dueTime?.toLocalTimeOrNull(),
        estimatedMinutes = t.estimatedMinutes?.takeIf { it > 0 },
        actualMinutes = t.actualMinutes?.takeIf { it >= 0 },
        status = enumOr(t.status, TaskStatus.OPEN),
        recurrence = StorageJson.decodeRecurrence(t.recurrenceJson),
        seriesId = t.seriesId,
        subtasks = subtasks.sortedBy { it.sortOrder }.map { Subtask(it.id, it.title, it.isDone, it.sortOrder) },
        notes = t.notes,
        tags = StorageJson.decodeStrings(t.tagsJson),
        isFlexible = t.isFlexible,
        appointmentId = t.appointmentId,
        postponeCount = t.postponeCount.coerceAtLeast(0),
        createdAt = t.createdAt.toInstant(),
        updatedAt = t.updatedAt.toInstant(),
        completedAt = t.completedAt?.toInstant(),
        completedOn = t.completedOn?.toLocalDateOrNull(),
        cancelledAt = t.cancelledAt?.toInstant(),
    )
}

fun Task.toEntity(): TaskEntity = TaskEntity(
    id = id,
    title = title,
    description = description,
    category = category.name,
    priority = priority.name,
    dueDate = dueDate?.toString(),
    dueTime = dueTime?.toStorage(),
    estimatedMinutes = estimatedMinutes,
    actualMinutes = actualMinutes,
    status = status.name,
    recurrenceJson = StorageJson.encodeRecurrence(recurrence),
    seriesId = seriesId,
    notes = notes,
    tagsJson = StorageJson.encodeStrings(tags),
    isFlexible = isFlexible,
    appointmentId = appointmentId,
    postponeCount = postponeCount,
    createdAt = createdAt.toEpochMilliseconds(),
    updatedAt = updatedAt.toEpochMilliseconds(),
    completedAt = completedAt?.toEpochMilliseconds(),
    completedOn = completedOn?.toString(),
    cancelledAt = cancelledAt?.toEpochMilliseconds(),
)

fun Task.subtaskEntities(): List<SubtaskEntity> =
    subtasks.mapIndexed { i, s -> SubtaskEntity(s.id, id, s.title, s.isDone, i) }

// endregion

// region appointments & reminders

fun AppointmentEntity.toDomain(): Appointment? {
    val d = date.toLocalDateOrNull() ?: return null
    val start = startTime.toLocalTimeOrNull() ?: return null
    val end = endTime.toLocalTimeOrNull()?.takeIf { it > start } ?: start
    return Appointment(
        id = id,
        title = title.ifBlank { "Appointment" },
        kind = enumOr(kind, AppointmentKind.OTHER),
        date = d,
        startTime = start,
        endTime = end,
        timeZoneId = timeZoneId,
        location = location,
        attendees = StorageJson.decodeStrings(attendeesJson),
        notes = notes,
        recurrence = StorageJson.decodeRecurrence(recurrenceJson),
        status = enumOr(status, AppointmentStatus.SCHEDULED),
        reminderOffsetsMinutes = StorageJson.decodeInts(reminderOffsetsJson).filter { it >= 0 },
        createdAt = createdAt.toInstant(),
        updatedAt = updatedAt.toInstant(),
    )
}

fun Appointment.toEntity(): AppointmentEntity = AppointmentEntity(
    id = id,
    title = title,
    kind = kind.name,
    date = date.toString(),
    startTime = startTime.toStorage(),
    endTime = endTime.toStorage(),
    timeZoneId = timeZoneId,
    location = location,
    attendeesJson = StorageJson.encodeStrings(attendees),
    notes = notes,
    recurrenceJson = StorageJson.encodeRecurrence(recurrence),
    status = status.name,
    reminderOffsetsJson = StorageJson.encodeInts(reminderOffsetsMinutes),
    createdAt = createdAt.toEpochMilliseconds(),
    updatedAt = updatedAt.toEpochMilliseconds(),
)

fun ReminderEntity.toDomain(): Reminder? {
    val start = startDate.toLocalDateOrNull() ?: return null
    val times = StorageJson.decodeStrings(timesJson).mapNotNull { it.toLocalTimeOrNull() }
    if (times.isEmpty()) {
        InvalidDataLog.record("reminder $id has no valid times")
        return null
    }
    return Reminder(
        id = id,
        title = title.ifBlank { "Reminder" },
        body = body,
        source = enumOr(source, ReminderSource.CUSTOM),
        sourceId = sourceId,
        category = enumOr(category, NotificationCategory.GENERAL),
        startDate = start,
        times = times,
        recurrence = StorageJson.decodeRecurrence(recurrenceJson),
        timeZoneId = timeZoneId,
        expiresOn = expiresOn?.toLocalDateOrNull(),
        respectQuietHours = respectQuietHours,
        status = enumOr(status, ReminderStatus.ACTIVE),
        snoozedUntil = snoozedUntil?.toInstant(),
        acknowledgedThrough = acknowledgedThrough?.toInstant(),
        createdAt = createdAt.toInstant(),
        updatedAt = updatedAt.toInstant(),
    )
}

fun Reminder.toEntity(): ReminderEntity = ReminderEntity(
    id = id,
    title = title,
    body = body,
    source = source.name,
    sourceId = sourceId,
    category = category.name,
    startDate = startDate.toString(),
    timesJson = StorageJson.encodeStrings(times.map { it.toStorage() }),
    recurrenceJson = StorageJson.encodeRecurrence(recurrence),
    timeZoneId = timeZoneId,
    expiresOn = expiresOn?.toString(),
    respectQuietHours = respectQuietHours,
    status = status.name,
    snoozedUntil = snoozedUntil?.toEpochMilliseconds(),
    acknowledgedThrough = acknowledgedThrough?.toEpochMilliseconds(),
    createdAt = createdAt.toEpochMilliseconds(),
    updatedAt = updatedAt.toEpochMilliseconds(),
)

// endregion

// region planning, habits, focus

fun DailyPlanEntity.toDomain(): DailyPlan? {
    val d = date.toLocalDateOrNull() ?: return null
    return DailyPlan(
        date = d,
        topPriorityTaskIds = StorageJson.decodeStrings(topPriorityIdsJson).distinct().take(DailyPlan.MAX_TOP_PRIORITIES),
        plannedTaskIds = StorageJson.decodeStrings(plannedTaskIdsJson).distinct(),
        availableMinutes = availableMinutes?.takeIf { it >= 0 },
        breakMinutes = breakMinutes.coerceAtLeast(0),
        energy = energy?.let { enumOr(it, EnergyLevel.MEDIUM) },
        isRestDay = isRestDay,
        acceptedOverCapacity = acceptedOverCapacity,
        notes = notes,
        createdAt = createdAt.toInstant(),
        updatedAt = updatedAt.toInstant(),
    )
}

fun DailyPlan.toEntity(): DailyPlanEntity = DailyPlanEntity(
    date = date.toString(),
    topPriorityIdsJson = StorageJson.encodeStrings(topPriorityTaskIds),
    plannedTaskIdsJson = StorageJson.encodeStrings(plannedTaskIds),
    availableMinutes = availableMinutes,
    breakMinutes = breakMinutes,
    energy = energy?.name,
    isRestDay = isRestDay,
    acceptedOverCapacity = acceptedOverCapacity,
    notes = notes,
    createdAt = createdAt.toEpochMilliseconds(),
    updatedAt = updatedAt.toEpochMilliseconds(),
)

fun HabitEntity.toDomain(): Habit {
    val days = maskToDays(scheduledDaysMask).ifEmpty { DayOfWeek.entries.toSet() }
    return Habit(
        id = id,
        title = title.ifBlank { "Habit" },
        emoji = emoji.ifBlank { "🌱" },
        scheduledDays = days,
        weeklyTarget = weeklyTarget.coerceIn(1, 7),
        reminderTime = reminderTime?.toLocalTimeOrNull(),
        streaksEnabled = streaksEnabled,
        pausedSince = pausedSince?.toLocalDateOrNull(),
        category = enumOr(category, TaskCategory.HEALTH),
        createdAt = createdAt.toInstant(),
        archived = archived,
    )
}

fun Habit.toEntity(): HabitEntity = HabitEntity(
    id = id,
    title = title,
    emoji = emoji,
    scheduledDaysMask = daysToMask(scheduledDays),
    weeklyTarget = weeklyTarget,
    reminderTime = reminderTime?.toStorage(),
    streaksEnabled = streaksEnabled,
    pausedSince = pausedSince?.toString(),
    category = category.name,
    createdAt = createdAt.toEpochMilliseconds(),
    archived = archived,
)

fun HabitCompletionEntity.toDomain(): HabitCompletion? =
    date.toLocalDateOrNull()?.let { HabitCompletion(habitId, it, completedAt.toInstant()) }

fun FocusSessionEntity.toDomain(): FocusSession? {
    val d = date.toLocalDateOrNull() ?: return null
    return FocusSession(
        id = id,
        taskId = taskId,
        category = category?.let { enumOr(it, TaskCategory.OTHER) },
        plannedMinutes = plannedMinutes.coerceIn(1, 24 * 60),
        date = d,
        startedAt = startedAt.toInstant(),
        endedAt = endedAt?.toInstant(),
        accumulatedSeconds = accumulatedSeconds.coerceAtLeast(0),
        segmentStartedAt = segmentStartedAt?.toInstant(),
        segmentStartedMonotonicMs = segmentStartedMonotonicMs,
        pauseCount = pauseCount.coerceAtLeast(0),
        status = enumOr(status, FocusStatus.INTERRUPTED),
    )
}

fun FocusSession.toEntity(): FocusSessionEntity = FocusSessionEntity(
    id = id,
    taskId = taskId,
    category = category?.name,
    plannedMinutes = plannedMinutes,
    date = date.toString(),
    startedAt = startedAt.toEpochMilliseconds(),
    endedAt = endedAt?.toEpochMilliseconds(),
    accumulatedSeconds = accumulatedSeconds,
    segmentStartedAt = segmentStartedAt?.toEpochMilliseconds(),
    segmentStartedMonotonicMs = segmentStartedMonotonicMs,
    pauseCount = pauseCount,
    status = status.name,
)

// endregion

// region progress

@Serializable
private data class StoredDimension(val d: String, val s: Int? = null, val w: Double = 0.0, val t: String = "")

private val dimensionListSerializer = ListSerializer(StoredDimension.serializer())

fun ScoreSnapshotEntity.toDomain(): DailyScore? {
    val d = date.toLocalDateOrNull() ?: return null
    val stored = StorageJson.decodeOr(emptyList()) { StorageJson.json.decodeFromString(dimensionListSerializer, dimensionsJson) }
    val dims = stored.mapNotNull { sd ->
        ScoreDimension.entries.firstOrNull { it.name == sd.d }?.let { DimensionScore(it, sd.s?.coerceIn(0, 100), sd.w, sd.t) }
    }
    return DailyScore(
        date = d,
        classification = enumOr(classification, DayClassification.NO_DATA),
        overall = overall?.coerceIn(0, 100),
        dimensions = dims,
        computedAt = computedAt.toInstant(),
    )
}

fun DailyScore.toEntity(): ScoreSnapshotEntity = ScoreSnapshotEntity(
    date = date.toString(),
    classification = classification.name,
    overall = overall,
    dimensionsJson = StorageJson.json.encodeToString(
        dimensionListSerializer,
        dimensions.map { StoredDimension(it.dimension.name, it.score, it.effectiveWeight, it.detail) },
    ),
    computedAt = computedAt.toEpochMilliseconds(),
)

fun XpEntryEntity.toDomain(): XpEntry? = date.toLocalDateOrNull()?.let {
    XpEntry(eventKey, amount, enumOr(reason, XpReason.TASK_COMPLETED), sourceId, it, createdAt.toInstant())
}

fun XpEntry.toEntity(): XpEntryEntity =
    XpEntryEntity(eventKey, amount, reason.name, sourceId, date.toString(), createdAt.toEpochMilliseconds())

fun QuestEntity.toDomain(): Quest? {
    val d = date.toLocalDateOrNull() ?: return null
    val type = QuestType.entries.firstOrNull { it.name == type } ?: return null
    return Quest(
        id = id,
        date = d,
        type = type,
        title = title,
        description = description,
        target = target.coerceAtLeast(1),
        progress = progress.coerceAtLeast(0),
        rewardXp = rewardXp.coerceAtLeast(0),
        status = enumOr(status, QuestStatus.ACTIVE),
        completedAt = completedAt?.toInstant(),
    )
}

fun Quest.toEntity(): QuestEntity = QuestEntity(
    id = id,
    date = date.toString(),
    type = type.name,
    title = title,
    description = description,
    target = target,
    progress = progress,
    rewardXp = rewardXp,
    status = status.name,
    completedAt = completedAt?.toEpochMilliseconds(),
)

fun ReflectionEntity.toDomain(): DailyReflection? = date.toLocalDateOrNull()?.let {
    DailyReflection(it, wentWell, biggestWin, moveToTomorrow, mood?.let { m -> enumOr(m, Mood.OKAY) }, tomorrowTopPriority, updatedAt.toInstant())
}

fun DailyReflection.toEntity(): ReflectionEntity = ReflectionEntity(
    date = date.toString(),
    wentWell = wentWell,
    biggestWin = biggestWin,
    moveToTomorrow = moveToTomorrow,
    mood = mood?.name,
    tomorrowTopPriority = tomorrowTopPriority,
    updatedAt = updatedAt.toEpochMilliseconds(),
)

fun ManualWinEntity.toDomain(): ManualWin? = date.toLocalDateOrNull()?.let { ManualWin(id, it, text, createdAt.toInstant()) }

fun ManualWin.toEntity(): ManualWinEntity = ManualWinEntity(id, date.toString(), text, createdAt.toEpochMilliseconds())

// endregion
