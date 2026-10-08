package com.example.wifiscanner.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelProvider.Companion.APPLICATION_KEY
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.example.wifiscanner.data.AppDatabase
import com.example.wifiscanner.data.DeviceLabelRepository
import com.example.wifiscanner.model.NetworkDevice
import com.example.wifiscanner.net.LocalNetworkInfo
import com.example.wifiscanner.net.NetworkScanner
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * ViewModel for the main scan screen (MVVM presentation layer).
 *
 * Responsibilities:
 *  - Own the [NetworkScanner] and expose its progressive emissions through
 *    a sealed [ScanUiState] (Idle / Loading / Success / Error).
 *  - Merge live scan results with persisted location labels so a device's
 *    label appears/updates instantly after the user saves one, without a
 *    rescan.
 *  - Survive configuration changes (rotation) — the scan keeps running in
 *    `viewModelScope` and the UI re-collects the StateFlow.
 */
class ScanViewModel(application: Application) : AndroidViewModel(application) {

    private val scanner = NetworkScanner(application)
    private val labelRepository = DeviceLabelRepository(
        AppDatabase.get(application).deviceLabelDao()
    )

    /** Raw scan state; only mutated from [startScan]'s coroutine. */
    private val _scanState = MutableStateFlow<ScanUiState>(ScanUiState.Idle)

    /** Reactive MAC -> label map from Room. */
    private val labelMap = labelRepository.observeLabelMap()

    /** Last device list from the most recent emission (for live merge). */
    private val scanDevices = MutableStateFlow<List<NetworkDevice>>(emptyList())

    private var scanJob: Job? = null

    /**
     * The single state stream the UI collects: scan state merged with the
     * latest persisted labels. Saving a label recomposes the list instantly.
     */
    val uiState: StateFlow<ScanUiState> = combine(_scanState, labelMap) { state, labels ->
        when (state) {
            is ScanUiState.Idle -> state
            is ScanUiState.Loading -> state.copy(devices = state.devices.withLabels(labels))
            is ScanUiState.Success -> ScanUiState.Success(state.devices.withLabels(labels))
            is ScanUiState.Error -> state
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ScanUiState.Idle)

    /**
     * Starts a new scan, cancelling any in-flight one. Partial results are
     * pushed into [ScanUiState.Loading] as the flow emits; the final list
     * becomes [ScanUiState.Success].
     */
    fun startScan() {
        scanJob?.cancel()
        scanDevices.value = emptyList()
        scanner.reset() // stale mDNS names from another network would mislabel
        scanJob = viewModelScope.launch {
            _scanState.value = ScanUiState.Loading(phase = "Detecting network…")
            try {
                scanner.scan().collect { devices ->
                    scanDevices.value = devices
                    _scanState.value = ScanUiState.Loading(
                        phase = "Scanning subnet…",
                        devices = devices,
                    )
                }
                _scanState.value = ScanUiState.Success(scanDevices.value)
            } catch (e: CancellationException) {
                throw e // structured concurrency: never swallow cancellation
            } catch (e: Exception) {
                _scanState.value = ScanUiState.Error(
                    when ((e as? NetworkScanner.ScanException)?.error) {
                        NetworkScanner.ScanError.WifiOff ->
                            "Wi-Fi is turned off. Enable Wi-Fi and try again."
                        NetworkScanner.ScanError.NotOnWifi ->
                            if (!LocalNetworkInfo.isOnWifi(getApplication()))
                                "Wi-Fi is turned off. Enable Wi-Fi and try again."
                            else
                                "Not connected to Wi-Fi. Connect to a Wi-Fi network and try again."
                        else -> e.message ?: "Scan failed unexpectedly."
                    }
                )
            }
        }
    }

    /** Cancels a running scan (UI "Stop" action); results keep what we have. */
    fun cancelScan() {
        scanJob?.cancel()
        scanJob = null
        val current = _scanState.value
        if (current is ScanUiState.Loading) {
            _scanState.value = if (current.devices.isEmpty()) {
                ScanUiState.Idle
            } else {
                ScanUiState.Success(current.devices)
            }
        }
    }

    /**
     * Persists a location label for [mac]. Blank text deletes the label.
     * The reactive [labelMap] pushes the change back into [uiState].
     */
    fun saveLocation(mac: String, label: String) {
        viewModelScope.launch { labelRepository.saveLabel(mac, label) }
    }

    /** Applies the label map onto a scan result list (MAC-keyed merge). */
    private fun List<NetworkDevice>.withLabels(labels: Map<String, String>): List<NetworkDevice> =
        if (labels.isEmpty()) this
        else map { device ->
            val mac = device.mac ?: return@map device
            device.copy(location = labels[mac]?.takeIf { it.isNotBlank() } ?: device.location)
        }

    companion object {
        /** Standard ViewModel factory for [ScanViewModel] (needs Application). */
        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = this[APPLICATION_KEY] as Application
                ScanViewModel(app)
            }
        }
    }
}
