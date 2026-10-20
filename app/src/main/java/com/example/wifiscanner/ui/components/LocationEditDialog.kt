package com.example.wifiscanner.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.example.wifiscanner.R

/**
 * Dialog for editing the physical location label of a device.
 *
 * Shows the device's identity (IP/MAC) for context, a text field prefilled
 * with the current label, Save (always enabled — clearing is allowed, which
 * deletes the label), and Cancel (discards). Deleting is a third action, kept
 * inside the content so the button row never overflows on a narrow screen.
 *
 * @param mac          identity of the labeled device (null for unknown).
 * @param initialLabel current label, or null when unset.
 * @param onSave       invoked with the trimmed new label ("" == delete).
 * @param onDismiss    invoked when the user cancels or dismisses.
 */
@Composable
fun LocationEditDialog(
    mac: String?,
    ip: String,
    initialLabel: String?,
    onSave: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    // Local edit state so Cancel truly discards changes.
    var text by remember(mac, initialLabel) { mutableStateOf(initialLabel.orEmpty()) }
    val save = { onSave(text.trim()) }

    AlertDialog(
        onDismissRequest = onDismiss,
        shape = MaterialTheme.shapes.large,
        title = {
            Text(
                stringResource(
                    if (initialLabel.isNullOrBlank()) R.string.set_location
                    else R.string.edit_location
                )
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                // Context line so the user knows which device they're labeling.
                Text(
                    text = buildString {
                        append(ip)
                        mac?.let { append("  •  "); append(it) }
                    },
                    style = MaterialTheme.typography.bodySmall.copy(
                        fontFamily = FontFamily.Monospace,
                    ),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    label = { Text(stringResource(R.string.label_location)) },
                    placeholder = { Text(stringResource(R.string.location_hint)) },
                    singleLine = true,
                    shape = MaterialTheme.shapes.small,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { save() }),
                    modifier = Modifier.fillMaxWidth(),
                )
                if (!initialLabel.isNullOrBlank()) {
                    TextButton(
                        onClick = { onSave("") },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Delete,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = stringResource(R.string.delete),
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = save) {
                Text(stringResource(R.string.save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        },
    )
}
