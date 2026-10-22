package com.daybloom.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.daybloom.design.Spacing
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Instant

/** Material date picker; the picker works in UTC milliseconds, so convert at the boundary only. */
@Composable
fun BloomDatePickerDialog(
    initial: LocalDate?,
    onDismiss: () -> Unit,
    onPicked: (LocalDate?) -> Unit,
    allowClear: Boolean = false,
) {
    val state = rememberDatePickerState(initialSelectedDateMillis = initial?.atStartOfDayIn(TimeZone.UTC)?.toEpochMilliseconds())
    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = {
                val picked = state.selectedDateMillis?.let { Instant.fromEpochMilliseconds(it).toLocalDateTime(TimeZone.UTC).date }
                onPicked(picked)
            }) { Text("Done") }
        },
        dismissButton = {
            Row {
                if (allowClear) TextButton(onClick = { onPicked(null) }) { Text("No date") }
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        },
    ) {
        DatePicker(state = state)
    }
}

@Composable
fun BloomTimePickerDialog(
    initial: LocalTime?,
    onDismiss: () -> Unit,
    onPicked: (LocalTime?) -> Unit,
    allowClear: Boolean = false,
) {
    val start = initial ?: LocalTime(9, 0)
    val state = rememberTimePickerState(initialHour = start.hour, initialMinute = start.minute, is24Hour = false)
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = { onPicked(LocalTime(state.hour, state.minute)) }) { Text("Done") } },
        dismissButton = {
            Row {
                if (allowClear) TextButton(onClick = { onPicked(null) }) { Text("No time") }
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        },
        text = { TimePicker(state = state, modifier = Modifier.fillMaxWidth().wrapContentWidth()) },
    )
}

/** Horizontal single-select chips. */
@Composable
fun <T> ChoiceChips(
    options: List<T>,
    selected: T?,
    label: (T) -> String,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(),
) {
    LazyRow(modifier, contentPadding = contentPadding, horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        items(options) { option ->
            FilterChip(selected = option == selected, onClick = { onSelect(option) }, label = { Text(label(option)) })
        }
    }
}

/** Horizontal multi-select chips. */
@Composable
fun <T> MultiChoiceChips(
    options: List<T>,
    selected: Set<T>,
    label: (T) -> String,
    onToggle: (T) -> Unit,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(),
) {
    LazyRow(modifier, contentPadding = contentPadding, horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        items(options) { option ->
            FilterChip(selected = option in selected, onClick = { onToggle(option) }, label = { Text(label(option)) })
        }
    }
}
