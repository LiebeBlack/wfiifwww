package com.example.wifiscanner.ui.screens

import android.os.Build
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Smartphone
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.example.wifiscanner.R
import com.example.wifiscanner.net.NetworkStatistics
import com.example.wifiscanner.ui.AppSettings
import com.example.wifiscanner.ui.DeviceSort
import com.example.wifiscanner.ui.SystemSettings
import com.example.wifiscanner.ui.UiSettings
import com.example.wifiscanner.ui.components.InfoRow
import com.example.wifiscanner.ui.components.SectionHeader
import com.example.wifiscanner.ui.components.hasScannerPermissions
import com.example.wifiscanner.ui.components.isLocationServicesEnabled
import com.example.wifiscanner.ui.theme.ThemeMode

/**
 * Settings section: appearance (theme + system accents), list behaviour
 * (grouping + default sort), export, and a short about block.
 *
 * Changes are written through [AppSettings] and take effect immediately —
 * switching to dark mode re-themes the whole app without a restart.
 */
@Composable
fun SettingsScreen(
    settings: AppSettings,
    state: UiSettings,
    deviceCount: Int,
    onShare: () -> Unit,
    stats: NetworkStatistics.WifiStatistics? = null,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    // Re-read on resume: the user may come back from the system settings with
    // a freshly granted permission, and the state rows must say so.
    var permissionGranted by remember { mutableStateOf(hasScannerPermissions(context)) }
    var locationEnabled by remember { mutableStateOf(isLocationServicesEnabled(context)) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        permissionGranted = hasScannerPermissions(context)
        locationEnabled = isLocationServicesEnabled(context)
    }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item { SectionHeader(title = stringResource(R.string.settings_appearance), icon = Icons.Filled.Palette) }

        item {
            SettingsCard {
                ThemeOption(
                    title = stringResource(R.string.settings_theme_system),
                    description = stringResource(R.string.settings_theme_system_desc),
                    selected = state.themeMode == ThemeMode.System,
                    onSelect = { settings.setThemeMode(ThemeMode.System) },
                )
                ThemeOption(
                    title = stringResource(R.string.settings_theme_light),
                    description = stringResource(R.string.settings_theme_light_desc),
                    selected = state.themeMode == ThemeMode.Light,
                    onSelect = { settings.setThemeMode(ThemeMode.Light) },
                )
                ThemeOption(
                    title = stringResource(R.string.settings_theme_dark),
                    description = stringResource(R.string.settings_theme_dark_desc),
                    selected = state.themeMode == ThemeMode.Dark,
                    onSelect = { settings.setThemeMode(ThemeMode.Dark) },
                )
            }
        }

        item {
            SettingsCard {
                SettingSwitch(
                    title = stringResource(R.string.settings_dynamic),
                    description = stringResource(R.string.settings_dynamic_desc),
                    checked = state.dynamicColor,
                    onCheckedChange = settings::setDynamicColor,
                    enabled = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S,
                )
            }
        }

        item { SectionHeader(title = stringResource(R.string.settings_list), icon = Icons.Filled.Tune) }

        item {
            SettingsCard {
                SettingSwitch(
                    title = stringResource(R.string.settings_group_by_kind),
                    description = stringResource(R.string.settings_group_by_kind_desc),
                    checked = state.groupByKind,
                    onCheckedChange = settings::setGroupByKind,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = stringResource(R.string.settings_default_sort),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.height(8.dp))
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                ) {
                    DeviceSort.entries.forEach { option ->
                        FilterChip(
                            selected = state.sort == option,
                            onClick = { settings.setSort(option) },
                            label = { Text(stringResource(option.labelRes)) },
                        )
                    }
                }
            }
        }

        item { SectionHeader(title = stringResource(R.string.settings_permissions), icon = Icons.Filled.Lock) }

        item {
            SettingsCard {
                PermissionRow(
                    title = stringResource(R.string.settings_permission_location),
                    granted = permissionGranted,
                    onOpen = { SystemSettings.openAppSettings(context) },
                )
                Spacer(Modifier.height(10.dp))
                PermissionRow(
                    title = stringResource(R.string.settings_location_services),
                    granted = locationEnabled,
                    onOpen = { SystemSettings.openLocationSettings(context) },
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = stringResource(R.string.settings_permissions_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        item { SectionHeader(title = stringResource(R.string.settings_device), icon = Icons.Filled.Smartphone) }

        item {
            SettingsCard {
                InfoRow(
                    label = stringResource(R.string.settings_device_model),
                    value = listOf(Build.MANUFACTURER, Build.MODEL)
                        .filter { part ->
                            part.isNotBlank() && !part.equals("unknown", ignoreCase = true)
                        }
                        .joinToString(" ")
                        .ifBlank { null },
                )
                InfoRow(
                    label = stringResource(R.string.settings_android),
                    value = "Android ${Build.VERSION.RELEASE} · API ${Build.VERSION.SDK_INT}",
                )
                InfoRow(
                    label = stringResource(R.string.settings_interface),
                    value = stats?.interfaceName,
                    monospace = true,
                )
                InfoRow(
                    label = stringResource(R.string.settings_local_ip),
                    value = listOfNotNull(
                        stats?.ipAddress,
                        stats?.prefixLength?.let { "/$it" },
                    ).joinToString("").ifBlank { null },
                    monospace = true,
                )
                InfoRow(
                    label = stringResource(R.string.settings_subnet),
                    value = stats?.let { snapshot ->
                        snapshot.networkAddress?.let { address ->
                            address + (snapshot.prefixLength?.let { "/$it" } ?: "")
                        }
                    },
                    monospace = true,
                )
                InfoRow(
                    label = stringResource(R.string.settings_gateway),
                    value = stats?.gateway,
                    monospace = true,
                )
                InfoRow(
                    label = stringResource(R.string.settings_current_network),
                    value = stats?.ssid,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = stringResource(R.string.settings_device_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        item { SectionHeader(title = stringResource(R.string.settings_data), icon = Icons.Filled.Share) }

        item {
            SettingsCard {
                Text(
                    text = stringResource(R.string.settings_share_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(12.dp))
                Button(onClick = onShare, enabled = deviceCount > 0) {
                    Icon(
                        imageVector = Icons.Filled.Share,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.settings_share))
                }
            }
        }

        item { SectionHeader(title = stringResource(R.string.settings_about), icon = Icons.Filled.Info) }

        item {
            SettingsCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Filled.Wifi,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(22.dp),
                    )
                    Spacer(Modifier.width(10.dp))
                    Column {
                        Text(
                            text = stringResource(R.string.app_name),
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Text(
                            text = stringResource(R.string.settings_version, stringResource(R.string.app_version)),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Spacer(Modifier.height(10.dp))
                Text(
                    text = stringResource(R.string.settings_about_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = stringResource(R.string.settings_about_how),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = stringResource(R.string.settings_about_features),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = stringResource(R.string.settings_about_tech),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(12.dp))
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Spacer(Modifier.height(10.dp))
                Text(
                    text = stringResource(R.string.settings_privacy),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** Card container shared by every settings block. */
@Composable
private fun SettingsCard(content: @Composable () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) {
            content()
        }
    }
}

/** One radio row: theme option with a short explanation. */
@Composable
private fun ThemeOption(
    title: String,
    description: String,
    selected: Boolean,
    onSelect: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.small)
            .clickable(onClick = onSelect)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = onSelect)
        Spacer(Modifier.width(4.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** Permission state row: what it is, whether it is on, and how to change it. */
@Composable
private fun PermissionRow(
    title: String,
    granted: Boolean,
    onOpen: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = stringResource(
                    if (granted) R.string.settings_permission_granted
                    else R.string.settings_permission_denied
                ),
                style = MaterialTheme.typography.bodySmall,
                color = if (granted) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.error
                },
            )
        }
        TextButton(onClick = onOpen) {
            Text(stringResource(R.string.settings_open))
        }
    }
}

/** Label + explanation on the left, switch on the right. */
@Composable
private fun SettingSwitch(
    title: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    enabled: Boolean = true,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.width(12.dp))
        Switch(checked = checked, onCheckedChange = onCheckedChange, enabled = enabled)
    }
}
