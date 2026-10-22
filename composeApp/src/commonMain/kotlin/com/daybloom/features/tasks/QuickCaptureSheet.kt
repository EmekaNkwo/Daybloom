package com.daybloom.features.tasks

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.daybloom.components.BloomCard
import com.daybloom.components.Format
import com.daybloom.design.Spacing
import com.daybloom.domain.services.CaptureKind
import com.daybloom.domain.services.ParsedCapture
import org.koin.compose.viewmodel.koinViewModel
import com.daybloom.components.BloomButtons
import com.daybloom.components.BloomTextField

@Composable
fun QuickCaptureSheet(
    onDismiss: () -> Unit,
    onOpenTaskEditor: () -> Unit,
    onOpenAppointmentEditor: () -> Unit,
    viewModel: QuickCaptureViewModel = koinViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    LaunchedEffect(state.done) {
        if (state.done) {
            viewModel.consumeDone()
            onDismiss()
        }
    }
    ModalBottomSheet(onDismissRequest = { viewModel.reset(); onDismiss() }, sheetState = sheetState) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = Spacing.screenGutter).navigationBarsPadding().imePadding().padding(bottom = Spacing.lg),
            verticalArrangement = Arrangement.spacedBy(Spacing.md),
        ) {
            Text("Quick add", style = MaterialTheme.typography.titleLarge)
            val parsed = state.parsed
            if (state.confirming && parsed != null) {
                ConfirmationCard(state.text, parsed, onConfirm = viewModel::submit, onEdit = viewModel::backToEditing)
            } else {
                BloomTextField(
                    value = state.text,
                    onValueChange = viewModel::onTextChange,
                    modifier = Modifier.fillMaxWidth().focusRequester(focus),
                    placeholder = { Text("Pay rent tomorrow at 9 AM") },
                    isError = state.error != null,
                    supportingText = { Text(state.error ?: "Try: “Call Mum on Friday”, “Read for 30 minutes tonight”") },
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { viewModel.submit() }),
                    singleLine = true,
                )
                if (parsed != null) ParsedPreview(parsed)
                Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                    OutlinedButton(onClick = {
                        when (viewModel.handOff()) {
                            CaptureHandoff.Task -> onOpenTaskEditor()
                            CaptureHandoff.Appointment -> onOpenAppointmentEditor()
                        }
                    }) { Text("More options") }
                    Button(colors = BloomButtons.primary(), onClick = viewModel::submit, enabled = parsed?.isValid == true && !state.saving, modifier = Modifier.weight(1f)) {
                        Text(if (parsed?.kind == CaptureKind.APPOINTMENT) "Add appointment" else "Add task")
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ParsedPreview(parsed: ParsedCapture) {
    val chips = buildList {
        if (parsed.kind == CaptureKind.APPOINTMENT) add("📅 Appointment")
        parsed.date?.let { add("🗓 ${Format.shortDate(it)}") }
        parsed.time?.let { add("⏰ ${Format.time(it)}") }
        parsed.durationMinutes?.let { add("⏳ ${Format.compactMinutes(it)}") }
        parsed.recurrence?.let { add("🔁 ${it.describe()}") }
        parsed.priority?.let { add("⚑ ${it.label}") }
        parsed.category?.let { add("${it.emoji} ${it.label}") }
        parsed.tags.forEach { add("#$it") }
    }
    Column(Modifier.semantics { liveRegion = LiveRegionMode.Polite }, verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
        if (parsed.title.isNotBlank()) Text("“${parsed.title}”", style = MaterialTheme.typography.titleMedium)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            chips.forEach { SuggestionChip(onClick = {}, label = { Text(it) }) }
        }
    }
}

@Composable
private fun ConfirmationCard(input: String, parsed: ParsedCapture, onConfirm: () -> Unit, onEdit: () -> Unit) {
    BloomCard(tonal = true) {
        Text("You entered:", style = MaterialTheme.typography.labelLarge)
        Text(input, style = MaterialTheme.typography.bodyLarge)
        parsed.date?.let {
            Text("Detected date:", style = MaterialTheme.typography.labelLarge)
            Text(Format.longDate(it) + (parsed.time?.let { t -> " at ${Format.time(t)}" } ?: ""), style = MaterialTheme.typography.titleMedium)
        }
        parsed.ambiguities.forEach { Text("• $it", style = MaterialTheme.typography.bodySmall) }
        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            Button(colors = BloomButtons.primary(), onClick = onConfirm) { Text("Confirm") }
            TextButton(onClick = onEdit) { Text("Edit") }
        }
    }
}
