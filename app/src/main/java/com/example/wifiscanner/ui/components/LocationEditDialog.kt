package com.example.wifiscanner.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.unit.dp
import com.example.wifiscanner.R

/**
 * Dialog for editing the physical location label of a device.
 *
 * Shows the device's identity (IP/MAC) for context, a text field prefilled
 * with the current label, Save (always enabled — clearing is allowed, which
 * deletes the label), and Cancel (discards).
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
    var text by remember(mac) { mutableStateOf(initialLabel.orEmpty()) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.edit_location)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                // Context line so the user knows which device they're labeling.
                Text(text = buildString {
                    append(ip)
                    mac?.let { append("  •  ") ; append(it) }
                })
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    label = { Text(stringResource(R.string.label_location)) },
                    placeholder = { Text(stringResource(R.string.location_hint)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(text.trim()) }) {
                Text(stringResource(R.string.save))
            }
        },
        dismissButton = {
            Row(modifier = Modifier.fillMaxWidth()) {
                // Delete only makes sense when a label already exists.
                if (!initialLabel.isNullOrBlank()) {
                    TextButton(onClick = { onSave("") }) {
                        Text(stringResource(R.string.delete))
                    }
                }
                androidx.compose.foundation.layout.Spacer(Modifier.weight(1f))
                TextButton(onClick = onDismiss) {
                    Text(stringResource(R.string.cancel))
                }
            }
        },
    )
}
