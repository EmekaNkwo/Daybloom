package com.daybloom.components

import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.ButtonColors
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.TextFieldColors
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Shape
import com.daybloom.design.BloomTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import com.daybloom.design.Spacing
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.TooltipAnchorPosition
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch

/**
 * Button hierarchy:
 * - primary (filled, action blue): the one main step on a screen or dialog — [BloomButtons.primary]
 * - secondary (FilledTonalButton / OutlinedButton): useful alternatives
 * - tertiary (TextButton, brand indigo): low-emphasis actions such as "Later" or "See all"
 */
object BloomButtons {
    @Composable
    fun primary(): ButtonColors {
        val c = BloomTheme.colors
        return ButtonDefaults.buttonColors(
            containerColor = c.action,
            contentColor = c.onAction,
            disabledContainerColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.10f),
            disabledContentColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f),
        )
    }

    /** Primary action placed on a [FeaturedCard] or other indigo surface. */
    @Composable
    fun onFeatured(): ButtonColors {
        val c = BloomTheme.colors
        return ButtonDefaults.buttonColors(containerColor = c.actionOnFeatured, contentColor = c.onActionOnFeatured)
    }
}

/** Text fields sit on a white fill so they read as places to type against the tinted background. */
@Composable
fun bloomTextFieldColors(): TextFieldColors {
    val scheme = MaterialTheme.colorScheme
    return OutlinedTextFieldDefaults.colors(
        focusedContainerColor = scheme.surfaceContainerLowest,
        unfocusedContainerColor = scheme.surfaceContainerLowest,
        errorContainerColor = scheme.surfaceContainerLowest,
        disabledContainerColor = scheme.surfaceContainer,
        unfocusedBorderColor = scheme.outlineVariant,
        focusedBorderColor = scheme.primary,
        unfocusedLabelColor = scheme.onSurfaceVariant,
        unfocusedPlaceholderColor = scheme.onSurfaceVariant.copy(alpha = 0.8f),
        focusedPlaceholderColor = scheme.onSurfaceVariant.copy(alpha = 0.8f),
    )
}

/** [OutlinedTextField] with the app's fill, border and radius. Parameters mirror the Material ones used here. */
@Composable
fun BloomTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    readOnly: Boolean = false,
    label: (@Composable () -> Unit)? = null,
    placeholder: (@Composable () -> Unit)? = null,
    leadingIcon: (@Composable () -> Unit)? = null,
    trailingIcon: (@Composable () -> Unit)? = null,
    supportingText: (@Composable () -> Unit)? = null,
    isError: Boolean = false,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
    singleLine: Boolean = false,
    maxLines: Int = if (singleLine) 1 else Int.MAX_VALUE,
    minLines: Int = 1,
    shape: Shape = MaterialTheme.shapes.medium,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier,
        enabled = enabled,
        readOnly = readOnly,
        label = label,
        placeholder = placeholder,
        leadingIcon = leadingIcon,
        trailingIcon = trailingIcon,
        supportingText = supportingText,
        isError = isError,
        keyboardOptions = keyboardOptions,
        keyboardActions = keyboardActions,
        singleLine = singleLine,
        maxLines = maxLines,
        minLines = minLines,
        shape = shape,
        colors = bloomTextFieldColors(),
    )
}

/**
 * A labelled on/off setting. The whole row is the toggle: tapping the text works, and screen readers announce
 * the title with the switch state instead of an unlabelled "switch".
 */
@Composable
fun SwitchRow(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    titleStyle: TextStyle = MaterialTheme.typography.bodyLarge,
) {
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = Spacing.minTouchTarget)
            .toggleable(value = checked, role = Role.Switch, onValueChange = onCheckedChange),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.lg),
    ) {
        Column(Modifier.weight(1f).padding(vertical = Spacing.xs), verticalArrangement = Arrangement.spacedBy(Spacing.xxs)) {
            Text(title, style = titleStyle)
            if (subtitle != null) {
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        // The row handles toggling; the switch only shows the state.
        Switch(checked = checked, onCheckedChange = null)
    }
}

/**
 * An ⓘ button that explains [text] in a tooltip on tap (or long-press / hover). Keeps secondary explanations out of
 * dense rows; [subject] names what is explained, for screen readers ("About Task completion").
 */
@Composable
fun InfoTooltip(text: String, subject: String, modifier: Modifier = Modifier) {
    val state = rememberTooltipState(isPersistent = true)
    val scope = rememberCoroutineScope()
    TooltipBox(
        positionProvider = TooltipDefaults.rememberTooltipPositionProvider(TooltipAnchorPosition.Above),
        tooltip = { PlainTooltip { Text(text) } },
        state = state,
        modifier = modifier,
    ) {
        IconButton(onClick = { scope.launch { state.show() } }) {
            Icon(Icons.Outlined.Info, contentDescription = "About $subject", tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
