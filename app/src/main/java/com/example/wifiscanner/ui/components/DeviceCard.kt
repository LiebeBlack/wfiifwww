package com.example.wifiscanner.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.wifiscanner.R
import com.example.wifiscanner.model.DiscoverySource
import com.example.wifiscanner.model.NetworkDevice

/**
 * One device in the scan results list.
 *
 * Shows the inferred [com.example.wifiscanner.model.DeviceKind] as the leading
 * icon and as a label, so devices are recognizable even when only a service
 * announcement was found and no MAC/vendor is available. Tapping the card
 * opens the full detail sheet; the button edits the location label.
 *
 * @param device          the model to render.
 * @param onClick         opens the detail sheet for this device.
 * @param onEditLocation  opens the label editor for this device's MAC.
 */
@Composable
fun DeviceCard(
    device: NetworkDevice,
    onClick: () -> Unit,
    onEditLocation: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = iconForKind(device.kind),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(36.dp),
            )

            Spacer(Modifier.width(16.dp))

            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                // Name (or IP fallback) as the title.
                Text(
                    text = device.displayName,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )

                // Inferred type.
                Text(
                    text = device.kind.label,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.tertiary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )

                // Address (+ self marker).
                Text(
                    text = buildString {
                        append(device.ip)
                        if (device.isSelf) append("  •  This device")
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )

                // Hardware address, when any method could recover one.
                device.mac?.let { mac ->
                    Text(
                        text = mac,
                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                    )
                }

                // Brand from the offline OUI table, or the model the device
                // itself advertised over mDNS.
                val brand = device.vendor ?: device.models.firstOrNull()
                Text(
                    text = brand ?: stringResource(R.string.unknown_vendor),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (brand == null) {
                        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                    } else {
                        MaterialTheme.colorScheme.secondary
                    },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )

                // Announced service classes (SSDP/mDNS).
                val services = device.services.filter { it.isNotBlank() }.distinct()
                if (services.isNotEmpty()) {
                    Text(
                        text = services.joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.tertiary,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }

                // Which discovery strategies saw this device; makes a degraded
                // scan self-explanatory.
                if (device.sources.isNotEmpty()) {
                    Text(
                        text = stringResource(
                            R.string.detected_via,
                            DiscoverySource.labels(device.sources),
                        ),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }

                // Labels are keyed by MAC, so offer the editor only when a
                // hardware address exists — otherwise explain why it is
                // missing instead of silently saving nothing.
                if (device.mac != null) {
                    OutlinedButton(onClick = onEditLocation) {
                        Icon(
                            imageVector = Icons.Filled.LocationOn,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                        )
                        Spacer(Modifier.width(4.dp))
                        Text(
                            text = device.location ?: stringResource(R.string.set_location),
                            style = MaterialTheme.typography.labelMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                } else {
                    Text(
                        text = stringResource(R.string.no_mac_hint),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}
