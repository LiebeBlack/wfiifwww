package com.example.wifiscanner.ui.screens

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.WifiFind
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.example.wifiscanner.R
import com.example.wifiscanner.model.DeviceKind
import com.example.wifiscanner.model.NetworkDevice
import com.example.wifiscanner.ui.DeviceSort
import com.example.wifiscanner.ui.ScanUiState
import com.example.wifiscanner.ui.displayName
import com.example.wifiscanner.ui.components.DeviceCard
import com.example.wifiscanner.ui.components.EmptyState
import com.example.wifiscanner.ui.components.SectionHeader
import com.example.wifiscanner.ui.components.iconForKind

/**
 * Devices section: every result, split into type sections when the setting is
 * on, with quick type filters and a sort menu. There is no search bar — the
 * search lives in its own screen, opened from the top bar.
 *
 * @param state       used only to explain an empty list while a scan runs.
 * @param groupByKind from Settings: section headers per inferred type.
 */
@Composable
fun DevicesScreen(
    state: ScanUiState,
    devices: List<NetworkDevice>,
    sort: DeviceSort,
    onSortChange: (DeviceSort) -> Unit,
    groupByKind: Boolean,
    onOpenDevice: (NetworkDevice) -> Unit,
    onEditLocation: (NetworkDevice) -> Unit,
    onScan: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var kindFilter by rememberSaveable { mutableStateOf<String?>(null) }
    var sortMenuOpen by remember { mutableStateOf(false) }
    val errorMessage = (state as? ScanUiState.Error)?.message
    val scanning = state is ScanUiState.Loading

    val activeKind = kindFilter?.let { name -> DeviceKind.entries.firstOrNull { it.name == name } }
    val kindsPresent = remember(devices) {
        DeviceKind.entries.filter { kind -> devices.any { it.kind == kind } }
    }
    // A filter for a type that no longer exists would leave the list looking
    // empty for no reason: drop it as soon as the type disappears.
    LaunchedEffect(kindsPresent) {
        if (activeKind != null && activeKind !in kindsPresent) kindFilter = null
    }
    val filtered = remember(devices, sort, kindFilter) {
        sort.apply(if (activeKind == null) devices else devices.filter { it.kind == activeKind })
    }
    val groups = remember(filtered, groupByKind) {
        if (!groupByKind) {
            emptyList<Pair<DeviceKind, List<NetworkDevice>>>()
        } else {
            DeviceKind.entries.mapNotNull { kind ->
                filtered.filter { it.kind == kind }
                    .takeIf { it.isNotEmpty() }
                    ?.let { kind to it }
            }
        }
    }

    Column(modifier = modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 8.dp, top = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = pluralStringResource(R.plurals.devices_count, filtered.size, filtered.size),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
            )
            Box {
                TextButton(onClick = { sortMenuOpen = true }) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.Sort,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = stringResource(sort.labelRes),
                        style = MaterialTheme.typography.labelMedium,
                    )
                }
                DropdownMenu(
                    expanded = sortMenuOpen,
                    onDismissRequest = { sortMenuOpen = false },
                ) {
                    DeviceSort.entries.forEach { option ->
                        DropdownMenuItem(
                            text = { Text(stringResource(option.labelRes)) },
                            onClick = {
                                onSortChange(option)
                                sortMenuOpen = false
                            },
                        )
                    }
                }
            }
        }

        if (scanning) {
            LinearProgressIndicator(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp)
                    .height(3.dp)
                    .clip(CircleShape),
                color = MaterialTheme.colorScheme.primary,
                trackColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.15f),
            )
        }

        if (kindsPresent.size > 1) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FilterChip(
                    selected = activeKind == null,
                    onClick = { kindFilter = null },
                    label = { Text(stringResource(R.string.devices_filter_all)) },
                )
                kindsPresent.forEach { kind ->
                    FilterChip(
                        selected = activeKind == kind,
                        onClick = {
                            kindFilter = if (activeKind == kind) null else kind.name
                        },
                        label = { Text(kind.displayName()) },
                    )
                }
            }
            Spacer(Modifier.height(6.dp))
        }

        when {
            devices.isEmpty() -> EmptyState(
                icon = if (errorMessage != null) Icons.Filled.ErrorOutline else Icons.Filled.WifiFind,
                title = when {
                    errorMessage != null -> stringResource(R.string.scan_failed_title)
                    scanning -> stringResource(R.string.scanning)
                    else -> stringResource(R.string.devices_empty_title)
                },
                message = errorMessage
                    ?: (state as? ScanUiState.Loading)?.phase
                    ?: stringResource(R.string.no_devices),
                modifier = Modifier.weight(1f),
                actionLabel = if (scanning) null else stringResource(
                    if (errorMessage != null) R.string.retry else R.string.scan
                ),
                onAction = if (scanning) null else onScan,
                isError = errorMessage != null,
            )

            filtered.isEmpty() -> EmptyState(
                icon = Icons.Filled.Search,
                title = stringResource(R.string.devices_no_filter_title),
                message = stringResource(
                    R.string.devices_no_filter_match,
                    activeKind?.displayName() ?: stringResource(R.string.devices_filter_all),
                ),
                modifier = Modifier.weight(1f),
            )

            else -> LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                if (groupByKind) {
                    groups.forEach { (kind, groupDevices) ->
                        item(key = "header-${kind.name}") {
                            SectionHeader(
                                title = kind.displayName(),
                                icon = iconForKind(kind),
                                trailing = groupDevices.size.toString(),
                                modifier = Modifier.padding(top = 8.dp),
                            )
                        }
                        items(groupDevices, key = { it.ip }) { device ->
                            DeviceCard(
                                device = device,
                                onClick = { onOpenDevice(device) },
                                onEditLocation = { onEditLocation(device) },
                            )
                        }
                    }
                } else {
                    items(filtered, key = { it.ip }) { device ->
                        DeviceCard(
                            device = device,
                            onClick = { onOpenDevice(device) },
                            onEditLocation = { onEditLocation(device) },
                        )
                    }
                }
            }
        }
    }
}
