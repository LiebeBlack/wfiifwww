package com.example.wifiscanner.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.wifiscanner.R
import com.example.wifiscanner.net.NetworkStatistics
import java.util.Locale

/**
 * Collapsible card with everything the app can read about the current Wi-Fi/LAN
 * link: SSID and access point, band/channel, negotiated speed, signal, IPv4
 * configuration (address, gateway, DNS, DHCP lease) and the platform-reported
 * link bandwidth — plus how long the last scan took and how many devices it
 * found.
 *
 * Fields the platform does not expose (denied Location permission, static IP,
 * no DHCP) are simply omitted instead of showing placeholders.
 *
 * @param stats         snapshot from [NetworkStatistics]; null until first read.
 * @param deviceCount   devices in the current result list.
 * @param elapsedMillis duration of the last completed scan, if any.
 */
@Composable
fun ScanStatsCard(
    stats: NetworkStatistics.WifiStatistics?,
    deviceCount: Int,
    elapsedMillis: Long?,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    val rows = statRows(stats = stats, deviceCount = deviceCount, elapsedMillis = elapsedMillis)

    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
            // ---- Header (tap to expand) ---------------------------------
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = Icons.Filled.Wifi,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp),
                )
                Spacer(Modifier.width(10.dp))
                Text(
                    text = stringResource(R.string.stats_title),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.weight(1f))
                Text(
                    text = headerSummary(stats),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Icon(
                    imageVector = if (expanded) {
                        Icons.Filled.KeyboardArrowUp
                    } else {
                        Icons.Filled.KeyboardArrowDown
                    },
                    contentDescription = stringResource(
                        if (expanded) R.string.stats_collapse else R.string.stats_expand
                    ),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp),
                )
            }

            // ---- Details -------------------------------------------------
            if (expanded) {
                Spacer(Modifier.height(8.dp))
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    rows.forEach { (label, value) ->
                        StatRow(label = label, value = value)
                    }
                }
            }
        }
    }
}

/** One label/value line; the value is right-aligned and single-line. */
@Composable
private fun StatRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(0.45f),
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            textAlign = TextAlign.End,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(0.55f),
        )
    }
}

/** Always-visible one-liner: SSID + band/channel, or the scan summary. */
@Composable
private fun headerSummary(stats: NetworkStatistics.WifiStatistics?): String {
    val ssid = stats?.ssid
    val band = stats?.band
    val channel = stats?.channel
    return when {
        ssid != null && band != null && channel != null -> "$ssid · $band ch.$channel"
        ssid != null -> ssid
        band != null && channel != null -> "$band ch.$channel"
        else -> stringResource(R.string.stats_no_wifi)
    }
}

/**
 * Builds the label/value rows, skipping everything that is unknown. Kept
 * composable so the labels come from resources.
 */
@Composable
private fun statRows(
    stats: NetworkStatistics.WifiStatistics?,
    deviceCount: Int,
    elapsedMillis: Long?,
): List<Pair<String, String>> {
    val rows = ArrayList<Pair<String, String>>(20)
    val yes = stringResource(R.string.value_yes)
    val no = stringResource(R.string.value_no)

    // ---- Scan summary ---------------------------------------------------
    elapsedMillis?.let {
        rows.add(stringResource(R.string.stat_scan_time) to seconds(it))
    }
    rows.add(stringResource(R.string.stat_devices) to deviceCount.toString())

    // ---- Link -----------------------------------------------------------
    stats ?: return rows
    stats.transport?.let { rows.add(stringResource(R.string.stat_connection) to it) }
    stats.metered?.let { rows.add(stringResource(R.string.stat_metered) to if (it) yes else no) }
    stats.wifiEnabled?.let { rows.add(stringResource(R.string.stat_wifi_enabled) to if (it) yes else no) }
    stats.ssid?.let { rows.add(stringResource(R.string.stat_ssid) to it) }
    stats.bssid?.let { rows.add(stringResource(R.string.stat_bssid) to it) }
    val bandChannel = listOfNotNull(
        stats.band,
        stats.channel?.let { stringResource(R.string.stat_channel, it) },
    )
    if (bandChannel.isNotEmpty()) {
        rows.add(stringResource(R.string.stat_band_channel) to bandChannel.joinToString(" · "))
    }
    stats.linkSpeedMbps?.let {
        rows.add(stringResource(R.string.stat_link_speed) to "$it Mbps")
    }
    if (stats.rxLinkSpeedMbps != null || stats.txLinkSpeedMbps != null) {
        rows.add(
            stringResource(R.string.stat_rx_tx) to "${stats.rxLinkSpeedMbps ?: 0} / ${stats.txLinkSpeedMbps ?: 0} Mbps"
        )
    }
    if (stats.signalDbm != null) {
        val level = stats.signalLevel?.let { it + 1 }
        rows.add(
            stringResource(R.string.stat_signal) to
                if (level != null) "${stats.signalDbm} dBm ($level/5)" else "${stats.signalDbm} dBm"
        )
    }

    // ---- IPv4 configuration ---------------------------------------------
    val address = listOfNotNull(
        stats.ipAddress,
        stats.prefixLength?.let { "/$it" },
    ).joinToString("")
    if (address.isNotEmpty()) rows.add(stringResource(R.string.stat_ip) to address)
    stats.networkAddress?.let { rows.add(stringResource(R.string.stat_network) to it) }
    stats.broadcastAddress?.let { rows.add(stringResource(R.string.stat_broadcast) to it) }
    stats.gateway?.let { rows.add(stringResource(R.string.stat_gateway) to it) }
    val dns = listOfNotNull(stats.dns1, stats.dns2)
    if (dns.isNotEmpty()) rows.add(stringResource(R.string.stat_dns) to dns.joinToString(" · "))
    stats.dhcpServer?.let { rows.add(stringResource(R.string.stat_dhcp) to it) }
    stats.leaseSeconds?.let { rows.add(stringResource(R.string.stat_lease) to minutes(it)) }
    stats.subnetHosts?.let { rows.add(stringResource(R.string.stat_hosts) to it.toString()) }

    // ---- Platform capabilities -------------------------------------------
    if (stats.downstreamKbps != null || stats.upstreamKbps != null) {
        rows.add(
            stringResource(R.string.stat_bandwidth) to
                "${(stats.downstreamKbps ?: 0) / 1000} / ${(stats.upstreamKbps ?: 0) / 1000} Mbps"
        )
    }
    stats.vpnActive?.let { rows.add(stringResource(R.string.stat_vpn) to if (it) yes else no) }

    return rows
}

/** "8.4 s", formatted for the current locale. */
private fun seconds(millis: Long): String =
    String.format(Locale.getDefault(), "%.1f s", millis / 1000.0)

/** "120 min", or seconds when the lease is very short. */
private fun minutes(leaseSeconds: Int): String =
    if (leaseSeconds >= 60) "${leaseSeconds / 60} min" else "$leaseSeconds s"
