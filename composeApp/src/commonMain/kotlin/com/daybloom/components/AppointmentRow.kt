package com.daybloom.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.daybloom.design.BloomTheme
import com.daybloom.design.Spacing
import androidx.compose.foundation.layout.widthIn
import com.daybloom.domain.models.AppointmentOccurrence
import com.daybloom.domain.models.AppointmentStatus

@Composable
fun AppointmentRow(
    occurrence: AppointmentOccurrence,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    trailing: @Composable (() -> Unit)? = null,
) {
    val appt = occurrence.appointment
    val statusLabel = when (occurrence.status) {
        AppointmentStatus.ATTENDED -> "Attended"
        AppointmentStatus.MISSED -> "Missed"
        AppointmentStatus.CANCELLED -> "Cancelled"
        AppointmentStatus.SCHEDULED -> null
    }
    val accent = when (occurrence.status) {
        AppointmentStatus.ATTENDED -> BloomTheme.colors.success
        AppointmentStatus.CANCELLED, AppointmentStatus.MISSED -> MaterialTheme.colorScheme.outline
        AppointmentStatus.SCHEDULED -> MaterialTheme.colorScheme.tertiary
    }
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = 64.dp)
            .clickable(onClickLabel = "Open ${appt.title}", onClick = onClick)
            .padding(horizontal = Spacing.screenGutter, vertical = Spacing.md)
            .semantics(mergeDescendants = true) {
                contentDescription = "${appt.title}, ${Format.timeRange(appt.startTime, appt.endTime)}" +
                    (if (appt.location.isNotBlank()) ", at ${appt.location}" else "") + (statusLabel?.let { ", $it" } ?: "")
            },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.md),
    ) {
        // Grows with large text instead of wrapping "3:30 PM" onto two lines.
        Column(Modifier.widthIn(min = 64.dp), verticalArrangement = Arrangement.spacedBy(Spacing.xxs)) {
            Text(Format.time(appt.startTime), style = MaterialTheme.typography.labelLarge, softWrap = false)
            Text(Format.time(appt.endTime), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, softWrap = false)
        }
        Box(Modifier.width(4.dp).height(36.dp).background(accent, RoundedCornerShape(2.dp)))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
            Text("${appt.kind.emoji} ${appt.title}", style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val sub = listOfNotNull(appt.location.takeIf { it.isNotBlank() }, statusLabel, appt.recurrence?.describe()).joinToString(" · ")
            if (sub.isNotEmpty()) {
                Text(sub, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        trailing?.invoke()
    }
}
