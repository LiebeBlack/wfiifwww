package com.example.wifiscanner.ui.screens

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
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.WifiFind
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.wifiscanner.R
import com.example.wifiscanner.model.NetworkDevice
import com.example.wifiscanner.ui.ScanUiState
import com.example.wifiscanner.ui.ScanViewModel
import com.example.wifiscanner.ui.components.DeviceCard
import com.example.wifiscanner.ui.components.LocationEditDialog
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue

/**
 * Main screen: Scan button + progress + reactive device list.
 *
 * Renders exactly one branch of [ScanUiState] and keeps the previous results
 * visible while a new scan runs (Loading carries partial devices).
 *
 * @param viewModel shared ViewModel obtained via the Factory.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(viewModel: ScanViewModel) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    // Which device's location dialog is open (null == closed).
    var editingDevice by remember { mutableStateOf<NetworkDevice?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.app_name)) },
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

            // ---- Content by state ----------------------------------------
            when (val s = state) {
                is ScanUiState.Idle -> EmptyState(
                    message = stringResource(R.string.no_devices),
                )

                is ScanUiState.Loading -> DeviceList(
                    devices = s.devices,
                    onEditLocation = { editingDevice = it },
                )

                is ScanUiState.Success -> DeviceList(
                    devices = s.devices,
                    onEditLocation = { editingDevice = it },
                )

                is ScanUiState.Error -> EmptyState(
                    message = s.message,
                    isError = true,
                )
            }
        }
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

/** Reactive LazyColumn of device cards. */
@Composable
private fun DeviceList(
    devices: List<NetworkDevice>,
    onEditLocation: (NetworkDevice) -> Unit,
) {
    if (devices.isEmpty()) {
        EmptyState(message = stringResource(R.string.no_devices))
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
