package com.example.wifiscanner.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.DevicesOther
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material.icons.filled.WifiTethering
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.example.wifiscanner.R
import com.example.wifiscanner.model.DeviceKind
import com.example.wifiscanner.model.NetworkDevice
import com.example.wifiscanner.net.NetworkStatistics
import com.example.wifiscanner.ui.ScanUiState
import com.example.wifiscanner.ui.displayName
import com.example.wifiscanner.ui.signalQuality
import com.example.wifiscanner.ui.components.DeviceCard
import com.example.wifiscanner.ui.components.InfoCard
import com.example.wifiscanner.ui.components.InfoRow
import com.example.wifiscanner.ui.components.LabeledBar
import com.example.wifiscanner.ui.components.NetworkHeroCard
import com.example.wifiscanner.ui.components.NoteBanner
import com.example.wifiscanner.ui.components.SectionHeader
import com.example.wifiscanner.ui.components.StatTile
import com.example.wifiscanner.ui.components.iconForKind
import java.util.Locale

/**
 * Overview: the network the phone is on, the scan action, three headline
 * metrics, the device-type distribution and the most recently found devices.
 *
 * Everything is one scroll; no controls are duplicated here that already live
 * in the Devices or Network sections.
 */
@Composable
fun DashboardScreen(
    state: ScanUiState,
    stats: NetworkStatistics.WifiStatistics?,
    devices: List<NetworkDevice>,
    onScan: () -> Unit,
    onStop: () -> Unit,
    onOpenDevices: () -> Unit,
    onOpenNetwork: () -> Unit,
    onOpenDevice: (NetworkDevice) -> Unit,
    modifier: Modifier = Modifier,
) {
    val scanning = state is ScanUiState.Loading
    val phase = (state as? ScanUiState.Loading)?.phase
    val note = when (val current = state) {
        is ScanUiState.Loading -> current.note
        is ScanUiState.Success -> current.note
        else -> null
    }
    val elapsed = (state as? ScanUiState.Success)?.elapsedMillis
    val errorMessage = (state as? ScanUiState.Error)?.message
    val typeCount = remember(devices) { devices.map { it.kind }.distinct().size }
    val methodCount = remember(devices) { devices.flatMap { it.sources }.distinct().size }
    val lastScan = elapsed?.let { stringResource(R.string.dashboard_last_scan, seconds(it)) }

    // Built once per snapshot instead of on every scan emission (there can be
    // a dozen of them per scan while the hero card is on screen).
    val subtitle = remember(stats, lastScan) {
        listOfNotNull(
            stats?.ipAddress,
            stats?.band?.let { band ->
                band + (stats.channel?.let { " · canal $it" } ?: "")
            },
            lastScan,
        ).joinToString("  ·  ")
    }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            NetworkHeroCard(
                ssid = stats?.ssid,
                connected = stats?.wifiConnected == true,
                ssidHidden = stats?.ssidHidden == true,
                wifiEnabled = stats?.wifiEnabled,
                signalLevel = stats?.signalLevel,
                signalDbm = stats?.signalDbm,
                band = stats?.band,
                channel = stats?.channel,
                speedMbps = stats?.linkSpeedMbps,
                subtitle = subtitle.ifBlank { stringResource(R.string.dashboard_idle_hint) },
                scanning = scanning,
                phase = phase,
                deviceCount = devices.size,
                onScan = onScan,
                onStop = onStop,
            )
        }

        if (note != null) {
            item { NoteBanner(note) }
        }

        if (errorMessage != null) {
            item { ErrorBanner(message = errorMessage) }
        }

        item {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                StatTile(
                    value = devices.size.toString(),
                    label = stringResource(R.string.dashboard_devices),
                    icon = Icons.Filled.DevicesOther,
                    modifier = Modifier.weight(1f),
                )
                StatTile(
                    value = typeCount.toString(),
                    label = stringResource(R.string.dashboard_types),
                    icon = Icons.Filled.Apps,
                    tint = MaterialTheme.colorScheme.secondary,
                    modifier = Modifier.weight(1f),
                )
                StatTile(
                    value = methodCount.toString(),
                    label = stringResource(R.string.dashboard_methods),
                    icon = Icons.Filled.WifiTethering,
                    tint = MaterialTheme.colorScheme.tertiary,
                    modifier = Modifier.weight(1f),
                )
            }
        }

        if (devices.isEmpty()) {
            item { ColdStartCard(scanning = scanning, onScan = onScan) }
        } else {
            item {
                SectionHeader(title = stringResource(R.string.dashboard_types_title))
            }
            item { TypeBreakdownCard(devices) }
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    SectionHeader(
                        title = stringResource(R.string.dashboard_recent_title),
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = onOpenDevices) {
                        Text(
                            pluralStringResource(
                                R.plurals.dashboard_see_all,
                                devices.size,
                                devices.size,
                            )
                        )
                    }
                }
            }
            items(devices.take(3), key = { "recent-${it.ip}" }) { device ->
                DeviceCard(
                    device = device,
                    onClick = { onOpenDevice(device) },
                    onEditLocation = onOpenDevices,
                    compact = true,
                )
            }
        }

        item {
            InfoCard(
                title = stringResource(R.string.dashboard_link_title),
                icon = Icons.Filled.Wifi,
                trailing = {
                    IconButton(onClick = onOpenNetwork) {
                        Icon(
                            imageVector = Icons.Filled.ChevronRight,
                            contentDescription = stringResource(R.string.dashboard_open_network),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                },
                modifier = Modifier.clickable(onClick = onOpenNetwork),
            ) {
                if (stats == null) {
                    Text(
                        text = stringResource(R.string.network_loading),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 4.dp),
                    )
                } else {
                    InfoRow(
                        label = stringResource(R.string.stat_band_channel),
                        value = listOfNotNull(
                            stats.band,
                            stats.channel?.let { stringResource(R.string.stat_channel, it) },
                        ).joinToString(" · ").ifBlank { null },
                    )
                    InfoRow(
                        label = stringResource(R.string.stat_signal),
                        value = stats.signalDbm?.let { dbm ->
                            listOfNotNull("$dbm dBm", signalQuality(stats.signalLevel))
                                .joinToString(" · ")
                        },
                    )
                    InfoRow(
                        label = stringResource(R.string.stat_link_speed),
                        value = stats.linkSpeedMbps?.let { "$it Mbps" },
                    )
                    InfoRow(stringResource(R.string.stat_ip), stats.ipAddress)
                    InfoRow(stringResource(R.string.stat_gateway), stats.gateway)
                    InfoRow(
                        label = stringResource(R.string.stat_dns),
                        value = listOfNotNull(stats.dns1, stats.dns2)
                            .joinToString(" · ")
                            .ifBlank { null },
                    )
                    InfoRow(
                        label = stringResource(R.string.stat_hosts),
                        value = stats.subnetHosts?.toString(),
                    )
                    if (stats.ipAddress == null && stats.gateway == null &&
                        stats.dns1 == null && stats.dns2 == null && stats.subnetHosts == null
                    ) {
                        Text(
                            text = stringResource(R.string.network_unavailable),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(vertical = 4.dp),
                        )
                    }
                }
            }
        }
    }
}

/**
 * Distribution of the current results by inferred type: one bar per type,
 * longest first. Caps at six rows and folds the rest into an "Other" count so
 * the card never grows unbounded.
 */
@Composable
private fun TypeBreakdownCard(devices: List<NetworkDevice>) {
    val counts = remember(devices) {
        devices.groupingBy { it.kind }
            .eachCount()
            .toList()
            .sortedByDescending { (_, count) -> count }
    }
    val max = counts.maxOfOrNull { (_, count) -> count } ?: 1
    val shown = counts.take(6)

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            shown.forEach { (kind, count) ->
                LabeledBar(
                    icon = iconForKind(kind),
                    label = kind.displayName(),
                    count = count,
                    max = max,
                    tint = if (kind == DeviceKind.Unknown) {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    } else {
                        MaterialTheme.colorScheme.primary
                    },
                )
            }
            val hidden = counts.drop(6).sumOf { (_, count) -> count }
            if (hidden > 0) {
                Text(
                    text = stringResource(R.string.dashboard_other_types, hidden),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** Invitation shown before the first scan of the session. */
@Composable
private fun ColdStartCard(scanning: Boolean, onScan: () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            Text(
                text = stringResource(R.string.dashboard_empty_title),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = stringResource(R.string.dashboard_idle_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (!scanning) {
                Spacer(Modifier.height(16.dp))
                OutlinedButton(onClick = onScan) {
                    Icon(
                        imageVector = Icons.Filled.PlayArrow,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.dashboard_scan_now))
                }
            }
        }
    }
}

/** Scan failure shown inline, without hiding the rest of the overview. */
@Composable
private fun ErrorBanner(message: String) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Icon(
                imageVector = Icons.Filled.ErrorOutline,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(10.dp))
            Text(text = message, style = MaterialTheme.typography.bodySmall)
        }
    }
}

/** "8.4 s", formatted for the current locale. */
private fun seconds(millis: Long): String =
    String.format(Locale.getDefault(), "%.1f s", millis / 1000.0)
