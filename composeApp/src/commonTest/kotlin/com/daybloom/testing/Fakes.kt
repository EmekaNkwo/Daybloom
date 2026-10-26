package com.daybloom.testing

import com.daybloom.domain.models.AchievementUnlock
import com.daybloom.domain.models.Appointment
import com.daybloom.domain.models.AppointmentStatus
import com.daybloom.domain.models.DailyPlan
import com.daybloom.domain.models.DailyReflection
import com.daybloom.domain.models.DailyScore
import com.daybloom.domain.models.FocusSession
import com.daybloom.domain.models.Habit
import com.daybloom.domain.models.HabitCompletion
import com.daybloom.domain.models.ManualWin
import com.daybloom.domain.models.Quest
import com.daybloom.domain.models.QuestStatus
import com.daybloom.domain.models.Reminder
import com.daybloom.domain.models.ReminderSource
import com.daybloom.domain.models.ReminderStatus
import com.daybloom.domain.models.Task
import com.daybloom.domain.models.TaskCategory
import com.daybloom.domain.models.TaskStatus
import com.daybloom.domain.models.UserPreferences
import com.daybloom.domain.models.XpEntry
import com.daybloom.domain.models.XpReason
import com.daybloom.domain.repositories.AchievementRepository
import com.daybloom.domain.repositories.AppointmentRepository
import com.daybloom.domain.repositories.DataResetRepository
import com.daybloom.domain.repositories.FocusRepository
import com.daybloom.domain.repositories.HabitRepository
import com.daybloom.domain.repositories.JournalRepository
import com.daybloom.domain.repositories.PlanRepository
import com.daybloom.domain.repositories.PreferencesRepository
import com.daybloom.domain.repositories.QuestRepository
import com.daybloom.domain.repositories.ReminderRepository
import com.daybloom.domain.repositories.ScheduledRecord
import com.daybloom.domain.repositories.ScoreRepository
import com.daybloom.domain.repositories.TaskRepository
import com.daybloom.domain.repositories.XpRepository
import com.daybloom.domain.usecases.SuggestionDismissalStore
import com.daybloom.notifications.ExactTimingState
import com.daybloom.notifications.NotificationPermissionManager
import com.daybloom.notifications.NotificationRequest
import com.daybloom.notifications.NotificationScheduler
import com.daybloom.notifications.NotificationStatus
import com.daybloom.notifications.PermissionState
import com.daybloom.notifications.SchedulerCapabilities
import com.daybloom.platform.IdGenerator
import com.daybloom.platform.PlatformDateTimeService
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlin.time.Duration
import kotlin.time.Instant

/** Controllable clock. Wall clock and monotonic clock can be moved independently to simulate clock changes. */
class FakeClock(
    start: LocalDateTime = LocalDateTime(2026, 10, 22, 9, 0),
    var zone: TimeZone = TimeZone.of("Europe/London"),
) : PlatformDateTimeService {
    var instant: Instant = start.toInstant(zone)
    var monotonic: Long = 1_000_000L
    val changes = MutableSharedFlow<Unit>(extraBufferCapacity = 4)

    override fun now(): Instant = instant
    override fun timeZone(): TimeZone = zone
    override fun monotonicMillis(): Long = monotonic
    override val timeChanges: Flow<Unit> = changes

    fun advance(by: Duration) {
        instant += by
        monotonic += by.inWholeMilliseconds
    }

    fun setLocal(dateTime: LocalDateTime) {
        val target = dateTime.toInstant(zone)
        monotonic += (target - instant).inWholeMilliseconds.coerceAtLeast(0)
        instant = target
    }
}

class SequentialIds(private val prefix: String = "id") : IdGenerator {
    private var next = 0
    override fun newId(): String = "$prefix-${++next}"
}

class FakeNotificationScheduler(
    var caps: SchedulerCapabilities = SchedulerCapabilities(maxPending = 64, exactTiming = true),
    /** iOS lists everything pending; Android can only probe the ids it's asked about (pass false). */
    private val enumeratesAll: Boolean = true,
) : NotificationScheduler {
    val pending = linkedMapOf<String, NotificationRequest>()
    val scheduleCalls = mutableListOf<NotificationRequest>()
    val cancelCalls = mutableListOf<String>()
    val shown = mutableListOf<NotificationRequest>()

    override suspend fun capabilities() = caps
    override suspend fun schedule(requests: List<NotificationRequest>) {
        requests.forEach { pending[it.id] = it; scheduleCalls += it }
    }
    /** Ids cancelled with removeDelivered = true, i.e. calls that would also remove an on-screen notification. */
    val removedDelivered = mutableListOf<String>()

    override suspend fun cancel(ids: Collection<String>, removeDelivered: Boolean) {
        ids.forEach { pending.remove(it); cancelCalls += it; if (removeDelivered) removedDelivered += it }
    }
    override suspend fun pendingIds(candidates: Collection<String>): Set<String> =
        if (enumeratesAll) pending.keys.toSet() else pending.keys.intersect(candidates.toSet())
    override suspend fun showNow(request: NotificationRequest) {
        shown += request
    }

    /** Simulates the OS dropping every alarm (reboot / force-stop). */
    fun wipe() = pending.clear()
}

class FakePermissionManager(var state: PermissionState = PermissionState.GRANTED) : NotificationPermissionManager {
    var requests = 0
    var grantOnRequest = true
    override suspend fun status() = NotificationStatus(state, ExactTimingState.AVAILABLE)
    override suspend fun requestPermission(): PermissionState {
        requests++
        if (grantOnRequest) state = PermissionState.GRANTED
        return state
    }
    override fun openExactTimingSettings() = Unit
    override fun openNotificationSettings() = Unit
}

/** In-memory persistence for every repository interface, sharing one store so "restarting" can reuse it. */
class InMemoryStore {
    val tasks = MutableStateFlow<Map<String, Task>>(emptyMap())
    val appointments = MutableStateFlow<Map<String, Appointment>>(emptyMap())
    val occurrenceStatuses = MutableStateFlow<Map<String, AppointmentStatus>>(emptyMap())
    val reminders = MutableStateFlow<Map<String, Reminder>>(emptyMap())
    val scheduled = MutableStateFlow<Map<String, ScheduledRecord>>(emptyMap())
    val plans = MutableStateFlow<Map<LocalDate, DailyPlan>>(emptyMap())
    val habits = MutableStateFlow<Map<String, Habit>>(emptyMap())
    val habitCompletions = MutableStateFlow<Set<HabitCompletion>>(emptySet())
    val focus = MutableStateFlow<Map<String, FocusSession>>(emptyMap())
    val scores = MutableStateFlow<Map<LocalDate, DailyScore>>(emptyMap())
    val xp = MutableStateFlow<Map<String, XpEntry>>(emptyMap())
    val unlocks = MutableStateFlow<Map<String, AchievementUnlock>>(emptyMap())
    val quests = MutableStateFlow<Map<String, Quest>>(emptyMap())
    val reflections = MutableStateFlow<Map<LocalDate, DailyReflection>>(emptyMap())
    val wins = MutableStateFlow<Map<String, ManualWin>>(emptyMap())
    val breaks = MutableStateFlow<List<Pair<String, LocalDate>>>(emptyList())
    val dismissals = MutableStateFlow<Map<String, Instant?>>(emptyMap())
    // Digests are off by default here so tests that count pending notifications see only their own reminders;
    // DailyTaskDigestTest turns them on.
    val preferences = MutableStateFlow(UserPreferences(onboardingComplete = true, morningDigestTime = null, eveningDigestTime = null))
}

class InMemoryTaskRepository(private val s: InMemoryStore) : TaskRepository {
    private fun all() = s.tasks.value.values.toList()
    private fun relevant(list: Collection<Task>, date: LocalDate, ids: List<String>) =
        list.filter { it.dueDate == date || it.completedOn == date || it.id in ids }

    override fun observeAll() = s.tasks.map { it.values.toList() }
    override fun observe(id: String) = s.tasks.map { it[id] }
    override fun observeRelevantTo(date: LocalDate, extraIds: List<String>) = s.tasks.map { relevant(it.values, date, extraIds) }
    override fun observeDueBetween(from: LocalDate, to: LocalDate) = s.tasks.map { m -> m.values.filter { t -> t.dueDate?.let { it in from..to } == true } }
    override fun observeCompletedBetween(from: LocalDate, to: LocalDate) = s.tasks.map { m -> m.values.filter { t -> t.completedOn?.let { it in from..to } == true } }
    override fun observeForAppointment(appointmentId: String) = s.tasks.map { m -> m.values.filter { it.appointmentId == appointmentId } }
    override suspend fun get(id: String) = s.tasks.value[id]
    override suspend fun getByIds(ids: List<String>) = ids.mapNotNull { s.tasks.value[it] }
    override suspend fun getRelevantTo(date: LocalDate, extraIds: List<String>) = relevant(all(), date, extraIds)
    override suspend fun getDueBetween(from: LocalDate, to: LocalDate) = all().filter { t -> t.dueDate?.let { it in from..to } == true }
    override suspend fun getCompletedBetween(from: LocalDate, to: LocalDate) = all().filter { t -> t.completedOn?.let { it in from..to } == true }
    override suspend fun getOpen() = all().filter { it.status == TaskStatus.OPEN }
    override suspend fun getForAppointment(appointmentId: String) = all().filter { it.appointmentId == appointmentId }
    override suspend fun existsInSeriesOn(seriesId: String, date: LocalDate) = all().any { it.seriesId == seriesId && it.dueDate == date }
    override suspend fun completionDates() = all().mapNotNull { it.completedOn }.toSet()
    override suspend fun completionCategories(): List<Pair<LocalDate, TaskCategory>> = all().mapNotNull { t -> t.completedOn?.let { it to t.category } }
    override suspend fun countCompleted() = all().count { it.status == TaskStatus.COMPLETED }
    override suspend fun save(task: Task) { s.tasks.value = s.tasks.value + (task.id to task) }
    override suspend fun delete(id: String) { s.tasks.value = s.tasks.value - id }
}

class InMemoryAppointmentRepository(private val s: InMemoryStore) : AppointmentRepository {
    override fun observeAll() = s.appointments.map { it.values.toList() }
    override fun observe(id: String) = s.appointments.map { it[id] }
    override fun observeOccurrenceStatuses() = s.occurrenceStatuses
    override suspend fun get(id: String) = s.appointments.value[id]
    override suspend fun getAll() = s.appointments.value.values.toList()
    override suspend fun getOccurrenceStatuses() = s.occurrenceStatuses.value
    override suspend fun save(appointment: Appointment) { s.appointments.value = s.appointments.value + (appointment.id to appointment) }
    override suspend fun delete(id: String) { s.appointments.value = s.appointments.value - id }
    override suspend fun setOccurrenceStatus(appointmentId: String, date: LocalDate, status: AppointmentStatus, at: Instant) {
        s.occurrenceStatuses.value = s.occurrenceStatuses.value + ("$appointmentId@$date" to status)
    }
}

class InMemoryReminderRepository(private val s: InMemoryStore) : ReminderRepository {
    override fun observeAll() = s.reminders.map { it.values.toList() }
    override fun observeForSource(source: ReminderSource, sourceId: String) = s.reminders.map { m -> m.values.filter { it.source == source && it.sourceId == sourceId } }
    override fun observeScheduled() = s.scheduled.map { it.values.toList() }
    override suspend fun getAll() = s.reminders.value.values.toList()
    override suspend fun getActive() = getAll().filter { it.status == ReminderStatus.ACTIVE }
    override suspend fun get(id: String) = s.reminders.value[id]
    override suspend fun getForSource(source: ReminderSource, sourceId: String) = getAll().filter { it.source == source && it.sourceId == sourceId }
    override suspend fun save(reminder: Reminder) { s.reminders.value = s.reminders.value + (reminder.id to reminder) }
    override suspend fun delete(id: String) { s.reminders.value = s.reminders.value - id }
    override suspend fun deleteForSource(source: ReminderSource, sourceId: String) {
        s.reminders.value = s.reminders.value.filterValues { !(it.source == source && it.sourceId == sourceId) }
    }
    override suspend fun getScheduled() = s.scheduled.value.values.toList()
    override suspend fun recordScheduled(records: List<ScheduledRecord>, at: Instant) { s.scheduled.value = s.scheduled.value + records.associateBy { it.id } }
    override suspend fun removeScheduled(ids: Collection<String>) { s.scheduled.value = s.scheduled.value - ids.toSet() }
    override suspend fun clearScheduled() { s.scheduled.value = emptyMap() }
}

class InMemoryPlanRepository(private val s: InMemoryStore) : PlanRepository {
    override fun observe(date: LocalDate) = s.plans.map { it[date] }
    override fun observeBetween(from: LocalDate, to: LocalDate) = s.plans.map { m -> m.values.filter { it.date in from..to } }
    override suspend fun get(date: LocalDate) = s.plans.value[date]
    override suspend fun getBetween(from: LocalDate, to: LocalDate) = s.plans.value.values.filter { it.date in from..to }
    override suspend fun getAll() = s.plans.value.values.toList()
    override suspend fun save(plan: DailyPlan) { s.plans.value = s.plans.value + (plan.date to plan) }
}

class InMemoryHabitRepository(private val s: InMemoryStore) : HabitRepository {
    override fun observeHabits() = s.habits.map { m -> m.values.filter { !it.archived } }
    override fun observeArchived() = s.habits.map { m -> m.values.filter { it.archived }.sortedBy { it.title.lowercase() } }
    override fun observeCompletions() = s.habitCompletions.map { it.toList() }
    override suspend fun getHabits() = s.habits.value.values.toList()
    override suspend fun get(id: String) = s.habits.value[id]
    override suspend fun save(habit: Habit) { s.habits.value = s.habits.value + (habit.id to habit) }
    override suspend fun delete(id: String) { s.habits.value = s.habits.value - id }
    override suspend fun getCompletions() = s.habitCompletions.value.toList()
    override suspend fun getCompletionsBetween(from: LocalDate, to: LocalDate) = s.habitCompletions.value.filter { it.date in from..to }
    override suspend fun setCompleted(habitId: String, date: LocalDate, done: Boolean, at: Instant) {
        val others = s.habitCompletions.value.filterNot { it.habitId == habitId && it.date == date }.toSet()
        s.habitCompletions.value = if (done) others + HabitCompletion(habitId, date, at) else others
    }
}

class InMemoryFocusRepository(private val s: InMemoryStore) : FocusRepository {
    override fun observeActive() = s.focus.map { m -> m.values.filter { it.isActive }.maxByOrNull { it.startedAt } }
    override fun observeBetween(from: LocalDate, to: LocalDate) = s.focus.map { m -> m.values.filter { it.date in from..to } }
    override suspend fun getActive() = s.focus.value.values.filter { it.isActive }.maxByOrNull { it.startedAt }
    override suspend fun get(id: String) = s.focus.value[id]
    override suspend fun getBetween(from: LocalDate, to: LocalDate) = s.focus.value.values.filter { it.date in from..to }
    override suspend fun getAll() = s.focus.value.values.toList()
    override suspend fun save(session: FocusSession) { s.focus.value = s.focus.value + (session.id to session) }
}

class InMemoryScoreRepository(private val s: InMemoryStore) : ScoreRepository {
    var saves = 0
    override fun observe(date: LocalDate) = s.scores.map { it[date] }
    override fun observeBetween(from: LocalDate, to: LocalDate) = s.scores.map { m -> m.values.filter { it.date in from..to }.sortedBy { it.date } }
    override suspend fun get(date: LocalDate) = s.scores.value[date]
    override suspend fun getBetween(from: LocalDate, to: LocalDate) = s.scores.value.values.filter { it.date in from..to }.sortedBy { it.date }
    override suspend fun save(score: DailyScore) { saves++; s.scores.value = s.scores.value + (score.date to score) }
}

class InMemoryXpRepository(private val s: InMemoryStore) : XpRepository {
    override suspend fun award(entry: XpEntry): Boolean {
        if (entry.eventKey in s.xp.value) return false
        s.xp.value = s.xp.value + (entry.eventKey to entry)
        return true
    }
    override suspend fun revoke(eventKey: String): Boolean {
        val had = eventKey in s.xp.value
        s.xp.value = s.xp.value - eventKey
        return had
    }
    override fun observeTotal() = s.xp.map { m -> m.values.sumOf { it.amount } }
    override fun observeRecent(limit: Int) = s.xp.map { m -> m.values.sortedByDescending { it.createdAt }.take(limit) }
    override suspend fun total() = s.xp.value.values.sumOf { it.amount }
    override suspend fun countRewardedTasksOn(date: LocalDate) = s.xp.value.values.count {
        it.date == date && (it.reason == XpReason.TASK_COMPLETED || it.reason == XpReason.TOP_PRIORITY_COMPLETED) && it.amount > 0
    }
    override suspend fun countByReason(reason: XpReason) = s.xp.value.values.count { it.reason == reason }
    override suspend fun exists(eventKey: String) = eventKey in s.xp.value
}

class InMemoryAchievementRepository(private val s: InMemoryStore) : AchievementRepository {
    override fun observeUnlocks() = s.unlocks.map { it.values.toList() }
    override suspend fun getUnlocks() = s.unlocks.value.values.toList()
    override suspend fun unlock(achievementId: String, at: Instant): Boolean {
        if (achievementId in s.unlocks.value) return false
        s.unlocks.value = s.unlocks.value + (achievementId to AchievementUnlock(achievementId, at))
        return true
    }
}

class InMemoryQuestRepository(private val s: InMemoryStore) : QuestRepository {
    override fun observeForDate(date: LocalDate) = s.quests.map { m -> m.values.filter { it.date == date }.sortedBy { it.id } }
    override fun observeCompletedCount() = s.quests.map { m -> m.values.count { it.status == QuestStatus.COMPLETED } }
    override suspend fun getForDate(date: LocalDate) = s.quests.value.values.filter { it.date == date }.sortedBy { it.id }
    override suspend fun insertIfAbsent(quests: List<Quest>) {
        s.quests.value = s.quests.value + quests.filter { it.id !in s.quests.value }.associateBy { it.id }
    }
    override suspend fun update(quest: Quest) { s.quests.value = s.quests.value + (quest.id to quest) }
    override suspend fun countCompleted() = s.quests.value.values.count { it.status == QuestStatus.COMPLETED }
}

class InMemoryJournalRepository(private val s: InMemoryStore) : JournalRepository {
    override fun observeReflection(date: LocalDate) = s.reflections.map { it[date] }
    override fun observeReflections() = s.reflections.map { it.values.sortedByDescending { r -> r.date } }
    override fun observeWins(date: LocalDate) = s.wins.map { m -> m.values.filter { it.date == date } }
    override fun observeBreakCount(date: LocalDate) = s.breaks.map { l -> l.count { it.second == date } }
    override suspend fun getReflection(date: LocalDate) = s.reflections.value[date]
    override suspend fun getReflections() = s.reflections.value.values.toList()
    override suspend fun saveReflection(reflection: DailyReflection) { s.reflections.value = s.reflections.value + (reflection.date to reflection) }
    override suspend fun deleteReflection(date: LocalDate) { s.reflections.value = s.reflections.value - date }
    override suspend fun saveWin(win: ManualWin) { s.wins.value = s.wins.value + (win.id to win) }
    override suspend fun deleteWin(id: String) { s.wins.value = s.wins.value - id }
    override suspend fun logBreak(id: String, date: LocalDate, at: Instant) { s.breaks.value = s.breaks.value + (id to date) }
    override suspend fun countBreaks(date: LocalDate) = s.breaks.value.count { it.second == date }
}

class InMemoryPreferencesRepository(private val s: InMemoryStore) : PreferencesRepository {
    override val preferences: Flow<UserPreferences> = s.preferences
    override suspend fun current() = s.preferences.value
    override suspend fun update(transform: (UserPreferences) -> UserPreferences) { s.preferences.value = transform(s.preferences.value) }
    override suspend fun reset() { s.preferences.value = UserPreferences() }
}

class InMemoryDismissals(private val s: InMemoryStore) : SuggestionDismissalStore {
    override suspend fun activeKeys(now: Instant) = s.dismissals.value.filterValues { it == null || it > now }.keys
    override suspend fun dismiss(key: String, until: Instant?, now: Instant) { s.dismissals.value = s.dismissals.value + (key to until) }
}

class InMemoryDataReset(private val s: InMemoryStore) : DataResetRepository {
    override suspend fun deleteAllData() {
        s.tasks.value = emptyMap(); s.appointments.value = emptyMap(); s.occurrenceStatuses.value = emptyMap()
        s.reminders.value = emptyMap(); s.scheduled.value = emptyMap(); s.plans.value = emptyMap()
        s.habits.value = emptyMap(); s.habitCompletions.value = emptySet(); s.focus.value = emptyMap()
        s.scores.value = emptyMap(); s.xp.value = emptyMap(); s.unlocks.value = emptyMap(); s.quests.value = emptyMap()
        s.reflections.value = emptyMap(); s.wins.value = emptyMap(); s.breaks.value = emptyList(); s.dismissals.value = emptyMap()
    }
}
