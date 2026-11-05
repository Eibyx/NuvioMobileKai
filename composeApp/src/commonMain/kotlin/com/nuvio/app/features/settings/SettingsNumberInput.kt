package com.nuvio.app.features.settings

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.nuvio.app.core.ui.DialogButton
import com.nuvio.app.core.ui.DialogButtonStyle
import com.nuvio.app.core.ui.DialogButtons
import com.nuvio.app.core.ui.DialogSurface
import nuvio.composeapp.generated.resources.*
import org.jetbrains.compose.resources.stringResource

/** A setting whose value is typed in: the row shows it with its unit and opens [NumberInputDialog]. */
@Composable
internal fun SettingsNumberRow(
    title: String,
    description: String?,
    value: Int,
    unit: String,
    range: IntRange,
    isTablet: Boolean,
    enabled: Boolean = true,
    onValueChange: (Int) -> Unit,
) {
    var editing by remember { mutableStateOf(false) }
    SettingsNavigationRow(
        title = title,
        description = listOfNotNull("$value $unit", description).joinToString("\n"),
        enabled = enabled,
        isTablet = isTablet,
        onClick = { editing = true },
    )
    if (editing) {
        NumberInputDialog(
            title = title,
            value = value,
            unit = unit,
            range = range,
            onSave = { typed ->
                onValueChange(typed)
                editing = false
            },
            onDismiss = { editing = false },
        )
    }
}

/** Type a whole number within [range]; Save stays off until it is one. */
@Composable
internal fun NumberInputDialog(
    title: String,
    value: Int,
    unit: String,
    range: IntRange,
    onSave: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    var input by remember(value) { mutableStateOf(value.toString()) }
    val typed = parseNumberInput(input, range)
    val valid = typed != null
    DialogSurface(
        onDismissRequest = onDismiss,
        title = title,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                    border = BorderStroke(
                        1.dp,
                        if (valid) MaterialTheme.colorScheme.outline.copy(alpha = 0.3f) else MaterialTheme.colorScheme.error,
                    ),
                ) {
                    BasicTextField(
                        value = input,
                        onValueChange = { text -> input = cleanNumberInput(text) },
                        modifier = Modifier
                            .widthIn(min = 96.dp)
                            .padding(horizontal = 14.dp, vertical = 10.dp)
                            .testTag("number-input"),
                        textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
                        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                    )
                }
                Text(
                    text = unit,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                text = stringResource(Res.string.settings_number_input_range, range.first, range.last, unit),
                style = MaterialTheme.typography.bodySmall,
                color = if (valid) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error,
            )
        }
        DialogButtons {
            DialogButton(
                text = stringResource(Res.string.action_cancel),
                onClick = onDismiss,
            )
            DialogButton(
                text = stringResource(Res.string.action_save),
                onClick = { typed?.let(onSave) },
                style = DialogButtonStyle.Primary,
                enabled = valid,
            )
        }
    }
}

/** Digits only, at most six. */
internal fun cleanNumberInput(text: String): String = text.filter(Char::isDigit).take(6)

/** The typed number when it is a whole number within [range], else null (Save stays off). */
internal fun parseNumberInput(text: String, range: IntRange): Int? =
    text.trim().toIntOrNull()?.takeIf { it in range }
