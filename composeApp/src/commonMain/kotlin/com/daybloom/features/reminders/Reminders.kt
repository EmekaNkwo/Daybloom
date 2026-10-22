package com.daybloom.features.reminders

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.daybloom.components.BloomCard
import com.daybloom.components.BloomDatePickerDialog
import com.daybloom.components.BloomTimePickerDialog
import com.daybloom.components.BloomTopBar
import com.daybloom.components.ChoiceChips
import com.daybloom.components.EmptyState
import com.daybloom.components.Format
import com.daybloom.components.LoadingState
import com.daybloom.components.SectionHeader
import com.daybloom.design.BloomTheme
import com.daybloom.design.Spacing
import androidx.compose.foundation.layout.offset
import com.daybloom.domain.models.NotificationCategory
import com.daybloom.domain.models.RecurrenceRule
import com.daybloom.domain.models.Reminder
import com.daybloom.domain.models.ReminderSource
import com.daybloom.domain.models.ReminderStatus
import com.daybloom.domain.repositories.ReminderRepository
import com.daybloom.domain.services.ReminderOccurrenceCalculator
import com.daybloom.domain.usecases.DeleteReminderUseCase
import com.daybloom.domain.usecases.HandleReminderActionUseCase
import com.daybloom.domain.usecases.NotificationAction
import com.daybloom.domain.usecases.ReminderDraft
import com.daybloom.domain.usecases.ScheduleReminderUseCase
import com.daybloom.domain.repositories.PreferencesRepository
import com.daybloom.features.common.AppMessages
import com.daybloom.features.common.runAction
import com.daybloom.notifications.ExactTimingState
import com.daybloom.notifications.NotificationPermissionManager
import com.daybloom.notifications.NotificationRequest
import com.daybloom.notifications.NotificationScheduler
import com.daybloom.notifications.PermissionState
import com.daybloom.notifications.ReconcileReason
import com.daybloom.notifications.ReminderDeliveryStatus
import com.daybloom.notifications.ReminderReconciler
import com.daybloom.platform.PlatformDateTimeService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.datetime.toLocalDateTime
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf
import com.daybloom.components.BloomButtons
import com.daybloom.components.BloomTextField
import com.daybloom.domain.services.DateMath
import com.daybloom.features.common.AppMessage
import com.daybloom.components.rememberHasChanges
import com.daybloom.components.unsavedChangesGuard
import com.daybloom.components.SwitchRow

data class ReminderItem(val reminder: Reminder, val next: LocalDateTime?)

private const val TEST_NOTIFICATION_ID = "daybloom-test-notification"

data class RemindersState(
    val loading: Boolean = true,
    val delivery: ReminderDeliveryStatus = ReminderDeliveryStatus(),
    val active: List<ReminderItem> = emptyList(),
    val inactive: List<Reminder> = emptyList(),
    val scheduledCount: Int = 0,
)

class RemindersViewModel(
    reminders: ReminderRepository,
    private val preferences: PreferencesRepository,
    private val reconciler: ReminderReconciler,
    private val permissions: NotificationPermissionManager,
    private val scheduler: NotificationScheduler,
    private val handle: HandleReminderActionUseCase,
    private val delete: DeleteReminderUseCase,
    private val messages: AppMessages,
    private val clock: PlatformDateTimeService,
) : ViewModel() {
    init {
        viewModelScope.launch { reconciler.refreshStatus() }
    }

    val state: StateFlow<RemindersState> = combine(reminders.observeAll(), reminders.observeScheduled(), reconciler.status, preferences.preferences) { all, scheduled, delivery, prefs ->
        val now = clock.now()
        val zone = clock.timeZone()
        val active = all.filter { it.status == ReminderStatus.ACTIVE }.map { r ->
            ReminderItem(r, ReminderOccurrenceCalculator.nextOccurrence(r, now, zone, prefs.quietHours)?.fireAt?.toLocalDateTime(zone))
        }.sortedWith(compareBy(nullsLast()) { it.next })
        RemindersState(
            loading = false,
            delivery = delivery,
            active = active,
            inactive = all.filter { it.status != ReminderStatus.ACTIVE }.sortedByDescending { it.updatedAt }.take(20),
            scheduledCount = scheduled.size,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), RemindersState())

    fun requestPermission() = runAction(messages) {
        val result = permissions.requestPermission()
        if (result == PermissionState.DENIED) permissions.openNotificationSettings()
        reconciler.reconcile(ReconcileReason.PERMISSION_CHANGED)
    }

    fun openExactSettings() = permissions.openExactTimingSettings()
    fun openNotificationSettings() = permissions.openNotificationSettings()

    fun resync() = runAction(messages, "Couldn't sync reminders.") {
        val result = reconciler.reconcile(ReconcileReason.APP_START)
        messages.post("Reminders synced · ${result.totalPending} scheduled")
    }

    /** Lets people confirm on their own device that notifications actually arrive. */
    fun sendTest() = runAction(messages, "Couldn't send a test notification.") {
        if (!permissions.status().canDeliver) {
            messages.post("Allow notifications first, then try again")
            return@runAction
        }
        scheduler.showNow(
            NotificationRequest(TEST_NOTIFICATION_ID, TEST_NOTIFICATION_ID, clock.now(), "DayBloom test 🌸", "Notifications are working.", NotificationCategory.GENERAL),
        )
        messages.post("Test notification sent")
    }

    fun snooze(r: Reminder) = runAction(messages) {
        handle(r.id, null, NotificationAction.SNOOZE)
        messages.post("Snoozed for ${HandleReminderActionUseCase.DEFAULT_SNOOZE_MINUTES} minutes")
    }

    fun dismissNext(r: Reminder) = runAction(messages) { handle(r.id, clock.now(), NotificationAction.DISMISS) }

    fun delete(r: Reminder) = runAction(messages) {
        delete(r.id)
        messages.post(AppMessage("Reminder deleted", "Undo") { delete.restore(r) })
    }
}

@Composable
fun RemindersScreen(onBack: () -> Unit, onNew: () -> Unit, onEdit: (String) -> Unit, viewModel: RemindersViewModel = koinViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    Scaffold(topBar = { BloomTopBar("Reminders", onBack = onBack, actions = { TextButton(onClick = onNew) { Text("New") } }) }) { padding ->
        if (state.loading) {
            LoadingState(Modifier.padding(padding))
            return@Scaffold
        }
        LazyColumn(
            Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(horizontal = Spacing.screenGutter, vertical = Spacing.sm),
            verticalArrangement = Arrangement.spacedBy(Spacing.sm),
        ) {
            item { DeliveryCard(state, viewModel) }
            if (state.active.isEmpty()) {
                item { EmptyState("🔔", "No reminders", "Add one here, or set reminders on tasks, appointments and habits.", actionLabel = "New reminder", onAction = onNew) }
            } else {
                item { SectionHeader("Active · ${state.active.size}") }
                items(state.active, key = { it.reminder.id }) { item -> ReminderRow(item, viewModel, onEdit) }
            }
            if (state.inactive.isNotEmpty()) {
                item { SectionHeader("Done & dismissed") }
                items(state.inactive, key = { "i-${it.id}" }) { r ->
                    Text("${r.title} · ${r.status.name.lowercase()}", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun DeliveryCard(state: RemindersState, viewModel: RemindersViewModel) {
    val status = state.delivery.status
    val caps = state.delivery.capabilities
    BloomCard(tonal = true) {
        when (status?.permission) {
            PermissionState.GRANTED, PermissionState.PROVISIONAL -> {
                Text("✅ Notifications are on", style = MaterialTheme.typography.titleSmall)
                Text("${state.scheduledCount} upcoming notification${if (state.scheduledCount == 1) "" else "s"} scheduled with your device.", style = MaterialTheme.typography.bodySmall)
            }
            PermissionState.NOT_DETERMINED, null -> {
                Text("🔔 Allow notifications?", style = MaterialTheme.typography.titleSmall)
                Text("DayBloom only notifies you for reminders you set. Nothing is sent anywhere — everything stays on this device.", style = MaterialTheme.typography.bodySmall)
                FilledTonalButton(onClick = { viewModel.requestPermission() }) { Text("Allow notifications") }
            }
            PermissionState.DENIED -> {
                Text("🔕 Notifications are off", style = MaterialTheme.typography.titleSmall)
                Text("Reminders are saved, but your device won't show them until notifications are allowed in Settings.", style = MaterialTheme.typography.bodySmall)
                FilledTonalButton(onClick = viewModel::openNotificationSettings) { Text("Open settings") }
            }
        }
        if (status?.exactTiming == ExactTimingState.RESTRICTED) {
            Text("⏱ Exact timing is off. Reminders may arrive a few minutes late.", style = MaterialTheme.typography.bodySmall, color = BloomTheme.colors.warning)
            TextButton(onClick = viewModel::openExactSettings, modifier = Modifier.offset(x = -Spacing.md)) { Text("Allow exact alarms") }
        }
        caps?.limitation?.takeIf { status?.exactTiming != ExactTimingState.RESTRICTED }?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Text(
            "Delivery is handled by your device's operating system. Battery savers and Focus modes can delay or silence notifications.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        // Pulled left by the buttons' inner padding so the first label lines up with the text above.
        Row(Modifier.offset(x = -Spacing.md)) {
            TextButton(onClick = { viewModel.resync() }) { Text("Re-sync now") }
            if (status?.canDeliver == true) TextButton(onClick = { viewModel.sendTest() }) { Text("Send a test") }
        }
    }
}

@Composable
private fun ReminderRow(item: ReminderItem, viewModel: RemindersViewModel, onEdit: (String) -> Unit) {
    var menu by remember { mutableStateOf(false) }
    val r = item.reminder
    BloomCard(onClick = { if (r.source == ReminderSource.CUSTOM || r.source == ReminderSource.BILL || r.source == ReminderSource.EVENT) onEdit(r.id) }) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(r.title, style = MaterialTheme.typography.titleSmall)
                Text(
                    listOfNotNull(
                        r.source.label,
                        r.times.joinToString(", ") { Format.time(it) },
                        r.recurrence?.describe(),
                        r.timeZoneId?.let { "($it)" },
                    ).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    item.next?.let { "Next: ${Format.shortDate(it.date)} at ${Format.time(it.time)}" } ?: "No upcoming time",
                    style = MaterialTheme.typography.labelMedium,
                )
                r.snoozedUntil?.let { Text("Snoozed", style = MaterialTheme.typography.labelSmall, color = BloomTheme.colors.warning) }
            }
            Box {
                IconButton(onClick = { menu = true }) { Icon(Icons.Outlined.MoreVert, "More for ${r.title}") }
                DropdownMenu(menu, { menu = false }) {
                    DropdownMenuItem({ Text("Snooze 10 min") }, { menu = false; viewModel.snooze(r) })
                    DropdownMenuItem({ Text("Skip the next one") }, { menu = false; viewModel.dismissNext(r) })
                    if (r.source == ReminderSource.CUSTOM || r.source == ReminderSource.BILL || r.source == ReminderSource.EVENT) {
                        DropdownMenuItem({ Text("Edit") }, { menu = false; onEdit(r.id) })
                        DropdownMenuItem({ Text("Delete") }, { menu = false; viewModel.delete(r) })
                    }
                }
            }
        }
    }
}

enum class ReminderRepeat(val label: String) { NONE("Once"), DAILY("Daily"), WEEKDAYS("Weekdays"), WEEKLY("Weekly"), MONTHLY("Monthly") }

data class ReminderEditorState(
    val loading: Boolean = true,
    val isNew: Boolean = true,
    val title: String = "",
    val body: String = "",
    val source: ReminderSource = ReminderSource.CUSTOM,
    val date: LocalDate? = null,
    val times: List<LocalTime> = listOf(LocalTime(9, 0)),
    val repeat: ReminderRepeat = ReminderRepeat.NONE,
    val expiresOn: LocalDate? = null,
    val respectQuietHours: Boolean = true,
    val pinZone: Boolean = false,
    val zoneId: String? = null,
    val errors: Map<String, String> = emptyMap(),
    val closed: Boolean = false,
)

class ReminderEditorViewModel(
    private val reminderId: String?,
    private val reminders: ReminderRepository,
    private val schedule: ScheduleReminderUseCase,
    private val messages: AppMessages,
    private val clock: PlatformDateTimeService,
) : ViewModel() {
    private val _state = MutableStateFlow(ReminderEditorState())
    val state: StateFlow<ReminderEditorState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            val existing = reminderId?.let { reminders.get(it) }
            _state.value = if (existing != null) {
                ReminderEditorState(
                    loading = false, isNew = false, title = existing.title, body = existing.body, source = existing.source,
                    date = existing.startDate, times = existing.times, repeat = repeatOf(existing.recurrence),
                    expiresOn = existing.expiresOn, respectQuietHours = existing.respectQuietHours,
                    pinZone = existing.timeZoneId != null, zoneId = existing.timeZoneId ?: clock.timeZone().id,
                )
            } else {
                // Default to the next full hour so a new reminder never starts out already in the past.
                val now = clock.localNow()
                val (date, time) = if (now.hour < 23) now.date to LocalTime(now.hour + 1, 0) else DateMath.plusDays(now.date, 1) to LocalTime(9, 0)
                ReminderEditorState(loading = false, date = date, times = listOf(time), zoneId = clock.timeZone().id)
            }
        }
    }

    fun update(transform: (ReminderEditorState) -> ReminderEditorState) = _state.update { transform(it).copy(errors = emptyMap()) }

    fun save() = runAction(messages, "Couldn't save the reminder.") {
        val s = _state.value
        val date = s.date ?: clock.today()
        val draft = ReminderDraft(
            title = s.title,
            body = s.body,
            source = s.source,
            category = when (s.source) {
                ReminderSource.BILL -> NotificationCategory.BILLS
                else -> NotificationCategory.GENERAL
            },
            startDate = date,
            times = s.times,
            recurrence = when (s.repeat) {
                ReminderRepeat.NONE -> null
                ReminderRepeat.DAILY -> RecurrenceRule.daily()
                ReminderRepeat.WEEKDAYS -> RecurrenceRule(com.daybloom.domain.models.RecurrenceFrequency.WEEKLY, daysOfWeek = RecurrenceRule.WEEKDAYS)
                ReminderRepeat.WEEKLY -> RecurrenceRule.weekly(date.dayOfWeek)
                ReminderRepeat.MONTHLY -> RecurrenceRule.monthly()
            },
            expiresOn = s.expiresOn,
            respectQuietHours = s.respectQuietHours,
            timeZoneId = if (s.pinZone) s.zoneId else null,
        )
        when (val result = schedule(draft, reminderId)) {
            is ScheduleReminderUseCase.Result.Saved -> {
                messages.post("Reminder saved")
                _state.update { it.copy(closed = true) }
            }
            is ScheduleReminderUseCase.Result.Invalid -> _state.update { it.copy(errors = result.errors) }
        }
    }

    private fun repeatOf(rule: RecurrenceRule?): ReminderRepeat = when {
        rule == null -> ReminderRepeat.NONE
        rule.frequency == com.daybloom.domain.models.RecurrenceFrequency.DAILY -> ReminderRepeat.DAILY
        rule.daysOfWeek == RecurrenceRule.WEEKDAYS -> ReminderRepeat.WEEKDAYS
        rule.frequency == com.daybloom.domain.models.RecurrenceFrequency.WEEKLY -> ReminderRepeat.WEEKLY
        else -> ReminderRepeat.MONTHLY
    }
}

@Composable
fun ReminderEditorScreen(reminderId: String?, onClose: () -> Unit, viewModel: ReminderEditorViewModel = koinViewModel(key = "rem-$reminderId") { parametersOf(reminderId) }) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(state.closed) { if (state.closed) onClose() }
    // Leaving with unsaved edits asks first instead of discarding them silently.
    val requestClose = unsavedChangesGuard(hasChanges = !state.closed && rememberHasChanges(ready = !state.loading, current = state.copy(errors = emptyMap(), closed = false)), onLeave = onClose)
    var picker by remember { mutableStateOf<String?>(null) }
    /** The time chip being edited; null when the time picker is adding a new time. */
    var editingTime by remember { mutableStateOf<LocalTime?>(null) }
    Scaffold(topBar = { BloomTopBar(if (state.isNew) "New reminder" else "Edit reminder", onBack = requestClose, actions = { TextButton(onClick = { viewModel.save() }) { Text("Save") } }) }) { padding ->
        if (state.loading) {
            LoadingState(Modifier.padding(padding))
            return@Scaffold
        }
        Column(
            Modifier.fillMaxSize().padding(padding).imePadding().verticalScroll(rememberScrollState()).padding(horizontal = Spacing.screenGutter),
            verticalArrangement = Arrangement.spacedBy(Spacing.md),
        ) {
            BloomTextField(
                state.title, { v -> viewModel.update { it.copy(title = v.take(200)) } }, Modifier.fillMaxWidth(),
                label = { Text("Remind me to…") }, isError = "title" in state.errors, supportingText = state.errors["title"]?.let { { Text(it) } }, singleLine = true,
            )
            BloomTextField(state.body, { v -> viewModel.update { it.copy(body = v.take(500)) } }, Modifier.fillMaxWidth(), label = { Text("Note (optional)") })
            ChoiceChips(listOf(ReminderSource.CUSTOM, ReminderSource.BILL, ReminderSource.EVENT), state.source, { it.label }, { s -> viewModel.update { it.copy(source = s) } })
            SectionHeader("When")
            AssistChip(onClick = { picker = "date" }, label = { Text(state.date?.let(Format::shortDate) ?: "Pick a date") })
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                state.times.forEach { t ->
                    InputChip(
                        selected = false,
                        onClick = { editingTime = t; picker = "time" },
                        label = { Text(Format.time(t)) },
                        trailingIcon = if (state.times.size > 1) ({ IconButton(onClick = { viewModel.update { it.copy(times = it.times - t) } }) { Icon(Icons.Filled.Close, "Remove ${Format.time(t)}") } }) else null,
                    )
                }
                TextButton(onClick = { editingTime = null; picker = "time" }) { Text("+ Time") }
            }
            state.errors["times"]?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            ChoiceChips(ReminderRepeat.entries, state.repeat, { it.label }, { r -> viewModel.update { it.copy(repeat = r) } })
            if (state.repeat != ReminderRepeat.NONE) {
                AssistChip(onClick = { picker = "expiry" }, label = { Text(state.expiresOn?.let { "Ends ${Format.shortDate(it)}" } ?: "No end date") })
                state.errors["expiresOn"]?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
            SwitchRow(
                "Respect quiet hours", state.respectQuietHours, { v -> viewModel.update { it.copy(respectQuietHours = v) } },
                subtitle = "Reminders inside quiet hours arrive when they end.",
            )
            SwitchRow(
                "Keep in ${state.zoneId}", state.pinZone, { v -> viewModel.update { it.copy(pinZone = v) } },
                subtitle = "On: fires at this time in ${state.zoneId} even if you travel. Off: follows your current time zone.",
            )
            Button(colors = BloomButtons.primary(), onClick = { viewModel.save() }, modifier = Modifier.fillMaxWidth()) { Text("Save reminder") }
            Spacer(Modifier.height(Spacing.xl))
        }
        when (picker) {
            "date" -> BloomDatePickerDialog(state.date, { picker = null }, { d -> picker = null; d?.let { date -> viewModel.update { it.copy(date = date) } } })
            "expiry" -> BloomDatePickerDialog(state.expiresOn, { picker = null }, { d -> picker = null; viewModel.update { it.copy(expiresOn = d) } }, allowClear = true)
            "time" -> BloomTimePickerDialog(editingTime, { picker = null }, { t ->
                picker = null
                val replacing = editingTime
                t?.let { time -> viewModel.update { s -> s.copy(times = (s.times.filterNot { it == replacing } + time).distinct().sorted()) } }
            })
        }
    }
}
