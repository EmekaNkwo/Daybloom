package com.daybloom.features.appointments

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.daybloom.components.AppointmentRow
import com.daybloom.components.EmptyState
import com.daybloom.components.Format
import com.daybloom.components.LoadingState
import com.daybloom.components.ScreenHeader
import com.daybloom.components.SectionHeader
import com.daybloom.components.TaskRow
import com.daybloom.design.Spacing
import androidx.compose.foundation.layout.height
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.clip
import com.daybloom.components.softShadow
import com.daybloom.design.BloomTheme
import com.daybloom.components.listGroupItem
import androidx.compose.foundation.lazy.itemsIndexed
import com.daybloom.domain.models.shortName
import com.daybloom.domain.services.DateMath
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import org.koin.compose.viewmodel.koinViewModel

@Composable
fun CalendarScreen(
    contentPadding: PaddingValues,
    onOpenAppointment: (String, String) -> Unit,
    onNewAppointment: (String) -> Unit,
    onOpenTask: (String) -> Unit,
    viewModel: CalendarViewModel = koinViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val today = state.today
    val selected = state.selected
    if (state.loading || today == null || selected == null) {
        LoadingState(Modifier.padding(contentPadding))
        return
    }
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(top = contentPadding.calculateTopPadding(), bottom = contentPadding.calculateBottomPadding() + Spacing.fabClearance),
    ) {
        item {
            Column(Modifier.padding(horizontal = Spacing.screenGutter), verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
                ScreenHeader("Calendar") {
                    TextButton(onClick = { onNewAppointment(selected.toString()) }) { Text("New appointment") }
                }
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    CalendarMode.entries.forEachIndexed { i, m ->
                        SegmentedButton(
                            selected = state.mode == m,
                            onClick = { viewModel.setMode(m) },
                            shape = SegmentedButtonDefaults.itemShape(i, CalendarMode.entries.size),
                            // No checkmark: the fill already marks the selection, and the icon squeezes labels.
                            icon = {},
                        ) { Text(m.label, style = MaterialTheme.typography.labelMedium, maxLines = 1, softWrap = false) }
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { viewModel.shift(false) }) { Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, "Previous") }
                    Text(
                        when (state.mode) {
                            CalendarMode.MONTH -> Format.monthYear(selected)
                            CalendarMode.WEEK -> "Week of ${Format.shortDate(DateMath.startOfWeek(selected))}"
                            else -> Format.longDate(selected)
                        },
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.weight(1f),
                        textAlign = TextAlign.Center,
                    )
                    IconButton(onClick = { viewModel.shift(true) }) { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, "Next") }
                }
                if (selected != today) {
                    TextButton(onClick = viewModel::goToToday, modifier = Modifier.align(Alignment.CenterHorizontally)) { Text("Back to today") }
                }
            }
        }
        when (state.mode) {
            CalendarMode.AGENDA -> {
                val busy = state.days.filterNot { it.isEmpty }
                if (busy.isEmpty()) {
                    item { EmptyState("🗓️", "Nothing scheduled", "The next two weeks are open.", actionLabel = "Add appointment", onAction = { onNewAppointment(selected.toString()) }) }
                }
                busy.forEach { day -> dayItems(day, today, onOpenAppointment, onOpenTask, viewModel::toggleTask, showHeader = true) }
            }
            CalendarMode.DAY -> state.selectedDay?.let { day ->
                if (day.isEmpty) item { EmptyState("☁️", if (day.isRestDay) "Rest day" else "Nothing scheduled", "Enjoy the space.") }
                dayItems(day, today, onOpenAppointment, onOpenTask, viewModel::toggleTask, showHeader = false)
            }
            CalendarMode.WEEK -> {
                item { WeekStrip(state.days.map { it.date }, selected, today, state, viewModel::select) }
                state.selectedDay?.let { day ->
                    dayItems(day, today, onOpenAppointment, onOpenTask, viewModel::toggleTask, showHeader = true)
                    if (day.isEmpty) item { EmptyDayNote(day.isRestDay) }
                }
            }
            CalendarMode.MONTH -> {
                item { MonthGrid(selected, today, state, viewModel::select) }
                state.selectedDay?.let { day ->
                    dayItems(day, today, onOpenAppointment, onOpenTask, viewModel::toggleTask, showHeader = true)
                    if (day.isEmpty) item { EmptyDayNote(day.isRestDay) }
                }
            }
        }
    }
}

private fun LazyListScope.dayItems(
    day: CalendarDay,
    today: LocalDate,
    onOpenAppointment: (String, String) -> Unit,
    onOpenTask: (String) -> Unit,
    onToggleTask: (com.daybloom.domain.models.Task, Boolean) -> Unit,
    showHeader: Boolean,
) {
    if (showHeader) {
        item(key = "h-${day.date}") {
            SectionHeader(
                Format.relativeDay(day.date, today).let { if (it == Format.shortDate(day.date)) it else "$it · ${Format.shortDate(day.date)}" } +
                    if (day.isRestDay) " · Rest day" else "",
                Modifier.padding(horizontal = Spacing.screenGutter),
            )
        }
    }
    // Appointments and tasks for the day share one grouped container.
    val count = day.appointments.size + day.tasks.size
    itemsIndexed(day.appointments, key = { _, it -> "a-${it.key}" }) { i, occ ->
        AppointmentRow(
            occ,
            onClick = { onOpenAppointment(occ.appointment.id, occ.date.toString()) },
            modifier = Modifier.listGroupItem(i, count, dividerStart = Spacing.lg),
        )
    }
    itemsIndexed(day.tasks, key = { _, it -> "t-${day.date}-${it.id}" }) { i, task ->
        TaskRow(
            task, today, onToggle = { onToggleTask(task, it) }, onClick = { onOpenTask(task.id) }, showDate = false,
            modifier = Modifier.listGroupItem(day.appointments.size + i, count).padding(start = Spacing.xs),
        )
    }
}

@Composable
private fun WeekStrip(dates: List<LocalDate>, selected: LocalDate, today: LocalDate, state: CalendarState, onSelect: (LocalDate) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = Spacing.sm)) {
        dates.forEach { d -> DayCell(d, selected, today, state, onSelect, Modifier.weight(1f), showWeekday = true) }
    }
}

@Composable
private fun MonthGrid(anchor: LocalDate, today: LocalDate, state: CalendarState, onSelect: (LocalDate) -> Unit) {
    val first = DateMath.firstOfMonth(anchor)
    val lead = DateMath.dayOfWeekIndex(first.dayOfWeek)
    val dates = DateMath.monthDates(anchor)
    val cells: List<LocalDate?> = List(lead) { null } + dates
    Column(Modifier.padding(horizontal = Spacing.sm)) {
        Row {
            DayOfWeek.entries.forEach { d ->
                Text(d.shortName().take(2), Modifier.weight(1f), textAlign = TextAlign.Center, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        cells.chunked(7).forEach { week ->
            Row {
                week.forEach { d ->
                    if (d == null) Box(Modifier.weight(1f)) else DayCell(d, anchor, today, state, onSelect, Modifier.weight(1f), showWeekday = false)
                }
                repeat(7 - week.size) { Box(Modifier.weight(1f)) }
            }
        }
    }
}

@Composable
private fun DayCell(date: LocalDate, selected: LocalDate, today: LocalDate, state: CalendarState, onSelect: (LocalDate) -> Unit, modifier: Modifier, showWeekday: Boolean) {
    val day = state.days.firstOrNull { it.date == date }
    val count = (day?.appointments?.size ?: 0) + (day?.tasks?.size ?: 0)
    val isSelected = date == selected
    val c = BloomTheme.colors
    val scheme = MaterialTheme.colorScheme
    // Selected day: solid action-blue tile. Week strip days sit on white tiles; month days on the background.
    val container = when {
        isSelected -> c.action
        showWeekday -> scheme.surfaceContainerLowest
        else -> Color.Transparent
    }
    val content = if (isSelected) c.onAction else scheme.onSurface
    val muted = if (isSelected) c.onAction.copy(alpha = 0.85f) else scheme.onSurfaceVariant
    val shape = MaterialTheme.shapes.small
    Column(
        modifier
            .aspectRatio(if (showWeekday) 0.78f else 1f)
            .padding(if (showWeekday) 3.dp else 2.dp)
            .then(if (showWeekday && !isSelected) Modifier.softShadow(shape, 4.dp) else Modifier)
            .background(container, shape)
            .then(if (date == today && !isSelected) Modifier.border(1.5.dp, scheme.primary, shape) else Modifier)
            .clip(shape)
            .clickable { onSelect(date) }
            .semantics {
                this.selected = isSelected
                contentDescription = "${Format.longDate(date)}, $count item${if (count == 1) "" else "s"}" + if (day?.isRestDay == true) ", rest day" else ""
            },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(Spacing.xxs, Alignment.CenterVertically),
    ) {
        if (showWeekday) Text(date.dayOfWeek.shortName().take(2), style = MaterialTheme.typography.labelSmall, color = muted)
        Text(
            "${date.day}",
            style = if (showWeekday) MaterialTheme.typography.titleMedium else MaterialTheme.typography.bodyMedium,
            fontWeight = if (isSelected || date == today) FontWeight.Bold else FontWeight.Normal,
            color = content,
        )
        Row(Modifier.height(5.dp), horizontalArrangement = Arrangement.spacedBy(Spacing.xxs)) {
            if ((day?.appointments?.size ?: 0) > 0) Box(Modifier.size(5.dp).background(if (isSelected) c.onAction else scheme.tertiary, CircleShape))
            if ((day?.tasks?.size ?: 0) > 0) Box(Modifier.size(5.dp).background(if (isSelected) c.onAction else scheme.primary, CircleShape))
            if (day?.isRestDay == true) Text("·", style = MaterialTheme.typography.labelSmall, color = muted)
        }
    }
}

/** Compact note for an empty selected day under the week strip or month grid, where a full empty state is too big. */
@Composable
private fun EmptyDayNote(restDay: Boolean) {
    Text(
        if (restDay) "Rest day — nothing scheduled." else "Nothing scheduled for this day.",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = Spacing.screenGutter, vertical = Spacing.sm),
    )
}
