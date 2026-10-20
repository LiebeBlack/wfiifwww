package com.example.wifiscanner.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material.icons.filled.WifiOff
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.wifiscanner.R

/**
 * Headline card of the overview: the network the phone is on, its link facts
 * (signal, band, negotiated speed) and the one action that matters — start
 * (or stop) a scan.
 *
 * The state line is explicit about *why* a name may be missing: Wi-Fi off,
 * not connected, or connected while Android hides the SSID. That distinction
 * is what keeps the card from claiming "no Wi-Fi" on a perfectly good link.
 *
 * The gradient is built from the theme's containers, so it follows light,
 * dark and dynamic colour without hard-coded hues. Only the progress area
 * animates, and only when a scan starts or stops, so a phase change never
 * triggers a layout animation.
 */
@Composable
fun NetworkHeroCard(
    ssid: String?,
    connected: Boolean,
    ssidHidden: Boolean,
    /** Whether the location permission is granted (needed to read the SSID). */
    locationPermissionGranted: Boolean = true,
    /** Whether the system location switch is on (also needed for the SSID). */
    locationEnabled: Boolean = true,
    wifiEnabled: Boolean?,
    signalLevel: Int?,
    signalDbm: Int?,
    /** True while the first snapshot is still being read. */
    loading: Boolean = false,
    /** Transport in use when it is not Wi-Fi (Ethernet, VPN, ...). */
    linkTransport: String? = null,
    band: String?,
    channel: Int?,
    speedMbps: Int?,
    subtitle: String,
    scanning: Boolean,
    phase: String?,
    deviceCount: Int,
    onScan: () -> Unit,
    onStop: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // Android withholds the SSID for two very different reasons: the network
    // really is hidden, or location is off/denied. Saying "red oculta" in the
    // second case would be wrong and would hide the exact fix from the user.
    val ssidNeedsLocation = ssidHidden && (!locationPermissionGranted || !locationEnabled)

    // The status line answers "am I on Wi-Fi?"; the title separately answers
    // "which network?" and may say "red oculta" when Android redacts it.
    val status = when {
        // Never claim "no Wi-Fi" before the first read finishes: that flash is
        // exactly what made a connected phone look offline on cold start.
        loading -> stringResource(R.string.dashboard_reading_network)
        connected -> stringResource(R.string.dashboard_connected)
        wifiEnabled == false -> stringResource(R.string.dashboard_wifi_off)
        linkTransport != null -> stringResource(R.string.dashboard_other_transport, linkTransport)
        else -> stringResource(R.string.dashboard_wifi_disconnected)
    }
    val title = when {
        loading -> stringResource(R.string.dashboard_reading_network)
        ssid != null -> ssid
        connected && ssidHidden -> stringResource(
            if (ssidNeedsLocation) R.string.dashboard_ssid_unavailable
            else R.string.dashboard_ssid_hidden
        )
        else -> stringResource(R.string.dashboard_no_network)
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.extraLarge)
            .background(
                Brush.linearGradient(
                    colors = listOf(
                        MaterialTheme.colorScheme.primaryContainer,
                        MaterialTheme.colorScheme.secondaryContainer,
                        MaterialTheme.colorScheme.surface,
                    ),
                ),
            )
            .padding(20.dp),
    ) {
        Column {
            // Deliberately no animateContentSize here: it re-ran a layout
            // animation on every scan phase (a dozen per scan), which is the
            // main source of jank while the progress bar is visible. The only
            // animated block is the progress area, and it animates once per
            // scan start/stop.
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = if (connected) Icons.Filled.Wifi else Icons.Filled.WifiOff,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(17.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = status.uppercase(),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                SignalBars(level = signalLevel)
            }

            Spacer(Modifier.height(14.dp))
            Text(
                text = title,
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )

            if (ssidHidden) {
                Spacer(Modifier.height(8.dp))
                Text(
                    text = stringResource(
                        if (ssidNeedsLocation) R.string.dashboard_ssid_hidden_hint
                        else R.string.network_hidden_ssid_note
                    ),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.tertiary,
                )
            }

            // ---- Link facts ---------------------------------------------
            if (connected && (signalDbm != null || band != null || speedMbps != null)) {
                Spacer(Modifier.height(14.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                    if (signalDbm != null) {
                        HeroFact(
                            label = stringResource(R.string.dashboard_signal),
                            value = "$signalDbm dBm",
                        )
                    }
                    if (band != null) {
                        HeroFact(
                            label = stringResource(R.string.dashboard_band),
                            value = channel?.let { "$band · $it" } ?: band,
                        )
                    }
                    if (speedMbps != null) {
                        HeroFact(
                            label = stringResource(R.string.dashboard_speed),
                            value = "$speedMbps Mbps",
                        )
                    }
                }
            }

            Spacer(Modifier.height(18.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Button(onClick = if (scanning) onStop else onScan) {
                    Icon(
                        imageVector = if (scanning) Icons.Filled.Stop else Icons.Filled.PlayArrow,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        stringResource(
                            if (scanning) R.string.dashboard_stop else R.string.dashboard_scan_now
                        )
                    )
                }
                Spacer(Modifier.weight(1f))
                if (!scanning) {
                    Text(
                        text = pluralStringResource(R.plurals.devices_found, deviceCount, deviceCount),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            AnimatedVisibility(
                visible = scanning,
                enter = fadeIn(tween(160)) + expandVertically(tween(200)),
                exit = fadeOut(tween(120)) + shrinkVertically(tween(160)),
            ) {
                Column {
                    Spacer(Modifier.height(14.dp))
                    LinearProgressIndicator(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(5.dp)
                            .clip(CircleShape),
                        color = MaterialTheme.colorScheme.primary,
                        trackColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.18f),
                    )
                    Spacer(Modifier.height(10.dp))
                    Crossfade(
                        targetState = phase.orEmpty(),
                        animationSpec = tween(220),
                        label = "fase",
                    ) { current ->
                        Text(
                            text = current,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
}

/** One quiet metric inside the hero card: tiny label, readable value. */
@Composable
private fun HeroFact(label: String, value: String) {
    Column {
        Text(
            text = label.uppercase(),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
            maxLines = 1,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
