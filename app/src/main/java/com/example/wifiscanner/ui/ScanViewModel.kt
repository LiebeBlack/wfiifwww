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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
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
 *  - Keep [stats] current: a default-network callback plus a Wi-Fi transport
 *    request refresh the snapshot when the link changes (connect, disconnect,
 *    VPN on/off, new SSID), debounced so a burst of capability updates does
 *    not spam the CPU. [startMonitoring]/[stopMonitoring] tie the
 *    registration to the visible lifecycle and [onCleared] unregisters as a
 *    final safety net, so the callbacks cannot leak.
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

    /** Reactive key (MAC or IP) -> label map from Room. */
    private val labelMap = labelRepository.observeLabelMap()

    /** Reactive key (MAC or IP) -> nickname map from Room. */
    private val nicknameMap = labelRepository.observeNicknameMap()

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
     * Builds a refresh trigger for the stats snapshot.
     *
     * Callbacks arrive on a binder thread, so they only schedule a coroutine;
     * the snapshot itself is read on `Dispatchers.IO`.
     *
     * Each registration needs its **own** callback object: the platform keeps
     * one registration record per callback, so registering the same instance
     * for two requests is rejected ("Callback already registered") and, even
     * when accepted, a single [stopMonitoring] could not release both.
     */
    private fun newRefreshCallback(): ConnectivityManager.NetworkCallback =
        object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) = scheduleStatsRefresh()

            override fun onLost(network: Network) = scheduleStatsRefresh()

            override fun onCapabilitiesChanged(
                network: Network,
                networkCapabilities: NetworkCapabilities,
            ) = scheduleStatsRefresh()
        }

    /** Fires when the default (internet-carrying) network changes. */
    private val defaultNetworkCallback = newRefreshCallback()

    /** Fires when any Wi-Fi link appears/disappears, even if it is not default. */
    private val wifiNetworkCallback = newRefreshCallback()

    /**
     * Wi-Fi changes must refresh the snapshot even when the Wi-Fi link is not
     * the *default* network (no internet, or a VPN/cellular link is active).
     * The default-network callback alone misses exactly those cases, which is
     * how the app used to keep saying "no Wi-Fi" on a perfectly good LAN.
     */
    private val wifiNetworkRequest: NetworkRequest = NetworkRequest.Builder()
        .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
        .build()

    /** True while the callbacks are registered (guards double registration). */
    private var monitoring = false

    /**
     * Registers the connectivity callbacks. Called when the UI becomes visible
     * (`ON_START`) and paired with [stopMonitoring] on `ON_STOP`, which is the
     * lifecycle that actually matches the resource: a registered network
     * callback keeps a binder connection and wakes the process on every link
     * change, so it must not outlive the visible screen.
     *
     * Registering the same callback twice throws `IllegalArgumentException`, so
     * the call is idempotent.
     */
    fun startMonitoring() {
        if (monitoring) return
        refreshStats()
        val defaultRegistered = runCatching {
            connectivityManager?.registerDefaultNetworkCallback(defaultNetworkCallback)
            connectivityManager != null
        }.getOrDefault(false)
        val wifiRegistered = runCatching {
            // Also listen to Wi-Fi that is not the default network: a VPN or a
            // Wi-Fi without internet would otherwise never trigger a refresh.
            connectivityManager?.registerNetworkCallback(wifiNetworkRequest, wifiNetworkCallback)
            connectivityManager != null
        }.getOrDefault(false)
        // If neither registration worked (no ConnectivityManager on a
        // non-standard ROM) the next ON_START retries instead of silently
        // treating the app as "monitoring" forever.
        monitoring = defaultRegistered || wifiRegistered
    }

    /** Unregisters the callbacks and drops any debounced refresh. */
    fun stopMonitoring() {
        if (!monitoring) return
        monitoring = false
        statsJob?.cancel()
        statsJob = null
        // Each callback owns its own registration, so both must be released:
        // leaving the Wi-Fi one registered would keep a binder callback (and
        // its process wakeups) alive after the screen is gone.
        runCatching { connectivityManager?.unregisterNetworkCallback(defaultNetworkCallback) }
        runCatching { connectivityManager?.unregisterNetworkCallback(wifiNetworkCallback) }
    }

    /**
     * The single state stream the UI collects: scan state merged with the
     * latest persisted labels and nicknames. Saving a label or nickname recomposes the list instantly.
     */
    val uiState: StateFlow<ScanUiState> = combine(_scanState, labelMap, nicknameMap) { state, labels, nicknames ->
        when (state) {
            is ScanUiState.Idle -> state
            is ScanUiState.Loading -> state.copy(devices = state.devices.withLabelsAndNicknames(labels, nicknames))
            is ScanUiState.Success -> state.copy(devices = state.devices.withLabelsAndNicknames(labels, nicknames))
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

        // Scanning itself does not need the location permission: sockets,
        // multicast and the neighbour table work without it. Only the SSID is
        // redacted, and the UI explains how to fix that. Blocking the scan here
        // was what made a denied permission look like a broken scanner.

        scanJob = viewModelScope.launch {
            val startedAt = System.currentTimeMillis()
            _scanState.value = ScanUiState.Loading(phase = "Detectando la red…")
            try {
                var last: NetworkScanner.ScanUpdate? = null
                scanner.scan().collect { update ->
                    // A cancelled scan (new scan started, or "Detener") must
                    // never write another frame: the flow emits from an IO
                    // thread, so without this guard a late emission could
                    // overwrite the state the user just switched to.
                    if (!currentCoroutineContext().isActive) return@collect
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
                        e.message ?: "El análisis falló de forma inesperada."
                    )
                } else {
                    _scanState.value = ScanUiState.Success(
                        devices = found,
                        note = "El análisis terminó con advertencias: " +
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
        // A callback delivered while the UI is stopping must not schedule work
        // that would read the link after the screen is gone.
        if (!monitoring) return
        loadStats(delayMillis = 400)
    }

    /**
     * Set when a refresh is requested while another one is pending or in
     * flight. Touched only from the main thread (UI, lifecycle effects and the
     * connectivity callbacks all arrive there), so no locking is needed.
     */
    private var statsDirty = false

    private fun loadStats(delayMillis: Long) {
        val pending = statsJob
        if (pending != null && pending.isActive) {
            // Never restart a pending debounce: capability callbacks can fire
            // faster than the window, and cancelling on every one postponed
            // the read indefinitely (a permanently stale network header).
            // Remember the request instead — the running job re-runs once.
            statsDirty = true
            return
        }
        statsDirty = false
        statsJob = viewModelScope.launch {
            if (delayMillis > 0) delay(delayMillis)
            // Absorb bursts of callbacks with one extra read instead of one
            // job per callback. The body runs on the main dispatcher; only the
            // snapshot itself moves to the IO pool (interface enumeration and
            // the OUI asset read are blocking I/O).
            do {
                statsDirty = false
                val snapshot = withContext(Dispatchers.IO) {
                    NetworkStatistics.snapshot(getApplication())
                }
                _stats.value = snapshot
            } while (statsDirty)
        }
    }

    /** Maps a typed scan failure to a user-readable message. */
    private fun messageFor(error: NetworkScanner.ScanError): String = when (error) {
        NetworkScanner.ScanError.WifiOff ->
            "El Wi-Fi está desactivado. Actívelo e inténtelo de nuevo."
        NetworkScanner.ScanError.NotOnWifi ->
            if (!LocalNetworkInfo.isOnWifi(getApplication())) {
                "No hay conexión a una red Wi-Fi. Conéctese e inténtelo de nuevo."
            } else {
                "No se pudo acceder a la red. Inténtelo de nuevo."
            }
        NetworkScanner.ScanError.PermissionDenied ->
            "El análisis necesita el permiso de ubicación. Concédalo e inténtelo de nuevo."
        NetworkScanner.ScanError.Unknown ->
            "El análisis falló de forma inesperada."
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
     * Persists a location label for a device. Blank text deletes the label.
     * The reactive [labelMap] pushes the change back into [uiState].
     */
    fun saveLocation(mac: String?, ip: String, label: String) {
        viewModelScope.launch { labelRepository.saveLabel(mac, ip, label) }
    }

    /**
     * Persists a nickname/apodo for a device. Blank text deletes the nickname.
     * The reactive [nicknameMap] pushes the change back into [uiState].
     */
    fun saveNickname(mac: String?, ip: String, nickname: String) {
        viewModelScope.launch { labelRepository.saveNickname(mac, ip, nickname) }
    }

    /**
     * Persists both location label and nickname for a device in one transaction.
     */
    fun saveLocationAndNickname(mac: String?, ip: String, label: String, nickname: String) {
        viewModelScope.launch { labelRepository.saveLabelAndNickname(mac, ip, label, nickname) }
    }

    /** Applies the label and nickname maps onto a scan result list (MAC/IP-keyed merge). */
    private fun List<NetworkDevice>.withLabelsAndNicknames(
        labels: Map<String, String>,
        nicknames: Map<String, String>
    ): List<NetworkDevice> =
        if (labels.isEmpty() && nicknames.isEmpty()) this
        else map { device ->
            val key = device.mac ?: device.ip
            device.copy(
                location = labels[key]?.takeIf { it.isNotBlank() } ?: device.location,
                nickname = nicknames[key]?.takeIf { it.isNotBlank() } ?: device.nickname
            )
        }

    override fun onCleared() {
        scanJob?.cancel()
        statsJob?.cancel()
        stopMonitoring()
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
