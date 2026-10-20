package com.example.wifiscanner.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Router
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material.icons.filled.WifiOff
import androidx.compose.material.icons.filled.WifiTethering
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.example.wifiscanner.R
import com.example.wifiscanner.model.DiscoverySource
import com.example.wifiscanner.model.NetworkDevice
import com.example.wifiscanner.net.NetworkStatistics
import com.example.wifiscanner.ui.SystemSettings
import com.example.wifiscanner.ui.components.InfoCard
import com.example.wifiscanner.ui.components.InfoRow
import com.example.wifiscanner.ui.components.LabeledBar
import com.example.wifiscanner.ui.components.SignalBars
import com.example.wifiscanner.ui.components.requiredRuntimePermissions
import com.example.wifiscanner.ui.displayLabel
import com.example.wifiscanner.ui.displayName
import com.example.wifiscanner.ui.signalQuality

/**
 * Network section: the full read-only picture of the current Wi-Fi/LAN link,
 * grouped into link / addressing / capabilities, plus which discovery methods
 * actually produced the current results.
 *
 * Every value is nullable and simply omitted when the platform does not
 * expose it (static IP, older API). A redacted SSID is never hidden: when
 * Android withholds it, the screen explains why and offers the exact fix
 * (grant the permission or turn the system location switch on).
 */
@Composable
fun NetworkScreen(
    stats: NetworkStatistics.WifiStatistics?,
    devices: List<NetworkDevice>,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val yes = stringResource(R.string.value_yes)
    val no = stringResource(R.string.value_no)
    val context = LocalContext.current

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        // Re-read after the dialog closes: the SSID appears immediately when
        // the grant is accepted, and the warning stays visible when it is not.
        onRefresh()
    }

    // A readable card beats one with a header and a divider but no rows: the
    // platform can legitimately return nothing (no permission, static IP).
    val linkMissing = stats != null &&
        stats.ssid == null && stats.bssid == null && stats.signalDbm == null &&
        stats.linkSpeedMbps == null && stats.frequencyMhz == null
    val ipv4Missing = stats != null &&
        stats.ipAddress == null && stats.networkAddress == null && stats.gateway == null &&
        stats.dns1 == null && stats.dhcpServer == null
    val capabilitiesMissing = stats != null &&
        stats.metered == null && stats.vpnActive == null && stats.wifiEnabled == null &&
        stats.downstreamKbps == null && stats.upstreamKbps == null
    val needsSsidFix = stats != null && stats.ssidHidden && stats.wifiConnected

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            InfoCard(
                title = stringResource(R.string.network_wifi_link),
                icon = Icons.Filled.Wifi,
                trailing = {
                    IconButton(onClick = onRefresh) {
                        Icon(
                            imageVector = Icons.Filled.Refresh,
                            contentDescription = stringResource(R.string.network_refresh),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(19.dp),
                        )
                    }
                },
            ) {
                if (stats == null) {
                    LoadingRow()
                } else {
                    // State first: connected, connected-but-redacted, off, or
                    // simply not on Wi-Fi. This is what the user came for.
                    LinkStatusRow(stats)
                    val ssidNeedsLocation = stats.ssidHidden &&
                        (!stats.locationPermissionGranted || !stats.locationServicesEnabled)
                    InfoRow(
                        label = stringResource(R.string.stat_ssid),
                        value = stats.ssid ?: when {
                            ssidNeedsLocation ->
                                stringResource(R.string.dashboard_ssid_unavailable)
                            stats.ssidHidden -> stringResource(R.string.dashboard_ssid_hidden)
                            else -> null
                        },
                    )
                    InfoRow(stringResource(R.string.stat_bssid), stats.bssid, monospace = true)
                    InfoRow(
                        label = stringResource(R.string.stat_connection),
                        value = stats.transport?.displayName(),
                    )
                    InfoRow(
                        label = stringResource(R.string.stat_band_channel),
                        value = listOfNotNull(
                            stats.band,
                            stats.channel?.let { stringResource(R.string.stat_channel, it) },
                        ).joinToString(" · ").ifBlank { null },
                    )
                    InfoRow(
                        label = stringResource(R.string.stat_frequency),
                        value = stats.frequencyMhz?.let { "$it MHz" },
                    )
                    InfoRow(
                        label = stringResource(R.string.stat_standard),
                        value = stats.wifiStandard,
                    )
                    InfoRow(
                        label = stringResource(R.string.stat_link_speed),
                        value = stats.linkSpeedMbps?.let { "$it Mbps" },
                    )
                    InfoRow(
                        label = stringResource(R.string.stat_max_speed),
                        value = if (stats.maxRxLinkSpeedMbps != null || stats.maxTxLinkSpeedMbps != null) {
                            "${stats.maxRxLinkSpeedMbps ?: 0} / ${stats.maxTxLinkSpeedMbps ?: 0} Mbps"
                        } else {
                            null
                        },
                    )
                    InfoRow(
                        label = stringResource(R.string.stat_ap_vendor),
                        value = stats.accessPointVendor,
                    )
                    InfoRow(
                        label = stringResource(R.string.stat_hidden_ssid),
                        value = stats.hiddenSsid?.let { hidden -> if (hidden) yes else no },
                    )
                    InfoRow(
                        label = stringResource(R.string.stat_network_id),
                        value = stats.networkId?.toString(),
                    )
                    InfoRow(
                        label = stringResource(R.string.stat_rx_tx),
                        value = if (stats.rxLinkSpeedMbps != null || stats.txLinkSpeedMbps != null) {
                            "${stats.rxLinkSpeedMbps ?: 0} / ${stats.txLinkSpeedMbps ?: 0} Mbps"
                        } else {
                            null
                        },
                    )
                    InfoRow(
                        label = stringResource(R.string.stat_signal),
                        value = stats.signalDbm?.let { dbm ->
                            val level = stats.signalLevel?.let { it + 1 }
                            if (level != null) "$dbm dBm · $level/5" else "$dbm dBm"
                        },
                    )
                    InfoRow(
                        label = stringResource(R.string.stat_signal_quality),
                        value = signalQuality(stats.signalLevel),
                    )
                    if (stats.signalLevel != null) {
                        SignalBars(level = stats.signalLevel)
                    }
                    if (stats.hiddenSsid == true) {
                        Text(
                            text = stringResource(R.string.network_hidden_ssid_note),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 6.dp),
                        )
                    }
                    if (linkMissing) UnavailableNote()
                }
            }
        }

        // ---- Explain and fix a redacted SSID ---------------------------
        item {
            AnimatedVisibility(visible = needsSsidFix) {
                SsidFixCard(
                    permissionGranted = stats?.locationPermissionGranted == true,
                    locationEnabled = stats?.locationServicesEnabled == true,
                    onGrantPermission = {
                        permissionLauncher.launch(requiredRuntimePermissions())
                    },
                    onOpenLocationSettings = {
                        SystemSettings.openLocationSettings(context)
                    },
                )
            }
        }

        item {
            InfoCard(
                title = stringResource(R.string.network_ipv4),
                icon = Icons.Filled.Router,
            ) {
                InfoRow(
                    label = stringResource(R.string.stat_ip),
                    value = listOfNotNull(
                        stats?.ipAddress,
                        stats?.prefixLength?.let { "/$it" },
                    ).joinToString("").ifBlank { null },
                    monospace = true,
                )
                InfoRow(
                    label = stringResource(R.string.stat_prefix),
                    value = listOfNotNull(
                        stats?.prefixLength?.let { "/$it" },
                        stats?.prefixLength?.let { maskOf(it) },
                    ).joinToString(" · ").ifBlank { null },
                    monospace = true,
                )
                InfoRow(stringResource(R.string.stat_network), stats?.networkAddress, monospace = true)
                InfoRow(stringResource(R.string.stat_broadcast), stats?.broadcastAddress, monospace = true)
                InfoRow(
                    label = stringResource(R.string.stat_interface),
                    value = stats?.interfaceName,
                    monospace = true,
                )
                InfoRow(
                    label = stringResource(R.string.stat_ipv6),
                    value = stats?.ipv6Addresses?.take(3)?.joinToString(" · "),
                    monospace = true,
                )
                InfoRow(stringResource(R.string.stat_gateway), stats?.gateway, monospace = true)
                InfoRow(
                    label = stringResource(R.string.stat_dns),
                    value = listOfNotNull(stats?.dns1, stats?.dns2).joinToString(" · ").ifBlank { null },
                    monospace = true,
                )
                InfoRow(stringResource(R.string.stat_dhcp), stats?.dhcpServer, monospace = true)
                InfoRow(
                    label = stringResource(R.string.stat_lease),
                    value = stats?.leaseSeconds?.let { lease ->
                        if (lease >= 60) "${lease / 60} min" else "$lease s"
                    },
                )
                InfoRow(stringResource(R.string.stat_hosts), stats?.subnetHosts?.toString())
                if (stats == null) LoadingRow() else if (ipv4Missing) UnavailableNote()
            }
        }

        item {
            InfoCard(
                title = stringResource(R.string.network_capabilities),
                icon = Icons.Filled.Info,
            ) {
                InfoRow(stringResource(R.string.stat_metered), stats?.metered?.let { if (it) yes else no })
                InfoRow(stringResource(R.string.stat_vpn), stats?.vpnActive?.let { if (it) yes else no })
                InfoRow(
                    label = stringResource(R.string.stat_wifi_enabled),
                    value = stats?.wifiEnabled?.let { if (it) yes else no },
                )
                InfoRow(
                    label = stringResource(R.string.stat_bandwidth),
                    value = if (stats?.downstreamKbps != null || stats?.upstreamKbps != null) {
                        "${(stats?.downstreamKbps ?: 0) / 1000} / ${(stats?.upstreamKbps ?: 0) / 1000} Mbps"
                    } else {
                        null
                    },
                )
                if (stats == null) LoadingRow() else if (capabilitiesMissing) UnavailableNote()
            }
        }

        item { DiscoveryCard(devices) }

        // Show top devices by service count
        val topDevices = remember(devices) {
            devices.sortedByDescending { it.services.size }.take(3)
        }
        if (topDevices.isNotEmpty() && topDevices.any { it.services.isNotEmpty() }) {
            item {
                InfoCard(
                    title = "Dispositivos más activos",
                    icon = Icons.Filled.WifiTethering,
                ) {
                    topDevices.forEach { device ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = device.displayName,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.weight(1f),
                                maxLines = 1,
                            )
                            Text(
                                text = "${device.services.size} serv.",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.secondary,
                            )
                        }
                    }
                }
            }
        }

        item { Spacer(Modifier.height(4.dp)) }
    }
}

/** First line of the link card: connected / off / not on Wi-Fi. */
@Composable
private fun LinkStatusRow(stats: NetworkStatistics.WifiStatistics) {
    val (text, tint) = when {
        stats.wifiConnected -> stringResource(R.string.dashboard_connected) to
            MaterialTheme.colorScheme.primary
        stats.wifiEnabled == false -> stringResource(R.string.dashboard_wifi_off) to
            MaterialTheme.colorScheme.error
        stats.transport != null && stats.transport != NetworkStatistics.Transport.Wifi ->
            // Ethernet / cellular / VPN only: claiming "Wi-Fi sin conexión"
            // would be wrong — there *is* a link, just not the Wi-Fi one.
            stringResource(R.string.dashboard_other_transport, stats.transport.displayName()) to
                MaterialTheme.colorScheme.tertiary
        else -> stringResource(R.string.dashboard_wifi_disconnected) to
            MaterialTheme.colorScheme.tertiary
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = if (stats.wifiConnected) Icons.Filled.Wifi else Icons.Filled.WifiOff,
            contentDescription = null,
            tint = tint,
            modifier = Modifier.size(16.dp),
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = text,
            style = MaterialTheme.typography.titleSmall,
            color = tint,
        )
    }
}

/** Spinner while the first snapshot is being read. */
@Composable
private fun LoadingRow() {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CircularProgressIndicator(
            modifier = Modifier.size(16.dp),
            strokeWidth = 2.dp,
            color = MaterialTheme.colorScheme.primary,
        )
        Spacer(Modifier.width(10.dp))
        Text(
            text = stringResource(R.string.network_loading),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * Card that turns "the SSID is blank" into an actionable explanation. Android
 * redacts the network name unless the location permission is granted *and*
 * the system location switch is on; both cases get their own button.
 */
@Composable
private fun SsidFixCard(
    permissionGranted: Boolean,
    locationEnabled: Boolean,
    onGrantPermission: () -> Unit,
    onOpenLocationSettings: () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.tertiaryContainer,
        contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
    ) {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Filled.LocationOn,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(10.dp))
                Text(
                    text = stringResource(R.string.network_ssid_hidden_title),
                    style = MaterialTheme.typography.titleSmall,
                )
            }
            Spacer(Modifier.height(6.dp))
            Text(
                text = stringResource(R.string.network_ssid_hidden_body),
                style = MaterialTheme.typography.bodySmall,
            )
            if (!permissionGranted) {
                Spacer(Modifier.height(12.dp))
                Button(onClick = onGrantPermission) {
                    Text(stringResource(R.string.network_grant_location))
                }
            } else if (!locationEnabled) {
                Spacer(Modifier.height(12.dp))
                Button(onClick = onOpenLocationSettings) {
                    Text(stringResource(R.string.network_enable_location))
                }
            }
        }
    }
}

/** Fallback line when the platform returned no values for a card. */
@Composable
private fun UnavailableNote() {
    Text(
        text = stringResource(R.string.network_unavailable),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(vertical = 4.dp),
    )
}

/** Which discovery methods produced the current result set, with counts. */
@Composable
private fun DiscoveryCard(devices: List<NetworkDevice>) {
    val counts = remember(devices) {
        devices.flatMap { it.sources }.groupingBy { it }.eachCount()
    }
    val ordered = remember(counts) { DiscoverySource.ordered(counts.keys) }
    val max = counts.values.maxOrNull() ?: 1

    InfoCard(
        title = stringResource(R.string.network_discovery),
        icon = Icons.Filled.WifiTethering,
        trailing = {
            if (devices.isNotEmpty()) {
                Text(
                    text = pluralStringResource(
                        R.plurals.network_devices_seen,
                        devices.size,
                        devices.size,
                    ),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
    ) {
        if (ordered.isEmpty()) {
            Text(
                text = stringResource(R.string.network_discovery_empty),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 4.dp),
            )
        } else {
            ordered.forEach { source ->
                LabeledBar(
                    icon = Icons.Filled.Check,
                    label = source.displayLabel(),
                    count = counts[source] ?: 0,
                    max = max,
                    tint = MaterialTheme.colorScheme.secondary,
                    modifier = Modifier.padding(vertical = 3.dp),
                )
            }
        }
    }
}

/** Dotted netmask for a prefix length, e.g. 24 -> 255.255.255.0. */
private fun maskOf(prefix: Int): String? {
    if (prefix !in 0..32) return null
    val mask = if (prefix == 0) 0L else (0xFFFFFFFFL shl (32 - prefix)) and 0xFFFFFFFFL
    return "${(mask shr 24) and 0xFF}.${(mask shr 16) and 0xFF}." +
        "${(mask shr 8) and 0xFF}.${mask and 0xFF}"
}
