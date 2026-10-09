package com.example.wifiscanner.ui.components

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.wifiscanner.R
import com.example.wifiscanner.model.DiscoverySource
import com.example.wifiscanner.model.NetworkDevice

/**
 * Full picture of one discovered device, opened by tapping a card.
 *
 * Every value can be copied to the clipboard (handy for support tickets) and
 * the evidence behind the classification is shown explicitly — which method
 * found the device, which ports answered, which services it announced — so a
 * partial scan is never mysterious.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DeviceDetailSheet(
    device: NetworkDevice,
    onDismiss: () -> Unit,
    onEditLocation: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState()
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current
    val copiedMessage = stringResource(R.string.copied_to_clipboard)

    /** Copies [value] and confirms it with a toast. */
    fun copy(value: String) {
        clipboard.setText(AnnotatedString(value))
        Toast.makeText(context, copiedMessage, Toast.LENGTH_SHORT).show()
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = device.displayName,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = device.kind.label,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.tertiary,
            )

            HorizontalDivider()

            DetailRow(
                label = stringResource(R.string.field_ip),
                value = device.ip,
                onCopy = ::copy,
            )

            device.mac?.let { mac ->
                DetailRow(
                    label = stringResource(R.string.field_mac),
                    value = mac,
                    monospace = true,
                    onCopy = ::copy,
                )
            }
            device.hostname?.let { hostname ->
                DetailRow(
                    label = stringResource(R.string.field_hostname),
                    value = hostname,
                    onCopy = ::copy,
                )
            }
            device.vendor?.let { vendor ->
                DetailRow(
                    label = stringResource(R.string.field_vendor),
                    value = vendor,
                    onCopy = ::copy,
                )
            }
            if (device.models.isNotEmpty()) {
                DetailRow(
                    label = stringResource(R.string.field_model),
                    value = device.models.distinct().joinToString(" · "),
                    onCopy = ::copy,
                )
            }
            if (device.location != null) {
                DetailRow(
                    label = stringResource(R.string.field_location),
                    value = device.location,
                    onCopy = ::copy,
                )
            }
            if (device.services.isNotEmpty()) {
                DetailRow(
                    label = stringResource(R.string.field_services),
                    value = device.services.distinct().joinToString(" · "),
                    onCopy = ::copy,
                )
            }
            if (device.openPorts.isNotEmpty()) {
                DetailRow(
                    label = stringResource(R.string.field_ports),
                    value = device.openPorts.sorted().joinToString(", "),
                    onCopy = ::copy,
                )
            }
            if (device.sources.isNotEmpty()) {
                DetailRow(
                    label = stringResource(R.string.field_sources),
                    value = DiscoverySource.labels(device.sources),
                    onCopy = ::copy,
                )
            }

            if (device.mac == null) {
                // Labels are keyed by MAC: explain the limitation instead of
                // showing an editor that could not save anything.
                Text(
                    text = stringResource(R.string.no_mac_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                val locationLabel = if (device.location != null) {
                    stringResource(R.string.edit_location)
                } else {
                    stringResource(R.string.set_location)
                }
                OutlinedButton(
                    onClick = onEditLocation,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(
                        imageVector = Icons.Filled.LocationOn,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(text = locationLabel)
                }
            }
        }
    }
}

/** One labelled, copyable field of the detail sheet. */
@Composable
private fun DetailRow(
    label: String,
    value: String,
    onCopy: (String) -> Unit,
    monospace: Boolean = false,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = value,
                style = if (monospace) {
                    MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace)
                } else {
                    MaterialTheme.typography.bodyMedium
                },
            )
        }
        IconButton(onClick = { onCopy(value) }) {
            Icon(
                imageVector = Icons.Filled.ContentCopy,
                contentDescription = stringResource(R.string.copy),
                modifier = Modifier.size(18.dp),
            )
        }
    }
}
