package com.example.wifiscanner.ui

import android.app.Application
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.example.wifiscanner.data.AppDatabase
import com.example.wifiscanner.data.DeviceLabelRepository
import com.example.wifiscanner.model.NetworkDevice
import com.example.wifiscanner.net.LocalNetworkInfo
import com.example.wifiscanner.net.NetworkScanner
import com.example.wifiscanner.net.NetworkStatistics
import com.example.wifiscanner.ui.components.hasScannerPermissions
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * ViewModel for the scan screen (MVVM presentation layer).
 *
 * Responsibilities:
 *  - Own the [NetworkScanner] and expose its progressive emissions through
 *    a sealed [ScanUiState] (Idle / Loading / Success / Error).
 *  - Merge live scan results with persisted location labels so a device's
 *    label appears/updates instantly after the user saves one, without a
 *    rescan.
 *  - Keep [stats] current: a single default-network callback refreshes the
 *    Wi-Fi snapshot when the link changes (connect, disconnect, VPN on/off,
 *    new SSID), debounced so a burst of capability updates does not spam the
 *    CPU. The callback is unregistered in [onCleared], so it cannot leak.
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
    private var statsJob: Job? = null

    /** Wi-Fi/LAN statistics for the header card (null until first read). */
    private val _stats = MutableStateFlow<NetworkStatistics.WifiStatistics?>(null)
    val stats: StateFlow<NetworkStatistics.WifiStatistics?> = _stats.asStateFlow()

    private val connectivityManager: ConnectivityManager? =
        application.getSystemService(ConnectivityManager::class.java)

    /**
     * Refresh trigger for the stats snapshot. Runs on a binder thread, so it
     * only schedules a coroutine; the snapshot itself is read on Dispatchers.Default.
     */
    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) = scheduleStatsRefresh()

        override fun onLost(network: Network) = scheduleStatsRefresh()

        override fun onCapabilitiesChanged(
            network: Network,
            networkCapabilities: NetworkCapabilities,
        ) = scheduleStatsRefresh()
    }

    /**
     * Wi-Fi changes must refresh the snapshot even when the Wi-Fi link is not
     * the *default* network (no internet, or a VPN/cellular link is active).
     * The default-network callback alone misses exactly those cases, which is
     * how the app used to keep saying "no Wi-Fi" on a perfectly good LAN.
     */
    private val wifiNetworkRequest: NetworkRequest = NetworkRequest.Builder()
        .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
        .build()

    init {
        refreshStats()
        runCatching {
            connectivityManager?.registerDefaultNetworkCallback(networkCallback)
        }
        runCatching {
            connectivityManager?.registerNetworkCallback(wifiNetworkRequest, networkCallback)
        }
    }

    /**
     * The single state stream the UI collects: scan state merged with the
     * latest persisted labels. Saving a label recomposes the list instantly.
     */
    val uiState: StateFlow<ScanUiState> = combine(_scanState, labelMap) { state, labels ->
        when (state) {
            is ScanUiState.Idle -> state
            is ScanUiState.Loading -> state.copy(devices = state.devices.withLabels(labels))
            is ScanUiState.Success -> state.copy(devices = state.devices.withLabels(labels))
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
        refreshStats() // the network may have changed since the last scan

        // The permission can be revoked in system settings while the app is
        // alive; fail with a clear message instead of a generic exception.
        if (!hasScannerPermissions(getApplication())) {
            _scanState.value = ScanUiState.Error(messageFor(NetworkScanner.ScanError.PermissionDenied))
            return
        }

        scanJob = viewModelScope.launch {
            val startedAt = System.currentTimeMillis()
            _scanState.value = ScanUiState.Loading(phase = "Detectando la red…")
            try {
                var last: NetworkScanner.ScanUpdate? = null
                scanner.scan().collect { update ->
                    last = update
                    scanDevices.value = update.devices
                    _scanState.value = ScanUiState.Loading(
                        phase = update.phase,
                        devices = update.devices,
                        note = update.note,
                    )
                }
                // A completed scan is always a success: degraded detection is
                // surfaced as a note, never as an error screen.
                _scanState.value = ScanUiState.Success(
                    devices = scanDevices.value,
                    note = last?.note,
                    elapsedMillis = System.currentTimeMillis() - startedAt,
                )
                refreshStats()
            } catch (e: CancellationException) {
                throw e // structured concurrency: never swallow cancellation
            } catch (e: NetworkScanner.ScanException) {
                _scanState.value = ScanUiState.Error(messageFor(e.error))
            } catch (e: Exception) {
                // Unexpected failure of a non-essential method: keep whatever
                // we already found and warn, instead of showing an error.
                val found = scanDevices.value
                if (found.isEmpty()) {
                    _scanState.value = ScanUiState.Error(
                        e.message ?: "El escaneo falló de forma inesperada."
                    )
                } else {
                    _scanState.value = ScanUiState.Success(
                        devices = found,
                        note = "El escaneo terminó con advertencias: " +
                            (e.message ?: e.javaClass.simpleName),
                        elapsedMillis = System.currentTimeMillis() - startedAt,
                    )
                }
            }
        }
    }

    /**
     * Re-reads the Wi-Fi/LAN statistics off the main thread. Called on start,
     * at the end of every scan, when the Network section becomes visible and
     * whenever the system reports a network change.
     */
    fun refreshStats() {
        loadStats(delayMillis = 0)
    }

    /** Coalesces bursts of network callbacks into one snapshot read. */
    private fun scheduleStatsRefresh() {
        loadStats(delayMillis = 400)
    }

    private fun loadStats(delayMillis: Long) {
        statsJob?.cancel()
        statsJob = viewModelScope.launch {
            if (delayMillis > 0) delay(delayMillis)
            val snapshot = withContext(Dispatchers.Default) {
                NetworkStatistics.snapshot(getApplication())
            }
            _stats.value = snapshot
        }
    }

    /** Maps a typed scan failure to a user-readable message. */
    private fun messageFor(error: NetworkScanner.ScanError): String = when (error) {
        NetworkScanner.ScanError.WifiOff ->
            "El Wi-Fi está desactivado. Actívalo e inténtalo de nuevo."
        NetworkScanner.ScanError.NotOnWifi ->
            if (!LocalNetworkInfo.isOnWifi(getApplication())) {
                "No estás conectado a una red Wi-Fi. Conéctate e inténtalo de nuevo."
            } else {
                "No se pudo acceder a la red. Inténtalo de nuevo."
            }
        NetworkScanner.ScanError.PermissionDenied ->
            "El escaneo necesita el permiso de ubicación. Concédelo e inténtalo de nuevo."
        NetworkScanner.ScanError.Unknown ->
            "El escaneo falló de forma inesperada."
    }

    /** Cancels a running scan (UI "Detener" action); results keep what we have. */
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
        refreshStats()
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

    override fun onCleared() {
        statsJob?.cancel()
        runCatching {
            connectivityManager?.unregisterNetworkCallback(networkCallback)
        }
        super.onCleared()
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
