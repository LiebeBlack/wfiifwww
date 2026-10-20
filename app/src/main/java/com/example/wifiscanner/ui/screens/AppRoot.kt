package com.example.wifiscanner.ui.screens

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.DevicesOther
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.wifiscanner.R
import com.example.wifiscanner.model.NetworkDevice
import com.example.wifiscanner.ui.AppSettings
import com.example.wifiscanner.ui.ScanExport
import com.example.wifiscanner.ui.ScanUiState
import com.example.wifiscanner.ui.ScanViewModel
import com.example.wifiscanner.ui.components.DeviceDetailSheet
import com.example.wifiscanner.ui.components.LocationEditDialog

/**
 * Top-level sections of the app. Each one is a full screen; the bottom bar is
 * the only navigator, so the app never nests tabs inside tabs.
 */
enum class AppSection(@StringRes val titleRes: Int, val icon: ImageVector) {
    Overview(R.string.section_overview, Icons.Filled.Dashboard),
    Devices(R.string.section_devices, Icons.Filled.DevicesOther),
    Network(R.string.section_network, Icons.Filled.Wifi),
    Settings(R.string.section_settings, Icons.Filled.Settings),
}

/**
 * App shell: top bar (section title, export, search), bottom navigation and
 * the shared dialogs.
 *
 * Search is a screen of its own rather than a bar on every screen; tapping the
 * magnifier switches to it, the back arrow or system back returns to the same
 * section. The scan state lives in the ViewModel, so navigating between
 * sections never interrupts or restarts a scan.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppRoot(
    viewModel: ScanViewModel,
    settings: AppSettings,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val stats by viewModel.stats.collectAsStateWithLifecycle()
    val uiSettings by settings.state.collectAsStateWithLifecycle()
    val context = LocalContext.current

    var section by rememberSaveable { mutableStateOf(AppSection.Overview) }
    var searchOpen by rememberSaveable { mutableStateOf(false) }
    var detailDevice by remember { mutableStateOf<NetworkDevice?>(null) }
    var editingDevice by remember { mutableStateOf<NetworkDevice?>(null) }

    val devices = when (val current = state) {
        is ScanUiState.Loading -> current.devices
        is ScanUiState.Success -> current.devices
        else -> emptyList<NetworkDevice>()
    }

    // First composition means the permission gate just let us through: the
    // ViewModel may have read the link *before* the grant, so refresh now that
    // the SSID is actually readable. (ON_RESUME does not replay for a screen
    // that appears while the app is already resumed.)
    LaunchedEffect(Unit) { viewModel.refreshStats() }

    // Re-read the link whenever the network section becomes visible.
    LaunchedEffect(section) {
        if (section == AppSection.Network) viewModel.refreshStats()
    }

    // Coming back from the system settings (or from a locked screen) must
    // refresh the link: the user may have granted the permission, enabled
    // Wi-Fi or joined another network while the app was in the background.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        viewModel.refreshStats()
    }

    if (searchOpen) {
        SearchScreen(
            devices = devices,
            onClose = { searchOpen = false },
            onOpenDevice = { detailDevice = it },
            onEditLocation = { editingDevice = it },
        )
    } else {
        Scaffold(
            containerColor = MaterialTheme.colorScheme.background,
            topBar = {
                TopAppBar(
                    title = {
                        Column {
                            Text(
                                text = stringResource(section.titleRes),
                                style = MaterialTheme.typography.titleLarge,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                            if (section == AppSection.Overview) {
                                // The live network name is the most useful subtitle
                                // here; the app name is only the fallback.
                                Text(
                                    text = stats?.ssid
                                        ?: if (stats?.ssidHidden == true) {
                                            stringResource(R.string.dashboard_ssid_hidden)
                                        } else {
                                            stringResource(R.string.app_name)
                                        },
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    },
                    actions = {
                        if (devices.isNotEmpty()) {
                            IconButton(onClick = { ScanExport.share(context, devices) }) {
                                Icon(
                                    imageVector = Icons.Filled.Share,
                                    contentDescription = stringResource(R.string.export),
                                )
                            }
                        }
                        IconButton(onClick = { searchOpen = true }) {
                            Icon(
                                imageVector = Icons.Filled.Search,
                                contentDescription = stringResource(R.string.search_open),
                            )
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = Color.Transparent,
                        scrolledContainerColor = MaterialTheme.colorScheme.surface,
                    ),
                )
            },
            bottomBar = {
                NavigationBar(
                    containerColor = MaterialTheme.colorScheme.surface,
                    tonalElevation = 0.dp,
                ) {
                    AppSection.entries.forEach { item ->
                        NavigationBarItem(
                            selected = section == item,
                            onClick = { section = item },
                            icon = {
                                Icon(
                                    imageVector = item.icon,
                                    contentDescription = null,
                                )
                            },
                            label = { Text(stringResource(item.titleRes)) },
                            colors = NavigationBarItemDefaults.colors(
                                indicatorColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.16f),
                                selectedIconColor = MaterialTheme.colorScheme.primary,
                                selectedTextColor = MaterialTheme.colorScheme.primary,
                            ),
                        )
                    }
                }
            },
        ) { innerPadding ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
            ) {
                when (section) {
                    AppSection.Overview -> DashboardScreen(
                        state = state,
                        stats = stats,
                        devices = devices,
                        onScan = viewModel::startScan,
                        onStop = viewModel::cancelScan,
                        onOpenDevices = { section = AppSection.Devices },
                        onOpenNetwork = { section = AppSection.Network },
                        onOpenDevice = { detailDevice = it },
                    )

                    AppSection.Devices -> DevicesScreen(
                        state = state,
                        devices = devices,
                        sort = uiSettings.sort,
                        onSortChange = settings::setSort,
                        groupByKind = uiSettings.groupByKind,
                        onOpenDevice = { detailDevice = it },
                        onEditLocation = { editingDevice = it },
                        onScan = viewModel::startScan,
                    )

                    AppSection.Network -> NetworkScreen(
                        stats = stats,
                        devices = devices,
                        onRefresh = viewModel::refreshStats,
                    )

                    AppSection.Settings -> SettingsScreen(
                        settings = settings,
                        state = uiSettings,
                        deviceCount = devices.size,
                        onShare = { ScanExport.share(context, devices) },
                    )
                }
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

    // ---- Location label editor -----------------------------------------
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
