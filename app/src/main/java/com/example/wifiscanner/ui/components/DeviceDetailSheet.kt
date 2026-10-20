package com.example.wifiscanner.ui.components

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.wifiscanner.R
import com.example.wifiscanner.model.DiscoverySource
import com.example.wifiscanner.model.NetworkDevice
import com.example.wifiscanner.ui.displayLabel
import com.example.wifiscanner.ui.displayName
import com.example.wifiscanner.ui.serviceLabel

/**
 * Full picture of one discovered device, opened by tapping a card.
 *
 * Structured as a header (who it is) plus two evidence chapters (identity and
 * how it was found). Every value can be copied to the clipboard, which makes
 * the sheet usable for support tickets; the label editor stays at the bottom.
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
    val kind = remember(device) { device.kind }

    /** Copies [value] and confirms it with a toast. */
    fun copy(value: String) {
        clipboard.setText(AnnotatedString(value))
        Toast.makeText(context, copiedMessage, Toast.LENGTH_SHORT).show()
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        shape = MaterialTheme.shapes.extraLarge,
        containerColor = MaterialTheme.colorScheme.background,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding()
                .padding(start = 20.dp, end = 20.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // ---- Header -------------------------------------------------
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(52.dp)
                        .clip(RoundedCornerShape(18.dp))
                        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = iconForKind(kind),
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(26.dp),
                    )
                }
                Spacer(Modifier.width(14.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = device.displayName,
                        style = MaterialTheme.typography.headlineSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = kind.displayName(),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.secondary,
                    )
                }
                if (device.isSelf) {
                    Text(
                        text = stringResource(R.string.own_device).uppercase(),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f))
                            .padding(horizontal = 8.dp, vertical = 3.dp),
                    )
                }
            }

            Spacer(Modifier.height(2.dp))

            // ---- Identity -----------------------------------------------
            InfoCard(
                title = stringResource(R.string.field_identity),
                icon = Icons.Filled.Info,
            ) {
                CopyRow(
                    label = stringResource(R.string.field_ip),
                    value = device.ip,
                    onCopy = ::copy,
                    monospace = true,
                )
                device.mac?.let { mac ->
                    CopyRow(
                        label = stringResource(R.string.field_mac),
                        value = mac,
                        onCopy = ::copy,
                        monospace = true,
                    )
                }
                device.hostname?.let { hostname ->
                    CopyRow(
                        label = stringResource(R.string.field_hostname),
                        value = hostname,
                        onCopy = ::copy,
                    )
                }
                device.vendor?.let { vendor ->
                    CopyRow(
                        label = stringResource(R.string.field_vendor),
                        value = vendor,
                        onCopy = ::copy,
                    )
                }
                if (device.models.isNotEmpty()) {
                    CopyRow(
                        label = stringResource(R.string.field_model),
                        value = device.models.distinct().joinToString(" · "),
                        onCopy = ::copy,
                    )
                }
                device.location?.let { location ->
                    CopyRow(
                        label = stringResource(R.string.field_location),
                        value = location,
                        onCopy = ::copy,
                    )
                }
            }

            // ---- Evidence -----------------------------------------------
            if (device.services.isNotEmpty() || device.openPorts.isNotEmpty() || device.sources.isNotEmpty()) {
                InfoCard(
                    title = stringResource(R.string.field_evidence),
                    icon = Icons.Filled.Link,
                ) {
                    if (device.sources.isNotEmpty()) {
                        CopyRow(
                            label = stringResource(R.string.field_sources),
                            value = DiscoverySource.ordered(device.sources)
                                .joinToString(" · ") { it.displayLabel() },
                            onCopy = ::copy,
                        )
                    }
                    if (device.services.isNotEmpty()) {
                        CopyRow(
                            label = stringResource(R.string.field_services),
                            value = device.services.distinct()
                                .joinToString(" · ") { serviceLabel(it) },
                            onCopy = ::copy,
                        )
                    }
                    if (device.openPorts.isNotEmpty()) {
                        CopyRow(
                            label = stringResource(R.string.field_ports),
                            value = device.openPorts.sorted().joinToString(", "),
                            onCopy = ::copy,
                            monospace = true,
                        )
                    }
                }
            }

            // ---- Label --------------------------------------------------
            if (device.mac == null) {
                Text(
                    text = stringResource(R.string.no_mac_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Button(
                    onClick = onEditLocation,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 4.dp),
                ) {
                    Icon(
                        imageVector = Icons.Filled.LocationOn,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = stringResource(
                            if (device.location != null) R.string.edit_location else R.string.set_location
                        )
                    )
                }
            }
        }
    }
}

/** One labelled, copyable field inside a detail card. */
@Composable
private fun CopyRow(
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
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
        IconButton(onClick = { onCopy(value) }) {
            Icon(
                imageVector = Icons.Filled.ContentCopy,
                contentDescription = stringResource(R.string.copy),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(17.dp),
            )
        }
    }
}
