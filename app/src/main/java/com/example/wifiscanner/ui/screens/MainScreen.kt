package com.example.wifiscanner.ui.screens

import androidx.annotation.StringRes
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Sort
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.WifiFind
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.wifiscanner.R
import com.example.wifiscanner.model.DiscoverySource
import com.example.wifiscanner.model.NetworkDevice
import com.example.wifiscanner.ui.ScanExport
import com.example.wifiscanner.ui.ScanUiState
import com.example.wifiscanner.ui.ScanViewModel
import com.example.wifiscanner.ui.components.DeviceCard
import com.example.wifiscanner.ui.components.DeviceDetailSheet
import com.example.wifiscanner.ui.components.LocationEditDialog
import com.example.wifiscanner.ui.components.ScanStatsCard
import com.example.wifiscanner.ui.ipv4SortKey
import com.example.wifiscanner.ui.matchesQuery

/**
 * Main screen: scan controls, a degradable-discovery banner, search/sort over
 * the results, and a reactive list of device cards.
 *
 * Renders exactly one branch of [ScanUiState] and keeps the previous results
 * visible while a new scan runs (Loading carries partial devices). Tapping a
 * card opens the detail sheet; the top-bar action exports the whole list.
 *
 * @param viewModel shared ViewModel obtained via the Factory.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(viewModel: ScanViewModel) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val stats by viewModel.stats.collectAsStateWithLifecycle()
    val context = LocalContext.current

    // Which device's location dialog / detail sheet is open (null == closed).
    var editingDevice by remember { mutableStateOf<NetworkDevice?>(null) }
    var detailDevice by remember { mutableStateOf<NetworkDevice?>(null) }
    var query by remember { mutableStateOf("") }
    var sort by remember { mutableStateOf(DeviceSort.Ip) }

    val devices = when (val s = state) {
        is ScanUiState.Loading -> s.devices
        is ScanUiState.Success -> s.devices
        else -> emptyList()
    }
    val visibleDevices = remember(devices, query, sort) {
        sort.apply(devices.filter { device -> device.matchesQuery(query) })
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.app_name)) },
                actions = {
                    if (devices.isNotEmpty()) {
                        IconButton(onClick = { ScanExport.share(context, devices) }) {
                            Icon(
                                imageVector = Icons.Filled.Share,
                                contentDescription = stringResource(R.string.export),
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                ),
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            // ---- Scan controls -------------------------------------------
            ScanControls(
                state = state,
                onScan = viewModel::startScan,
                onStop = viewModel::cancelScan,
            )

            // ---- Degraded-discovery banner --------------------------------
            // Shown instead of an error: discovery keeps working, just with
            // fewer methods available (e.g. the ARP table is hidden).
            val note = when (val s = state) {
                is ScanUiState.Loading -> s.note
                is ScanUiState.Success -> s.note
                else -> null
            }
            if (note != null) {
                ScanNote(note)
                Spacer(Modifier.height(8.dp))
            }

            // ---- Network statistics (SSID, band, IP config, timing) -------
            ScanStatsCard(
                stats = stats,
                deviceCount = devices.size,
                elapsedMillis = (state as? ScanUiState.Success)?.elapsedMillis,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
            Spacer(Modifier.height(8.dp))

            // ---- Search + sort, once there is something to sift -----------
            if (devices.isNotEmpty()) {
                SearchAndSortBar(
                    query = query,
                    onQueryChange = { query = it },
                    sort = sort,
                    onSortChange = { sort = it },
                )
                ScanSummary(devices)
            }

            // ---- Content by state ----------------------------------------
            when (val s = state) {
                is ScanUiState.Idle -> EmptyState(
                    message = stringResource(R.string.no_devices),
                )

                // While the first results stream in, the empty list explains
                // what the scanner is doing instead of inviting a second scan.
                is ScanUiState.Loading -> DeviceList(
                    devices = visibleDevices,
                    filtered = query.isNotBlank(),
                    emptyMessage = s.phase,
                    onOpenDetails = { detailDevice = it },
                    onEditLocation = { editingDevice = it },
                )

                is ScanUiState.Success -> DeviceList(
                    devices = visibleDevices,
                    filtered = query.isNotBlank(),
                    emptyMessage = stringResource(R.string.no_devices),
                    onOpenDetails = { detailDevice = it },
                    onEditLocation = { editingDevice = it },
                )

                is ScanUiState.Error -> EmptyState(
                    message = s.message,
                    isError = true,
                )
            }
        }
    }

    // ---- Device detail sheet -------------------------------------------
    detailDevice?.let { device ->
        DeviceDetailSheet(
            device = device,
            onDismiss = { detailDevice = null },
            onEditLocation = {
                editingDevice = device
                detailDevice = null
            },
        )
    }

    // ---- Location edit dialog ------------------------------------------
    editingDevice?.let { device ->
        LocationEditDialog(
            mac = device.mac,
            ip = device.ip,
            initialLabel = device.location,
            onSave = { newLabel ->
                device.mac?.let { viewModel.saveLocation(it, newLabel) }
                editingDevice = null
            },
            onDismiss = { editingDevice = null },
        )
    }
}

/** Sort orders offered in the toolbar of the results list. */
private enum class DeviceSort(@StringRes val labelRes: Int) {
    Ip(R.string.sort_ip),
    Name(R.string.sort_name),
    Kind(R.string.sort_kind),
    Vendor(R.string.sort_vendor);

    /** Applies this sort order to [devices]. */
    fun apply(devices: List<NetworkDevice>): List<NetworkDevice> = when (this) {
        Ip -> devices.sortedBy { it.ip.ipv4SortKey() }
        Name -> devices.sortedBy { it.displayName.lowercase() }
        Kind -> devices.sortedWith(compareBy({ it.kind.ordinal }, { it.displayName.lowercase() }))
        // Unknown brands sort last: "~" is above every letter in ASCII and
        // the keys are lower-cased so case does not scatter the order.
        Vendor -> devices.sortedWith(
            compareBy(
                { (it.vendor ?: it.models.firstOrNull() ?: "~").lowercase() },
                { it.displayName.lowercase() },
            )
        )
    }
}

/**
 * Header row with the primary Scan/Stop button, progress indicator and the
 * device count while scanning.
 */
@Composable
private fun ScanControls(
    state: ScanUiState,
    onScan: () -> Unit,
    onStop: () -> Unit,
) {
    val isScanning = state is ScanUiState.Loading

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        if (isScanning) {
            FilledTonalButton(onClick = onStop) {
                Icon(Icons.Filled.Stop, contentDescription = stringResource(R.string.stop))
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.stop))
            }
            CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
            Text(
                text = (state as ScanUiState.Loading).let {
                    if (it.devices.isEmpty()) it.phase
                    else stringResource(R.string.devices_found, it.devices.size)
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            Button(onClick = onScan) {
                Icon(Icons.Filled.PlayArrow, contentDescription = stringResource(R.string.scan))
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.scan))
            }
            (state as? ScanUiState.Success)?.let {
                Text(
                    text = stringResource(R.string.devices_found, it.devices.size),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** Search field plus the sort menu for the results list. */
@Composable
private fun SearchAndSortBar(
    query: String,
    onQueryChange: (String) -> Unit,
    sort: DeviceSort,
    onSortChange: (DeviceSort) -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        OutlinedTextField(
            value = query,
            onValueChange = onQueryChange,
            modifier = Modifier.weight(1f),
            singleLine = true,
            placeholder = { Text(stringResource(R.string.search_hint)) },
            leadingIcon = {
                Icon(
                    imageVector = Icons.Filled.Search,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
            },
            trailingIcon = {
                if (query.isNotEmpty()) {
                    IconButton(onClick = { onQueryChange("") }) {
                        Icon(
                            imageVector = Icons.Filled.Close,
                            contentDescription = stringResource(R.string.clear_search),
                        )
                    }
                }
            },
        )

        Box {
            TextButton(onClick = { menuOpen = true }) {
                Icon(
                    imageVector = Icons.Filled.Sort,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(4.dp))
                Text(
                    text = stringResource(sort.labelRes),
                    style = MaterialTheme.typography.labelMedium,
                )
            }
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                DeviceSort.entries.forEach { option ->
                    DropdownMenuItem(
                        text = { Text(stringResource(option.labelRes)) },
                        onClick = {
                            onSortChange(option)
                            menuOpen = false
                        },
                    )
                }
            }
        }
    }
}

/**
 * One-line summary of how the devices were found, e.g.
 * `"12 devices · ARP 3 · ICMP 5 · SSDP 4"` — the fastest way to notice a
 * scan that ran in a degraded mode.
 */
@Composable
private fun ScanSummary(devices: List<NetworkDevice>) {
    val counts = remember(devices) {
        devices.flatMap { it.sources }.groupingBy { it }.eachCount()
    }
    val summary = remember(counts) {
        DiscoverySource.ordered(counts.keys)
            .filter { it != DiscoverySource.Self }
            .joinToString(" · ") { source -> "${source.label} ${counts[source] ?: 0}" }
    }
    if (summary.isEmpty()) return

    Text(
        text = summary,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
    )
}

/**
 * Advisory banner for scans that ran in a degraded mode (a blocked discovery
 * method). It is deliberately *not* an error state: results are still shown.
 */
@Composable
private fun ScanNote(note: String) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.tertiaryContainer,
        ),
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Filled.Info,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onTertiaryContainer,
                modifier = Modifier.size(20.dp),
            )
            Spacer(Modifier.width(10.dp))
            Column {
                Text(
                    text = stringResource(R.string.limited_detection),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onTertiaryContainer,
                )
                Text(
                    text = note,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onTertiaryContainer,
                )
            }
        }
    }
}

/**
 * Reactive LazyColumn of device cards.
 *
 * @param filtered     true when a search is active, so an empty list explains
 *                     "nothing matches" instead of "no devices found".
 * @param emptyMessage what to say when the list is genuinely empty (the
 *                     scanner's current phase while a scan is running).
 */
@Composable
private fun DeviceList(
    devices: List<NetworkDevice>,
    filtered: Boolean,
    emptyMessage: String,
    onOpenDetails: (NetworkDevice) -> Unit,
    onEditLocation: (NetworkDevice) -> Unit,
) {
    if (devices.isEmpty()) {
        // A search that matches nothing is worth explaining differently from a
        // scan that simply has found nothing yet.
        EmptyState(message = if (filtered) stringResource(R.string.no_matches) else emptyMessage)
        return
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        items(devices, key = { it.stableId }) { device ->
            DeviceCard(
                device = device,
                onClick = { onOpenDetails(device) },
                onEditLocation = { onEditLocation(device) },
            )
        }
    }
}

/** Centered placeholder for empty / error states. */
@Composable
private fun EmptyState(message: String, isError: Boolean = false) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            if (isError) {
                Icon(
                    imageVector = Icons.Filled.ErrorOutline,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(48.dp),
                )
                Spacer(Modifier.height(12.dp))
            } else {
                Icon(
                    imageVector = Icons.Filled.WifiFind,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(48.dp),
                )
                Spacer(Modifier.height(12.dp))
            }
            Text(
                text = message,
                style = MaterialTheme.typography.bodyLarge,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
