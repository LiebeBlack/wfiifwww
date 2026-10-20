package com.example.wifiscanner.ui.screens

import androidx.annotation.StringRes
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.DevicesOther
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
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
enum class AppSection(
    @StringRes val titleRes: Int,
    @StringRes val shortTitleRes: Int,
    val icon: ImageVector
) {
    Overview(R.string.section_overview, R.string.section_overview_short, Icons.Filled.Dashboard),
    Devices(R.string.section_devices, R.string.section_devices_short, Icons.Filled.DevicesOther),
    Network(R.string.section_network, R.string.section_network_short, Icons.Filled.Wifi),
    Settings(R.string.section_settings, R.string.section_settings_short, Icons.Filled.Settings),
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
    // Keeps each section's scroll position across tab switches and while the
    // search screen temporarily replaces the shell. Without it, every tab
    // change re-created the LazyColumn state and jumped back to the top.
    val sectionStates = rememberSaveableStateHolder()
    var detailDevice by remember { mutableStateOf<NetworkDevice?>(null) }
    var editingDevice by remember { mutableStateOf<NetworkDevice?>(null) }
    var editingNicknameDevice by remember { mutableStateOf<NetworkDevice?>(null) }

    // The bar doubles as a status line: the live scan phase while scanning and
    // the network name on the overview. No extra screen is needed for it.
    val loadingPhase = (state as? ScanUiState.Loading)?.phase
    // While the first snapshot is still being read, stats is null: showing the
    // app name avoids claiming "no Wi-Fi" for a frame.
    val snapshot = stats
    val ssidUnavailable = snapshot != null && snapshot.ssidHidden &&
        (!snapshot.locationPermissionGranted || !snapshot.locationServicesEnabled)
    val topSubtitle = loadingPhase ?: if (section == AppSection.Overview && snapshot != null) {
        snapshot.ssid ?: when {
            ssidUnavailable -> stringResource(R.string.dashboard_ssid_unavailable)
            snapshot.ssidHidden -> stringResource(R.string.dashboard_ssid_hidden)
            else -> stringResource(R.string.app_name)
        }
    } else {
        null
    }

    val devices = when (val current = state) {
        is ScanUiState.Loading -> current.devices
        is ScanUiState.Success -> current.devices
        else -> emptyList<NetworkDevice>()
    }

    // Re-read the link whenever the network section becomes visible.
    LaunchedEffect(section) {
        if (section == AppSection.Network) viewModel.refreshStats()
    }

    // The connectivity callbacks are registered only while this screen is
    // started. ON_START/ON_STOP are the exact pair for a visible UI; keeping
    // them registered while the app is backgrounded would hold a binder and
    // wake the process for every network change (a real leak). ON_START is
    // replayed when the observer is added to an already-resumed lifecycle, so
    // this also covers the moment the permission gate lets us through.
    LifecycleEventEffect(Lifecycle.Event.ON_START) { viewModel.startMonitoring() }
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { viewModel.stopMonitoring() }

    // Coming back from the system settings (or from a locked screen) must
    // refresh the link: the user may have granted the permission, enabled
    // Wi-Fi or joined another network while the app was paused.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        viewModel.refreshStats()
    }

    if (searchOpen) {
        SearchScreen(
            devices = devices,
            onClose = { searchOpen = false },
            onOpenDevice = { detailDevice = it },
            onEditLocation = { editingDevice = it },
            onEditNickname = { editingNicknameDevice = it },
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
                            if (topSubtitle != null) {
                                Text(
                                    text = topSubtitle,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    },
                    actions = {
                        if (state is ScanUiState.Loading) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.primary,
                            )
                            Spacer(Modifier.width(6.dp))
                        }
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
                Column {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
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
                                label = { Text(stringResource(item.shortTitleRes)) },
                                colors = NavigationBarItemDefaults.colors(
                                    indicatorColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.16f),
                                    selectedIconColor = MaterialTheme.colorScheme.primary,
                                    selectedTextColor = MaterialTheme.colorScheme.primary,
                                ),
                            )
                        }
                    }
                }
            },
        ) { innerPadding ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
            ) {
                Crossfade(
                    targetState = section,
                    animationSpec = tween(durationMillis = 220),
                    label = "section",
                ) { current ->
                    sectionStates.SaveableStateProvider(current.name) {
                        when (current) {
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
                                stats = stats,
                                deviceCount = devices.size,
                                onShare = { ScanExport.share(context, devices) },
                            )
                        }
                    }
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
            onEditNickname = {
                editingNicknameDevice = device
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
                viewModel.saveLocation(device.mac, device.ip, newLabel)
                editingDevice = null
            },
            onDismiss = { editingDevice = null },
        )
    }

    // ---- Nickname editor -----------------------------------------------
    editingNicknameDevice?.let { device ->
        NicknameEditDialog(
            mac = device.mac,
            ip = device.ip,
            initialNickname = device.nickname,
            onSave = { newNickname ->
                viewModel.saveNickname(device.mac, device.ip, newNickname)
                editingNicknameDevice = null
            },
            onDismiss = { editingNicknameDevice = null },
        )
    }
}
